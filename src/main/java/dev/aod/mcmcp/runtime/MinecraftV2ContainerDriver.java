package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.KnownContainerPolicy;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.PhaseFivePort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Local approach followed by the shared owned-menu open/transfer/readback/release cycle. */
final class MinecraftV2ContainerDriver implements V2InventoryJobExecution.Driver {
    private static final int MAX_EVIDENCE_WAIT_TICKS = 80;
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliation;
    private final PhaseFivePort port;
    private final V2InventoryContainerArguments request;
    private final NavCell target;
    private final MinecraftActionPrimitiveExecutor navigation;
    private final CoordinateGoalPlanner planner;
    private final List<Map<String, Object>> effects = new ArrayList<>();
    private LocalPlayer player;
    private ClientLevel level;
    private Vec3 lastPosition;
    private float health;
    private double travelled;
    private long startedTick;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int evidenceWaitTicks;
    private KnownContainerAttempt attempt;
    private List<Map<String, Object>> items;
    private int confirmedCount;
    private boolean uncertain;
    private String failure;

    MinecraftV2ContainerDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions, AgentObservations observations,
            ClientReconciliationSignals reconciliation, PhaseFivePort port,
            V2InventoryContainerArguments request, String dimension) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.sessions = Objects.requireNonNull(sessions);
        this.observations = Objects.requireNonNull(observations);
        this.reconciliation = Objects.requireNonNull(reconciliation);
        this.port = Objects.requireNonNull(port);
        this.request = Objects.requireNonNull(request);
        target = request.target(dimension);
        navigation = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        planner = new CoordinateGoalPlanner(sessions.get().worldSessionId(), target,
                cell -> !cell.equals(target));
    }

    @Override
    public void begin(long clientTick, BooleanSupplier outputAllowed) {
        if (player != null) throw new IllegalStateException("container job already begun");
        player = Objects.requireNonNull(minecraft.player);
        level = Objects.requireNonNull(minecraft.level);
        lastPosition = player.position();
        health = player.getHealth() + player.getAbsorptionAmount();
        startedTick = clientTick;
    }

    @Override
    public V2InventoryJobExecution.Step tick(long clientTick, BooleanSupplier outputAllowed) {
        if (failure != null) return V2InventoryJobExecution.Step.FAILED;
        var session = sessions.get();
        if (!outputAllowed.getAsBoolean() || !session.worldReady()
                || !target.dimension().equals(session.dimension())
                || minecraft.player != player || minecraft.level != level
                || player.getHealth() + player.getAbsorptionAmount() < health) {
            return fail("container_context_changed");
        }
        var position = player.position();
        travelled += position.distanceTo(lastPosition);
        lastPosition = position;
        if (!Double.isFinite(travelled) || travelled > request.maxDistance()) {
            return fail("container_travel_limit");
        }
        if (clientTick - startedTick >= request.maxTicks()) {
            return fail("container_duration_limit");
        }
        if (attempt != null) {
            var result = attempt.tick(clientTick);
            captureEffects(result.effects());
            if (result.status() == KnownContainerAttempt.Status.FAILED) return fail(result.evidence());
            if (result.status() == KnownContainerAttempt.Status.SUCCEEDED) {
                if (request.inspect()) {
                    items = attempt.inspectionContents().items().stream()
                            .filter(item -> request.itemId() == null || request.itemId().equals(item.item()))
                            .map(item -> Map.<String, Object>of("item", item.item(), "count", item.count()))
                            .toList();
                } else if (confirmedCount != request.count() || uncertain) {
                    return fail("container_quantity_not_confirmed");
                }
                return V2InventoryJobExecution.Step.CONFIRMED;
            }
            return V2InventoryJobExecution.Step.RUNNING;
        }
        var map = observations.requireAgentMap(session);
        if (navigation.active()) {
            var motion = navigation.tick(minecraft, map, LocalObservationVolume.global(),
                    Math.max(0, request.maxDistance() - travelled), 1_080.0D,
                    clientTick, outputAllowed);
            if (motion.status() == MinecraftActionPrimitiveExecutor.Status.FAILED) {
                return fail("container_approach_failed");
            }
            if (motion.status() != MinecraftActionPrimitiveExecutor.Status.RUNNING) {
                navigation.close();
                waitingEvidence = map.edges();
                evidenceWaitTicks = 0;
            }
            return V2InventoryJobExecution.Step.RUNNING;
        }
        if (prepare(session, clientTick)) return V2InventoryJobExecution.Step.RUNNING;
        if (failure != null) return V2InventoryJobExecution.Step.FAILED;
        if (!request.advance() || request.maxDistance() == 0
                || waitingEvidence != null && waitingEvidence.equals(map.edges())) {
            return ++evidenceWaitTicks <= MAX_EVIDENCE_WAIT_TICKS
                    ? V2InventoryJobExecution.Step.RUNNING : fail("container_target_not_observed");
        }
        var plan = planner.plan(map, ActionPlanning.playerCell(player, session.dimension()),
                session.worldSessionId(), map.worldRevision(), CoordinateGoalPlanner.Budget.DEFAULT,
                () -> !outputAllowed.getAsBoolean());
        return switch (plan.status()) {
            case KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT -> {
                navigation.beginNavigate(plan.route().orElseThrow(), 0.35D);
                waitingEvidence = null;
                yield V2InventoryJobExecution.Step.RUNNING;
            }
            case BLOCKED, REACHED_KNOWN_GOAL -> {
                waitingEvidence = map.edges();
                evidenceWaitTicks = 0;
                yield V2InventoryJobExecution.Step.RUNNING;
            }
            case LIMIT, CANCELLED, STALE_MAP, WORLD_MISMATCH -> fail("container_approach_unavailable");
        };
    }

    private boolean prepare(WorldSessionTracker.Snapshot session, long clientTick) {
        var position = new BlockPos(target.x(), target.y(), target.z());
        if (!level.isLoaded(position) || !player.isWithinBlockInteractionRange(position, 0)) return false;
        var frame = observations.latestInternalFrame();
        if (frame.isEmpty()) return false;
        var ledger = reconciliation.bindAndSnapshot(level, session.worldSessionId());
        var aim = V2BlockAimResolver.resolve(frame.orElseThrow(), target, null,
                player.getEyePosition(), session.worldSessionId(), clientTick,
                ledger.worldRevision(), ledger.surfaceBarrierWorldRevision(target.x(), target.y(), target.z()),
                id -> true);
        if (aim.isEmpty()) return false;
        var state = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(level.getBlockState(position));
        if (!state.blockId().equals(aim.orElseThrow().blockId())) return false;
        if (request.blockId() != null && !request.blockId().equals(state.blockId())) {
            fail("container_block_condition_changed");
            return false;
        }
        if (!KnownContainerPolicy.allows(state.blockId())) {
            fail("unsupported_container");
            return false;
        }
        // Let motion settle before the stationary owned-menu kernel captures its baseline.
        var velocity = player.getDeltaMovement();
        if (!player.onGround() || player.isShiftKeyDown()
                || velocity.x * velocity.x + velocity.z * velocity.z > 0.01D) return true;
        var ray = aim.orElseThrow().aim();
        var operation = request.operation(session.dimension(), state,
                new Vec3(ray.aimX(), ray.aimY(), ray.aimZ()),
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0D);
        attempt = new KnownContainerAttempt(port, operation, clientTick,
                Math.addExact(startedTick, request.maxTicks()));
        return true;
    }

    @Override
    public boolean allowsScreenChange() {
        // The shared port checks the exact owned screen and cursor on every tick.
        return attempt != null;
    }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.inspect() ? "inspect" : "transfer");
        result.put("target", Map.of("dimension", target.dimension(),
                "x", target.x(), "y", target.y(), "z", target.z()));
        if (request.itemId() != null) result.put("item", request.itemId());
        if (request.inspect()) {
            result.put("complete", items != null);
            if (items != null) result.put("items", items);
        } else {
            result.put("direction", request.store() ? "store" : "take");
            result.put("requested_count", request.count());
            result.put("confirmed_count", confirmedCount);
            result.put("unconfirmed", uncertain);
            if (!effects.isEmpty()) result.put("effects", List.copyOf(effects));
        }
        if (failure != null) result.put("failure", failure);
        return result;
    }

    @Override
    public void close() {
        try {
            navigation.close();
        } finally {
            if (attempt != null) {
                try { attempt.close(); }
                finally { captureEffects(attempt.drainEffectDeltas()); }
            }
        }
    }

    private void captureEffects(List<KnownContainerAttempt.EffectDelta> deltas) {
        for (var delta : deltas) {
            boolean confirmed = delta.verification() == AgentActionStore.Verification.CONFIRMED;
            if (confirmed) {
                confirmedCount = Math.addExact(confirmedCount,
                        ((Number) delta.observedAfter().get("transferred")).intValue());
            } else uncertain = true;
            effects.add(Map.of("verification", confirmed ? "confirmed" : "unknown",
                    "before", delta.observedBefore(), "after", delta.observedAfter()));
        }
    }

    private V2InventoryJobExecution.Step fail(String reason) {
        failure = reason;
        return V2InventoryJobExecution.Step.FAILED;
    }
}
