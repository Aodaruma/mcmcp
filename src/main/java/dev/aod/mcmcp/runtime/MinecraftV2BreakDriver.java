package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.routine.V2BreakSourcePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Approaches a requested target through known safe cells, then uses normal break input. */
final class MinecraftV2BreakDriver implements V2BreakJobExecution.Driver {
    private static final int MAX_APPROACH_EVIDENCE_WAIT_TICKS = 80;
    private static final double APPROACH_TOLERANCE = 0.35D;
    private static final int BREAK_ACK_GRACE_TICKS = 20;
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final MinecraftStationaryBreakPort port;
    private final DoubleSupplier remainingDistance;
    private MinecraftActionPrimitiveExecutor navigation;
    private CoordinateGoalPlanner planner;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int approachEvidenceWaitTicks;
    private MinecraftActionPrimitiveExecutor facing;
    private KnownBlockBreakAttempt attack;
    private StationaryBreakRequest attackRequest;
    private NavCell target;
    private V2BreakArguments request;
    private String blockId;
    private Stage stage = Stage.IDLE;
    private int originalSlot = -1;
    private int selectedToolSlot = -1;
    private int crosshairWaitTicks;

    MinecraftV2BreakDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations,
            ClientReconciliationSignals reconciliationSignals,
            MinecraftStationaryBreakPort port,
            DoubleSupplier remainingDistance) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.reconciliationSignals = Objects.requireNonNull(reconciliationSignals, "reconciliationSignals");
        this.port = Objects.requireNonNull(port, "port");
        this.remainingDistance = Objects.requireNonNull(remainingDistance, "remainingDistance");
    }

    @Override
    public String dimension() {
        return sessions.get().dimension();
    }

    @Override
    public V2BreakJobExecution.BeginResult begin(NavCell next,
            V2BreakArguments request, BooleanSupplier outputAllowed) {
        if (stage != Stage.IDLE) throw new IllegalStateException("break driver already active");
        if (!outputAllowed.getAsBoolean()) return V2BreakJobExecution.BeginResult.FAILED;
        var observed = beginObservedTarget(next, request, outputAllowed);
        if (observed != V2BreakJobExecution.BeginResult.WAITING
                || !request.advance() || request.maxDistance() <= 0.0D) return observed;
        target = next;
        this.request = request;
        planner = new CoordinateGoalPlanner(sessions.get().worldSessionId(), next);
        navigation = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        stage = Stage.APPROACH;
        return V2BreakJobExecution.BeginResult.STARTED;
    }

    private V2BreakJobExecution.BeginResult beginObservedTarget(
            NavCell next, V2BreakArguments request, BooleanSupplier outputAllowed) {
        var session = sessions.get();
        var player = minecraft.player;
        var level = minecraft.level;
        if (!session.worldReady() || player == null || level == null
                || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !session.dimension().equals(next.dimension())) {
            return V2BreakJobExecution.BeginResult.FAILED;
        }
        var position = new BlockPos(next.x(), next.y(), next.z());
        if (!level.isLoaded(position)) return V2BreakJobExecution.BeginResult.WAITING;
        if (!player.isWithinBlockInteractionRange(position, 0.0D)) {
            return V2BreakJobExecution.BeginResult.WAITING;
        }
        if (visiblyAir(position)) return V2BreakJobExecution.BeginResult.SKIPPED;
        var frame = observations.latestInternalFrame();
        if (frame.isEmpty()) return V2BreakJobExecution.BeginResult.WAITING;
        var reconciliation = reconciliationSignals.bindAndSnapshot(level, session.worldSessionId());
        long barrier = reconciliation.surfaceBarrierWorldRevision(next.x(), next.y(), next.z());
        var aim = V2BlockAimResolver.resolve(frame.orElseThrow(), next,
                player.getEyePosition(), session.worldSessionId(), session.clientTick(),
                reconciliation.worldRevision(), barrier);
        if (aim.isEmpty()) {
            return V2BreakJobExecution.BeginResult.WAITING;
        }
        BlockState state = level.getBlockState(position);
        String currentBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!currentBlockId.equals(aim.orElseThrow().blockId())) {
            return V2BreakJobExecution.BeginResult.WAITING;
        }
        if (!request.accepts(currentBlockId)) return V2BreakJobExecution.BeginResult.SKIPPED;
        if (!V2BreakSourcePolicy.allowsLiveState(state)) {
            return V2BreakJobExecution.BeginResult.FAILED;
        }
        if (!outputAllowed.getAsBoolean()) return V2BreakJobExecution.BeginResult.FAILED;
        target = next;
        this.request = request;
        blockId = currentBlockId;
        originalSlot = player.getInventory().getSelectedSlot();
        selectedToolSlot = bestHotbarTool(state);
        player.getInventory().setSelectedSlot(selectedToolSlot);
        facing = new MinecraftActionPrimitiveExecutor(
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
        facing.beginFace(aim.orElseThrow().aim(), 60L);
        stage = Stage.FACING;
        return V2BreakJobExecution.BeginResult.STARTED;
    }

    @Override
    public V2BreakJobExecution.StepResult tick(long clientTick, BooleanSupplier outputAllowed) {
        if (stage == Stage.IDLE || !outputAllowed.getAsBoolean()) {
            return V2BreakJobExecution.StepResult.FAILED;
        }
        var session = sessions.get();
        if (!session.worldReady() || !session.dimension().equals(target.dimension())) {
            return V2BreakJobExecution.StepResult.FAILED;
        }
        if (stage == Stage.APPROACH) {
            return tickApproach(session, clientTick, outputAllowed);
        }
        if (stage == Stage.FACING) {
            var map = observations.requireAgentMap(session);
            var result = facing.tick(minecraft, map, LocalObservationVolume.global(),
                    0.0D, 1_080.0D, clientTick, outputAllowed);
            if (result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING) {
                return V2BreakJobExecution.StepResult.RUNNING;
            }
            if (result.status() != MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
                return V2BreakJobExecution.StepResult.FAILED;
            }
            facing.close();
            facing = null;
            stage = Stage.WAIT_CROSSHAIR;
            return V2BreakJobExecution.StepResult.RUNNING;
        }
        if (stage == Stage.WAIT_CROSSHAIR) {
            if (!crosshairOnTarget()) {
                return ++crosshairWaitTicks <= 10
                        ? V2BreakJobExecution.StepResult.RUNNING
                        : V2BreakJobExecution.StepResult.FAILED;
            }
            var block = new BlockTarget(target.dimension(), target.x(), target.y(), target.z());
            var source = port.captureExpectedSource(block, Set.of(blockId));
            attackRequest = new StationaryBreakRequest(block, source,
                    new StationaryBreakGoal("minecraft:air", 1),
                    Math.addExact(clientTick,
                            StationaryBreakRequest.MAX_V2_ATTACK_LEASE_TICKS
                                    + BREAK_ACK_GRACE_TICKS),
                    StationaryBreakRequest.MAX_V2_ATTACK_LEASE_TICKS, 1);
            attack = new KnownBlockBreakAttempt(port, attackRequest, clientTick,
                    KnownBlockBreakAttempt.Completion.AUTHORITATIVE_AIR);
            stage = Stage.BREAKING;
            return V2BreakJobExecution.StepResult.RUNNING;
        }
        var frame = port.observe(attackRequest);
        boolean controlled = frame.worldReady() && frame.controlContextClear()
                && frame.playerAlive() && frame.healthSafe()
                && frame.visibleThreatClear() && frame.targetInReach()
                && frame.crosshairOnTarget() && outputAllowed.getAsBoolean();
        return switch (attack.tick(clientTick, controlled)) {
            case RUNNING -> V2BreakJobExecution.StepResult.RUNNING;
            case SUCCEEDED -> V2BreakJobExecution.StepResult.CONFIRMED;
            case SERVER_DENIED_OR_DESYNC -> V2BreakJobExecution.StepResult.FAILED;
        };
    }

    private V2BreakJobExecution.StepResult tickApproach(
            WorldSessionTracker.Snapshot session, long clientTick,
            BooleanSupplier outputAllowed) {
        var map = observations.requireAgentMap(session);
        if (navigation.active()) {
            var motion = navigation.tick(minecraft, map, LocalObservationVolume.global(),
                    Math.max(0.0D, remainingDistance.getAsDouble()),
                    1_080.0D, clientTick, outputAllowed);
            return switch (motion.status()) {
                case RUNNING -> V2BreakJobExecution.StepResult.RUNNING;
                case SUCCEEDED, REPLAN_REQUIRED -> {
                    navigation.close();
                    waitingEvidence = map.edges();
                    approachEvidenceWaitTicks = 0;
                    yield V2BreakJobExecution.StepResult.RUNNING;
                }
                case FAILED -> V2BreakJobExecution.StepResult.FAILED;
            };
        }
        var observed = beginObservedTarget(target, request, outputAllowed);
        switch (observed) {
            case STARTED -> { return V2BreakJobExecution.StepResult.RUNNING; }
            case SKIPPED -> { return V2BreakJobExecution.StepResult.SKIPPED; }
            case FAILED -> { return V2BreakJobExecution.StepResult.FAILED; }
            case WAITING -> { }
        }
        if (waitingEvidence != null && waitingEvidence.equals(map.edges())) {
            return ++approachEvidenceWaitTicks <= MAX_APPROACH_EVIDENCE_WAIT_TICKS
                    ? V2BreakJobExecution.StepResult.RUNNING
                    : V2BreakJobExecution.StepResult.FAILED;
        }
        var current = ActionPlanning.playerCell(minecraft.player, session.dimension());
        var plan = planner.plan(map, current, session.worldSessionId(), map.worldRevision(),
                CoordinateGoalPlanner.Budget.DEFAULT,
                () -> !outputAllowed.getAsBoolean());
        return switch (plan.status()) {
            case KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT -> {
                navigation.beginNavigate(plan.route().orElseThrow(), APPROACH_TOLERANCE);
                waitingEvidence = null;
                yield V2BreakJobExecution.StepResult.RUNNING;
            }
            case BLOCKED, REACHED_KNOWN_GOAL -> {
                waitingEvidence = map.edges();
                approachEvidenceWaitTicks = 0;
                yield V2BreakJobExecution.StepResult.RUNNING;
            }
            case LIMIT, CANCELLED, STALE_MAP, WORLD_MISMATCH ->
                    V2BreakJobExecution.StepResult.FAILED;
        };
    }

    @Override
    public void close() {
        if (attack != null) {
            attack.close();
            attack = null;
            attackRequest = null;
        }
        if (facing != null) {
            facing.close();
            facing = null;
        }
        if (navigation != null) {
            navigation.close();
            navigation = null;
        }
        var player = minecraft.player;
        if (player != null && originalSlot >= 0
                && player.getInventory().getSelectedSlot() == selectedToolSlot) {
            player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        selectedToolSlot = -1;
        target = null;
        request = null;
        planner = null;
        waitingEvidence = null;
        approachEvidenceWaitTicks = 0;
        blockId = null;
        crosshairWaitTicks = 0;
        stage = Stage.IDLE;
    }

    private int bestHotbarTool(BlockState state) {
        var inventory = minecraft.player.getInventory();
        var hotbar = new ArrayList<ToolCandidate>(Inventory.getSelectionSize());
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            boolean available = !stack.isDamageableItem()
                    || stack.getMaxDamage() - stack.getDamageValue() >= 1;
            boolean harvests = !state.requiresCorrectToolForDrops()
                    || !stack.isEmpty() && stack.isCorrectToolForDrops(state);
            float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
            hotbar.add(new ToolCandidate(available, harvests, speed));
        }
        return chooseHotbarTool(hotbar, inventory.getSelectedSlot());
    }

    static int chooseHotbarTool(List<ToolCandidate> hotbar, int selectedSlot) {
        if (hotbar.size() != Inventory.getSelectionSize()
                || selectedSlot < 0 || selectedSlot >= hotbar.size()) {
            throw new IllegalArgumentException("invalid hotbar selection");
        }
        int best = -1;
        boolean bestHarvests = false;
        float bestSpeed = -1.0F;
        for (int offset = 0; offset < hotbar.size(); offset++) {
            int slot = (selectedSlot + offset) % hotbar.size();
            var candidate = hotbar.get(slot);
            if (!candidate.available()) continue;
            if (best < 0 || candidate.harvests() && !bestHarvests
                    || candidate.harvests() == bestHarvests && candidate.speed() > bestSpeed) {
                best = slot;
                bestHarvests = candidate.harvests();
                bestSpeed = candidate.speed();
            }
        }
        return best >= 0 ? best : selectedSlot;
    }

    record ToolCandidate(boolean available, boolean harvests, float speed) { }

    private boolean crosshairOnTarget() {
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(new BlockPos(target.x(), target.y(), target.z()));
    }

    /** A clear current ray proves air; the target state is read only after both ray checks. */
    private boolean visiblyAir(BlockPos position) {
        var player = minecraft.player;
        var level = minecraft.level;
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(position);
        for (BlockPos traversed : BlockPos.betweenClosed(BlockPos.containing(eye), position)) {
            if (!level.isLoaded(traversed)) return false;
        }
        var visual = level.clip(new ClipContext(eye, center,
                ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, player));
        if (visual.getType() != HitResult.Type.MISS) return false;
        var collision = level.clip(new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        return collision.getType() == HitResult.Type.MISS
                && level.getBlockState(position).isAir();
    }

    private enum Stage { IDLE, APPROACH, FACING, WAIT_CROSSHAIR, BREAKING }
}
