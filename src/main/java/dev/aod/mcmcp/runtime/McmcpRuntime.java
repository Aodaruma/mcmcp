package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.AgentObservations.PreparedObservationPage;
import dev.aod.mcmcp.runtime.ActionAdmission.AgentAdmissionSnapshot;
import dev.aod.mcmcp.runtime.ActionAdmission.PreparedAgentAction;
import dev.aod.mcmcp.runtime.ActionAdmission.AdmissionFenceFailure;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.ActionProgramCursor;
import dev.aod.mcmcp.agent.action.AgentActionStore.RendererRecoveryStage;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.CollectBatchEvidence;
import dev.aod.mcmcp.agent.action.ContainerInspection;
import dev.aod.mcmcp.agent.action.FrameItemAttempt;
import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.action.KnownBlockMutationAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationWireMapper;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor;
import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.client.AutomationIndicatorController;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.client.MultiplayerAllowlist;
import dev.aod.mcmcp.mcp.EvaluationTurnControl;
import dev.aod.mcmcp.mcp.McpRuntimePort.RuntimeReply;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.observation.BlockPlanComparator;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BoundedInputLease;
import dev.aod.mcmcp.routine.FrameItemPort;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.MinecraftFrameItemPort;
import dev.aod.mcmcp.routine.MinecraftKnownBrewingPort;
import dev.aod.mcmcp.routine.MinecraftKnownFurnacePort;
import dev.aod.mcmcp.routine.MinecraftKnownMenuPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveWorldPort;
import dev.aod.mcmcp.routine.MinecraftPillarUpPort;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.PhaseFivePortRouter;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakOperation;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.runtime.ActionBudgets.BatchTargetDisposition;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitAuthorization;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitLiveState;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitVisibilityState;
import dev.aod.mcmcp.runtime.ActionPredicates.PredicateRequirements;
import dev.aod.mcmcp.runtime.KillZoneSafety.AttackProfile;
import dev.aod.mcmcp.runtime.KillZoneSafety.KillZoneAdmission;
import dev.aod.mcmcp.runtime.RecoveryPlanning.RecoveryDescentTracker;
import dev.aod.mcmcp.runtime.RecoveryPlanning.RecoveryHazards;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentTransportBridge;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentUiBridge;
import dev.aod.mcmcp.voice.SimpleVoiceChat2622Adapter;
import dev.aod.mcmcp.voice.VoiceChatEventBridge;
import dev.aod.mcmcp.voice.VoiceChatSafetyController;
import dev.aod.mcmcp.voice.VoiceTransmissionGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Client runtime and the sole implementation of the MCP-to-Minecraft boundary. */
public final class McmcpRuntime implements McpRuntimePort, EvaluationTurnControl {
    static final int MAX_ACTION_INPUT_RELEASE_ATTEMPTS = 3;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String MCP_PROTOCOL_VERSION = "2026-07-28";
    private static final long ACTION_DELIVERY_CONFIRM_NANOS = Duration.ofSeconds(5).toNanos();

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final DeterministicAStar agentPathfinder = new DeterministicAStar();
    private final AgentActionStore agentActions = new AgentActionStore();
    private final WorldMemory memory = new WorldMemory();
    private final MinecraftObservationService observations = new MinecraftObservationService(memory);
    private final BlockPlanComparator blockPlans = new BlockPlanComparator(observations, memory);
    private final ClientRecipeCatalog recipeCatalog = new ClientRecipeCatalog();
    private final ScreenOwnershipSignals screenOwnership = ScreenOwnershipSignals.global();
    private final KnownMenuOperationRefs knownMenuOperationRefs = new KnownMenuOperationRefs();
    private final FishingSessionRefs fishingSessionRefs = new FishingSessionRefs();
    private final ScopedEntityAttackConsentStore entityAttackConsent =
            new ScopedEntityAttackConsentStore();
    private final LocalArmingState arming = new LocalArmingState();
    private final InputReleaseController inputRelease = new InputReleaseController();
    private final EvaluationLeaseController evaluationControl;
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final ClientReconciliationSignals reconciliationSignals;
    private final AgentObservations agentObservations;
    private final ActionAdmission actionAdmission;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final MinecraftFrameItemPort frameItemPort;
    private final MinecraftApplyBlockPlanPort applyBlockPlanPort;
    private final MinecraftPillarUpPort pillarUpPort;
    private final MinecraftKnownBrewingPort knownBrewingPort;
    private final MinecraftKnownFurnacePort knownFurnacePort;
    private final MinecraftKnownMenuPort knownMenuPort;
    private final MinecraftPhaseFiveInventoryPort phaseFiveInventoryPort;
    private final MinecraftPhaseFiveWorldPort phaseFiveWorldPort;
    private final PhaseFivePortRouter phaseFivePort;
    private final MinecraftFinitePlanPort finitePlanPort;
    private final RoutineManager routines;
    private final RoutineLifecycle routineLifecycle;
    private final RoutineAdmission routineAdmission;
    private final VoiceChatSafetyController voiceChat;
    private final ClientCommandInbox inbox;
    private AgentExecution agentExecution;
    private boolean pendingAgentInputRelease;
    private boolean agentInputReleaseFaultLogged;
    private boolean pendingAgentReturnReady;
    private long agentControlOwnershipEpoch;
    private long lastStatefulAgentCleanupClientTick = Long.MIN_VALUE;
    private long lastStatefulAgentCleanupOwnershipEpoch = Long.MIN_VALUE;
    private AgentCleanupProgress lastStatefulAgentCleanup =
            new AgentCleanupProgress(true, true);
    private PendingAgentTerminal pendingAgentTerminal;
    private PendingAgentAdmission pendingAgentAdmission;
    private MinecraftRecoveryGovernor recoveryGovernor;
    private final RecoveryDescentTracker recoveryDescent = new RecoveryDescentTracker();
    private long pauseStartedAtNanos;
    private AutomationIndicatorController entityAttackConsentUi;

    private volatile WorldSessionTracker.Snapshot publishedSession = sessions.snapshot();
    private volatile boolean paused;
    private volatile Thread clientThread;
    private volatile String endpointFaultCode;
    private String transientMultiplayerConsentAddress;
    private volatile boolean shutdown;

