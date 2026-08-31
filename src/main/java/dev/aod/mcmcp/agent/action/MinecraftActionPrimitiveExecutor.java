package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Client-thread executor for the movement and camera primitives in Action DSL v1.
 *
 * <p>All terrain decisions come from the supplied immutable Known Traversability Map snapshot.
 * This adapter deliberately reads only the local player's pose; it never queries live blocks,
 * entities, the camera frustum, focus, or the current Screen to fill an evidence gap.</p>
 */
public final class MinecraftActionPrimitiveExecutor implements AutoCloseable {
    public static final float MAX_ALLOWED_CAMERA_DEGREES_PER_TICK = 18.0F;
    public static final int STALL_TICKS = 20;
    static final int SETTLE_STABLE_TICKS = 10;
    static final int SETTLE_SAFETY_TICKS = 3;
    private static final float AIM_TOLERANCE_DEGREES = 0.75F;
    private static final double PROGRESS_EPSILON_BLOCKS = 0.03D;
    private static final double STEP_EPSILON = 1.0E-7D;
    private static final double SETTLE_DRIFT_EPSILON_SQUARED = 4.0E-6D;
    private static final double INTERMEDIATE_WAYPOINT_TOLERANCE = 0.32D;
    private static final double ROUTE_CORRIDOR_RADIUS = 0.85D;
    private static final double ROUTE_VERTICAL_MARGIN = 1.25D;
    private static final Duration LEASE_HORIZON = Duration.ofMillis(500);

    private final UUID ownerId = UUID.randomUUID();
    private final float maxCameraDegreesPerTick;
    private NavigateState navigation;
    private FaceState face;
    private MovementInputLease movement;
    private long lastClientTick = -1;

    /** @param maxCameraDegreesPerTick configured degrees/second divided by 20 client ticks */
    public MinecraftActionPrimitiveExecutor(float maxCameraDegreesPerTick) {
        requireCameraLimit(maxCameraDegreesPerTick);
        this.maxCameraDegreesPerTick = maxCameraDegreesPerTick;
    }

    public void beginNavigate(RoutePlan route, double tolerance) {
        requireIdle();
        Objects.requireNonNull(route, "route");
        if (!Double.isFinite(tolerance) || tolerance < 0.1D || tolerance > 1.5D) {
            throw new IllegalArgumentException("navigation tolerance must be within 0.1..1.5");
        }
        if (route.distanceBlocks() > 32.0D) {
            throw new IllegalArgumentException("navigation route exceeds the Action DSL limit");
        }
        navigation = new NavigateState(route, tolerance);
    }

    /**
     * Starts a face primitive from evidence already accepted by the observation policy.
     * Constructing this plan is the caller's explicit proof that the target is known.
     */
    public void beginFace(KnownFaceTarget target, long tickUpperBound) {
        requireIdle();
        if (tickUpperBound < 1L || tickUpperBound > 600L) {
            throw new IllegalArgumentException("face tickUpperBound must be within 1..600");
        }
        face = new FaceState(Objects.requireNonNull(target, "target"), tickUpperBound);
    }

