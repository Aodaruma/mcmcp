package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.NavCell;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Executes one observed, in-reach vanilla target at a time with normal player input. */
final class MinecraftV2BreakDriver implements V2BreakJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final MinecraftStationaryBreakPort port;
    private MinecraftActionPrimitiveExecutor facing;
    private KnownBlockBreakAttempt attack;
    private StationaryBreakRequest attackRequest;
    private NavCell target;
    private String blockId;
    private Stage stage = Stage.IDLE;
    private int originalSlot = -1;
    private int selectedToolSlot = -1;
    private int crosshairWaitTicks;

    MinecraftV2BreakDriver(Minecraft minecraft,
            Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations,
            ClientReconciliationSignals reconciliationSignals,
            MinecraftStationaryBreakPort port) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.reconciliationSignals = Objects.requireNonNull(reconciliationSignals, "reconciliationSignals");
        this.port = Objects.requireNonNull(port, "port");
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
        target = next;
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
                    Math.addExact(clientTick, 60L),
                    StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS, 1);
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
        var player = minecraft.player;
        if (player != null && originalSlot >= 0
                && player.getInventory().getSelectedSlot() == selectedToolSlot) {
            player.getInventory().setSelectedSlot(originalSlot);
        }
        originalSlot = -1;
        selectedToolSlot = -1;
        target = null;
        blockId = null;
        crosshairWaitTicks = 0;
        stage = Stage.IDLE;
    }

    private int bestHotbarTool(BlockState state) {
        var inventory = minecraft.player.getInventory();
        int best = inventory.getSelectedSlot();
        float speed = 1.0F;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()
                    && stack.getMaxDamage() - stack.getDamageValue() < 1) continue;
            float candidate = stack.getDestroySpeed(state);
            if (candidate > speed) {
                best = slot;
                speed = candidate;
            }
        }
        return best;
    }

    private boolean crosshairOnTarget() {
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(new BlockPos(target.x(), target.y(), target.z()));
    }

    private enum Stage { IDLE, FACING, WAIT_CROSSHAIR, BREAKING }
}
