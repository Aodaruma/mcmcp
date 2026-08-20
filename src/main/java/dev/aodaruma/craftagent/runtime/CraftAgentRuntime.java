package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.CraftAgentMod;
import dev.aodaruma.craftagent.mcp.McpRuntimePort;
import dev.aodaruma.craftagent.mcp.McpToolSchemas;
import dev.aodaruma.craftagent.mcp.RuntimeCallContext;
import dev.aodaruma.craftagent.observation.BlockPlanComparator;
import dev.aodaruma.craftagent.observation.MinecraftObservationService;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.safety.InputReleaseController;
import dev.aodaruma.craftagent.safety.LocalArmingState;
import dev.aodaruma.craftagent.routine.ActionBounds;
import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.BlockStateFingerprint;
import dev.aodaruma.craftagent.routine.BreakBlockRequest;
import dev.aodaruma.craftagent.routine.InteractBlockRequest;
import dev.aodaruma.craftagent.routine.InteractEntityRequest;
import dev.aodaruma.craftagent.routine.MinecraftSemanticActionPort;
import dev.aodaruma.craftagent.routine.MinecraftStationaryBreakPort;
import dev.aodaruma.craftagent.routine.NavigateToRequest;
import dev.aodaruma.craftagent.routine.PlaceBlockRequest;
import dev.aodaruma.craftagent.routine.RoutineManager;
import dev.aodaruma.craftagent.routine.RoutineFailure;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;
import dev.aodaruma.craftagent.routine.SemanticActionRequest;
import dev.aodaruma.craftagent.routine.StationaryBreakGoal;
import dev.aodaruma.craftagent.routine.StationaryBreakRequest;
import dev.aodaruma.craftagent.voice.SimpleVoiceChat2622Adapter;
import dev.aodaruma.craftagent.voice.VoiceChatAdapter;
import dev.aodaruma.craftagent.voice.VoiceChatEventBridge;
import dev.aodaruma.craftagent.voice.VoiceChatSafetyController;
import dev.aodaruma.craftagent.voice.VoiceTransmissionGuard;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Client runtime and the sole implementation of the MCP-to-Minecraft boundary. */
public final class CraftAgentRuntime implements McpRuntimePort {
    private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
    /** Expanded only when a phase has passed its gate. */
    private static final Set<String> AVAILABLE_CAPABILITIES = Set.of(
            "stationary_break",
            NavigateToRequest.KIND,
            BreakBlockRequest.KIND,
            PlaceBlockRequest.KIND,
            InteractBlockRequest.KIND,
            InteractEntityRequest.KIND);

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final WorldMemory memory = new WorldMemory();
    private final MinecraftObservationService observations = new MinecraftObservationService(memory);
    private final BlockPlanComparator blockPlans = new BlockPlanComparator(observations, memory);
    private final LocalArmingState arming = new LocalArmingState();
    private final InputReleaseController inputRelease = new InputReleaseController();
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final ClientReconciliationSignals reconciliationSignals;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final RoutineManager routines;
    private final VoiceChatSafetyController voiceChat;
    private final ClientCommandInbox inbox;
    private final FinalizationRetryQueue finalizationRetries = new FinalizationRetryQueue();

    private UUID voiceRoutineId;

    private volatile WorldSessionTracker.Snapshot publishedSession = sessions.snapshot();
    private volatile boolean paused;
    private volatile boolean shutdown;

