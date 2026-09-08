package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.LocalArmingState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;

/** 旧routineの入力検証と受付。期限・終了処理はRoutineLifecycleに委ねる。 */
final class RoutineAdmission {
    private final RoutineManager routines;
    private final RoutineLifecycle lifecycle;
    private final LocalArmingState arming;
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final MinecraftFinitePlanPort finitePlanPort;

    RoutineAdmission(RoutineManager routines, RoutineLifecycle lifecycle, LocalArmingState arming,
            MinecraftStationaryBreakPort stationaryBreakPort,
            MinecraftSemanticActionPort semanticActionPort, MinecraftFinitePlanPort finitePlanPort) {
        this.routines = routines;
        this.lifecycle = lifecycle;
        this.arming = arming;
        this.stationaryBreakPort = stationaryBreakPort;
        this.semanticActionPort = semanticActionPort;
        this.finitePlanPort = finitePlanPort;
    }

    Map<String, Object> listRoutines(Map<String, Object> arguments) {
        Object kind = arguments.get("kind");
        return kind == null
                ? RoutineCatalog.routineCatalog()
                : RoutineCatalog.routineCatalog(RuntimeArguments.stringArgument(arguments, "kind"));
    }

    Map<String, Object> getRoutine(Map<String, Object> arguments) {
        var routineId = RuntimeArguments.uuidArgument(arguments, "routine_id");
        long afterEventSeq = RuntimeArguments.optionalLong(arguments, "after_event_seq", 0);
        int maxEvents = Math.toIntExact(RuntimeArguments.optionalLong(arguments, "max_events", 32));
        return RoutineWireMapper.toMap(routines.getRoutine(routineId, afterEventSeq, maxEvents));
    }

    Map<String, Object> startRoutine(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            RuntimeCallContext context) {
        RoutineArguments.requireStartRoutineKeys(arguments);
        String kind = RuntimeArguments.stringArgument(arguments, "kind");
        if (!ActionWireMapper.AVAILABLE_CAPABILITIES.contains(kind)) {
            throw new IllegalArgumentException("kind is not an available routine");
        }
        String completionIntent = RuntimeArguments.completionIntentArgument(arguments);
        return switch (kind) {
            case "stationary_break" -> startStationaryBreak(
                    minecraft, session, arguments, completionIntent, context);
            case NavigateToRequest.KIND,
                    BreakBlockRequest.KIND,
                    PlaceBlockRequest.KIND,
                    InteractBlockRequest.KIND,
                    InteractEntityRequest.KIND,
                    UseItemOnBlockRequest.KIND -> startSemanticAction(
                            minecraft, session, arguments, completionIntent, context);
            case ApplyBlockPlanRequest.KIND -> startApplyBlockPlan(
                    minecraft, session, arguments, completionIntent, context);
            case "craft_items", "transfer_items", "tend_crop_area",
                    "harvest_tree_area", "sleep_at_bed", "survey_area" -> startPhaseFive(
                            minecraft, session, arguments, completionIntent, context);
            case "execute_plan" -> startFinitePlan(
                    session, arguments, completionIntent, context);
            default -> throw new IllegalArgumentException("kind is not an available routine");
        };
    }

    private Map<String, Object> startStationaryBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var parameters = RuntimeArguments.objectArgument(arguments, "parameters");
        RuntimeArguments.requireExactKeys(parameters, "stationary_break parameters", Set.of(
                "target", "allowed_blocks", "goal", "regeneration_timeout_seconds"));
        var target = RoutineArguments.dimensionBlockTargetArgument(parameters, "target");
        var bounds = RoutineArguments.actionBoundsArgument(arguments, session);
        if (!bounds.contains(target)
                || bounds.maxTravelBlocks() != 0
                || !bounds.allowBreak()
                || bounds.maxDurationSeconds() > 60) {
            throw new IllegalArgumentException("stationary_break target/bounds are inconsistent");
        }

