package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.CoordinateMoveJobExecution;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.FallDamageBudget;
import dev.aod.mcmcp.agent.safety.GroundTraversal;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Prepares the actual vanilla posture, then executes only observed routes. */
final class MinecraftV2MoveDriver implements CoordinateMoveJobExecution.MovementDriver {
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final V2MoveArguments request;
    private final UUID owner = UUID.randomUUID();
    private final MinecraftActionPrimitiveExecutor executor = new MinecraftActionPrimitiveExecutor(
            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
    private MovementInputLease posture;
    private long postureStarted = -1;
    private RoutePlan pendingRoute;
    private double pendingTolerance;
    private final Locomotion mode;
    private long takeoffStarted = -1;
    private net.minecraft.world.phys.Vec3 takeoffTarget;
    private final FallDamageBudget fallBudget;
    private PlayerDamageSignals.Snapshot damageEvidence;
    private long arcFallBaseline;
    private RoutePlan wholeRoute;
    private int nextEdge, chunkEnd;
    private boolean aligning;
    private MinecraftGroundTraversalExecutor arc;
    private int jumps, drops;
    private dev.aod.mcmcp.agent.action.ExactPositionPrimitive exactApproach;
    private boolean routeEndsAtRequestedCell;

    MinecraftV2MoveDriver(Minecraft minecraft, V2MoveArguments request) {
        this.minecraft = minecraft;
        this.request = request;
        mode = Locomotion.aerialMode(minecraft.player);
        executor.sneakWhileMoving(request.sneak());
        fallBudget = new FallDamageBudget(request.maxFallDamage());
        var player = minecraft.player;
        PlayerDamageSignals.global().health(player, player.getHealth(), player.getAbsorptionAmount());
        damageEvidence = PlayerDamageSignals.global().bind(player, player.getHealth(), player.getAbsorptionAmount());
    }

    KnownTraversabilitySnapshot augment(KnownTraversabilitySnapshot map) {
        return active() && !aligning || request.sneak() ? map : MinecraftGroundTransferMap.augment(map, minecraft.player, fallBudget.remaining());
    }

    boolean currentTraversalSafe() { return arc != null && arc.currentSafe(); }

    String observeDamage() {
        var player = minecraft.player;
        PlayerDamageSignals.global().health(player, player.getHealth(), player.getAbsorptionAmount());
        var fresh = PlayerDamageSignals.global().bind(player, player.getHealth(), player.getAbsorptionAmount());
        double loss = fresh.loss() - damageEvidence.loss();
        boolean other = fresh.other() != damageEvidence.other();
        boolean confirmed = arc != null && fresh.falls() > arcFallBaseline && !other;
        damageEvidence = fresh;
        if (!fallBudget.record(loss, confirmed)) return "fall_damage_budget_or_cause_mismatch";
        return other ? "unexpected_damage" : null;
    }

    @Override public java.util.Map<String, Object> result() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.putAll(java.util.Map.of("max_fall_damage", request.maxFallDamage(), "fall_damage_observed", fallBudget.observed(),
                "fall_damage_budget_used", fallBudget.spent(), "gap_jumps", jumps, "drops", drops));
        var player = minecraft.player;
        if (player != null) result.put("position", java.util.Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
        if (request.exactPosition() != null) result.put("requested_position", java.util.Map.of(
                "x", request.exactPosition().x, "y", request.exactPosition().y, "z", request.exactPosition().z));
        return java.util.Map.copyOf(result);
    }

    @Override
    public boolean prepare(long clientTick, double remainingDistance, BooleanSupplier outputAllowed) {
        if (mode != Locomotion.aerialMode(minecraft.player))
            throw new IllegalStateException("navigation mode changed");
        if (mode.aerial()) return prepareFlight(clientTick, remainingDistance, outputAllowed);
        if (!request.sneak() || minecraft.player.isInWater()) return true;
        if (!outputAllowed.getAsBoolean()) return false;
        if (posture == null) {
            posture = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
            postureStarted = clientTick;
        }
        posture.setDesired(owner, Set.of(MovementInputLease.MovementKey.CROUCH));
        if (!posture.heartbeat(owner, System.nanoTime(), HORIZON))
            throw new IllegalStateException("posture lease expired");
        // Wait for vanilla and the local projector to observe the smaller actual body.
        return clientTick - postureStarted >= 3 && minecraft.player.getBoundingBox().getYsize() <= 1.51D;
    }

    private boolean prepareFlight(long tick, double remainingDistance, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean()) return false;
        var player = minecraft.player;
        if (player.getAbilities().flying) {
            releasePosture();
            takeoffStarted = -1;
            return true;
        }
        if (takeoffStarted < 0) {
            takeoffStarted = tick;
            takeoffTarget = player.getBoundingBox().getCenter().add(0, Locomotion.FLIGHT_FEET_OFFSET, 0);
        }
        long elapsed = tick - takeoffStarted;
        if (elapsed > 30) throw new IllegalStateException("flight did not activate");
        if (posture == null) posture = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
        // Start from neutral input, then send a normal double jump. Check live abilities before
        // every pulse so an already active flight is never accidentally toggled off.
        boolean jump = elapsed == 1 || elapsed >= 3 && elapsed <= 5;
        posture.setDesired(owner, jump ? Set.of(MovementInputLease.MovementKey.JUMP) : Set.of());
        if (!posture.heartbeat(owner, System.nanoTime(), HORIZON)) throw new IllegalStateException("flight lease expired");
        var observed = LocalObservationVolume.global().latestFor(player).orElseThrow();
        AgentInputState.global().requireNavigationMovementSafety(player, player.level(),
                observed.worldRevision(), remainingDistance, new AgentInputState.NavigationIntent(
                        takeoffTarget, 1, mode, request.tolerance()));
        return false;
    }