    public CraftAgentRuntime(String modVersion, String neoForgeVersion) {
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
        this.neoForgeVersion = Objects.requireNonNull(neoForgeVersion, "neoForgeVersion");
        stationaryBreakPort = new MinecraftStationaryBreakPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global());
        reconciliationSignals = ClientReconciliationSignals.global();
        semanticActionPort = new MinecraftSemanticActionPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global(),
                reconciliationSignals);
        routines = new RoutineManager(stationaryBreakPort, semanticActionPort);
        voiceChat = new VoiceChatSafetyController(
                SimpleVoiceChat2622Adapter.forNeoForge(() -> {
                    var minecraft = Minecraft.getInstance();
                    return minecraft != null && minecraft.isSameThread();
                }),
                VoiceTransmissionGuard.GLOBAL,
                VoiceChatEventBridge.GLOBAL,
                true,
                this::requestSafetyStop);
        inbox = new ClientCommandInbox(
                ClientCommandInbox.DEFAULT_CAPACITY,
                inputRelease,
                arming,
                this::stopActiveRoutineForEmergency);
    }

    public void onResourcesReady() {
        sessions.resourcesReady();
        publishSession();
    }

    public void onLoggingIn(Minecraft minecraft) {
        assertClientThread(minecraft);
        stopForLifecycle(minecraft, "world_join");
        routines.clearSession("world_join");
        finalizationRetries.clear();
        voiceRoutineId = null;
        stationaryBreakPort.clearSession();
        semanticActionPort.clearSession();
        reconciliationSignals.closeLevel(minecraft.level);
        sessions.beginConnection();
        arming.lock("world_join");
        publishSession();
    }

    public void onLevelUnload(Minecraft minecraft) {
        assertClientThread(minecraft);
        stopForLifecycle(minecraft, "level_or_dimension_change");
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        stationaryBreakPort.clearSession();
        semanticActionPort.clearSession();
        reconciliationSignals.closeLevel(minecraft.level);
        sessions.suspendWorld();
        arming.lock("level_or_dimension_change");
        publishSession();
    }

    public void onLoggingOut(Minecraft minecraft) {
        assertClientThread(minecraft);
        stopForLifecycle(minecraft, "disconnect");
        routines.clearSession("disconnect");
        finalizationRetries.clear();
        voiceRoutineId = null;
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        stationaryBreakPort.clearSession();
        semanticActionPort.clearSession();
        reconciliationSignals.closeLevel(minecraft.level);
        sessions.invalidate();
        memory.detachSession();
        arming.lock("disconnect");
        publishSession();
    }

    public void onPlayerClone(Minecraft minecraft) {
        assertClientThread(minecraft);
        inbox.requestEmergencyStop("player_respawn");
    }

    public void onPauseChanged(boolean paused) {
        this.paused = paused;
        if (paused) {
            stopForPriorityClientEvent("client_paused");
        }
    }

    public void onPreTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        synchronizeWorld(minecraft);
        sessions.tick();
        publishSession();
        inbox.drainEmergencyStopPreTick(minecraft, publishedSession);
        inbox.drainControlsPreTick(sessions.snapshot());
        retryPendingFinalizations(minecraft);
        tickActiveRoutine(minecraft);
        publishSession();
    }

    public void onPostTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        try {
            stationaryBreakPort.captureIssuedPredictions();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent stationary-break prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        try {
            semanticActionPort.captureIssuedPredictions();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent semantic-action prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        inbox.drainReadsPostTick(sessions.snapshot());
        publishSession();
    }

    public void toggleLocalArming(Minecraft minecraft) {
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        if (!session.worldReady()) {
            overlay(minecraft, "CraftAgent: ワールド準備前のため解除できません");
            return;
        }
        var current = arming.snapshot(session.worldSessionId(), System.nanoTime());
        if (!current.locked()) {
            runPriorityStop(
                    () -> inbox.requestEmergencyStop("local_key"),
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
            overlay(minecraft, "CraftAgent: ロックしました");
        } else {
            arming.arm(session.worldSessionId(), AVAILABLE_CAPABILITIES,
                    LocalArmingState.DEFAULT_ARM_DURATION, System.nanoTime());
            overlay(minecraft, "CraftAgent: 15分間ローカル解除しました");
        }
    }

    public void emergencyStopFromLocalKey(Minecraft minecraft) {
        assertClientThread(minecraft);
        runPriorityStop(
                () -> inbox.requestEmergencyStop("local_emergency_key"),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        overlay(minecraft, "CraftAgent: 緊急停止・ロック済み");
    }

    /** Local controls are already on the client thread and must finish the priority stop inline. */
    static void runPriorityStop(Runnable request, Runnable drain) {
        Objects.requireNonNull(request, "request").run();
        Objects.requireNonNull(drain, "drain").run();
    }

    /** Returns whether an active/pending start was stopped before the caller can continue. */
    static boolean runPriorityEventStopIfRequired(
            boolean workPending,
            Runnable request,
            Runnable drain) {
        if (!workPending) {
            return false;
        }
        runPriorityStop(request, drain);
        return true;
    }

    public void shutdown(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        shutdown = true;
        sessions.stopping();
        inbox.shutdown(minecraft, sessions.snapshot());
        routines.clearSession("client_shutdown");
        finalizationRetries.clear();
        voiceRoutineId = null;
        voiceChat.close();
        stationaryBreakPort.clearSession();
        semanticActionPort.clearSession();
        reconciliationSignals.closeLevel(minecraft.level);
        memory.detachSession();
        arming.lock("client_shutdown");
        publishSession();
    }

    @Override
    public CompletionStage<RuntimeReply> submit(RuntimeCommand command, RuntimeCallContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (command instanceof EmergencyStop stop) {
            return inbox.requestEmergencyStop(stop.reason())
                    .thenApply(receipt -> RuntimeReply.success(Map.of(
                            "stop_requested", true,
                            "locked", receipt.locked(),
                            "released_inputs", receipt.inputsReleased(),
                            "discarded_pending_starts", receipt.discardedPendingStarts())));
        }
        if (!context.canBeginWork()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    RuntimeReply.failure("timeout", "The client-thread deadline expired", true));
        }

        var fence = publishedSession;
        var submitted = command instanceof CancelRoutine
                ? inbox.submitControl(command.toolName(), fence.generation(), context.deadlineNanos(), () -> {
                    if (!context.canBeginWork()) {
                        throw new ClientCommandInbox.CommandTimeoutException(command.toolName());
                    }
                    return executeOnClientThread(command, context);
                })
                : inbox.submit(command.toolName(), fence.generation(), context.deadlineNanos(), () -> {
                    if (!context.canBeginWork()) {
                        throw new ClientCommandInbox.CommandTimeoutException(command.toolName());
                    }
                    return executeOnClientThread(command, context);
                });
        return submitted.handle((data, failure) -> failure == null
                        ? RuntimeReply.success(data)
                        : mapFailure(failure));
    }

    private Map<String, Object> executeOnClientThread(
            RuntimeCommand command,
            RuntimeCallContext context) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        return switch (command) {
            case GetStatus ignored -> status(minecraft, session);
            case GetSnapshot snapshot -> {
                requireReady(session);
                yield observations.getSnapshot(minecraft, session.clientTick(), snapshot.arguments());
            }
            case CompareBlockPlan compare -> {
                requireReady(session);
                yield blockPlans.compare(minecraft, session.clientTick(), compare.arguments());
            }
            case ListRoutines ignored -> listRoutines();
            case GetRoutine get -> getRoutine(get.arguments());
            case StartRoutine start -> {
                requireReady(session);
                yield startRoutine(minecraft, session, start.arguments(), context);
            }
            case CancelRoutine cancel -> cancelRoutine(minecraft, cancel.arguments());
            case EmergencyStop ignored -> throw new AssertionError("emergency stop bypasses the normal queue");
        };
    }

    private Map<String, Object> status(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        var lock = arming.snapshot(session.worldSessionId(), System.nanoTime());
        var stats = memory.stats();
        Long unlockExpiryTick = lock.locked()
                ? null
                : session.clientTick() + Math.max(0L, lock.remainingNanos() / Duration.ofMillis(50).toNanos());

        var versions = new LinkedHashMap<String, Object>();
        versions.put("mcp", MCP_PROTOCOL_VERSION);
        versions.put("mod", modVersion);
        // The launcher string is NeoForge's launch target in dev/production and is not
        // the Minecraft data version advertised by the running game.
        versions.put("minecraft", SharedConstants.getCurrentVersion().name());
        versions.put("neoforge", neoForgeVersion);
        versions.put("adapter", "minecraft-26.2-prediction-v1");

        var world = new LinkedHashMap<String, Object>();
        world.put("connected", session.worldReady());
        world.put("dimension", session.dimension());
        world.put("world_session_id", session.worldSessionId() == null ? null : session.worldSessionId().toString());

        var lockPayload = new LinkedHashMap<String, Object>();
        lockPayload.put("locked", lock.locked());
        lockPayload.put("unlock_expires_at_client_tick", unlockExpiryTick);
        lockPayload.put("reason", lock.lastLockReason());

        long evicted = stats.evictedBlocks() + stats.evictedEntities();
        var memoryPayload = new LinkedHashMap<String, Object>();
        memoryPayload.put("count", stats.retainedBlocks() + stats.retainedEntities());
        memoryPayload.put("retention_policy", "session_lru:block=" + stats.blockLimit() + ",entity=" + stats.entityLimit());
        memoryPayload.put("evicted_count", evicted);
        memoryPayload.put("oldest_retained_tick", stats.oldestRetainedTick() == 0 ? null : stats.oldestRetainedTick());
        memoryPayload.put("warning", evicted == 0 ? null : "old observations have been evicted");

        var activeRoutine = routines.activeRoutineId()
                .map(id -> routines.getRoutine(id, Long.MAX_VALUE, 1))
                .map(snapshot -> Map.<String, Object>of(
                        "routine_id", snapshot.routineId().toString(),
                        "kind", snapshot.kind(),
                        "state", snapshot.state().name()))
                .orElse(null);

        var voiceSnapshot = voiceChat.snapshot();
        var voiceStatus = switch (voiceSnapshot.availability()) {
            case NOT_INSTALLED -> "unavailable";
            case READY -> voiceSnapshot.stateFailureCode() != null
                            || Boolean.FALSE.equals(voiceSnapshot.connected())
                    ? "error"
                    : voiceSnapshot.active() || Boolean.TRUE.equals(voiceSnapshot.muted())
                            ? "muted"
                            : "ready";
            case INCOMPATIBLE, UNAVAILABLE -> "error";
        };

        var result = new LinkedHashMap<String, Object>();
        result.put("versions", versions);
        result.put("world", world);
        result.put("lock", lockPayload);
        result.put("capability_profile", AVAILABLE_CAPABILITIES.stream().sorted().toList());
        var voiceChat = new LinkedHashMap<String, Object>();
        voiceChat.put("status", voiceStatus);
        voiceChat.put("adapter_version", voiceSnapshot.adapterVersion());
        voiceChat.put("connected", voiceSnapshot.connected());
        voiceChat.put("muted", voiceSnapshot.muted());
        voiceChat.put("failure", voiceSnapshot.stateFailureCode());
        voiceChat.put("recovery_required", voiceSnapshot.recoveryRequired());
        result.put("voice_chat", voiceChat);
        result.put("policies", Map.of("survival", "stop_and_notify", "completion", "stay"));
        result.put("active_routine", activeRoutine);
        result.put("memory", memoryPayload);
        return result;
    }

    private Map<String, Object> listRoutines() {
        return routineCatalog();
    }

    static Map<String, Object> routineCatalog() {
        return Map.of(
                "catalog_version", "phase-3",
                "routines", List.of(
                        routineCatalogEntry(
                                "stationary_break",
                                2,
                                McpToolSchemas.stationaryBreakStartInput(),
                                List.of(
                                        "each counted break has a covering vanilla prediction ACK",
                                        "each counted break has a server-verified target state transition",
                                        "the synchronized inventory reaches the requested minimum item count",
                                        "all routine-owned attack input is released before terminal state")),
                        routineCatalogEntry(
                                NavigateToRequest.KIND,
                                3,
                                McpToolSchemas.navigateToStartInput(),
                                List.of(
                                        "the destination is reached within the requested horizontal tolerance",
                                        "the settled position is server-reconciled without a position correction",
                                        "all routine-owned movement input is released before verification")),
                        routineCatalogEntry(
                                BreakBlockRequest.KIND,
                                3,
                                McpToolSchemas.breakBlockStartInput(),
                                List.of(
                                        "the requested block transition has a covering vanilla prediction ACK",
                                        "the server-verified target state matches the Phase 3 minecraft:air expected_after",
                                        "all routine-owned attack input is released before verification")),
                        routineCatalogEntry(
                                PlaceBlockRequest.KIND,
                                3,
                                McpToolSchemas.placeBlockStartInput(),
                                List.of(
                                        "exactly one bounded main-hand placement is dispatched",
                                        "the placement has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
                        routineCatalogEntry(
                                InteractBlockRequest.KIND,
                                3,
                                McpToolSchemas.interactBlockStartInput(),
                                List.of(
                                        "exactly one allowlisted block interaction is dispatched",
                                        "expected_after is the exact full same-block toggle state",
                                        "the interaction has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
                        routineCatalogEntry(
                                InteractEntityRequest.KIND,
                                3,
                                McpToolSchemas.interactEntityStartInput(),
                                List.of(
                                        "the opaque entity reference is re-resolved as a visible, reachable, targeted adult cow",
                                        "exactly one main-hand interaction is dispatched with minecraft:bucket and without automatic retry",
                                        "a fresh inbound inventory sync reaches the absolute minecraft:milk_bucket count goal"))));
    }

    private static Map<String, Object> routineCatalogEntry(
            String kind,
            int phase,
            Map<String, Object> inputSchema,
            List<String> postconditions) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("kind", kind);
        entry.put("phase", phase);
        entry.put("experimental", false);
        entry.put("input_schema", inputSchema);
        entry.put("postconditions", postconditions);
        return Map.copyOf(entry);
    }

    private Map<String, Object> getRoutine(Map<String, Object> arguments) {
        var routineId = uuidArgument(arguments, "routine_id");
        long afterEventSeq = optionalLong(arguments, "after_event_seq", 0);
        int maxEvents = Math.toIntExact(optionalLong(arguments, "max_events", 32));
        return RoutineWireMapper.toMap(routines.getRoutine(routineId, afterEventSeq, maxEvents));
    }

    private Map<String, Object> startRoutine(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            RuntimeCallContext context) {
        requireExactKeys(arguments, "start_routine", Set.of(
                "kind", "parameters", "bounds", "completion_intent", "idempotency_key"));
        String kind = stringArgument(arguments, "kind");
        if (!AVAILABLE_CAPABILITIES.contains(kind)) {
            throw new IllegalArgumentException("kind is not an available routine");
        }
        if (!"finish_goal".equals(stringArgument(arguments, "completion_intent"))) {
            throw new IllegalArgumentException("completion_intent must be finish_goal");
        }
        return switch (kind) {
            case "stationary_break" -> startStationaryBreak(
                    minecraft, session, arguments, context);
            case NavigateToRequest.KIND,
                    BreakBlockRequest.KIND,
                    PlaceBlockRequest.KIND,
                    InteractBlockRequest.KIND,
                    InteractEntityRequest.KIND -> startSemanticAction(
                            minecraft, session, arguments, context);
            default -> throw new IllegalArgumentException("kind is not an available routine");
        };
    }

    private Map<String, Object> startStationaryBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            RuntimeCallContext context) {
        var parameters = objectArgument(arguments, "parameters");
        requireExactKeys(parameters, "stationary_break parameters", Set.of(
                "target", "allowed_blocks", "goal", "regeneration_timeout_seconds"));
        var target = dimensionBlockTargetArgument(parameters, "target");
        var bounds = actionBoundsArgument(arguments, session);
        if (!bounds.contains(target)
                || bounds.maxTravelBlocks() != 0
                || !bounds.allowBreak()
                || bounds.maxDurationSeconds() > 60) {
            throw new IllegalArgumentException("stationary_break target/bounds are inconsistent");
        }

        Set<String> allowedBlocks = stringSetArgument(parameters, "allowed_blocks");
        var goalMap = objectArgument(parameters, "goal");
        requireExactKeys(goalMap, "goal", Set.of("item", "minimum_inventory_count"));
        var goal = new StationaryBreakGoal(
                stringArgument(goalMap, "item"),
                intArgument(goalMap, "minimum_inventory_count"));
        int maxDurationSeconds = bounds.maxDurationSeconds();
        int regenerationSeconds = intArgument(parameters, "regeneration_timeout_seconds");
        if (regenerationSeconds < 1 || regenerationSeconds > 10) {
            throw new IllegalArgumentException("regeneration_timeout_seconds must be in 1..10");
        }
        String idempotencyKey = stringArgument(arguments, "idempotency_key");
        String requestIdentity = stationaryBreakIdentity(
                target,
                allowedBlocks,
                goal,
                bounds.minimum(),
                bounds.maximum(),
                maxDurationSeconds,
                regenerationSeconds);
        var replay = replayStationaryBreakAfterFinalizationGate(
                finalizationRetries,
                routines,
                idempotencyKey,
                requestIdentity,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        requireLiveCall(context, "start_routine");

        long nowNanos = System.nanoTime();
        long hardDeadlineNanos = saturatingAdd(
                nowNanos, Duration.ofSeconds(maxDurationSeconds).toNanos());
        if (!arming.allows(
                session.worldSessionId(), "stationary_break", hardDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    "stationary_break is not armed for this world session and deadline",
                    false,
                    Map.of());
        }
        validateLiveBounds(minecraft, bounds, target);

        final BlockStateFingerprint expectedSource;
        try {
            expectedSource = stationaryBreakPort.captureExpectedSource(target, allowedBlocks);
        }
        catch (IllegalArgumentException | IllegalStateException failure) {
            throw new RuntimeInvocationException(
                    "unsafe_state", publicMessage(failure), true, Map.of("target", "not_ready"));
        }
        long hardDeadlineTick = saturatingAdd(
                session.clientTick(), Math.multiplyExact(maxDurationSeconds, 20L));
        int regenerationTicks = Math.multiplyExact(regenerationSeconds, 20);
        var request = new StationaryBreakRequest(
                target,
                expectedSource,
                goal,
                hardDeadlineTick,
                StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                regenerationTicks);

        var receipt = admitWithVoiceSafety(context, () -> routines.startStationaryBreak(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startSemanticAction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            RuntimeCallContext context) {
        var request = semanticActionArgument(arguments, session);
        var idempotencyKey = stringArgument(arguments, "idempotency_key");
        var requestIdentity = semanticActionIdentity(request);
        var replay = replaySemanticActionAfterFinalizationGate(
                finalizationRetries,
                routines,
                idempotencyKey,
                requestIdentity,
                request,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        requireLiveCall(context, "start_routine");
        long nowNanos = System.nanoTime();
        long hardDeadlineNanos = saturatingAdd(
                nowNanos, Duration.ofSeconds(request.bounds().maxDurationSeconds()).toNanos());
        if (!arming.allows(
                session.worldSessionId(), request.kind(), hardDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session and deadline",
                    false,
                    Map.of());
        }
        validateLiveBounds(minecraft, request.bounds(), semanticTarget(request).orElse(null));

        var receipt = admitWithVoiceSafety(context, () -> routines.startSemanticAction(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private RoutineManager.StartReceipt admitWithVoiceSafety(
            RuntimeCallContext context,
            Supplier<RoutineManager.StartReceipt> admission) {
        final VoiceChatSafetyController.BeginResult voiceBegin;
        try {
            requireLiveCall(context, "start_routine");
            voiceBegin = voiceChat.beginAutomation();
        }
        catch (ClientCommandInbox.CommandTimeoutException timeout) {
            // The deadline check happens before Voice Chat is touched, so no rollback is needed.
            throw timeout;
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error("CraftAgent Voice Chat begin safety gate threw", failure);
            var voiceEnd = cleanUpRejectedVoiceBegin();
            var details = new LinkedHashMap<String, Object>();
            details.put("voice.stage", "begin");
            details.put("voice.failure", "voicechat_begin_exception");
            appendVoiceEndDetails(details, voiceEnd);
            throw new RuntimeInvocationException(
                    "unsafe_state", "Voice Chat safety gate failed", true, details);
        }
        if (!voiceBegin.permitted()) {
            var code = voiceBegin.failureCode() != null
                            && (voiceBegin.failureCode().contains("version")
                            || voiceBegin.failureCode().contains("adapter"))
                    ? "incompatible"
                    : "unsafe_state";
            var voiceEnd = cleanUpRejectedVoiceBegin();
            var details = new LinkedHashMap<>(voiceBeginFailureDetails(voiceBegin));
            appendVoiceEndDetails(details, voiceEnd);
            throw new RuntimeInvocationException(
                    code,
                    "Voice Chat safety gate rejected routine start",
                    true,
                    details);
        }

        final RoutineManager.StartReceipt receipt;
        try {
            requireLiveCall(context, "start_routine");
            receipt = admission.get();
        }
        catch (RuntimeException | LinkageError failure) {
            var voiceEnd = endVoiceSessionFor(null);
            throw withVoiceEndFailureDiagnostics(failure, voiceEnd);
        }
        voiceRoutineId = receipt.routineId();
        return receipt;
    }

    private Map<String, Object> startReceiptPayload(RoutineManager.StartReceipt receipt) {
        var snapshot = routines.getRoutine(receipt.routineId(), Long.MAX_VALUE, 1);
        return Map.of(
                "routine_id", receipt.routineId().toString(),
                "kind", snapshot.kind(),
                "state", snapshot.state().name(),
                "idempotent_replay", receipt.reused());
    }

    private static String stationaryBreakIdentity(
            BlockTarget target,
            Set<String> allowedBlocks,
            StationaryBreakGoal goal,
            BlockTarget minimum,
            BlockTarget maximum,
            int maxDurationSeconds,
            int regenerationSeconds) {
        var sortedBlocks = allowedBlocks.stream().sorted().toList();
        return String.join("\u001f",
                target.dimension(),
                Integer.toString(target.x()),
                Integer.toString(target.y()),
                Integer.toString(target.z()),
                String.join(",", sortedBlocks),
                goal.itemId(),
                Integer.toString(goal.minimumInventoryCount()),
                Integer.toString(minimum.x()),
                Integer.toString(minimum.y()),
                Integer.toString(minimum.z()),
                Integer.toString(maximum.x()),
                Integer.toString(maximum.y()),
                Integer.toString(maximum.z()),
                Integer.toString(maxDurationSeconds),
                Integer.toString(regenerationSeconds));
    }

    static String semanticActionIdentity(SemanticActionRequest request) {
        Objects.requireNonNull(request, "request");
        var canonical = new StringBuilder();
        appendIdentity(canonical, request.kind());
        switch (request) {
            case NavigateToRequest navigation -> {
                appendTargetIdentity(canonical, navigation.target());
                appendIdentity(canonical, Double.toHexString(
                        navigation.horizontalToleranceBlocks()));
            }
            case BreakBlockRequest block -> {
                appendTargetIdentity(canonical, block.target());
                appendBlockStateIdentity(canonical, block.expectedBefore());
                appendBlockStateIdentity(canonical, block.expectedAfter());
            }
            case PlaceBlockRequest place -> {
                appendTargetIdentity(canonical, place.target());
                appendBlockStateIdentity(canonical, place.expectedBefore());
                appendIdentity(canonical, place.item());
                appendBlockStateIdentity(canonical, place.expectedAfter());
            }
            case InteractBlockRequest block -> {
                appendTargetIdentity(canonical, block.target());
                appendBlockStateIdentity(canonical, block.expectedBefore());
                appendBlockStateIdentity(canonical, block.expectedAfter());
            }
            case InteractEntityRequest entity -> {
                appendIdentity(canonical, entity.entityRef());
                appendIdentity(canonical, entity.expectedType());
                appendIdentity(canonical, entity.hand());
                appendIdentity(canonical, entity.heldItem());
                appendIdentity(canonical, entity.goal().itemId());
                appendIdentity(canonical, Integer.toString(
                        entity.goal().minimumInventoryCount()));
            }
        }
        appendBoundsIdentity(canonical, request.bounds());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendBoundsIdentity(StringBuilder output, ActionBounds bounds) {
        appendIdentity(output, bounds.dimension());
        appendTargetIdentity(output, bounds.minimum());
        appendTargetIdentity(output, bounds.maximum());
        appendIdentity(output, Integer.toString(bounds.maxTravelBlocks()));
        appendIdentity(output, Integer.toString(bounds.maxDurationSeconds()));
        appendIdentity(output, Boolean.toString(bounds.allowBreak()));
    }

    private static void appendTargetIdentity(StringBuilder output, BlockTarget target) {
        appendIdentity(output, target.dimension());
        appendIdentity(output, Integer.toString(target.x()));
        appendIdentity(output, Integer.toString(target.y()));
        appendIdentity(output, Integer.toString(target.z()));
    }

    private static void appendBlockStateIdentity(
            StringBuilder output,
            BlockStateFingerprint state) {
        appendIdentity(output, state.blockId());
        state.properties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendIdentity(output, entry.getKey());
                    appendIdentity(output, entry.getValue());
                });
        appendIdentity(output, Integer.toString(state.properties().size()));
    }

    private static void appendIdentity(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value).append(';');
    }

    private Map<String, Object> cancelRoutine(Minecraft minecraft, Map<String, Object> arguments) {
        var routineId = uuidArgument(arguments, "routine_id");
        var before = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        boolean alreadyTerminal = before.state().terminal();
        if (alreadyTerminal) {
            return Map.of(
                    "routine_id", routineId.toString(),
                    "state", before.state().name(),
                    "released_inputs", finalizationReleasedInputs(before),
                    "already_terminal", true);
        }
        var cancelled = routines.cancelRoutine(
                routineId, stringArgument(arguments, "reason"), Long.MAX_VALUE, 1);
        var cleanup = finalizeTerminalRoutine(minecraft, cancelled);
        return Map.of(
                "routine_id", routineId.toString(),
                "state", cleanup.snapshot().state().name(),
                "released_inputs", cleanup.inputsReleased(),
                "already_terminal", alreadyTerminal);
    }

    private void tickActiveRoutine(Minecraft minecraft) {
        var before = routines.activeRoutineId();
        if (before.isEmpty()) {
            return;
        }
        var session = sessions.snapshot();
        var activeArming = arming.snapshot(session.worldSessionId(), System.nanoTime());
        if (!enforceActiveRoutineArming(
                activeArming,
                () -> inbox.requestEmergencyStop(activeArmingStopReason(activeArming)),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()))) {
            return;
        }
        try {
            routines.tick();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error("CraftAgent routine tick failed; forcing emergency stop", failure);
            inbox.requestEmergencyStop("routine_tick_exception");
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
            return;
        }
        var routineId = before.orElseThrow();
        if (finalizationRetries.contains(routineId)) {
            return;
        }
        var current = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        if (current.state() == RoutineState.FINALIZING) {
            finalizeRoutineBoundary(minecraft, current);
        }
        else if (current.state().terminal()) {
            finishTerminalRoutine(minecraft, routineId);
        }
    }

    /**
     * A routine may span a pause or a low-TPS interval, so admission-time deadline checks are not
     * enough. Recheck the wall-clock local lease immediately before every routine tick and finish a
     * priority stop inline when it is no longer armed.
     */
    static boolean enforceActiveRoutineArming(
            LocalArmingState.Snapshot snapshot,
            Runnable requestStop,
            Runnable drainStop) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.locked()) {
            return true;
        }
        runPriorityStop(requestStop, drainStop);
        return false;
    }

    static String activeArmingStopReason(LocalArmingState.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "expired".equals(snapshot.lastLockReason())
                ? "local_arming_expired"
                : "local_arming_locked";
    }

    private void retryPendingFinalizations(Minecraft minecraft) {
        long clientTick = sessions.snapshot().clientTick();
        for (var routineId : finalizationRetries.pendingRoutineIds(clientTick)) {
            final RoutineSnapshot snapshot;
            try {
                snapshot = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
            }
            catch (RoutineManager.RoutineNotFoundException missing) {
                finalizationRetries.forget(routineId);
                continue;
            }
            catch (RuntimeException | LinkageError failure) {
                CraftAgentMod.LOGGER.error(
                        "CraftAgent could not inspect pending routine finalization {}",
                        routineId,
                        failure);
                continue;
            }

            if (snapshot.finalizationCompleted()) {
                finalizationRetries.forget(routineId);
            }
            else if (snapshot.state() == RoutineState.FINALIZING || snapshot.state().terminal()) {
                finalizeRoutineBoundary(minecraft, snapshot);
            }
            else {
                CraftAgentMod.LOGGER.error(
                        "CraftAgent retained finalization retry {} in non-terminal state {}",
                        routineId,
                        snapshot.state());
                arming.lock("routine_finalization_failed");
            }
        }
    }

    private void finishTerminalRoutine(Minecraft minecraft, UUID routineId) {
        var before = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        var terminal = finalizeTerminalRoutine(minecraft, before).snapshot();
        if (terminal.finalizationFailure() != null) {
            arming.lock("routine_finalization_failed");
        }
        else if (terminal.state() == RoutineState.FAILED) {
            arming.lock("routine_failed_" + terminal.failure().code().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private boolean stopActiveRoutineForEmergency(
            String reason,
            WorldSessionTracker.Snapshot session) {
        var active = routines.activeRoutineId();
        if (active.isEmpty()) {
            return endVoiceFor(voiceRoutineId);
        }
        try {
            var cancelled = routines.cancelRoutine(active.orElseThrow(), reason, Long.MAX_VALUE, 1);
            var cleanup = finalizeTerminalRoutine(Minecraft.getInstance(), cancelled);
            return cleanup.inputsReleased() && cleanup.voice().success();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error("CraftAgent routine cancellation failed during emergency stop", failure);
            endVoiceFor(active.orElseThrow());
            return false;
        }
    }

    private TerminalCleanup finalizeTerminalRoutine(
            Minecraft minecraft,
            RoutineSnapshot terminalSnapshot) {
        return finalizeRoutineBoundary(minecraft, terminalSnapshot);
    }

    private TerminalCleanup finalizeRoutineBoundary(
            Minecraft minecraft,
            RoutineSnapshot snapshot) {
        var attempt = finalizationRetries.attempt(
                snapshot.routineId(),
                sessions.snapshot().clientTick(),
                priorIncident -> {
                    boolean retryCleanup = shouldRetryFinalizationCleanup(priorIncident);
                    var cleanup = retryCleanup
                            ? releaseOwnedResources(
                                    minecraft,
                                    snapshot.routineId(),
                                    priorIncident == null || !priorIncident.inputsReleased(),
                                    priorIncident == null || !priorIncident.voiceRestored())
                            : retainedCleanupOutcome(priorIncident);
                    if (retryCleanup) {
                        finalizationRetries.rememberCleanupOutcome(
                                snapshot.routineId(),
                                cleanup.inputsReleased(),
                                cleanup.voice().success(),
                                cleanup.voice().failureCode());
                    }
                    var failure = finalizationFailure(
                            snapshot,
                            cleanup.inputsReleased(),
                            cleanup.voice().success(),
                            cleanup.voice().failureCode(),
                            priorIncident == null ? null : priorIncident.boundaryFailureCode(),
                            priorIncident != null && priorIncident.previousInputReleaseFailure(),
                            priorIncident == null ? null : priorIncident.previousVoiceFailureCode());
                    var finalized = snapshot.state() == RoutineState.FINALIZING
                            ? routines.completeFinalization(
                                    snapshot.routineId(), failure, Long.MAX_VALUE, 1)
                            : routines.recordTerminalFinalization(
                                    snapshot.routineId(), failure, Long.MAX_VALUE, 1);
                    if (finalized.finalizationFailure() != null) {
                        arming.lock("routine_finalization_failed");
                    }
                    return new TerminalCleanup(
                            finalized, cleanup.inputsReleased(), cleanup.voice());
                },
                () -> releaseOwnedResources(minecraft, snapshot.routineId()));
        if (attempt.success()) {
            return attempt.value();
        }

        if (attempt.incident().failedAttempts()
                >= FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS) {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent routine finalization exhausted cleanup retries; "
                            + "continuing record-only probes for {}",
                    snapshot.routineId(),
                    attempt.failure());
        }
        else {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent routine finalization boundary failed; a bounded retry was retained for {}",
                    snapshot.routineId(),
                    attempt.failure());
        }
        arming.lock("routine_finalization_failed");
        var emergencyRelease = attempt.emergencyRelease();
        if (!attempt.emergencyReleaseAttempted()) {
            var incident = attempt.incident();
            return new TerminalCleanup(
                    snapshot,
                    incident.inputsReleased(),
                    new VoiceEndOutcome(
                            incident.voiceRestored(),
                            incident.voiceRestored()
                                    ? null
                                    : incident.previousVoiceFailureCode(),
                            false,
                            false,
                            incident.voiceRestored()));
        }
        if (emergencyRelease == null) {
            finalizationRetries.rememberCleanupOutcome(
                    snapshot.routineId(),
                    false,
                    false,
                    "finalization_emergency_release_exception");
            return new TerminalCleanup(
                    snapshot,
                    false,
                    new VoiceEndOutcome(
                            false,
                            "finalization_emergency_release_exception",
                            true,
                            false,
                            false));
        }
        finalizationRetries.rememberCleanupOutcome(
                snapshot.routineId(),
                emergencyRelease.inputsReleased(),
                emergencyRelease.voice().success(),
                emergencyRelease.voice().failureCode());
        return new TerminalCleanup(
                snapshot,
                emergencyRelease.inputsReleased(),
                emergencyRelease.voice());
    }

    static boolean shouldRetryFinalizationCleanup(FinalizationRetryQueue.Incident incident) {
        return incident == null
                || incident.failedAttempts() < FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS;
    }

    private static CleanupOutcome retainedCleanupOutcome(FinalizationRetryQueue.Incident incident) {
        Objects.requireNonNull(incident, "incident");
        return new CleanupOutcome(
                incident.inputsReleased(),
                new VoiceEndOutcome(
                        incident.voiceRestored(),
                        incident.voiceRestored()
                                ? null
                                : incident.previousVoiceFailureCode(),
                        false,
                        false,
                        incident.voiceRestored()));
    }

    private CleanupOutcome releaseOwnedResources(Minecraft minecraft, UUID routineId) {
        return releaseOwnedResources(minecraft, routineId, true, true);
    }

    private CleanupOutcome releaseOwnedResources(
            Minecraft minecraft,
            UUID routineId,
            boolean releaseInputs,
            boolean restoreVoice) {
        boolean inputsReleased;
        if (!releaseInputs) {
            inputsReleased = true;
        }
        else {
            try {
                inputsReleased = inputRelease.releaseAll(minecraft);
            }
            catch (RuntimeException | LinkageError failure) {
                CraftAgentMod.LOGGER.error("CraftAgent input release failed during finalization", failure);
                inputsReleased = false;
            }
        }
        var voice = restoreVoice
                ? endVoiceSessionFor(routineId)
                : new VoiceEndOutcome(true, null, false, false, false);
        if (!voice.success()) {
            CraftAgentMod.LOGGER.warn(
                    "CraftAgent Voice Chat restore did not complete: {}", voice.failureCode());
        }
        return new CleanupOutcome(inputsReleased, voice);
    }

    static RoutineFailure finalizationFailure(
            RoutineSnapshot snapshot,
            boolean inputsReleased,
            boolean voiceRestored,
            String voiceFailureCode) {
        return finalizationFailure(
                snapshot, inputsReleased, voiceRestored, voiceFailureCode, null);
    }

    static RoutineFailure finalizationFailure(
            RoutineSnapshot snapshot,
            boolean inputsReleased,
            boolean voiceRestored,
            String voiceFailureCode,
            String boundaryFailureCode) {
        return finalizationFailure(
                snapshot,
                inputsReleased,
                voiceRestored,
                voiceFailureCode,
                boundaryFailureCode,
                false,
                null);
    }

    static RoutineFailure finalizationFailure(
            RoutineSnapshot snapshot,
            boolean inputsReleased,
            boolean voiceRestored,
            String voiceFailureCode,
            String boundaryFailureCode,
            boolean previousInputReleaseFailure,
            String previousVoiceFailureCode) {
        if (inputsReleased && voiceRestored && boundaryFailureCode == null) {
            return null;
        }
        String code = !inputsReleased
                ? "INPUT_RELEASE_FAILED"
                : !voiceRestored
                        ? "VOICECHAT_RESTORE_FAILED"
                        : "FINALIZATION_BOUNDARY_FAILED";
        var observed = new LinkedHashMap<String, Object>();
        observed.put("inputs_released", inputsReleased);
        observed.put("voicechat_restored", voiceRestored);
        if (voiceFailureCode != null) {
            observed.put("voicechat_failure", voiceFailureCode);
        }
        if (boundaryFailureCode != null) {
            observed.put("boundary_failure", boundaryFailureCode);
        }
        if (previousInputReleaseFailure) {
            observed.put("previous_input_release_failure", true);
        }
        if (previousVoiceFailureCode != null) {
            observed.put("previous_voicechat_failure", previousVoiceFailureCode);
        }
        return new RoutineFailure(
                RoutineFailure.Category.EXTERNAL,
                code,
                false,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.FINALIZATION,
                intValue(snapshot.verification().get("attempts")),
                Map.of("inputs_released", true, "voicechat_restored", true),
                observed,
                Map.of(
                        "goal_verified", snapshot.goalVerified(),
                        "terminal_state", snapshot.state().name(),
                        "finalization_retry", boundaryFailureCode != null),
                List.of("player"),
                true);
    }

    static boolean finalizationReleasedInputs(RoutineSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.finalizationCompleted()) {
            return false;
        }
        var failure = snapshot.finalizationFailure();
        if (failure == null) {
            return true;
        }
        return Boolean.TRUE.equals(failure.observed().get("inputs_released"));
    }

    private boolean endVoiceFor(UUID routineId) {
        var outcome = endVoiceSessionFor(routineId);
        if (!outcome.success()) {
            CraftAgentMod.LOGGER.warn(
                    "CraftAgent Voice Chat restore did not complete: {}", outcome.failureCode());
        }
        return outcome.success();
    }

    private VoiceEndOutcome endVoiceSessionFor(UUID routineId) {
        if (routineId != null && !routineId.equals(voiceRoutineId)) {
            return new VoiceEndOutcome(true, null, false, false, false);
        }
        if (routineId == null && voiceRoutineId != null) {
            return new VoiceEndOutcome(true, null, false, false, false);
        }
        try {
            var ended = voiceChat.endAutomation();
            var outcome = new VoiceEndOutcome(
                    ended.failureCode() == null,
                    ended.failureCode(),
                    ended.sessionExisted(),
                    ended.restoreAttempted(),
                    ended.restored());
            voiceRoutineId = voiceRoutineAfterEnd(voiceRoutineId, routineId, outcome);
            return outcome;
        }
        catch (RuntimeException | LinkageError failure) {
            return new VoiceEndOutcome(
                    false, "voicechat_end_exception", true, false, false);
        }
    }

    private VoiceEndOutcome cleanUpRejectedVoiceBegin() {
        if (routines.activeRoutineId().isEmpty() && voiceRoutineId != null) {
            return endVoiceSessionFor(voiceRoutineId);
        }
        return endVoiceSessionFor(null);
    }

    static UUID voiceRoutineAfterEnd(
            UUID currentRoutineId,
            UUID requestedRoutineId,
            VoiceEndOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        boolean ownsRequestedSession = requestedRoutineId == null
                || requestedRoutineId.equals(currentRoutineId);
        return outcome.success() && ownsRequestedSession ? null : currentRoutineId;
    }

    static Map<String, Object> voiceBeginFailureDetails(
            VoiceChatSafetyController.BeginResult begin) {
        Objects.requireNonNull(begin, "begin");
        var details = new LinkedHashMap<String, Object>();
        details.put("reason", begin.failureCode() == null
                ? "voicechat_not_ready"
                : begin.failureCode());
        details.put("voice.stage", "begin");
        details.put("voice.present", begin.voiceChatPresent());
        details.put("voice.owns_mute", begin.ownsMute());
        details.put("voice.rollback_attempted", begin.rollbackAttempted());
        details.put("voice.rollback_restored", begin.rollbackRestored());
        if (begin.rollbackFailureCode() != null) {
            details.put("voice.rollback_failure", begin.rollbackFailureCode());
        }
        return Map.copyOf(details);
    }

    private static void appendVoiceEndDetails(
            Map<String, Object> details,
            VoiceEndOutcome voiceEnd) {
        details.put("voice.end_succeeded", voiceEnd.success());
        details.put("voice.end_session_existed", voiceEnd.sessionExisted());
        details.put("voice.end_restore_attempted", voiceEnd.restoreAttempted());
        details.put("voice.end_restored", voiceEnd.restored());
        if (voiceEnd.failureCode() != null) {
            details.put("voice.end_failure", voiceEnd.failureCode());
        }
    }

    static RuntimeException withVoiceEndFailureDiagnostics(
            Throwable failure,
            VoiceEndOutcome voiceEnd) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(voiceEnd, "voiceEnd");
        if (voiceEnd.success() && failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        var details = new LinkedHashMap<String, Object>();
        appendVoiceEndDetails(details, voiceEnd);
        return new FailureWithDetailsException(failure, details);
    }

    private void requestSafetyStop(String reason) {
        inbox.requestEmergencyStop(reason);
    }

    public void onManualInput(String reason) {
        stopForPriorityClientEvent(reason);
    }

    private void stopForPriorityClientEvent(String reason) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        runPriorityEventStopIfRequired(
                routines.activeRoutineId().isPresent()
                        || inbox.hasPendingCommand("start_routine"),
                () -> inbox.requestEmergencyStop(reason),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
    }

    private static void requireLiveCall(RuntimeCallContext context, String command) {
        if (!context.canBeginWork()) {
            throw new ClientCommandInbox.CommandTimeoutException(command);
        }
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

    private static int intValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    record VoiceEndOutcome(
            boolean success,
            String failureCode,
            boolean sessionExisted,
            boolean restoreAttempted,
            boolean restored) {
    }

    private record CleanupOutcome(boolean inputsReleased, VoiceEndOutcome voice) {
    }

    private record TerminalCleanup(
            RoutineSnapshot snapshot,
            boolean inputsReleased,
            VoiceEndOutcome voice) {
    }

    private void stopForLifecycle(Minecraft minecraft, String reason) {
        inbox.requestEmergencyStop(reason);
        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
    }

    static SemanticActionRequest semanticActionArgument(
            Map<String, Object> arguments,
            WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        return semanticActionArgument(arguments, session.dimension());
    }

    static SemanticActionRequest semanticActionArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireExactKeys(arguments, "start_routine", Set.of(
                "kind", "parameters", "bounds", "completion_intent", "idempotency_key"));
        if (!"finish_goal".equals(stringArgument(arguments, "completion_intent"))) {
            throw new IllegalArgumentException("completion_intent must be finish_goal");
        }
        var kind = stringArgument(arguments, "kind");
        var parameters = objectArgument(arguments, "parameters");
        var bounds = actionBoundsArgument(arguments, currentDimension);
        return switch (kind) {
            case NavigateToRequest.KIND -> {
                requireExactKeys(parameters, "navigate_to parameters", Set.of(
                        "target", "horizontal_tolerance_blocks"));
                yield new NavigateToRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        doubleArgument(parameters, "horizontal_tolerance_blocks"),
                        bounds);
            }
            case BreakBlockRequest.KIND -> {
                requireExactKeys(parameters, "break_block parameters", Set.of(
                        "target", "expected_before", "expected_after"));
                yield new BreakBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case PlaceBlockRequest.KIND -> {
                requireExactKeys(parameters, "place_block parameters", Set.of(
                        "target", "expected_before", "item", "expected_after"));
                yield new PlaceBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        stringArgument(parameters, "item"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case InteractBlockRequest.KIND -> {
                requireExactKeys(parameters, "interact_block parameters", Set.of(
                        "target", "expected_before", "expected_after"));
                yield new InteractBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case InteractEntityRequest.KIND -> {
                requireExactKeys(parameters, "interact_entity parameters", Set.of(
                        "entity_ref", "expected_type", "hand", "held_item", "goal"));
                var goal = objectArgument(parameters, "goal");
                requireExactKeys(goal, "goal", Set.of("item", "minimum_inventory_count"));
                yield new InteractEntityRequest(
                        stringArgument(parameters, "entity_ref"),
                        stringArgument(parameters, "expected_type"),
                        stringArgument(parameters, "hand"),
                        stringArgument(parameters, "held_item"),
                        new StationaryBreakGoal(
                                stringArgument(goal, "item"),
                                intArgument(goal, "minimum_inventory_count")),
                        bounds);
            }
            default -> throw new IllegalArgumentException("kind is not a Phase 3 semantic action");
        };
    }

    private static ActionBounds actionBoundsArgument(
            Map<String, Object> arguments,
            WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        return actionBoundsArgument(arguments, session.dimension());
    }

    private static ActionBounds actionBoundsArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        var bounds = objectArgument(arguments, "bounds");
        requireExactKeys(bounds, "bounds", Set.of(
                "dimension", "region", "max_travel_blocks",
                "max_duration_seconds", "allow_break"));
        var dimension = stringArgument(bounds, "dimension");
        if (!dimension.equals(Objects.requireNonNull(currentDimension, "currentDimension"))) {
            throw new IllegalArgumentException("bounds.dimension must equal the current dimension");
        }
        var region = objectArgument(bounds, "region");
        requireExactKeys(region, "bounds.region", Set.of("min", "max"));
        var minimum = positionTargetArgument(region, "min", dimension);
        var maximum = positionTargetArgument(region, "max", dimension);
        return new ActionBounds(
                dimension,
                minimum,
                maximum,
                intArgument(bounds, "max_travel_blocks"),
                intArgument(bounds, "max_duration_seconds"),
                booleanArgument(bounds, "allow_break"));
    }

    private static BlockTarget dimensionBlockTargetArgument(
            Map<String, Object> source,
            String name) {
        var target = objectArgument(source, name);
        requireExactKeys(target, name, Set.of("dimension", "x", "y", "z"));
        return checkedBlockTarget(
                stringArgument(target, "dimension"),
                intArgument(target, "x"),
                intArgument(target, "y"),
                intArgument(target, "z"));
    }

    private static BlockTarget positionTargetArgument(
            Map<String, Object> source,
            String name,
            String dimension) {
        var target = objectArgument(source, name);
        requireExactKeys(target, name, Set.of("x", "y", "z"));
        return checkedBlockTarget(
                dimension,
                intArgument(target, "x"),
                intArgument(target, "y"),
                intArgument(target, "z"));
    }

    private static BlockTarget checkedBlockTarget(
            String dimension,
            int x,
            int y,
            int z) {
        if (x < -30_000_000 || x > 29_999_999
                || z < -30_000_000 || z > 29_999_999
                || y < -2_048 || y > 2_047) {
            throw new IllegalArgumentException("block position is outside supported bounds");
        }
        return new BlockTarget(dimension, x, y, z);
    }

    private static BlockStateFingerprint blockStateArgument(
            Map<String, Object> source,
            String name) {
        var state = objectArgument(source, name);
        requireAllowedKeys(state, name, Set.of("block", "properties"));
        var block = stringArgument(state, "block");
        var properties = new LinkedHashMap<String, String>();
        if (state.containsKey("properties")) {
            var rawProperties = state.get("properties");
            if (!(rawProperties instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException(name + ".properties must be an object");
            }
            if (values.size() > 128) {
                throw new IllegalArgumentException(name + ".properties must contain at most 128 entries");
            }
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !key.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException(name + ".properties has an invalid key");
                }
                if (!(entry.getValue() instanceof String value)
                        || value.isBlank()
                        || value.length() > 64) {
                    throw new IllegalArgumentException(name + ".properties has an invalid value");
                }
                properties.put(key, value);
            }
        }
        return new BlockStateFingerprint(block, properties);
    }

    private static Optional<BlockTarget> semanticTarget(SemanticActionRequest request) {
        return switch (request) {
            case NavigateToRequest navigation -> Optional.of(navigation.target());
            case BreakBlockRequest block -> Optional.of(block.target());
            case PlaceBlockRequest place -> Optional.of(place.target());
            case InteractBlockRequest block -> Optional.of(block.target());
            case InteractEntityRequest ignored -> Optional.empty();
        };
    }

    private static void validateLiveBounds(
            Minecraft minecraft,
            ActionBounds bounds,
            BlockTarget target) {
        var level = minecraft.level;
        if (level == null) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
        if (!level.isInsideBuildHeight(bounds.minimum().y())
                || !level.isInsideBuildHeight(bounds.maximum().y())) {
            throw new IllegalArgumentException("bounds.region is outside the current build height");
        }
        if (target != null
                && !level.getWorldBorder().isWithinBounds(
                        new net.minecraft.core.BlockPos(target.x(), target.y(), target.z()))) {
            throw new IllegalArgumentException("target is outside the current world border");
        }
    }

    private static void requireExactKeys(
            Map<String, Object> source,
            String name,
            Set<String> expected) {
        requireAllowedKeys(source, name, expected);
        if (source.size() != expected.size() || !source.keySet().containsAll(expected)) {
            throw new IllegalArgumentException(name + " must contain exactly "
                    + expected.stream().sorted().toList());
        }
    }

    private static void requireAllowedKeys(
            Map<String, Object> source,
            String name,
            Set<String> allowed) {
        Objects.requireNonNull(source, "source");
        for (var key : source.keySet()) {
            if (key == null || !allowed.contains(key)) {
                throw new IllegalArgumentException(name + " contains an unknown property");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static String stringArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return text;
    }

    private static int intArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Math.toIntExact(exactLong(number));
        }
        catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static double doubleArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return result;
    }

    private static boolean booleanArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return flag;
    }

    private static long optionalLong(Map<String, Object> source, String name, long fallback) {
        var value = source.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return exactLong(number);
        }
        catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static long exactLong(Number number) {
        return switch (number) {
            case Byte value -> value.longValue();
            case Short value -> value.longValue();
            case Integer value -> value.longValue();
            case Long value -> value;
            case BigInteger value -> value.longValueExact();
            case BigDecimal value -> value.longValueExact();
            case Float value -> exactFloatingLong(value.doubleValue());
            case Double value -> exactFloatingLong(value);
            default -> new BigDecimal(number.toString()).longValueExact();
        };
    }

    private static long exactFloatingLong(double value) {
        if (!Double.isFinite(value)
                || value != Math.rint(value)
                || value < Long.MIN_VALUE
                || value >= 0x1.0p63) {
            throw new ArithmeticException("not an exact long");
        }
        return (long) value;
    }

    private static UUID uuidArgument(Map<String, Object> source, String name) {
        try {
            return UUID.fromString(stringArgument(source, name));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be a UUID", invalid);
        }
    }

    private static Set<String> stringSetArgument(Map<String, Object> source, String name) {
        var value = source.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        if (list.isEmpty() || list.size() > 16) {
            throw new IllegalArgumentException(name + " must contain 1..16 registry IDs");
        }
        var result = new java.util.LinkedHashSet<String>();
        for (var element : list) {
            if (!(element instanceof String text)
                    || !text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException(name + " must contain registry IDs");
            }
            result.add(text);
        }
        if (result.size() != list.size()) {
            throw new IllegalArgumentException(name + " must contain unique registry IDs");
        }
        return Set.copyOf(result);
    }

    private static long saturatingAdd(long left, long right) {
        if (right < 0 || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void synchronizeWorld(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.gameMode == null
                || minecraft.getConnection() == null) {
            return;
        }
        var dimension = minecraft.level.dimension().identifier().toString();
        var before = sessions.snapshot();
        boolean changed = sessions.latchReady(dimension);
        var after = sessions.snapshot();
        if (changed) {
            memory.startSession(after.worldSessionId(), dimension);
            if (before.dimension() != null && !before.dimension().equals(dimension)) {
                arming.lock("dimension_changed");
                inputRelease.releaseAll(minecraft);
            }
        }
    }

    private static void requireReady(WorldSessionTracker.Snapshot session) {
        if (!session.worldReady()) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
    }

    static RuntimeReply mapFailure(Throwable failure) {
        var cause = unwrap(failure);
        if (cause instanceof FailureWithDetailsException detailed) {
            var mapped = mapFailure(detailed.getCause());
            var original = mapped.failure();
            var details = new LinkedHashMap<String, Object>(original.details());
            details.putAll(detailed.details());
            return RuntimeReply.failure(
                    original.code(), original.message(), original.retryable(), details);
        }
        if (cause instanceof ClientCommandInbox.CommandTimeoutException) {
            return RuntimeReply.failure("timeout", "The client-thread deadline expired", true);
        }
        if (cause instanceof ClientCommandInbox.CommandInvalidatedException) {
            return RuntimeReply.failure("unsafe_state", "The world or safety epoch changed before execution", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return RuntimeReply.failure("busy", "The bounded client command inbox is full", true);
        }
        if (cause instanceof MinecraftObservationService.ObservationUnavailableException unavailable) {
            return RuntimeReply.failure(unavailable.code(), unavailable.getMessage(), true);
        }
        if (cause instanceof RuntimeInvocationException invocation) {
            return RuntimeReply.failure(
                    invocation.code(), publicMessage(invocation), invocation.retryable(), invocation.details());
        }
        if (cause instanceof RoutineManager.RoutineBusyException busy) {
            var details = busy.activeRoutineId() == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("active_routine_id", busy.activeRoutineId().toString());
            return RuntimeReply.failure("busy", "Another routine is active", true, details);
        }
        if (cause instanceof RoutineManager.IdempotencyConflictException) {
            return RuntimeReply.failure(
                    "idempotency_conflict", "The idempotency key has different arguments", false);
        }
        if (cause instanceof RoutineManager.RoutineNotFoundException) {
            return RuntimeReply.failure("routine_not_found", "The routine is not retained", false);
        }
        if (cause instanceof IllegalArgumentException) {
            return RuntimeReply.failure("invalid_argument", publicMessage(cause), false);
        }
        return RuntimeReply.failure("internal_error", "The request failed; consult the local audit log", false);
    }

    private static Throwable unwrap(Throwable failure) {
        var result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String publicMessage(Throwable failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "The request arguments are invalid";
        }
        var normalized = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(512, normalized.length()));
    }

    private void publishSession() {
        publishedSession = sessions.snapshot();
    }

    private static void assertClientThread(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Minecraft state accessed outside the client thread");
        }
    }

    private static void overlay(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(message));
        }
    }

    public boolean paused() {
        return paused;
    }

    public WorldMemory memory() {
        return memory;
    }

    private static final class RuntimeInvocationException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final Map<String, Object> details;

        private RuntimeInvocationException(
                String code,
                String message,
                boolean retryable,
                Map<String, Object> details) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.retryable = retryable;
            this.details = Map.copyOf(details);
        }

        private String code() {
            return code;
        }

        private boolean retryable() {
            return retryable;
        }

        private Map<String, Object> details() {
            return details;
        }
    }

    private static final class FailureWithDetailsException extends RuntimeException {
        private final Map<String, Object> details;

        private FailureWithDetailsException(Throwable cause, Map<String, Object> details) {
            super(Objects.requireNonNull(cause, "cause"));
            this.details = Map.copyOf(details);
        }

        private Map<String, Object> details() {
            return details;
        }
    }
}