        Set<String> allowedBlocks = RuntimeArguments.stringSetArgument(parameters, "allowed_blocks");
        RoutineArguments.validateStationaryBreakAllowedBlocks(allowedBlocks);
        var goalMap = RuntimeArguments.objectArgument(parameters, "goal");
        RuntimeArguments.requireExactKeys(goalMap, "goal", Set.of("item", "minimum_inventory_count"));
        var goal = new StationaryBreakGoal(
                RuntimeArguments.stringArgument(goalMap, "item"),
                RuntimeArguments.intArgument(goalMap, "minimum_inventory_count"));
        int maxDurationSeconds = bounds.maxDurationSeconds();
        int regenerationSeconds = RuntimeArguments.intArgument(parameters, "regeneration_timeout_seconds");
        if (regenerationSeconds < 1 || regenerationSeconds > 10) {
            throw new IllegalArgumentException("regeneration_timeout_seconds must be in 1..10");
        }
        String idempotencyKey = RuntimeArguments.stringArgument(arguments, "idempotency_key");
        String requestIdentity = RoutineIdentity.stationaryBreakIdentity(
                target,
                allowedBlocks,
                goal,
                bounds.minimum(),
                bounds.maximum(),
                maxDurationSeconds,
                regenerationSeconds,
                completionIntent);
        var replay = replayStationaryBreakAfterFinalizationGate(
                lifecycle.finalizationRetries(),
                routines,
                idempotencyKey,
                requestIdentity,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        lifecycle.requireLiveCall(context);

        if (!arming.allows(session.worldSessionId(), "stationary_break")) {
            throw new RuntimeInvocationException(
                    "locked",
                    "stationary_break is not armed for this world session",
                    false,
                    Map.of());
        }
        RoutineArguments.validateLiveBounds(minecraft, bounds, target);

        final BlockStateFingerprint expectedSource;
        try {
            expectedSource = stationaryBreakPort.captureExpectedSource(target, allowedBlocks);
        }
        catch (IllegalArgumentException | IllegalStateException failure) {
            throw new RuntimeInvocationException(
                    "unsafe_state", RuntimeFailures.publicMessage(failure), true, Map.of("target", "not_ready"));
        }
        long hardDeadlineTick = ActionBudgets.saturatingAdd(
                session.clientTick(), Math.multiplyExact(maxDurationSeconds, 20L));
        int regenerationTicks = Math.multiplyExact(regenerationSeconds, 20);
        var request = new StationaryBreakRequest(
                target,
                expectedSource,
                goal,
                hardDeadlineTick,
                StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                regenerationTicks);

        var receipt = lifecycle.admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent, maxDurationSeconds,
                () -> routines.startStationaryBreak(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startSemanticAction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var request = RoutineArguments.semanticActionArgument(arguments, session);
        var idempotencyKey = RuntimeArguments.stringArgument(arguments, "idempotency_key");
        var requestIdentity = RoutineIdentity.semanticActionIdentity(request, completionIntent);
        var replay = replaySemanticActionAfterFinalizationGate(
                lifecycle.finalizationRetries(),
                routines,
                idempotencyKey,
                requestIdentity,
                request,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        lifecycle.requireLiveCall(context);
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
                    false,
                    Map.of());
        }
        RoutineArguments.validateLiveBounds(minecraft, request.bounds(), RoutineArguments.semanticTarget(request).orElse(null));
        if (request instanceof PlaceBlockRequest place) {
            try {
                semanticActionPort.requireSafePlacementSupportForAdmission(place);
            } catch (SafePlacementSupportPolicy.UnsafePlacementSupportException rejected) {
                throw new RuntimeInvocationException(
                        "unsafe_state", SafePlacementSupportPolicy.REJECTION_MESSAGE, true,
                        Map.of("placement_support", "not_safe"));
            }
        }

        var receipt = lifecycle.admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent,
                request.bounds().maxDurationSeconds(), () -> routines.startSemanticAction(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startApplyBlockPlan(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var parsed = RoutineArguments.applyBlockPlanArgument(arguments, session.dimension());
        var request = parsed.request();
        var idempotencyKey = RuntimeArguments.stringArgument(arguments, "idempotency_key");
        var replay = replayApplyBlockPlanAfterFinalizationGate(
                lifecycle.finalizationRetries(),
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow(), parsed.resourceEstimate());
        }

        lifecycle.requireLiveCall(context);
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
                    false,
                    Map.of());
        }
        for (var step : request.steps()) {
            RoutineArguments.validateLiveBounds(minecraft, request.bounds(), step.target());
        }
        RoutineArguments.validateApplyBlockPlanItems(request);

