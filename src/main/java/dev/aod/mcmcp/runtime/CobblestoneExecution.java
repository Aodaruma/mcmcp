package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakOperation;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** 再生成を含む有限破壊operationと、確認済みcheckpoint・未確認dispatchを所有する。 */
final class CobblestoneExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private StationaryBreakOperation cobblestoneGeneratorAttempt;
    private long cobblestoneGeneratorCheckpoint;
    private boolean cobblestoneGeneratorUnknownRecorded;
    private ActionDsl.OperateKnownCobblestoneGenerator operation;
    private int agentSelectedSlot = -1;

    CobblestoneExecution(UUID actionId, AgentActionStore agentActions,
            MinecraftStationaryBreakPort stationaryBreakPort) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.stationaryBreakPort = stationaryBreakPort;
    }

    int selectedSlot() { return agentSelectedSlot; }

    boolean close() {
        boolean closed = true;
        if (cobblestoneGeneratorAttempt != null) {
            try {
                if (operation != null) {
                    RoutineSnapshot snapshot =
                            cobblestoneGeneratorAttempt.snapshot();
                    recordCobblestoneGeneratorCheckpoints(
                            operation, snapshot);
                    recordUnconfirmedCobblestoneGeneratorDispatch(operation, snapshot);
                }
                cobblestoneGeneratorAttempt.close();
                cobblestoneGeneratorAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error(
                        "MCMCP cobblestone-generator release failed", failure);
            }
        }
        return closed;
    }

    PrimitiveOutcome tick(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.OperateKnownCobblestoneGenerator operation) {
        agentSelectedSlot = -1;
        this.operation = operation;
        var player = Objects.requireNonNull(minecraft.player, "player");
        ActionDsl.BreakKnownBlock block = KnownBreakSafety.cobblestoneGeneratorBreak(operation);
        if (cobblestoneGeneratorAttempt == null) {
            int currentCount = PlayerInventoryEvidence.inventoryItemCount(player, operation.expectedDrop());
            if (currentCount < operation.minimumInventoryCount()
                    && operation.minimumInventoryCount() - currentCount > operation.maxBreaks()) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "cobblestone_goal_exceeds_max_breaks");
            }
            int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                    player, operation.toolItem(), operation.maxBreaks());
            if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(player, action.program())) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        toolSlot < 0
                                ? "required_iron_pickaxe_unavailable"
                                : "inventory_full");
            }
            if (!KnownBreakSafety.breakTargetStateMatches(minecraft, block)
                    || !KnownBreakSafety.breakSourceControlled(minecraft, block)) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "cobblestone_generator_target_or_face_changed");
            }
            try {
                player.getInventory().setSelectedSlot(toolSlot);
                agentSelectedSlot = toolSlot;
                var target = new BlockTarget(
                        operation.target().dimension(), operation.target().x(),
                        operation.target().y(), operation.target().z());
                BlockStateFingerprint observed = stationaryBreakPort.captureExpectedSource(
                        target, Set.of("minecraft:cobblestone"));
                var expected = new BlockStateFingerprint(
                        operation.expectedState().block(),
                        operation.expectedState().properties());
                if (!expected.equals(observed)) {
                    return PrimitiveOutcome.failed(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            "cobblestone_generator_state_changed");
                }
                var request = new StationaryBreakRequest(
                        target,
                        expected,
                        new StationaryBreakGoal(
                                operation.expectedDrop(), operation.minimumInventoryCount()),
                        Math.addExact(
                                session.clientTick(), operation.maxOperationDurationTicks()),
                        StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                        operation.regenerationWaitTicks());
                cobblestoneGeneratorAttempt = new StationaryBreakOperation(
                        stationaryBreakPort, request, operation.maxBreaks(), session.clientTick());
                cobblestoneGeneratorCheckpoint = 0L;
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error(
                        "MCMCP cobblestone-generator operation could not start", failure);
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "cobblestone_generator_start_failed");
            }
        }

        // Air is the expected neutral regeneration wait. Once cobblestone is present again,
        // exact target, state, reach, tool, and the operation's unchanged view are rechecked.
        // The hit face may legitimately flip at the same coordinate as the block regenerates.
        var generatorSnapshot = cobblestoneGeneratorAttempt.snapshot();
        if ("execute".equals(generatorSnapshot.phase())
                && !KnownBreakSafety.breakSourceControlled(minecraft, block, false)) {
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                    true,
                    "cobblestone_generator_stationary_face_changed");
        }

        final StationaryBreakOperation.TickResult result;
        try {
            result = cobblestoneGeneratorAttempt.tick();
            recordCobblestoneGeneratorCheckpoints(
                    operation, result.snapshot());
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP cobblestone-generator confirmation failed", failure);
            return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "cobblestone_generator_confirmation_failed");
        }
        switch (result.status()) {
            case RUNNING -> { }
            case SUCCEEDED -> {
                try {
                    cobblestoneGeneratorAttempt.close();
                    cobblestoneGeneratorAttempt = null;
                } catch (RuntimeException | LinkageError releaseFailure) {
                    return PrimitiveOutcome.failed(
                            AgentActionStore.FailureCode.INTERNAL_ERROR,
                            true,
                            "cobblestone_generator_release_failed");
                }
                return PrimitiveOutcome.succeeded();
            }
            case MAX_BREAKS_REACHED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                    true,
                    "cobblestone_generator_max_breaks_reached"); }
            case FAILED -> {
                RoutineFailure failure = result.snapshot().failure();
                AgentActionStore.FailureCode code = failure != null
                                && failure.category() == RoutineFailure.Category.SAFETY
                        ? AgentActionStore.FailureCode.SAFETY_INTERRUPTED
                        : failure != null && "HARD_DEADLINE_EXPIRED".equals(failure.code())
                                ? AgentActionStore.FailureCode.CONDITION_TIMEOUT
                                : failure != null
                                        && (failure.category()
                                                        == RoutineFailure.Category.PRECONDITION
                                                || failure.category()
                                                        == RoutineFailure.Category.DIVERGENCE)
                                                ? AgentActionStore.FailureCode.WORLD_CHANGED
                                                : AgentActionStore.FailureCode
                                                        .SERVER_DENIED_OR_DESYNC;
                return PrimitiveOutcome.failed(
                        code,
                        failure == null || failure.retryable(),
                        "cobblestone_generator_"
                                + (failure == null ? "failed"
                                        : failure.code().toLowerCase(Locale.ROOT)));
            }
        }
        return PrimitiveOutcome.running();
    }

    private void recordCobblestoneGeneratorCheckpoints(
            ActionDsl.OperateKnownCobblestoneGenerator operation,
            RoutineSnapshot snapshot) {
        long checkpoint = snapshot.checkpoint().seq();
        while (cobblestoneGeneratorCheckpoint < checkpoint) {
            cobblestoneGeneratorCheckpoint++;
            agentActions.recordBlockBreak(actionId);
            agentActions.recordEffect(
                    actionId,
                    "block_break",
                    "block:" + operation.target().dimension() + ":"
                            + operation.target().x() + "," + operation.target().y() + ","
                            + operation.target().z(),
                    Map.of(
                            "block", operation.expectedState().block(),
                            "properties", operation.expectedState().properties(),
                            "cycle", cobblestoneGeneratorCheckpoint),
                    Map.of(
                            "block", "minecraft:air",
                            "properties", Map.of(),
                            "inventory_count", snapshot.progress().completed()),
                    AgentActionStore.Verification.CONFIRMED,
                    snapshot.lastClientTick(),
                    snapshot.checkpoint().observationRevision());
        }
    }

    private void recordUnconfirmedCobblestoneGeneratorDispatch(
            ActionDsl.OperateKnownCobblestoneGenerator operation,
            RoutineSnapshot snapshot) {
        if (cobblestoneGeneratorUnknownRecorded) return;
        Object rawAttempts = snapshot.diagnostics().get("attempts");
        long attempts = rawAttempts instanceof Number number ? number.longValue() : 0L;
        if (attempts <= snapshot.checkpoint().seq()) return;
        agentActions.recordEffect(
                actionId,
                "block_break",
                "block:" + operation.target().dimension() + ":"
                        + operation.target().x() + "," + operation.target().y() + ","
                        + operation.target().z(),
                Map.of(
                        "block", operation.expectedState().block(),
                        "properties", operation.expectedState().properties(),
                        "cycle", attempts),
                Map.of(),
                AgentActionStore.Verification.UNKNOWN,
                snapshot.lastClientTick(),
                snapshot.checkpoint().observationRevision());
        cobblestoneGeneratorUnknownRecorded = true;
    }
}