    public McmcpRuntime(String modVersion, String neoForgeVersion) {
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
        this.neoForgeVersion = Objects.requireNonNull(neoForgeVersion, "neoForgeVersion");
        KnownMenuProfileSupport.initializeModProfiles();
        stationaryBreakPort = new MinecraftStationaryBreakPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global());
        reconciliationSignals = ClientReconciliationSignals.global();
        agentObservations = new AgentObservations(memory, sessions, observations, reconciliationSignals);
        semanticActionPort = new MinecraftSemanticActionPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global(),
                reconciliationSignals);
        frameItemPort = new MinecraftFrameItemPort(
                Minecraft::getInstance, sessions::snapshot, observations,
                FrameDisplaySyncSignals.global());
        applyBlockPlanPort = new MinecraftApplyBlockPlanPort(
                Minecraft::getInstance,
                sessions::snapshot,
                memory,
                observations,
                ClientPredictionSignals.global(),
                reconciliationSignals);
        pillarUpPort = new MinecraftPillarUpPort(
                Minecraft::getInstance,
                sessions::snapshot,
                observations,
                ClientPredictionSignals.global(),
                reconciliationSignals);
        knownBrewingPort = new MinecraftKnownBrewingPort(
                Minecraft::getInstance,
                sessions::snapshot,
                observations,
                screenOwnership,
                ContainerSyncSignals.global());
        knownFurnacePort = new MinecraftKnownFurnacePort(
                Minecraft::getInstance,
                sessions::snapshot,
                observations,
                screenOwnership,
                ContainerSyncSignals.global(),
                ClientPredictionSignals.global(),
                recipeCatalog);
        knownMenuPort = new MinecraftKnownMenuPort(
                Minecraft::getInstance,
                sessions::snapshot,
                ContainerSyncSignals.global(),
                knownMenuOperationRefs);
        phaseFiveInventoryPort = new MinecraftPhaseFiveInventoryPort(
                Minecraft::getInstance,
                sessions::snapshot,
                recipeCatalog,
                observations,
                screenOwnership,
                () -> McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0D,
                ClientPredictionSignals.global(), this::initialContainerOpenWitness);
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
        actionAdmission = new ActionAdmission(arming, agentObservations, reconciliationSignals,
                recipeCatalog, agentPathfinder,
                () -> pendingAgentInputRelease || agentExecution != null
                        || agentActions.active().isPresent() || routines.activeRoutineId().isPresent(),
                this::multiplayerPolicyAllows, this::publishRendererRecovery);
        voiceChat = new VoiceChatSafetyController(
                SimpleVoiceChat2622Adapter.forNeoForge(() -> {
                    var minecraft = Minecraft.getInstance();
                    return minecraft != null && minecraft.isSameThread();
                }),
                VoiceTransmissionGuard.GLOBAL,
                VoiceChatEventBridge.GLOBAL,
                true,
                this::requestSafetyStop);
        routineLifecycle = new RoutineLifecycle(routines, sessions, arming, inputRelease,
                voiceChat, observations, screenOwnership, this::returnControlReady,
                context -> requireLiveCall(context, "start_routine"));
        routineAdmission = new RoutineAdmission(routines, routineLifecycle, arming,
                stationaryBreakPort, semanticActionPort, finitePlanPort);
        inbox = new ClientCommandInbox(
                ClientCommandInbox.DEFAULT_CAPACITY,
                inputRelease,
                arming,
                this::stopActiveRoutineForEmergency);
        evaluationControl = new EvaluationLeaseController(sessions, () -> publishedSession,
                arming, inputRelease, inbox, () -> shutdown,
                () -> !paused && !Minecraft.getInstance().isPaused()
                        && endpointFaultCode == null,
                this::automationActivityPending, this::evaluationActionsTerminal);
    }

    public void onResourcesReady() {
        sessions.resourcesReady();
        publishSession();
    }

    public void onLoggingIn(Minecraft minecraft) {
        assertClientThread(minecraft);
        transientMultiplayerConsentAddress = null;
        evaluationControl.terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
        clearAgentSessionState();
        stopForLifecycle(minecraft, "world_join");
        routines.clearSession("world_join");
        routineLifecycle.clearSession();
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
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
        transientMultiplayerConsentAddress = null;
        evaluationControl.terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
        clearAgentSessionState();
        stopForLifecycle(minecraft, "level_or_dimension_change");
        routines.clearSession("level_or_dimension_change");
        routineLifecycle.clearSession();
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
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
        transientMultiplayerConsentAddress = null;
        evaluationControl.terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
        clearAgentSessionState();
        stopForLifecycle(minecraft, "disconnect");
        routines.clearSession("disconnect");
        routineLifecycle.clearSession();
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
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
        transientMultiplayerConsentAddress = null;
        evaluationControl.terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
        clearAgentSessionState();
        stopForLifecycle(minecraft, "player_respawn");
        routines.clearSession("player_respawn");
        routineLifecycle.clearSession();
        ClientPredictionSignals.global().resetAttemptsForPlayerClone(minecraft.level);
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
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
        routineLifecycle.onPauseChanged(paused, nowNanos);
        if (paused) {
            pauseStartedAtNanos = nowNanos;
            releaseAgentInputsForHold(minecraft, "pause_input_release_failed");
        } else {
            long pausedNanos = ActionBudgets.nonNegativeNanoElapsed(pauseStartedAtNanos, nowNanos);
            if (agentExecution != null) {
                agentExecution.pausedNanos = ActionBudgets.saturatingNonNegativeAdd(
                        agentExecution.pausedNanos, pausedNanos);
            }
            pauseStartedAtNanos = 0L;
        }
        this.paused = paused;
    }

    /** Accepts only copied position-sound values and performs a bounded enqueue. */
    public void onPositionSoundEvent(
            String soundEvent,
            SoundSource source,
            double x,
            double y,
            double z) {
        agentObservations.soundPlaybacks().capturePositionSound(soundEvent, source, x, y, z);
    }

    public void onPreTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        clientThread = Thread.currentThread();
        if (shutdown) {
            return;
        }
        boolean pendingReleaseClockAdvanced = advancePendingAgentReleaseClock();
        try {
            // Account the final movement sample before any control-lane stop can terminalize the
            // Action. Stateful cleanup may sample again after restoring camera ownership, but
            // the updated baseline makes that same-tick sample exactly zero.
            recordPendingAgentMotion(minecraft);
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP pre-tick motion accounting failed; stopping automation before input reuse",
                    failure);
            inbox.requestEmergencyStop("observation_pipeline_failed");
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
            publishSession();
            return;
        }
        evaluationControl.finishPendingEvaluationTerminalOnClient(minecraft);
        evaluationControl.terminateInvalidEvaluationLeaseOnClient(minecraft);
        var evaluationSession = sessions.snapshot();
        if (evaluationControl.snapshot(evaluationSession.worldSessionId()).active()
                && evaluationSession.worldReady()
                && !localControlAvailable(minecraft, evaluationSession)) {
            evaluationControl.terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.PLAYER_UNAVAILABLE);
        }
        boolean pendingReleaseCompleted = retryPendingAgentInputRelease(minecraft);
        // A retained stop waiter owns the priority lane even when the shared release retry
        // remains pending or fails closed. Drain it before any ordinary world/action work.
        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        if (!pendingReleaseCompleted || pendingAgentInputRelease) {
            // Cancellation still needs a client-thread reply while the exact cleanup owner is
            // retained. New actions remain denied by the active-action/admission fences.
            inbox.drainControlsPreTick(sessions.snapshot());
            publishSession();
            return;
        }
        try {
            synchronizeWorld(minecraft);
            var frameSession = sessions.snapshot();
            if (frameSession.worldReady() && minecraft.level != null) {
                FrameDisplaySyncSignals.global().bindAndSnapshot(
                        minecraft.level, frameSession.worldSessionId());
            }
            trackRecoveryDescent(minecraft);
            if (!pendingReleaseClockAdvanced) {
                sessions.tick();
            }
            agentObservations.synchronizeKnownTraversability(minecraft);
            agentObservations.collectAgentObservation(minecraft);
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
        routineLifecycle.retryPendingFinalizations(minecraft);
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
        entityAttackConsent.clear();
        if (anyActive()) {
            evaluationControl.terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.LOCAL_ESCAPE);
        } else {
            runPriorityStop(
                    inbox::requestLocalEmergencyStop,
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        }
        routineLifecycle.clearContinuation();
        overlay(minecraft, "MCMCP: 現在の操作を緊急停止（MCP操作はON）");
    }

    /** Read-only, client-thread view used by the local HUD and pause-menu indicator. */
    public AutomationUiSnapshot automationUiSnapshot() {
        var session = sessions.snapshot();
        var lock = arming.snapshot(session.worldSessionId());
        var consent = entityAttackConsentSnapshot(session, lock);
        boolean localConsentPending = localEntityAttackConsentPending(
                consent.state(), consent.channel());
        return AutomationUiSnapshot.resolve(
                localControlAvailable(Minecraft.getInstance(), session),
                lock,
                evaluationControl.snapshot(session.worldSessionId()).active(),
                localConsentPending,
                !localConsentPending
                        ? null
                        : String.join(", ", consent.scope().entityTypeAllowlist()),
                endpointFaultCode);
    }

    static boolean localEntityAttackConsentPending(
            ScopedEntityAttackConsentStore.State state,
            ScopedEntityAttackConsentStore.Channel channel) {
        Objects.requireNonNull(state, "state");
        return state == ScopedEntityAttackConsentStore.State.PENDING
                && channel == ScopedEntityAttackConsentStore.Channel.LOCAL_UI;
    }

    /** Bootstrap-only local presentation binding; the runtime never exposes the grant sink. */
    public void installEntityAttackConsentUi(AutomationIndicatorController controller) {
        Objects.requireNonNull(controller, "controller");
        if (entityAttackConsentUi != null && entityAttackConsentUi != controller) {
            throw new IllegalStateException("entity attack consent UI is already installed");
        }
        entityAttackConsentUi = controller;
    }

    /** Future internal admission hook; not mapped to an MCP Tool or public command. */
    ScopedEntityAttackConsentStore.RequestResult requestEntityAttackConsentForCanonicalAction(
            String policyBindingHash,
            ScopedEntityAttackConsentStore.Scope scope) {
        return requestEntityAttackConsentForCanonicalAction(policyBindingHash, scope, true);
    }

    private ScopedEntityAttackConsentStore.RequestResult
            requestEntityAttackConsentForCanonicalAction(
                    String policyBindingHash,
                    ScopedEntityAttackConsentStore.Scope scope,
                    boolean openLocalPrompt) {
        Objects.requireNonNull(scope, "scope");
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        var lock = arming.snapshot(session.worldSessionId());
        if (shutdown
                || endpointFaultCode != null
                || !localControlAvailable(minecraft, session)
                || lock.mode() != LocalArmingState.Mode.READY
                || !Objects.equals(session.dimension(), scope.dimension())
                || agentActions.active().isPresent()
                || routines.activeRoutineId().isPresent()
                || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || openLocalPrompt && minecraft.gui.screen() != null
                || openLocalPrompt && entityAttackConsentUi == null) {
            throw new IllegalStateException("entity attack consent admission is not ready");
        }
        var sessionId = Objects.requireNonNull(session.worldSessionId(), "worldSessionId");
        var result = entityAttackConsent.request(
                sessionId,
                policyBindingHash,
                scope,
                openLocalPrompt
                        ? ScopedEntityAttackConsentStore.Channel.LOCAL_UI
                        : ScopedEntityAttackConsentStore.Channel.TRANSPORT,
                session.clientTick());
        if (openLocalPrompt
                && result == ScopedEntityAttackConsentStore.RequestResult.REGISTERED) {
            try {
                entityAttackConsentUi.openEntityAttackConsentPrompt(
                        scope,
                        new EntityAttackConsentPromptSink(sessionId, policyBindingHash, scope));
            } catch (RuntimeException | LinkageError failure) {
                entityAttackConsent.clear();
                throw failure;
            }
        }
        return result;
    }

    private final class EntityAttackConsentPromptSink
            implements AutomationIndicatorController.EntityAttackConsentPromptSink {
        private final UUID sessionId;
        private final String policyBindingHash;
        private final ScopedEntityAttackConsentStore.Scope scope;
        private boolean terminal;

        private EntityAttackConsentPromptSink(
                UUID sessionId,
                String policyBindingHash,
                ScopedEntityAttackConsentStore.Scope scope) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.policyBindingHash = Objects.requireNonNull(
                    policyBindingHash, "policyBindingHash");
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        @Override
        public boolean grantFromPhysicalPrimaryClick() {
            var minecraft = Minecraft.getInstance();
            assertClientThread(minecraft);
            if (terminal || !pending()) {
                terminal = true;
                entityAttackConsent.clear();
                return false;
            }
            var session = sessions.snapshot();
            boolean granted = ScopedEntityAttackConsentUiBridge
                    .grantFromPhysicalPromptClick(
                            entityAttackConsent, sessionId, session.clientTick());
            terminal = true;
            if (!granted) {
                entityAttackConsent.clear();
                return false;
            }
            overlay(minecraft, "MCMCP: 範囲付き反復攻撃の開始を3分間許可しました");
            return true;
        }

        @Override
        public void cancel() {
            var minecraft = Minecraft.getInstance();
            assertClientThread(minecraft);
            if (terminal) {
                return;
            }
            terminal = true;
            entityAttackConsent.clear();
            emergencyStopFromLocalKey(minecraft);
        }

        @Override
        public boolean pending() {
            var minecraft = Minecraft.getInstance();
            assertClientThread(minecraft);
            if (terminal) {
                return false;
            }
            var session = sessions.snapshot();
            var control = arming.snapshot(session.worldSessionId());
            var snapshot = entityAttackConsentSnapshot(session, control);
            return snapshot.state() == ScopedEntityAttackConsentStore.State.PENDING
                    && snapshot.channel() == ScopedEntityAttackConsentStore.Channel.LOCAL_UI
                    && sessionId.equals(session.worldSessionId())
                    && policyBindingHash.equals(snapshot.policyBindingHash())
                    && scope.equals(snapshot.scope());
        }
    }

    /** May be called by the endpoint lifecycle worker; client-thread cleanup uses the priority lane. */
    public void reportEndpointFault(String code) {
        endpointFaultCode = RuntimeFailures.sanitizeLocalCode(code);
        entityAttackConsent.clear();
        inbox.requestEmergencyStop("endpoint_fault");
        evaluationControl.snapshot(publishedSession.worldSessionId()).activeLease()
                .ifPresent(lease -> evaluationControl.requestEvaluationReleaseFromAnyThread(
                        lease.leaseId(), EvaluationTurnControl.ReleaseReason.ENDPOINT_FAULT));
    }

    public void clearEndpointFault() {
        endpointFaultCode = null;
    }

    /** Uses the same priority lane as Esc so a UI stop releases every owned input inline. */
    public void disableAutomationFromUi(Minecraft minecraft) {
        assertClientThread(minecraft);
        transientMultiplayerConsentAddress = null;
        entityAttackConsent.clear();
        if (anyActive()) {
            evaluationControl.terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.LOCAL_UI_DISABLED);
        } else {
            runPriorityStop(
                    inbox::requestLocalDisable,
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        }
        arming.lock("local_ui_disabled");
        routineLifecycle.clearContinuation();
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
        routineLifecycle.resetContinuation(session.worldSessionId());
        arming.arm(session.worldSessionId(), availableCapabilities(minecraft));
        overlay(minecraft, "MCMCP: このワールドでMCP自動操作を再許可しました");
        return true;
    }

    public boolean enableAutomationFromUi() {
        return enableAutomationFromUi(Minecraft.getInstance());
    }

    /** Grants only the current armed period; disabling or leaving the level clears it. */
    public boolean enableAutomationForCurrentMultiplayerSessionFromUi(
            Minecraft minecraft, String address) {
        assertClientThread(minecraft);
        var server = minecraft.getCurrentServer();
        if (!minecraft.isMultiplayerServer()
                || server == null
                || !MultiplayerAllowlist.sameAddress(address, server.ip)) {
            return false;
        }
        transientMultiplayerConsentAddress = address;
        if (enableAutomationFromUi(minecraft)) {
            return true;
        }
        transientMultiplayerConsentAddress = null;
        return false;
    }

    static boolean localControlAvailable(
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
                || pendingAgentInputRelease
                || pendingAgentTerminal != null
                || agentExecution != null
                || pendingAgentAdmission != null
                || inbox.hasPendingCommand("start_routine")
                || inbox.hasPendingCommand("agent_start_action")
                || routineLifecycle.hasPendingFinalizations()
                || routineLifecycle.hasVoiceOwner();
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

    static void clearAutomationPortSessions(
            Runnable stationaryBreakClear,
            Runnable semanticActionClear,
            Runnable applyBlockPlanClear,
            Runnable pillarUpClear) {
        clearAutomationPortSessions(
                stationaryBreakClear, semanticActionClear, applyBlockPlanClear);
        clearAutomationPortSession("pillar_up", pillarUpClear);
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
        clearAutomationPortSession("frame_item", frameItemPort::clearSession);
        clearAutomationPortSession("finite_plan", finitePlanPort::clearSession);
        clearAutomationPortSession("known_brewing", knownBrewingPort::clearSession);
        clearAutomationPortSession("known_furnace", knownFurnacePort::clearSession);
        clearAutomationPortSession("known_menu", knownMenuPort::clearSession);
        clearAutomationPortSession("known_menu_refs", knownMenuOperationRefs::clear);
        clearAutomationPortSession("phase_five_inventory", phaseFiveInventoryPort::clearSession);
        clearAutomationPortSession("phase_five_world", phaseFiveWorldPort::clearSession);
        clearAutomationPortSession("phase_five_router", phaseFivePort::clearSession);
    }

    private void clearAgentSessionState() {
        FrameDisplaySyncSignals.global().clear();
        entityAttackConsent.clear();
        recoveryDescent.reset();
        agentObservations.clearSession();
        fishingSessionRefs.clear();
        var activeAction = agentActions.active();
        PendingAgentTerminal worldBoundaryTerminal = activeAction
                .map(action -> PendingAgentTerminal.failure(
                        action.actionId(),
                        new AgentActionStore.Failure(
                                AgentActionStore.FailureCode.WORLD_CHANGED,
                                true,
                                List.of("world_boundary"))))
                .orElse(null);
        boolean inputsReleased = false;
        try {
            inputsReleased = closeAgentControl(Minecraft.getInstance(), "world_boundary");
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP action input release failed", failure);
        }
        if (worldBoundaryTerminal != null) {
            if (inputsReleased) {
                publishAgentTerminal(worldBoundaryTerminal);
            } else {
                rememberPendingAgentTerminal(worldBoundaryTerminal);
            }
        }
        agentObservations.resetObserver();
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
        evaluationControl.terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.CLIENT_SHUTDOWN);
        shutdown = true;
        entityAttackConsent.clear();
        clearAgentSessionState();
        sessions.stopping();
        inbox.shutdown(minecraft, sessions.snapshot());
        routines.clearSession("client_shutdown");
        routineLifecycle.clearSession();
        voiceChat.close();
        clearAutomationPortSessions(
                stationaryBreakPort::clearSession,
                semanticActionPort::clearSession,
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
        clearPhaseFivePortSessions();
        ClientPredictionSignals.global().closeLevel(minecraft.level);
        reconciliationSignals.closeLevel(minecraft.level);
        screenOwnership.clearLevel(minecraft.level);
        recipeCatalog.detachSession();
        memory.detachSession();
        arming.lock("client_shutdown");
        publishSession();
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> acquire(
            EvaluationTurnControl.AcquireRequest request) {
        return evaluationControl.acquire(request);
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> await(UUID leaseId) {
        return evaluationControl.await(leaseId);
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> release(
            UUID leaseId, EvaluationTurnControl.ReleaseReason reason) {
        return evaluationControl.release(leaseId, reason);
    }

    @Override
    public boolean active(UUID leaseId) { return evaluationControl.active(leaseId); }

    @Override
    public boolean anyActive() { return evaluationControl.anyActive(); }

    @Override
    public EvaluationTurnControl.FenceSnapshot fenceSnapshot() {
        return evaluationControl.fenceSnapshot();
    }

    private boolean evaluationActionsTerminal() {
        return agentActions.active().isEmpty()
                && routines.activeRoutineId().isEmpty()
                && pendingAgentTerminal == null
                && !pendingAgentInputRelease
                && agentExecution == null
                && pendingAgentAdmission == null
                && !routineLifecycle.hasPendingFinalizations()
                && !routineLifecycle.hasVoiceOwner()
                && !inbox.hasPendingCommand("start_routine")
                && !inbox.hasPendingCommand("agent_start_action");
    }

    @Override
    public CompletionStage<RuntimeReply> submit(RuntimeCommand command, RuntimeCallContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (command instanceof EmergencyStop stop) {
            var fence = publishedSession;
            java.util.concurrent.Callable<CompletionStage<RuntimeReply>> emergency = () ->
                    withEvaluationLeaseFence(context, command.toolName(), () -> {
                        var minecraft = Minecraft.getInstance();
                        assertClientThread(minecraft);
                        var stopped = inbox.requestEmergencyStop(stop.reason());
                        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
                        return stopped.handle((receipt, failure) -> failure == null
                                ? RuntimeReply.success(Map.of(
                                        "stop_requested", true,
                                        "locked", receipt.locked(),
                                        "released_inputs", receipt.inputsReleased(),
                                        "discarded_pending_starts",
                                        receipt.discardedPendingStarts()))
                                : RuntimeFailures.mapFailure(failure));
                    });
            return inbox.submitControlMapped(
                    command.toolName(),
                    fence.generation(),
                    context.deadlineNanos(),
                    emergency,
                    failure -> CompletableFuture.completedFuture(RuntimeFailures.mapFailure(failure)))
                    .thenCompose(stage -> stage);
        }
        if (!context.canBeginWork()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    RuntimeReply.failure("server_busy", "The client-thread deadline expired", true));
        }
        if (command instanceof StartAction start) {
            return submitPreparedAgentStart(start, context);
        }
        if (command instanceof GetAction action) {
            return submitAgentGetAction(action, context);
        }

        var fence = publishedSession;
        java.util.concurrent.Callable<RuntimeReply> work = () ->
                withEvaluationLeaseFence(context, command.toolName(), () -> {
            if (command instanceof GetObservation observation) {
                var minecraft = Minecraft.getInstance();
                assertClientThread(minecraft);
                var session = sessions.snapshot();
                RuntimeFailures.requireReady(session);
                PreparedObservationPage prepared = agentObservations.getAgentObservation(observation.arguments());
                return RuntimeReply.success(
                        prepared.wirePage(),
                        new McpRuntimePort.ObservationDeliveryReceipt(prepared.receiptId()));
            }
            return RuntimeReply.success(executeOnClientThread(command, context));
        });
        var submitted = command instanceof CancelRoutine
                || command instanceof CancelAction
                || command instanceof ConfirmActionDelivery
                || command instanceof AbandonActionDelivery
                || command instanceof ConfirmObservationDelivery
                || command instanceof AbandonObservationDelivery
                ? inbox.submitControlMapped(
                        command.toolName(), fence.generation(), context.deadlineNanos(),
                        work, RuntimeFailures::mapFailure)
                : inbox.submitMapped(
                        command.toolName(), fence.generation(), context.deadlineNanos(),
                        work, RuntimeFailures::mapFailure, () -> false,
                        agentObservations::abandonUnconfirmedDelivery);
        return submitted;
    }

    /**
     * Handles the optional terminal wait on the calling MCP worker. AgentActionStore is a
     * synchronized, Minecraft-independent state machine, so no client-thread dispatch or game
     * state access is needed while this read-only request waits.
     */
    private CompletionStage<RuntimeReply> submitAgentGetAction(
            GetAction command, RuntimeCallContext context) {
        try {
            UUID requestedId = RuntimeArguments.actionId(command.arguments());
            int requestedWaitMillis = agentActionWaitTimeoutMillis(command.arguments());
            var containerQuery = ContainerInspection.Query.parse(command.arguments());
            if (requestedWaitMillis > 0 && Thread.currentThread() == clientThread) {
                return CompletableFuture.completedFuture(RuntimeReply.failure(
                        "internal_error",
                        "Action terminal waits cannot run on the Minecraft client thread",
                        true));
            }
            requireLiveCall(context, command.toolName());
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(context.remainingNanos());
            int effectiveWaitMillis = (int) Math.min(
                    requestedWaitMillis, Math.min(Integer.MAX_VALUE, remainingMillis));
            AgentActionStore.Snapshot snapshot = agentActions.awaitTerminal(
                    requestedId, effectiveWaitMillis);
            RuntimeReply reply = withEvaluationLeaseFence(
                    context,
                    command.toolName(),
                    () -> RuntimeReply.success(ActionWireMapper.actionPayload(snapshot, containerQuery)));
            return CompletableFuture.completedFuture(reply);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            context.cancel();
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "server_busy", "The action terminal wait was interrupted", true));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(RuntimeFailures.mapFailure(failure));
        }
    }

    private CompletionStage<RuntimeReply> submitPreparedAgentStart(
            StartAction command, RuntimeCallContext context) {
        if (Thread.currentThread() == clientThread) {
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "internal_error", "Agent preflight cannot block the client thread", true));
        }
        final ActionDsl.Request request;
        final ActionDslSource source;
        final PredicateRequirements predicateRequirements;
        final boolean localSafetyRequired;
        try {
            var sourceObject = GSON.toJsonTree(command.arguments()).getAsJsonObject();
            request = ActionDslParser.parse(sourceObject);
            ActionDslValidator.validate(request);
            source = ActionDslSource.capture(sourceObject);
            predicateRequirements = ActionPredicates.predicateRequirements(request.program());
            localSafetyRequired = ActionPlanning.actionAdmissionRequiresLocalSafety(request.program());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(RuntimeFailures.mapFailure(failure));
        }
        var fence = publishedSession;
        var surfaceRecovery = new SurfacePreflightRecovery(request.budget());
        // Use the same pre-tick observation phase as execution. After the player tick,
        // the previous renderer fog sample cannot authorize fresh visual evidence.
        var capture = inbox.submitControl(
                command.toolName(),
                fence.generation(),
                context.deadlineNanos(),
                () -> withEvaluationLeaseFence(
                        context,
                        command.toolName(),
                        () -> actionAdmission.captureAgentAdmission(
                                Minecraft.getInstance(),
                                sessions.snapshot(),
                                predicateRequirements,
                                localSafetyRequired,
                                ActionPlanning.containsRecipeReference(request.program()),
                                request.program(), surfaceRecovery)),
                context::isCancelled, ignored -> { });
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
            if (surfaceRecovery.hasWaited()) {
                return CompletableFuture.completedFuture(ActionAdmission.mapAdmissionFailure(failure, surfaceRecovery));
            }
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "server_busy", "Agent preflight capture timed out", true));
        } catch (ExecutionException | RuntimeException failure) {
            return CompletableFuture.completedFuture(ActionAdmission.mapAdmissionFailure(failure, surfaceRecovery));
        }

        final PreparedAgentAction prepared;
        try {
            requireLiveCall(context, command.toolName());
            prepared = actionAdmission.prepareAgentAction(request, source, snapshot, context, surfaceRecovery);
            requireLiveCall(context, command.toolName());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(RuntimeFailures.mapFailure(failure));
        }

        java.util.concurrent.Callable<RuntimeReply> commit = () ->
                withEvaluationLeaseFence(context, command.toolName(), () ->
                        RuntimeReply.success(commitAgentAction(
                                Minecraft.getInstance(),
                                sessions.snapshot(),
                                prepared,
                                context)));
        return inbox.submitControlMapped(
                command.toolName(),
                snapshot.session().generation(),
                context.deadlineNanos(),
                commit,
                failure -> ActionAdmission.mapAdmissionFailure(failure, surfaceRecovery),
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
            case GetState state -> status(minecraft, session, state.arguments());
            case GetObservation ignored ->
                    throw new AssertionError("agent_get_observation must stage delivery metadata");
            case StartAction action -> {
                throw new AssertionError("agent_start_action must use worker preflight");
            }
            case GetAction action -> getAgentAction(action.arguments());
            case CancelAction action -> cancelAgentAction(minecraft, action.arguments());
            case ConfirmActionDelivery delivery -> confirmAgentActionDelivery(delivery.actionId());
            case AbandonActionDelivery delivery -> abandonAgentActionDelivery(delivery.actionId());
            case ConfirmObservationDelivery delivery -> Map.of(
                    "confirmed", agentObservations.deliveredEvidence().confirmDelivery(delivery.receiptId()));
            case AbandonObservationDelivery delivery -> Map.of(
                    "abandoned", agentObservations.deliveredEvidence().abandonDelivery(delivery.receiptId()));
            case GetSnapshot snapshot -> {
                RuntimeFailures.requireReady(session);
                yield observations.getSnapshot(minecraft, session.clientTick(), snapshot.arguments());
            }
            case CompareBlockPlan compare -> {
                RuntimeFailures.requireReady(session);
                yield blockPlans.compare(minecraft, session.clientTick(), compare.arguments());
            }
            case GetRecipes getRecipes -> {
                RuntimeFailures.requireReady(session);
                yield getRecipes(minecraft, session, getRecipes.arguments());
            }
            case ListRoutines list -> routineAdmission.listRoutines(list.arguments());
            case GetRoutine get -> routineAdmission.getRoutine(get.arguments());
            case StartRoutine start -> {
                RuntimeFailures.requireReady(session);
                yield routineAdmission.startRoutine(minecraft, session, start.arguments(), context);
            }
            case CancelRoutine cancel -> routineAdmission.cancelRoutine(minecraft, cancel.arguments());
            case EmergencyStop ignored -> throw new AssertionError("emergency stop bypasses the normal queue");
        };
    }

    private Map<String, Object> getRecipes(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments) {
        RuntimeArguments.requireExactKeys(arguments, "get_recipes", Set.of("query", "max_results"));
        int maxResults = RuntimeArguments.intArgument(arguments, "max_results");
        if (maxResults < 1 || maxResults > 64) {
            throw new IllegalArgumentException("max_results must be in 1..64");
        }
        Map<String, Object> queryInput = RuntimeArguments.objectArgument(arguments, "query");
        String kind = RuntimeArguments.stringArgument(queryInput, "kind");
        ClientRecipeCatalog.Query query = switch (kind) {
            case "result_item" -> {
                RuntimeArguments.requireExactKeys(queryInput, "get_recipes query", Set.of("kind", "item"));
                yield new ClientRecipeCatalog.Query(
                        ClientRecipeCatalog.QueryKind.RESULT_ITEM,
                        RuntimeArguments.stringArgument(queryInput, "item"));
            }
            case "result_tag" -> {
                RuntimeArguments.requireExactKeys(queryInput, "get_recipes query", Set.of("kind", "tag"));
                yield new ClientRecipeCatalog.Query(
                        ClientRecipeCatalog.QueryKind.RESULT_TAG,
                        RuntimeArguments.stringArgument(queryInput, "tag"));
            }
            default -> throw new IllegalArgumentException("get_recipes query kind is unsupported");
        };
        recipeCatalog.refreshFromClient(
                minecraft, Objects.requireNonNull(session.worldSessionId(), "worldSessionId"), session.clientTick());
        return recipeCatalog.query(session.worldSessionId(), query, maxResults).toMap();
    }

    private boolean multiplayerPolicyAllows(Minecraft minecraft) {
        if (!minecraft.isMultiplayerServer()) return true;
        var server = minecraft.getCurrentServer();
        return server != null
                && (MultiplayerAllowlist.sameAddress(
                                transientMultiplayerConsentAddress, server.ip)
                        || MultiplayerAllowlist.allows(
                                minecraft.gameDirectory.toPath()
                                        .resolve("config/mcmcp/allowed-servers.json"),
                                server.ip));
    }

    private Map<String, Object> commitAgentAction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PreparedAgentAction prepared,
            RuntimeCallContext context) {
        assertClientThread(minecraft);
        var captured = prepared.snapshot();
        if (pendingAgentInputRelease || agentExecution != null
                || agentActions.active().isPresent() || routines.activeRoutineId().isPresent()) {
            throw new RuntimeInvocationException(
                    "task_busy", "Another action is already queued or running.", true, Map.of());
        }
        var admissionFailure = actionAdmission.admissionFenceFailure(
                minecraft,
                session,
                prepared,
                LocalArmingState.Mode.READY,
                captured.control().controlEpoch(), RendererRecoveryStage.COMMIT);
        if (admissionFailure.isPresent()) {
            if (admissionFailure.orElseThrow() == AdmissionFenceFailure.RENDERER_EVIDENCE_MISSING) {
                throw new ClientCommandInbox.DeferControl();
            }
            throw ActionAdmission.admissionPreflightFailure(admissionFailure.orElseThrow());
        }
        requireLiveCall(context, "agent_start_action");
        ActionDsl.OperateKillZone killZone = KillZoneSafety.soleKillZone(prepared.program().request().program());
        KillZoneAdmission killZoneAdmission = null;
        ScopedEntityAttackConsentTransportBridge.ResponseCapability transportApproval = null;
        if (killZone != null) {
            killZoneAdmission = requireKillZoneAdmission(
                    minecraft, session, prepared.source(), killZone);
            if (killZone.consentRef().isEmpty()) {
                KillZoneElicitationDecision decision = killZoneElicitationDecision(
                        context.elicitationInput());
                if (decision == KillZoneElicitationDecision.TRANSPORT_APPROVED
                        || decision == KillZoneElicitationDecision.REJECTED) {
                    var consent = entityAttackConsent.snapshot(
                            session.worldSessionId(), session.clientTick());
                    if (consent.state() != ScopedEntityAttackConsentStore.State.PENDING
                            || consent.channel()
                                    != ScopedEntityAttackConsentStore.Channel.TRANSPORT
                            || !Objects.equals(
                                    context.elicitationInput().requestState(),
                                    consent.approvalRequestState())
                            || !killZoneAdmission.policyBindingHash()
                                    .equals(consent.policyBindingHash())
                            || !killZoneAdmission.scope().equals(consent.scope())) {
                        throw new RuntimeInvocationException(
                                "consent_binding_mismatch",
                                "No matching pending kill-zone policy exists for this response.",
                                true,
                                Map.of());
                    }
                    var responseCapability = ScopedEntityAttackConsentTransportBridge
                            .bindTransportResponse(context.elicitationInput());
                    if (decision == KillZoneElicitationDecision.REJECTED) {
                        boolean rejected = ScopedEntityAttackConsentTransportBridge.rejectPending(
                                entityAttackConsent,
                                responseCapability,
                                Objects.requireNonNull(session.worldSessionId(), "worldSessionId"),
                                killZoneAdmission.policyBindingHash(),
                                killZoneAdmission.scope(),
                                session.clientTick());
                        if (!rejected) {
                            throw new RuntimeInvocationException(
                                    "consent_binding_mismatch",
                                    "The pending transport response was already handled or revoked.",
                                    true,
                                    Map.of());
                        }
                        throw new RuntimeInvocationException(
                                "capability_denied",
                                "The kill-zone operation was not approved by the user.",
                                false,
                                Map.of());
                    }
                    transportApproval = responseCapability;
                } else {
                    boolean openLocalPrompt =
                            decision == KillZoneElicitationDecision.FALLBACK_LOCAL_UI;
                    var request = requestEntityAttackConsentForCanonicalAction(
                            killZoneAdmission.policyBindingHash(),
                            killZoneAdmission.scope(),
                            openLocalPrompt);
                    if (request != ScopedEntityAttackConsentStore.RequestResult.REGISTERED
                            && request
                                    != ScopedEntityAttackConsentStore.RequestResult.ALREADY_PENDING) {
                        throw new RuntimeInvocationException(
                                "consent_unavailable",
                                "Another kill-zone consent request is active or the request clock changed.",
                                true,
                                Map.of("request_result", request.name().toLowerCase(Locale.ROOT)));
                    }
                    var pending = entityAttackConsent.snapshot(
                            session.worldSessionId(), session.clientTick());
                    return awaitingKillZoneConsentPayload(
                            killZoneAdmission.policyBindingHash(),
                            openLocalPrompt ? null : pending.approvalRequestState(),
                            killZoneAdmission.scope());
                }
            }
            if (transportApproval == null) {
                var consent = entityAttackConsent.snapshot(
                        session.worldSessionId(), session.clientTick());
                if (consent.state() != ScopedEntityAttackConsentStore.State.GRANTED
                        || !killZoneAdmission.policyBindingHash().equals(consent.policyBindingHash())
                        || !killZoneAdmission.scope().equals(consent.scope())
                        || !killZone.consentRef().orElseThrow().equals(consent.consentRef())) {
                    throw new RuntimeInvocationException(
                            "consent_binding_mismatch",
                            "The consent ref is missing, expired, or bound to a different kill-zone policy.",
                            true,
                            Map.of());
                }
            }
        }
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
                    prepared.source(),
                    Instant.now(),
                    System.nanoTime() + ACTION_DELIVERY_CONFIRM_NANOS);
            pendingAgentAdmission = new PendingAgentAdmission(
                    accepted.actionId(), prepared, killZoneAdmission,
                    transportApproval);
            publishRendererRecovery(prepared.surfaceRecovery());
            requireLiveCall(context, "agent_start_action");
        } catch (RuntimeException | LinkageError failure) {
            if (accepted == null) {
                returnControlReady();
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

    static KillZoneElicitationDecision killZoneElicitationDecision(
            RuntimeCallContext.ElicitationInput elicitation) {
        Objects.requireNonNull(elicitation, "elicitation");
        if (!elicitation.formSupported()) {
            return KillZoneElicitationDecision.FALLBACK_LOCAL_UI;
        }
        if (!elicitation.responded()) {
            return KillZoneElicitationDecision.AWAITING_TRANSPORT_RESPONSE;
        }
        return elicitation.acceptedAndConfirmed()
                ? KillZoneElicitationDecision.TRANSPORT_APPROVED
                : KillZoneElicitationDecision.REJECTED;
    }

    enum KillZoneElicitationDecision {
        FALLBACK_LOCAL_UI,
        AWAITING_TRANSPORT_RESPONSE,
        TRANSPORT_APPROVED,
        REJECTED
    }

    private static Map<String, Object> awaitingKillZoneConsentPayload(
            String policyBindingHash,
            String approvalRequestState,
            ScopedEntityAttackConsentStore.Scope scope) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schema_version", 1);
        payload.put("state", "AWAITING_CONSENT");
        payload.put("policy_binding_hash", policyBindingHash);
        payload.put("approval_request_state", approvalRequestState);
        payload.put("approval_scope_summary", killZoneApprovalScopeSummary(scope));
        payload.put("action_reserved", false);
        payload.put("input_acquired", false);
        return Collections.unmodifiableMap(payload);
    }

    private static String killZoneApprovalScopeSummary(
            ScopedEntityAttackConsentStore.Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return "ディメンション " + scope.dimension()
                + "、対象区域 " + conciseBounds(scope.targetKillZoneBounds())
                + "、待機位置 " + conciseBounds(scope.playerStationBounds());
    }

    private static String conciseBounds(ScopedEntityAttackConsentStore.Bounds bounds) {
        return String.format(
                Locale.ROOT,
                "X=%.3f〜%.3f / Y=%.3f〜%.3f / Z=%.3f〜%.3f",
                bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                bounds.minZ(), bounds.maxZ());
    }

    private KillZoneAdmission requireKillZoneAdmission(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDslSource source,
            ActionDsl.OperateKillZone operation) {
        assertClientThread(minecraft);
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        if (!session.worldReady()
                || !session.dimension().equals(operation.targetKillZoneBounds().dimension())
                || !player.onGround()
                || player.isCreative() || player.isSpectator()
                || minecraft.gameMode == null
                || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())) {
            throw new RuntimeInvocationException(
                    "unsafe_state", "Kill-zone consent requires a grounded survival player with no blocking Screen.",
                    true, Map.of());
        }
        String heldItem = BuiltInRegistries.ITEM.getKey(
                player.getMainHandItem().getItem()).toString();
        if (!operation.mainHandItem().equals(heldItem)) {
            throw new RuntimeInvocationException(
                    "target_changed", "The declared main-hand item is not currently held.",
                    true, Map.of());
        }
        AttackProfile profile = KillZoneSafety.requireKnownAttackProfile(player.getMainHandItem());
        if (player.getMainHandItem().isDamageableItem()
                && player.getMainHandItem().getMaxDamage()
                        - player.getMainHandItem().getDamageValue() < operation.maxAttacks()) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile",
                    "The unenchanted weapon needs at least max_attacks remaining durability.",
                    false, Map.of());
        }
        AABB box = player.getBoundingBox();
        var station = new ScopedEntityAttackConsentStore.Bounds(
                box.minX - 0.125D, box.minY - 0.0625D, box.minZ - 0.125D,
                box.maxX + 0.125D, box.maxY + 0.125D, box.maxZ + 0.125D);
        var raw = operation.targetKillZoneBounds();
        var zone = new ScopedEntityAttackConsentStore.Bounds(
                raw.min().x(), raw.min().y(), raw.min().z(),
                raw.max().x(), raw.max().y(), raw.max().z());
        KillZoneSafety.requireKillZoneBarrier(
                level, player, station, zone, operation.entityTypeAllowlist());
        String structure = KillZoneSafety.killZoneStructureFingerprint(level, station, zone);
        var scope = new ScopedEntityAttackConsentStore.Scope(
                session.dimension(),
                station,
                zone,
                operation.entityTypeAllowlist(),
                heldItem,
                profile.fingerprint(),
                structure,
                profile.sideEffects(),
                operation.maxAttacks(),
                operation.minimumIntervalTicks(),
                operation.maxOperationDurationTicks());
        var binding = new StringBuilder(source.consentBindingSha256());
        RoutineIdentity.appendIdentity(binding, scope.toString());
        return new KillZoneAdmission(RoutineIdentity.sha256Identity(binding), scope);
    }

    private void publishRendererRecovery(SurfacePreflightRecovery recovery) {
        if (recovery.lease() == null) return;
        var summary = recovery.summary();
        if (summary.missingStages() == 0) return;
        if (agentExecution != null && agentExecution.surfaceRecovery == recovery) {
            agentActions.recordRendererRecovery(agentExecution.actionId, summary);
        } else if (pendingAgentAdmission != null && pendingAgentAdmission.prepared().surfaceRecovery() == recovery) {
            agentActions.recordRendererRecovery(pendingAgentAdmission.actionId(), summary);
        }
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
            finishAgentControlReady(Minecraft.getInstance());
        }
    }

    private Map<String, Object> confirmAgentActionDelivery(UUID actionId) {
        var pending = pendingAgentAdmission;
        if (pending == null
                || !pending.actionId().equals(actionId)) {
            boolean abandoned;
            try {
                abandoned = agentActions.abandonUnconfirmed(
                        actionId, "delivery_confirmation_without_pending_admission");
            } catch (AgentActionStore.NotFoundException failure) {
                abandoned = false;
            }
            if (abandoned) {
                finishAgentControlReady(Minecraft.getInstance());
            }
            return Map.of("action_id", actionId.toString(), "confirmed", false);
        }
        // This step acknowledges that the already-built HTTP response was delivered; it is not a
        // second admission decision. Volatile pose/observation checks run again immediately before
        // markRunning(), where a changed fence becomes WORLD_CHANGED without revoking the lease.
        AgentActionStore.Confirmation confirmation;
        try {
            confirmation = agentActions.confirm(actionId, System.nanoTime());
        } catch (AgentActionStore.NotFoundException failure) {
            confirmation = AgentActionStore.Confirmation.STALE;
        }
        if (confirmation == AgentActionStore.Confirmation.EXPIRED) {
            finishAgentControlReady(Minecraft.getInstance());
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
            finishAgentControlReady(Minecraft.getInstance());
        }
        return Map.of("action_id", actionId.toString(), "abandoned", abandoned);
    }

    private Map<String, Object> getAgentAction(Map<String, Object> arguments) {
        agentActionWaitTimeoutMillis(arguments);
        return ActionWireMapper.actionPayload(agentActions.get(RuntimeArguments.actionId(arguments)), ContainerInspection.Query.parse(arguments));
    }

    static int agentActionWaitTimeoutMillis(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(
                arguments, "agent_get_action", Set.of("action_id", "wait_timeout_ms",
                        "include_container_results", "container_results_cursor", "container_results_limit"));
        if (!arguments.containsKey("action_id")) {
            throw new IllegalArgumentException("agent_get_action must contain action_id");
        }
        int timeoutMillis = arguments.containsKey("wait_timeout_ms")
                ? RuntimeArguments.intArgument(arguments, "wait_timeout_ms")
                : 0;
        if (timeoutMillis < 0
                || timeoutMillis > AgentActionStore.MAX_TERMINAL_WAIT_MILLIS) {
            throw new IllegalArgumentException("wait_timeout_ms must be in 0.."
                    + AgentActionStore.MAX_TERMINAL_WAIT_MILLIS);
        }
        return timeoutMillis;
    }

    private Map<String, Object> cancelAgentAction(
            Minecraft minecraft, Map<String, Object> arguments) {
        RuntimeArguments.requireExactKeys(arguments, "agent_cancel_action", Set.of("action_id"));
        UUID requestedId = RuntimeArguments.actionId(arguments);
        AgentActionStore.State stateAtRequest = agentActions.get(requestedId).state();
        boolean activeBeforeRequest = !stateAtRequest.terminal();
        var terminal = PendingAgentTerminal.cancel(requestedId);
        if (activeBeforeRequest && !releaseAgentControl(minecraft)) {
            rememberPendingAgentTerminal(terminal);
            retainReadyAfterDeferredAgentRelease();
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "Agent cancellation is retained while bounded input cleanup completes.",
                    true,
                    Map.of());
        }
        final AgentActionStore.CancelResult cancelled;
        if (activeBeforeRequest) {
            if (!publishAgentTerminal(terminal)) {
                throw new RuntimeInvocationException(
                        "unsafe_state",
                        "Agent cancellation is retained pending safe terminal publication.",
                        true,
                        Map.of());
            }
            cancelled = new AgentActionStore.CancelResult(requestedId, true, stateAtRequest);
            returnControlReady();
        } else {
            cancelled = agentActions.cancel(requestedId);
        }
        return Map.of(
                "schema_version", 1,
                "action_id", cancelled.actionId().toString(),
                "cancel_requested", cancelled.cancelRequested(),
                "state_at_request", cancelled.stateAtRequest().wireName());
    }

    private static Set<String> availableCapabilities(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        return ActionWireMapper.AVAILABLE_CAPABILITIES;
    }

    private Map<String, Object> status(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Map<String, Object> arguments) {
        var lock = arming.snapshot(session.worldSessionId());
        var inventory = new LinkedHashMap<String, Integer>();
        var standardPotions = new LinkedHashMap<StandardPotionKey, Integer>();
        Map<String, Object> merchantOffers = null;
        Map<String, Object> knownMenu = null;
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
                    StandardPotionPolicy.identify(stack).ifPresent(identity ->
                            standardPotions.merge(
                                    new StandardPotionKey(identity.item(), identity.potion()),
                                    identity.count(),
                                    Integer::sum));
                }
            }
            if (minecraft.gui.screen() instanceof MerchantScreen screen
                    && screen.getMenu() == player.containerMenu) {
                var snapshot = MerchantOfferSignals.global().latestAfter(
                                minecraft.level,
                                new MerchantOfferSignals.Baseline(
                                        session.worldSessionId(), 0L))
                        .orElse(null);
                var open = ContainerSyncSignals.global().snapshot(minecraft.level)
                        .map(ContainerSyncSignals.Snapshot::lastOpenScreen)
                        .orElse(null);
                merchantOffers = ActionWireMapper.merchantOfferPayload(
                        session.worldSessionId(), screen.getMenu().containerId, open, snapshot);
            }
            if (lock.mode() == LocalArmingState.Mode.READY) {
                knownMenu = ActionWireMapper.knownMenuPayload(
                        minecraft, session, ContainerSyncSignals.global(), knownMenuOperationRefs);
            }
        }

        var inventoryPayload = inventory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(),
                        "count", entry.getValue()))
                .toList();
        var standardPotionPayload = standardPotions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey().item(),
                        "potion", entry.getKey().potion(),
                        "count", entry.getValue()))
                .toList();
        var result = new LinkedHashMap<>(
                ActionWireMapper.statePayload(
                        lock,
                        paused,
                        world,
                        inventoryPayload,
                        standardPotionPayload,
                        minecraft.isMultiplayerServer() && multiplayerPolicyAllows(minecraft),
                        McmcpClientConfig.visualRadiusBlocks(),
                        McmcpClientConfig.raysPerTick()));
        result.put(
                "entity_attack_consent",
                ActionWireMapper.entityAttackConsentPayload(entityAttackConsentSnapshot(session, lock)));
        if (!arguments.isEmpty()) {
            RuntimeFailures.requireReady(session);
            result.put("recipe_query", getRecipes(minecraft, session, arguments));
        }
        if (merchantOffers != null) {
            result.put("merchant_offers", merchantOffers);
        }
        if (knownMenu != null) {
            result.put("known_menu", knownMenu);
        }
        result.put("observation", agentObservations.frames().announceLatestSummary()
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

    private ScopedEntityAttackConsentStore.Snapshot entityAttackConsentSnapshot(
            WorldSessionTracker.Snapshot session,
            LocalArmingState.Snapshot control) {
        if (shutdown
                || endpointFaultCode != null
                || !session.worldReady()
                || control.mode() == LocalArmingState.Mode.OFF) {
            entityAttackConsent.clear();
            return ScopedEntityAttackConsentStore.Snapshot.none();
        }
        return entityAttackConsent.snapshot(session.worldSessionId(), session.clientTick());
    }

    private record StandardPotionKey(String item, String potion)
            implements Comparable<StandardPotionKey> {
        private StandardPotionKey {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(potion, "potion");
        }

        @Override
        public int compareTo(StandardPotionKey other) {
            int itemOrder = item.compareTo(other.item);
            return itemOrder != 0 ? itemOrder : potion.compareTo(other.potion);
        }
    }

    private void tickAgentAction(Minecraft minecraft) {
        var active = agentActions.active();
        if (active.isEmpty()) {
            // Terminal paths already release ownership, and onPreTick retries pending cleanup.
            // Releasing again while idle would stop the USER's bow/potion use every client tick.
            return;
        }
        var action = active.orElseThrow();
        if (action.state() == AgentActionStore.State.UNCONFIRMED) {
            if (agentActions.expireUnconfirmed(System.nanoTime())) {
                finishAgentControlReady(minecraft);
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
                        || !pending.actionId().equals(action.actionId())) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            "admission_missing_before_execution");
                    return;
                }
                var admissionFailure = actionAdmission.admissionFenceFailure(
                        minecraft,
                        session,
                        pending.prepared(),
                        LocalArmingState.Mode.AGENT,
                        pending.prepared().snapshot().control().controlEpoch() + 1L, RendererRecoveryStage.DISPATCH);
                if (admissionFailure.isPresent()) {
                    if (admissionFailure.orElseThrow() == AdmissionFenceFailure.RENDERER_EVIDENCE_MISSING) {
                        // Receipt acknowledgement authorizes no input. Retain the original
                        // admission, delivery lease and elapsed budget until a fresh render.
                        return;
                    }
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            admissionFailure.orElseThrow().executionEvidence());
                    return;
                }
                KillZoneAdmission killAuthorization = pending.killZoneAdmission();
                KillZoneAdmission currentKillAuthorization = null;
                if (killAuthorization != null) {
                    ActionDsl.OperateKillZone operation = Objects.requireNonNull(
                            KillZoneSafety.soleKillZone(action.program().request().program()),
                            "kill-zone operation");
                    currentKillAuthorization = requireKillZoneAdmission(
                            minecraft, session, pending.prepared().source(), operation);
                }
                long startedAtNanos = pending.prepared().surfaceRecovery().executionStartNanos(System.nanoTime());
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
                                .positionCorrectionRevision(),
                        new MenuPrimitiveExecution(action.actionId(), agentActions, knownFurnacePort,
                                knownMenuPort, phaseFiveInventoryPort, knownBrewingPort, applyBlockPlanPort,
                                pillarUpPort, semanticActionPort, observations, agentObservations.deliveredEvidence()),
                        new FishingPrimitiveExecution(action.actionId(), agentActions, fishingSessionRefs, arming));
                boolean transportApprovalConsumed = false;
                if (killAuthorization != null
                        && pending.transportApproval() != null) {
                    transportApprovalConsumed = currentKillAuthorization.equals(killAuthorization)
                            && ScopedEntityAttackConsentTransportBridge.consumeApprovedPending(
                                    entityAttackConsent,
                                    pending.transportApproval(),
                                    session.worldSessionId(),
                                    currentKillAuthorization.policyBindingHash(),
                                    currentKillAuthorization.scope(),
                                    session.clientTick());
                    if (!transportApprovalConsumed) {
                        failAgentAction(
                                AgentActionStore.FailureCode.CAPABILITY_DENIED,
                                true,
                                "kill_zone_transport_approval_not_consumed");
                        return;
                    }
                }
                agentActions.markRunning(action.actionId());
                agentActions.recordAdmissionTicks(action.actionId(),
                        pending.prepared().surfaceRecovery().consumedTicks(session.clientTick()));
                agentExecution = nextExecution;
                agentExecution.surfaceRecovery = pending.prepared().surfaceRecovery();
                agentExecution.surfaceAdmission = pending.prepared().snapshot();
                agentInputReleaseFaultLogged = false;
                agentExecution.frameItemAim = pending.prepared().frameItemAim().orElse(null);
                if (killAuthorization != null) {
                    boolean consumed = currentKillAuthorization.equals(killAuthorization)
                            && (transportApprovalConsumed
                                    || entityAttackConsent.consumeExactForActionStart(
                                            Objects.requireNonNull(KillZoneSafety.soleKillZone(
                                                            action.program().request().program()))
                                                    .consentRef().orElseThrow(),
                                            session.worldSessionId(),
                                            currentKillAuthorization.policyBindingHash(),
                                            currentKillAuthorization.scope(),
                                            session.clientTick()));
                    if (!consumed) {
                        failAgentAction(
                                AgentActionStore.FailureCode.CAPABILITY_DENIED,
                                true,
                                "kill_zone_consent_not_consumed");
                        return;
                    }
                    agentExecution.killZone = new KillZoneExecution(
                            Objects.requireNonNull(
                                    KillZoneSafety.soleKillZone(action.program().request().program())),
                            killAuthorization.scope(),
                            session.clientTick(),
                            minecraft.player.getHealth(),
                            minecraft.player.getAbsorptionAmount(), agentExecution.actionId, agentActions, agentObservations.frames(),
                            observations, reconciliationSignals);
                    if (!advanceAgentProgram(
                            minecraft, agentActions.get(action.actionId()).progress())) {
                        return;
                    }
                }
                agentControlOwnershipEpoch = Math.incrementExact(agentControlOwnershipEpoch);
                pendingAgentAdmission = null;
                if (paused) {
                    // A pause cannot refund renderer waiting charged before execution began.
                    pauseStartedAtNanos = System.nanoTime();
                }
            }
            if (agentExecution.killZone != null
                    && minecraft.player != null
                    && agentExecution.killZone.healthDecreased(minecraft.player)) {
                safetyInterruptKillZone(
                        minecraft, session, action, agentExecution.killZone, "health_decreased");
                return;
            }
            if (paused) {
                releaseAgentInputsForHold(minecraft, "pause_input_release_failed");
                if (agentExecution.primitive instanceof ActionDsl.HoldBoundedInputs
                        || ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                            true,
                            ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)
                                    ? "frame_item_screen_open" : "bounded_input_screen_open");
                }
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

            var currentReconciliation = reconciliationSignals.bindAndSnapshot(
                    minecraft.level, session.worldSessionId());
            agentExecution.latestWorldRevision = currentReconciliation.worldRevision();
            long correctionRevision = currentReconciliation.positionCorrectionRevision();
            if (correctionRevision > agentExecution.positionCorrectionRevision) {
                long previousCorrectionRevision = agentExecution.positionCorrectionRevision;
                agentExecution.positionCorrectionRevision = correctionRevision;
                agentExecution.lastPosition = minecraft.player.position();
                agentExecution.lastYaw = minecraft.player.getYRot();
                agentExecution.lastPitch = minecraft.player.getXRot();
                boolean repeated = ActionBudgets.repeatedPositionCorrection(
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
            if (agentExecution.primitive instanceof ActionDsl.HoldBoundedInputs hold) {
                tickAgentBoundedInputHold(minecraft, session, action, hold, true);
                return;
            }
            var recovery = tickAgentRecovery(minecraft, session, now);
            if (agentExecution.killZone != null
                    && recovery.state() != MinecraftRecoveryGovernor.State.IDLE
                    && recovery.state() != MinecraftRecoveryGovernor.State.REPLAN_REQUIRED) {
                safetyInterruptKillZone(
                        minecraft,
                        session,
                        action,
                        agentExecution.killZone,
                        recovery.reason().name().toLowerCase(Locale.ROOT));
                return;
            }
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

            // The ordinary recovery governor remains authoritative for every hard hazard. Only
            // the later generic local visible-hostile REPLAN is replaced by zone-scoped proofs.
            if (agentExecution.killZone != null) {
                var killBudget = action.program().effectiveBudget();
                var killUsed = agentActions.get(action.actionId()).progress();
                long durationLimit = Duration.ofMillis(killBudget.maxDurationMillis()).toNanos();
                if (killUsed.distanceTravelled() > 0.0D
                        || killUsed.cameraDegrees() > 0.0D
                        || killUsed.blocksBroken() > 0
                        || killUsed.blocksPlaced() > 0
                        || killUsed.motionOverflowed()) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "kill_zone_stationary_contract");
                    return;
                }
                long elapsedNanos = activeElapsedNanos(agentExecution, now);
                boolean hardDeadlineReached = elapsedNanos >= durationLimit
                        || killUsed.ticks() >= killBudget.maxTicks();
                long effectReserveNanos = Duration.ofMillis(
                        ActionDslCompiler.KILL_ZONE_EFFECT_RESERVE_TICKS * 50L).toNanos();
                boolean newDispatchBudgetReached = elapsedNanos
                                >= Math.max(0L, durationLimit - effectReserveNanos)
                        || killUsed.ticks() >= killBudget.maxTicks()
                                - ActionDslCompiler.KILL_ZONE_EFFECT_RESERVE_TICKS
                        || killUsed.interactions() >= killBudget.maxInteractions();
                if (hardDeadlineReached) {
                    tickKillZone(minecraft, session, action, true, true);
                    return;
                }
                var used = agentActions.get(action.actionId()).progress();
                if (agentExecution.primitive == null
                        && !advanceAgentProgram(minecraft, used)) {
                    return;
                }
                agentActions.recordTick(action.actionId());
                tickKillZone(
                        minecraft, session, action, false, newDispatchBudgetReached);
                return;
            }

            var usedBeforeTick = agentActions.get(action.actionId()).progress();
            boolean movementRejected = AgentInputState.global().consumeGoalMovementRejection();
            long durationLimit = Duration.ofMillis(
                    action.program().effectiveBudget().maxDurationMillis()).toNanos();
            if (usedBeforeTick.motionOverflowed()
                    || usedBeforeTick.distanceTravelled()
                            > action.program().effectiveBudget().maxDistanceBlocks()
                    || usedBeforeTick.cameraDegrees()
                            > action.program().effectiveBudget().maxCameraDegrees()) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "motion");
                return;
            }
            if (activeElapsedNanos(agentExecution, now) >= durationLimit) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "duration");
                return;
            }
            if (usedBeforeTick.ticks() >= action.program().effectiveBudget().maxTicks()) {
                failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false, "ticks");
                return;
            }
            // Vanilla can collect the witnessed item while the navigator is still reporting
            // RUNNING. Honor that server-confirmed inventory delta only after the hard action
            // gates, and before a vanished witness can be mistaken for a path failure.
            if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItemBatch
                    && !reconcileCollectBatchEvidence(minecraft, session, action)) {
                return;
            }
            if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItem collect
                    && agentExecution.pickupInventoryBefore >= 0
                    && PlayerInventoryEvidence.pickupInventoryIncreased(
                            agentExecution.pickupInventoryBefore,
                            PlayerInventoryEvidence.inventoryItemCount(player, collect.displayedItem()))) {
                completeAgentPrimitive(minecraft, action);
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
            if (agentExecution.primitive
                    instanceof ActionDsl.OperateKnownCobblestoneGenerator
                    && (recovery.state() == MinecraftRecoveryGovernor.State.REPLAN_REQUIRED
                            || agentObservations.localSafety() == LocalObservationProjector.CurrentSafety.REPLAN)) {
                failAgentAction(
                        AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                        true,
                        "cobblestone_generator_safety_changed");
                return;
            }
            if (!(agentExecution.primitive instanceof ActionDsl.OperateKnownMenu)
                    && !(agentExecution.primitive instanceof ActionDsl.PillarUpKnown)
                    && (recovery.state() == MinecraftRecoveryGovernor.State.REPLAN_REQUIRED
                            || agentObservations.localSafety() == LocalObservationProjector.CurrentSafety.REPLAN)) {
                if (ActionEvidence.isAgentWait(agentExecution.primitive)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.PATH_BLOCKED,
                            true,
                            "local_safety_changed_during_wait");
                } else {
                    requestAgentReplan(actionTick, "local_safety_changed");
                }
                return;
            }
            if (agentExecution.primitive
                    instanceof ActionDsl.HoldBoundedInputs hold) {
                tickAgentBoundedInputHold(minecraft, session, action, hold, false);
                return;
            }
            if (agentExecution.primitive
                    instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
                tickAgentCobblestoneGenerator(minecraft, session, action, operation);
                return;
            }
            if (ActionEvidence.isAgentWait(agentExecution.primitive)) {
                if (occurrenceBudgetExceeded(
                        agentActions.get(action.actionId()).progress(),
                        agentExecution)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_budget");
                    return;
                }
                boolean complete = false;
                if (agentExecution.primitive instanceof ActionDsl.WaitUntil wait
                        && wait.condition() instanceof ActionDsl.CropMatureCondition) {
                    CropWaitLiveState live = authorizedCropWaitLiveState(
                            minecraft,
                            session,
                            wait,
                            agentExecution.cropWaitAuthorization);
                    if (live == CropWaitLiveState.WORLD_CHANGED) {
                        failAgentAction(
                                AgentActionStore.FailureCode.WORLD_CHANGED,
                                true,
                                "crop_wait_world_changed");
                        return;
                    }
                    if (live == CropWaitLiveState.VISIBILITY_INVALIDATED) {
                        failAgentAction(
                                AgentActionStore.FailureCode.PATH_BLOCKED,
                                true,
                                "crop_wait_visibility_invalidated");
                        return;
                    }
                    if (live == CropWaitLiveState.UNLOADED) {
                        failAgentAction(
                                AgentActionStore.FailureCode.PATH_BLOCKED,
                                true,
                                "crop_wait_target_unloaded");
                        return;
                    }
                    if (live == CropWaitLiveState.TARGET_CHANGED) {
                        failAgentAction(
                                AgentActionStore.FailureCode.PATH_BLOCKED,
                                true,
                                "crop_wait_target_changed");
                        return;
                    }
                    complete = live == CropWaitLiveState.MATURE;
                } else if (agentExecution.primitive instanceof ActionDsl.WaitUntil wait
                        && wait.condition() instanceof ActionDsl.SoundClueCondition sound) {
                    complete = soundClueMatched(minecraft, sound, session.clientTick());
                }
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
                            "wait_condition_timeout");
                }
                return;
            }
            if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod
                    && agentExecution.fishingAimComplete
                    || agentExecution.primitive instanceof ActionDsl.ReelKnownFishingSession) {
                tickAgentFishing(minecraft, session, action);
                return;
            }
            if (agentExecution.replanning
                    && ActionBudgets.replanDeadlineReached(actionTick, agentExecution.replanDeadlineTick)) {
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

            ActionDsl.CollectVisibleItem activeCollect = activeCollectTarget();
            if (activeCollect != null
                    && (agentExecution.pickupInventoryBefore >= 0
                            || agentExecution.collectBatchEvidence != null)) {
                if (agentExecution.pickupArrivalTick >= 0L) {
                    tickAgentPickupConfirmation(minecraft, session, action, activeCollect);
                    return;
                }
                if (agentExecution.pickupCell != null) {
                    var pickupMap = agentObservations.requireAgentMap(session);
                    long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(
                            pickupMap,
                            reconciliationSignals.bindAndSnapshot(
                                    Objects.requireNonNull(minecraft.level, "level"),
                                    session.worldSessionId()));
                    if (!AgentPrimitivePlanner.visibleItemPickupCellCurrent(
                            pickupMap,
                            agentObservations.agentPlanningFrame(),
                            activeCollect,
                            agentExecution.pickupCell,
                            visualBarrierWorldRevision,
                            session.clientTick(),
                            ActionBudgets.visibleItemEvidenceMaxAgeTicks(
                                    McmcpClientConfig.raysPerTick()))) {
                        requestAgentReplan(actionTick, "pickup_witness_changed");
                        return;
                    }
                }
            }

            if (agentExecution.primitive instanceof ActionDsl.TillKnownBlock
                    || agentExecution.primitive instanceof ActionDsl.TillKnownBatch
                    || agentExecution.primitive instanceof ActionDsl.PlantKnownWheat
                    || agentExecution.primitive instanceof ActionDsl.PlantKnownWheatBatch
                    || agentExecution.primitive instanceof ActionDsl.HarvestKnownWheat
                    || agentExecution.primitive instanceof ActionDsl.HarvestKnownWheatBatch
                    || agentExecution.primitive instanceof ActionDsl.OpenKnownFenceGate
                    || agentExecution.primitive instanceof ActionDsl.OpenKnownPassage) {
                tickAgentBlockMutation(minecraft, session, action, actionTick);
                return;
            }

            if (ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
                tickAgentFrameItem(minecraft, session, action);
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.InspectKnownContainer
                    || agentExecution.primitive instanceof ActionDsl.TakeKnownContainerStack
                    || agentExecution.primitive instanceof ActionDsl.StoreKnownContainerStack
                    || agentExecution.primitive instanceof ActionDsl.CraftKnownRecipe
                    || agentExecution.primitive instanceof ActionDsl.SmeltKnownRecipe
                    || agentExecution.primitive instanceof ActionDsl.OperateKnownMenu) {
                applyMenuPrimitiveOutcome(minecraft, action,
                        agentExecution.menuPrimitives.tickAgentContainer(minecraft, session,
                                agentExecution.primitive, agentExecution.mutationAims,
                                agentExecution.latestWorldRevision));
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.BrewKnownPotionBatch) {
                applyMenuPrimitiveOutcome(minecraft, action,
                        agentExecution.menuPrimitives.tickAgentBrewing(session, agentExecution.primitive,
                                agentExecution.mutationAims, agentExecution.maxCameraDegreesPerTick));
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.ApplyKnownBlockPlan
                    || agentExecution.primitive instanceof ActionDsl.ClearKnownBlockPlan) {
                applyMenuPrimitiveOutcome(minecraft, action,
                        agentExecution.menuPrimitives.tickAgentConstruction(session, agentExecution.primitive,
                                agentExecution.latestWorldRevision));
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.PillarUpKnown) {
                applyMenuPrimitiveOutcome(minecraft, action,
                        agentExecution.menuPrimitives.tickAgentPillarUp(session, agentExecution.primitive));
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.ApplyKnownRedstoneSpec) {
                applyMenuPrimitiveOutcome(minecraft, action,
                        agentExecution.menuPrimitives.tickAgentRedstone(minecraft, session,
                                agentExecution.primitive, agentExecution.mutationAims));
                return;
            }

            KnownTraversabilitySnapshot map = agentObservations.requireAgentMap(session);
            if (agentExecution.primitiveExecutor.active()
                    && (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition
                            || agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace
                            || KnownBreakSafety.isKnownBreak(agentExecution.primitive)
                            || agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod)) {
                var faceReconciliation = reconciliationSignals.bindAndSnapshot(
                        Objects.requireNonNull(minecraft.level, "level"),
                        session.worldSessionId());
                var faceSurfaceBarrier = ActionEvidence.surfaceRevisionBarrier(map, faceReconciliation);
                boolean faceEvidenceCurrent;
                if (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition face) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownFacingTarget(
                            map, agentObservations.agentPlanningFrame(), face.target());
                } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace face) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownFacingSurface(
                            map,
                            agentObservations.agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    face.target(), face.face(), face.expectedBlock()));
                } else if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod cast) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownExactSurface(
                            map,
                            agentObservations.agentPlanningFrame(),
                            cast.target(),
                            cast.face(),
                            cast.expectedState(),
                            faceSurfaceBarrier.applyAsLong(cast.target()));
                } else {
                    var block = agentExecution.primitive;
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownSurface(
                            map,
                            agentObservations.agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    KnownBreakSafety.breakTarget(block), KnownBreakSafety.breakFace(block), KnownBreakSafety.breakBlockId(block)),
                            faceSurfaceBarrier.applyAsLong(KnownBreakSafety.breakTarget(block)));
                    if (faceEvidenceCurrent && block instanceof ActionDsl.BreakKnownBlock exact) {
                        try {
                            AgentPrimitivePlanner.requireKnownBreakSurface(
                                    map, agentObservations.agentPlanningFrame(), exact,
                                    faceSurfaceBarrier.applyAsLong(exact.target()));
                        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
                            faceEvidenceCurrent = false;
                        }
                    }
                }
                if (!faceEvidenceCurrent) {
                    requestAgentReplan(actionTick, "face_target_reobservation");
                    return;
                }
            }
            if (KnownBreakSafety.isKnownBreak(agentExecution.primitive)
                    && agentExecution.breakAimComplete) {
                tickAgentBreak(
                        minecraft, session, action, map, agentExecution.primitive, actionTick);
                return;
            }
            if (!agentExecution.primitiveExecutor.active()
                    && !beginAgentPrimitive(
                            minecraft, action, map, usedBeforeTick, session.clientTick())) {
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
            if (ActionBudgets.motionBudgetExceededAfterPrimitive(
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
            // The movement tick itself can enter the pickup area and let vanilla collect the
            // witnessed item before the next observation frame. Bind contact against the still
            // fresh policy-visible AABB and reconcile the post-move absolute inventory now.
            if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItemBatch
                    && !reconcileCollectBatchEvidence(minecraft, session, action)) {
                return;
            }
            switch (result.status()) {
                case RUNNING -> {
                    if (ActionBudgets.shouldVerifyReplanHeartbeat(agentExecution.replanning, result)) {
                        agentExecution.replanHeartbeatPending = true;
                    }
                }
                case SUCCEEDED -> {
                    if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod) {
                        agentExecution.fishingAimComplete = true;
                        agentExecution.replanning = false;
                        agentExecution.replanNotBeforeTick = 0L;
                        agentExecution.replanDeadlineTick = 0L;
                        return;
                    }
                    if (KnownBreakSafety.isKnownBreak(agentExecution.primitive)) {
                        agentExecution.breakAimComplete = true;
                        agentExecution.replanning = false;
                        agentExecution.replanNotBeforeTick = 0L;
                        agentExecution.replanDeadlineTick = 0L;
                        return;
                    }
                    ActionDsl.CollectVisibleItem completedCollect = activeCollectTarget();
                    if (completedCollect != null) {
                        long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(
                                map,
                                reconciliationSignals.bindAndSnapshot(
                                        Objects.requireNonNull(minecraft.level, "level"),
                                        session.worldSessionId()));
                        var itemBounds = AgentPrimitivePlanner.visibleItemAabb(
                                map,
                                agentObservations.agentPlanningFrame(),
                                completedCollect,
                                visualBarrierWorldRevision,
                                session.clientTick(),
                                ActionBudgets.visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
                        if (itemBounds.isEmpty() || !PlayerInventoryEvidence.playerPickupAreaIntersects(
                                Objects.requireNonNull(minecraft.player, "player").getBoundingBox(),
                                itemBounds.orElseThrow())) {
                            requestAgentReplan(actionTick, "pickup_area_unreached");
                            return;
                        }
                        if (agentExecution.primitive
                                instanceof ActionDsl.CollectVisibleItemBatch) {
                            agentExecution.collectBatchEvidence.recordContact(
                                    agentExecution.collectBatchIndex, session.clientTick());
                        }
                        closeAgentPrimitiveExecutor();
                        agentExecution.pickupArrivalTick = session.clientTick();
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

    private void tickKillZone(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, boolean actionHardDeadlineReached,
            boolean newDispatchBudgetReached) {
        var operation = Objects.requireNonNull(agentExecution.killZone, "killZone");
        var outcome = operation.tickKillZone(minecraft, session,
                agentExecution.latestWorldRevision, actionHardDeadlineReached, newDispatchBudgetReached);
        applyKillZoneOutcome(minecraft, action, outcome);
    }

    private void safetyInterruptKillZone(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, KillZoneExecution operation, String reason) {
        var outcome = operation.safetyInterruptKillZone(minecraft, session,
                agentExecution.latestWorldRevision, operation, reason);
        applyKillZoneOutcome(minecraft, action, outcome);
    }

    private void applyKillZoneOutcome(Minecraft minecraft, AgentActionStore.Active action,
            PrimitiveOutcome outcome) {
        if (outcome.failure() != null) {
            failAgentAction(outcome.failure().code(), outcome.failure().recoverable(),
                    outcome.failure().evidence().getFirst());
        } else if (outcome.complete()) {
            agentActions.completeNode(action.actionId());
            agentExecution.primitive = null;
            advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
        }
    }

    /** 制御nodeの進行中にActionが終了した場合はfalseを返す。 */
    private boolean advanceAgentProgram(
            Minecraft minecraft, AgentActionStore.Progress occurrenceBaseline) {
        ActionProgramCursor.Advance advance = agentExecution.cursor.next(ActionPredicates.policySnapshot(minecraft));
        for (String controlNode : advance.completedControlNodeIds()) {
            agentActions.beginNode(agentExecution.actionId, controlNode);
            agentActions.completeNode(agentExecution.actionId);
        }
        if (advance.finished()) {
            UUID completedActionId = agentExecution.actionId;
            var terminal = PendingAgentTerminal.success(completedActionId);
            if (!releaseAgentControl(minecraft)) {
                rememberPendingAgentTerminal(terminal);
                retainReadyAfterDeferredAgentRelease();
                return false;
            }
            if (publishAgentTerminal(terminal)) {
                returnControlReady();
            }
            return false;
        }
        agentExecution.primitive = advance.primitive();
        agentExecution.occurrenceBaseline = Objects.requireNonNull(
                occurrenceBaseline, "occurrenceBaseline");
        agentExecution.occurrenceLimit = null;
        agentExecution.retainOccurrenceBaseline = false;
        agentExecution.primitivePlanning = false;
        agentExecution.mutationAimFailures = 0;
        agentExecution.mutationBatchPlan = null;
        agentExecution.mutationBatchIndex = 0;
        agentExecution.mutationBatchTarget = null;
        agentExecution.mutationBatchTargetAim = null;
        agentExecution.mutationBatchTargetBound = false;
        agentExecution.mutationBatchTargetDeadlineTick = 0L;
        agentExecution.collectBatchIndex = 0;
        agentExecution.collectBatchEvidence = null;
        agentExecution.cropWaitAuthorization = null;
        agentExecution.fishingAimComplete = false;
        agentExecution.primitivePlanDeadlineTick = Math.addExact(
                occurrenceBaseline.ticks(), ActionEvidence.primitiveReobservationTicks(advance.primitive()));
        agentExecution.mutationAims.clear();
        agentExecution.pickupInventoryBefore = advance.primitive()
                instanceof ActionDsl.CollectVisibleItem collect
                ? ActionBudgets.pickupOccurrenceBaseline(
                        -1,
                        PlayerInventoryEvidence.inventoryItemCount(
                                Objects.requireNonNull(minecraft.player, "player"),
                                collect.displayedItem()))
                : -1;
        agentExecution.pickupArrivalTick = -1L;
        agentExecution.pickupCell = null;
        if (advance.primitive() instanceof ActionDsl.CollectVisibleItemBatch batch) {
            agentExecution.collectBatchEvidence = new CollectBatchEvidence(
                    batch.targets(),
                    PlayerInventoryEvidence.collectBatchInventoryCounts(
                            Objects.requireNonNull(minecraft.player, "player"), batch));
        }
        agentActions.beginNode(agentExecution.actionId, advance.primitive().id());
        if (advance.primitive() instanceof ActionDsl.WaitTicks wait) {
            agentExecution.waitTicksRemaining = wait.ticks();
            agentExecution.occurrenceLimit = agentExecution.program.primitiveCostBounds()
                    .get(advance.primitive().id());
        } else if (advance.primitive() instanceof ActionDsl.WaitUntil wait) {
            agentExecution.waitTicksRemaining = wait.maxTicks();
        }
        if (advance.primitive() instanceof ActionDsl.WaitTicks
                && agentExecution.occurrenceLimit == null) {
            throw new IllegalStateException("Compiled wait cost bound is unavailable");
        }
        if (agentExecution.occurrenceLimit != null
                && !ActionBudgets.fitsRemainingBudget(
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
            var surfaceRecovery = agentExecution.surfaceRecovery;
            if (surfaceRecovery != null && surfaceRecovery.applies(agentExecution.primitive)) {
                var rendererFailure = actionAdmission.surfaceRecoveryFailure(minecraft, session, surfaceRecovery, RendererRecoveryStage.JIT);
                if (rendererFailure.isPresent()) {
                    var reason = rendererFailure.orElseThrow();
                    if (reason != AdmissionFenceFailure.RENDERER_EVIDENCE_MISSING) {
                        failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true, reason.code());
                    } else {
                        releaseAgentInputsForHold(minecraft, "renderer_wait_input_release_failed");
                    }
                    return false;
                }
            }
            var player = Objects.requireNonNull(minecraft.player, "player");
            var map = agentObservations.requireAgentMap(session);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"),
                    session.worldSessionId());
            long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(map, reconciliation);
            boolean worldPlanning = ActionPlanning.requiresWorldPlanning(agentExecution.primitive);
            var planningFrame = worldPlanning ? agentObservations.agentPlanningFrame(agentExecution.primitive,
                    surfaceRecovery != null && surfaceRecovery.applies(agentExecution.primitive)
                            ? surfaceRecovery.lease() : null) : Optional.<ObservationFrame>empty();
            var analysis = worldPlanning
                    ? actionAdmission.analyzePrimitive(
                            action.program().request().program(),
                            agentExecution.primitive,
                            map,
                            ActionPlanning.playerPose(player, session.dimension()),
                            planningFrame,
                            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                            visualBarrierWorldRevision,
                            ActionEvidence.primitiveSurfaceRevisionBarrier(
                                    agentExecution.primitive, map, reconciliation),
                            () -> true)
                    : ActionPlanning.emptyPrimitiveAnalysis();
            ActionDslCompiler.Cost cost = (worldPlanning
                            ? analysis.worstCase(agentExecution.primitive)
                            : Optional.ofNullable(action.program().primitiveCostBounds()
                                    .get(agentExecution.primitive.id())))
                    .orElseThrow(() -> new IllegalStateException(
                            "JIT primitive analysis did not produce a cost"));
            long activeElapsedNanos = activeElapsedNanos(
                    agentExecution, System.nanoTime());
            ActionDslCompiler.Cost remainingCost = surfaceRecovery != null
                    && surfaceRecovery.applies(agentExecution.primitive)
                    ? ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                            progress,
                            agentExecution.occurrenceBaseline.executedNodes() == 0,
                            agentExecution.primitive,
                            cost,
                            activeElapsedNanos)
                    : ActionBudgets.firstPrimitiveRemainingCost(progress, cost, activeElapsedNanos);
            if (!ActionBudgets.fitsRemainingBudget(
                    progress,
                    action.program().effectiveBudget(),
                    remainingCost,
                    activeElapsedNanos)) {
                failAgentAction(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        "jit_primitive_budget");
                return false;
            }
            CropWaitAuthorization cropWaitAuthorization =
                    agentExecution.primitive instanceof ActionDsl.WaitUntil wait
                            && wait.condition() instanceof ActionDsl.CropMatureCondition
                            ? ActionEvidence.requireCropWaitAuthorization(
                                    session,
                                    wait,
                                    analysis,
                                    visualBarrierWorldRevision,
                                    player.position(),
                                    player.getEyePosition())
                            : null;
            if (agentExecution.retainOccurrenceBaseline) {
                agentExecution.occurrenceLimit = ActionBudgets.occurrenceCostIncludingConsumed(
                        progress, agentExecution.occurrenceBaseline, cost);
                agentExecution.retainOccurrenceBaseline = false;
            } else {
                agentExecution.occurrenceBaseline = progress;
                agentExecution.occurrenceLimit = cost;
            }
            agentExecution.mutationAims.putAll(analysis.mutationAims());
            if (ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
                var currentAim = AgentPrimitivePlanner.requireFrameItemAim(
                        map, ActionPlanning.playerPose(player, session.dimension()), planningFrame,
                        agentExecution.primitive, visualBarrierWorldRevision);
                if (!ActionEvidence.frameItemEvidenceFresh(currentAim, session.clientTick())
                        || !ActionEvidence.sameFrameItemAuthorization(agentExecution.frameItemAim, currentAim)) {
                    failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED,
                            false, "frame_item_authorization_changed");
                    return false;
                }
                agentExecution.frameItemAim = currentAim;
            }
            Optional.ofNullable(analysis.mutationBatchPlans().get(agentExecution.primitive.id()))
                    .ifPresent(plan -> agentExecution.mutationBatchPlan = plan);
            agentExecution.cropWaitAuthorization = cropWaitAuthorization;
            agentExecution.primitivePlanDeadlineTick = 0L;
            if (agentExecution.primitivePlanning) {
                agentActions.setPhase(
                        action.actionId(), AgentActionStore.Phase.EXECUTING, "jit_primitive_bound");
                agentExecution.primitivePlanning = false;
            }
            if (surfaceRecovery != null && surfaceRecovery.applies(agentExecution.primitive)) {
                actionAdmission.rendererRecoveryRevalidated(surfaceRecovery, RendererRecoveryStage.JIT);
            }
            return true;
        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
            if (agentExecution.surfaceRecovery != null
                    && agentExecution.surfaceRecovery.applies(agentExecution.primitive)
                    && unavailable.code() == AgentPrimitivePlanner.Code.TARGET_UNKNOWN) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                        AdmissionFenceFailure.SURFACE_REOBSERVATION_MISMATCH.code());
                return false;
            }
            if (ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED,
                        false, "frame_item_authorization_unavailable");
                return false;
            }
            if (!releaseAgentInputsForHold(minecraft, "jit_replan_input_release_failed")) {
                return false;
            }
            if (ActionBudgets.replanDeadlineReached(actionTick, agentExecution.primitivePlanDeadlineTick)) {
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

    private CropWaitLiveState authorizedCropWaitLiveState(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.WaitUntil wait,
            CropWaitAuthorization authorization) {
        var level = minecraft.level;
        var player = minecraft.player;
        if (level == null || player == null) {
            return CropWaitLiveState.WORLD_CHANGED;
        }
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                level, session.worldSessionId());
        CropWaitVisibilityState visibility = ActionEvidence.cropWaitVisibilityState(
                authorization,
                session,
                ((ActionDsl.CropMatureCondition) wait.condition()).target(),
                reconciliation.visualBarrierWorldRevision(),
                player.position(),
                player.getEyePosition());
        if (visibility == CropWaitVisibilityState.WORLD_CHANGED) {
            return CropWaitLiveState.WORLD_CHANGED;
        }
        if (visibility == CropWaitVisibilityState.VISIBILITY_INVALIDATED) {
            return CropWaitLiveState.VISIBILITY_INVALIDATED;
        }
        ActionDsl.Position target = authorization.target();
        var position = new BlockPos(target.x(), target.y(), target.z());
        if (!level.isLoaded(position) || !level.getWorldBorder().isWithinBounds(position)) {
            return CropWaitLiveState.UNLOADED;
        }
        // The authorization is coordinate-exact; do not inspect any neighboring or hidden state.
        return ActionEvidence.cropWaitLiveState(true, level.getBlockState(position));
    }

    private boolean soundClueMatched(
            Minecraft minecraft, ActionDsl.SoundClueCondition condition, long currentTick) {
        var player = minecraft.player;
        FishingHook hook = player == null ? null : player.fishing;
        if (player == null || !PlayerInventoryEvidence.ownedFishingHook(player, hook, null)
                || !ActionEvidence.pointInside(condition.bounds(), sessions.snapshot().dimension(),
                        hook.getX(), hook.getY(), hook.getZ())) {
            return false;
        }
        List<ObservationRecord.SoundClue> nearby = agentObservations.soundClues().snapshot(currentTick).clues()
                .stream()
                .filter(clue -> {
                    double dx = clue.position().x() - hook.getX();
                    double dy = clue.position().y() - hook.getY();
                    double dz = clue.position().z() - hook.getZ();
                    return dx * dx + dy * dy + dz * dz <= 4.0;
                })
                .toList();
        return ActionEvidence.soundClueMatches(condition, currentTick, nearby);
    }

    private boolean beginAgentPrimitive(
            Minecraft minecraft,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            AgentActionStore.Progress progressBeforeTick,
            long currentTick) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        ActionDslCompiler.Cost cost;
        try {
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"),
                    map.worldSessionId());
            long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(map, reconciliation);
            var surfaceRevisionBarrier = ActionEvidence.surfaceRevisionBarrier(map, reconciliation);
            if (agentExecution.primitive instanceof ActionDsl.NavigateToKnown navigate) {
                RoutePlan route = AgentPrimitivePlanner.requireRoute(
                        map,
                        agentPathfinder,
                        ActionPlanning.playerCell(player, map.dimension()),
                        navigate.target());
                var pose = ActionPlanning.playerPose(player, map.dimension());
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.navigationReplanCost(route, pose)
                        : AgentPrimitivePlanner.navigationCost(route, pose);
                if (agentExecution.replanning) {
                    String evidence = ActionBudgets.replannedRouteBudgetFailure(
                            progressBeforeTick,
                            agentExecution.occurrenceBaseline,
                            agentExecution.occurrenceLimit,
                            action.program().effectiveBudget(),
                            cost,
                            activeElapsedNanos(agentExecution, System.nanoTime()));
                    if (evidence != null) {
                        failAgentAction(
                                AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                                false,
                                evidence);
                        return false;
                    }
                } else if (!ActionBudgets.fitsRemainingBudget(
                                progressBeforeTick,
                                action.program().effectiveBudget(),
                                cost,
                                activeElapsedNanos(agentExecution, System.nanoTime()))) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "navigate_to_known");
                    return false;
                } else if (!fitsOccurrenceRemaining(
                        progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_navigate_to_known");
                    return false;
                }
                agentExecution.primitiveExecutor.beginNavigate(route, navigate.tolerance());
            } else if (agentExecution.primitive instanceof ActionDsl.ApproachKnownSurface approach) {
                Optional<ObservationFrame> approachFrame = agentObservations.agentPlanningFrame();
                long approachSurfaceBarrier =
                        surfaceRevisionBarrier.applyAsLong(approach.target());
                var pose = ActionPlanning.playerPose(player, map.dimension());
                AgentPrimitivePlanner.ApproachPlan plan =
                        ActionPlanning.requireRuntimeApproachPlan(
                                map,
                                agentPathfinder,
                                pose,
                                approach,
                                approachFrame,
                                approachSurfaceBarrier);
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.navigationReplanCost(plan.route(), pose)
                        : AgentPrimitivePlanner.navigationCost(plan.route(), pose);
                if (agentExecution.replanning) {
                    String evidence = ActionBudgets.replannedRouteBudgetFailure(
                            progressBeforeTick,
                            agentExecution.occurrenceBaseline,
                            agentExecution.occurrenceLimit,
                            action.program().effectiveBudget(),
                            cost,
                            activeElapsedNanos(agentExecution, System.nanoTime()));
                    if (evidence != null) {
                        failAgentAction(
                                AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                                false,
                                evidence);
                        return false;
                    }
                } else if (!ActionBudgets.fitsRemainingBudget(
                                progressBeforeTick,
                                action.program().effectiveBudget(),
                                cost,
                                activeElapsedNanos(agentExecution, System.nanoTime()))
                        || !fitsOccurrenceRemaining(
                                progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "approach_known_surface");
                    return false;
                }
                agentExecution.primitiveExecutor.beginNavigate(plan.route(), 0.25D);
            } else if (agentExecution.primitive
                    instanceof ActionDsl.ApproachKnownPlacement approach) {
                var pose = ActionPlanning.playerPose(player, map.dimension());
                AgentPrimitivePlanner.ApproachPlan plan =
                        ActionPlanning.requireRuntimeKnownPlacementApproachPlan(
                                map,
                                agentPathfinder,
                                pose,
                                approach,
                                agentObservations.agentPlanningFrame(),
                                surfaceRevisionBarrier,
                                agentObservations.deliveredEvidence()::resolvePlacementState);
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.navigationReplanCost(plan.route(), pose)
                        : AgentPrimitivePlanner.navigationCost(plan.route(), pose);
                if (agentExecution.replanning) {
                    String evidence = ActionBudgets.replannedRouteBudgetFailure(
                            progressBeforeTick,
                            agentExecution.occurrenceBaseline,
                            agentExecution.occurrenceLimit,
                            action.program().effectiveBudget(),
                            cost,
                            activeElapsedNanos(agentExecution, System.nanoTime()));
                    if (evidence != null) {
                        failAgentAction(
                                AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                                false,
                                evidence);
                        return false;
                    }
                } else if (!ActionBudgets.fitsRemainingBudget(
                                progressBeforeTick,
                                action.program().effectiveBudget(),
                                cost,
                                activeElapsedNanos(agentExecution, System.nanoTime()))
                        || !fitsOccurrenceRemaining(
                                progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "approach_known_placement");
                    return false;
                }
                agentExecution.primitiveExecutor.beginNavigate(plan.route(), 0.25D);
            } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition face) {
                var target = AgentPrimitivePlanner.requireKnownFaceTarget(
                        map,
                        agentObservations.agentPlanningFrame(),
                        face.target());
                cost = AgentPrimitivePlanner.faceCost(
                        ActionPlanning.playerPose(player, map.dimension()),
                        face.target(),
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                if (!ActionBudgets.fitsRemainingBudget(
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
            } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace face) {
                var target = AgentPrimitivePlanner.requireKnownBlockFaceTarget(
                        map,
                        agentObservations.agentPlanningFrame(),
                        face);
                cost = AgentPrimitivePlanner.faceCost(
                        ActionPlanning.playerPose(player, map.dimension()),
                        face,
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        activeElapsedNanos(agentExecution, System.nanoTime()))) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "face_block_target");
                    return false;
                }
                if (!fitsOccurrenceRemaining(
                        progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "primitive_face_block_target");
                    return false;
                }
                agentExecution.primitiveExecutor.beginFace(target, cost.ticks());
            } else if (agentExecution.primitive instanceof ActionDsl.BreakKnownFace block) {
                AgentPrimitivePlanner.MutationAim breakAim =
                        AgentPrimitivePlanner.requireKnownBreakAim(
                        map,
                        agentObservations.agentPlanningFrame(),
                        block,
                        surfaceRevisionBarrier.applyAsLong(block.target()));
                cost = AgentPrimitivePlanner.breakCost(
                        ActionPlanning.playerPose(player, map.dimension()),
                        block,
                        breakAim.point(),
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                long aimTicks = ActionBudgets.breakAimTicks(cost);
                cost = ActionBudgets.breakExecutionCost(cost, agentExecution.replanning);
                if (!ActionBudgets.fitsRemainingBudget(
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
                int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                        player, block.toolItem(), remainingBreaks);
                if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(
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
                        new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                                map.worldSessionId(), map.worldRevision(),
                                block.target(), breakAim.point().x,
                                breakAim.point().y, breakAim.point().z, true),
                        aimTicks);
            } else if (agentExecution.primitive instanceof ActionDsl.BreakKnownBlock block) {
                AgentPrimitivePlanner.MutationAim breakAim =
                        AgentPrimitivePlanner.requireKnownBreakAim(
                                map,
                                agentObservations.agentPlanningFrame(),
                                block,
                                surfaceRevisionBarrier.applyAsLong(block.target()));
                cost = AgentPrimitivePlanner.breakCost(
                        ActionPlanning.playerPose(player, map.dimension()),
                        block,
                        breakAim.point(),
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                long aimTicks = ActionBudgets.breakAimTicks(cost);
                cost = ActionBudgets.breakExecutionCost(cost, agentExecution.replanning);
                if (!ActionBudgets.fitsRemainingBudget(
                        progressBeforeTick,
                        action.program().effectiveBudget(),
                        cost,
                        activeElapsedNanos(agentExecution, System.nanoTime()))
                        || !fitsOccurrenceRemaining(
                                progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "break_known_block");
                    return false;
                }
                int remainingBreaks = Math.toIntExact(Math.max(
                        1L,
                        action.program().worstCaseCost().blocksBroken()
                                - progressBeforeTick.blocksBroken()));
                int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                        player, block.toolItem(), remainingBreaks);
                if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(
                        player, action.program())) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            toolSlot < 0 ? "required_tool_unavailable" : "inventory_full");
                    return false;
                }
                player.getInventory().setSelectedSlot(toolSlot);
                agentExecution.agentSelectedSlot = toolSlot;
                agentExecution.primitiveExecutor.beginFace(
                        new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                                map.worldSessionId(), map.worldRevision(),
                                block.target(), breakAim.point().x,
                                breakAim.point().y, breakAim.point().z, true),
                        aimTicks);
            } else if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod cast) {
                if (!PlayerInventoryEvidence.exactFishingRodHeld(player, cast.hand(), cast.rodItem())
                        || player.fishing != null) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            player.fishing == null
                                    ? "required_fishing_rod_unavailable"
                                    : "owned_bobber_already_present");
                    return false;
                }
                AgentPrimitivePlanner.MutationAim aim = Objects.requireNonNull(
                        agentExecution.mutationAims.get(cast.id()), "fishing cast aim");
                cost = Objects.requireNonNull(
                        agentExecution.occurrenceLimit, "fishing cast cost");
                agentExecution.primitiveExecutor.beginFace(
                        new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                                map.worldSessionId(), map.worldRevision(), cast.target(),
                                aim.point().x, aim.point().y, aim.point().z, true),
                        Math.max(1L, Math.min(600L, cost.ticks())));
            } else if (isCollectPrimitive(agentExecution.primitive)) {
                ActionDsl.CollectVisibleItem collect = Objects.requireNonNull(
                        activeCollectTarget(), "active collect target");
                AgentPrimitivePlanner.PickupPlan pickup = AgentPrimitivePlanner.requirePickupPlan(
                        map,
                        agentPathfinder,
                        ActionPlanning.playerCell(player, map.dimension()),
                        agentObservations.agentPlanningFrame(),
                        collect,
                        visualBarrierWorldRevision,
                        currentTick,
                        ActionBudgets.visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
                var pose = ActionPlanning.playerPose(player, map.dimension());
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.pickupReplanCost(pickup.route(), pose)
                        : AgentPrimitivePlanner.pickupCost(pickup.route(), pose);
                if (agentExecution.replanning) {
                    String evidence = ActionBudgets.replannedRouteBudgetFailure(
                            progressBeforeTick,
                            agentExecution.occurrenceBaseline,
                            agentExecution.occurrenceLimit,
                            action.program().effectiveBudget(),
                            cost,
                            activeElapsedNanos(agentExecution, System.nanoTime()));
                    if (evidence != null) {
                        failAgentAction(
                                AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                                false,
                                evidence);
                        return false;
                    }
                } else if (!ActionBudgets.fitsRemainingBudget(
                                progressBeforeTick,
                                action.program().effectiveBudget(),
                                cost,
                                activeElapsedNanos(agentExecution, System.nanoTime()))
                        || !fitsOccurrenceRemaining(
                                progressBeforeTick, agentExecution, cost)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                            false,
                            "collect_visible_item");
                    return false;
                }
                if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItem
                        && agentExecution.pickupInventoryBefore < 0) {
                    throw new IllegalStateException(
                            "collect occurrence inventory baseline was not captured");
                }
                agentExecution.pickupCell = pickup.pickupCell();
                agentExecution.primitiveExecutor.beginNavigate(pickup.route(), 0.25D);
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
            if (!ActionBudgets.replanDeadlineReached(actionTick, agentExecution.replanDeadlineTick)) {
                return false;
            }
            throw unavailable;
        }
        agentExecution.replanNotBeforeTick = 0L;
        return true;
    }

    private void tickAgentPickupConfirmation(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.CollectVisibleItem collect) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItem
                && PlayerInventoryEvidence.pickupInventoryIncreased(
                agentExecution.pickupInventoryBefore,
                PlayerInventoryEvidence.inventoryItemCount(player, collect.displayedItem()))) {
            completeAgentPrimitive(minecraft, action);
            return;
        }
        if (session.clientTick() - agentExecution.pickupArrivalTick
                >= AgentPrimitivePlanner.PICKUP_CONFIRM_TICKS) {
            failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "pickup_unconfirmed");
        }
    }

    private void completeAgentPrimitive(
            Minecraft minecraft, AgentActionStore.Active action) {
        closeAgentPrimitiveExecutor();
        var collect = (ActionDsl.CollectVisibleItem) agentExecution.primitive;
        int inventoryAfter = PlayerInventoryEvidence.inventoryItemCount(
                Objects.requireNonNull(minecraft.player, "player"), collect.displayedItem());
        agentActions.recordNodeEvidence(
                action.actionId(),
                "item_pickup=" + collect.displayedItem()
                        + ",inventory_before=" + agentExecution.pickupInventoryBefore
                        + ",inventory_after=" + inventoryAfter);
        agentActions.completeNode(action.actionId());
        agentExecution.primitive = null;
        agentExecution.pickupInventoryBefore = -1;
        agentExecution.pickupArrivalTick = -1L;
        agentExecution.pickupCell = null;
        agentExecution.replanning = false;
        agentExecution.replanHeartbeatPending = false;
        agentExecution.replanNotBeforeTick = 0L;
        agentExecution.replanDeadlineTick = 0L;
        advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
    }

    private ActionDsl.CollectVisibleItem activeCollectTarget() {
        if (agentExecution == null) return null;
        if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItem collect) {
            return collect;
        }
        if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItemBatch batch
                && agentExecution.collectBatchIndex >= 0
                && agentExecution.collectBatchIndex < batch.targets().size()) {
            return AgentPrimitivePlanner.collectBatchChild(
                    batch, agentExecution.collectBatchIndex);
        }
        return null;
    }

    private static boolean isCollectPrimitive(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.CollectVisibleItem
                || primitive instanceof ActionDsl.CollectVisibleItemBatch;
    }

    /** Returns false after completing a target or making the action terminal this tick. */
    private boolean reconcileCollectBatchEvidence(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        var batch = (ActionDsl.CollectVisibleItemBatch) agentExecution.primitive;
        CollectBatchEvidence evidence = agentExecution.collectBatchEvidence;
        if (evidence == null
                || agentExecution.collectBatchIndex < 0
                || agentExecution.collectBatchIndex >= batch.targets().size()) {
            failAgentAction(
                    AgentActionStore.FailureCode.INTERNAL_ERROR,
                    false,
                    "collect_batch_state_missing");
            return false;
        }
        var player = Objects.requireNonNull(minecraft.player, "player");
        KnownTraversabilitySnapshot map = agentObservations.requireAgentMap(session);
        long visualBarrierWorldRevision = ActionEvidence.visualBarrierWorldRevision(
                map,
                reconciliationSignals.bindAndSnapshot(
                        Objects.requireNonNull(minecraft.level, "level"),
                        session.worldSessionId()));
        List<Optional<dev.aod.mcmcp.agent.observation.ObservationValues.Aabb>> bounds =
                AgentPrimitivePlanner.visibleBatchItemAabbs(
                        map,
                        agentObservations.agentPlanningFrame(),
                        batch,
                        visualBarrierWorldRevision,
                        session.clientTick(),
                        ActionBudgets.visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
        boolean currentTargetContact = false;
        for (int index = agentExecution.collectBatchIndex;
                index < batch.targets().size(); index++) {
            if (!evidence.credited(index)
                    && bounds.get(index).filter(aabb -> PlayerInventoryEvidence.playerPickupAreaIntersects(
                            player.getBoundingBox(), aabb)).isPresent()) {
                evidence.recordContact(index, session.clientTick());
                currentTargetContact |= index == agentExecution.collectBatchIndex;
            }
        }
        if (currentTargetContact && agentExecution.pickupArrivalTick < 0L) {
            // Contact can happen one navigator tick before it reports SUCCEEDED. Stop owned
            // movement immediately and retain the contact lease while inventory sync catches up.
            closeAgentPrimitiveExecutor();
            agentExecution.pickupArrivalTick = session.clientTick();
        }

        final List<CollectBatchEvidence.Credit> credits;
        try {
            credits = evidence.reconcile(
                    PlayerInventoryEvidence.collectBatchInventoryCounts(player, batch),
                    session.clientTick(),
                    AgentPrimitivePlanner.PICKUP_CONFIRM_TICKS,
                    agentExecution.collectBatchIndex);
        } catch (CollectBatchEvidence.InventoryDecreasedException decreased) {
            failAgentAction(
                    AgentActionStore.FailureCode.WORLD_CHANGED,
                    true,
                    "collect_batch_inventory_decreased");
            return false;
        }
        for (CollectBatchEvidence.Credit credit : credits) {
            agentActions.recordNodeEvidence(
                    action.actionId(),
                    "batch_target[" + credit.targetIndex() + "]="
                            + (credit.incidental() ? "incidentally_collected" : "collected")
                            + ",inventory_before=" + credit.inventoryBefore()
                            + ",inventory_after=" + credit.inventoryAfter());
        }
        if (!evidence.credited(agentExecution.collectBatchIndex)) {
            return true;
        }
        completeCollectBatchTarget(minecraft, action, batch);
        return false;
    }

    private void completeCollectBatchTarget(
            Minecraft minecraft,
            AgentActionStore.Active action,
            ActionDsl.CollectVisibleItemBatch batch) {
        closeAgentPrimitiveExecutor();
        agentExecution.collectBatchIndex++;
        agentExecution.pickupArrivalTick = -1L;
        agentExecution.pickupCell = null;
        agentExecution.replanning = false;
        agentExecution.replanHeartbeatPending = false;
        agentExecution.replanNotBeforeTick = 0L;
        agentExecution.replanDeadlineTick = 0L;
        if (agentExecution.collectBatchIndex < batch.targets().size()) {
            return;
        }
        agentActions.completeNode(action.actionId());
        agentExecution.primitive = null;
        agentExecution.collectBatchEvidence = null;
        advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
    }

    private void tickAgentFishing(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        var outcome = agentExecution.fishing.tickAgentFishing(minecraft, session,
                agentExecution.primitive, agentExecution.latestWorldRevision);
        if (outcome.failure() != null) {
            failAgentAction(outcome.failure().code(), outcome.failure().recoverable(),
                    outcome.failure().evidence().getFirst());
        } else if (outcome.complete()) {
            finishFishingPrimitive(minecraft, action);
        }
    }

    private void finishFishingPrimitive(
            Minecraft minecraft, AgentActionStore.Active action) {
        agentExecution.fishingAimComplete = false;
        closeAgentPrimitiveExecutor();
        agentActions.completeNode(action.actionId());
        agentExecution.primitive = null;
        advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
    }

    private void tickAgentBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KnownTraversabilitySnapshot map,
            ActionDsl.Node block,
            long actionTick) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        if (agentExecution.blockBreakAttempt == null) {
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"),
                    session.worldSessionId());
            long surfaceBarrierWorldRevision = ActionEvidence.surfaceRevisionBarrier(map, reconciliation)
                    .applyAsLong(KnownBreakSafety.breakTarget(block));
            if (!KnownBreakSafety.breakTargetStateMatches(minecraft, block)) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "break_target_changed");
                return;
            }
            if (!AgentPrimitivePlanner.knownSurface(
                            map,
                            agentObservations.agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    KnownBreakSafety.breakTarget(block), KnownBreakSafety.breakFace(block), KnownBreakSafety.breakBlockId(block)),
                            surfaceBarrierWorldRevision)
                    || !KnownBreakSafety.breakSourceControlled(minecraft, block)) {
                requestAgentReplan(actionTick, "break_target_reobservation");
                return;
            }
            try {
                var target = new BlockTarget(
                        KnownBreakSafety.breakTarget(block).dimension(),
                        KnownBreakSafety.breakTarget(block).x(),
                        KnownBreakSafety.breakTarget(block).y(),
                        KnownBreakSafety.breakTarget(block).z());
                var expected = stationaryBreakPort.captureExpectedSource(
                        target, Set.of(KnownBreakSafety.breakBlockId(block)));
                if (block instanceof ActionDsl.BreakKnownBlock exact) {
                    var declared = new BlockStateFingerprint(
                            exact.expectedState().block(), exact.expectedState().properties());
                    if (!expected.equals(declared)) {
                        requestAgentReplan(actionTick, "break_precondition_changed");
                        return;
                    }
                    SafeBreakSourcePolicy.requireKnownBlockCombination(
                            exact.expectedState().block(),
                            exact.toolItem(),
                            exact.expectedDrop());
                }
                int minimumInventoryCount = block instanceof ActionDsl.BreakKnownBlock exact
                        ? exact.minimumInventoryCount()
                        : Math.min(2_304, Math.addExact(
                                PlayerInventoryEvidence.inventoryItemCount(player, KnownBreakSafety.breakExpectedDrop(block)), 1));
                var request = new StationaryBreakRequest(
                        target,
                        expected,
                        new StationaryBreakGoal(
                                KnownBreakSafety.breakExpectedDrop(block), minimumInventoryCount),
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
                    session.clientTick(), KnownBreakSafety.breakSourceControlled(minecraft, block));
            recordBreakEffects(
                    action.actionId(), KnownBreakSafety.breakTarget(block),
                    agentExecution.blockBreakAttempt.drainEffectDeltas());
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

    private void recordBreakEffects(
            UUID actionId,
            ActionDsl.Position target,
            List<KnownBlockBreakAttempt.EffectDelta> effects) {
        String subject = "block:" + target.dimension() + ":"
                + target.x() + "," + target.y() + "," + target.z();
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    "block_break",
                    subject,
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    effect.worldRevision());
        }
    }

    private void tickAgentBoundedInputHold(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.HoldBoundedInputs hold,
            boolean recordTick) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        var progress = agentActions.get(action.actionId()).progress();
        if (agentExecution.boundedInputHold != null
                && agentExecution.boundedInputHold.activeTicks >= hold.durationTicks()) {
            if (!closeBoundedInputHold()) {
                failAgentAction(AgentActionStore.FailureCode.INTERNAL_ERROR, false,
                        "bounded_input_release_failed");
                return;
            }
            agentActions.completeNode(action.actionId());
            agentExecution.primitive = null;
            advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
            return;
        }
        long durationLimit = Duration.ofMillis(
                action.program().effectiveBudget().maxDurationMillis()).toNanos();
        if ((recordTick
                        ? progress.ticks() >= action.program().effectiveBudget().maxTicks()
                        : progress.ticks() > action.program().effectiveBudget().maxTicks())
                || activeElapsedNanos(agentExecution, System.nanoTime()) >= durationLimit) {
            failAgentAction(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false,
                    "bounded_input_duration_budget");
            return;
        }
        String unsafe = boundedInputUnsafeReason(minecraft, session, action, hold);
        if (unsafe != null) {
            failAgentAction(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                    "bounded_input_" + unsafe);
            return;
        }
        try {
            boolean acquired = false;
            if (agentExecution.boundedInputHold == null) {
                Set<BoundedInputLease.Input> inputs = hold.inputs().stream()
                        .map(McmcpRuntime::boundedLeaseInput)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                var lease = BoundedInputLease.acquire(
                        AgentInputState.global(), inputs, System.nanoTime(), Duration.ofSeconds(1));
                agentExecution.boundedInputHold = new BoundedInputExecution(
                        lease, player.position(), player.getHealth() + player.getAbsorptionAmount());
                acquired = true;
            }
            var execution = agentExecution.boundedInputHold;
            if (!acquired && !execution.lease.heartbeat(
                    System.nanoTime(), Duration.ofSeconds(1))) {
                failAgentAction(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                        "bounded_input_lease_expired");
                return;
            }
            if (boundedInputMoves(hold)) {
                double remaining = Math.max(0.0D,
                        action.program().effectiveBudget().maxDistanceBlocks()
                                - progress.distanceTravelled());
                AgentInputState.global().requireGoalMovementSafety(
                        player, level, agentExecution.latestWorldRevision, remaining);
            }
            execution.observeMovement(player.position(), boundedInputMovesHorizontally(hold));
            execution.activeTicks++;
            if (recordTick) agentActions.recordTick(action.actionId());
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP bounded input hold failed", failure);
            failAgentAction(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                    "bounded_input_runtime_failure");
        }
    }

    private String boundedInputUnsafeReason(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.HoldBoundedInputs hold) {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null
                || minecraft.getConnection() == null) return "world_unavailable";
        if (player != agentExecution.playerIdentity || !session.worldReady()
                || !Objects.equals(session.worldSessionId(), agentExecution.worldSessionId)) {
            return "world_session_changed";
        }
        if (!player.isAlive() || player.isDeadOrDying()) return "player_dead";
        if (agentExecution.boundedInputHold != null
                && player.getHealth() + player.getAbsorptionAmount()
                        < agentExecution.boundedInputHold.effectiveHealthBaseline) {
            return "health_decreased";
        }
        if (player.isOnFire()) return "on_fire";
        if (player.isInLava()) return "in_lava";
        if (player.isInWater()) return "in_water";
        if (player.isPassenger() || player.isFallFlying() || player.fallDistance > 0.0F) {
            return "unstable_pose";
        }
        if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())) return "screen_open";
        if (minecraft.gui.overlay() != null) return "overlay_open";
        if (screenOwnership.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return "screen_owner_active";
        }
        if (agentObservations.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE) {
            return "local_safety_changed";
        }
        BlockPos feet = BlockPos.containing(player.position());
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())
                || !level.getWorldBorder().isWithinBounds(feet)) return "unknown_or_unloaded";
        if (agentExecution.boundedInputHold != null
                && agentExecution.boundedInputHold.stalledTicks >= 10) return "movement_blocked";
        if (action.program().effectiveBudget().maxDistanceBlocks()
                - agentActions.get(action.actionId()).progress().distanceTravelled() <= 0.0D
                && boundedInputMoves(hold)) return "distance_limit";
        if (hold.targetGuard().isEmpty()) return null;
        var guard = hold.targetGuard().orElseThrow();
        if (!guard.target().dimension().equals(level.dimension().identifier().toString())) {
            return "target_dimension_changed";
        }
        var target = new BlockPos(guard.target().x(), guard.target().y(), guard.target().z());
        if (!level.isLoaded(target) || !level.getWorldBorder().isWithinBounds(target)
                || !player.isWithinBlockInteractionRange(target, 0.0D)
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)
                || hit.getDirection() != Direction.valueOf(guard.face().name())) {
            return "target_face_or_reach_changed";
        }
        var expected = new BlockStateFingerprint(
                guard.expectedState().block(), guard.expectedState().properties());
        if (!expected.equals(MinecraftStationaryBreakPort.fingerprintForPolicy(
                level.getBlockState(target)))) return "target_state_changed";
        var selected = player.getMainHandItem();
        if (selected.isEmpty() || !hold.selectedItem().orElseThrow().equals(
                BuiltInRegistries.ITEM.getKey(selected.getItem()).toString())) {
            return "selected_item_changed";
        }
        if (agentExecution.boundedInputHold != null
                && player.position().distanceToSqr(agentExecution.boundedInputHold.startPosition)
                        > 1.0D / (1024.0D * 1024.0D)) return "station_changed";
        return null;
    }

    private boolean closeBoundedInputHold() {
        if (agentExecution == null || agentExecution.boundedInputHold == null) return true;
        try {
            agentExecution.boundedInputHold.lease.close();
            agentExecution.boundedInputHold = null;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP bounded input release failed", failure);
            return false;
        }
    }

    private static boolean boundedInputMoves(ActionDsl.HoldBoundedInputs hold) {
        return hold.inputs().stream().anyMatch(input -> switch (input) {
            case FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK -> true;
            case ATTACK, USE -> false;
        });
    }

    private static boolean boundedInputMovesHorizontally(ActionDsl.HoldBoundedInputs hold) {
        return hold.inputs().stream().anyMatch(input -> switch (input) {
            case FORWARD, BACK, LEFT, RIGHT -> true;
            case JUMP, SNEAK, ATTACK, USE -> false;
        });
    }

    private static BoundedInputLease.Input boundedLeaseInput(ActionDsl.BoundedInput input) {
        return BoundedInputLease.Input.valueOf(input.name());
    }

    private void tickAgentCobblestoneGenerator(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.OperateKnownCobblestoneGenerator operation) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        ActionDsl.BreakKnownBlock block = KnownBreakSafety.cobblestoneGeneratorBreak(operation);
        if (agentExecution.cobblestoneGeneratorAttempt == null) {
            int currentCount = PlayerInventoryEvidence.inventoryItemCount(player, operation.expectedDrop());
            if (currentCount < operation.minimumInventoryCount()
                    && operation.minimumInventoryCount() - currentCount > operation.maxBreaks()) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "cobblestone_goal_exceeds_max_breaks");
                return;
            }
            int toolSlot = KnownBreakSafety.findDurableHotbarTool(
                    player, operation.toolItem(), operation.maxBreaks());
            if (toolSlot < 0 || !KnownBreakSafety.inventoryCanReceiveKnownBreakDrops(player, action.program())) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        toolSlot < 0
                                ? "required_iron_pickaxe_unavailable"
                                : "inventory_full");
                return;
            }
            if (!KnownBreakSafety.breakTargetStateMatches(minecraft, block)
                    || !KnownBreakSafety.breakSourceControlled(minecraft, block)) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "cobblestone_generator_target_or_face_changed");
                return;
            }
            try {
                player.getInventory().setSelectedSlot(toolSlot);
                agentExecution.agentSelectedSlot = toolSlot;
                var target = new BlockTarget(
                        operation.target().dimension(), operation.target().x(),
                        operation.target().y(), operation.target().z());
                BlockStateFingerprint observed = stationaryBreakPort.captureExpectedSource(
                        target, Set.of("minecraft:cobblestone"));
                var expected = new BlockStateFingerprint(
                        operation.expectedState().block(),
                        operation.expectedState().properties());
                if (!expected.equals(observed)) {
                    failAgentAction(
                            AgentActionStore.FailureCode.WORLD_CHANGED,
                            true,
                            "cobblestone_generator_state_changed");
                    return;
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
                agentExecution.cobblestoneGeneratorAttempt = new StationaryBreakOperation(
                        stationaryBreakPort, request, operation.maxBreaks(), session.clientTick());
                agentExecution.cobblestoneGeneratorCheckpoint = 0L;
            } catch (RuntimeException | LinkageError failure) {
                McmcpMod.LOGGER.error(
                        "MCMCP cobblestone-generator operation could not start", failure);
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "cobblestone_generator_start_failed");
                return;
            }
        }

        // Air is the expected neutral regeneration wait. Once cobblestone is present again,
        // exact target, state, reach, tool, and the operation's unchanged view are rechecked.
        // The hit face may legitimately flip at the same coordinate as the block regenerates.
        var generatorSnapshot = agentExecution.cobblestoneGeneratorAttempt.snapshot();
        if ("execute".equals(generatorSnapshot.phase())
                && !KnownBreakSafety.breakSourceControlled(minecraft, block, false)) {
            failAgentAction(
                    AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                    true,
                    "cobblestone_generator_stationary_face_changed");
            return;
        }

        final StationaryBreakOperation.TickResult result;
        try {
            result = agentExecution.cobblestoneGeneratorAttempt.tick();
            recordCobblestoneGeneratorCheckpoints(
                    action.actionId(), operation, result.snapshot());
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error(
                    "MCMCP cobblestone-generator confirmation failed", failure);
            failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    "cobblestone_generator_confirmation_failed");
            return;
        }
        switch (result.status()) {
            case RUNNING -> { }
            case SUCCEEDED -> {
                try {
                    agentExecution.cobblestoneGeneratorAttempt.close();
                    agentExecution.cobblestoneGeneratorAttempt = null;
                } catch (RuntimeException | LinkageError releaseFailure) {
                    failAgentAction(
                            AgentActionStore.FailureCode.INTERNAL_ERROR,
                            true,
                            "cobblestone_generator_release_failed");
                    return;
                }
                agentActions.completeNode(action.actionId());
                agentExecution.primitive = null;
                advanceAgentProgram(
                        minecraft, agentActions.get(action.actionId()).progress());
            }
            case MAX_BREAKS_REACHED -> failAgentAction(
                    AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                    true,
                    "cobblestone_generator_max_breaks_reached");
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
                failAgentAction(
                        code,
                        failure == null || failure.retryable(),
                        "cobblestone_generator_"
                                + (failure == null ? "failed"
                                        : failure.code().toLowerCase(Locale.ROOT)));
            }
        }
    }

    private void recordCobblestoneGeneratorCheckpoints(
            UUID actionId,
            ActionDsl.OperateKnownCobblestoneGenerator operation,
            RoutineSnapshot snapshot) {
        long checkpoint = snapshot.checkpoint().seq();
        while (agentExecution.cobblestoneGeneratorCheckpoint < checkpoint) {
            agentExecution.cobblestoneGeneratorCheckpoint++;
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
                            "cycle", agentExecution.cobblestoneGeneratorCheckpoint),
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
        if (agentExecution.cobblestoneGeneratorUnknownRecorded) return;
        Object rawAttempts = snapshot.diagnostics().get("attempts");
        long attempts = rawAttempts instanceof Number number ? number.longValue() : 0L;
        if (attempts <= snapshot.checkpoint().seq()) return;
        agentActions.recordEffect(
                agentExecution.actionId,
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
        agentExecution.cobblestoneGeneratorUnknownRecorded = true;
    }

    private void tickAgentBlockMutation(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            long actionTick) {
        ActionDsl.Node mutation = agentExecution.primitive;
        if (ActionBudgets.isMutationBatch(mutation)) {
            if (!bindMutationBatchTarget(minecraft, session, action, actionTick)) {
                return;
            }
            mutation = agentExecution.mutationBatchTarget;
        }
        if (agentExecution.blockMutationAttempt == null) {
            SemanticActionRequest request = ConstructionRequests.blockMutationRequest(
                    mutation,
                    ActionBudgets.isMutationBatch(agentExecution.primitive)
                            ? agentExecution.mutationBatchTargetAim
                            : agentExecution.mutationAims.get(agentExecution.primitive.id()));
            long deadline = Math.addExact(
                    session.clientTick(), AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND);
            agentExecution.blockMutationAttempt = new KnownBlockMutationAttempt(
                    semanticActionPort, request, session.clientTick(), deadline);
        }
        KnownBlockMutationAttempt.TickResult result =
                agentExecution.blockMutationAttempt.tick(session.clientTick());
        if (result.dispatchedThisTick()) {
            armBatchTillSettlingAllowance(minecraft, mutation);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> {
                if (ActionBudgets.isMutationBatch(agentExecution.primitive)
                        && ActionBudgets.mutationBatchDisposition(
                                agentExecution.mutationBatchIndex,
                                agentExecution.mutationBatchPlan.steps().size(),
                                false) != BatchTargetDisposition.STOP) {
                    throw new IllegalStateException("Failed batch target must stop dispatch");
                }
                if (ActionBudgets.retryableMutationAimFailure(result.evidence())) {
                    if (!ActionBudgets.mutationAimRetriesAllowed(agentExecution.primitive)) {
                        failAgentAction(
                                AgentActionStore.FailureCode.PATH_BLOCKED,
                                true,
                                "batch_aim_raycast_unavailable");
                    } else {
                        retryAgentMutationAim(minecraft, action, result.evidence());
                    }
                } else {
                    failAgentAction(
                            AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                            true,
                            result.evidence());
                }
            }
            case SUCCEEDED -> {
                agentExecution.blockMutationAttempt = null;
                if (result.performed()) {
                    if (mutation instanceof ActionDsl.TillKnownBlock
                            || mutation instanceof ActionDsl.OpenKnownFenceGate
                            || mutation instanceof ActionDsl.OpenKnownPassage) {
                        agentActions.recordInteraction(action.actionId());
                    } else if (mutation instanceof ActionDsl.PlantKnownWheat) {
                        agentActions.recordBlockPlace(action.actionId());
                    } else {
                        agentActions.recordBlockBreak(action.actionId());
                    }
                }
                if (ActionBudgets.isMutationBatch(agentExecution.primitive)) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), ActionBudgets.batchTargetTrace(mutation));
                    BatchTargetDisposition disposition = ActionBudgets.mutationBatchDisposition(
                            agentExecution.mutationBatchIndex,
                            agentExecution.mutationBatchPlan.steps().size(),
                            true);
                    agentExecution.mutationBatchIndex++;
                    agentExecution.mutationBatchTarget = null;
                    agentExecution.mutationBatchTargetAim = null;
                    agentExecution.mutationBatchTargetBound = false;
                    agentExecution.mutationBatchTargetDeadlineTick = 0L;
                    agentExecution.mutationAimFailures = 0;
                    if (disposition == BatchTargetDisposition.COMPLETE) {
                        agentActions.completeNode(action.actionId());
                        agentExecution.primitive = null;
                        agentExecution.replanning = false;
                        agentExecution.replanNotBeforeTick = 0L;
                        agentExecution.replanDeadlineTick = 0L;
                        advanceAgentProgram(
                                minecraft, agentActions.get(action.actionId()).progress());
                    }
                } else {
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
    }

    private void tickAgentFrameItem(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.frameItemAttempt == null) {
            var aim = Objects.requireNonNull(agentExecution.frameItemAim, "admitted frame item aim");
            boolean remove = agentExecution.primitive instanceof ActionDsl.RemoveVisibleFrameItem;
            var request = new FrameItemPort.Request(
                    remove ? FrameItemPort.Mode.REMOVE : FrameItemPort.Mode.INSERT,
                    aim.entityRef(),
                    (remove ? aim.expectedItem() : aim.insertedItem()).orElseThrow(),
                    aim.rotation(), session.worldSessionId(), session.dimension(),
                    new FrameItemPort.AimPoint(aim.aimPoint().x, aim.aimPoint().y, aim.aimPoint().z),
                    agentExecution.maxCameraDegreesPerTick);
            agentExecution.frameItemAttempt = new FrameItemAttempt(
                    frameItemPort, request, session.clientTick(),
                    Math.addExact(session.clientTick(), ActionDslCompiler.FRAME_ITEM_TICKS));
        }
        FrameItemAttempt attempt = agentExecution.frameItemAttempt;
        FrameItemAttempt.TickResult result;
        try {
            result = attempt.tick(session.clientTick());
        } finally {
            recordFrameItemUsage(action.actionId(), attempt);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    false, result.evidence());
            case SUCCEEDED -> {
                agentExecution.frameItemAttempt = null;
                agentActions.recordNodeEvidence(action.actionId(), "frame_display_server_confirmed");
                agentActions.completeNode(action.actionId());
                agentExecution.primitive = null;
                advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
            }
        }
    }

    private void recordFrameItemUsage(UUID actionId, FrameItemAttempt attempt) {
        int interactions = attempt.drainInteractionDelta();
        for (int count = 0; count < interactions; count++) {
            agentActions.recordInteraction(actionId);
        }
        var aim = Objects.requireNonNull(agentExecution.frameItemAim, "admitted frame item aim");
        String kind = agentExecution.primitive instanceof ActionDsl.RemoveVisibleFrameItem
                ? "frame_item_remove" : "frame_item_insert";
        for (var effect : attempt.drainEffectDeltas()) {
            agentActions.recordEffect(actionId, kind, aim.entityType(),
                    effect.observedBefore(), effect.observedAfter(), effect.verification(),
                    effect.clientTick(), effect.worldRevision());
        }
    }

    private MinecraftPhaseFiveInventoryPort.InitialOpenWitness initialContainerOpenWitness() {
        var execution = agentExecution;
        var ready = MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY;
        var unsafe = MinecraftPhaseFiveInventoryPort.InitialOpenWitness.SAFETY_CHANGED;
        if (execution == null || execution.surfaceRecovery == null
                || !execution.surfaceRecovery.applies(execution.primitive)) return ready;
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        var captured = execution.surfaceAdmission;
        var lock = arming.snapshot(session.worldSessionId());
        if (captured == null || !ActionAdmission.sameAdmissionSession(captured.session(), session)
                || minecraft.player != execution.playerIdentity
                || lock.mode() != LocalArmingState.Mode.AGENT
                || lock.controlEpoch() != captured.control().controlEpoch() + 1L
                || !lock.capabilities().equals(captured.control().capabilities())
                || agentObservations.localSafety() != LocalObservationProjector.CurrentSafety.CONTINUE
                || McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F != captured.cameraDegreesPerTick()
                || minecraft.isMultiplayerServer() != captured.multiplayerServer()
                || multiplayerPolicyAllows(minecraft) != captured.multiplayerAllowed()) return unsafe;
        try {
            var map = agentObservations.requireAgentMap(session);
            var reconciliation = reconciliationSignals.bindAndSnapshot(minecraft.level, session.worldSessionId());
            ActionEvidence.visualBarrierWorldRevision(map, reconciliation);
            var decision = actionAdmission.surfaceRecoveryFailure(minecraft, session, execution.surfaceRecovery, RendererRecoveryStage.INITIAL_OPEN);
            if (decision.isPresent()) {
                return MinecraftPhaseFiveInventoryPort.InitialOpenWitness.valueOf(decision.orElseThrow().name());
            }
            var target = SurfacePreflightRecovery.target(execution.primitive);
            AgentPrimitivePlanner.requireKnownSurface(map,
                    agentObservations.agentPlanningFrame(execution.primitive, execution.surfaceRecovery.lease()),
                    target.position(), target.block(),
                    ActionEvidence.primitiveSurfaceRevisionBarrier(execution.primitive, map, reconciliation)
                            .applyAsLong(target.position()));
            actionAdmission.rendererRecoveryRevalidated(execution.surfaceRecovery, RendererRecoveryStage.INITIAL_OPEN);
            return ready;
        } catch (AgentPrimitivePlanner.PlanningException mismatch) {
            return MinecraftPhaseFiveInventoryPort.InitialOpenWitness.SURFACE_REOBSERVATION_MISMATCH;
        } catch (RuntimeException unavailable) {
            return unsafe;
        }
    }

    private void applyMenuPrimitiveOutcome(
            Minecraft minecraft, AgentActionStore.Active action, PrimitiveOutcome outcome) {
        if (outcome.failure() != null) {
            failAgentAction(outcome.failure().code(), outcome.failure().recoverable(),
                    outcome.failure().evidence().getFirst(),
                    outcome.failure().evidence().subList(1, outcome.failure().evidence().size())
                            .toArray(String[]::new));
        } else if (outcome.complete()) {
            agentActions.completeNode(action.actionId());
            agentExecution.primitive = null;
            agentExecution.replanning = false;
            agentExecution.replanNotBeforeTick = 0L;
            agentExecution.replanDeadlineTick = 0L;
            advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
        }
    }

    private boolean bindMutationBatchTarget(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            long actionTick) {
        if (agentExecution.mutationBatchTargetBound) {
            return true;
        }
        AgentPrimitivePlanner.MutationBatchPlan plan = agentExecution.mutationBatchPlan;
        if (plan == null || agentExecution.mutationBatchIndex >= plan.steps().size()) {
            failAgentAction(
                    AgentActionStore.FailureCode.INTERNAL_ERROR, false, "mutation_batch_plan_missing");
            return false;
        }
        AgentPrimitivePlanner.MutationBatchStep step =
                plan.steps().get(agentExecution.mutationBatchIndex);
        try {
            var player = Objects.requireNonNull(minecraft.player, "player");
            var map = agentObservations.requireAgentMap(session);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"), session.worldSessionId());
            AgentPrimitivePlanner.Pose currentPose = ActionPlanning.playerPose(player, session.dimension());
            var analysis = actionAdmission.analyzePrimitive(
                    action.program().request().program(),
                    step.primitive(),
                    map,
                    currentPose,
                    agentObservations.agentPlanningFrame(),
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                    ActionEvidence.visualBarrierWorldRevision(map, reconciliation),
                    ActionEvidence.surfaceRevisionBarrier(map, reconciliation),
                    () -> true);
            ActionDslCompiler.Cost cost = analysis.worstCase(step.primitive()).orElseThrow();
            AgentPrimitivePlanner.MutationAim freshAim = analysis.mutationAims()
                    .get(step.primitive().id());
            if (freshAim == null) {
                throw new IllegalStateException("Fresh batch target aim is unavailable");
            }
            ActionDslCompiler.Cost requiredRemainder = ActionBudgets.mutationBatchRequiredRemainder(
                    plan,
                    agentExecution.mutationBatchIndex,
                    currentPose,
                    freshAim,
                    cost,
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
            AgentActionStore.Progress progress = agentActions.get(action.actionId()).progress();
            if (!ActionBudgets.fitsMutationBatchRemainder(
                    progress,
                    agentExecution.occurrenceBaseline,
                    agentExecution.occurrenceLimit,
                    action.program().effectiveBudget(),
                    requiredRemainder,
                    activeElapsedNanos(agentExecution, System.nanoTime()))) {
                failAgentAction(
                        AgentActionStore.FailureCode.BUDGET_EXCEEDED,
                        false,
                        "batch_target_budget");
                return false;
            }
            agentExecution.mutationBatchTarget = step.primitive();
            agentExecution.mutationBatchTargetAim = freshAim;
            agentExecution.mutationBatchTargetBound = true;
            agentExecution.mutationBatchTargetDeadlineTick = 0L;
            agentExecution.replanning = false;
            agentActions.setPhase(
                    action.actionId(), AgentActionStore.Phase.EXECUTING,
                    "batch_target_reproved");
            return true;
        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
            if (!releaseAgentInputsForHold(minecraft, "batch_reproof_input_release_failed")) {
                return false;
            }
            if (agentExecution.mutationBatchTargetDeadlineTick == 0L) {
                agentExecution.mutationBatchTargetDeadlineTick = Math.addExact(
                        actionTick, AgentPrimitivePlanner.MUTATION_BATCH_REPROOF_TICKS);
                agentActions.setPhase(
                        action.actionId(), AgentActionStore.Phase.REPLANNING,
                        "batch_" + unavailable.code().name().toLowerCase(Locale.ROOT));
            }
            if (ActionBudgets.replanDeadlineReached(
                    actionTick, agentExecution.mutationBatchTargetDeadlineTick)) {
                failAgentAction(
                        AgentActionStore.FailureCode.PATH_BLOCKED,
                        true,
                        "batch_" + unavailable.code().name().toLowerCase(Locale.ROOT));
            }
            return false;
        }
    }

    private void armBatchTillSettlingAllowance(Minecraft minecraft, ActionDsl.Node mutation) {
        agentExecution.tillSettlingAllowance = 0.0D;
        agentExecution.tillSettlingTarget = null;
        agentExecution.tillSettlingDeadlineTick = 0L;
        if (!ActionBudgets.isMutationBatch(agentExecution.primitive)
                || !(mutation instanceof ActionDsl.TillKnownBlock till)
                || minecraft.player == null) {
            return;
        }
        var player = minecraft.player;
        if (Mth.floor(player.getY()) == till.target().y() + 1
                && Mth.floor(player.getX()) == till.target().x()
                && Mth.floor(player.getZ()) == till.target().z()) {
            agentExecution.tillSettlingAllowance = 1.0D / 16.0D;
            agentExecution.tillSettlingTarget = till.target();
            agentExecution.tillSettlingDeadlineTick = Math.addExact(
                    agentActions.get(agentExecution.actionId).progress().ticks(), 2L);
        }
    }

    private void retryAgentMutationAim(
            Minecraft minecraft, AgentActionStore.Active action, String evidence) {
        agentExecution.blockMutationAttempt.close();
        agentExecution.blockMutationAttempt = null;
        agentExecution.mutationAimFailures++;
        if (!ActionBudgets.mutationAimRetryAllowed(agentExecution.mutationAimFailures)) {
            failAgentAction(
                    AgentActionStore.FailureCode.PATH_BLOCKED,
                    true,
                    "aim_raycast_unavailable_repeated");
            return;
        }
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
        if (!releaseAgentInputsForHold(minecraft, "mutation_aim_input_release_failed")) {
            return;
        }
        agentActions.setPhase(action.actionId(), AgentActionStore.Phase.REPLANNING, evidence);
    }

    private void requestAgentReplan(long actionTick, String reason) {
        if (ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
            failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED,
                    false, "frame_item_replan_required");
            return;
        }
        closeAgentPrimitiveExecutor();
        if (!releaseAgentInputsForHold(
                Minecraft.getInstance(), "replan_input_release_failed")) {
            return;
        }
        agentExecution.breakAimComplete = false;
        agentExecution.replanHeartbeatPending = false;
        agentExecution.replanNotBeforeTick = actionTick + 1L;
        if (isCollectPrimitive(agentExecution.primitive)) {
            // Arrival is evidence about the old pose only. Preserve the occurrence inventory
            // baseline, but force a fresh witness/route bind after any correction or safety replan.
            agentExecution.pickupArrivalTick = -1L;
            agentExecution.pickupCell = null;
        }
        if (agentExecution.replanning) return;
        agentActions.setPhase(
                agentExecution.actionId, AgentActionStore.Phase.REPLANNING, reason);
        agentExecution.replanning = true;
        agentExecution.replanDeadlineTick = RecoveryPlanning.agentReplanDeadlineTick(
                agentExecution.primitive,
                actionTick,
                agentExecution.occurrenceBaseline.ticks(),
                agentExecution.occurrenceLimit.ticks());
    }

    private MinecraftRecoveryGovernor.TickResult tickAgentRecovery(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long nowNanos) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var map = agentObservations.requireAgentMap(session);
        var local = Objects.requireNonNull(
                agentObservations.localObservation(), "local safety observation");
        if (local.worldRevision() != map.worldRevision()) {
            throw new IllegalStateException("local safety observation crossed a world revision");
        }
        if (recoveryGovernor == null) {
            recoveryGovernor = new MinecraftRecoveryGovernor(minecraft);
        }
        long clientTick = RecoveryPlanning.recoveryEvidenceClientTick(session);
        var evidence = recoveryEvidence(player, session, map, local, clientTick);
        return recoveryGovernor.tick(
                evidence,
                RecoveryPlanning.recoveryCandidates(
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
            long clientTick) {
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
        RecoveryHazards hazards = RecoveryPlanning.recoveryHazards(
                current.fluid(),
                current.hazard(),
                player.isInLava(),
                player.onGround(),
                player.getDeltaMovement().y,
                descentSinceGround);
        return new MinecraftRecoveryGovernor.Evidence(
                clientTick,
                session.worldSessionId(),
                map.dimension(),
                map.worldRevision(),
                new MinecraftRecoveryGovernor.Position(
                        player.getX(), player.getY(), player.getZ()),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getAirSupply(),
                player.isUnderWater(),
                RecoveryPlanning.effectDuration(player, MobEffects.WATER_BREATHING),
                player.isOnFire(),
                Math.max(0, player.getRemainingFireTicks()),
                RecoveryPlanning.effectDuration(player, MobEffects.FIRE_RESISTANCE),
                hazards.inLava(),
                current.suffocation(),
                hazards.onGround(),
                hazards.verticalVelocity(),
                hazards.descentSinceGround(),
                landing,
                player.hurtTime > 0 ? clientTick : -1L,
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

    private record PendingAgentAdmission(
            UUID actionId,
            PreparedAgentAction prepared,
            KillZoneAdmission killZoneAdmission,
            ScopedEntityAttackConsentTransportBridge.ResponseCapability transportApproval) {
        private PendingAgentAdmission {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(prepared, "prepared");
            if (transportApproval != null && killZoneAdmission == null) {
                throw new IllegalArgumentException(
                        "transport approval requires a kill-zone admission");
            }
        }
    }

    enum AgentTerminalKind { SUCCESS, FAILURE, CANCEL }

    record PendingAgentTerminal(
            UUID actionId,
            AgentTerminalKind kind,
            AgentActionStore.Failure failure) {
        PendingAgentTerminal {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(kind, "kind");
            if ((kind == AgentTerminalKind.FAILURE) != (failure != null)) {
                throw new IllegalArgumentException(
                        "failure payload must be present only for FAILURE terminal intent");
            }
        }

        private static PendingAgentTerminal success(UUID actionId) {
            return new PendingAgentTerminal(actionId, AgentTerminalKind.SUCCESS, null);
        }

        private static PendingAgentTerminal failure(
                UUID actionId, AgentActionStore.Failure failure) {
            return new PendingAgentTerminal(
                    actionId, AgentTerminalKind.FAILURE,
                    Objects.requireNonNull(failure, "failure"));
        }

        private static PendingAgentTerminal cancel(UUID actionId) {
            return new PendingAgentTerminal(actionId, AgentTerminalKind.CANCEL, null);
        }
    }

    private static double remainingDistance(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            AgentExecution execution) {
        double global = budget.maxDistanceBlocks() - used.distanceTravelled();
        double occurrence = execution.occurrenceLimit.distanceBlocks()
                - ActionBudgets.consumedDistance(used, execution.occurrenceBaseline);
        return Math.max(0.0D, Math.min(global, occurrence));
    }

    private static double remainingCameraDegrees(
            AgentActionStore.Progress used,
            ActionDsl.Budget budget,
            AgentExecution execution) {
        double global = budget.maxCameraDegrees() - used.cameraDegrees();
        double occurrence = execution.occurrenceLimit.cameraDegrees()
                - ActionBudgets.consumedCamera(used, execution.occurrenceBaseline);
        return Math.max(0.0D, Math.min(global, occurrence));
    }

    private static boolean fitsOccurrenceRemaining(
            AgentActionStore.Progress used,
            AgentExecution execution,
            ActionDslCompiler.Cost next) {
        var baseline = Objects.requireNonNull(execution.occurrenceBaseline, "occurrenceBaseline");
        var limit = Objects.requireNonNull(execution.occurrenceLimit, "occurrenceLimit");
        return ActionBudgets.fitsOccurrenceBudget(used, baseline, limit, next);
    }

    private static boolean occurrenceBudgetExceeded(
            AgentActionStore.Progress used, AgentExecution execution) {
        var baseline = Objects.requireNonNull(execution.occurrenceBaseline, "occurrenceBaseline");
        var limit = Objects.requireNonNull(execution.occurrenceLimit, "occurrenceLimit");
        return used.motionOverflowed()
                || ActionBudgets.consumedDurationMillis(used, baseline) > limit.durationMillis()
                || ActionBudgets.consumedTicks(used, baseline) > limit.ticks()
                || ActionBudgets.consumedDistance(used, baseline) > limit.distanceBlocks() + 1.0e-9D
                || ActionBudgets.consumedCamera(used, baseline) > limit.cameraDegrees() + 1.0e-9D
                || ActionBudgets.consumedInteractions(used, baseline) > limit.interactions()
                || ActionBudgets.consumedBreaks(used, baseline) > limit.blocksBroken()
                || ActionBudgets.consumedPlacements(used, baseline) > limit.blocksPlaced();
    }

    private static boolean occurrenceBudgetExceededAfterPrimitive(
            AgentActionStore.Progress used,
            AgentExecution execution,
            MinecraftActionPrimitiveExecutor.Status status) {
        Objects.requireNonNull(status, "status");
        return occurrenceBudgetExceeded(used, execution);
    }

    private void recordAgentMotion(
            UUID actionId, net.minecraft.client.player.LocalPlayer player) {
        var position = player.position();
        double distance = position.distanceTo(agentExecution.lastPosition);
        double camera = ActionBudgets.cameraDelta(
                player.getYRot(), player.getXRot(),
                agentExecution.lastYaw, agentExecution.lastPitch);
        var settlingTarget = agentExecution.tillSettlingTarget;
        long currentTick = agentActions.get(actionId).progress().ticks();
        var level = Minecraft.getInstance().level;
        boolean settlingWindow = settlingTarget != null
                && currentTick <= agentExecution.tillSettlingDeadlineTick
                && !agentExecution.primitiveExecutor.active()
                && level != null
                && "minecraft:farmland".equals(BuiltInRegistries.BLOCK.getKey(
                                level.getBlockState(new BlockPos(
                                        settlingTarget.x(),
                                        settlingTarget.y(),
                                        settlingTarget.z())).getBlock())
                        .toString())
                && Mth.floor(agentExecution.lastPosition.x) == settlingTarget.x()
                && Mth.floor(agentExecution.lastPosition.z) == settlingTarget.z()
                && Mth.floor(position.x) == settlingTarget.x()
                && Mth.floor(position.z) == settlingTarget.z();
        var movement = AgentInputState.global().movementSnapshot();
        boolean inputNeutral = !movement.forward()
                && !movement.backward()
                && !movement.left()
                && !movement.right()
                && !movement.jump();
        double settlingCredit = ActionBudgets.batchTillSettlingCredit(
                agentExecution.lastPosition,
                position,
                agentExecution.tillSettlingAllowance,
                settlingWindow,
                inputNeutral);
        if (settlingCredit > 0.0D
                || distance > 1.0e-9D
                || currentTick > agentExecution.tillSettlingDeadlineTick) {
            agentExecution.tillSettlingAllowance = 0.0D;
            agentExecution.tillSettlingTarget = null;
            agentExecution.tillSettlingDeadlineTick = 0L;
        }
        agentActions.recordMotion(actionId, Math.max(0.0D, distance - settlingCredit), camera);
        if (settlingCredit > 0.0D) {
            agentActions.recordPassiveMotion(actionId, settlingCredit, "farmland_settling");
        }
        agentExecution.lastPosition = position;
        agentExecution.lastYaw = player.getYRot();
        agentExecution.lastPitch = player.getXRot();
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
        long elapsed = ActionBudgets.nonNegativeNanoElapsed(startedAtNanos, nowNanos);
        return pausedNanos >= elapsed ? 0L : elapsed - pausedNanos;
    }

    private boolean closeAgentPrimitiveExecutor() {
        if (agentExecution == null) return true;
        boolean closed = true;
        if (!closeBoundedInputHold()) closed = false;
        try {
            agentExecution.primitiveExecutor.close();
        } catch (RuntimeException | LinkageError failure) {
            closed = false;
            McmcpMod.LOGGER.error("MCMCP Action DSL input release failed", failure);
        }
        if (agentExecution.blockBreakAttempt != null) {
            KnownBlockBreakAttempt breaking = agentExecution.blockBreakAttempt;
            try {
                breaking.close();
                agentExecution.blockBreakAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-face break release failed", failure);
            } finally {
                try {
                    if (KnownBreakSafety.isKnownBreak(agentExecution.primitive)) {
                        recordBreakEffects(
                                agentExecution.actionId,
                                KnownBreakSafety.breakTarget(agentExecution.primitive),
                                breaking.drainEffectDeltas());
                    }
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-block break effect capture failed", failure);
                }
            }
        }
        if (agentExecution.cobblestoneGeneratorAttempt != null) {
            try {
                if (agentExecution.primitive
                        instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
                    RoutineSnapshot snapshot =
                            agentExecution.cobblestoneGeneratorAttempt.snapshot();
                    recordCobblestoneGeneratorCheckpoints(
                            agentExecution.actionId, operation, snapshot);
                    recordUnconfirmedCobblestoneGeneratorDispatch(operation, snapshot);
                }
                agentExecution.cobblestoneGeneratorAttempt.close();
                agentExecution.cobblestoneGeneratorAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error(
                        "MCMCP cobblestone-generator release failed", failure);
            }
        }
        if (agentExecution.blockMutationAttempt != null) {
            try {
                agentExecution.blockMutationAttempt.close();
                agentExecution.blockMutationAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-block mutation release failed", failure);
            }
        }
        if (agentExecution.frameItemAttempt != null) {
            FrameItemAttempt frameItem = agentExecution.frameItemAttempt;
            try {
                frameItem.close();
                agentExecution.frameItemAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (frameItem.releaseStatus() != FrameItemAttempt.ReleaseStatus.PROGRESSING) {
                    McmcpMod.LOGGER.error("MCMCP frame-item release failed", failure);
                }
            } finally {
                try {
                    recordFrameItemUsage(agentExecution.actionId, frameItem);
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error("MCMCP frame-item effect capture failed", failure);
                }
            }
        }
        if (!agentExecution.menuPrimitives.close(
                agentExecution.primitive, agentExecution.latestWorldRevision)) closed = false;
        if (!agentExecution.fishing.close(Minecraft.getInstance(),
                sessions.snapshot().clientTick(), agentExecution.latestWorldRevision)) closed = false;
        agentExecution.breakAimComplete = false;
        agentExecution.fishingAimComplete = false;
        return closed;
    }

    private boolean closeRecoveryGovernor() {
        if (recoveryGovernor == null) return true;
        try {
            recoveryGovernor.close();
            recoveryGovernor = null;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP recovery input release failed", failure);
            return false;
        }
    }

    private void failAgentAction(
            AgentActionStore.FailureCode code, boolean recoverable, String evidence,
            String... diagnostics) {
        // Do not publish a terminal snapshot while an await worker could still observe an
        // Agent-owned key as pressed. Input release must happen first; READY is published only
        // after the terminal Action snapshot has awakened its waiters.
        var active = agentActions.active();
        if (active.isEmpty()) {
            releaseAgentControl(Minecraft.getInstance());
            return;
        }
        closePendingKillZoneEffectForTerminal(evidence);
        var failureEvidence = new ArrayList<String>(1 + diagnostics.length);
        failureEvidence.add(evidence);
        failureEvidence.addAll(List.of(diagnostics));
        var terminal = PendingAgentTerminal.failure(
                active.orElseThrow().actionId(),
                new AgentActionStore.Failure(code, recoverable, failureEvidence));
        if (!releaseAgentControl(Minecraft.getInstance())) {
            rememberPendingAgentTerminal(terminal);
            retainReadyAfterDeferredAgentRelease();
            return;
        }
        if (publishAgentTerminal(terminal)) {
            returnControlReady();
        }
    }

    private void closePendingKillZoneEffectForTerminal(String reason) {
        if (agentExecution == null || agentExecution.killZone == null) return;
        agentExecution.killZone.closePendingEffect(sessions.snapshot(), agentExecution.latestWorldRevision);
    }

    private void finishAgentControlReady(Minecraft minecraft) {
        if (releaseAgentControl(minecraft)) {
            returnControlReady();
        } else {
            retainReadyAfterDeferredAgentRelease();
        }
    }

    private void returnControlReady() {
        // READY is both public state and the physical-input release edge. Do not publish it while
        // any Agent cleanup reference is still retained for the next bounded retry.
        if (pendingAgentInputRelease
                || pendingAgentTerminal != null
                || agentExecution != null
                || pendingAgentAdmission != null) {
            retainReadyAfterDeferredAgentRelease();
            return;
        }
        var session = sessions.snapshot();
        if (session.worldSessionId() != null) {
            arming.completeAction(session.worldSessionId());
        }
    }

    private void retainReadyAfterDeferredAgentRelease() {
        WorldSessionTracker.Snapshot session = sessions.snapshot();
        pendingAgentReturnReady = session.worldSessionId() != null
                && !arming.snapshot(session.worldSessionId()).locked();
    }

    private boolean releaseAgentControl(Minecraft minecraft) {
        closePendingKillZoneEffectForTerminal("control_release");
        // Stateful menu/view cleanup advances at most once per client tick. Do not mistake that
        // bounded asynchronous progress for a failed same-tick input-release command.
        AgentCleanupProgress stateful = advanceStatefulAgentCleanupOncePerClientTick(minecraft);
        boolean primitiveClosed = stateful.primitiveClosed();
        boolean recoveryClosed = stateful.recoveryClosed();
        boolean ownersReleased = boundedActionInputRelease(
                () -> releaseAllAndConfirmNoInputOwner(minecraft));
        boolean cleanupConfirmed = primitiveClosed && recoveryClosed && ownersReleased;
        pendingAgentInputRelease = !cleanupConfirmed;
        if (!cleanupConfirmed) {
            if (!primitiveClosed && recoveryClosed && ownersReleased
                    && statefulMenuReleaseProgressing()) {
                return false;
            }
            pendingAgentReturnReady = false;
            arming.lock("agent_input_release_failed");
            if (!agentInputReleaseFaultLogged) {
                agentInputReleaseFaultLogged = true;
                McmcpMod.LOGGER.error(
                        "MCMCP terminal input cleanup remained unconfirmed after {} attempts; "
                                + "the owner and local control lock were retained",
                        MAX_ACTION_INPUT_RELEASE_ATTEMPTS);
            }
            return false;
        }
        agentInputReleaseFaultLogged = false;
        try {
            restoreAgentSelectedSlot(minecraft);
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP selected-slot restoration failed", failure);
        } finally {
            agentExecution = null;
            pendingAgentAdmission = null;
        }
        return true;
    }

    private AgentCleanupProgress advanceStatefulAgentCleanupOncePerClientTick(
            Minecraft minecraft) {
        long clientTick = sessions.snapshot().clientTick();
        if (lastStatefulAgentCleanupClientTick == clientTick
                && lastStatefulAgentCleanupOwnershipEpoch == agentControlOwnershipEpoch) {
            return lastStatefulAgentCleanup;
        }
        lastStatefulAgentCleanupClientTick = clientTick;
        lastStatefulAgentCleanupOwnershipEpoch = agentControlOwnershipEpoch;
        boolean primitiveClosed = closeAgentPrimitiveExecutor();
        // A primitive close may advance bounded camera/slot restoration. Sample ownership
        // while AgentExecution and the first terminal intent are still retained.
        recordPendingAgentMotion(minecraft);
        boolean recoveryClosed = closeRecoveryGovernor();
        lastStatefulAgentCleanup = new AgentCleanupProgress(
                primitiveClosed, recoveryClosed);
        return lastStatefulAgentCleanup;
    }

    private boolean statefulMenuReleaseProgressing() {
        return agentExecution != null && (
                agentExecution.frameItemAttempt != null
                        && agentExecution.frameItemAttempt.releaseStatus()
                                == FrameItemAttempt.ReleaseStatus.PROGRESSING
                || agentExecution.menuPrimitives.releaseProgressing()
                || agentExecution.fishing.active());
    }

    private boolean advancePendingAgentReleaseClock() {
        if (!pendingAgentInputRelease) return false;
        WorldSessionTracker.Snapshot session = sessions.snapshot();
        if (!session.worldReady()) return false;
        sessions.tick();
        return true;
    }

    /** Releases a non-terminal hold/replan boundary, failing the active Action closed if needed. */
    private boolean releaseAgentInputsForHold(Minecraft minecraft, String evidence) {
        if (releaseOwnedInputsOrLock(minecraft)) {
            return true;
        }
        PendingAgentTerminal terminal = agentActions.active()
                .map(action -> PendingAgentTerminal.failure(
                        action.actionId(),
                        new AgentActionStore.Failure(
                                AgentActionStore.FailureCode.INTERNAL_ERROR,
                                true,
                                List.of(evidence))))
                .orElse(null);
        if (terminal != null) {
            rememberPendingAgentTerminal(terminal);
        }
        // The first global release failure is terminal for this hold boundary. Route the exact
        // primitive/recovery owners through the shared finite retry fence; releaseAgentControl
        // deliberately clears their references only after every close and owner-none proof pass.
        if (releaseAgentControl(minecraft) && terminal != null) {
            publishAgentTerminal(terminal);
        }
        return false;
    }

    private boolean releaseOwnedInputsOrLock(Minecraft minecraft) {
        boolean released = boundedActionInputRelease(() -> inputRelease.releaseAll(minecraft));
        pendingAgentInputRelease = !released;
        if (!released) {
            pendingAgentReturnReady = false;
            arming.lock("agent_input_release_failed");
            McmcpMod.LOGGER.error(
                    "MCMCP input release remained unconfirmed after {} attempts; "
                            + "local control was locked",
                    MAX_ACTION_INPUT_RELEASE_ATTEMPTS);
        }
        return released;
    }

    private boolean releaseAllAndConfirmNoInputOwner(Minecraft minecraft) {
        boolean released = inputRelease.releaseAll(minecraft);
        boolean inputOwnerNone = inputRelease.inputOwnerNone(minecraft);
        return released && inputOwnerNone;
    }

    private void rememberPendingAgentTerminal(PendingAgentTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        PendingAgentTerminal retained = firstTerminalIntent(pendingAgentTerminal, terminal);
        if (retained.equals(terminal)) {
            pendingAgentTerminal = retained;
            return;
        }
        McmcpMod.LOGGER.error(
                "MCMCP retained the first pending Agent terminal intent {} and rejected {}",
                pendingAgentTerminal.kind(), terminal.kind());
    }

    /** Publishes exactly the retained result; caller has already confirmed complete input release. */
    private boolean publishAgentTerminal(PendingAgentTerminal proposed) {
        Objects.requireNonNull(proposed, "proposed");
        // Lifecycle and physical-stop callbacks can run while an earlier release retry is pending.
        // The first terminal cause is immutable: a later stop must never replace it.
        PendingAgentTerminal terminal = firstTerminalIntent(pendingAgentTerminal, proposed);
        try {
            var summary = agentActions.latestSummary().orElseThrow(() ->
                    new IllegalStateException("Agent terminal intent has no retained action"));
            if (!summary.actionId().equals(terminal.actionId())) {
                throw new IllegalStateException("Agent terminal intent no longer matches latest action");
            }
            var latest = agentActions.get(terminal.actionId());
            if (latest.state().terminal()
                    && !terminalMatchesSnapshot(terminal, latest)) {
                throw new IllegalStateException("Agent terminal intent conflicts with retained result");
            }
            if (!latest.state().terminal()) {
                switch (terminal.kind()) {
                    case SUCCESS -> agentActions.succeed(terminal.actionId());
                    case FAILURE -> {
                        if (!agentActions.terminateActive(terminal.failure())) {
                            throw new IllegalStateException("Agent failure intent was not applied");
                        }
                    }
                    case CANCEL -> agentActions.cancel(terminal.actionId());
                }
            }
            if (!terminalMatchesSnapshot(terminal, agentActions.get(terminal.actionId()))) {
                throw new IllegalStateException("Agent terminal intent was not retained exactly");
            }
            if (terminal.equals(pendingAgentTerminal)) {
                pendingAgentTerminal = null;
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            rememberPendingAgentTerminal(terminal);
            pendingAgentReturnReady = false;
            arming.lock("agent_terminal_publication_failed");
            McmcpMod.LOGGER.error(
                    "MCMCP Agent terminal publication failed; local control remains locked",
                    failure);
            return false;
        }
    }

    static boolean terminalMatchesSnapshot(
            PendingAgentTerminal terminal, AgentActionStore.Snapshot snapshot) {
        return switch (terminal.kind()) {
            case SUCCESS -> snapshot.state() == AgentActionStore.State.SUCCEEDED;
            case CANCEL -> snapshot.state() == AgentActionStore.State.CANCELLED;
            case FAILURE -> snapshot.state() == AgentActionStore.State.FAILED
                    && terminal.failure().equals(snapshot.failure());
        };
    }

    static PendingAgentTerminal firstTerminalIntent(
            PendingAgentTerminal retained, PendingAgentTerminal proposed) {
        return retained == null
                ? Objects.requireNonNull(proposed, "proposed")
                : retained;
    }

    private boolean closeAgentControl(Minecraft minecraft, String lockReason) {
        pendingAgentReturnReady = false;
        entityAttackConsent.clear();
        boolean inputsReleased = releaseAgentControl(minecraft);
        arming.lock(lockReason);
        return inputsReleased;
    }

    static boolean boundedActionInputRelease(BooleanSupplier releaseAttempt) {
        Objects.requireNonNull(releaseAttempt, "releaseAttempt");
        for (int attempt = 0; attempt < MAX_ACTION_INPUT_RELEASE_ATTEMPTS; attempt++) {
            try {
                if (releaseAttempt.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Retry the idempotent full-release boundary, then fail closed below.
            }
        }
        return false;
    }

    private boolean retryPendingAgentInputRelease(Minecraft minecraft) {
        boolean released = !pendingAgentInputRelease || releaseAgentControl(minecraft);
        boolean published = released
                && (pendingAgentTerminal == null || publishAgentTerminal(pendingAgentTerminal));
        if (published && pendingAgentReturnReady) {
            pendingAgentReturnReady = false;
            returnControlReady();
        }
        return published;
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
            releaseAgentInputsForHold(minecraft, "routine_pause_input_release_failed");
            return;
        }
        var session = sessions.snapshot();
        var routineId = before.orElseThrow();
        if (!routineLifecycle.withinDeadline(routineId, System.nanoTime())) {
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
        if (routineLifecycle.finalizationRetries().contains(routineId)) {
            return;
        }
        var current = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        if (current.state() == RoutineState.FINALIZING) {
            routineLifecycle.finalizeRoutineBoundary(minecraft, current);
        }
        else if (current.state().terminal()) {
            routineLifecycle.finishTerminalRoutine(minecraft, routineId);
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

    private ClientCommandInbox.StopProgress stopActiveRoutineForEmergency(
            String reason,
            WorldSessionTracker.Snapshot session) {
        routineLifecycle.clearContinuation();
        AgentActionStore.FailureCode actionCode = "local_ui_disabled".equals(reason)
                ? AgentActionStore.FailureCode.USER_DISABLED
                : AgentActionStore.FailureCode.EMERGENCY_STOP;
        var activeAgent = agentActions.active();
        PendingAgentTerminal actionTerminal = activeAgent
                .map(action -> PendingAgentTerminal.failure(
                        action.actionId(),
                        new AgentActionStore.Failure(
                                actionCode, true, List.of(RuntimeFailures.sanitizeLocalCode(reason)))))
                .orElse(null);
        ClientCommandInbox.StopProgress actionProgress = ClientCommandInbox.StopProgress.COMPLETE;
        try {
            if (releaseAgentControl(Minecraft.getInstance())) {
                if (actionTerminal != null) {
                    actionProgress = publishAgentTerminal(actionTerminal)
                            ? ClientCommandInbox.StopProgress.COMPLETE
                            : ClientCommandInbox.StopProgress.FAILED;
                }
            } else {
                if (actionTerminal != null) {
                    rememberPendingAgentTerminal(actionTerminal);
                }
                actionProgress = statefulMenuReleaseProgressing()
                        ? ClientCommandInbox.StopProgress.PENDING
                        : ClientCommandInbox.StopProgress.FAILED;
            }
        } catch (RuntimeException | LinkageError failure) {
            actionProgress = ClientCommandInbox.StopProgress.FAILED;
            McmcpMod.LOGGER.error("MCMCP emergency action termination failed", failure);
        }
        var active = routines.activeRoutineId();
        if (active.isEmpty()) {
            boolean voiceEnded = routineLifecycle.endOwnedVoice();
            return voiceEnded ? actionProgress : ClientCommandInbox.StopProgress.FAILED;
        }
        try {
            var cancelled = routines.cancelRoutine(active.orElseThrow(), reason, Long.MAX_VALUE, 1);
            var cleanup = routineLifecycle.finalizeTerminalRoutine(Minecraft.getInstance(), cancelled);
            if (!cleanup.inputsReleased() || !cleanup.voice().success()) {
                return ClientCommandInbox.StopProgress.FAILED;
            }
            return actionProgress;
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP routine cancellation failed during emergency stop", failure);
            routineLifecycle.endVoiceFor(active.orElseThrow());
            return ClientCommandInbox.StopProgress.FAILED;
        }
    }

    private void requestSafetyStop(String reason) {
        entityAttackConsent.clear();
        inbox.requestEmergencyStop(reason);
    }

    public void onScreenOwnershipFailure(String reason) {
        Objects.requireNonNull(reason, "reason");
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        entityAttackConsent.clear();
        runPriorityEventStopIfRequired(
                automationActivityPending(),
                () -> inbox.requestEmergencyStop(reason),
                () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
    }

    private void requireLiveCall(RuntimeCallContext context, String command) {
        if (!context.canBeginWork()) {
            throw new ClientCommandInbox.CommandTimeoutException(command);
        }
        if (!context.evaluationLeaseCurrent(this)) {
            throw new ClientCommandInbox.CommandInvalidatedException(command);
        }
    }

    private <T> T withEvaluationLeaseFence(
            RuntimeCallContext context,
            String command,
            Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return evaluationControl.withFence(() -> {
            requireLiveCall(context, command);
            return work.get();
        });
    }

    private record AgentCleanupProgress(boolean primitiveClosed, boolean recoveryClosed) {
    }

    private void stopForLifecycle(Minecraft minecraft, String reason) {
        try {
            inbox.requestEmergencyStop(reason);
            inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        } finally {
            routineLifecycle.clearDeadline();
        }
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
                evaluationControl.terminateActiveEvaluationOnClient(
                        minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
                clearAgentSessionState();
                routines.clearSession("dimension_changed");
                routineLifecycle.clearSession();
                clearAutomationPortSessions(
                        stationaryBreakPort::clearSession,
                        semanticActionPort::clearSession,
                        applyBlockPlanPort::clearSession,
                        pillarUpPort::clearSession);
                clearPhaseFivePortSessions();
                recipeCatalog.detachSession();
                memory.detachSession();
                arming.lock("dimension_changed");
            }
            memory.startSession(after.worldSessionId(), dimension);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    minecraft.level, after.worldSessionId());
            agentObservations.startSession(
                    after.worldSessionId(), dimension, reconciliation.worldRevision());
            screenOwnership.bindWorldSession(minecraft.level, after.worldSessionId());
            MerchantOfferSignals.global().bindSession(minecraft.level, after.worldSessionId());
        }
    }

    private void publishSession() {
        publishedSession = sessions.snapshot();
    }

    static void assertClientThread(Minecraft minecraft) {
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

    private static final class BoundedInputExecution {
        private final BoundedInputLease lease;
        private final Vec3 startPosition;
        private final float effectiveHealthBaseline;
        private Vec3 lastPosition;
        private long activeTicks;
        private int stalledTicks;

        private BoundedInputExecution(
                BoundedInputLease lease, Vec3 startPosition, float effectiveHealthBaseline) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.startPosition = Objects.requireNonNull(startPosition, "startPosition");
            this.lastPosition = startPosition;
            if (!Float.isFinite(effectiveHealthBaseline) || effectiveHealthBaseline <= 0.0F) {
                throw new IllegalArgumentException("bounded input health baseline must be positive");
            }
            this.effectiveHealthBaseline = effectiveHealthBaseline;
        }

        private void observeMovement(Vec3 current, boolean expectsHorizontalMovement) {
            Objects.requireNonNull(current, "current");
            double horizontal = Math.hypot(current.x - lastPosition.x, current.z - lastPosition.z);
            stalledTicks = expectsHorizontalMovement && horizontal < 1.0E-4D
                    ? Math.min(10, stalledTicks + 1) : 0;
            lastPosition = current;
        }
    }

    private static final class AgentExecution {
        private final MenuPrimitiveExecution menuPrimitives;
        private SurfacePreflightRecovery surfaceRecovery;
        private AgentAdmissionSnapshot surfaceAdmission;
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
        private long latestWorldRevision;
        private int positionCorrections;
        private AgentActionStore.Progress occurrenceBaseline;
        private ActionDslCompiler.Cost occurrenceLimit;
        private final Object playerIdentity;
        private final int originalSelectedSlot;
        private final float maxCameraDegreesPerTick;
        private int agentSelectedSlot = -1;
        private boolean breakAimComplete;
        private boolean fishingAimComplete;
        private final FishingPrimitiveExecution fishing;
        private int mutationAimFailures;
        private KnownBlockBreakAttempt blockBreakAttempt;
        private StationaryBreakOperation cobblestoneGeneratorAttempt;
        private BoundedInputExecution boundedInputHold;
        private long cobblestoneGeneratorCheckpoint;
        private boolean cobblestoneGeneratorUnknownRecorded;
        private KnownBlockMutationAttempt blockMutationAttempt;
        private FrameItemAttempt frameItemAttempt;
        private AgentPrimitivePlanner.FrameItemAim frameItemAim;
        private AgentPrimitivePlanner.MutationBatchPlan mutationBatchPlan;
        private int mutationBatchIndex;
        private ActionDsl.Node mutationBatchTarget;
        private AgentPrimitivePlanner.MutationAim mutationBatchTargetAim;
        private boolean mutationBatchTargetBound;
        private long mutationBatchTargetDeadlineTick;
        private int collectBatchIndex;
        private CollectBatchEvidence collectBatchEvidence;
        private CropWaitAuthorization cropWaitAuthorization;
        private double tillSettlingAllowance;
        private ActionDsl.Position tillSettlingTarget;
        private long tillSettlingDeadlineTick;
        private KillZoneExecution killZone;
        private int pickupInventoryBefore = -1;
        private long pickupArrivalTick = -1L;
        private NavCell pickupCell;

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
                long positionCorrectionRevision, MenuPrimitiveExecution menuPrimitives,
                FishingPrimitiveExecution fishing) {
            this.menuPrimitives = menuPrimitives;
            this.fishing = fishing;
            actionId = action.actionId();
            this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
            program = action.program();
            cursor = new ActionProgramCursor(action.program().request().program());
            primitiveExecutor = new MinecraftActionPrimitiveExecutor(maxCameraDegreesPerTick);
            this.maxCameraDegreesPerTick = maxCameraDegreesPerTick;
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

}