    @Override
    public void begin(RoutePlan route, double tolerance) {
        routeEndsAtRequestedCell = route.cells().getLast().equals(request.goal());
        if (route.edges().stream().noneMatch(edge -> edge.locomotion().ballistic())) {
            beginOrdinary(route, tolerance);
            return;
        }
        wholeRoute = route; nextEdge = 0; pendingTolerance = tolerance;
        beginChunk();
    }

    private void beginChunk() {
        var edge = wholeRoute.edges().get(nextEdge);
        if (edge.locomotion().ballistic()) {
            aligning = true;
            // Center on supported ground before committing to the arc.
            beginOrdinary(new RoutePlan(wholeRoute.worldSessionId(), wholeRoute.dimension(), wholeRoute.worldRevision(),
                    java.util.List.of(edge.key().from()), java.util.List.of(), 0, 0, 36, 1800), 0.12);
        } else {
            chunkEnd = nextEdge + 1;
            while (chunkEnd < wholeRoute.edges().size() && !wholeRoute.edges().get(chunkEnd).locomotion().ballistic()) chunkEnd++;
            beginOrdinary(wholeRoute.segment(nextEdge, chunkEnd), 0.12);
        }
    }

    private void beginOrdinary(RoutePlan route, double tolerance) {
        if (mode.aerial()) {
            executor.beginNavigate(route, tolerance);
            return;
        }
        if (!route.edges().isEmpty() && route.edges().stream().noneMatch(edge -> edge.locomotion() == Locomotion.WATER)) {
            var from = route.cells().getFirst();
            var next = route.cells().get(1);
            if (from.x() != next.x() || from.z() != next.z()) {
                double x = minecraft.player.getX() + next.x() - from.x();
                double y = minecraft.player.getEyeY();
                double z = minecraft.player.getZ() + next.z() - from.z();
                var cell = BlockPos.containing(x, y, z);
                pendingRoute = route;
                pendingTolerance = tolerance;
                executor.beginFace(new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        route.worldSessionId(), route.worldRevision(),
                        new ActionDsl.Position(route.dimension(), cell.getX(), cell.getY(), cell.getZ()),
                        x, y, z, true), 60);
                return;
            }
        }
        executor.beginNavigate(route, tolerance);
    }

    @Override
    public MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map,
            double remainingDistance, long clientTick, BooleanSupplier outputAllowed) {
        if (mode != Locomotion.aerialMode(minecraft.player)) {
            close();
            return new MinecraftActionPrimitiveExecutor.TickResult(
                    MinecraftActionPrimitiveExecutor.Status.FAILED,
                    MinecraftActionPrimitiveExecutor.Reason.UNSUPPORTED_LOCOMOTION);
        }
        if (exactApproach != null) return exactApproach.tick(minecraft, map, remainingDistance, clientTick, outputAllowed);
        var step = tickRoute(map, remainingDistance, clientTick, outputAllowed);
        if (step.status() == MinecraftActionPrimitiveExecutor.Status.SUCCEEDED
                && routeEndsAtRequestedCell && request.exactPosition() != null) {
            executor.close();
            exactApproach = new dev.aod.mcmcp.agent.action.ExactPositionPrimitive(
                    request.exactPosition(), request.tolerance(), request.sneak());
            return exactApproach.tick(minecraft, map, remainingDistance, clientTick, outputAllowed);
        }
        return step;
    }

    private MinecraftActionPrimitiveExecutor.TickResult tickRoute(KnownTraversabilitySnapshot map,
            double remainingDistance, long clientTick, BooleanSupplier outputAllowed) {
        if (arc != null) {
            var step = arc.tick(map, remainingDistance, clientTick, outputAllowed);
            if (step.status() != MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) return step;
            arc.close(); arc = null; fallBudget.finish();
            if (wholeRoute.edges().get(nextEdge).locomotion() == Locomotion.GAP_JUMP) jumps++; else drops++;
            nextEdge++;
            return nextChunk();
        }
        if (pendingRoute != null && posture != null) prepare(clientTick, remainingDistance, outputAllowed);
        else releasePosture();
        var step = executor.tick(minecraft, map, LocalObservationVolume.global(),
                remainingDistance, 1_080.0D, clientTick, outputAllowed);
        if (pendingRoute != null && step.status() == MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
            executor.beginNavigate(pendingRoute, pendingTolerance);
            pendingRoute = null;
            return new MinecraftActionPrimitiveExecutor.TickResult(
                    MinecraftActionPrimitiveExecutor.Status.RUNNING, MinecraftActionPrimitiveExecutor.Reason.NONE);
        }
        if (wholeRoute != null && step.status() == MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
            executor.close();
            if (aligning) {
                aligning = false;
                var edge = wholeRoute.edges().get(nextEdge);
                var plan = GroundTraversal.discover(minecraft.player, minecraft.player.getBoundingBox(),
                        edge.key().to().x(), edge.key().to().z(), edge.locomotion(), fallBudget.remaining()).orElse(null);
                if (plan == null || Math.abs(plan.landing().minY - edge.key().to().y()) > 0.21
                        || !fallBudget.begin(plan.predictedDamage(minecraft.player)))
                    return new MinecraftActionPrimitiveExecutor.TickResult(MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED,
                            MinecraftActionPrimitiveExecutor.Reason.ROUTE_EDGE_CHANGED);
                arcFallBaseline = damageEvidence.falls();
                arc = new MinecraftGroundTraversalExecutor(minecraft, plan);
                return arc.tick(map, remainingDistance, clientTick, outputAllowed);
            }
            nextEdge = chunkEnd;
            return nextChunk();
        }
        return step;
    }

    private MinecraftActionPrimitiveExecutor.TickResult nextChunk() {
        if (nextEdge >= wholeRoute.edges().size()) {
            wholeRoute = null;
            return new MinecraftActionPrimitiveExecutor.TickResult(MinecraftActionPrimitiveExecutor.Status.SUCCEEDED, MinecraftActionPrimitiveExecutor.Reason.NONE);
        }
        beginChunk();
        return new MinecraftActionPrimitiveExecutor.TickResult(MinecraftActionPrimitiveExecutor.Status.RUNNING, MinecraftActionPrimitiveExecutor.Reason.NONE);
    }

    @Override
    public boolean active() { return executor.active() || wholeRoute != null || arc != null || exactApproach != null; }

    public boolean arrived(dev.aod.mcmcp.agent.navigation.NavCell current,
            dev.aod.mcmcp.agent.navigation.NavCell goal, double radius, double tolerance) {
        var player = minecraft.player;
        if (request.exactPosition() != null && goal.equals(request.goal())) {
            return dev.aod.mcmcp.agent.action.ExactPositionPrimitive.reached(player.position(), request.exactPosition(), tolerance, radius)
                    && (mode.aerial() || player.isInWater() || player.onGround())
                    && (!player.isInWater() || MinecraftActionPrimitiveExecutor.waterBreathingRecovered(
                            player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), player.getAirSupply(), player.getMaxAirSupply()));
        }
        double horizontal = Math.hypot(player.getX() - goal.x() - 0.5D, player.getZ() - goal.z() - 0.5D);
        double y = goal.y() + (mode.aerial() ? Locomotion.FLIGHT_FEET_OFFSET : 0);
        return horizontal <= radius + tolerance && Math.abs(player.getY() - y) <= radius + 0.21D
                && (!player.isInWater() || MinecraftActionPrimitiveExecutor.waterBreathingRecovered(
                        player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), player.getAirSupply(), player.getMaxAirSupply()))
                && (player.isInWater() || mode.aerial() || player.onGround());
    }

    private void releasePosture() {
        if (posture != null) { posture.close(); posture = null; }
    }

    @Override
    public void close() {
        try { executor.close(); }
        finally {
            releasePosture(); pendingRoute = null; wholeRoute = null; aligning = false;
            if (exactApproach != null) { exactApproach.close(); exactApproach = null; }
            if (arc != null) { arc.close(); arc = null; fallBudget.finish(); }
        }
    }
}
