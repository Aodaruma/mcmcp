package dev.aod.mcmcp.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.ActionProgramCursor;
import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.action.KnownBlockMutationAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import dev.aod.mcmcp.agent.dsl.PredicateEvaluator;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import dev.aod.mcmcp.agent.observation.ObservationFrameStore;
import dev.aod.mcmcp.agent.observation.ObservationKind;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.OmnidirectionalObserver;
import dev.aod.mcmcp.agent.observation.ObservationStoreException;
import dev.aod.mcmcp.agent.observation.ObservationWireMapper;
import dev.aod.mcmcp.agent.observation.SoundClueStore;
import dev.aod.mcmcp.agent.observation.SoundPlaybackQueue;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor;
import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.client.MultiplayerAllowlist;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import dev.aod.mcmcp.mcp.McpToolSchemas;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.observation.BlockPlanComparator;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockPlanValidationException;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.ApplyBlockPlanOperation;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.ApplyBlockPlanStep;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BlockAimWitness;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.FinitePlanRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveWorldPort;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFivePortRouter;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import dev.aod.mcmcp.voice.SimpleVoiceChat2622Adapter;
import dev.aod.mcmcp.voice.VoiceChatAdapter;
import dev.aod.mcmcp.voice.VoiceChatEventBridge;
import dev.aod.mcmcp.voice.VoiceChatSafetyController;
import dev.aod.mcmcp.voice.VoiceTransmissionGuard;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Client runtime and the sole implementation of the MCP-to-Minecraft boundary. */
public final class McmcpRuntime implements McpRuntimePort {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String MCP_PROTOCOL_VERSION = "2026-07-28";
    private static final Duration FINALIZATION_RESERVE = Duration.ofSeconds(5);
    private static final long ACTION_DELIVERY_CONFIRM_NANOS = Duration.ofSeconds(5).toNanos();
    private static final double MAX_SAFE_STAY_HORIZONTAL_SPEED_SQUARED = 0.01;
    private static final float MIN_SAFE_STAY_HEALTH = 6.0F;
    /** Expanded only when a phase has passed its gate. */
    private static final Set<String> AVAILABLE_CAPABILITIES =
            Set.of("movement", "camera", "block_break", "block_interact", "block_place");

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final ObservationFrameStore agentObservationFrames = new ObservationFrameStore();
    private final SoundClueStore soundClues = new SoundClueStore();
    private final SoundPlaybackQueue soundPlaybacks = new SoundPlaybackQueue();
    private final KnownTraversabilityMap knownTraversability = new KnownTraversabilityMap();
    private final DeterministicAStar agentPathfinder = new DeterministicAStar();
    private final AgentActionStore agentActions = new AgentActionStore();
    private final WorldMemory memory = new WorldMemory();
    private final MinecraftObservationService observations = new MinecraftObservationService(memory);
    private final BlockPlanComparator blockPlans = new BlockPlanComparator(observations, memory);
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
    private final MinecraftFinitePlanPort finitePlanPort;
    private final RoutineManager routines;
    private final VoiceChatSafetyController voiceChat;
    private final ClientCommandInbox inbox;
    private final FinalizationRetryQueue finalizationRetries = new FinalizationRetryQueue();
    private final GoalContinuationSession goalContinuation = new GoalContinuationSession();
    private RoutineWallClockDeadline activeRoutineDeadline;
    private AgentExecution agentExecution;
    private PendingAgentAdmission pendingAgentAdmission;
    private OmnidirectionalObserver agentObserver;
    private LocalObservationVolume.Snapshot latestLocalObservation;
    private MinecraftRecoveryGovernor recoveryGovernor;
    private final RecoveryDescentTracker recoveryDescent = new RecoveryDescentTracker();
    private LocalObservationProjector.CurrentSafety localSafety =
            LocalObservationProjector.CurrentSafety.REPLAN;
    private long knownTraversabilityRevision;
    private boolean soundPlaybackTruncated;
    private long pauseStartedAtNanos;

    private UUID voiceRoutineId;

    private volatile WorldSessionTracker.Snapshot publishedSession = sessions.snapshot();
    private volatile boolean paused;
    private volatile Thread clientThread;
    private volatile String endpointFaultCode;
    private volatile boolean shutdown;