        var receipt = lifecycle.admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent,
                request.bounds().maxDurationSeconds(), () -> routines.startApplyBlockPlan(
                idempotencyKey,
                parsed.requestIdentity(),
                request,
                session.clientTick()));
        return startReceiptPayload(receipt, parsed.resourceEstimate());
    }

    private Map<String, Object> startPhaseFive(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var parsed = RoutineArguments.phaseFiveRequestArgument(arguments, session.dimension());
        var request = parsed.request();
        String idempotencyKey = RuntimeArguments.stringArgument(arguments, "idempotency_key");
        var replay = replayPhaseFiveAfterFinalizationGate(
                lifecycle.finalizationRetries(),
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                request,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        lifecycle.requireLiveCall(context);
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
                    false,
                    Map.of());
        }
        RoutineArguments.validateLiveBounds(minecraft, request.bounds(), parsed.targets());

        var receipt = lifecycle.admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent,
                request.bounds().maxDurationSeconds(), () -> routines.startPhaseFive(
                idempotencyKey,
                parsed.requestIdentity(),
                request,
                session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startFinitePlan(
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var parsed = RoutineArguments.finitePlanRequestArgument(arguments);
        var request = parsed.request();
        String idempotencyKey = RuntimeArguments.stringArgument(arguments, "idempotency_key");
        var replay = replayFinitePlanAfterFinalizationGate(
                lifecycle.finalizationRetries(),
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        lifecycle.requireLiveCall(context);
        if (!arming.allows(session.worldSessionId(), "execute_plan")) {
            throw new RuntimeInvocationException(
                    "locked",
                    "execute_plan is not armed for this world session",
                    false,
                    Map.of());
        }
        finitePlanPort.validate(request);
        int maxDurationSeconds = (request.maxTicks() + 19) / 20;
        var receipt = lifecycle.admitWithVoiceSafety(
                context,
                session.worldSessionId(),
                completionIntent,
                maxDurationSeconds,
                () -> routines.startFinitePlan(
                        idempotencyKey,
                        parsed.requestIdentity(),
                        request,
                        session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startReceiptPayload(RoutineManager.StartReceipt receipt) {
        return startReceiptPayload(receipt, null);
    }

    private Map<String, Object> startReceiptPayload(
            RoutineManager.StartReceipt receipt,
            Map<String, Object> resourceEstimate) {
        var snapshot = routines.getRoutine(receipt.routineId(), Long.MAX_VALUE, 1);
        var result = new LinkedHashMap<String, Object>();
        result.put("routine_id", receipt.routineId().toString());
        result.put("kind", snapshot.kind());
        result.put("state", snapshot.state().name());
        result.put("idempotent_replay", receipt.reused());
        result.put("resource_estimate", resourceEstimate);
        return result;
    }

    Map<String, Object> cancelRoutine(Minecraft minecraft, Map<String, Object> arguments) {
        var routineId = RuntimeArguments.uuidArgument(arguments, "routine_id");
        var before = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        boolean alreadyTerminal = before.state().terminal();
        if (alreadyTerminal) {
            return Map.of(
                    "routine_id", routineId.toString(),
                    "state", before.state().name(),
                    "released_inputs", RoutineLifecycle.finalizationReleasedInputs(before),
                    "already_terminal", true);
        }
        var cancelled = routines.cancelRoutine(
                routineId, RuntimeArguments.stringArgument(arguments, "reason"), Long.MAX_VALUE, 1);
        var cleanup = lifecycle.finalizeTerminalRoutine(minecraft, cancelled);
        return Map.of(
                "routine_id", routineId.toString(),
                "state", cleanup.snapshot().state().name(),
                "released_inputs", cleanup.inputsReleased(),
                "already_terminal", alreadyTerminal);
    }

    static void requireNoPendingFinalizations(FinalizationRetryQueue retries) {
        Objects.requireNonNull(retries, "retries");
        if (retries.hasPending()) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "A previous routine finalization is still pending",
                    true,
                    Map.of(
                            "reason", "finalization_pending",
                            "pending_finalizations", retries.pendingCount()));
        }
    }

    static Optional<RoutineManager.StartReceipt> replayStationaryBreakAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replayStationaryBreak(idempotencyKey, requestIdentity, clientTick);
    }

    static Optional<RoutineManager.StartReceipt> replaySemanticActionAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            SemanticActionRequest request,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replaySemanticAction(
                idempotencyKey, requestIdentity, request, clientTick);
    }

    static Optional<RoutineManager.StartReceipt> replayApplyBlockPlanAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replayApplyBlockPlan(
                idempotencyKey, requestIdentity, clientTick);
    }

    static Optional<RoutineManager.StartReceipt> replayPhaseFiveAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            PhaseFiveRequest request,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replayPhaseFive(
                idempotencyKey, requestIdentity, request, clientTick);
    }

    static Optional<RoutineManager.StartReceipt> replayFinitePlanAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replayFinitePlan(idempotencyKey, requestIdentity, clientTick);
    }

}