    /** Executes at most one bounded camera update and one movement heartbeat. */
    public TickResult tick(
            Minecraft minecraft,
            KnownTraversabilitySnapshot currentSnapshot,
            LocalObservationVolume movementSafety,
            double remainingDistance,
            double remainingCameraDegrees,
            long clientTick,
            BooleanSupplier outputAllowed) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        Objects.requireNonNull(movementSafety, "movementSafety");
        Objects.requireNonNull(outputAllowed, "outputAllowed");
        if (!Double.isFinite(remainingDistance) || remainingDistance < 0.0D
                || !Double.isFinite(remainingCameraDegrees) || remainingCameraDegrees < 0.0D) {
            throw new IllegalArgumentException("remaining motion budgets must be finite and non-negative");
        }
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Action primitive execution must run on the client thread");
        }
        if (clientTick < 0 || clientTick <= lastClientTick) {
            throw new IllegalArgumentException("clientTick must increase for every executor tick");
        }
        if (navigation == null && face == null) {
            throw new IllegalStateException("No Action DSL primitive is active");
        }
        lastClientTick = clientTick;
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return finish(Status.FAILED, Reason.WORLD_UNAVAILABLE);
        }
        try {
            return navigation != null
                    ? tickNavigation(
                            minecraft,
                            player,
                            currentSnapshot,
                            movementSafety,
                            remainingDistance,
                            clientTick,
                            outputAllowed)
                    : tickFace(
                            player,
                            currentSnapshot,
                            remainingCameraDegrees,
                            clientTick,
                            outputAllowed);
        } catch (RuntimeException | LinkageError failure) {
            try {
                close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public boolean active() {
        return navigation != null || face != null || movement != null;
    }

    private TickResult tickNavigation(
            Minecraft minecraft,
            LocalPlayer player,
            KnownTraversabilitySnapshot snapshot,
            LocalObservationVolume movementSafety,
            double remainingDistance,
            long clientTick,
            BooleanSupplier outputAllowed) {
        NavigateState state = navigation;
        if (!state.route.worldSessionId().equals(snapshot.worldSessionId())
                || !state.route.dimension().equals(snapshot.dimension())) {
            return finish(Status.FAILED, Reason.WORLD_BOUNDARY_CHANGED);
        }
        if (player.isPassenger() || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying) {
            return finish(Status.REPLAN_REQUIRED, Reason.UNSUPPORTED_LOCOMOTION);
        }

        NavCell finalCell = state.route.cells().getLast();
        if (state.settling) {
            return tickNavigationSettlement(
                    minecraft, player, snapshot, movementSafety,
                    remainingDistance, clientTick, outputAllowed);
        }
        if (state.route.edges().isEmpty() && !sameCellRouteCurrent(state.route, snapshot)) {
            return finish(Status.REPLAN_REQUIRED, Reason.ROUTE_EDGE_CHANGED);
        }
        if (state.route.edges().isEmpty()) {
            switch (sameCellDecision(
                    player.getX(), player.getY(), player.getZ(), finalCell, state.tolerance)) {
                case OFF_ROUTE -> {
                    return finish(Status.REPLAN_REQUIRED, Reason.PLAYER_OFF_ROUTE);
                }
                case SETTLE -> {
                    state.beginSettlement();
                    return tickNavigationSettlement(
                            minecraft,
                            player,
                            snapshot,
                            movementSafety,
                            remainingDistance,
                            clientTick,
                            outputAllowed);
                }
                case DRIVE -> { }
            }
            return driveNavigationWaypoint(
                    minecraft,
                    player,
                    snapshot,
                    movementSafety,
                    remainingDistance,
                    clientTick,
                    outputAllowed,
                    finalCell,
                    state.tolerance,
                    0,
                    Locomotion.GROUND,
                    EdgeDecision.CONFIRMED);
        }

        TraversabilityEdge planned = state.route.edges().get(state.edgeIndex);
        if (!insideRouteCorridor(player, planned.key())) {
            return finish(Status.REPLAN_REQUIRED, Reason.PLAYER_OFF_ROUTE);
        }

        NavCell waypoint = planned.key().to();
        double waypointTolerance = state.edgeIndex == state.route.edges().size() - 1
                ? state.tolerance : INTERMEDIATE_WAYPOINT_TOLERANCE;
        if (atWaypoint(player, waypoint, waypointTolerance)) {
            state.edgeIndex++;
            state.resetProgress();
            if (state.edgeIndex == state.route.edges().size()) {
                state.beginSettlement();
                return tickNavigationSettlement(
                        minecraft, player, snapshot, movementSafety,
                        remainingDistance, clientTick, outputAllowed);
            }
            planned = state.route.edges().get(state.edgeIndex);
            if (!insideRouteCorridor(player, planned.key())) {
                return finish(Status.REPLAN_REQUIRED, Reason.PLAYER_OFF_ROUTE);
            }
            waypoint = planned.key().to();
        }

        EdgeDecision edge = edgeDecision(state.route, state.edgeIndex, snapshot);
        if (edge == EdgeDecision.REPLAN) {
            return finish(Status.REPLAN_REQUIRED, Reason.ROUTE_EDGE_CHANGED);
        }
        int verticalDelta = effectiveVerticalDelta(
                Integer.compare(waypoint.y(), planned.key().from().y()),
                planned.locomotion(),
                planned.targetSupport(),
                waypoint.y() - player.getY());
        return driveNavigationWaypoint(
                minecraft,
                player,
                snapshot,
                movementSafety,
                remainingDistance,
                clientTick,
                outputAllowed,
                waypoint,
                waypointTolerance,
                verticalDelta,
                planned.locomotion(),
                edge);
    }

    private TickResult driveNavigationWaypoint(
            Minecraft minecraft,
            LocalPlayer player,
            KnownTraversabilitySnapshot snapshot,
            LocalObservationVolume movementSafety,
            double remainingDistance,
            long clientTick,
            BooleanSupplier outputAllowed,
            NavCell waypoint,
            double waypointTolerance,
            int verticalDelta,
            Locomotion locomotion,
            EdgeDecision edge) {
        NavigateState state = navigation;
        state.activeTicks++;
        if (!navigationOutputAllowed(state.activeTicks, state.route.tickUpperBound())) {
            return finish(Status.REPLAN_REQUIRED, Reason.PRIMITIVE_TICK_BUDGET_EXHAUSTED);
        }
        if (remainingDistance <= 0.0D) {
            return finish(Status.REPLAN_REQUIRED, Reason.MOTION_BUDGET_EXHAUSTED);
        }
        // navigate_to_known owns movement only. Relative steering preserves the player's view;
        // a caller that wants camera motion must declare and execute face_known_position.
        Set<MovementInputLease.MovementKey> desired = steering(
                player.getX(), player.getZ(), player.getYRot(), waypoint, waypointTolerance);
        desired = withVerticalInput(
                desired,
                verticalDelta,
                waypoint.y() - player.getY(),
                player.maxUpStep(),
                locomotion);
        Vec3 command = commandDirection(player.getYRot(), desired);
        if (command.horizontalDistanceSqr() > 0.0D) {
            double previewLength = Math.min(1.0D, horizontalDistance(player, waypoint));
            Vec3 preview = command.scale(previewLength);
            if (!movementSafety.canPreviewGoalMovement(
                    player, preview, snapshot.worldRevision())) {
                return finish(Status.REPLAN_REQUIRED, Reason.UNVERIFIED_MOVEMENT_VECTOR);
            }
        }
        state.observeProgress(navigationDistance(player, waypoint, locomotion), clientTick);
        if (state.stalled(clientTick)) {
            return finish(Status.REPLAN_REQUIRED, Reason.MOVEMENT_STALLED);
        }
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        long outputNanos = System.nanoTime();
        if (movement == null) {
            movement = MovementInputLease.acquire(
                    minecraft, ownerId, outputNanos, LEASE_HORIZON);
        }
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        outputNanos = System.nanoTime();
        movement.setDesired(ownerId, desired);
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        outputNanos = System.nanoTime();
        if (!movement.heartbeat(ownerId, outputNanos, LEASE_HORIZON)) {
            movement = null;
            return finish(Status.FAILED, Reason.MOVEMENT_LEASE_EXPIRED);
        }
        if (locomotion == Locomotion.GROUND) {
            AgentInputState.global().requireGoalMovementSafety(
                    player, player.level(), snapshot.worldRevision(), remainingDistance);
        } else {
            AgentInputState.global().requireNavigationMovementSafety(
                    player,
                    player.level(),
                    snapshot.worldRevision(),
                    remainingDistance,
                    new AgentInputState.NavigationIntent(
                            new Vec3(
                                    waypoint.x() + 0.5D,
                                    waypoint.y() + player.getBbHeight() * 0.5D,
                                    waypoint.z() + 0.5D),
                            verticalDelta,
                            locomotion,
                            waypointTolerance));
        }
        return runningNavigationResult(edge, !desired.isEmpty());
    }

    private TickResult tickNavigationSettlement(
            Minecraft minecraft,
            LocalPlayer player,
            KnownTraversabilitySnapshot snapshot,
            LocalObservationVolume movementSafety,
            double remainingDistance,
            long clientTick,
            BooleanSupplier outputAllowed) {
        NavigateState state = navigation;
        SettlementSafetyDecision safety = settlementSafetyDecision(
                snapshot.worldRevision(),
                movementSafety.latestFor(player));
        NavCell destination = state.route.cells().getLast();
        if (!atWaypoint(player, destination, state.tolerance)) {
            return finish(Status.REPLAN_REQUIRED, Reason.PLAYER_OFF_ROUTE);
        }
        if (state.lastSettlePosition != null
                && player.position().distanceToSqr(state.lastSettlePosition)
                        <= SETTLE_DRIFT_EPSILON_SQUARED) {
            state.stableTicks++;
            // The final three position-stable ticks must also carry consecutive current safety
            // evidence. LocalObservationVolume derives this from Vanilla's live player AABB and
            // collision shapes, so no passage/block-specific geometry is duplicated here.
            state.safeTicks = safety == SettlementSafetyDecision.CLEAR
                    ? state.safeTicks + 1 : 0;
        } else {
            state.stableTicks = 0;
            state.safeTicks = 0;
        }
        state.lastSettlePosition = player.position();
        TickResult settlement = settlementCompletion(
                safety, state.stableTicks, state.safeTicks);
        if (settlement.terminal()) {
            return finish(settlement.status(), settlement.reason());
        }
        state.activeTicks++;
        if (!navigationOutputAllowed(state.activeTicks, state.route.tickUpperBound())) {
            return finish(Status.REPLAN_REQUIRED, Reason.PRIMITIVE_TICK_BUDGET_EXHAUSTED);
        }
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        long outputNanos = System.nanoTime();
        if (movement == null) {
            movement = MovementInputLease.acquire(
                    minecraft, ownerId, outputNanos, LEASE_HORIZON);
        }
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        outputNanos = System.nanoTime();
        movement.setDesired(ownerId, Set.of());
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        outputNanos = System.nanoTime();
        if (!movement.heartbeat(ownerId, outputNanos, LEASE_HORIZON)) {
            movement = null;
            return finish(Status.FAILED, Reason.MOVEMENT_LEASE_EXPIRED);
        }
        AgentInputState.global().requireGoalMovementSafety(
                player, player.level(), snapshot.worldRevision(), remainingDistance);
        return TickResult.running(Reason.NONE);
    }

    static SettlementSafetyDecision settlementSafetyDecision(
            long currentWorldRevision,
            java.util.Optional<LocalObservationVolume.Snapshot> latestSafety) {
        if (currentWorldRevision < 0L) {
            throw new IllegalArgumentException("current world revision must be non-negative");
        }
        Objects.requireNonNull(latestSafety, "latestSafety");
        if (latestSafety.isEmpty()) {
            return SettlementSafetyDecision.MISSING_OR_STALE;
        }
        var snapshot = latestSafety.orElseThrow();
        ObservationRecord current = snapshot.current();
        if (snapshot.worldRevision() != currentWorldRevision
                || current.worldRevision() != currentWorldRevision) {
            return SettlementSafetyDecision.MISSING_OR_STALE;
        }
        return current.loaded() == ObservationRecord.LoadedState.LOADED
                        && current.clearance() == ObservationRecord.Clearance.CLEAR
                        && current.support() == ObservationRecord.Support.PRESENT
                        && current.fluid() == ObservationRecord.Fluid.NONE
                        && !current.suffocation()
                        && current.hazard() == ObservationRecord.Hazard.NONE
                ? SettlementSafetyDecision.CLEAR
                : SettlementSafetyDecision.UNSAFE;
    }

    static TickResult settlementCompletion(
            SettlementSafetyDecision safety, int stableTicks, int safeTicks) {
        Objects.requireNonNull(safety, "safety");
        if (stableTicks < 0 || safeTicks < 0 || safeTicks > stableTicks) {
            throw new IllegalArgumentException("settlement tick counts are inconsistent");
        }
        if (stableTicks < SETTLE_STABLE_TICKS) {
            return TickResult.running(Reason.NONE);
        }
        return safety == SettlementSafetyDecision.CLEAR && safeTicks >= SETTLE_SAFETY_TICKS
                ? new TickResult(Status.SUCCEEDED, Reason.NONE)
                : new TickResult(Status.REPLAN_REQUIRED, Reason.DESTINATION_SAFETY_UNVERIFIED);
    }

    private TickResult tickFace(
            LocalPlayer player,
            KnownTraversabilitySnapshot snapshot,
            double remainingCameraDegrees,
            long clientTick,
            BooleanSupplier outputAllowed) {
        FaceState state = face;
        BoundaryDecision boundary = faceBoundaryDecision(state.target, snapshot);
        if (boundary != BoundaryDecision.CURRENT) {
            return finish(
                    boundary == BoundaryDecision.REVISION_CHANGED
                            ? Status.REPLAN_REQUIRED : Status.FAILED,
                    boundary == BoundaryDecision.REVISION_CHANGED
                            ? Reason.WORLD_REVISION_CHANGED : Reason.WORLD_BOUNDARY_CHANGED);
        }

        Vec3 eye = player.getEyePosition();
        double dx = state.target.aimX() - eye.x;
        double dy = state.target.aimY() - eye.y;
        double dz = state.target.aimZ() - eye.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0e-9D && Math.abs(dy) < 1.0e-9D) {
            return finish(Status.FAILED, Reason.INVALID_FACE_TARGET);
        }
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        double error = angularError(player.getYRot(), player.getXRot(), desiredYaw, desiredPitch);
        if (error <= AIM_TOLERANCE_DEGREES * 2.0D) {
            return finish(Status.SUCCEEDED, Reason.NONE);
        }
        if (remainingCameraDegrees <= 0.0D) {
            return finish(Status.REPLAN_REQUIRED, Reason.MOTION_BUDGET_EXHAUSTED);
        }
        state.observeProgress(error, clientTick);
        if (state.stalled(clientTick)) {
            return finish(Status.REPLAN_REQUIRED, Reason.CAMERA_STALLED);
        }
        if (!outputAllowed.getAsBoolean()) {
            return finish(Status.REPLAN_REQUIRED, Reason.HARD_DEADLINE);
        }
        turn(player, desiredYaw, desiredPitch, remainingCameraDegrees);
        state.activeTicks++;
        if (angularError(player.getYRot(), player.getXRot(), desiredYaw, desiredPitch)
                <= AIM_TOLERANCE_DEGREES * 2.0D) {
            return finish(Status.SUCCEEDED, Reason.NONE);
        }
        return state.activeTicks >= state.tickUpperBound
                ? finish(Status.REPLAN_REQUIRED, Reason.PRIMITIVE_TICK_BUDGET_EXHAUSTED)
                : TickResult.running(Reason.NONE);
    }

    private TickResult finish(Status status, Reason reason) {
        releaseMovement();
        navigation = null;
        face = null;
        return new TickResult(status, reason);
    }

    @Override
    public void close() {
        releaseMovement();
        navigation = null;
        face = null;
    }

    private void releaseMovement() {
        if (movement == null) {
            return;
        }
        // MovementInputLease keeps its release incomplete when close throws. Retain the exact
        // lease so the terminal cleanup lane can retry it instead of losing its owner token.
        movement.close(ownerId);
        movement = null;
    }

    private void requireIdle() {
        if (active()) {
            throw new IllegalStateException("An Action DSL primitive is already active");
        }
        lastClientTick = -1;
    }

    static BoundaryDecision boundaryDecision(
            UUID sessionId,
            String dimension,
            long worldRevision,
            KnownTraversabilitySnapshot snapshot) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!sessionId.equals(snapshot.worldSessionId())
                || !dimension.equals(snapshot.dimension())) {
            return BoundaryDecision.WORLD_CHANGED;
        }
        return worldRevision == snapshot.worldRevision()
                ? BoundaryDecision.CURRENT : BoundaryDecision.REVISION_CHANGED;
    }

    static BoundaryDecision faceBoundaryDecision(
            KnownFaceTarget target,
            KnownTraversabilitySnapshot snapshot) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshot, "snapshot");
        BoundaryDecision exact = boundaryDecision(
                target.worldSessionId(),
                target.target().dimension(),
                target.worldRevision(),
                snapshot);
        return exact == BoundaryDecision.REVISION_CHANGED
                        && target.allowNewerWorldRevision()
                        && snapshot.worldRevision() > target.worldRevision()
                ? BoundaryDecision.CURRENT : exact;
    }

    static EdgeDecision edgeDecision(
            RoutePlan route,
            int edgeIndex,
            KnownTraversabilitySnapshot snapshot) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(snapshot, "snapshot");
        if (edgeIndex < 0 || edgeIndex >= route.edges().size()
                || !route.worldSessionId().equals(snapshot.worldSessionId())
                || !route.dimension().equals(snapshot.dimension())) {
            return EdgeDecision.REPLAN;
        }
        TraversabilityEdge planned = route.edges().get(edgeIndex);
        TraversabilityEdge current = snapshot.edge(planned.key()).orElse(null);
        if (current == null || !route.worldSessionId().equals(current.worldSessionId())
                || !current.traversable()
                || current.locomotion() != planned.locomotion()
                || !diagonalProofCurrent(current, snapshot)) {
            return EdgeDecision.REPLAN;
        }
        if (current.requiresProbe()) {
            // A newly downgraded edge was not included in the compiled probe/tick budget.
            return planned.requiresProbe() ? EdgeDecision.PROBE : EdgeDecision.REPLAN;
        }
        return EdgeDecision.CONFIRMED;
    }

    static boolean sameCellRouteCurrent(
            RoutePlan route, KnownTraversabilitySnapshot snapshot) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(snapshot, "snapshot");
        return route.edges().isEmpty()
                && route.worldSessionId().equals(snapshot.worldSessionId())
                && route.dimension().equals(snapshot.dimension())
                && snapshot.worldRevision() >= route.worldRevision()
                && snapshot.containsCell(route.cells().getLast());
    }

    static boolean navigationOutputAllowed(long activeTicks, long tickUpperBound) {
        return activeTicks >= 0L && tickUpperBound > 0L && activeTicks < tickUpperBound;
    }

    static boolean jumpRequired(int verticalDelta, double stepHeight, double maxUpStep) {
        if (!Double.isFinite(stepHeight)
                || !Double.isFinite(maxUpStep)
                || maxUpStep < 0.0D) {
            throw new IllegalArgumentException(
                    "step height must be finite and maxUpStep non-negative");
        }
        return verticalDelta > 0 && stepHeight > maxUpStep + STEP_EPSILON;
    }

    static TickResult runningNavigationResult(EdgeDecision edge, boolean movementIssued) {
        Objects.requireNonNull(edge, "edge");
        return TickResult.running(edge == EdgeDecision.PROBE && movementIssued
                ? Reason.PROBE_MICRO_STEP : Reason.NONE);
    }

    private static boolean diagonalProofCurrent(
            TraversabilityEdge edge,
            KnownTraversabilitySnapshot snapshot) {
        NavCell from = edge.key().from();
        NavCell to = edge.key().to();
        if (!from.horizontallyDiagonalTo(to)) return true;
        NavCell xSide = new NavCell(from.dimension(), to.x(), from.y(), from.z());
        NavCell zSide = new NavCell(from.dimension(), from.x(), from.y(), to.z());
        return confirmed(snapshot, new TraversabilityEdge.Key(from, xSide))
                && confirmed(snapshot, new TraversabilityEdge.Key(from, zSide));
    }

    private static boolean confirmed(
            KnownTraversabilitySnapshot snapshot,
            TraversabilityEdge.Key key) {
        return snapshot.edge(key)
                .map(edge -> edge.status() == TraversabilityEdge.Status.CONFIRMED)
                .orElse(false);
    }

    static float boundedYawDelta(float currentYaw, float desiredYaw, float limit) {
        requireCameraLimit(limit);
        return Mth.clamp(
                Mth.wrapDegrees(desiredYaw - currentYaw),
                -limit,
                limit);
    }

    static float boundedPitchDelta(float currentPitch, float desiredPitch, float limit) {
        requireCameraLimit(limit);
        return Mth.clamp(
                Mth.clamp(desiredPitch, -90.0F, 90.0F) - currentPitch,
                -limit,
                limit);
    }

    private void turn(
            LocalPlayer player,
            float desiredYaw,
            float desiredPitch,
            double remainingCameraDegrees) {
        float yaw = boundedYawDelta(player.getYRot(), desiredYaw, maxCameraDegreesPerTick);
        float pitch = boundedPitchDelta(player.getXRot(), desiredPitch, maxCameraDegreesPerTick);
        double total = Math.abs(yaw) + Math.abs(pitch);
        if (total > remainingCameraDegrees) {
            double scale = remainingCameraDegrees / total;
            yaw *= (float) scale;
            pitch *= (float) scale;
        }
        player.turn(yaw / 0.15D, pitch / 0.15D);
    }

    private static void requireCameraLimit(float limit) {
        if (!Float.isFinite(limit) || limit <= 0.0F
                || limit > MAX_ALLOWED_CAMERA_DEGREES_PER_TICK) {
            throw new IllegalArgumentException("camera limit must be within (0, 18]");
        }
    }

    private static double angularError(
            float yaw, float pitch, float desiredYaw, float desiredPitch) {
        return Math.abs(Mth.wrapDegrees(desiredYaw - yaw))
                + Math.abs(Mth.clamp(desiredPitch, -90.0F, 90.0F) - pitch);
    }

    private static boolean atWaypoint(LocalPlayer player, NavCell cell, double tolerance) {
        return waypointReached(
                player.getX(), player.getY(), player.getZ(), cell, tolerance);
    }

    static boolean waypointReached(
            double x, double y, double z, NavCell cell, double tolerance) {
        Objects.requireNonNull(cell, "cell");
        return Mth.floor(x) == cell.x()
                && Mth.floor(y) == cell.y()
                && Mth.floor(z) == cell.z()
                && Math.hypot(cell.x() + 0.5D - x, cell.z() + 0.5D - z) <= tolerance;
    }

    static SameCellDecision sameCellDecision(
            double x, double y, double z, NavCell cell, double tolerance) {
        if (Mth.floor(x) != cell.x()
                || Mth.floor(y) != cell.y()
                || Mth.floor(z) != cell.z()) {
            return SameCellDecision.OFF_ROUTE;
        }
        return waypointReached(x, y, z, cell, tolerance)
                ? SameCellDecision.SETTLE : SameCellDecision.DRIVE;
    }

    private static double horizontalDistance(LocalPlayer player, NavCell cell) {
        return Math.hypot(cell.x() + 0.5D - player.getX(), cell.z() + 0.5D - player.getZ());
    }

    static double navigationDistance(
            double playerX,
            double playerY,
            double playerZ,
            NavCell cell,
            Locomotion locomotion) {
        double horizontal = Math.hypot(cell.x() + 0.5D - playerX, cell.z() + 0.5D - playerZ);
        return locomotion == Locomotion.GROUND
                ? horizontal : Math.hypot(horizontal, cell.y() - playerY);
    }

    private static double navigationDistance(
            LocalPlayer player, NavCell cell, Locomotion locomotion) {
        return navigationDistance(
                player.getX(), player.getY(), player.getZ(), cell, locomotion);
    }

    static Set<MovementInputLease.MovementKey> withVerticalInput(
            Set<MovementInputLease.MovementKey> horizontal,
            int verticalDelta,
            double remainingHeight,
            double maxUpStep,
            Locomotion locomotion) {
        Objects.requireNonNull(horizontal, "horizontal");
        Objects.requireNonNull(locomotion, "locomotion");
        var result = horizontal.isEmpty()
                ? EnumSet.noneOf(MovementInputLease.MovementKey.class)
                : EnumSet.copyOf(horizontal);
        if (locomotion == Locomotion.SCAFFOLDING && verticalDelta < 0) {
            result.add(MovementInputLease.MovementKey.CROUCH);
        } else if (locomotion != Locomotion.GROUND && verticalDelta > 0) {
            result.add(MovementInputLease.MovementKey.JUMP);
        } else if (jumpRequired(verticalDelta, remainingHeight, maxUpStep)) {
            result.add(MovementInputLease.MovementKey.JUMP);
        }
        return Set.copyOf(result);
    }

    static int effectiveVerticalDelta(
            int plannedVerticalDelta,
            Locomotion locomotion,
            TraversabilityEdge.TargetSupport targetSupport,
            double remainingHeight) {
        Objects.requireNonNull(locomotion, "locomotion");
        Objects.requireNonNull(targetSupport, "targetSupport");
        if (plannedVerticalDelta == 0
                && locomotion == Locomotion.LADDER
                && targetSupport == TraversabilityEdge.TargetSupport.CONFIRMED
                && remainingHeight > STEP_EPSILON) {
            return 1;
        }
        return plannedVerticalDelta;
    }

    public static Set<MovementInputLease.MovementKey> steering(
            double playerX, double playerZ, float yaw, NavCell target) {
        return steering(playerX, playerZ, yaw, target, 0.0D);
    }

    static Set<MovementInputLease.MovementKey> steering(
            double playerX,
            double playerZ,
            float yaw,
            NavCell target,
            double tolerance) {
        Objects.requireNonNull(target, "target");
        return steering(
                playerX, playerZ, yaw,
                target.x() + 0.5D, target.z() + 0.5D, tolerance);
    }

    public static Set<MovementInputLease.MovementKey> steering(
            double playerX,
            double playerZ,
            float yaw,
            double targetX,
            double targetZ,
            double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0.0D) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative");
        }
        if (!Double.isFinite(targetX) || !Double.isFinite(targetZ)) {
            throw new IllegalArgumentException("target must be finite");
        }
        double dx = targetX - playerX;
        double dz = targetZ - playerZ;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double forward = dx * forwardX + dz * forwardZ;
        double right = dx * -forwardZ + dz * forwardX;
        if (Math.abs(forward) <= 0.10D && Math.abs(right) <= 0.10D
                && Math.hypot(dx, dz) <= tolerance) {
            return Set.of();
        }

        var best = EnumSet.noneOf(MovementInputLease.MovementKey.class);
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int forwardInput = -1; forwardInput <= 1; forwardInput++) {
            for (int leftInput = -1; leftInput <= 1; leftInput++) {
                if (forwardInput == 0 && leftInput == 0) {
                    continue;
                }
                var candidate = EnumSet.noneOf(MovementInputLease.MovementKey.class);
                if (forwardInput > 0) {
                    candidate.add(MovementInputLease.MovementKey.FORWARD);
                } else if (forwardInput < 0) {
                    candidate.add(MovementInputLease.MovementKey.BACK);
                }
                if (leftInput > 0) {
                    candidate.add(MovementInputLease.MovementKey.LEFT);
                } else if (leftInput < 0) {
                    candidate.add(MovementInputLease.MovementKey.RIGHT);
                }
                Vec3 direction = commandDirection(yaw, candidate);
                double dot = direction.x * dx + direction.z * dz;
                if (dot > bestDot) {
                    bestDot = dot;
                    best = candidate;
                }
            }
        }
        return Set.copyOf(best);
    }

    static Vec3 commandDirection(
            float yaw, Set<MovementInputLease.MovementKey> movement) {
        Objects.requireNonNull(movement, "movement");
        double forward = movement.contains(MovementInputLease.MovementKey.FORWARD) ? 1.0D
                : movement.contains(MovementInputLease.MovementKey.BACK) ? -1.0D : 0.0D;
        double left = movement.contains(MovementInputLease.MovementKey.LEFT) ? 1.0D
                : movement.contains(MovementInputLease.MovementKey.RIGHT) ? -1.0D : 0.0D;
        if (forward == 0.0D && left == 0.0D) return Vec3.ZERO;
        double scale = 1.0D / Math.hypot(forward, left);
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        return new Vec3(
                (forward * forwardX + left * forwardZ) * scale,
                0.0D,
                (forward * forwardZ - left * forwardX) * scale);
    }

    static boolean insideRouteCorridor(double x, double y, double z, TraversabilityEdge.Key edge) {
        NavCell from = edge.from();
        NavCell to = edge.to();
        if (y < Math.min(from.y(), to.y()) - ROUTE_VERTICAL_MARGIN
                || y > Math.max(from.y(), to.y()) + ROUTE_VERTICAL_MARGIN) {
            return false;
        }
        double ax = from.x() + 0.5D;
        double az = from.z() + 0.5D;
        double bx = to.x() + 0.5D;
        double bz = to.z() + 0.5D;
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double along = lengthSquared == 0.0D ? 0.0D
                : Mth.clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared, 0.0D, 1.0D);
        return Math.hypot(x - (ax + along * dx), z - (az + along * dz))
                <= ROUTE_CORRIDOR_RADIUS;
    }

    private static boolean insideRouteCorridor(LocalPlayer player, TraversabilityEdge.Key edge) {
        return insideRouteCorridor(player.getX(), player.getY(), player.getZ(), edge);
    }

    public record KnownFaceTarget(
            UUID worldSessionId,
            long worldRevision,
            ActionDsl.Position target,
            double aimX,
            double aimY,
            double aimZ,
            boolean allowNewerWorldRevision) {
        public KnownFaceTarget(
                UUID worldSessionId,
                long worldRevision,
                ActionDsl.Position target,
                double aimX,
                double aimY,
                double aimZ) {
            this(worldSessionId, worldRevision, target, aimX, aimY, aimZ, false);
        }

        public KnownFaceTarget(
                UUID worldSessionId, long worldRevision, ActionDsl.Position target) {
            this(worldSessionId, worldRevision, target, false);
        }

        public KnownFaceTarget(
                UUID worldSessionId,
                long worldRevision,
                ActionDsl.Position target,
                boolean allowNewerWorldRevision) {
            this(
                    worldSessionId,
                    worldRevision,
                    target,
                    target.x() + 0.5D,
                    target.y() + 0.5D,
                    target.z() + 0.5D,
                    allowNewerWorldRevision);
        }

        public KnownFaceTarget {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(target, "target");
            if (worldRevision < 0
                    || !Double.isFinite(aimX) || !Double.isFinite(aimY) || !Double.isFinite(aimZ)
                    || aimX < target.x() || aimX > target.x() + 1.0D
                    || aimY < target.y() || aimY > target.y() + 1.0D
                    || aimZ < target.z() || aimZ > target.z() + 1.0D) {
                throw new IllegalArgumentException("worldRevision must be non-negative");
            }
        }

        public static KnownFaceTarget forBlockFace(
                UUID worldSessionId,
                long worldRevision,
                ActionDsl.Position target,
                ActionDsl.BlockFace face) {
            Vec3 aim = blockFaceAimPoint(target, face);
            return new KnownFaceTarget(
                    worldSessionId, worldRevision, target, aim.x, aim.y, aim.z, false);
        }

        public static KnownFaceTarget forBlockFaceRevisionWindow(
                UUID worldSessionId,
                long worldRevision,
                ActionDsl.Position target,
                ActionDsl.BlockFace face) {
            // Callers must revalidate the target's position-specific surface barrier before
            // every tick; this flag only prevents unrelated neutral revisions from restarting aim.
            Vec3 aim = blockFaceAimPoint(target, face);
            return new KnownFaceTarget(
                    worldSessionId, worldRevision, target, aim.x, aim.y, aim.z, true);
        }
    }

    public static Vec3 blockFaceAimPoint(
            ActionDsl.Position target, ActionDsl.BlockFace face) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(face, "face");
        double inset = 1.0e-3D;
        return switch (face) {
            case DOWN -> new Vec3(target.x() + 0.5D, target.y() + inset, target.z() + 0.5D);
            case UP -> new Vec3(target.x() + 0.5D, target.y() + 1.0D - inset, target.z() + 0.5D);
            case NORTH -> new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + inset);
            case SOUTH -> new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 1.0D - inset);
            case WEST -> new Vec3(target.x() + inset, target.y() + 0.5D, target.z() + 0.5D);
            case EAST -> new Vec3(target.x() + 1.0D - inset, target.y() + 0.5D, target.z() + 0.5D);
        };
    }

    public record TickResult(Status status, Reason reason) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            if (status == Status.RUNNING && reason != Reason.NONE
                    && reason != Reason.PROBE_MICRO_STEP) {
                throw new IllegalArgumentException("running result has a terminal reason");
            }
            if (status != Status.RUNNING && reason == Reason.PROBE_MICRO_STEP) {
                throw new IllegalArgumentException("probe micro-step is a running result");
            }
        }

        static TickResult running(Reason reason) {
            return new TickResult(Status.RUNNING, reason);
        }

        public boolean terminal() {
            return status != Status.RUNNING;
        }
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        REPLAN_REQUIRED,
        FAILED
    }

    public enum Reason {
        NONE,
        PROBE_MICRO_STEP,
        WORLD_UNAVAILABLE,
        WORLD_BOUNDARY_CHANGED,
        WORLD_REVISION_CHANGED,
        ROUTE_EDGE_CHANGED,
        PLAYER_OFF_ROUTE,
        UNVERIFIED_MOVEMENT_VECTOR,
        MOVEMENT_STALLED,
        CAMERA_STALLED,
        PRIMITIVE_TICK_BUDGET_EXHAUSTED,
        MOTION_BUDGET_EXHAUSTED,
        MOVEMENT_LEASE_EXPIRED,
        INVALID_FACE_TARGET,
        UNSUPPORTED_LOCOMOTION,
        DESTINATION_SAFETY_UNVERIFIED,
        HARD_DEADLINE
    }

    enum BoundaryDecision {
        CURRENT,
        REVISION_CHANGED,
        WORLD_CHANGED
    }

    enum EdgeDecision {
        CONFIRMED,
        PROBE,
        REPLAN
    }

    enum SameCellDecision {
        OFF_ROUTE,
        DRIVE,
        SETTLE
    }

    enum SettlementSafetyDecision {
        CLEAR,
        MISSING_OR_STALE,
        UNSAFE
    }

    private static final class NavigateState extends ProgressState {
        private final RoutePlan route;
        private final double tolerance;
        private int edgeIndex;
        private long activeTicks;
        private boolean settling;
        private Vec3 lastSettlePosition;
        private int stableTicks;
        private int safeTicks;

        private NavigateState(RoutePlan route, double tolerance) {
            this.route = route;
            this.tolerance = tolerance;
        }

        private void beginSettlement() {
            settling = true;
            lastSettlePosition = null;
            stableTicks = 0;
            safeTicks = 0;
        }
    }

    private static final class FaceState extends ProgressState {
        private final KnownFaceTarget target;
        private final long tickUpperBound;
        private long activeTicks;

        private FaceState(KnownFaceTarget target, long tickUpperBound) {
            this.target = target;
            this.tickUpperBound = tickUpperBound;
        }
    }

    private abstract static class ProgressState {
        private double best = Double.POSITIVE_INFINITY;
        private long lastProgressTick = -1;

        final void observeProgress(double value, long clientTick) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("progress value must be finite and non-negative");
            }
            if (lastProgressTick < 0 || best - value >= PROGRESS_EPSILON_BLOCKS) {
                best = value;
                lastProgressTick = clientTick;
            }
        }

        final boolean stalled(long clientTick) {
            return lastProgressTick >= 0 && clientTick - lastProgressTick >= STALL_TICKS;
        }

        final void resetProgress() {
            best = Double.POSITIVE_INFINITY;
            lastProgressTick = -1;
        }
    }
}