    public McmcpRuntime(String modVersion, String neoForgeVersion) {
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
        finitePlanPort = new MinecraftFinitePlanPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                semanticActionPort,
                phaseFivePort);
        routines = new RoutineManager(
                stationaryBreakPort, semanticActionPort, applyBlockPlanPort,
                phaseFivePort, finitePlanPort);
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
        clearAgentSessionState();
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
        clearAgentSessionState();
        stopForLifecycle(minecraft, "level_or_dimension_change");
        routines.clearSession("level_or_dimension_change");
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
        sessions.suspendWorld();
        memory.detachSession();
        arming.lock("level_or_dimension_change");
        publishSession();
    }

    public void onLoggingOut(Minecraft minecraft) {
        assertClientThread(minecraft);
        clearAgentSessionState();
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
        clearAgentSessionState();
        stopForLifecycle(minecraft, "player_respawn");
        routines.clearSession("player_respawn");
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
        arming.lock("player_respawn");
        publishSession();
    }

    public void onPauseChanged(boolean paused) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        if (paused == this.paused) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (paused) {
            pauseStartedAtNanos = nowNanos;
            inputRelease.releaseAll(minecraft);
        } else {
            long pausedNanos = nonNegativeNanoElapsed(pauseStartedAtNanos, nowNanos);
            if (activeRoutineDeadline != null) {
                activeRoutineDeadline = activeRoutineDeadline.shiftStart(pausedNanos);
            }
            if (agentExecution != null) {
                agentExecution.pausedNanos = saturatingNonNegativeAdd(
                        agentExecution.pausedNanos, pausedNanos);
            }
            pauseStartedAtNanos = 0L;
        }
        this.paused = paused;
    }

    /** May be called by NeoForge's audio source thread; it only performs a bounded enqueue. */
    public void onSoundPlaybackStart(SoundInstance sound) {
        soundPlaybacks.capturePlaybackStart(sound);
    }

    public void onPreTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        clientThread = Thread.currentThread();
        if (shutdown) {
            return;
        }
        try {
            recordPendingAgentMotion(minecraft);
            synchronizeWorld(minecraft);
            trackRecoveryDescent(minecraft);
            sessions.tick();
            synchronizeKnownTraversability(minecraft);
            collectAgentObservation(minecraft);
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP pre-tick observation failed; stopping automation before input reuse",
                    failure);
            inbox.requestEmergencyStop("observation_pipeline_failed");
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
            publishSession();
            return;
        }
        publishSession();
        if (publishedSession.worldReady()
                && !localControlAvailable(minecraft, publishedSession)
                && (!arming.snapshot(publishedSession.worldSessionId()).locked()
                || automationActivityPending())) {
            inbox.requestEmergencyStop("player_unavailable");
        }
        inbox.drainEmergencyStopPreTick(minecraft, publishedSession);
        inbox.drainControlsPreTick(sessions.snapshot());
        retryPendingFinalizations(minecraft);
        tickActiveRoutine(minecraft);
        tickAgentAction(minecraft);
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
            McmcpMod.LOGGER.error(
                    "MCMCP stationary-break prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        try {
            semanticActionPort.captureIssuedPredictions();
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP semantic-action prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        try {
            applyBlockPlanPort.captureIssuedPredictions();
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP block-plan prediction capture failed; stopping automation",
                    failure);
            requestSafetyStop("prediction_capture_failed");
        }
        inbox.drainReadsPostTick(sessions.snapshot());
        publishSession();
    }

    public void emergencyStopFromLocalKey(Minecraft minecraft) {
        assertClientThread(minecraft);
        runPriorityStop(
                inbox::requestLocalEmergencyStop,
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        goalContinuation.clear();
        overlay(minecraft, "MCMCP: 現在の操作を緊急停止（MCP操作はON）");
    }

    /** Read-only, client-thread view used by the local HUD and pause-menu indicator. */
    public AutomationUiSnapshot automationUiSnapshot() {
        var session = sessions.snapshot();
        var lock = arming.snapshot(session.worldSessionId());
        return AutomationUiSnapshot.resolve(
                localControlAvailable(Minecraft.getInstance(), session),
                lock,
                endpointFaultCode);
    }

    public boolean inputIsolationActive() {
        var session = sessions.snapshot();
        return arming.snapshot(session.worldSessionId()).inputIsolationActive();
    }

    /** May be called by the endpoint lifecycle worker; client-thread cleanup uses the priority lane. */
    public void reportEndpointFault(String code) {
        endpointFaultCode = sanitizeLocalCode(code);
        inbox.requestEmergencyStop("endpoint_fault");
    }

    public void clearEndpointFault() {
        endpointFaultCode = null;
    }

    /** Uses the same priority lane as Esc so a UI stop releases every owned input inline. */
    public void disableAutomationFromUi(Minecraft minecraft) {
        assertClientThread(minecraft);
        runPriorityStop(
                () -> inbox.requestEmergencyStop("local_ui_disabled"),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        goalContinuation.clear();
        overlay(minecraft, "MCMCP: MCP自動操作を無効にしました");
    }

    public void disableAutomationFromUi() {
        disableAutomationFromUi(Minecraft.getInstance());
    }

    /** Local-only re-arming endpoint used by the Screen status button. */
    public boolean enableAutomationFromUi(Minecraft minecraft) {
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        if (!localControlAvailable(minecraft, session)) {
            overlay(minecraft, "MCMCP: ワールド準備前のため再許可できません");
            return false;
        }
        if (endpointFaultCode != null) {
            overlay(minecraft, "MCMCP: MCPエンドポイント障害のため許可できません");
            return false;
        }
        if (automationActivityPending()) {
            overlay(minecraft, "MCMCP: 停止処理の完了後に再許可してください");
            return false;
        }
        goalContinuation.reset(session.worldSessionId());
        arming.arm(session.worldSessionId(), availableCapabilities(minecraft));
        overlay(minecraft, "MCMCP: このワールドでMCP自動操作を再許可しました");
        return true;
    }

    public boolean enableAutomationFromUi() {
        return enableAutomationFromUi(Minecraft.getInstance());
    }

    private static String sanitizeLocalCode(String code) {
        if (code == null || code.isBlank()) {
            return "internal_error";
        }
        String normalized = code.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return "internal_error";
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static boolean localControlAvailable(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session) {
        return session.worldReady()
                && minecraft.level != null
                && minecraft.player != null
                && minecraft.player.isAlive()
                && minecraft.gameMode != null
                && minecraft.getConnection() != null;
    }

    private boolean automationActivityPending() {
        return routines.activeRoutineId().isPresent()
                || agentActions.active().isPresent()
                || inbox.hasPendingCommand("start_routine")
                || inbox.hasPendingCommand("agent_start_action")
                || finalizationRetries.hasPending()
                || voiceRoutineId != null;
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
            McmcpMod.LOGGER.error(
                    "MCMCP {} session cleanup failed; continuing the lifecycle fence",
                    name,
                    failure);
        }
    }

    private void clearPhaseFivePortSessions() {
        clearAutomationPortSession("finite_plan", finitePlanPort::clearSession);
        clearAutomationPortSession("phase_five_inventory", phaseFiveInventoryPort::clearSession);
        clearAutomationPortSession("phase_five_world", phaseFiveWorldPort::clearSession);
        clearAutomationPortSession("phase_five_router", phaseFivePort::clearSession);
    }

    private void clearAgentSessionState() {
        recoveryDescent.reset();
        agentObservationFrames.clear();
        soundClues.clear();
        soundPlaybacks.clear();
        soundPlaybackTruncated = false;
        latestLocalObservation = null;
        knownTraversability.clearWorld();
        knownTraversabilityRevision = 0L;
        localSafety = LocalObservationProjector.CurrentSafety.REPLAN;
        try {
            agentActions.terminateActive(new AgentActionStore.Failure(
                    AgentActionStore.FailureCode.WORLD_CHANGED,
                    true,
                    List.of("world_boundary")));
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP action lifecycle termination failed", failure);
        } finally {
            try {
                closeAgentControl(Minecraft.getInstance(), "world_boundary");
            } finally {
                agentActions.clear();
            }
        }
        if (agentObserver != null) {
            agentObserver.reset();
        }
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
        clearAgentSessionState();
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
                    RuntimeReply.failure("server_busy", "The client-thread deadline expired", true));
        }
        if (command instanceof StartAction start) {
            return submitPreparedAgentStart(start, context);
        }

        var fence = publishedSession;
        java.util.concurrent.Callable<RuntimeReply> work = () -> {
            if (!context.canBeginWork()) {
                throw new ClientCommandInbox.CommandTimeoutException(command.toolName());
            }
            return RuntimeReply.success(executeOnClientThread(command, context));
        };
        var submitted = command instanceof CancelRoutine
                || command instanceof CancelAction
                || command instanceof ConfirmActionDelivery
                || command instanceof AbandonActionDelivery
                ? inbox.submitControlMapped(
                        command.toolName(), fence.generation(), context.deadlineNanos(),
                        work, McmcpRuntime::mapFailure)
                : inbox.submitMapped(
                        command.toolName(), fence.generation(), context.deadlineNanos(),
                        work, McmcpRuntime::mapFailure, () -> false, ignored -> { });
        return submitted;
    }

    private CompletionStage<RuntimeReply> submitPreparedAgentStart(
            StartAction command, RuntimeCallContext context) {
        if (Thread.currentThread() == clientThread) {
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "internal_error", "Agent preflight cannot block the client thread", true));
        }
        final ActionDsl.Request request;
        final PredicateRequirements predicateRequirements;
        try {
            request = ActionDslParser.parse(GSON.toJsonTree(command.arguments()).getAsJsonObject());
            ActionDslValidator.validate(request);
            predicateRequirements = predicateRequirements(request.program());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
        }
        var fence = publishedSession;
        var capture = inbox.submit(
                command.toolName(),
                fence.generation(),
                context.deadlineNanos(),
                () -> captureAgentAdmission(
                        Minecraft.getInstance(), sessions.snapshot(), predicateRequirements));
        final AgentAdmissionSnapshot snapshot;
        try {
            snapshot = capture.get(context.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            capture.cancel(true);
            context.cancel();
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "server_busy", "Agent preflight was interrupted", true));
        } catch (TimeoutException failure) {
            capture.cancel(true);
            context.cancel();
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "server_busy", "Agent preflight capture timed out", true));
        } catch (ExecutionException | RuntimeException failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
        }

        final PreparedAgentAction prepared;
        try {
            requireLiveCall(context, command.toolName());
            prepared = prepareAgentAction(request, snapshot, context);
            requireLiveCall(context, command.toolName());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
        }

        java.util.concurrent.Callable<RuntimeReply> commit = () -> {
            if (!context.canBeginWork()) {
                throw new ClientCommandInbox.CommandTimeoutException(command.toolName());
            }
            return RuntimeReply.success(commitAgentAction(
                    Minecraft.getInstance(), sessions.snapshot(), prepared, context));
        };
        return inbox.submitMapped(
                command.toolName(),
                snapshot.session().generation(),
                context.deadlineNanos(),
                commit,
                McmcpRuntime::mapFailure,
                context::isCancelled,
                reply -> {
                    if (reply.successful()) {
                        rollbackAbandonedAgentAction(
                                reply.data(), "action_dispatch_abandoned");
                    }
                });
    }

    private Map<String, Object> executeOnClientThread(
            RuntimeCommand command,
            RuntimeCallContext context) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        return switch (command) {
            case GetState ignored -> status(minecraft, session);
            case GetObservation observation -> {
                requireReady(session);
                yield getAgentObservation(observation.arguments());
            }
            case StartAction action -> {
                throw new AssertionError("agent_start_action must use worker preflight");
            }
            case GetAction action -> getAgentAction(action.arguments());
            case CancelAction action -> cancelAgentAction(minecraft, action.arguments());
            case ConfirmActionDelivery delivery -> confirmAgentActionDelivery(delivery.actionId());
            case AbandonActionDelivery delivery -> abandonAgentActionDelivery(delivery.actionId());
            case GetSnapshot snapshot -> {
                requireReady(session);
                yield observations.getSnapshot(minecraft, session.clientTick(), snapshot.arguments());
            }
            case CompareBlockPlan compare -> {
                requireReady(session);
                yield blockPlans.compare(minecraft, session.clientTick(), compare.arguments());
            }
            case GetRecipes getRecipes -> {
                requireReady(session);
                yield getRecipes(minecraft, session, getRecipes.arguments());
            }
            case ListRoutines list -> listRoutines(list.arguments());
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

    private Map<String, Object> getAgentObservation(Map<String, Object> arguments) {
        requireExactKeys(arguments, "agent_get_observation",
                Set.of("schema_version", "frame_id", "kinds", "cursor", "limit"));
        if (intArgument(arguments, "schema_version") != 1) {
            throw new IllegalArgumentException("schema_version must be 1");
        }
        Object rawKinds = arguments.get("kinds");
        if (!(rawKinds instanceof List<?> values)) {
            throw new IllegalArgumentException("kinds must be an array");
        }
        var kinds = EnumSet.noneOf(ObservationKind.class);
        for (Object value : values) {
            if (!(value instanceof String wireName) || !kinds.add(ObservationKind.fromWireName(wireName))) {
                throw new IllegalArgumentException("kinds must contain unique observation kinds");
            }
        }
        Object rawCursor = arguments.get("cursor");
        String cursor = rawCursor == null ? null : (String) rawCursor;
        try {
            return ObservationWireMapper.page(agentObservationFrames.page(
                    stringArgument(arguments, "frame_id"),
                    kinds,
                    cursor,
                    intArgument(arguments, "limit")));
        } catch (ObservationStoreException failure) {
            throw new RuntimeInvocationException(
                    failure.code().name().toLowerCase(Locale.ROOT),
                    failure.getMessage(),
                    failure.code() != ObservationStoreException.Code.INVALID_CURSOR,
                    Map.of());
        }
    }

    private AgentAdmissionSnapshot captureAgentAdmission(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PredicateRequirements predicateRequirements) {
        assertClientThread(minecraft);
        requireReady(session);
        if (agentActions.active().isPresent() || routines.activeRoutineId().isPresent()) {
            throw new RuntimeInvocationException(
                    "task_busy", "Another action is already queued or running.", true, Map.of());
        }
        if (minecraft.isMultiplayerServer() && !multiplayerPolicyAllows(minecraft)) {
            throw new RuntimeInvocationException(
                    "multiplayer_not_allowed",
                    "This local policy does not allow multiplayer automation.",
                    false,
                    Map.of());
        }
        var lock = arming.snapshot(session.worldSessionId());
        if (lock.mode() != LocalArmingState.Mode.READY) {
            throw new RuntimeInvocationException(
                    "mcp_operation_disabled",
                    "Enable MCP operation from the in-game Screen before starting an action.",
                    true,
                    Map.of());
        }
        if (localSafety != LocalObservationProjector.CurrentSafety.CONTINUE) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The Local Observation Volume does not permit action admission.",
                    true,
                    Map.of());
        }
        var map = requireAgentMap(session);
        var player = Objects.requireNonNull(minecraft.player, "player");
        var predicateSnapshot = AdmissionPolicySnapshot.capture(
                policySnapshot(minecraft), predicateRequirements);
        return new AgentAdmissionSnapshot(
                session,
                lock,
                map,
                playerPose(player, session.dimension()),
                agentObservationFrames.latestFrame(),
                localSafety,
                predicateRequirements,
                predicateSnapshot,
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                minecraft.isMultiplayerServer(),
                multiplayerPolicyAllows(minecraft),
                reconciliationSignals.bindAndSnapshot(
                        minecraft.level, session.worldSessionId())
                        .positionCorrectionRevision());
    }

    private static boolean multiplayerPolicyAllows(Minecraft minecraft) {
        if (!minecraft.isMultiplayerServer()) return true;
        var server = minecraft.getCurrentServer();
        return McmcpClientConfig.multiplayerDefault()
                && server != null
                && MultiplayerAllowlist.allows(
                        minecraft.gameDirectory.toPath()
                                .resolve("config/mcmcp/allowed-servers.json"),
                        server.ip);
    }

    private PreparedAgentAction prepareAgentAction(
            ActionDsl.Request request,
            AgentAdmissionSnapshot snapshot,
            RuntimeCallContext context) {
        validatePredicateAvailability(request.program(), snapshot.predicateSnapshot());
        var allowed = EnumSet.noneOf(ActionDsl.Capability.class);
        if (snapshot.control().capabilities().contains("movement")) {
            allowed.add(ActionDsl.Capability.MOVEMENT);
        }
        if (snapshot.control().capabilities().contains("camera")) {
            allowed.add(ActionDsl.Capability.CAMERA);
        }
        if (snapshot.control().capabilities().contains("block_break")) {
            allowed.add(ActionDsl.Capability.BLOCK_BREAK);
        }
        if (snapshot.control().capabilities().contains("block_interact")) {
            allowed.add(ActionDsl.Capability.BLOCK_INTERACT);
        }
        if (snapshot.control().capabilities().contains("block_place")) {
            allowed.add(ActionDsl.Capability.BLOCK_PLACE);
        }
        ActionDslCompiler.CompiledProgram program = ActionDslCompiler.compile(
                request, McmcpRuntime::structuralPrimitiveCost, allowed);
        Optional<ActionDsl.Node> initialPrimitive = firstPrimitive(
                request.program(), snapshot.predicateSnapshot());
        final AgentPrimitivePlanner.Analysis analysis;
        try {
            analysis = initialPrimitive
                    .filter(McmcpRuntime::requiresWorldPlanning)
                    .map(primitive -> analyzePrimitive(
                            request.program(),
                            primitive,
                            snapshot.map(),
                            snapshot.pose(),
                            snapshot.frame(),
                            snapshot.cameraDegreesPerTick(),
                            context::canBeginWork))
                    .orElseGet(McmcpRuntime::emptyPrimitiveAnalysis);
            initialPrimitive.flatMap(analysis::worstCase).ifPresent(cost -> {
                if (!costWithinBudget(cost, program.effectiveBudget())) {
                    throw new ActionDslException(
                            ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE,
                            "The initial primitive exceeds the effective action budget");
                }
            });
        } catch (AgentPrimitivePlanner.PlanningException failure) {
            throw planningFailure(failure);
        }
        return new PreparedAgentAction(snapshot, program, analysis, initialPrimitive);
    }

    private AgentPrimitivePlanner.Analysis analyzePrimitive(
            ActionDsl.Program program,
            ActionDsl.Node primitive,
            KnownTraversabilitySnapshot map,
            AgentPrimitivePlanner.Pose pose,
            Optional<ObservationFrame> frame,
            float cameraDegreesPerTick,
            java.util.function.BooleanSupplier canContinue) {
        var oneNode = new ActionDsl.Program(
                program.dslVersion(), Optional.empty(), program.capabilities(), List.of(primitive));
        return AgentPrimitivePlanner.analyze(
                oneNode,
                map,
                agentPathfinder,
                pose,
                frame,
                cameraDegreesPerTick,
                canContinue);
    }

    private static AgentPrimitivePlanner.Analysis emptyPrimitiveAnalysis() {
        return new AgentPrimitivePlanner.Analysis(
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
    }

    private static Optional<ActionDsl.Node> firstPrimitive(
            ActionDsl.Program program, PolicySnapshot snapshot) {
        return Optional.ofNullable(new ActionProgramCursor(program).next(snapshot).primitive());
    }

    private static boolean requiresWorldPlanning(ActionDsl.Node node) {
        return !(node instanceof ActionDsl.WaitTicks || node instanceof ActionDsl.WaitUntil);
    }

    static Optional<ActionDslCompiler.Cost> structuralPrimitiveCost(ActionDsl.Node node) {
        long interactions = node instanceof ActionDsl.TillKnownBlock
                        || node instanceof ActionDsl.OpenKnownFenceGate
                ? 1L : 0L;
        long breaks = node instanceof ActionDsl.BreakKnownFace
                        || node instanceof ActionDsl.HarvestKnownWheat
                ? 1L : 0L;
        long placements = node instanceof ActionDsl.PlantKnownWheat ? 1L : 0L;
        return Optional.of(new ActionDslCompiler.Cost(
                0L, 0L, 0.0D, 0.0D, interactions, breaks, placements));
    }

    private static boolean costWithinBudget(
            ActionDslCompiler.Cost cost, ActionDsl.Budget budget) {
        return cost.durationMillis() <= budget.maxDurationMillis()
                && cost.ticks() <= budget.maxTicks()
                && cost.distanceBlocks() <= budget.maxDistanceBlocks()
                && cost.cameraDegrees() <= budget.maxCameraDegrees()
                && cost.interactions() <= budget.maxInteractions()
                && cost.blocksBroken() <= budget.maxBlocksBroken()
                && cost.blocksPlaced() <= budget.maxBlocksPlaced();
    }

    private Map<String, Object> commitAgentAction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PreparedAgentAction prepared,
            RuntimeCallContext context) {
        assertClientThread(minecraft);
        var captured = prepared.snapshot();
        if (agentActions.active().isPresent() || routines.activeRoutineId().isPresent()) {
            throw new RuntimeInvocationException(
                    "task_busy", "Another action is already queued or running.", true, Map.of());
        }
        if (!admissionFenceCurrent(
                minecraft,
                session,
                prepared,
                LocalArmingState.Mode.READY,
                captured.control().controlEpoch())) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The world, local control, pose, observation, or policy changed during preflight.",
                    true,
                    Map.of());
        }
        requireLiveCall(context, "agent_start_action");
        if (!arming.beginAction(session.worldSessionId())) {
            throw new RuntimeInvocationException(
                    "mcp_operation_disabled",
                    "The READY authorization is no longer available.",
                    true,
                    Map.of());
        }
        AgentActionStore.Accepted accepted = null;
        try {
            requireLiveCall(context, "agent_start_action");
            accepted = agentActions.reserve(
                    prepared.program(),
                    Instant.now(),
                    System.nanoTime() + ACTION_DELIVERY_CONFIRM_NANOS);
            pendingAgentAdmission = new PendingAgentAdmission(accepted.actionId(), prepared);
            requireLiveCall(context, "agent_start_action");
        } catch (RuntimeException | LinkageError failure) {
            if (accepted == null) {
                arming.lock("action_admission_failed");
            } else {
                rollbackAbandonedAgentAction(
                        Map.of("action_id", accepted.actionId().toString()),
                        "action_admission_abandoned");
            }
            throw failure;
        }
        return Map.of(
                "schema_version", 1,
                "action_id", accepted.actionId().toString(),
                "state", "queued",
                "accepted_at", accepted.acceptedAt().toString());
    }

    private static boolean sameAdmissionSession(
            WorldSessionTracker.Snapshot captured,
            WorldSessionTracker.Snapshot current) {
        return current.worldReady()
                && captured.generation() == current.generation()
                && Objects.equals(captured.worldSessionId(), current.worldSessionId())
                && Objects.equals(captured.dimension(), current.dimension());
    }

    private boolean admissionFenceCurrent(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PreparedAgentAction prepared,
            LocalArmingState.Mode expectedMode,
            long expectedControlEpoch) {
        var captured = prepared.snapshot();
        var player = minecraft.player;
        var lock = arming.snapshot(session.worldSessionId());
        if (!sameAdmissionSession(captured.session(), session)
                || player == null
                || lock.mode() != expectedMode
                || lock.controlEpoch() != expectedControlEpoch
                || !lock.capabilities().equals(captured.control().capabilities())
                || !playerPose(player, session.dimension()).equals(captured.pose())
                || captured.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE
                || localSafety != LocalObservationProjector.CurrentSafety.CONTINUE
                || McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F
                        != captured.cameraDegreesPerTick()
                || minecraft.isMultiplayerServer() != captured.multiplayerServer()
                || multiplayerPolicyAllows(minecraft) != captured.multiplayerAllowed()
                || reconciliationSignals.bindAndSnapshot(
                                minecraft.level, session.worldSessionId())
                        .positionCorrectionRevision()
                        != captured.positionCorrectionRevision()) {
            return false;
        }
        final KnownTraversabilitySnapshot currentMap;
        final AdmissionPolicySnapshot currentPredicates;
        try {
            currentMap = requireAgentMap(session);
            currentPredicates = AdmissionPolicySnapshot.capture(
                    policySnapshot(minecraft), captured.predicateRequirements());
            validatePredicateAvailability(
                    prepared.program().request().program(), currentPredicates);
        } catch (RuntimeException | LinkageError changed) {
            return false;
        }
        return firstPrimitive(prepared.program().request().program(), currentPredicates)
                        .equals(prepared.initialPrimitive())
                && routeDependenciesCurrent(currentMap, prepared.analysis().routeDependencies())
                && prepared.analysis().knownTargets().stream().allMatch(target ->
                        AgentPrimitivePlanner.knownTarget(
                                currentMap, agentObservationFrames.latestFrame(), target))
                && prepared.analysis().knownSurfaces().stream().allMatch(surface ->
                        AgentPrimitivePlanner.knownSurface(
                                currentMap, agentObservationFrames.latestFrame(), surface))
                && breakProgramPreconditionsCurrent(
                        minecraft, prepared.program(), prepared.initialPrimitive());
    }

    static boolean routeDependenciesCurrent(
            KnownTraversabilitySnapshot current,
            Map<dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Key,
                    dev.aod.mcmcp.agent.navigation.TraversabilityEdge> required) {
        for (var dependency : required.entrySet()) {
            var currentEdge = current.edge(dependency.getKey()).orElse(null);
            if (currentEdge == null || !sameOrSaferEdge(dependency.getValue(), currentEdge)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOrSaferEdge(
            dev.aod.mcmcp.agent.navigation.TraversabilityEdge captured,
            dev.aod.mcmcp.agent.navigation.TraversabilityEdge current) {
        return captured.worldSessionId().equals(current.worldSessionId())
                && (captured.status() == current.status()
                        || captured.status()
                                == dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Status.PROBE_ALLOWED
                        && current.status()
                                == dev.aod.mcmcp.agent.navigation.TraversabilityEdge.Status.CONFIRMED);
    }

    private void rollbackAbandonedAgentAction(
            Map<String, Object> receipt, String reason) {
        try {
            Object rawActionId = receipt.get("action_id");
            if (rawActionId instanceof String value) {
                UUID actionId = UUID.fromString(value);
                if (!agentActions.get(actionId).state().terminal()) {
                    agentActions.cancel(actionId);
                }
            }
        } finally {
            closeAgentControl(Minecraft.getInstance(), reason);
        }
    }

    private Map<String, Object> confirmAgentActionDelivery(UUID actionId) {
        var pending = pendingAgentAdmission;
        var minecraft = Minecraft.getInstance();
        if (pending == null
                || !pending.actionId().equals(actionId)
                || !admissionFenceCurrent(
                        minecraft,
                        sessions.snapshot(),
                        pending.prepared(),
                        LocalArmingState.Mode.AGENT,
                        pending.prepared().snapshot().control().controlEpoch() + 1L)) {
            boolean abandoned;
            try {
                abandoned = agentActions.abandonUnconfirmed(
                        actionId, "admission_changed_before_delivery_confirmation");
            } catch (AgentActionStore.NotFoundException failure) {
                abandoned = false;
            }
            if (abandoned) {
                closeAgentControl(minecraft, "action_admission_changed");
            }
            return Map.of("action_id", actionId.toString(), "confirmed", false);
        }
        AgentActionStore.Confirmation confirmation;
        try {
            confirmation = agentActions.confirm(actionId, System.nanoTime());
        } catch (AgentActionStore.NotFoundException failure) {
            confirmation = AgentActionStore.Confirmation.STALE;
        }
        if (confirmation == AgentActionStore.Confirmation.EXPIRED) {
            closeAgentControl(Minecraft.getInstance(), "action_delivery_confirmation_failed");
        }
        return Map.of(
                "action_id", actionId.toString(),
                "confirmed", confirmation.confirmed());
    }

    private Map<String, Object> abandonAgentActionDelivery(UUID actionId) {
        boolean abandoned;
        try {
            abandoned = agentActions.abandonUnconfirmed(
                    actionId, "http_response_not_delivered");
        } catch (AgentActionStore.NotFoundException failure) {
            abandoned = false;
        }
        if (abandoned) {
            closeAgentControl(Minecraft.getInstance(), "action_delivery_abandoned");
        }
        return Map.of("action_id", actionId.toString(), "abandoned", abandoned);
    }

    private Map<String, Object> getAgentAction(Map<String, Object> arguments) {
        requireExactKeys(arguments, "agent_get_action", Set.of("action_id"));
        return actionPayload(agentActions.get(actionId(arguments)));
    }

    private Map<String, Object> cancelAgentAction(
            Minecraft minecraft, Map<String, Object> arguments) {
        requireExactKeys(arguments, "agent_cancel_action", Set.of("action_id"));
        UUID requestedId = actionId(arguments);
        boolean activeBeforeRequest = !agentActions.get(requestedId).state().terminal();
        final AgentActionStore.CancelResult cancelled;
        try {
            cancelled = agentActions.cancel(requestedId);
        } finally {
            if (activeBeforeRequest) {
                finishAgentControlReady(minecraft, "action_cancelled");
            }
        }
        return Map.of(
                "schema_version", 1,
                "action_id", cancelled.actionId().toString(),
                "cancel_requested", cancelled.cancelRequested(),
                "state_at_request", cancelled.stateAtRequest().wireName());
    }

    private static UUID actionId(Map<String, Object> arguments) {
        try {
            return UUID.fromString(stringArgument(arguments, "action_id"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("action_id must be a UUID", failure);
        }
    }

    private static Map<String, Object> actionPayload(AgentActionStore.Snapshot snapshot) {
        var progress = snapshot.progress();
        var progressPayload = new LinkedHashMap<String, Object>();
        progressPayload.put("phase", progress.phase().wireName());
        progressPayload.put("current_node_id", progress.currentNodeId());
        progressPayload.put("executed_nodes", progress.executedNodes());
        progressPayload.put("total_node_upper_bound", progress.totalNodeUpperBound());
        progressPayload.put("distance_travelled", progress.distanceTravelled());
        progressPayload.put("camera_degrees", progress.cameraDegrees());
        progressPayload.put("interactions", progress.interactions());
        progressPayload.put("blocks_broken", progress.blocksBroken());
        progressPayload.put("blocks_placed", progress.blocksPlaced());
        progressPayload.put("ticks", progress.ticks());

        Map<String, Object> failurePayload = null;
        if (snapshot.failure() != null) {
            failurePayload = Map.of(
                    "code", snapshot.failure().code().wireName(),
                    "recoverable", snapshot.failure().recoverable(),
                    "evidence", snapshot.failure().evidence());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("action_id", snapshot.actionId().toString());
        result.put("state", snapshot.state().wireName());
        result.put("progress", progressPayload);
        result.put("failure", failurePayload);
        result.put("trace", snapshot.trace().stream().map(entry -> Map.<String, Object>of(
                "tick", entry.tick(),
                "event", entry.event(),
                "detail", entry.detail())).toList());
        return result;
    }

    private static Set<String> availableCapabilities(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        return AVAILABLE_CAPABILITIES;
    }

    private Map<String, Object> status(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        var lock = arming.snapshot(session.worldSessionId());
        var inventory = new LinkedHashMap<String, Integer>();
        Map<String, Object> world = null;

        if (session.worldReady() && minecraft.player != null && minecraft.level != null) {
            var player = minecraft.player;
            world = new LinkedHashMap<>();
            world.put("dimension", session.dimension());
            world.put("client_tick", session.clientTick());
            world.put("world_revision", reconciliationSignals
                    .bindAndSnapshot(minecraft.level, session.worldSessionId())
                    .worldRevision());
            world.put("position", Map.of(
                    "x", player.getX(),
                    "y", player.getY(),
                    "z", player.getZ()));
            world.put("yaw", Mth.wrapDegrees(player.getYRot()));
            world.put("pitch", Mth.clamp(player.getXRot(), -90.0F, 90.0F));
            world.put("health", player.getHealth());
            world.put("absorption", player.getAbsorptionAmount());
            world.put("hunger", player.getFoodData().getFoodLevel());
            world.put("air", player.getAirSupply());
            world.put("max_air", player.getMaxAirSupply());
            world.put("on_fire", player.isOnFire());
            world.put("submerged", player.isUnderWater());
            world.put("status_effects", player.getActiveEffects().stream()
                    .map(effect -> effect.getEffect().getRegisteredName())
                    .distinct()
                    .sorted()
                    .limit(64)
                    .toList());

            var playerInventory = player.getInventory();
            for (int slot = 0; slot < playerInventory.getContainerSize(); slot++) {
                var stack = playerInventory.getItem(slot);
                if (!stack.isEmpty()) {
                    String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    inventory.merge(item, stack.getCount(), Integer::sum);
                }
            }
        }

        var inventoryPayload = inventory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(),
                        "count", entry.getValue()))
                .toList();
        var result = new LinkedHashMap<>(
                statePayload(
                        lock,
                        paused,
                        world,
                        inventoryPayload,
                        minecraft.isMultiplayerServer() && multiplayerPolicyAllows(minecraft),
                        McmcpClientConfig.visualRadiusBlocks(),
                        McmcpClientConfig.raysPerTick()));
        result.put("observation", agentObservationFrames.latestSummary()
                .map(ObservationWireMapper::summary)
                .orElse(null));
        result.put("action", agentActions.latestSummary()
                .map(summary -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("action_id", summary.actionId().toString());
                    value.put("state", summary.state().wireName());
                    value.put("end_reason", summary.endReason());
                    return value;
                })
                .orElse(null));
        return result;
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory) {
        return statePayload(
                lock, paused, world, inventory,
                false,
                McmcpClientConfig.DEFAULT_VISUAL_RADIUS_BLOCKS,
                McmcpClientConfig.DEFAULT_RAYS_PER_TICK);
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory,
            boolean multiplayerEnabled,
            int visualRadiusBlocks,
            int raysPerTick) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(inventory, "inventory");

        var control = new LinkedHashMap<String, Object>();
        control.put("mode", lock.mode().name().toLowerCase(Locale.ROOT));
        // Kept nullable for MCP clients written against schema version 1; READY no longer expires.
        control.put("ready_expires_at", null);
        control.put("game_paused", paused);

        var actionDsl = Map.<String, Object>of(
                "version", 1,
                "max_ast_depth", 4,
                "max_source_nodes", 64,
                "max_executed_nodes", 256,
                "max_repeat_count", 16,
                "allowed_capabilities", AVAILABLE_CAPABILITIES.stream().sorted().toList());
        var policy = Map.<String, Object>ofEntries(
                Map.entry("profile", "survival_omnidirectional"),
                Map.entry("multiplayer_enabled", multiplayerEnabled),
                Map.entry("max_duration_ms", 600_000),
                Map.entry("max_ticks", 12_000),
                Map.entry("max_distance_blocks", 32),
                Map.entry("max_camera_degrees", 360),
                Map.entry("max_blocks_broken", 8),
                Map.entry("max_interactions", 8),
                Map.entry("max_blocks_placed", 8),
                Map.entry("omnidirectional_visual_radius_blocks", visualRadiusBlocks),
                Map.entry("local_observation_radius_blocks", 4),
                Map.entry("omnidirectional_direction_count", 2_048),
                Map.entry("omnidirectional_rays_per_tick", raysPerTick),
                Map.entry("max_recent_sound_clues", 32),
                Map.entry("sound_clue_ttl_ticks", 600),
                Map.entry("action_dsl", actionDsl));

        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("control", control);
        result.put("world", world);
        result.put("inventory", List.copyOf(inventory));
        result.put("policy", policy);
        result.put("observation", null);
        result.put("action", null);
        return result;
    }

    private Map<String, Object> listRoutines(Map<String, Object> arguments) {
        Object kind = arguments.get("kind");
        return kind == null
                ? routineCatalog()
                : routineCatalog(stringArgument(arguments, "kind"));
    }

    static Map<String, Object> routineCatalog() {
        var summaries = detailedRoutineCatalog().stream()
                .map(McmcpRuntime::routineCatalogSummary)
                .toList();
        return Map.of(
                "catalog_version", "phase-6-compact-v2",
                "routines", summaries);
    }

    static Map<String, Object> routineCatalog(String kind) {
        Objects.requireNonNull(kind, "kind");
        var entry = detailedRoutineCatalog().stream()
                .filter(candidate -> kind.equals(candidate.get("kind")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("kind is not an available routine"));
        var detailed = new LinkedHashMap<>(entry);
        detailed.put("capabilities", routineCapabilities(kind));
        return Map.of(
                "catalog_version", "phase-6-compact-v2",
                "routines", List.of(Map.copyOf(detailed)));
    }

    private static List<Map<String, Object>> detailedRoutineCatalog() {
        return List.of(
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
                                        "the exact item is selected from hotbar or staged once from player inventory before dispatch",
                                        "wheat, carrot, potato, and beetroot items may initially plant an exact age-0 crop above farmland",
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
                                UseItemOnBlockRequest.KIND,
                                3,
                                McpToolSchemas.useItemOnBlockStartInput(),
                                List.of(
                                        "the exact item is selected from hotbar or staged once from player inventory before dispatch",
                                        "the closed transition tills dirt, grass block, or dirt path to moisture-0 farmland with a vanilla hoe",
                                        "exactly one allowlisted normal-use item action is dispatched",
                                        "the action has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
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
                                        "minimum_destination_count zero performs a no-mutation bounded content readback",
                                        "default-only or exact item-ID whole stacks, including damaged tools, can be transferred",
                                        "a missing requested item reports a bounded list of observed source item IDs for replanning",
                                        "one bounded quick-move segment is never retried blindly",
                                        "success requires a fresh reopened full-content snapshot for both endpoints and an empty cursor")),
                        routineCatalogEntry(
                                "tend_crop_area",
                                5,
                                McpToolSchemas.tendCropAreaStartInput(),
                                List.of(
                                        "only existing declared crop blocks are harvested or replanted; air cells require place_block",
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
                                        "spawn-surface assessment is explicitly predicted rather than server-confirmed")),
                        routineCatalogEntry(
                                "execute_plan",
                                6,
                                McpToolSchemas.executePlanStartInput(),
                                List.of(
                                        "every child action remains private to one parent routine",
                                        "all loops, waits, total ticks, nesting, and expanded executions are bounded",
                                        "conditions authorize progress only from positive current evidence",
                                        "the active child action is released before any terminal parent state")));
    }

    private static Map<String, Object> routineCatalogSummary(Map<String, Object> entry) {
        String kind = (String) entry.get("kind");
        return Map.of(
                "kind", kind,
                "phase", entry.get("phase"),
                "experimental", entry.get("experimental"),
                "capabilities", routineCapabilities(kind));
    }

    private static List<String> routineCapabilities(String kind) {
        return switch (kind) {
            case "stationary_break" -> List.of("break one regenerating target", "collect to inventory goal");
            case NavigateToRequest.KIND -> List.of("bounded ground navigation");
            case BreakBlockRequest.KIND -> List.of("break one exact block to air");
            case PlaceBlockRequest.KIND -> List.of(
                    "place one exact block or initially plant one crop",
                    "auto-stage the exact item from player inventory");
            case InteractBlockRequest.KIND -> List.of("toggle one allowlisted block");
            case InteractEntityRequest.KIND -> List.of("interact with one visible referenced entity");
            case UseItemOnBlockRequest.KIND -> List.of(
                    "till one exact dirt, grass, or path block with a vanilla hoe",
                    "auto-stage the exact item from player inventory");
            case ApplyBlockPlanRequest.KIND -> List.of("verify, break, place, or replace up to 64 declared cells");
            case "craft_items" -> List.of("craft a client-known recipe to an inventory goal");
            case "transfer_items" -> List.of(
                    "open, transfer one item type to or from one container, verify, and close",
                    "inspect bounded source item choices without mutation by setting the destination goal to zero",
                    "report bounded source item choices when the requested item is absent");
            case "tend_crop_area" -> List.of(
                    "harvest and replant existing declared crop plots",
                    "use place_block for initial planting into air");
            case "harvest_tree_area" -> List.of("harvest and replant declared visible tree cells");
            case "sleep_at_bed" -> List.of("sleep at one declared bed and return");
            case "survey_area" -> List.of("visit declared waypoints and observe declared samples");
            case "execute_plan" -> List.of(
                    "execute a bounded typed sequence with finite loops and checks",
                    "compose transfer_items, use_item_on_block, and place_block for farming");
            default -> throw new IllegalArgumentException("kind is not an available routine");
        };
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

        if (!arming.allows(session.worldSessionId(), "stationary_break")) {
            throw new RuntimeInvocationException(
                    "locked",
                    "stationary_break is not armed for this world session",
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
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
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
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
                    false,
                    Map.of());
        }
        for (var step : request.steps()) {
            validateLiveBounds(minecraft, request.bounds(), step.target());
        }
        validateApplyBlockPlanItems(request);

        var receipt = admitWithVoiceSafety(
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
        if (!arming.allows(session.worldSessionId(), request.kind())) {
            throw new RuntimeInvocationException(
                    "locked",
                    request.kind() + " is not armed for this world session",
                    false,
                    Map.of());
        }
        validateLiveBounds(minecraft, request.bounds(), parsed.targets());

        var receipt = admitWithVoiceSafety(
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
        var parsed = finitePlanRequestArgument(arguments);
        var request = parsed.request();
        String idempotencyKey = stringArgument(arguments, "idempotency_key");
        var replay = replayFinitePlanAfterFinalizationGate(
                finalizationRetries,
                routines,
                idempotencyKey,
                parsed.requestIdentity(),
                session.clientTick());
        if (replay.isPresent()) {
            return startReceiptPayload(replay.orElseThrow());
        }

        requireLiveCall(context, "start_routine");
        if (!arming.allows(session.worldSessionId(), "execute_plan")) {
            throw new RuntimeInvocationException(
                    "locked",
                    "execute_plan is not armed for this world session",
                    false,
                    Map.of());
        }
        finitePlanPort.validate(request);
        int maxDurationSeconds = (request.maxTicks() + 19) / 20;
        var receipt = admitWithVoiceSafety(
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

    private RoutineManager.StartReceipt admitWithVoiceSafety(
            RuntimeCallContext context,
            UUID worldSessionId,
            String completionIntent,
            int maxDurationSeconds,
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
            McmcpMod.LOGGER.error("MCMCP Voice Chat begin safety gate threw", failure);
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
        if (!arming.beginAction(worldSessionId)) {
            var voiceEnd = endVoiceSessionFor(null);
            var details = new LinkedHashMap<String, Object>();
            details.put("reason", "ready_lease_unavailable");
            appendVoiceEndDetails(details, voiceEnd);
            throw new RuntimeInvocationException(
                    "locked",
                    "The READY authorization is no longer available",
                    true,
                    details);
        }
        try {
            requireLiveCall(context, "start_routine");
            receipt = admission.get();
        }
        catch (RuntimeException | LinkageError failure) {
            arming.lock("action_admission_failed");
            var voiceEnd = endVoiceSessionFor(null);
            throw withVoiceEndFailureDiagnostics(failure, voiceEnd);
        }
        if (!receipt.reused()) {
            long startedAtNanos = System.nanoTime();
            activeRoutineDeadline = RoutineWallClockDeadline.start(
                    receipt.routineId(), maxDurationSeconds, startedAtNanos);
            if (paused) {
                pauseStartedAtNanos = startedAtNanos;
            }
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
            case UseItemOnBlockRequest use -> {
                appendTargetIdentity(canonical, use.target());
                appendBlockStateIdentity(canonical, use.expectedBefore());
                appendIdentity(canonical, use.item());
                appendBlockStateIdentity(canonical, use.expectedAfter());
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

    private void tickAgentAction(Minecraft minecraft) {
        var active = agentActions.active();
        if (active.isEmpty()) {
            closeAgentPrimitiveExecutor();
            closeRecoveryGovernor();
            agentExecution = null;
            return;
        }
        var action = active.orElseThrow();
        if (action.state() == AgentActionStore.State.UNCONFIRMED) {
            if (agentActions.expireUnconfirmed(System.nanoTime())) {
                closeAgentControl(minecraft, "action_delivery_confirmation_timeout");
            }
            return;
        }
        var session = sessions.snapshot();
        try {
            if (agentExecution == null || !agentExecution.actionId.equals(action.actionId())) {
                if (minecraft.player == null || !session.worldReady()) {
                    failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true, "world_unavailable");
                    return;
                }
                var pending = pendingAgentAdmission;
                if (pending == null
                        || !pending.actionId().equals(action.actionId())
                        || !admissionFenceCurrent(
                                minecraft,
                                session,
                                pending.prepared(),
                                LocalArmingState.Mode.AGENT,
                                pending.prepared().snapshot().control().controlEpoch() + 1L)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            "admission_changed_before_execution");
                    return;
                }
                long startedAtNanos = System.nanoTime();
                var nextExecution = new AgentExecution(
                        action,
                        session.worldSessionId(),
                        startedAtNanos,
                        minecraft.player.position(),
                        minecraft.player.getYRot(),
                        minecraft.player.getXRot(),
                        minecraft.player,
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                        pending.prepared().analysis().mutationAims(),
                        reconciliationSignals.bindAndSnapshot(
                                        minecraft.level, session.worldSessionId())
                                .positionCorrectionRevision());
                agentActions.markRunning(action.actionId());
                agentExecution = nextExecution;
                pendingAgentAdmission = null;
                if (paused) {
                    pauseStartedAtNanos = startedAtNanos;
                }
            }
            if (paused) {
                inputRelease.releaseAll(minecraft);
                return;
            }
            if (!session.worldReady()
                    || !Objects.equals(agentExecution.worldSessionId, session.worldSessionId())) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true, "world_session_changed");
                return;
            }
            var control = arming.snapshot(session.worldSessionId());
            if (control.mode() != LocalArmingState.Mode.AGENT
                    && control.mode() != LocalArmingState.Mode.RECOVERING) {
                failAgentAction(AgentActionStore.FailureCode.USER_DISABLED, true, "local_control_locked");
                return;
            }

            long correctionRevision = reconciliationSignals.bindAndSnapshot(
                    minecraft.level, session.worldSessionId()).positionCorrectionRevision();
            if (correctionRevision > agentExecution.positionCorrectionRevision) {
                long previousCorrectionRevision = agentExecution.positionCorrectionRevision;
                agentExecution.positionCorrectionRevision = correctionRevision;
                agentExecution.lastPosition = minecraft.player.position();
                agentExecution.lastYaw = minecraft.player.getYRot();
                agentExecution.lastPitch = minecraft.player.getXRot();
                boolean repeated = repeatedPositionCorrection(
                        previousCorrectionRevision,
                        correctionRevision,
                        agentExecution.positionCorrections);
                agentExecution.positionCorrections++;
                if (repeated) {
                    failAgentAction(
                            AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                            true,
                            "repeated_position_correction");
                    return;
                }
                var correctionProgress = agentActions.get(action.actionId()).progress();
                agentActions.recordTick(action.actionId());
                if (agentExecution.primitive != null && agentExecution.occurrenceLimit != null) {
                    requestAgentReplan(
                            correctionProgress.ticks() + 1L, "server_position_correction");
                }
                return;
            }

            long now = System.nanoTime();
            var player = minecraft.player;
            recordAgentMotion(action.actionId(), player);
            if (agentActions.get(action.actionId()).progress().motionOverflowed()) {
                failAgentAction(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        "fixed_motion_contract");
                return;
            }
            var recovery = tickAgentRecovery(minecraft, session, now);
            switch (recovery.state()) {
                case RECOVERING, PAUSED -> {
                    agentActions.recordTick(action.actionId());
                    return;
                }
                case RECOVERED -> {
                    failAgentAction(
                            AgentActionStore.FailureCode.SAFETY_RECOVERED,
                            true,
                            recovery.reason().name().toLowerCase(Locale.ROOT));
                    return;
                }
                case EXHAUSTED -> {
                    failAgentAction(
                            AgentActionStore.FailureCode.RECOVERY_EXHAUSTED,
                            false,
                            recovery.reason().name().toLowerCase(Locale.ROOT));
                    return;
                }
                case STOPPED -> {
                    failAgentAction(
                            AgentActionStore.FailureCode.EMERGENCY_STOP,
                            true,
                            recovery.reason().name().toLowerCase(Locale.ROOT));
                    return;
                }
                case IDLE, REPLAN_REQUIRED -> { }
            }

            var usedBeforeTick = agentActions.get(action.actionId()).progress();
            boolean movementRejected = AgentInputState.global().consumeGoalMovementRejection();
            long durationLimit = Duration.ofMillis(
                    action.program().effectiveBudget().maxDurationMillis()).toNanos();
            if (activeElapsedNanos(agentExecution, now) >= durationLimit) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "duration");
                return;
            }
            if (usedBeforeTick.ticks() >= action.program().effectiveBudget().maxTicks()) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "ticks");
                return;
            }
            if (usedBeforeTick.motionOverflowed()
                    || usedBeforeTick.distanceTravelled()
                            > action.program().effectiveBudget().maxDistanceBlocks()
                    || usedBeforeTick.cameraDegrees()
                            > action.program().effectiveBudget().maxCameraDegrees()) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "motion");
                return;
            }
            agentActions.recordTick(action.actionId());
            long actionTick = usedBeforeTick.ticks() + 1L;
            if (agentExecution.replanning
                    && agentExecution.replanHeartbeatPending
                    && !movementRejected) {
                agentActions.setPhase(
                        action.actionId(), AgentActionStore.Phase.EXECUTING,
                        "replan_heartbeat_verified");
                agentExecution.replanning = false;
                agentExecution.replanHeartbeatPending = false;
                agentExecution.replanDeadlineTick = 0L;
            }
            if (movementRejected) {
                if (agentExecution.primitive != null && agentExecution.occurrenceLimit != null) {
                    requestAgentReplan(actionTick, "unverified_actual_movement");
                }
                return;
            }

            if (agentExecution.primitive == null
                    && !advanceAgentProgram(minecraft, usedBeforeTick)) {
                return;
            }
            if (agentExecution.occurrenceLimit == null
                    && !bindAgentPrimitive(
                            minecraft,
                            session,
                            action,
                            usedBeforeTick,
                            actionTick)) {
                return;
            }
            if (recovery.state() == MinecraftRecoveryGovernor.State.REPLAN_REQUIRED
                    || localSafety == LocalObservationProjector.CurrentSafety.REPLAN) {
                if (isAgentWait(agentExecution.primitive)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.PATH_BLOCKED,
                            true,
                            "local_safety_changed_during_wait");
                } else {
                    requestAgentReplan(actionTick, "local_safety_changed");
                }
                return;
            }
            if (isAgentWait(agentExecution.primitive)) {
                if (occurrenceBudgetExceeded(
                        agentActions.get(action.actionId()).progress(),
                        agentExecution)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_budget");
                    return;
                }
                boolean complete = agentExecution.primitive instanceof ActionDsl.WaitUntil wait
                        && cropMatureConditionSatisfied(
                                wait.condition(),
                                requireAgentMap(session),
                                agentObservationFrames.latestFrame());
                if (complete || agentExecution.primitive instanceof ActionDsl.WaitTicks
                        && --agentExecution.waitTicksRemaining == 0) {
                    agentActions.completeNode(action.actionId());
                    agentExecution.primitive = null;
                    advanceAgentProgram(
                            minecraft, agentActions.get(action.actionId()).progress());
                } else if (agentExecution.primitive instanceof ActionDsl.WaitUntil
                        && --agentExecution.waitTicksRemaining == 0) {
                    failAgentAction(
                            AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                            true,
                            "crop_mature_timeout");
                }
                return;
            }
            if (agentExecution.replanning
                    && replanDeadlineReached(actionTick, agentExecution.replanDeadlineTick)) {
                failAgentAction(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "replan_deadline_exhausted");
                return;
            }
            if (agentExecution.replanNotBeforeTick > actionTick) {
                return;
            }
            if (occurrenceBudgetExceeded(
                    agentActions.get(action.actionId()).progress(),
                    agentExecution)) {
                failAgentAction(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        "primitive_budget");
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.TillKnownBlock
                    || agentExecution.primitive instanceof ActionDsl.PlantKnownWheat
                    || agentExecution.primitive instanceof ActionDsl.HarvestKnownWheat
                    || agentExecution.primitive instanceof ActionDsl.OpenKnownFenceGate) {
                tickAgentBlockMutation(minecraft, session, action);
                return;
            }

            KnownTraversabilitySnapshot map = requireAgentMap(session);
            if (agentExecution.primitive instanceof ActionDsl.BreakKnownFace block
                    && agentExecution.breakAimComplete) {
                tickAgentBreak(minecraft, session, action, map, block, actionTick);
                return;
            }
            if (!agentExecution.primitiveExecutor.active()
                    && !beginAgentPrimitive(minecraft, action, map, usedBeforeTick)) {
                return;
            }
            if (activeElapsedNanos(agentExecution, System.nanoTime()) >= durationLimit) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "duration");
                return;
            }
            final MinecraftActionPrimitiveExecutor.TickResult result;
            try {
                result = agentExecution.primitiveExecutor.tick(
                        minecraft,
                        map,
                        LocalObservationVolume.global(),
                        remainingDistance(
                                usedBeforeTick,
                                action.program().effectiveBudget(),
                                agentExecution),
                        remainingCameraDegrees(
                                usedBeforeTick,
                                action.program().effectiveBudget(),
                                agentExecution),
                        actionTick,
                        () -> activeElapsedNanos(agentExecution, System.nanoTime())
                                < durationLimit);
            } finally {
                recordAgentMotion(action.actionId(), player);
            }
            AgentInputState.global().capMovementValidity(actionMovementDeadline(
                    agentExecution, durationLimit, System.nanoTime()));
            if (activeElapsedNanos(agentExecution, System.nanoTime()) >= durationLimit) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "duration");
                return;
            }
            var usedAfterTick = agentActions.get(action.actionId()).progress();
            if (motionBudgetExceededAfterPrimitive(
                    usedAfterTick,
                    action.program().effectiveBudget(),
                    agentExecution.primitive,
                    result.status())
                    || occurrenceBudgetExceededAfterPrimitive(
                            usedAfterTick,
                            agentExecution,
                            result.status())) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "motion");
                return;
            }
            switch (result.status()) {
                case RUNNING -> {
                    if (shouldVerifyReplanHeartbeat(agentExecution.replanning, result)) {
                        agentExecution.replanHeartbeatPending = true;
                    }
                }
                case SUCCEEDED -> {
                    if (agentExecution.primitive instanceof ActionDsl.BreakKnownFace) {
                        agentExecution.breakAimComplete = true;
                        agentExecution.replanning = false;
                        agentExecution.replanNotBeforeTick = 0L;
                        agentExecution.replanDeadlineTick = 0L;
                        return;
                    }
                    closeAgentPrimitiveExecutor();
                    agentActions.completeNode(action.actionId());
                    agentExecution.primitive = null;
                    agentExecution.replanning = false;
                    agentExecution.replanNotBeforeTick = 0L;
                    agentExecution.replanDeadlineTick = 0L;
                    advanceAgentProgram(minecraft, usedAfterTick);
                }
                case REPLAN_REQUIRED -> requestAgentReplan(
                        actionTick, result.reason().name().toLowerCase(Locale.ROOT));
                case FAILED -> failAgentAction(
                        result.reason() == MinecraftActionPrimitiveExecutor.Reason.WORLD_UNAVAILABLE
                                        || result.reason()
                                        == MinecraftActionPrimitiveExecutor.Reason.WORLD_BOUNDARY_CHANGED
                                ? AgentActionStore.FailureCode.WORLD_CHANGED
                                : AgentActionStore.FailureCode.INTERNAL_ERROR,
                        false,
                        result.reason().name().toLowerCase(Locale.ROOT));
            }
        } catch (AgentPrimitivePlanner.PlanningException failure) {
            failAgentAction(
                    AgentActionStore.FailureCode.PATH_BLOCKED,
                    true,
                    failure.code().name().toLowerCase(Locale.ROOT));
        } catch (ActionDslException failure) {
            var code = failure.code() == ActionDslException.Code.PREDICATE_UNAVAILABLE
                    ? AgentActionStore.FailureCode.PREDICATE_UNAVAILABLE
                    : AgentActionStore.FailureCode.INTERNAL_ERROR;
            failAgentAction(code, failure.code() == ActionDslException.Code.PREDICATE_UNAVAILABLE,
                    failure.code().name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP Action DSL execution failed", failure);
            failAgentAction(AgentActionStore.FailureCode.INTERNAL_ERROR, false, "runtime_exception");
        }
    }

    /** Returns false when the action became terminal while advancing control nodes. */
    private boolean advanceAgentProgram(
            Minecraft minecraft, AgentActionStore.Progress occurrenceBaseline) {
        ActionProgramCursor.Advance advance = agentExecution.cursor.next(policySnapshot(minecraft));
        for (String controlNode : advance.completedControlNodeIds()) {
            agentActions.beginNode(agentExecution.actionId, controlNode);
            agentActions.completeNode(agentExecution.actionId);
        }
        if (advance.finished()) {
            try {
                agentActions.succeed(agentExecution.actionId);
            } finally {
                finishAgentControlReady(minecraft, "action_completed");
            }
            return false;
        }
        agentExecution.primitive = advance.primitive();
        agentExecution.occurrenceBaseline = Objects.requireNonNull(
                occurrenceBaseline, "occurrenceBaseline");
        agentExecution.occurrenceLimit = null;
        agentExecution.retainOccurrenceBaseline = false;
        agentExecution.primitivePlanning = false;
        agentExecution.primitivePlanDeadlineTick = Math.addExact(
                occurrenceBaseline.ticks(), primitiveReobservationTicks(advance.primitive()));
        agentExecution.mutationAims.clear();
        agentActions.beginNode(agentExecution.actionId, advance.primitive().id());
        if (advance.primitive() instanceof ActionDsl.WaitTicks wait) {
            agentExecution.waitTicksRemaining = wait.ticks();
            agentExecution.occurrenceLimit = agentExecution.program.primitiveCostBounds()
                    .get(advance.primitive().id());
        } else if (advance.primitive() instanceof ActionDsl.WaitUntil wait) {
            agentExecution.waitTicksRemaining = wait.maxTicks();
            agentExecution.occurrenceLimit = agentExecution.program.primitiveCostBounds()
                    .get(advance.primitive().id());
        }
        if (isAgentWait(advance.primitive()) && agentExecution.occurrenceLimit == null) {
            throw new IllegalStateException("Compiled wait cost bound is unavailable");
        }
        if (isAgentWait(advance.primitive())
                && !fitsRemainingBudget(
                        occurrenceBaseline,
                        agentExecution.program.effectiveBudget(),
                        agentExecution.occurrenceLimit,
                        activeElapsedNanos(agentExecution, System.nanoTime()))) {
            failAgentAction(
                    AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                    false,
                    "wait_remaining_budget");
            return false;
        }
        return true;
    }

    private boolean bindAgentPrimitive(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            AgentActionStore.Progress progress,
            long actionTick) {
        try {
            var player = Objects.requireNonNull(minecraft.player, "player");
            var analysis = analyzePrimitive(
                    action.program().request().program(),
                    agentExecution.primitive,
                    requireAgentMap(session),
                    playerPose(player, session.dimension()),
                    agentObservationFrames.latestFrame(),
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                    () -> true);
            ActionDslCompiler.Cost cost = analysis.worstCase(agentExecution.primitive)
                    .orElseThrow(() -> new IllegalStateException(
                            "JIT primitive analysis did not produce a cost"));
            if (!fitsRemainingBudget(
                    progress,
                    action.program().effectiveBudget(),
                    cost,
                    activeElapsedNanos(agentExecution, System.nanoTime()))) {
                failAgentAction(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        "jit_primitive_budget");
                return false;
            }
            if (agentExecution.retainOccurrenceBaseline) {
                agentExecution.occurrenceLimit = occurrenceCostIncludingConsumed(
                        progress, agentExecution.occurrenceBaseline, cost);
                agentExecution.retainOccurrenceBaseline = false;
            } else {
                agentExecution.occurrenceBaseline = progress;
                agentExecution.occurrenceLimit = cost;
            }
            agentExecution.mutationAims.putAll(analysis.mutationAims());
            agentExecution.primitivePlanDeadlineTick = 0L;
            if (agentExecution.primitivePlanning) {
                agentActions.setPhase(
                        action.actionId(), AgentActionStore.Phase.EXECUTING, "jit_primitive_bound");
                agentExecution.primitivePlanning = false;
            }
            return true;
        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
            inputRelease.releaseAll(minecraft);
            if (replanDeadlineReached(actionTick, agentExecution.primitivePlanDeadlineTick)) {
                failAgentAction(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "jit_" + unavailable.code().name().toLowerCase(Locale.ROOT));
                return false;
            }
            if (!agentExecution.primitivePlanning) {
                agentActions.setPhase(
                        action.actionId(),
                        AgentActionStore.Phase.REPLANNING,
                        unavailable.code().name().toLowerCase(Locale.ROOT));
                agentExecution.primitivePlanning = true;
            }
            return false;
        }
    }

    static long primitiveReobservationTicks(ActionDsl.Node primitive) {
        return requiresWorldPlanning(primitive)
                ? AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS : 0L;
    }

    static boolean cropMatureConditionSatisfied(
            ActionDsl.CropMatureCondition condition,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame) {
        var target = condition.target();
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(target.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .anyMatch(surface -> surface.worldRevision() == map.worldRevision()
                        && surface.position().x() == target.x()
                        && surface.position().y() == target.y()
                        && surface.position().z() == target.z()
                        && surface.block().value().equals("minecraft:wheat")
                        && Boolean.TRUE.equals(surface.cropMature()));
    }

    private static boolean isAgentWait(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.WaitTicks || primitive instanceof ActionDsl.WaitUntil;
    }

    private boolean beginAgentPrimitive(
            Minecraft minecraft,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        ActionDslCompiler.Cost cost;
        try {
            if (agentExecution.primitive instanceof ActionDsl.NavigateToKnown navigate) {
                RoutePlan route = AgentPrimitivePlanner.requireRoute(
                        map,
                        agentPathfinder,
                        playerCell(player, map.dimension()),
                        navigate.target());
                cost = AgentPrimitivePlanner.navigationCost(
                        route, playerPose(player, map.dimension()));
                if (agentExecution.replanning) {
                    if (!fits(0L, cost.durationMillis(),
                                    agentExecution.occurrenceLimit.durationMillis())
                            || !fits(0L, cost.ticks(),
                                    agentExecution.occurrenceLimit.ticks())) {
                        failAgentAction(
                                AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                                false,
                                "primitive_replanned_route");
                        return false;
                    }
                    cost = AgentPrimitivePlanner.navigationReplanCost(route, cost);
                }
                if (!fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        activeElapsedNanos(agentExecution, System.nanoTime()))) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "replanned_route");
                    return false;
                }
                if (!fitsOccurrenceRemaining(
                        progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_replanned_route");
                    return false;
                }
                agentExecution.primitiveExecutor.beginNavigate(route, navigate.tolerance());
            } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition face) {
                var target = AgentPrimitivePlanner.requireKnownFaceTarget(
                        map, agentObservationFrames.latestFrame(), face.target());
                cost = AgentPrimitivePlanner.faceCost(
                        playerPose(player, map.dimension()),
                        face.target(),
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                if (!fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        activeElapsedNanos(agentExecution, System.nanoTime()))) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "face_target");
                    return false;
                }
                if (!fitsOccurrenceRemaining(
                        progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_face_target");
                    return false;
                }
                agentExecution.primitiveExecutor.beginFace(target, cost.ticks());
            } else if (agentExecution.primitive instanceof ActionDsl.BreakKnownFace block) {
                AgentPrimitivePlanner.requireKnownBreakSurface(
                        map, agentObservationFrames.latestFrame(), block);
                cost = AgentPrimitivePlanner.breakCost(
                        playerPose(player, map.dimension()),
                        block,
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                long aimTicks = breakAimTicks(cost);
                cost = breakExecutionCost(cost, agentExecution.replanning);
                if (!fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        activeElapsedNanos(agentExecution, System.nanoTime()))
                        || !fitsOccurrenceRemaining(
                                progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "break_known_face");
                    return false;
                }
                int remainingBreaks = Math.toIntExact(Math.max(
                        1L,
                        action.program().worstCaseCost().blocksBroken()
                                - progressBeforeTick.blocksBroken()));
                int toolSlot = findDurableHotbarTool(
                        player, block.toolItem(), remainingBreaks);
                if (toolSlot < 0 || !inventoryCanReceiveKnownLogs(
                        player, action.program())) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            toolSlot < 0 ? "required_axe_unavailable" : "inventory_full");
                    return false;
                }
                player.getInventory().setSelectedSlot(toolSlot);
                agentExecution.agentSelectedSlot = toolSlot;
                agentExecution.primitiveExecutor.beginFace(
                        MinecraftActionPrimitiveExecutor.KnownFaceTarget.forBlockFace(
                                map.worldSessionId(), map.worldRevision(),
                                block.target(), block.face()),
                        aimTicks);
            } else {
                failAgentAction(
                        AgentActionStore.FailureCode.INTERNAL_ERROR, false, "primitive_unavailable");
                return false;
            }
        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
            long actionTick = progressBeforeTick.ticks() + 1L;
            if (!agentExecution.replanning) {
                requestAgentReplan(actionTick, unavailable.code().name().toLowerCase(Locale.ROOT));
                return false;
            }
            if (!replanDeadlineReached(actionTick, agentExecution.replanDeadlineTick)) {
                return false;
            }
            throw unavailable;
        }
        agentExecution.replanNotBeforeTick = 0L;
        return true;
    }

    private void tickAgentBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            ActionDsl.BreakKnownFace block,
            long actionTick) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        if (agentExecution.blockBreakAttempt == null) {
            if (!breakTargetStateMatches(minecraft, block)) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "break_target_changed");
                return;
            }
            if (!AgentPrimitivePlanner.knownSurface(
                            map,
                            agentObservationFrames.latestFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    block.target(), block.face(), block.expectedBlock()))
                    || !breakSourceControlled(minecraft, block)) {
                requestAgentReplan(actionTick, "break_target_reobservation");
                return;
            }
            try {
                var target = new BlockTarget(
                        block.target().dimension(),
                        block.target().x(),
                        block.target().y(),
                        block.target().z());
                var expected = stationaryBreakPort.captureExpectedSource(
                        target, Set.of(block.expectedBlock()));
                var request = new StationaryBreakRequest(
                        target,
                        expected,
                        new StationaryBreakGoal(block.expectedBlock(), 1),
                        Math.addExact(
                                session.clientTick(),
                                AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND),
                        StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS,
                        1);
                agentExecution.blockBreakAttempt = new KnownBlockBreakAttempt(
                        stationaryBreakPort, request, session.clientTick());
                return;
            } catch (SafeBreakSourcePolicy.UnsafeBreakSourceException
                    | IllegalArgumentException changed) {
                requestAgentReplan(actionTick, "break_precondition_changed");
                return;
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error("MCMCP known-face break could not start", failure);
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "break_start_failed");
                return;
            }
        }

        final KnownBlockBreakAttempt.TickResult result;
        try {
            result = agentExecution.blockBreakAttempt.tick(
                    session.clientTick(), breakSourceControlled(minecraft, block));
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP known-face break confirmation failed", failure);
            failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "break_confirmation_failed");
            return;
        }
        switch (result) {
            case RUNNING -> { }
            case SERVER_DENIED_OR_DESYNC -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "break_not_server_confirmed");
            case SUCCEEDED -> {
                agentExecution.blockBreakAttempt = null;
                agentActions.recordBlockBreak(action.actionId());
                agentActions.completeNode(action.actionId());
                agentExecution.primitive = null;
                agentExecution.breakAimComplete = false;
                agentExecution.replanning = false;
                agentExecution.replanNotBeforeTick = 0L;
                agentExecution.replanDeadlineTick = 0L;
                advanceAgentProgram(
                        minecraft, agentActions.get(action.actionId()).progress());
            }
        }
    }

    private void tickAgentBlockMutation(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.blockMutationAttempt == null) {
            SemanticActionRequest request = blockMutationRequest(
                    agentExecution.primitive,
                    agentExecution.mutationAims.get(agentExecution.primitive.id()));
            long deadline = Math.addExact(
                    session.clientTick(), AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND);
            agentExecution.blockMutationAttempt = new KnownBlockMutationAttempt(
                    semanticActionPort, request, session.clientTick(), deadline);
        }
        KnownBlockMutationAttempt.TickResult result =
                agentExecution.blockMutationAttempt.tick(session.clientTick());
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> {
                if (retryableMutationAimFailure(result.evidence())) {
                    retryAgentMutationAim(minecraft, action, result.evidence());
                } else {
                    failAgentAction(
                            AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                            true,
                            result.evidence());
                }
            }
            case SUCCEEDED -> {
                agentExecution.blockMutationAttempt = null;
                if (agentExecution.primitive instanceof ActionDsl.TillKnownBlock
                        || agentExecution.primitive instanceof ActionDsl.OpenKnownFenceGate) {
                    agentActions.recordInteraction(action.actionId());
                } else if (agentExecution.primitive instanceof ActionDsl.PlantKnownWheat) {
                    agentActions.recordBlockPlace(action.actionId());
                } else {
                    agentActions.recordBlockBreak(action.actionId());
                }
                agentActions.completeNode(action.actionId());
                agentExecution.primitive = null;
                agentExecution.replanning = false;
                agentExecution.replanNotBeforeTick = 0L;
                agentExecution.replanDeadlineTick = 0L;
                advanceAgentProgram(
                        minecraft, agentActions.get(action.actionId()).progress());
            }
        }
    }

    private void retryAgentMutationAim(
            Minecraft minecraft, AgentActionStore.Active action, String evidence) {
        agentExecution.blockMutationAttempt.close();
        agentExecution.blockMutationAttempt = null;
        agentExecution.occurrenceLimit = null;
        agentExecution.retainOccurrenceBaseline = true;
        agentExecution.mutationAims.clear();
        agentExecution.primitivePlanDeadlineTick = Math.addExact(
                agentActions.get(action.actionId()).progress().ticks(),
                AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        agentExecution.replanning = false;
        agentExecution.replanNotBeforeTick = 0L;
        agentExecution.replanDeadlineTick = 0L;
        agentExecution.primitivePlanning = true;
        inputRelease.releaseAll(minecraft);
        agentActions.setPhase(action.actionId(), AgentActionStore.Phase.REPLANNING, evidence);
    }

    static boolean retryableMutationAimFailure(String evidence) {
        return "aim_raycast_unavailable".equals(evidence);
    }

    static SemanticActionRequest blockMutationRequest(
            ActionDsl.Node node, AgentPrimitivePlanner.MutationAim plannedAim) {
        Objects.requireNonNull(plannedAim, "plannedAim");
        ActionDsl.Position position = switch (node) {
            case ActionDsl.TillKnownBlock value -> value.target();
            case ActionDsl.PlantKnownWheat value -> value.target();
            case ActionDsl.HarvestKnownWheat value -> value.target();
            case ActionDsl.OpenKnownFenceGate value -> value.target();
            default -> throw new IllegalArgumentException("node is not a known block mutation");
        };
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        boolean breaking = node instanceof ActionDsl.HarvestKnownWheat;
        var bounds = new ActionBounds(
                position.dimension(), target, target, 0, 5, breaking);
        var aim = Optional.of(blockAimWitness(plannedAim));
        return switch (node) {
            case ActionDsl.TillKnownBlock till -> new UseItemOnBlockRequest(
                    target,
                    new BlockStateFingerprint(till.expectedBlock(), Map.of()),
                    till.hoeItem(),
                    new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0")),
                    bounds,
                    aim);
            case ActionDsl.PlantKnownWheat plant -> new PlaceBlockRequest(
                    target,
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    plant.seedItem(),
                    new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")),
                    bounds,
                    aim);
            case ActionDsl.HarvestKnownWheat ignored -> new BreakBlockRequest(
                    target,
                    new BlockStateFingerprint("minecraft:wheat", Map.of("age", "7")),
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    bounds,
                    aim);
            case ActionDsl.OpenKnownFenceGate ignored -> new InteractBlockRequest(
                    target,
                    new BlockStateFingerprint(
                            "minecraft:oak_fence_gate", Map.of("open", "false")),
                    new BlockStateFingerprint(
                            "minecraft:oak_fence_gate", Map.of("open", "true")),
                    bounds,
                    aim);
            default -> throw new IllegalArgumentException("node is not a known block mutation");
        };
    }

    private static BlockAimWitness blockAimWitness(AgentPrimitivePlanner.MutationAim aim) {
        var block = aim.block();
        return new BlockAimWitness(
                new BlockTarget(block.dimension(), block.x(), block.y(), block.z()),
                BlockAimWitness.Face.valueOf(aim.face().name()),
                aim.point().x,
                aim.point().y,
                aim.point().z);
    }

    private void requestAgentReplan(long actionTick, String reason) {
        closeAgentPrimitiveExecutor();
        agentExecution.breakAimComplete = false;
        agentExecution.replanHeartbeatPending = false;
        agentExecution.replanNotBeforeTick = actionTick + 1L;
        if (agentExecution.replanning) return;
        agentActions.setPhase(
                agentExecution.actionId, AgentActionStore.Phase.REPLANNING, reason);
        agentExecution.replanning = true;
        agentExecution.replanDeadlineTick = agentReplanDeadlineTick(
                agentExecution.primitive,
                actionTick,
                agentExecution.occurrenceBaseline.ticks(),
                agentExecution.occurrenceLimit.ticks());
    }

    static long agentReplanWindowTicks(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.BreakKnownFace
                ? AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS
                : 20L;
    }

    static long agentReplanDeadlineTick(
            ActionDsl.Node primitive,
            long actionTick,
            long occurrenceStartTick,
            long occurrenceTickLimit) {
        long observationDeadline = Math.addExact(actionTick, agentReplanWindowTicks(primitive));
        if (!(primitive instanceof ActionDsl.NavigateToKnown)) return observationDeadline;
        long admittedNavigationDeadline = Math.addExact(
                occurrenceStartTick, Math.addExact(occurrenceTickLimit, 1L));
        return Math.max(observationDeadline, admittedNavigationDeadline);
    }

    private MinecraftRecoveryGovernor.TickResult tickAgentRecovery(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long nowNanos) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var map = requireAgentMap(session);
        var local = Objects.requireNonNull(
                latestLocalObservation, "local safety observation");
        if (local.worldRevision() != map.worldRevision()) {
            throw new IllegalStateException("local safety observation crossed a world revision");
        }
        if (recoveryGovernor == null) {
            recoveryGovernor = new MinecraftRecoveryGovernor(minecraft);
        }
        long activeTick = agentActions.get(agentExecution.actionId).progress().ticks() + 1L;
        var evidence = recoveryEvidence(player, session, map, local, activeTick);
        return recoveryGovernor.tick(
                evidence,
                recoveryCandidates(
                        minecraft, player, map, evidence, recoveryGovernor.recovering()),
                MinecraftRecoveryGovernor.StopSignal.NONE,
                () -> preemptAgentGoalForRecovery(session),
                nowNanos);
    }

    private void preemptAgentGoalForRecovery(WorldSessionTracker.Snapshot session) {
        closeAgentPrimitiveExecutor();
        if (agentExecution.goalPreempted) return;
        if (!arming.beginRecovery(session.worldSessionId())) {
            throw new IllegalStateException("recovery could not acquire the local control lease");
        }
        agentActions.setPhase(
                agentExecution.actionId,
                AgentActionStore.Phase.RECOVERING,
                "goal_preempted_for_safety");
        agentExecution.goalPreempted = true;
    }

    private MinecraftRecoveryGovernor.Evidence recoveryEvidence(
            net.minecraft.client.player.LocalPlayer player,
            WorldSessionTracker.Snapshot session,
            KnownTraversabilitySnapshot map,
            LocalObservationVolume.Snapshot local,
            long activeTick) {
        var current = local.current();
        var damageSource = player.getLastDamageSource();
        MinecraftRecoveryGovernor.DamageKind damageKind;
        if (damageSource == null) {
            damageKind = MinecraftRecoveryGovernor.DamageKind.NONE;
        } else if (damageSource.getEntity() != null || damageSource.getDirectEntity() != null) {
            damageKind = MinecraftRecoveryGovernor.DamageKind.ATTACK;
        } else if (damageSource.is(DamageTypeTags.IS_FIRE)) {
            damageKind = MinecraftRecoveryGovernor.DamageKind.FIRE;
        } else if (damageSource.is(DamageTypeTags.IS_DROWNING)) {
            damageKind = MinecraftRecoveryGovernor.DamageKind.DROWNING;
        } else if (damageSource.is(DamageTypeTags.IS_FALL)) {
            damageKind = MinecraftRecoveryGovernor.DamageKind.FALL;
        } else {
            damageKind = MinecraftRecoveryGovernor.DamageKind.OTHER;
        }
        var landingEvidence = LocalObservationVolume.global()
                .directLanding(player, map.worldRevision());
        MinecraftRecoveryGovernor.Landing landing = current.fluid()
                == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA
                ? MinecraftRecoveryGovernor.Landing.KNOWN_LAVA
                : switch (landingEvidence) {
                    case SAFE -> MinecraftRecoveryGovernor.Landing.KNOWN_SAFE;
                    case LAVA -> MinecraftRecoveryGovernor.Landing.KNOWN_LAVA;
                    case VOID -> MinecraftRecoveryGovernor.Landing.KNOWN_VOID;
                    case UNKNOWN -> MinecraftRecoveryGovernor.Landing.UNKNOWN;
                };
        double descentSinceGround = recoveryDescent.current(
                player, player.level(), session.worldSessionId());
        RecoveryHazards hazards = recoveryHazards(
                current.fluid(),
                current.hazard(),
                player.isInLava(),
                player.onGround(),
                player.getDeltaMovement().y,
                descentSinceGround);
        return new MinecraftRecoveryGovernor.Evidence(
                activeTick,
                session.worldSessionId(),
                map.dimension(),
                map.worldRevision(),
                new MinecraftRecoveryGovernor.Position(
                        player.getX(), player.getY(), player.getZ()),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getAirSupply(),
                player.isUnderWater(),
                effectDuration(player, MobEffects.WATER_BREATHING),
                player.isOnFire(),
                Math.max(0, player.getRemainingFireTicks()),
                effectDuration(player, MobEffects.FIRE_RESISTANCE),
                hazards.inLava(),
                current.suffocation(),
                hazards.onGround(),
                hazards.verticalVelocity(),
                hazards.descentSinceGround(),
                landing,
                player.hurtTime > 0 ? activeTick : -1L,
                damageKind,
                false,
                current.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.WATER,
                current.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FIRE_DAMAGE
                        || current.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.CONTACT_DAMAGE
                        || current.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FREEZING);
    }

    static RecoveryHazards recoveryHazards(
            dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid fluid,
            dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard hazard,
            boolean playerInLava,
            boolean onGround,
            double verticalVelocity,
            double descentSinceGround) {
        boolean observedDangerousFall = hazard
                == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FALL;
        return new RecoveryHazards(
                playerInLava
                        || fluid == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA,
                onGround && !observedDangerousFall,
                observedDangerousFall ? Math.min(-0.081D, verticalVelocity) : verticalVelocity,
                observedDangerousFall
                        ? Math.max(Math.nextUp(3.0D), descentSinceGround)
                        : Math.max(0.0D, descentSinceGround));
    }

    record RecoveryHazards(
            boolean inLava,
            boolean onGround,
            double verticalVelocity,
            double descentSinceGround) {
    }

    private void trackRecoveryDescent(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.player == null || minecraft.level == null) {
            recoveryDescent.reset();
            return;
        }
        long correctionRevision = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId()).positionCorrectionRevision();
        recoveryDescent.update(
                minecraft.player,
                minecraft.level,
                session.worldSessionId(),
                minecraft.player.getY(),
                minecraft.player.onGround(),
                minecraft.player.isInWater() && !minecraft.player.isInLava(),
                correctionRevision);
    }

    static final class RecoveryDescentTracker {
        private Object playerIdentity;
        private Object levelIdentity;
        private UUID worldSessionId;
        private double lastY;
        private double descent;
        private long correctionRevision;

        double update(
                Object player,
                Object level,
                UUID sessionId,
                double y,
                boolean onGround,
                boolean safeWater,
                long currentCorrectionRevision) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(sessionId, "sessionId");
            if (!Double.isFinite(y) || currentCorrectionRevision < 0L) {
                throw new IllegalArgumentException("descent evidence must be finite");
            }
            if (playerIdentity != player
                    || levelIdentity != level
                    || !sessionId.equals(worldSessionId)) {
                playerIdentity = player;
                levelIdentity = level;
                worldSessionId = sessionId;
                lastY = y;
                descent = 0.0D;
                correctionRevision = currentCorrectionRevision;
            } else {
                if (currentCorrectionRevision == correctionRevision) {
                    descent += Math.max(0.0D, lastY - y);
                }
                lastY = y;
                correctionRevision = currentCorrectionRevision;
            }
            if (onGround || safeWater) {
                descent = 0.0D;
            }
            return descent;
        }

        double current(Object player, Object level, UUID sessionId) {
            return playerIdentity == player
                            && levelIdentity == level
                            && Objects.equals(worldSessionId, sessionId)
                    ? descent : 0.0D;
        }

        void reset() {
            playerIdentity = null;
            levelIdentity = null;
            worldSessionId = null;
            lastY = 0.0D;
            descent = 0.0D;
            correctionRevision = 0L;
        }
    }

    record PredicateRequirements(
            Set<ActionDsl.NumericField> numericFields,
            Set<ActionDsl.BooleanField> booleanFields,
            Set<String> inventoryItems,
            Set<String> statusEffects) {
        PredicateRequirements {
            numericFields = Set.copyOf(Objects.requireNonNull(numericFields, "numericFields"));
            booleanFields = Set.copyOf(Objects.requireNonNull(booleanFields, "booleanFields"));
            inventoryItems = Set.copyOf(Objects.requireNonNull(inventoryItems, "inventoryItems"));
            statusEffects = Set.copyOf(Objects.requireNonNull(statusEffects, "statusEffects"));
        }
    }

    private record AdmissionPolicySnapshot(
            Map<ActionDsl.NumericField, Double> numericValues,
            Map<ActionDsl.BooleanField, Boolean> booleanValues,
            Map<String, Integer> inventoryCounts,
            Map<String, Boolean> statusEffectValues) implements PolicySnapshot {
        private AdmissionPolicySnapshot {
            numericValues = Map.copyOf(Objects.requireNonNull(numericValues, "numericValues"));
            booleanValues = Map.copyOf(Objects.requireNonNull(booleanValues, "booleanValues"));
            inventoryCounts = Map.copyOf(
                    Objects.requireNonNull(inventoryCounts, "inventoryCounts"));
            statusEffectValues = Map.copyOf(
                    Objects.requireNonNull(statusEffectValues, "statusEffectValues"));
        }

        static AdmissionPolicySnapshot capture(
                PolicySnapshot source, PredicateRequirements requirements) {
            var numeric = new EnumMap<ActionDsl.NumericField, Double>(ActionDsl.NumericField.class);
            for (var field : requirements.numericFields()) {
                var value = source.numeric(field);
                if (value.isPresent()) numeric.put(field, value.getAsDouble());
            }
            var bools = new EnumMap<ActionDsl.BooleanField, Boolean>(ActionDsl.BooleanField.class);
            for (var field : requirements.booleanFields()) {
                source.bool(field).ifPresent(value -> bools.put(field, value));
            }
            var items = new LinkedHashMap<String, Integer>();
            for (var item : requirements.inventoryItems()) {
                var value = source.inventoryCount(item);
                if (value.isPresent()) items.put(item, value.getAsInt());
            }
            var effects = new LinkedHashMap<String, Boolean>();
            for (var effect : requirements.statusEffects()) {
                source.hasStatusEffect(effect).ifPresent(value -> effects.put(effect, value));
            }
            return new AdmissionPolicySnapshot(numeric, bools, items, effects);
        }

        @Override
        public OptionalDouble numeric(ActionDsl.NumericField field) {
            Double value = numericValues.get(field);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }

        @Override
        public Optional<Boolean> bool(ActionDsl.BooleanField field) {
            return Optional.ofNullable(booleanValues.get(field));
        }

        @Override
        public OptionalInt inventoryCount(String item) {
            Integer value = inventoryCounts.get(item);
            return value == null ? OptionalInt.empty() : OptionalInt.of(value);
        }

        @Override
        public Optional<Boolean> hasStatusEffect(String effect) {
            return Optional.ofNullable(statusEffectValues.get(effect));
        }
    }

    private record AgentAdmissionSnapshot(
            WorldSessionTracker.Snapshot session,
            LocalArmingState.Snapshot control,
            KnownTraversabilitySnapshot map,
            AgentPrimitivePlanner.Pose pose,
            Optional<ObservationFrame> frame,
            LocalObservationProjector.CurrentSafety localSafety,
            PredicateRequirements predicateRequirements,
            AdmissionPolicySnapshot predicateSnapshot,
            float cameraDegreesPerTick,
            boolean multiplayerServer,
            boolean multiplayerAllowed,
            long positionCorrectionRevision) {
        private AgentAdmissionSnapshot {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(map, "map");
            Objects.requireNonNull(pose, "pose");
            frame = Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(localSafety, "localSafety");
            Objects.requireNonNull(predicateRequirements, "predicateRequirements");
            Objects.requireNonNull(predicateSnapshot, "predicateSnapshot");
            if (positionCorrectionRevision < 0L) {
                throw new IllegalArgumentException(
                        "positionCorrectionRevision must be non-negative");
            }
        }
    }

    private record PreparedAgentAction(
            AgentAdmissionSnapshot snapshot,
            ActionDslCompiler.CompiledProgram program,
            AgentPrimitivePlanner.Analysis analysis,
            Optional<ActionDsl.Node> initialPrimitive) {
        private PreparedAgentAction {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(analysis, "analysis");
            Objects.requireNonNull(initialPrimitive, "initialPrimitive");
        }
    }

    private record PendingAgentAdmission(UUID actionId, PreparedAgentAction prepared) {
        private PendingAgentAdmission {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(prepared, "prepared");
        }
    }

    private static int effectDuration(
            net.minecraft.client.player.LocalPlayer player,
            Holder<net.minecraft.world.effect.MobEffect> effect) {
        var instance = player.getEffect(effect);
        return instance == null ? 0
                : instance.isInfiniteDuration()
                ? Integer.MAX_VALUE
                : Math.max(0, instance.getDuration());
    }

    private static List<MinecraftRecoveryGovernor.Candidate> recoveryCandidates(
            Minecraft minecraft,
            net.minecraft.client.player.LocalPlayer player,
            KnownTraversabilitySnapshot map,
            MinecraftRecoveryGovernor.Evidence evidence,
            boolean continuingRecovery) {
        var candidates = new ArrayList<MinecraftRecoveryGovernor.Candidate>();
        var threat = recoveryThreat(player);
        var currentHazard = LocalObservationVolume.global().latestFor(player)
                .map(snapshot -> snapshot.current().hazard())
                .orElse(dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.UNKNOWN);
        var currentCenter = player.getBoundingBox().getCenter();
        boolean lavaRecovery = evidence.inLava()
                || continuingRecovery
                        && !evidence.onGround()
                        && evidence.landing() == MinecraftRecoveryGovernor.Landing.KNOWN_LAVA;
        boolean continuingLavaEscape = lavaRecovery && !evidence.inLava();
        for (var option : LocalObservationVolume.global()
                .recoveryOptions(player, map.worldRevision())) {
            var endpoint = option.endpoint();
            var target = new net.minecraft.world.phys.Vec3(
                    option.target().x(), option.target().y(), option.target().z());
            var movement = EnumSet.noneOf(dev.aod.mcmcp.routine.MovementInputLease.MovementKey.class);
            movement.addAll(MinecraftActionPrimitiveExecutor.steering(
                    player.getX(),
                    player.getZ(),
                    player.getYRot(),
                    target.x,
                    target.z,
                    0.05D));
            if (requiresRecoveryJump(currentCenter.y, target)) {
                movement.add(dev.aod.mcmcp.routine.MovementInputLease.MovementKey.JUMP);
            }
            if (movement.isEmpty()) continue;
            boolean dryStable = recoveryDryStable(endpoint);
            boolean waterStable = endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.clearance()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                    && endpoint.fluid()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.WATER
                    && !endpoint.suffocation()
                    && endpoint.hazard()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE;
            boolean avoidsNewDamage = LocalObservationVolume.avoidsNewDamageHazard(
                    currentHazard, option.path().hazard(), endpoint.hazard());
            if (evidence.suffocating() && (dryStable || waterStable)) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("free", target),
                        MinecraftRecoveryGovernor.CandidateKind.BACK_TO_FREE_AABB,
                        AgentInputState.RecoveryMode.ESCAPE_SUFFOCATION,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if ((currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FIRE_DAMAGE
                    || currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.CONTACT_DAMAGE
                    || currentHazard
                            == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.FREEZING)
                    && dryStable) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("surface-exit", target),
                        MinecraftRecoveryGovernor.CandidateKind.RETREAT_TO_KNOWN_SAFE,
                        AgentInputState.RecoveryMode.EXIT_DAMAGE_SURFACE,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (lavaRecovery && avoidsNewDamage && (dryStable || waterStable)
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("lava-exit", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        continuingLavaEscape
                                ? AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE
                                : AgentInputState.RecoveryMode.EXIT_LAVA,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (lavaRecovery
                    && !continuingLavaEscape
                    && target.y > currentCenter.y
                    && endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.clearance()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                    && endpoint.fluid()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA
                    && !endpoint.suffocation()) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("lava-progress", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        AgentInputState.RecoveryMode.EXIT_LAVA,
                        target,
                        null,
                        movement,
                        false,
                        false);
            }
            if (evidence.underwater()
                    && endpoint.loaded()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.LAVA
                    && endpoint.fluid()
                    != dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.UNKNOWN
                    && !endpoint.suffocation()
                    && endpoint.hazard()
                    == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE) {
                boolean reachesAir = endpoint.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE;
                if (reachesAir || target.y > currentCenter.y) {
                    addRecoveryCandidate(
                            candidates,
                            player,
                            map,
                            recoveryCandidateId("air", target),
                            MinecraftRecoveryGovernor.CandidateKind.REACH_BREATHING_SPACE,
                            AgentInputState.RecoveryMode.REACH_BREATHING_SPACE,
                            target,
                            null,
                            movement,
                            reachesAir,
                            reachesAir);
                }
            }
            if (evidence.onFire() && waterStable && avoidsNewDamage) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("water", target),
                        MinecraftRecoveryGovernor.CandidateKind.EXIT_HAZARDOUS_FLUID,
                        AgentInputState.RecoveryMode.ENTER_WATER,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (!evidence.onGround()
                    && evidence.verticalVelocity() < -0.08D
                    && option.landing()
                    && dryStable) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("landing", target),
                        MinecraftRecoveryGovernor.CandidateKind.STEER_TO_KNOWN_LANDING,
                        AgentInputState.RecoveryMode.STEER_TO_LANDING,
                        target,
                        null,
                        movement,
                        true,
                        true);
            }
            if (threat != null && dryStable
                    && fartherFromThreat(player.position(), target, threat)) {
                addRecoveryCandidate(
                        candidates,
                        player,
                        map,
                        recoveryCandidateId("retreat", target),
                        MinecraftRecoveryGovernor.CandidateKind.RETREAT_FROM_THREAT,
                        AgentInputState.RecoveryMode.RETREAT_FROM_THREAT,
                        target,
                        threat,
                        movement,
                        false,
                        true);
            }
        }
        return List.copyOf(candidates);
    }

    private static void addRecoveryCandidate(
            List<MinecraftRecoveryGovernor.Candidate> candidates,
            net.minecraft.client.player.LocalPlayer player,
            KnownTraversabilitySnapshot map,
            String id,
            MinecraftRecoveryGovernor.CandidateKind kind,
            AgentInputState.RecoveryMode mode,
            net.minecraft.world.phys.Vec3 target,
            net.minecraft.world.phys.Vec3 threat,
            Set<dev.aod.mcmcp.routine.MovementInputLease.MovementKey> movement,
            boolean preventsFatalHarm,
            boolean reachesStableState) {
        var currentCenter = player.getBoundingBox().getCenter();
        double distance = recoveryDistance(currentCenter, target);
        if (distance <= 0.0D || movement.isEmpty()) return;
        candidates.add(new MinecraftRecoveryGovernor.Candidate(
                id,
                map.worldSessionId(),
                map.dimension(),
                map.worldRevision(),
                kind,
                movement,
                new AgentInputState.RecoveryIntent(mode, target, threat),
                true,
                true,
                preventsFatalHarm,
                true,
                reachesStableState,
                Math.max(1, (int) Math.ceil(distance * RoutePlan.TICKS_PER_TRANSITION)),
                distance,
                distance));
    }

    static boolean requiresRecoveryJump(double currentCenterY, net.minecraft.world.phys.Vec3 target) {
        return target.y > currentCenterY + 0.1D;
    }

    static double recoveryDistance(
            net.minecraft.world.phys.Vec3 currentCenter,
            net.minecraft.world.phys.Vec3 target) {
        return Math.hypot(target.x - currentCenter.x, target.z - currentCenter.z)
                + Math.max(0.0D, target.y - currentCenter.y);
    }

    static String recoveryCandidateId(String prefix, net.minecraft.world.phys.Vec3 target) {
        return prefix + "-"
                + recoveryTargetCoordinate(target.x) + "_"
                + recoveryTargetCoordinate(target.y) + "_"
                + recoveryTargetCoordinate(target.z);
    }

    private static long recoveryTargetCoordinate(double coordinate) {
        return (long) Math.floor(coordinate * 4.0D);
    }

    private static boolean recoveryDryStable(LocalObservationVolume.EndpointSafety endpoint) {
        return endpoint.loaded()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED
                && endpoint.clearance()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR
                && endpoint.support()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.PRESENT
                && endpoint.fluid()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE
                && !endpoint.suffocation()
                && endpoint.hazard()
                        == dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE;
    }

    private static net.minecraft.world.phys.Vec3 recoveryThreat(
            net.minecraft.client.player.LocalPlayer player) {
        var source = player.getLastDamageSource();
        if (source == null) return null;
        var raw = source.sourcePositionRaw();
        if (raw != null) return raw;
        var causing = source.getEntity();
        if (causing != null && !causing.isRemoved()) return causing.position();
        var direct = source.getDirectEntity();
        return direct == null || direct.isRemoved()
                || direct.position().distanceToSqr(player.position()) <= 1.0E-6D
                ? null : direct.position();
    }

    static boolean fartherFromThreat(
            net.minecraft.world.phys.Vec3 current,
            net.minecraft.world.phys.Vec3 target,
            net.minecraft.world.phys.Vec3 threat) {
        double currentDistance = Math.hypot(current.x - threat.x, current.z - threat.z);
        double targetDistance = Math.hypot(target.x - threat.x, target.z - threat.z);
        return targetDistance > currentDistance + 1.0E-7D;
    }

    static PredicateRequirements predicateRequirements(ActionDsl.Program program) {
        Objects.requireNonNull(program, "program");
        var numeric = EnumSet.noneOf(ActionDsl.NumericField.class);
        var bools = EnumSet.noneOf(ActionDsl.BooleanField.class);
        var items = new LinkedHashSet<String>();
        var effects = new LinkedHashSet<String>();
        collectPredicateRequirements(program.body(), numeric, bools, items, effects);
        return new PredicateRequirements(numeric, bools, items, effects);
    }

    private static void collectPredicateRequirements(
            List<ActionDsl.Node> nodes,
            Set<ActionDsl.NumericField> numeric,
            Set<ActionDsl.BooleanField> bools,
            Set<String> items,
            Set<String> effects) {
        for (var node : nodes) {
            if (node instanceof ActionDsl.If conditional) {
                for (var atomic : predicateOperands(conditional.condition())) {
                    switch (atomic) {
                        case ActionDsl.NumericPredicate value -> numeric.add(value.field());
                        case ActionDsl.BooleanPredicate value -> bools.add(value.field());
                        case ActionDsl.InventoryPredicate value -> items.add(value.item());
                        case ActionDsl.StatusPredicate value -> effects.add(value.effect());
                    }
                }
                collectPredicateRequirements(
                        conditional.thenBranch(), numeric, bools, items, effects);
                collectPredicateRequirements(
                        conditional.elseBranch(), numeric, bools, items, effects);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                collectPredicateRequirements(repeat.body(), numeric, bools, items, effects);
            }
        }
    }

    private static List<ActionDsl.AtomicPredicate> predicateOperands(
            ActionDsl.Predicate predicate) {
        return predicate instanceof ActionDsl.AtomicPredicate atomic
                ? List.of(atomic)
                : ((ActionDsl.LogicalPredicate) predicate).operands();
    }

    private static boolean breakProgramPreconditionsCurrent(
            Minecraft minecraft,
            ActionDslCompiler.CompiledProgram program,
            Optional<ActionDsl.Node> initialPrimitive) {
        if (initialPrimitive.filter(ActionDsl.BreakKnownFace.class::isInstance).isEmpty()) {
            return true;
        }
        var player = minecraft.player;
        if (player == null) return false;
        var breaks = new ArrayList<ActionDsl.BreakKnownFace>();
        collectBreakNodes(program.request().program().body(), breaks);
        if (breaks.isEmpty()) return true;
        if (!player.isAlive() || player.isDeadOrDying() || player.isUsingItem()
                || !player.onGround() || player.isPassenger()
                || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying
                || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return false;
        }
        int requiredDurability = Math.toIntExact(program.worstCaseCost().blocksBroken());
        // ponytail: mixed-tool branches use one conservative worst-path allowance per tool.
        for (var block : breaks) {
            if (findDurableHotbarTool(player, block.toolItem(), requiredDurability) < 0) {
                return false;
            }
        }
        return inventoryCanReceiveKnownLogs(player, program);
    }

    private static void collectBreakNodes(
            List<ActionDsl.Node> nodes, List<ActionDsl.BreakKnownFace> output) {
        for (var node : nodes) {
            if (node instanceof ActionDsl.BreakKnownFace block) {
                output.add(block);
            } else if (node instanceof ActionDsl.If conditional) {
                collectBreakNodes(conditional.thenBranch(), output);
                collectBreakNodes(conditional.elseBranch(), output);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                collectBreakNodes(repeat.body(), output);
            }
        }
    }

    private static int findDurableHotbarTool(
            net.minecraft.client.player.LocalPlayer player,
            String itemId,
            int requiredDurability) {
        if (requiredDurability < 1) return -1;
        var inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                    && stack.isDamageableItem()
                    && stack.getMaxDamage() - stack.getDamageValue() >= requiredDurability) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean inventoryCanReceiveKnownLogs(
            net.minecraft.client.player.LocalPlayer player,
            ActionDslCompiler.CompiledProgram program) {
        var breaks = new ArrayList<ActionDsl.BreakKnownFace>();
        collectBreakNodes(program.request().program().body(), breaks);
        var logItems = new LinkedHashSet<String>();
        breaks.forEach(block -> logItems.add(block.expectedBlock()));
        if (logItems.isEmpty()) return true;
        int requiredPerType = Math.toIntExact(program.worstCaseCost().blocksBroken());
        var inventory = player.getInventory();
        int emptySlots = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) emptySlots++;
        }
        int newStacksNeeded = 0;
        for (String itemId : logItems) {
            int existingCapacity = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                    existingCapacity = Math.addExact(
                            existingCapacity,
                            Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                }
            }
            if (existingCapacity < requiredPerType) newStacksNeeded++;
        }
        return emptySlots >= newStacksNeeded;
    }

    private static boolean breakSourceControlled(
            Minecraft minecraft, ActionDsl.BreakKnownFace block) {
        var player = minecraft.player;
        var level = minecraft.level;
        var gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null
                || minecraft.getConnection() == null
                || !player.isAlive() || player.isDeadOrDying() || player.isUsingItem()
                || !player.onGround() || player.isPassenger()
                || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying
                || gameMode.getPlayerMode() != GameType.SURVIVAL
                || !block.target().dimension().equals(
                        level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                block.target().x(), block.target().y(), block.target().z());
        if (!level.isLoaded(position)
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(position)
                || hit.getDirection() != Direction.valueOf(block.face().name())
                || !player.isWithinBlockInteractionRange(position, 0.0D)
                || !level.getWorldBorder().isWithinBounds(position)
                || player.blockActionRestricted(level, position, gameMode.getPlayerMode())) {
            return false;
        }
        var state = level.getBlockState(position);
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        float destroyProgress = state.getDestroyProgress(player, level, position);
        if (!block.expectedBlock().equals(blockId)
                || !SafeBreakSourcePolicy.allowsLiveState(
                        state, level.getBlockEntity(position) != null)
                || destroyProgress <= 0.0F
                || destroyProgress * StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS < 1.0F) {
            return false;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (selected < 0 || selected >= Inventory.getSelectionSize()) return false;
        var tool = player.getInventory().getItem(selected);
        return !tool.isEmpty()
                && block.toolItem().equals(
                        BuiltInRegistries.ITEM.getKey(tool.getItem()).toString())
                && tool.isDamageableItem()
                && tool.getMaxDamage() - tool.getDamageValue() >= 1;
    }

    private static boolean breakTargetStateMatches(
            Minecraft minecraft, ActionDsl.BreakKnownFace block) {
        var level = minecraft.level;
        if (level == null || !block.target().dimension().equals(
                level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                block.target().x(), block.target().y(), block.target().z());
        return level.isLoaded(position)
                && block.expectedBlock().equals(BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(position).getBlock()).toString());
    }

    static void validatePredicateAvailability(
            ActionDsl.Program program, PolicySnapshot snapshot) {
        for (var node : program.body()) {
            validatePredicateAvailability(node, snapshot);
        }
    }

    private static void validatePredicateAvailability(
            ActionDsl.Node node, PolicySnapshot snapshot) {
        if (node instanceof ActionDsl.If conditional) {
            PredicateEvaluator.evaluate(conditional.condition(), snapshot);
            conditional.thenBranch().forEach(child ->
                    validatePredicateAvailability(child, snapshot));
            conditional.elseBranch().forEach(child ->
                    validatePredicateAvailability(child, snapshot));
        } else if (node instanceof ActionDsl.Repeat repeat) {
            repeat.body().forEach(child -> validatePredicateAvailability(child, snapshot));
        }
    }

    private static PolicySnapshot policySnapshot(Minecraft minecraft) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        return new PolicySnapshot() {
            @Override
            public OptionalDouble numeric(ActionDsl.NumericField field) {
                return OptionalDouble.of(switch (field) {
                    case HEALTH -> player.getHealth();
                    case HUNGER -> player.getFoodData().getFoodLevel();
                    case AIR -> player.getAirSupply();
                });
            }

            @Override
            public Optional<Boolean> bool(ActionDsl.BooleanField field) {
                return Optional.of(switch (field) {
                    case ON_FIRE -> player.isOnFire();
                    case SUBMERGED -> player.isUnderWater();
                });
            }

            @Override
            public OptionalInt inventoryCount(String item) {
                int count = 0;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    var stack = inventory.getItem(slot);
                    if (!stack.isEmpty()
                            && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                        count = Math.addExact(count, stack.getCount());
                    }
                }
                return OptionalInt.of(count);
            }

            @Override
            public Optional<Boolean> hasStatusEffect(String effect) {
                return Optional.of(player.getActiveEffects().stream()
                        .anyMatch(instance -> instance.getEffect().getRegisteredName().equals(effect)));
            }
        };
    }

    private KnownTraversabilitySnapshot requireAgentMap(
            WorldSessionTracker.Snapshot session) {
        var map = knownTraversability.snapshot().orElseThrow(() ->
                new RuntimeInvocationException(
                        "unsafe_state", "No current traversability map is available.", true, Map.of()));
        if (!session.worldReady()
                || !session.worldSessionId().equals(map.worldSessionId())
                || !session.dimension().equals(map.dimension())
                || map.worldRevision() != knownTraversabilityRevision) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The traversability map crossed a world boundary.",
                    true,
                    Map.of());
        }
        return map;
    }

    private static AgentPrimitivePlanner.Pose playerPose(
            net.minecraft.client.player.LocalPlayer player, String dimension) {
        Objects.requireNonNull(player, "player");
        return new AgentPrimitivePlanner.Pose(
                playerCell(player, dimension),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getEyeY() - player.getY(),
                player.getYRot(),
                player.getXRot());
    }

    private static NavCell playerCell(
            net.minecraft.client.player.LocalPlayer player, String dimension) {
        return new NavCell(
                dimension,
                Mth.floor(player.getX()),
                Mth.floor(player.getY()),
                Mth.floor(player.getZ()));
    }

    private static RuntimeInvocationException planningFailure(
            AgentPrimitivePlanner.PlanningException failure) {
        String code = switch (failure.code()) {
            case TIMEOUT -> "timeout";
            case PROGRAM_BUDGET_UNPROVABLE -> "program_budget_unprovable";
            case TARGET_UNKNOWN, NO_KNOWN_PATH ->
                    failure.code().name().toLowerCase(Locale.ROOT);
        };
        return new RuntimeInvocationException(
                code,
                failure.getMessage(),
                true,
                Map.of());
    }

    static boolean fitsRemainingBudget(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDslCompiler.Cost next,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(next, "next");
        if (activeElapsedNanos < 0L || used.motionOverflowed()) return false;
        return fits(
                        activeElapsedNanos,
                        Duration.ofMillis(next.durationMillis()).toNanos(),
                        Duration.ofMillis(budget.maxDurationMillis()).toNanos())
                && fits(used.ticks(), next.ticks(), budget.maxTicks())
                && fits(used.distanceTravelled(), next.distanceBlocks(), budget.maxDistanceBlocks())
                && fits(used.cameraDegrees(), next.cameraDegrees(), budget.maxCameraDegrees())
                && fits(used.interactions(), next.interactions(), budget.maxInteractions())
                && fits(used.blocksBroken(), next.blocksBroken(), budget.maxBlocksBroken())
                && fits(used.blocksPlaced(), next.blocksPlaced(), budget.maxBlocksPlaced());
    }

    static boolean motionBudgetExhausted(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDsl.Node primitive) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(budget, "budget");
        return used.motionOverflowed()
                || primitive instanceof ActionDsl.NavigateToKnown
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.FaceKnownPosition
                        && used.cameraDegrees() >= budget.maxCameraDegrees()
                || primitive instanceof ActionDsl.BreakKnownFace
                        && used.cameraDegrees() >= budget.maxCameraDegrees();
    }

    private static double remainingDistance(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            AgentExecution execution) {
        double global = budget.maxDistanceBlocks() - used.distanceTravelled();
        double occurrence = execution.occurrenceLimit.distanceBlocks()
                - consumedDistance(used, execution.occurrenceBaseline);
        return Math.max(0.0D, Math.min(global, occurrence));
    }

    private static double remainingCameraDegrees(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            AgentExecution execution) {
        double global = budget.maxCameraDegrees() - used.cameraDegrees();
        double occurrence = execution.occurrenceLimit.cameraDegrees()
                - consumedCamera(used, execution.occurrenceBaseline);
        return Math.max(0.0D, Math.min(global, occurrence));
    }

    private static boolean fitsOccurrenceRemaining(
            AgentActionStore.Progress used,
            AgentExecution execution,
            ActionDslCompiler.Cost next) {
        var baseline = Objects.requireNonNull(execution.occurrenceBaseline, "occurrenceBaseline");
        var limit = Objects.requireNonNull(execution.occurrenceLimit, "occurrenceLimit");
        return !used.motionOverflowed()
                && fits(consumedDurationMillis(used, baseline), next.durationMillis(),
                        limit.durationMillis())
                && fits(consumedTicks(used, baseline), next.ticks(), limit.ticks())
                && fits(consumedDistance(used, baseline), next.distanceBlocks(),
                        limit.distanceBlocks())
                && fits(consumedCamera(used, baseline), next.cameraDegrees(),
                        limit.cameraDegrees())
                && fits(consumedInteractions(used, baseline), next.interactions(),
                        limit.interactions())
                && fits(consumedBreaks(used, baseline), next.blocksBroken(),
                        limit.blocksBroken())
                && fits(consumedPlacements(used, baseline), next.blocksPlaced(),
                        limit.blocksPlaced());
    }

    static ActionDslCompiler.Cost breakExecutionCost(
            ActionDslCompiler.Cost planned, boolean reobservationComplete) {
        Objects.requireNonNull(planned, "planned");
        if (!reobservationComplete) return planned;
        long ticks = Math.subtractExact(
                planned.ticks(), AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS);
        long duration = Math.subtractExact(
                planned.durationMillis(),
                Math.multiplyExact(AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS, 50L));
        return new ActionDslCompiler.Cost(
                duration,
                ticks,
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
    }

    static long breakAimTicks(ActionDslCompiler.Cost planned) {
        Objects.requireNonNull(planned, "planned");
        return Math.max(
                1L,
                planned.ticks()
                        - AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS
                        - AgentPrimitivePlanner.BREAK_TICK_UPPER_BOUND);
    }

    private static boolean occurrenceBudgetExceeded(
            AgentActionStore.Progress used, AgentExecution execution) {
        var baseline = Objects.requireNonNull(execution.occurrenceBaseline, "occurrenceBaseline");
        var limit = Objects.requireNonNull(execution.occurrenceLimit, "occurrenceLimit");
        return used.motionOverflowed()
                || consumedDurationMillis(used, baseline) > limit.durationMillis()
                || consumedTicks(used, baseline) > limit.ticks()
                || consumedDistance(used, baseline) > limit.distanceBlocks() + 1.0e-9D
                || consumedCamera(used, baseline) > limit.cameraDegrees() + 1.0e-9D
                || consumedInteractions(used, baseline) > limit.interactions()
                || consumedBreaks(used, baseline) > limit.blocksBroken()
                || consumedPlacements(used, baseline) > limit.blocksPlaced();
    }

    private static boolean occurrenceBudgetExceededAfterPrimitive(
            AgentActionStore.Progress used,
            AgentExecution execution,
            MinecraftActionPrimitiveExecutor.Status status) {
        Objects.requireNonNull(status, "status");
        return occurrenceBudgetExceeded(used, execution);
    }

    static ActionDslCompiler.Cost occurrenceCostIncludingConsumed(
            AgentActionStore.Progress used,
            AgentActionStore.Progress baseline,
            ActionDslCompiler.Cost next) {
        return new ActionDslCompiler.Cost(
                Math.addExact(consumedDurationMillis(used, baseline), next.durationMillis()),
                Math.addExact(consumedTicks(used, baseline), next.ticks()),
                consumedDistance(used, baseline) + next.distanceBlocks(),
                consumedCamera(used, baseline) + next.cameraDegrees(),
                Math.addExact(consumedInteractions(used, baseline), next.interactions()),
                Math.addExact(consumedBreaks(used, baseline), next.blocksBroken()),
                Math.addExact(consumedPlacements(used, baseline), next.blocksPlaced()));
    }

    private static long consumedDurationMillis(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return Math.multiplyExact(consumedTicks(used, baseline), 50L);
    }

    private static long consumedTicks(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.ticks(), baseline.ticks());
    }

    private static double consumedDistance(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.distanceTravelled(), baseline.distanceTravelled());
    }

    private static double consumedCamera(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.cameraDegrees(), baseline.cameraDegrees());
    }

    private static long consumedInteractions(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.interactions(), baseline.interactions());
    }

    private static long consumedBreaks(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.blocksBroken(), baseline.blocksBroken());
    }

    private static long consumedPlacements(
            AgentActionStore.Progress used, AgentActionStore.Progress baseline) {
        return nonNegativeDifference(used.blocksPlaced(), baseline.blocksPlaced());
    }

    private static long nonNegativeDifference(long current, long baseline) {
        if (current < baseline) throw new IllegalStateException("Action progress moved backwards");
        return current - baseline;
    }

    private static double nonNegativeDifference(double current, double baseline) {
        double difference = current - baseline;
        if (!Double.isFinite(difference) || difference < -1.0e-9D) {
            throw new IllegalStateException("Action progress moved backwards");
        }
        return Math.max(0.0D, difference);
    }

    static boolean motionBudgetExceededAfterPrimitive(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            ActionDsl.Node primitive,
            MinecraftActionPrimitiveExecutor.Status status) {
        Objects.requireNonNull(status, "status");
        if (used.motionOverflowed()
                || used.distanceTravelled() > budget.maxDistanceBlocks()
                || used.cameraDegrees() > budget.maxCameraDegrees()) {
            return true;
        }
        return status == MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED
                && motionBudgetExhausted(used, budget, primitive);
    }

    static boolean replanDeadlineReached(long actionTick, long deadlineTick) {
        return deadlineTick > 0L && actionTick >= deadlineTick;
    }

    static boolean shouldVerifyReplanHeartbeat(
            boolean replanning, MinecraftActionPrimitiveExecutor.TickResult result) {
        Objects.requireNonNull(result, "result");
        return replanning && result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING;
    }

    static boolean repeatedPositionCorrection(
            long previousRevision, long currentRevision, int previousCorrections) {
        return currentRevision - previousRevision > 1L || previousCorrections > 0;
    }

    private void recordAgentMotion(
            UUID actionId, net.minecraft.client.player.LocalPlayer player) {
        var position = player.position();
        double distance = position.distanceTo(agentExecution.lastPosition);
        double camera = cameraDelta(
                player.getYRot(), player.getXRot(),
                agentExecution.lastYaw, agentExecution.lastPitch);
        agentActions.recordMotion(actionId, distance, camera);
        agentExecution.lastPosition = position;
        agentExecution.lastYaw = player.getYRot();
        agentExecution.lastPitch = player.getXRot();
    }

    static double cameraDelta(
            float yaw, float pitch, float previousYaw, float previousPitch) {
        return Math.abs(Mth.wrapDegrees((double) yaw - previousYaw))
                + Math.abs((double) pitch - previousPitch);
    }

    private void recordPendingAgentMotion(Minecraft minecraft) {
        if (agentExecution == null || minecraft.player == null || minecraft.level == null) return;
        var session = sessions.snapshot();
        if (!sameAgentMotionBoundary(
                agentExecution.worldSessionId,
                session,
                minecraft.level.dimension().identifier().toString())) {
            return;
        }
        long correctionRevision = reconciliationSignals.currentSnapshot(minecraft.level)
                .map(ClientReconciliationSignals.Snapshot::positionCorrectionRevision)
                .orElse(agentExecution.positionCorrectionRevision);
        if (correctionRevision > agentExecution.positionCorrectionRevision) {
            agentExecution.lastPosition = minecraft.player.position();
            agentExecution.lastYaw = minecraft.player.getYRot();
            agentExecution.lastPitch = minecraft.player.getXRot();
            return;
        }
        var active = agentActions.active();
        if (active.isPresent() && active.orElseThrow().actionId().equals(agentExecution.actionId)) {
            recordAgentMotion(agentExecution.actionId, minecraft.player);
        }
    }

    static boolean sameAgentMotionBoundary(
            UUID executionSession,
            WorldSessionTracker.Snapshot session,
            String currentDimension) {
        return session.worldReady()
                && Objects.equals(executionSession, session.worldSessionId())
                && Objects.equals(session.dimension(), currentDimension);
    }

    private static boolean fits(long used, long next, long maximum) {
        return used >= 0L && next >= 0L && used <= maximum && next <= maximum - used;
    }

    private static boolean fits(double used, double next, double maximum) {
        return Double.isFinite(used) && Double.isFinite(next) && Double.isFinite(maximum)
                && used >= 0.0D && next >= 0.0D && used <= maximum
                && next <= maximum - used + 1.0e-9D;
    }

    private static long activeElapsedNanos(AgentExecution execution, long nowNanos) {
        return activeElapsedNanos(
                execution.startedAtNanos, execution.pausedNanos, nowNanos);
    }

    private static long actionMovementDeadline(
            AgentExecution execution, long durationLimitNanos, long nowNanos) {
        long remaining = Math.max(
                0L, durationLimitNanos - activeElapsedNanos(execution, nowNanos));
        return AgentInputState.global().watchdogTime(nowNanos) + remaining;
    }

    static long activeElapsedNanos(long startedAtNanos, long pausedNanos, long nowNanos) {
        if (pausedNanos < 0L) {
            throw new IllegalArgumentException("pausedNanos must be non-negative");
        }
        long elapsed = nonNegativeNanoElapsed(startedAtNanos, nowNanos);
        return pausedNanos >= elapsed ? 0L : elapsed - pausedNanos;
    }

    private static long nonNegativeNanoElapsed(long startedAtNanos, long nowNanos) {
        long elapsed = nowNanos - startedAtNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private void closeAgentPrimitiveExecutor() {
        if (agentExecution == null) return;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            try {
                AgentInputState.global().neutralizeTrackedAgentVelocity(player);
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error("MCMCP Agent velocity neutralization failed", failure);
            }
        }
        try {
            agentExecution.primitiveExecutor.close();
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP Action DSL input release failed", failure);
        }
        if (agentExecution.blockBreakAttempt != null) {
            try {
                agentExecution.blockBreakAttempt.close();
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error("MCMCP known-face break release failed", failure);
            } finally {
                agentExecution.blockBreakAttempt = null;
            }
        }
        if (agentExecution.blockMutationAttempt != null) {
            try {
                agentExecution.blockMutationAttempt.close();
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error("MCMCP known-block mutation release failed", failure);
            } finally {
                agentExecution.blockMutationAttempt = null;
            }
        }
        agentExecution.breakAimComplete = false;
    }

    private void closeRecoveryGovernor() {
        if (recoveryGovernor == null) return;
        try {
            recoveryGovernor.close();
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP recovery input release failed", failure);
        } finally {
            recoveryGovernor = null;
        }
    }

    private void failAgentAction(
            AgentActionStore.FailureCode code, boolean recoverable, String evidence) {
        try {
            agentActions.terminateActive(new AgentActionStore.Failure(
                    code, recoverable, List.of(evidence)));
        } finally {
            finishAgentControlReady(
                    Minecraft.getInstance(), code.wireName().toLowerCase(Locale.ROOT));
        }
    }

    private void finishAgentControlReady(Minecraft minecraft, String fallbackLockReason) {
        var session = sessions.snapshot();
        closeAgentPrimitiveExecutor();
        closeRecoveryGovernor();
        inputRelease.releaseAll(minecraft);
        restoreAgentSelectedSlot(minecraft);
        agentExecution = null;
        pendingAgentAdmission = null;
        if (session.worldSessionId() == null || !arming.completeAction(session.worldSessionId())) {
            arming.lock(fallbackLockReason);
        }
    }

    private void closeAgentControl(Minecraft minecraft, String lockReason) {
        closeAgentPrimitiveExecutor();
        closeRecoveryGovernor();
        inputRelease.releaseAll(minecraft);
        arming.lock(lockReason);
        restoreAgentSelectedSlot(minecraft);
        agentExecution = null;
        pendingAgentAdmission = null;
    }

    private void restoreAgentSelectedSlot(Minecraft minecraft) {
        if (agentExecution == null || agentExecution.agentSelectedSlot < 0) return;
        var player = minecraft.player;
        if (player == agentExecution.playerIdentity
                && player.getInventory().getSelectedSlot() == agentExecution.agentSelectedSlot) {
            player.getInventory().setSelectedSlot(agentExecution.originalSelectedSlot);
        }
    }

    private void tickActiveRoutine(Minecraft minecraft) {
        var before = routines.activeRoutineId();
        if (before.isEmpty()) {
            return;
        }
        if (paused) {
            inputRelease.releaseAll(minecraft);
            return;
        }
        var session = sessions.snapshot();
        var routineId = before.orElseThrow();
        if (activeRoutineDeadline == null
                || !activeRoutineDeadline.allows(routineId, System.nanoTime())) {
            runPriorityStop(
                    () -> inbox.requestEmergencyStop("routine_wall_clock_deadline"),
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
            return;
        }
        var activeArming = arming.snapshot(session.worldSessionId());
        if (!enforceActiveRoutineArming(
                activeArming,
                () -> inbox.requestEmergencyStop("local_arming_locked"),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()))) {
            return;
        }
        try {
            routines.tick();
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP routine tick failed; forcing emergency stop", failure);
            inbox.requestEmergencyStop("routine_tick_exception");
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
            return;
        }
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
     * Recheck local arming immediately before every routine tick and finish a priority stop inline
     * when it is no longer armed.
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
                McmcpMod.LOGGER.error(
                        "MCMCP could not inspect pending routine finalization {}",
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
                McmcpMod.LOGGER.error(
                        "MCMCP retained finalization retry {} in non-terminal state {}",
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
    }

    private boolean stopActiveRoutineForEmergency(
            String reason,
            WorldSessionTracker.Snapshot session) {
        goalContinuation.clear();
        AgentActionStore.FailureCode actionCode = "local_ui_disabled".equals(reason)
                ? AgentActionStore.FailureCode.USER_DISABLED
                : AgentActionStore.FailureCode.EMERGENCY_STOP;
        boolean actionStopped = true;
        try {
            agentActions.terminateActive(new AgentActionStore.Failure(
                    actionCode, true, List.of(sanitizeLocalCode(reason))));
        } catch (RuntimeException | LinkageError failure) {
            actionStopped = false;
            McmcpMod.LOGGER.error("MCMCP emergency action termination failed", failure);
        } finally {
            closeAgentControl(Minecraft.getInstance(), sanitizeLocalCode(reason));
        }
        var active = routines.activeRoutineId();
        if (active.isEmpty()) {
            boolean voiceEnded = endVoiceFor(voiceRoutineId);
            return actionStopped && voiceEnded;
        }
        try {
            var cancelled = routines.cancelRoutine(active.orElseThrow(), reason, Long.MAX_VALUE, 1);
            var cleanup = finalizeTerminalRoutine(Minecraft.getInstance(), cancelled);
            return actionStopped && cleanup.inputsReleased() && cleanup.voice().success();
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP routine cancellation failed during emergency stop", failure);
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
            clearRoutineWallClockDeadline(cleanup.snapshot().routineId());
            applyCompletionIntentAfterTerminal(cleanup.snapshot());
            return cleanup;
        }

        if (attempt.incident().failedAttempts()
                >= FinalizationRetryQueue.MAX_AUTOMATIC_ATTEMPTS) {
            McmcpMod.LOGGER.error(
                    "MCMCP routine finalization exhausted cleanup retries; "
                            + "continuing record-only probes for {}",
                    snapshot.routineId(),
                    attempt.failure());
        }
        else {
            McmcpMod.LOGGER.error(
                    "MCMCP routine finalization boundary failed; a bounded retry was retained for {}",
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
        goalContinuation.consumeIntent(terminal.routineId());
        goalContinuation.clear();
        boolean succeeded = terminal.state() == RoutineState.SUCCEEDED
                && terminal.goalVerified()
                && terminal.finalizationFailure() == null;
        arming.lock(succeeded ? "goal_finished" : "goal_aborted");
    }

    static boolean recoverableContinuationFailure(
            String completionIntent,
            RoutineFailure failure,
            RoutineFailure finalizationFailure) {
        return GoalContinuationSession.CONTINUE_GOAL.equals(completionIntent)
                && failure != null
                && finalizationFailure == null
                && !failure.requiresUser()
                && failure.category() != RoutineFailure.Category.SAFETY
                && failure.recovery() == RoutineFailure.Recovery.REPLAN;
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
                McmcpMod.LOGGER.error("MCMCP input release failed during finalization", failure);
                inputsReleased = false;
            }
        }
        var voice = restoreVoice
                ? endVoiceSessionFor(routineId)
                : new VoiceEndOutcome(true, null, false, false, false);
        if (!voice.success()) {
            McmcpMod.LOGGER.warn(
                    "MCMCP Voice Chat restore did not complete: {}", voice.failureCode());
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
            McmcpMod.LOGGER.warn(
                    "MCMCP Voice Chat restore did not complete: {}", outcome.failureCode());
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

    public void onScreenOwnershipFailure(String reason) {
        Objects.requireNonNull(reason, "reason");
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        runPriorityEventStopIfRequired(
                automationActivityPending(),
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

    static Optional<RoutineManager.StartReceipt> replayFinitePlanAfterFinalizationGate(
            FinalizationRetryQueue retries,
            RoutineManager routines,
            String idempotencyKey,
            String requestIdentity,
            long clientTick) {
        requireNoPendingFinalizations(retries);
        return routines.replayFinitePlan(idempotencyKey, requestIdentity, clientTick);
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

    record ParsedFinitePlan(FinitePlanRequest request, String requestIdentity) {
        ParsedFinitePlan {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
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
        try {
            inbox.requestEmergencyStop(reason);
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        } finally {
            activeRoutineDeadline = null;
        }
    }

    private void clearRoutineWallClockDeadline(UUID routineId) {
        if (activeRoutineDeadline != null
                && activeRoutineDeadline.routineId().equals(routineId)) {
            activeRoutineDeadline = null;
        }
    }

    record RoutineWallClockDeadline(
            UUID routineId, long startedAtNanos, long durationNanos, long pausedNanos) {
        RoutineWallClockDeadline(UUID routineId, long startedAtNanos, long durationNanos) {
            this(routineId, startedAtNanos, durationNanos, 0L);
        }

        RoutineWallClockDeadline {
            Objects.requireNonNull(routineId, "routineId");
            if (durationNanos <= 0 || pausedNanos < 0L) {
                throw new IllegalArgumentException("durationNanos must be positive and pause non-negative");
            }
        }

        static RoutineWallClockDeadline start(UUID routineId, int maxDurationSeconds, long nowNanos) {
            if (maxDurationSeconds <= 0) {
                throw new IllegalArgumentException("max_duration_seconds must be positive");
            }
            return new RoutineWallClockDeadline(
                    routineId,
                    nowNanos,
                    Math.addExact(
                            Duration.ofSeconds(maxDurationSeconds).toNanos(),
                            FINALIZATION_RESERVE.toNanos()));
        }

        boolean allows(UUID activeRoutineId, long nowNanos) {
            long elapsedNanos = activeElapsedNanos(startedAtNanos, pausedNanos, nowNanos);
            return routineId.equals(activeRoutineId)
                    && elapsedNanos < durationNanos;
        }

        RoutineWallClockDeadline shiftStart(long pausedNanos) {
            if (pausedNanos <= 0L) {
                return this;
            }
            return new RoutineWallClockDeadline(
                    routineId,
                    startedAtNanos,
                    durationNanos,
                    saturatingNonNegativeAdd(this.pausedNanos, pausedNanos));
        }
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
            case UseItemOnBlockRequest.KIND -> {
                requireExactKeys(parameters, "use_item_on_block parameters", Set.of(
                        "target", "expected_before", "item", "expected_after"));
                yield new UseItemOnBlockRequest(
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

    static ParsedFinitePlan finitePlanRequestArgument(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        if (!"execute_plan".equals(stringArgument(arguments, "kind"))) {
            throw new IllegalArgumentException("kind must be execute_plan");
        }
        String completionIntent = completionIntentArgument(arguments);
        var outerBounds = objectArgument(arguments, "bounds");
        requireExactKeys(outerBounds, "execute_plan bounds", Set.of());
        var parameters = objectArgument(arguments, "parameters");
        var request = FinitePlanRequest.parse(parameters);
        var canonical = new StringBuilder();
        appendIdentity(canonical, "finite-plan/v1");
        appendCanonicalValue(canonical, parameters);
        appendCanonicalValue(canonical, completionIntent);
        return new ParsedFinitePlan(request, sha256Identity(canonical));
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
                String stackPolicy = stringArgument(stack, "stack_policy");
                if (!Set.of("default_components_only", "item_id_any_components")
                        .contains(stackPolicy)) {
                    throw new IllegalArgumentException("transfer stack policy is unsupported");
                }
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
            try {
                long integral = exactLong(number);
                appendIdentity(output, "integer");
                appendIdentity(output, Long.toString(integral));
            }
            catch (ArithmeticException nonInteger) {
                double finite = number.doubleValue();
                if (!Double.isFinite(finite)) {
                    throw new IllegalArgumentException("canonical numbers must be finite");
                }
                appendIdentity(output, "number");
                appendIdentity(output, new BigDecimal(number.toString())
                        .stripTrailingZeros().toPlainString());
            }
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
            case UseItemOnBlockRequest use -> Optional.of(use.target());
            case InteractBlockRequest block -> Optional.of(block.target());
            case InteractEntityRequest ignored -> Optional.empty();
        };
    }

    static void validateLiveBounds(
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

    static void validateLiveBounds(
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
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("saturating tick add requires non-negative operands");
        }
        return saturatingNonNegativeAdd(left, right);
    }

    private static long saturatingNonNegativeAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("saturating add requires non-negative operands");
        }
        if (left > Long.MAX_VALUE - right) {
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
            if (before.dimension() != null && !before.dimension().equals(dimension)) {
                clearAgentSessionState();
                routines.clearSession("dimension_changed");
                finalizationRetries.clear();
                goalContinuation.clear();
                voiceRoutineId = null;
                clearAutomationPortSessions(
                        stationaryBreakPort::clearSession,
                        semanticActionPort::clearSession,
                        applyBlockPlanPort::clearSession);
                clearPhaseFivePortSessions();
                recipeCatalog.detachSession();
                memory.detachSession();
                arming.lock("dimension_changed");
                inputRelease.releaseAll(minecraft);
            }
            memory.startSession(after.worldSessionId(), dimension);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    minecraft.level, after.worldSessionId());
            knownTraversability.startSession(
                    after.worldSessionId(), dimension, reconciliation.worldRevision());
            knownTraversabilityRevision = reconciliation.worldRevision();
            screenOwnership.bindWorldSession(minecraft.level, after.worldSessionId());
        }
    }

    private void synchronizeKnownTraversability(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.level == null) {
            return;
        }
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        if (reconciliation.worldRevision() <= knownTraversabilityRevision) {
            return;
        }
        var mutations = reconciliation.worldMutations().stream()
                .filter(mutation -> mutation.revision() > knownTraversabilityRevision)
                .toList();
        boolean ledgerGap = mutations.isEmpty()
                || mutations.getFirst().revision() != knownTraversabilityRevision + 1L;
        if (ledgerGap || mutations.stream().anyMatch(mutation ->
                mutation.kind() == ClientReconciliationSignals.WorldMutation.Kind.ALL)) {
            knownTraversability.startSession(
                    session.worldSessionId(), session.dimension(), reconciliation.worldRevision());
            knownTraversabilityRevision = reconciliation.worldRevision();
            return;
        }

        var affected = new LinkedHashSet<NavCell>();
        var map = knownTraversability.snapshot().orElseThrow();
        for (var key : map.edges().keySet()) {
            for (var mutation : mutations) {
                if (mutationAffects(mutation, key.from()) || mutationAffects(mutation, key.to())) {
                    affected.add(key.from());
                    affected.add(key.to());
                    break;
                }
            }
        }
        knownTraversability.advanceWorldRevision(
                reconciliation.worldRevision(), affected, List.of());
        knownTraversabilityRevision = reconciliation.worldRevision();
    }

    private static boolean mutationAffects(
            ClientReconciliationSignals.WorldMutation mutation, NavCell cell) {
        return switch (mutation.kind()) {
            case ALL -> true;
            case CHUNK -> (cell.x() >> 4) == mutation.x() && (cell.z() >> 4) == mutation.z();
            case BLOCK -> Math.abs((long) cell.x() - mutation.x()) <= 1L
                    && Math.abs((long) cell.z() - mutation.z()) <= 1L
                    && cell.y() >= mutation.y() - 2
                    && cell.y() <= mutation.y() + 3;
        };
    }

    private void collectAgentObservation(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        int radius = McmcpClientConfig.visualRadiusBlocks();
        int rays = McmcpClientConfig.raysPerTick();
        if (agentObserver == null
                || agentObserver.configuredRadiusBlocks() != radius
                || agentObserver.raysPerTick() != rays) {
            agentObserver = new OmnidirectionalObserver(radius, rays);
        }
        long worldRevision = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId()).worldRevision();
        var dimension = new ResourceId(session.dimension());
        latestLocalObservation = LocalObservationVolume.global().observe(
                minecraft.player, session.clientTick(), worldRevision);
        var local = LocalObservationProjector.project(
                latestLocalObservation,
                session.worldSessionId(),
                session.dimension(),
                worldRevision,
                minecraft.player.getY());
        localSafety = local.currentSafety();
        local.edges().forEach(knownTraversability::observe);
        soundPlaybackTruncated = soundPlaybacks.drainInto(
                soundClues,
                dimension,
                session.clientTick(),
                worldRevision,
                candidate -> {
                    Identifier identifier = Identifier.tryParse(candidate);
                    return identifier != null && BuiltInRegistries.ENTITY_TYPE.get(identifier).isPresent();
                }).recentSoundCluesTruncated();
        double fogDistance = ClientFogDistanceSignals.currentOr(
                minecraft.level,
                minecraft.player,
                minecraft.player.tickCount,
                1.0D);
        agentObserver.tick(
                        minecraft.level,
                        minecraft.player,
                        session.clientTick(),
                        worldRevision,
                        fogDistance)
                .ifPresent(visual -> {
                    var sounds = soundClues.snapshot(visual.frameCompletedTick());
                    var records = new ArrayList<>(visual.records());
                    records.addAll(local.records());
                    records.addAll(sounds.clues());
                    agentObservationFrames.publish(new ObservationFrame(
                            visual.frameId(),
                            visual.dimension(),
                            visual.frameCompletedTick(),
                            visual.configuredVisualRadiusBlocks(),
                            visual.visibleEntitiesTruncated(),
                            sounds.recentSoundCluesTruncated() || soundPlaybackTruncated,
                            records));
                });
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
            return RuntimeReply.failure("server_busy", "The client-thread deadline expired", true);
        }
        if (cause instanceof ClientCommandInbox.CommandInvalidatedException) {
            return RuntimeReply.failure("unsafe_state", "The world or safety epoch changed before execution", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return RuntimeReply.failure("server_busy", "The bounded client command inbox is full", true);
        }
        if (cause instanceof MinecraftObservationService.ObservationUnavailableException unavailable) {
            return RuntimeReply.failure(unavailable.code(), unavailable.getMessage(), true);
        }
        if (cause instanceof ActionDslException invalidDsl) {
            return RuntimeReply.failure(
                    invalidDsl.code().name().toLowerCase(Locale.ROOT),
                    publicMessage(invalidDsl),
                    invalidDsl.code() != ActionDslException.Code.INVALID_ARGUMENT);
        }
        if (cause instanceof AgentActionStore.BusyException) {
            return RuntimeReply.failure("task_busy", "Another action is active", true);
        }
        if (cause instanceof AgentActionStore.NotFoundException) {
            return RuntimeReply.failure("action_not_found", "The action is not retained", false);
        }
        if (cause instanceof RuntimeInvocationException invocation) {
            return RuntimeReply.failure(
                    invocation.code(), publicMessage(invocation), invocation.retryable(), invocation.details());
        }
        if (cause instanceof RoutineManager.RoutineBusyException busy) {
            var details = busy.activeRoutineId() == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("active_routine_id", busy.activeRoutineId().toString());
            return RuntimeReply.failure("task_busy", "Another routine is active", true, details);
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

    private static final class AgentExecution {
        private final UUID actionId;
        private final UUID worldSessionId;
        private final ActionDslCompiler.CompiledProgram program;
        private final ActionProgramCursor cursor;
        private final MinecraftActionPrimitiveExecutor primitiveExecutor;
        private final Map<String, AgentPrimitivePlanner.MutationAim> mutationAims;
        private final long startedAtNanos;
        private long pausedNanos;
        private net.minecraft.world.phys.Vec3 lastPosition;
        private float lastYaw;
        private float lastPitch;
        private ActionDsl.Node primitive;
        private int waitTicksRemaining;
        private long replanNotBeforeTick;
        private long replanDeadlineTick;
        private long primitivePlanDeadlineTick;
        private boolean replanning;
        private boolean primitivePlanning;
        private boolean retainOccurrenceBaseline;
        private boolean replanHeartbeatPending;
        private boolean goalPreempted;
        private long positionCorrectionRevision;
        private int positionCorrections;
        private AgentActionStore.Progress occurrenceBaseline;
        private ActionDslCompiler.Cost occurrenceLimit;
        private final Object playerIdentity;
        private final int originalSelectedSlot;
        private int agentSelectedSlot = -1;
        private boolean breakAimComplete;
        private KnownBlockBreakAttempt blockBreakAttempt;
        private KnownBlockMutationAttempt blockMutationAttempt;

        private AgentExecution(
                AgentActionStore.Active action,
                UUID worldSessionId,
                long startedAtNanos,
                net.minecraft.world.phys.Vec3 lastPosition,
                float lastYaw,
                float lastPitch,
                net.minecraft.client.player.LocalPlayer player,
                float maxCameraDegreesPerTick,
                Map<String, AgentPrimitivePlanner.MutationAim> mutationAims,
                long positionCorrectionRevision) {
            actionId = action.actionId();
            this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
            program = action.program();
            cursor = new ActionProgramCursor(action.program().request().program());
            primitiveExecutor = new MinecraftActionPrimitiveExecutor(maxCameraDegreesPerTick);
            this.mutationAims = new LinkedHashMap<>(
                    Objects.requireNonNull(mutationAims, "mutationAims"));
            this.startedAtNanos = startedAtNanos;
            this.lastPosition = Objects.requireNonNull(lastPosition, "lastPosition");
            this.lastYaw = lastYaw;
            this.lastPitch = lastPitch;
            playerIdentity = Objects.requireNonNull(player, "player");
            originalSelectedSlot = player.getInventory().getSelectedSlot();
            if (positionCorrectionRevision < 0L) {
                throw new IllegalArgumentException(
                        "positionCorrectionRevision must be non-negative");
            }
            this.positionCorrectionRevision = positionCorrectionRevision;
        }
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
