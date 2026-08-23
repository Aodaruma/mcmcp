package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.CraftAgentMod;
import dev.aodaruma.craftagent.mcp.McpRuntimePort;
import dev.aodaruma.craftagent.mcp.McpToolSchemas;
import dev.aodaruma.craftagent.mcp.RuntimeCallContext;
import dev.aodaruma.craftagent.observation.BlockPlanComparator;
import dev.aodaruma.craftagent.observation.BlockPlan;
import dev.aodaruma.craftagent.observation.BlockPlanStateTransformer;
import dev.aodaruma.craftagent.observation.BlockPlanValidationException;
import dev.aodaruma.craftagent.observation.BlockStateView;
import dev.aodaruma.craftagent.observation.ClientRecipeCatalog;
import dev.aodaruma.craftagent.observation.CreativeRegionCapture;
import dev.aodaruma.craftagent.observation.MinecraftObservationService;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.safety.InputReleaseController;
import dev.aodaruma.craftagent.safety.LocalArmingState;
import dev.aodaruma.craftagent.routine.ActionBounds;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanOperation;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanRequest;
import dev.aodaruma.craftagent.routine.ApplyBlockPlanStep;
import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.BlockStateFingerprint;
import dev.aodaruma.craftagent.routine.BreakBlockRequest;
import dev.aodaruma.craftagent.routine.InteractBlockRequest;
import dev.aodaruma.craftagent.routine.InteractEntityRequest;
import dev.aodaruma.craftagent.routine.MinecraftApplyBlockPlanPort;
import dev.aodaruma.craftagent.routine.MinecraftPhaseFiveInventoryPort;
import dev.aodaruma.craftagent.routine.MinecraftPhaseFiveWorldPort;
import dev.aodaruma.craftagent.routine.MinecraftSemanticActionPort;
import dev.aodaruma.craftagent.routine.MinecraftStationaryBreakPort;
import dev.aodaruma.craftagent.routine.NavigateToRequest;
import dev.aodaruma.craftagent.routine.PlaceBlockRequest;
import dev.aodaruma.craftagent.routine.PhaseFiveBounds;
import dev.aodaruma.craftagent.routine.PhaseFivePortRouter;
import dev.aodaruma.craftagent.routine.PhaseFiveRequest;
import dev.aodaruma.craftagent.routine.RoutineManager;
import dev.aodaruma.craftagent.routine.SafeBreakSourcePolicy;
import dev.aodaruma.craftagent.routine.SafePlacementSupportPolicy;
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
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final Duration FINALIZATION_RESERVE = Duration.ofSeconds(5);
    private static final double MAX_SAFE_STAY_HORIZONTAL_SPEED_SQUARED = 0.01;
    private static final float MIN_SAFE_STAY_HEALTH = 6.0F;
    /** Expanded only when a phase has passed its gate. */
    private static final Set<String> AVAILABLE_CAPABILITIES = Set.of(
            "stationary_break",
            NavigateToRequest.KIND,
            BreakBlockRequest.KIND,
            PlaceBlockRequest.KIND,
            InteractBlockRequest.KIND,
            InteractEntityRequest.KIND,
            ApplyBlockPlanRequest.KIND,
            "craft_items",
            "transfer_items",
            "tend_crop_area",
            "harvest_tree_area",
            "sleep_at_bed",
            "survey_area");

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final WorldMemory memory = new WorldMemory();
    private final MinecraftObservationService observations = new MinecraftObservationService(memory);
    private final BlockPlanComparator blockPlans = new BlockPlanComparator(observations, memory);
    private final CreativeRegionCapture creativeRegions = new CreativeRegionCapture();
    private final ClientRecipeCatalog recipeCatalog = new ClientRecipeCatalog();
    private final ScreenOwnershipSignals screenOwnership = ScreenOwnershipSignals.global();
    private final LocalArmingState arming = new LocalArmingState();
    private final InputReleaseController inputRelease = new InputReleaseController();
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final ClientReconciliationSignals reconciliationSignals;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final MinecraftApplyBlockPlanPort applyBlockPlanPort;
    private final MinecraftPhaseFiveInventoryPort phaseFiveInventoryPort;
    private final MinecraftPhaseFiveWorldPort phaseFiveWorldPort;
    private final PhaseFivePortRouter phaseFivePort;
    private final RoutineManager routines;
    private final VoiceChatSafetyController voiceChat;
    private final ClientCommandInbox inbox;
    private final FinalizationRetryQueue finalizationRetries = new FinalizationRetryQueue();
    private final GoalContinuationSession goalContinuation = new GoalContinuationSession();

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
        applyBlockPlanPort = new MinecraftApplyBlockPlanPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global(),
                reconciliationSignals);
        phaseFiveInventoryPort = new MinecraftPhaseFiveInventoryPort(
                Minecraft::getInstance,
                sessions::snapshot,
                recipeCatalog,
                screenOwnership);
        phaseFiveWorldPort = new MinecraftPhaseFiveWorldPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                semanticActionPort);
        phaseFivePort = new PhaseFivePortRouter(phaseFiveInventoryPort, phaseFiveWorldPort);
        routines = new RoutineManager(
                stationaryBreakPort, semanticActionPort, applyBlockPlanPort, phaseFivePort);
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
        goalContinuation.clear();
        voiceRoutineId = null;
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession);
        clearPhaseFivePortSessions();
        reconciliationSignals.closeLevel(minecraft.level);
        screenOwnership.clearLevel(minecraft.level);
        recipeCatalog.detachSession();
        sessions.beginConnection();
        arming.lock("world_join");
        publishSession();
    }

    public void onLevelUnload(Minecraft minecraft) {
        assertClientThread(minecraft);
        stopForLifecycle(minecraft, "level_or_dimension_change");
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession);
        clearPhaseFivePortSessions();
        reconciliationSignals.closeLevel(minecraft.level);
        screenOwnership.clearLevel(minecraft.level);
        recipeCatalog.detachSession();
        sessions.suspendWorld();
        goalContinuation.clear();
        arming.lock("level_or_dimension_change");
        publishSession();
    }

    public void onLoggingOut(Minecraft minecraft) {
        assertClientThread(minecraft);
        stopForLifecycle(minecraft, "disconnect");
        routines.clearSession("disconnect");
        finalizationRetries.clear();
        goalContinuation.clear();
        voiceRoutineId = null;
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession);
        clearPhaseFivePortSessions();
        reconciliationSignals.closeLevel(minecraft.level);
        screenOwnership.clearLevel(minecraft.level);
        recipeCatalog.detachSession();
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
        creativeRegions.fenceClient(minecraft, publishedSession.worldSessionId());
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
        try {
            applyBlockPlanPort.captureIssuedPredictions();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent block-plan prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        inbox.drainReadsPostTick(sessions.snapshot());
        publishSession();
    }

    public void onServerPostTick(MinecraftServer server) {
        creativeRegions.onServerPostTick(server);
    }

    public void onServerStopping(MinecraftServer server) {
        creativeRegions.onServerStopping(server);
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
            goalContinuation.clear();
            overlay(minecraft, "CraftAgent: ロックしました");
        } else {
            goalContinuation.reset(session.worldSessionId());
            arming.arm(session.worldSessionId(), availableCapabilities(minecraft),
                    LocalArmingState.DEFAULT_ARM_DURATION, System.nanoTime());
            overlay(minecraft, "CraftAgent: 15分間ローカル解除しました");
        }
    }

    public void emergencyStopFromLocalKey(Minecraft minecraft) {
        assertClientThread(minecraft);
        runPriorityStop(
                () -> inbox.requestEmergencyStop("local_emergency_key"),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        goalContinuation.clear();
        overlay(minecraft, "CraftAgent: 緊急停止・ロック済み");
    }

    /** Local controls are already on the client thread and must finish the priority stop inline. */
    static void runPriorityStop(Runnable request, Runnable drain) {
        Objects.requireNonNull(request, "request").run();
        Objects.requireNonNull(drain, "drain").run();
    }

    /** One lifecycle fence for every adapter that can retain input, slot or prediction ownership. */
    static void clearAutomationPortSessions(
            Runnable stationaryBreakClear,
            Runnable semanticActionClear,
            Runnable applyBlockPlanClear) {
        clearAutomationPortSession("stationary_break", stationaryBreakClear);
        clearAutomationPortSession("semantic_action", semanticActionClear);
        clearAutomationPortSession("apply_block_plan", applyBlockPlanClear);
    }

    private static void clearAutomationPortSession(String name, Runnable clear) {
        Objects.requireNonNull(clear, name + "Clear");
        try {
            clear.run();
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error(
                    "CraftAgent {} session cleanup failed; continuing the lifecycle fence",
                    name,
                    failure);
        }
    }

    private void clearPhaseFivePortSessions() {
        clearAutomationPortSession("phase_five_inventory", phaseFiveInventoryPort::clearSession);
        clearAutomationPortSession("phase_five_world", phaseFiveWorldPort::clearSession);
        clearAutomationPortSession("phase_five_router", phaseFivePort::clearSession);
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
        creativeRegions.cancelActive("client_shutdown");
        sessions.stopping();
        inbox.shutdown(minecraft, sessions.snapshot());
        routines.clearSession("client_shutdown");
        finalizationRetries.clear();
        goalContinuation.clear();
        voiceRoutineId = null;
        voiceChat.close();
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession);
        clearPhaseFivePortSessions();
        reconciliationSignals.closeLevel(minecraft.level);
        screenOwnership.clearLevel(minecraft.level);
        recipeCatalog.detachSession();
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
            case CaptureCreativeRegion capture -> {
                requireReady(session);
                yield captureCreativeRegion(minecraft, session, capture.arguments(), context);
            }
            case CompareBlockPlan compare -> {
                requireReady(session);
                yield blockPlans.compare(minecraft, session.clientTick(), compare.arguments());
            }
            case GetRecipes getRecipes -> {
                requireReady(session);
                yield getRecipes(minecraft, session, getRecipes.arguments());
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

    private Map<String, Object> getRecipes(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments) {
        requireExactKeys(arguments, "get_recipes", Set.of("query", "max_results"));
        int maxResults = intArgument(arguments, "max_results");
        if (maxResults < 1 || maxResults > 64) {
            throw new IllegalArgumentException("max_results must be in 1..64");
        }
        Map<String, Object> queryInput = objectArgument(arguments, "query");
        String kind = stringArgument(queryInput, "kind");
        ClientRecipeCatalog.Query query = switch (kind) {
            case "result_item" -> {
                requireExactKeys(queryInput, "get_recipes query", Set.of("kind", "item"));
                yield new ClientRecipeCatalog.Query(
                        ClientRecipeCatalog.QueryKind.RESULT_ITEM,
                        stringArgument(queryInput, "item"));
            }
            case "result_tag" -> {
                requireExactKeys(queryInput, "get_recipes query", Set.of("kind", "tag"));
                yield new ClientRecipeCatalog.Query(
                        ClientRecipeCatalog.QueryKind.RESULT_TAG,
                        stringArgument(queryInput, "tag"));
            }
            default -> throw new IllegalArgumentException("get_recipes query kind is unsupported");
        };
        recipeCatalog.refreshFromClient(
                minecraft, Objects.requireNonNull(session.worldSessionId(), "worldSessionId"), session.clientTick());
        return recipeCatalog.query(session.worldSessionId(), query, maxResults).toMap();
    }

    private Map<String, Object> captureCreativeRegion(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            RuntimeCallContext context) {
        requireLiveCall(context, "capture_creative_region");
        Object operation = arguments.get("operation");
        UUID worldSessionId = Objects.requireNonNull(session.worldSessionId(), "worldSessionId");
        if ("status".equals(operation)) {
            return creativeRegions.status(worldSessionId, arguments);
        }
        if (!"start".equals(operation)) {
            throw new IllegalArgumentException("operation must be start or status");
        }
        long nowNanos = System.nanoTime();
        long jobDeadlineNanos = saturatingAdd(
                nowNanos, CreativeRegionCapture.JOB_TIMEOUT.toNanos());
        if (!arming.allows(
                session.worldSessionId(), CreativeRegionCapture.CAPABILITY,
                jobDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    "Creative region capture is not locally armed for this world session",
                    false,
                    Map.of());
        }
        if (!CreativeRegionCapture.isConfirmedCreative(minecraft)) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "Creative region capture requires local integrated single-player Creative mode",
                    false,
                    Map.of("reason", "creative_mode_not_confirmed"));
        }
        requireLiveCall(context, "capture_creative_region");
        return creativeRegions.start(
                minecraft,
                session.clientTick(),
                worldSessionId,
                arguments,
                () -> arming.allows(
                        worldSessionId,
                        CreativeRegionCapture.CAPABILITY,
                        jobDeadlineNanos,
                        System.nanoTime()));
    }

    private static Set<String> availableCapabilities(Minecraft minecraft) {
        if (!CreativeRegionCapture.isConfirmedCreative(minecraft)) {
            return AVAILABLE_CAPABILITIES;
        }
        var result = new LinkedHashSet<>(AVAILABLE_CAPABILITIES);
        result.add(CreativeRegionCapture.CAPABILITY);
        return Set.copyOf(result);
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
        result.put("capability_profile", availableCapabilities(minecraft).stream().sorted().toList());
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
                "catalog_version", "phase-6",
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
                                        "a fresh inbound inventory sync reaches the absolute minecraft:milk_bucket count goal")),
                        routineCatalogEntry(
                                ApplyBlockPlanRequest.KIND,
                                4,
                                McpToolSchemas.applyBlockPlanStartInput(),
                                List.of(
                                        "already-satisfied cells are skipped only after a current exact full-state observation",
                                        "all mutations have a covering vanilla prediction ACK and exact server state",
                                        "all required cells match current exact full states with unknown equal to zero",
                                        "the current client inventory is accepted as the eligible-hotbar baseline; every placement requires a fresh inbound selected-slot inventory sync")),
                        routineCatalogEntry(
                                "craft_items",
                                5,
                                McpToolSchemas.craftItemsStartInput(),
                                List.of(
                                        "the opaque client-known recipe reference and fingerprint are revalidated before dispatch",
                                        "ambiguous container clicks are never retried blindly",
                                        "success requires a fresh full-content readback with the absolute inventory goal and an empty cursor")),
                        routineCatalogEntry(
                                "transfer_items",
                                5,
                                McpToolSchemas.transferItemsStartInput(),
                                List.of(
                                        "only an automation-opened canonical single chest or barrel is used",
                                        "one bounded quick-move segment is never retried blindly",
                                        "success requires a fresh reopened full-content snapshot for both endpoints and an empty cursor")),
                        routineCatalogEntry(
                                "tend_crop_area",
                                5,
                                McpToolSchemas.tendCropAreaStartInput(),
                                List.of(
                                        "only declared current-visible cells using the closed vanilla crop adapters are mutated",
                                        "every harvest and replant transition has server-positive block evidence",
                                        "drop collection uncertainty remains explicit")),
                        routineCatalogEntry(
                                "harvest_tree_area",
                                5,
                                McpToolSchemas.harvestTreeAreaStartInput(),
                                List.of(
                                        "only declared current-visible vanilla log cells are claimed and mutated",
                                        "hidden logs and complete natural-tree coverage are never inferred",
                                        "drop collection uncertainty remains explicit")),
                        routineCatalogEntry(
                                "sleep_at_bed",
                                5,
                                McpToolSchemas.sleepAtBedStartInput(),
                                List.of(
                                        "both exact bed halves and the dimension sleep rule are revalidated before normal use",
                                        "sleep and wake require server-synchronized player state",
                                        "respawn change is confirmed only by the action-scoped vanilla semantic signal")),
                        routineCatalogEntry(
                                "survey_area",
                                5,
                                McpToolSchemas.surveyAreaStartInput(),
                                List.of(
                                        "only declared waypoints and samples are inspected through normal movement and view control",
                                        "current, last-known, and unknown coverage remain distinct",
                                        "spawn-surface assessment is explicitly predicted rather than server-confirmed"))));
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
        requireStartRoutineKeys(arguments);
        String kind = stringArgument(arguments, "kind");
        if (!AVAILABLE_CAPABILITIES.contains(kind)) {
            throw new IllegalArgumentException("kind is not an available routine");
        }
        String completionIntent = completionIntentArgument(arguments);
        return switch (kind) {
            case "stationary_break" -> startStationaryBreak(
                    minecraft, session, arguments, completionIntent, context);
            case NavigateToRequest.KIND,
                    BreakBlockRequest.KIND,
                    PlaceBlockRequest.KIND,
                    InteractBlockRequest.KIND,
                    InteractEntityRequest.KIND -> startSemanticAction(
                            minecraft, session, arguments, completionIntent, context);
            case ApplyBlockPlanRequest.KIND -> startApplyBlockPlan(
                    minecraft, session, arguments, completionIntent, context);
            case "craft_items", "transfer_items", "tend_crop_area",
                    "harvest_tree_area", "sleep_at_bed", "survey_area" -> startPhaseFive(
                            minecraft, session, arguments, completionIntent, context);
            default -> throw new IllegalArgumentException("kind is not an available routine");
        };
    }

    private Map<String, Object> startStationaryBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
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
        validateStationaryBreakAllowedBlocks(allowedBlocks);
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
                regenerationSeconds,
                completionIntent);
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
        long hardDeadlineNanos = admissionDeadlineNanos(nowNanos, maxDurationSeconds);
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

        var receipt = admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent, () -> routines.startStationaryBreak(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startSemanticAction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var request = semanticActionArgument(arguments, session);
        var idempotencyKey = stringArgument(arguments, "idempotency_key");
        var requestIdentity = semanticActionIdentity(request, completionIntent);
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
        long hardDeadlineNanos = admissionDeadlineNanos(
                nowNanos, request.bounds().maxDurationSeconds());
        if (!arming.allows(
                session.worldSessionId(), request.kind(), hardDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session and deadline",
                    false,
                    Map.of());
        }
        validateLiveBounds(minecraft, request.bounds(), semanticTarget(request).orElse(null));
        if (request instanceof PlaceBlockRequest place) {
            try {
                semanticActionPort.requireSafePlacementSupportForAdmission(place);
            } catch (SafePlacementSupportPolicy.UnsafePlacementSupportException rejected) {
                throw new RuntimeInvocationException(
                        "unsafe_state", SafePlacementSupportPolicy.REJECTION_MESSAGE, true,
                        Map.of("placement_support", "not_safe"));
            }
        }

        var receipt = admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent, () -> routines.startSemanticAction(
                idempotencyKey, requestIdentity, request, session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private Map<String, Object> startApplyBlockPlan(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments,
            String completionIntent,
            RuntimeCallContext context) {
        var parsed = applyBlockPlanArgument(arguments, session.dimension());
        var request = parsed.request();
        var idempotencyKey = stringArgument(arguments, "idempotency_key");
        var replay = replayApplyBlockPlanAfterFinalizationGate(
                finalizationRetries,
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow(), parsed.resourceEstimate());
        }

        requireLiveCall(context, "start_routine");
        long nowNanos = System.nanoTime();
        long hardDeadlineNanos = admissionDeadlineNanos(
                nowNanos, request.bounds().maxDurationSeconds());
        if (!arming.allows(
                session.worldSessionId(), request.kind(), hardDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session and deadline",
                    false,
                    Map.of());
        }
        for (var step : request.steps()) {
            validateLiveBounds(minecraft, request.bounds(), step.target());
        }
        validateApplyBlockPlanItems(request);

        var receipt = admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent, () -> routines.startApplyBlockPlan(
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
        var parsed = phaseFiveRequestArgument(arguments, session.dimension());
        var request = parsed.request();
        String idempotencyKey = stringArgument(arguments, "idempotency_key");
        var replay = replayPhaseFiveAfterFinalizationGate(
                finalizationRetries,
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                request,
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        requireLiveCall(context, "start_routine");
        long nowNanos = System.nanoTime();
        long hardDeadlineNanos = admissionDeadlineNanos(
                nowNanos, request.bounds().maxDurationSeconds());
        if (!arming.allows(
                session.worldSessionId(), request.kind(), hardDeadlineNanos, nowNanos)) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session and deadline",
                    false,
                    Map.of());
        }
        validateLiveBounds(minecraft, request.bounds(), parsed.targets());

        var receipt = admitWithVoiceSafety(
                context, session.worldSessionId(), completionIntent, () -> routines.startPhaseFive(
                idempotencyKey,
                parsed.requestIdentity(),
                request,
                session.clientTick()));
        return startReceiptPayload(receipt);
    }

    private RoutineManager.StartReceipt admitWithVoiceSafety(
            RuntimeCallContext context,
            UUID worldSessionId,
            String completionIntent,
            Supplier<RoutineManager.StartReceipt> admission) {
        if (!goalContinuation.canAdmit(worldSessionId, completionIntent)) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The local continuation routine limit is exhausted",
                    false,
                    Map.of("reason", "continuation_limit"));
        }
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
        goalContinuation.remember(
                worldSessionId, receipt.routineId(), receipt.reused(), completionIntent);
        return receipt;
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

    private static String stationaryBreakIdentity(
            BlockTarget target,
            Set<String> allowedBlocks,
            StationaryBreakGoal goal,
            BlockTarget minimum,
            BlockTarget maximum,
            int maxDurationSeconds,
            int regenerationSeconds,
            String completionIntent) {
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
                Integer.toString(regenerationSeconds),
                completionIntent);
    }

    static String semanticActionIdentity(SemanticActionRequest request) {
        return semanticActionIdentity(request, GoalContinuationSession.FINISH_GOAL);
    }

    static String semanticActionIdentity(SemanticActionRequest request, String completionIntent) {
        Objects.requireNonNull(request, "request");
        GoalContinuationSession.requireIntent(completionIntent);
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
        appendIdentity(canonical, completionIntent);
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
                applyCompletionIntentAfterTerminal(snapshot);
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
        goalContinuation.clear();
        boolean creativeReleased = creativeRegions.cancelActive(reason);
        var active = routines.activeRoutineId();
        if (active.isEmpty()) {
            return creativeReleased && endVoiceFor(voiceRoutineId);
        }
        try {
            var cancelled = routines.cancelRoutine(active.orElseThrow(), reason, Long.MAX_VALUE, 1);
            var cleanup = finalizeTerminalRoutine(Minecraft.getInstance(), cancelled);
            return creativeReleased && cleanup.inputsReleased() && cleanup.voice().success();
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
                    String boundaryFailureCode = priorIncident == null
                            ? completionBoundaryFailure(minecraft, snapshot)
                            : priorIncident.boundaryFailureCode();
                    var failure = finalizationFailure(
                            snapshot,
                            cleanup.inputsReleased(),
                            cleanup.voice().success(),
                            cleanup.voice().failureCode(),
                            boundaryFailureCode,
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
            var cleanup = attempt.value();
            applyCompletionIntentAfterTerminal(cleanup.snapshot());
            return cleanup;
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
        goalContinuation.clear();
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

    private String completionBoundaryFailure(Minecraft minecraft, RoutineSnapshot snapshot) {
        if (snapshot.state() != RoutineState.FINALIZING || !snapshot.goalVerified()) {
            return null;
        }
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) {
            return safeStayFailure(
                    false, false, false, false, 0.0F, 0.0D,
                    false, false, false, false);
        }
        var velocity = player.getDeltaMovement();
        boolean visibleThreatClear = level.getEntities(
                        player,
                        player.getBoundingBox().inflate(16.0D),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream()
                .noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, 16.0D));
        return safeStayFailure(
                true,
                player.isAlive(),
                player.onGround(),
                player.isPassenger(),
                player.getHealth(),
                velocity.x * velocity.x + velocity.z * velocity.z,
                player.isUsingItem(),
                minecraft.gui.screen() == null,
                screenOwnership.snapshot().phase() == ScreenOwnershipSignals.Phase.IDLE,
                visibleThreatClear);
    }

    static String safeStayFailure(
            boolean worldReady,
            boolean alive,
            boolean onGround,
            boolean passenger,
            float health,
            double horizontalVelocitySquared,
            boolean usingItem,
            boolean screenClear,
            boolean screenOwnershipIdle,
            boolean visibleThreatClear) {
        if (!worldReady) {
            return "safe_stay_world_unavailable";
        }
        if (!alive) {
            return "safe_stay_player_not_alive";
        }
        if (!onGround) {
            return "safe_stay_not_on_ground";
        }
        if (passenger) {
            return "safe_stay_passenger";
        }
        if (!Float.isFinite(health) || health < MIN_SAFE_STAY_HEALTH) {
            return "safe_stay_low_health";
        }
        if (!Double.isFinite(horizontalVelocitySquared)
                || horizontalVelocitySquared > MAX_SAFE_STAY_HORIZONTAL_SPEED_SQUARED) {
            return "safe_stay_player_moving";
        }
        if (usingItem) {
            return "safe_stay_item_use_active";
        }
        if (!screenClear) {
            return "safe_stay_screen_open";
        }
        if (!screenOwnershipIdle) {
            return "safe_stay_screen_ownership_active";
        }
        if (!visibleThreatClear) {
            return "safe_stay_visible_hostile";
        }
        return null;
    }

    private void applyCompletionIntentAfterTerminal(RoutineSnapshot terminal) {
        String completionIntent = goalContinuation.consumeIntent(terminal.routineId());
        if (completionIntent == null) {
            return;
        }
        boolean succeeded = terminal.state() == RoutineState.SUCCEEDED
                && terminal.goalVerified()
                && terminal.finalizationFailure() == null;
        if (succeeded && GoalContinuationSession.CONTINUE_GOAL.equals(completionIntent)) {
            return;
        }
        goalContinuation.clear();
        arming.lock(succeeded ? "goal_finished" : "goal_aborted");
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
                        || creativeRegions.hasActiveJob()
                        || inbox.hasPendingCommand("start_routine")
                        || inbox.hasPendingCommand("capture_creative_region"),
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

    record ParsedApplyBlockPlan(
            ApplyBlockPlanRequest request,
            String requestIdentity,
            Map<String, Object> resourceEstimate) {
        ParsedApplyBlockPlan {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
            Objects.requireNonNull(resourceEstimate, "resourceEstimate");
        }
    }

    record ParsedPhaseFive(
            PhaseFiveRequest request,
            String requestIdentity,
            List<BlockTarget> targets) {
        ParsedPhaseFive {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    private record FullStatePair(
            BlockStateFingerprint source,
            BlockStateFingerprint transformed) {
        private FullStatePair {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(transformed, "transformed");
        }
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
        requireStartRoutineKeys(arguments);
        completionIntentArgument(arguments);
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
                var expectedBefore = blockStateArgument(parameters, "expected_before");
                SafeBreakSourcePolicy.requireRegisteredBlockId(expectedBefore.blockId());
                yield new BreakBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        expectedBefore,
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

    static ParsedApplyBlockPlan applyBlockPlanArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        if (!ApplyBlockPlanRequest.KIND.equals(stringArgument(arguments, "kind"))) {
            throw new IllegalArgumentException("kind must be apply_block_plan");
        }
        String completionIntent = completionIntentArgument(arguments);

        var parameters = objectArgument(arguments, "parameters");
        requireExactKeys(parameters, "apply_block_plan parameters", Set.of(
                "anchor", "transform", "phase", "entries"));
        var bounds = actionBoundsArgument(arguments, currentDimension);
        var anchor = dimensionBlockTargetArgument(parameters, "anchor");
        if (!anchor.dimension().equals(bounds.dimension())) {
            throw new IllegalArgumentException("anchor dimension must equal bounds.dimension");
        }

        var transformInput = objectArgument(parameters, "transform");
        requireExactKeys(transformInput, "transform", Set.of("rotation", "mirror"));
        int rotation = intArgument(transformInput, "rotation");
        var transform = new BlockPlan.Transform(rotation, stringArgument(transformInput, "mirror"));

        var phase = objectArgument(parameters, "phase");
        requireExactKeys(phase, "phase", Set.of("id", "index", "total"));
        String phaseId = stringArgument(phase, "id");
        int phaseIndex = intArgument(phase, "index");
        int phaseTotal = intArgument(phase, "total");

        Object rawEntries = parameters.get("entries");
        if (!(rawEntries instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > ApplyBlockPlanRequest.MAX_STEPS) {
            throw new IllegalArgumentException("entries must contain 1..64 items");
        }

        var canonical = new StringBuilder();
        appendIdentity(canonical, "apply_block_plan/v1");
        appendIdentity(canonical, ApplyBlockPlanRequest.KIND);
        appendTargetIdentity(canonical, anchor);
        appendIdentity(canonical, Integer.toString(transform.rotation()));
        appendIdentity(canonical, transform.mirror());
        appendIdentity(canonical, phaseId);
        appendIdentity(canonical, Integer.toString(phaseIndex));
        appendIdentity(canonical, Integer.toString(phaseTotal));
        appendIdentity(canonical, Integer.toString(entries.size()));

        var steps = new ArrayList<ApplyBlockPlanStep>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            String path = "entries[" + index + "]";
            if (!(entries.get(index) instanceof Map<?, ?> rawEntry)) {
                throw new IllegalArgumentException(path + " must be an object");
            }
            @SuppressWarnings("unchecked")
            var entry = (Map<String, Object>) rawEntry;
            String operationName = stringArgument(entry, "operation");
            ApplyBlockPlanOperation operation = switch (operationName) {
                case "verify_only" -> ApplyBlockPlanOperation.VERIFY_ONLY;
                case "break_to_air" -> ApplyBlockPlanOperation.BREAK_TO_AIR;
                case "place" -> ApplyBlockPlanOperation.PLACE;
                case "replace" -> ApplyBlockPlanOperation.REPLACE;
                default -> throw new IllegalArgumentException(path + ".operation is unsupported");
            };
            boolean itemRequired = operation == ApplyBlockPlanOperation.PLACE
                    || operation == ApplyBlockPlanOperation.REPLACE;
            var exactKeys = itemRequired
                    ? Set.of("id", "offset", "operation", "expected_before", "expected_after", "item")
                    : Set.of("id", "offset", "operation", "expected_before", "expected_after");
            requireExactKeys(entry, path, exactKeys);

            String id = stringArgument(entry, "id");
            var offset = objectArgument(entry, "offset");
            requireExactKeys(offset, path + ".offset", Set.of("x", "y", "z"));
            int rawX = relativeCoordinate(offset, "x", path);
            int rawY = relativeCoordinate(offset, "y", path);
            int rawZ = relativeCoordinate(offset, "z", path);
            var transformedOffset = transform.apply(new BlockPlan.Offset(rawX, rawY, rawZ));
            final BlockTarget target;
            try {
                target = checkedBlockTarget(
                        anchor.dimension(),
                        Math.addExact(anchor.x(), transformedOffset.x()),
                        Math.addExact(anchor.y(), transformedOffset.y()),
                        Math.addExact(anchor.z(), transformedOffset.z()));
            }
            catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(path + ".offset transforms outside supported bounds", overflow);
            }

            var before = fullBlockStateArgument(entry, "expected_before", transform, path);
            var after = fullBlockStateArgument(entry, "expected_after", transform, path);
            Optional<String> item = itemRequired
                    ? Optional.of(stringArgument(entry, "item"))
                    : Optional.empty();
            if (operation == ApplyBlockPlanOperation.REPLACE
                    && before.transformed().equals(after.transformed())) {
                throw new IllegalArgumentException(
                        path + " replace requires different exact before and after states");
            }
            steps.add(new ApplyBlockPlanStep(
                    id, operation, target, before.transformed(), after.transformed(), item));

            appendIdentity(canonical, id);
            appendIdentity(canonical, Integer.toString(rawX));
            appendIdentity(canonical, Integer.toString(rawY));
            appendIdentity(canonical, Integer.toString(rawZ));
            appendIdentity(canonical, operation.wireName());
            appendBlockStateIdentity(canonical, before.source());
            appendBlockStateIdentity(canonical, after.source());
            appendIdentity(canonical, item.orElse(""));
        }

        var request = new ApplyBlockPlanRequest(
                phaseId, phaseIndex, phaseTotal, steps, bounds);
        request.requiredResources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendIdentity(canonical, entry.getKey());
                    appendIdentity(canonical, Integer.toString(entry.getValue()));
                });
        appendIdentity(canonical, Integer.toString(request.requiredResources().size()));
        appendBoundsIdentity(canonical, bounds);
        appendIdentity(canonical, completionIntent);
        return new ParsedApplyBlockPlan(
                request,
                sha256Identity(canonical),
                resourceEstimate(request));
    }

    static ParsedPhaseFive phaseFiveRequestArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        String completionIntent = completionIntentArgument(arguments);
        String kind = stringArgument(arguments, "kind");
        if (!PhaseFiveRequest.KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind is not a Phase 5 routine");
        }
        var parameters = objectArgument(arguments, "parameters");
        var bounds = phaseFiveBoundsArgument(arguments, currentDimension);
        var targets = new ArrayList<BlockTarget>();
        int expectedUnits;
        String progressUnit;

        switch (kind) {
            case "craft_items" -> {
                requireExactKeys(parameters, "craft_items parameters", Set.of(
                        "recipe_ref", "recipe_fingerprint", "goal", "station", "max_crafts"));
                requireOpaqueReference(parameters, "recipe_ref");
                requireSha256Fingerprint(parameters, "recipe_fingerprint");
                var goal = objectArgument(parameters, "goal");
                requireExactKeys(goal, "craft_items goal", Set.of(
                        "item", "stack_policy", "minimum_inventory_count"));
                requireRegisteredItemId(stringArgument(goal, "item"));
                requireLiteral(goal, "stack_policy", "default_components_only");
                expectedUnits = requireRange(
                        intArgument(goal, "minimum_inventory_count"), 1, 2_304,
                        "minimum_inventory_count");
                requireRange(intArgument(parameters, "max_crafts"), 1, 64, "max_crafts");

                var station = objectArgument(parameters, "station");
                requireExactKeys(station, "craft_items station", Set.of(
                        "kind", "target", "expected_state"));
                requireLiteral(station, "kind", "crafting_table");
                var target = boundedDimensionTarget(station, "target", bounds);
                targets.add(target);
                var expected = exactFullState(station, "expected_state", "station");
                if (!"minecraft:crafting_table".equals(expected.blockId())) {
                    throw new IllegalArgumentException(
                            "craft_items station must be minecraft:crafting_table");
                }
                progressUnit = "items";
            }
            case "transfer_items" -> {
                requireExactKeys(parameters, "transfer_items parameters", Set.of(
                        "container", "direction", "stack", "goal", "max_transfer_count"));
                String direction = stringArgument(parameters, "direction");
                if (!direction.equals("player_to_container")
                        && !direction.equals("container_to_player")) {
                    throw new IllegalArgumentException("transfer direction is unsupported");
                }
                var container = objectArgument(parameters, "container");
                requireExactKeys(container, "transfer_items container", Set.of(
                        "target", "expected_state"));
                targets.add(boundedDimensionTarget(container, "target", bounds));
                var expected = exactFullState(container, "expected_state", "container");
                if (!expected.blockId().equals("minecraft:barrel")
                        && !expected.blockId().equals("minecraft:chest")) {
                    throw new IllegalArgumentException(
                            "transfer container must be a canonical chest or barrel");
                }
                if (expected.blockId().equals("minecraft:chest")
                        && !"single".equals(expected.properties().get("type"))) {
                    throw new IllegalArgumentException("transfer chest must be single");
                }
                var stack = objectArgument(parameters, "stack");
                requireExactKeys(stack, "transfer_items stack", Set.of("item", "stack_policy"));
                requireRegisteredItemId(stringArgument(stack, "item"));
                requireLiteral(stack, "stack_policy", "default_components_only");
                var goal = objectArgument(parameters, "goal");
                requireExactKeys(goal, "transfer_items goal", Set.of("minimum_destination_count"));
                expectedUnits = requireRange(
                        intArgument(goal, "minimum_destination_count"), 0, 2_304,
                        "minimum_destination_count");
                requireRange(intArgument(parameters, "max_transfer_count"), 1, 2_304,
                        "max_transfer_count");
                progressUnit = "items";
            }
            case "tend_crop_area" -> {
                requireExactKeys(parameters, "tend_crop_area parameters", Set.of(
                        "crop_adapter", "plots", "goal", "wait_policy"));
                String adapter = stringArgument(parameters, "crop_adapter");
                if (!Set.of("wheat", "carrots", "potatoes", "beetroots").contains(adapter)) {
                    throw new IllegalArgumentException("crop_adapter is unsupported");
                }
                var plots = objectListArgument(parameters, "plots", 1, 64);
                var ids = new java.util.HashSet<String>();
                var cropTargets = new java.util.HashSet<BlockTarget>();
                for (int index = 0; index < plots.size(); index++) {
                    var plot = plots.get(index);
                    String path = "plots[" + index + "]";
                    requireExactKeys(plot, path, Set.of(
                            "id", "crop_position", "support_position", "expected_support_state"));
                    requireUniqueLocalId(ids, stringArgument(plot, "id"), path + ".id");
                    var crop = boundedDimensionTarget(plot, "crop_position", bounds);
                    if (!cropTargets.add(crop)) {
                        throw new IllegalArgumentException("crop positions must be unique");
                    }
                    targets.add(crop);
                    targets.add(boundedDimensionTarget(plot, "support_position", bounds));
                    var support = exactFullState(plot, "expected_support_state", path);
                    if (!"minecraft:farmland".equals(support.blockId())) {
                        throw new IllegalArgumentException("crop support must be minecraft:farmland");
                    }
                }
                var goal = objectArgument(parameters, "goal");
                requireExactKeys(goal, "tend_crop_area goal", Set.of(
                        "minimum_harvested_plots", "replant", "collect_drops"));
                expectedUnits = requireRange(
                        intArgument(goal, "minimum_harvested_plots"), 0, plots.size(),
                        "minimum_harvested_plots");
                requireTrue(goal, "replant");
                requireTrue(goal, "collect_drops");
                String waitPolicy = stringArgument(parameters, "wait_policy");
                if (!waitPolicy.equals("no_wait") && !waitPolicy.equals("until_minimum")) {
                    throw new IllegalArgumentException("wait_policy is unsupported");
                }
                progressUnit = "cells";
            }
            case "harvest_tree_area" -> {
                requireExactKeys(parameters, "harvest_tree_area parameters", Set.of(
                        "trees", "collect_drops"));
                requireTrue(parameters, "collect_drops");
                var trees = objectListArgument(parameters, "trees", 1, 8);
                var ids = new java.util.HashSet<String>();
                var logTargets = new java.util.HashSet<BlockTarget>();
                int totalLogs = 0;
                for (int treeIndex = 0; treeIndex < trees.size(); treeIndex++) {
                    var tree = trees.get(treeIndex);
                    String path = "trees[" + treeIndex + "]";
                    requireExactKeys(tree, path, Set.of(
                            "id", "logs", "support", "sapling", "growth_clearance"));
                    requireUniqueLocalId(ids, stringArgument(tree, "id"), path + ".id");
                    var logs = objectListArgument(tree, "logs", 1, 64);
                    totalLogs = Math.addExact(totalLogs, logs.size());
                    if (totalLogs > 64) {
                        throw new IllegalArgumentException("all tree logs together must not exceed 64");
                    }
                    for (int logIndex = 0; logIndex < logs.size(); logIndex++) {
                        var log = logs.get(logIndex);
                        String logPath = path + ".logs[" + logIndex + "]";
                        validateExpectedCell(log, logPath, bounds, targets, logTargets);
                    }
                    validateExpectedCell(
                            objectArgument(tree, "support"), path + ".support",
                            bounds, targets, null);
                    var sapling = objectArgument(tree, "sapling");
                    requireExactKeys(sapling, path + ".sapling", Set.of(
                            "item", "expected_after_state"));
                    requireRegisteredItemId(stringArgument(sapling, "item"));
                    exactFullState(sapling, "expected_after_state", path + ".sapling");
                    var clearance = objectListArgument(tree, "growth_clearance", 1, 64);
                    for (int clearanceIndex = 0; clearanceIndex < clearance.size(); clearanceIndex++) {
                        validateExpectedCell(
                                clearance.get(clearanceIndex),
                                path + ".growth_clearance[" + clearanceIndex + "]",
                                bounds, targets, null);
                    }
                }
                expectedUnits = totalLogs;
                progressUnit = "blocks";
            }
            case "sleep_at_bed" -> {
                requireExactKeys(parameters, "sleep_at_bed parameters", Set.of(
                        "bed", "return_policy"));
                requireLiteral(parameters, "return_policy", "start_checkpoint");
                var bed = objectArgument(parameters, "bed");
                requireExactKeys(bed, "sleep_at_bed bed", Set.of(
                        "foot_position", "expected_foot_state",
                        "head_position", "expected_head_state"));
                var foot = boundedDimensionTarget(bed, "foot_position", bounds);
                var head = boundedDimensionTarget(bed, "head_position", bounds);
                if (foot.equals(head)) {
                    throw new IllegalArgumentException("bed halves must use distinct positions");
                }
                targets.add(foot);
                targets.add(head);
                var footState = exactFullState(bed, "expected_foot_state", "bed");
                var headState = exactFullState(bed, "expected_head_state", "bed");
                if (!footState.blockId().equals(headState.blockId())
                        || !footState.blockId().endsWith("_bed")) {
                    throw new IllegalArgumentException("bed halves must use the same bed block");
                }
                expectedUnits = 1;
                progressUnit = "interactions";
            }
            case "survey_area" -> {
                requireExactKeys(parameters, "survey_area parameters", Set.of(
                        "waypoints", "samples", "goal", "assessment"));
                var waypoints = objectListArgument(parameters, "waypoints", 1, 32);
                var waypointIds = new java.util.HashSet<String>();
                for (int index = 0; index < waypoints.size(); index++) {
                    var waypoint = waypoints.get(index);
                    String path = "waypoints[" + index + "]";
                    requireExactKeys(waypoint, path, Set.of("id", "target", "look_at"));
                    requireUniqueLocalId(
                            waypointIds, stringArgument(waypoint, "id"), path + ".id");
                    targets.add(boundedDimensionTarget(waypoint, "target", bounds));
                    targets.add(boundedDimensionTarget(waypoint, "look_at", bounds));
                }
                var samples = objectListArgument(parameters, "samples", 1, 256);
                var sampleIds = new java.util.HashSet<String>();
                for (int index = 0; index < samples.size(); index++) {
                    var sample = samples.get(index);
                    String path = "samples[" + index + "]";
                    requireExactKeys(sample, path, Set.of("id", "position"));
                    requireUniqueLocalId(sampleIds, stringArgument(sample, "id"), path + ".id");
                    targets.add(boundedDimensionTarget(sample, "position", bounds));
                }
                var goal = objectArgument(parameters, "goal");
                requireExactKeys(goal, "survey_area goal", Set.of("minimum_observed_samples"));
                expectedUnits = requireRange(
                        intArgument(goal, "minimum_observed_samples"), 1, samples.size(),
                        "minimum_observed_samples");
                String assessment = stringArgument(parameters, "assessment");
                if (!assessment.equals("coverage_only")
                        && !assessment.equals("spawn_surface_prediction")) {
                    throw new IllegalArgumentException("survey assessment is unsupported");
                }
                progressUnit = "cells";
            }
            default -> throw new AssertionError("unreachable Phase 5 kind");
        }

        var request = new PhaseFiveRequest(
                kind, parameters, bounds, expectedUnits, progressUnit);
        var canonical = new StringBuilder();
        appendIdentity(canonical, "phase-five/v1");
        appendCanonicalValue(canonical, kind);
        appendCanonicalValue(canonical, parameters);
        appendCanonicalValue(canonical, Map.of(
                "dimension", bounds.dimension(),
                "minimum", Map.of(
                        "x", bounds.minimum().x(), "y", bounds.minimum().y(), "z", bounds.minimum().z()),
                "maximum", Map.of(
                        "x", bounds.maximum().x(), "y", bounds.maximum().y(), "z", bounds.maximum().z()),
                "max_travel_blocks", bounds.maxTravelBlocks(),
                "max_duration_seconds", bounds.maxDurationSeconds(),
                "allow_break", bounds.allowBreak()));
        appendCanonicalValue(canonical, completionIntent);
        return new ParsedPhaseFive(
                request, sha256Identity(canonical), targets.stream().distinct().toList());
    }

    private static PhaseFiveBounds phaseFiveBoundsArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        var bounds = objectArgument(arguments, "bounds");
        requireExactKeys(bounds, "bounds", Set.of(
                "dimension", "region", "max_travel_blocks",
                "max_duration_seconds", "allow_break"));
        String dimension = stringArgument(bounds, "dimension");
        if (!dimension.equals(Objects.requireNonNull(currentDimension, "currentDimension"))) {
            throw new IllegalArgumentException("bounds.dimension must equal the current dimension");
        }
        var region = objectArgument(bounds, "region");
        requireExactKeys(region, "bounds.region", Set.of("min", "max"));
        return new PhaseFiveBounds(
                dimension,
                positionTargetArgument(region, "min", dimension),
                positionTargetArgument(region, "max", dimension),
                intArgument(bounds, "max_travel_blocks"),
                intArgument(bounds, "max_duration_seconds"),
                booleanArgument(bounds, "allow_break"));
    }

    private static BlockTarget boundedDimensionTarget(
            Map<String, Object> source,
            String name,
            PhaseFiveBounds bounds) {
        var target = dimensionBlockTargetArgument(source, name);
        if (!bounds.contains(target)) {
            throw new IllegalArgumentException(name + " must be inside bounds.region");
        }
        return target;
    }

    private static BlockStateFingerprint exactFullState(
            Map<String, Object> source,
            String name,
            String path) {
        return fullBlockStateArgument(
                source, name, new BlockPlan.Transform(0, "none"), path).transformed();
    }

    private static void validateExpectedCell(
            Map<String, Object> cell,
            String path,
            PhaseFiveBounds bounds,
            List<BlockTarget> targets,
            Set<BlockTarget> uniqueTargets) {
        requireExactKeys(cell, path, Set.of("position", "expected_state"));
        var target = boundedDimensionTarget(cell, "position", bounds);
        if (uniqueTargets != null && !uniqueTargets.add(target)) {
            throw new IllegalArgumentException("declared log positions must be unique");
        }
        targets.add(target);
        exactFullState(cell, "expected_state", path);
    }

    private static List<Map<String, Object>> objectListArgument(
            Map<String, Object> source,
            String name,
            int minimum,
            int maximum) {
        Object raw = source.get(name);
        if (!(raw instanceof List<?> values)
                || values.size() < minimum
                || values.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " must contain " + minimum + ".." + maximum + " objects");
        }
        var result = new ArrayList<Map<String, Object>>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(name + " must contain only objects");
            }
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) map;
            result.add(typed);
        }
        return List.copyOf(result);
    }

    private static void requireUniqueLocalId(Set<String> ids, String id, String path) {
        if (!id.matches("[a-z][a-z0-9_.-]{0,63}") || !ids.add(id)) {
            throw new IllegalArgumentException(path + " must be a unique local identifier");
        }
    }

    private static void requireRegisteredItemId(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        var registered = identifier == null
                ? Optional.<Holder.Reference<net.minecraft.world.item.Item>>empty()
                : BuiltInRegistries.ITEM.get(identifier);
        if (registered.isEmpty()
                || registered.orElseThrow().value() == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("item must be a registered item ID");
        }
    }

    private static void requireOpaqueReference(Map<String, Object> source, String name) {
        if (!stringArgument(source, name).matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException(name + " must be a 24-character opaque reference");
        }
    }

    private static void requireSha256Fingerprint(Map<String, Object> source, String name) {
        if (!stringArgument(source, name).matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 fingerprint");
        }
    }

    private static void requireLiteral(
            Map<String, Object> source, String name, String expected) {
        if (!expected.equals(stringArgument(source, name))) {
            throw new IllegalArgumentException(name + " must be " + expected);
        }
    }

    private static void requireTrue(Map<String, Object> source, String name) {
        if (!booleanArgument(source, name)) {
            throw new IllegalArgumentException(name + " must be true");
        }
    }

    private static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }

    private static void appendCanonicalValue(StringBuilder output, Object value) {
        if (value instanceof Map<?, ?> map) {
            appendIdentity(output, "map");
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException("canonical map keys must be strings");
                        }
                        appendIdentity(output, key);
                        appendCanonicalValue(output, entry.getValue());
                    });
            appendIdentity(output, Integer.toString(map.size()));
        }
        else if (value instanceof List<?> list) {
            appendIdentity(output, "list");
            for (Object element : list) {
                appendCanonicalValue(output, element);
            }
            appendIdentity(output, Integer.toString(list.size()));
        }
        else if (value instanceof String text) {
            appendIdentity(output, "string");
            appendIdentity(output, text);
        }
        else if (value instanceof Boolean flag) {
            appendIdentity(output, "boolean");
            appendIdentity(output, flag.toString());
        }
        else if (value instanceof Number number) {
            appendIdentity(output, "integer");
            appendIdentity(output, Long.toString(exactLong(number)));
        }
        else {
            throw new IllegalArgumentException("unsupported value in Phase 5 identity");
        }
    }

    private static int relativeCoordinate(
            Map<String, Object> offset,
            String coordinate,
            String entryPath) {
        int value = intArgument(offset, coordinate);
        if (value < -4_096 || value > 4_096) {
            throw new IllegalArgumentException(
                    entryPath + ".offset." + coordinate + " must be in -4096..4096");
        }
        return value;
    }

    private static FullStatePair fullBlockStateArgument(
            Map<String, Object> source,
            String name,
            BlockPlan.Transform transform,
            String entryPath) {
        var state = objectArgument(source, name);
        requireExactKeys(state, entryPath + "." + name, Set.of("block", "properties"));
        var fingerprint = blockStateArgument(source, name);
        var sourceView = new BlockStateView(fingerprint.blockId(), fingerprint.properties());
        var transformed = BlockPlanStateTransformer.transformFull(
                sourceView, transform, entryPath + "." + name);
        return new FullStatePair(
                fingerprint,
                new BlockStateFingerprint(transformed.block(), transformed.properties()));
    }

    private static String sha256Identity(StringBuilder canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<String, Object> resourceEstimate(ApplyBlockPlanRequest request) {
        var items = request.requiredResources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(),
                        "maximum_required_count", entry.getValue()))
                .toList();
        int breaks = 0;
        int placements = 0;
        for (var step : request.steps()) {
            if (step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                breaks++;
            }
            if (step.operation() == ApplyBlockPlanOperation.PLACE
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                placements++;
            }
        }
        return Map.of(
                "items", items,
                "break_operations", breaks,
                "place_operations", placements);
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

    private static void validateLiveBounds(
            Minecraft minecraft,
            PhaseFiveBounds bounds,
            List<BlockTarget> targets) {
        var level = minecraft.level;
        if (level == null) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
        if (!level.isInsideBuildHeight(bounds.minimum().y())
                || !level.isInsideBuildHeight(bounds.maximum().y())) {
            throw new IllegalArgumentException("bounds.region is outside the current build height");
        }
        for (var target : targets) {
            if (!bounds.contains(target)) {
                throw new IllegalArgumentException("Phase 5 target is outside bounds.region");
            }
            if (!level.getWorldBorder().isWithinBounds(
                    new net.minecraft.core.BlockPos(target.x(), target.y(), target.z()))) {
                throw new IllegalArgumentException("Phase 5 target is outside the current world border");
            }
        }
    }

    static void validateApplyBlockPlanItems(ApplyBlockPlanRequest request) {
        for (var step : request.steps()) {
            if (step.operation().mutating()
                    && (unsupportedMultiCellBlock(step.expectedBefore().blockId())
                            || unsupportedMultiCellBlock(step.expectedAfter().blockId()))) {
                throw new IllegalArgumentException(
                        "multi-cell mutation is not supported; verify each cell with verify_only");
            }
            if ((step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE)
                    && !SafeBreakSourcePolicy.allowsRegisteredBlockId(
                            step.expectedBefore().blockId())) {
                throw new IllegalArgumentException(SafeBreakSourcePolicy.REJECTION_MESSAGE);
            }
            if (step.requiredItemId().isEmpty()) {
                continue;
            }
            String itemId = step.requiredItemId().orElseThrow();
            Identifier identifier = Identifier.tryParse(itemId);
            var registered = identifier == null
                    ? Optional.<Holder.Reference<net.minecraft.world.item.Item>>empty()
                    : BuiltInRegistries.ITEM.get(identifier);
            if (registered.isEmpty()
                    || !(registered.orElseThrow().value() instanceof BlockItem blockItem)) {
                throw new IllegalArgumentException("plan item must be a registered BlockItem");
            }
            if (blockItem instanceof SolidBucketItem) {
                throw new IllegalArgumentException(
                        "plan item must not replace itself with a different container item");
            }
            if (!MinecraftApplyBlockPlanPort.supportsPlacementItem(blockItem)) {
                throw new IllegalArgumentException(
                        "plan item uses an unsupported placement implementation");
            }
            var placedBlock = blockItem.getBlock();
            String placedBlockId = BuiltInRegistries.BLOCK.getKey(placedBlock).toString();
            if (!placedBlockId.equals(step.expectedAfter().blockId())) {
                throw new IllegalArgumentException("plan item must place the expected_after block");
            }
            if (blockItem instanceof BedItem
                    || blockItem instanceof DoubleHighBlockItem
                    || placedBlock instanceof DoorBlock
                    || placedBlock instanceof BedBlock
                    || placedBlock.defaultBlockState().hasProperty(
                            BlockStateProperties.DOUBLE_BLOCK_HALF)
                    || placedBlock.defaultBlockState().hasProperty(
                            BlockStateProperties.BED_PART)) {
                throw new IllegalArgumentException(
                        "multi-cell mutation is not supported; verify each cell with verify_only");
            }
        }
    }

    static void validateStationaryBreakAllowedBlocks(Set<String> allowedBlocks) {
        Objects.requireNonNull(allowedBlocks, "allowedBlocks");
        allowedBlocks.forEach(SafeBreakSourcePolicy::requireRegisteredBlockId);
    }

    private static boolean unsupportedMultiCellBlock(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        var registered = identifier == null
                ? Optional.<Holder.Reference<net.minecraft.world.level.block.Block>>empty()
                : BuiltInRegistries.BLOCK.get(identifier);
        if (registered.isEmpty()) {
            return false;
        }
        var block = registered.orElseThrow().value();
        var state = block.defaultBlockState();
        return block instanceof DoorBlock
                || block instanceof BedBlock
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART);
    }

    private static void requireStartRoutineKeys(Map<String, Object> arguments) {
        Set<String> allowed = Set.of(
                "kind", "parameters", "bounds", "completion_intent", "idempotency_key");
        requireAllowedKeys(arguments, "start_routine", allowed);
        Set<String> required = Set.of("kind", "parameters", "bounds", "idempotency_key");
        if (!arguments.keySet().containsAll(required)
                || arguments.size() < required.size()
                || arguments.size() > allowed.size()) {
            throw new IllegalArgumentException(
                    "start_routine must contain kind, parameters, bounds, and idempotency_key; "
                            + "completion_intent is optional");
        }
    }

    static String completionIntentArgument(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        String intent = arguments.containsKey("completion_intent")
                ? stringArgument(arguments, "completion_intent")
                : GoalContinuationSession.FINISH_GOAL;
        GoalContinuationSession.requireIntent(intent);
        return intent;
    }

    static long admissionDeadlineNanos(long nowNanos, int maxDurationSeconds) {
        if (maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("max_duration_seconds must be positive");
        }
        long workDeadline = saturatingAdd(
                nowNanos, Duration.ofSeconds(maxDurationSeconds).toNanos());
        return saturatingAdd(workDeadline, FINALIZATION_RESERVE.toNanos());
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
            screenOwnership.bindWorldSession(minecraft.level, after.worldSessionId());
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
        if (cause instanceof CreativeRegionCapture.CaptureBusyException) {
            return RuntimeReply.failure("busy", "Another Creative capture is active", true);
        }
        if (cause instanceof CreativeRegionCapture.CaptureIdempotencyConflictException) {
            return RuntimeReply.failure(
                    "idempotency_conflict", "The idempotency key has different arguments", false);
        }
        if (cause instanceof CreativeRegionCapture.CaptureNotFoundException) {
            return RuntimeReply.failure(
                    "capture_not_found", "The Creative capture job is not retained", false);
        }
        if (cause instanceof RoutineManager.IdempotencyConflictException) {
            return RuntimeReply.failure(
                    "idempotency_conflict", "The idempotency key has different arguments", false);
        }
        if (cause instanceof RoutineManager.RoutineNotFoundException) {
            return RuntimeReply.failure("routine_not_found", "The routine is not retained", false);
        }
        if (cause instanceof BlockPlanValidationException invalidPlan) {
            var details = new LinkedHashMap<String, Object>(invalidPlan.details());
            details.put("plan_validation_code", invalidPlan.code());
            details.put("path", invalidPlan.path());
            return RuntimeReply.failure(
                    "invalid_argument", publicMessage(invalidPlan), false, details);
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
