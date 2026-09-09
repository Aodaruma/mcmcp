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
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.runtime.ActionEvidence.CropWaitAuthorization;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
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
            if (!startAgentExecution(minecraft, session, action)) return;
            if (!agentControlCurrent(minecraft, session, action)) return;
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
            if (!recoveryAllowsAgentTick(minecraft, session, action, recovery)) return;

            // The ordinary recovery governor remains authoritative for every hard hazard. Only
            // the later generic local visible-hostile REPLAN is replaced by zone-scoped proofs.
            if (agentExecution.killZone != null) {
                tickKillZoneBudget(minecraft, session, action, now);
                return;
            }

            tickAgentProgram(minecraft, session, action, now, recovery);
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

    /** 配送確認後にも元のadmission fenceを通して実行ownerを確定する。 */
    private boolean startAgentExecution(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution == null || !agentExecution.actionId.equals(action.actionId())) {
            if (minecraft.player == null || !session.worldReady()) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true, "world_unavailable");
                return false;
            }
            var pending = pendingAgentAdmission;
            if (pending == null
                    || !pending.actionId().equals(action.actionId())) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "admission_missing_before_execution");
                return false;
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
                    return false;
                }
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        admissionFailure.orElseThrow().executionEvidence());
                return false;
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
                    new FishingPrimitiveExecution(action.actionId(), agentActions, fishingSessionRefs, arming),
                    new MovementExecution(McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                            agentPathfinder, agentObservations, reconciliationSignals),
                    new WaitExecution(agentObservations, reconciliationSignals),
                    new BlockMutationExecution(action.actionId(), agentActions,
                            semanticActionPort, agentObservations, actionAdmission, reconciliationSignals),
                    new CobblestoneExecution(action.actionId(), agentActions,
                            stationaryBreakPort),
                    new KnownBreakExecution(action.actionId(), agentActions,
                            stationaryBreakPort, reconciliationSignals, agentObservations),
                    new BoundedInputExecution(minecraft.player,
                            session.worldSessionId(), screenOwnership, agentActions),
                    new FrameItemExecution(action.actionId(), agentActions, frameItemPort,
                            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F, pending.prepared().frameItemAim().orElse(null)));
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
                    return false;
                }
            }
            agentActions.markRunning(action.actionId());
            agentActions.recordAdmissionTicks(action.actionId(),
                    pending.prepared().surfaceRecovery().consumedTicks(session.clientTick()));
            agentExecution = nextExecution;
            agentExecution.surfaceRecovery = pending.prepared().surfaceRecovery();
            agentExecution.surfaceAdmission = pending.prepared().snapshot();
            agentInputReleaseFaultLogged = false;
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
                    return false;
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
                    return false;
                }
            }
            agentControlOwnershipEpoch = Math.incrementExact(agentControlOwnershipEpoch);
            pendingAgentAdmission = null;
            if (paused) {
                // A pause cannot refund renderer waiting charged before execution began.
                pauseStartedAtNanos = System.nanoTime();
            }
        }
        return true;
    }

    /** pause・world・lease・server位置補正を、通常tick加算より先に処理する。 */
    private boolean agentControlCurrent(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.killZone != null
                && minecraft.player != null
                && agentExecution.killZone.healthDecreased(minecraft.player)) {
            safetyInterruptKillZone(
                    minecraft, session, action, agentExecution.killZone, "health_decreased");
            return false;
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
            return false;
        }
        if (!session.worldReady()
                || !Objects.equals(agentExecution.worldSessionId, session.worldSessionId())) {
            failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true, "world_session_changed");
            return false;
        }
        var control = arming.snapshot(session.worldSessionId());
        if (control.mode() != LocalArmingState.Mode.AGENT
                && control.mode() != LocalArmingState.Mode.RECOVERING) {
            failAgentAction(AgentActionStore.FailureCode.USER_DISABLED, true, "local_control_locked");
            return false;
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
                return false;
            }
            var correctionProgress = agentActions.get(action.actionId()).progress();
            agentActions.recordTick(action.actionId());
            if (agentExecution.primitive != null && agentExecution.occurrenceLimit != null) {
                requestAgentReplan(
                        correctionProgress.ticks() + 1L, "server_position_correction");
            }
            return false;
        }

        return true;
    }

    private boolean recoveryAllowsAgentTick(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, MinecraftRecoveryGovernor.TickResult recovery) {
        if (agentExecution.killZone != null
                && recovery.state() != MinecraftRecoveryGovernor.State.IDLE
                && recovery.state() != MinecraftRecoveryGovernor.State.REPLAN_REQUIRED) {
            safetyInterruptKillZone(
                    minecraft,
                    session,
                    action,
                    agentExecution.killZone,
                    recovery.reason().name().toLowerCase(Locale.ROOT));
            return false;
        }
        switch (recovery.state()) {
            case RECOVERING, PAUSED -> {
                agentActions.recordTick(action.actionId());
                return false;
            }
            case RECOVERED -> {
                failAgentAction(
                        AgentActionStore.FailureCode.SAFETY_RECOVERED,
                        true,
                        recovery.reason().name().toLowerCase(Locale.ROOT));
                return false;
            }
            case EXHAUSTED -> {
                failAgentAction(
                        AgentActionStore.FailureCode.RECOVERY_EXHAUSTED,
                        false,
                        recovery.reason().name().toLowerCase(Locale.ROOT));
                return false;
            }
            case STOPPED -> {
                failAgentAction(
                        AgentActionStore.FailureCode.EMERGENCY_STOP,
                        true,
                        recovery.reason().name().toLowerCase(Locale.ROOT));
                return false;
            }
            case IDLE, REPLAN_REQUIRED -> { }
        }

        return true;
    }

    private void tickKillZoneBudget(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, long now) {
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

    /** dispatch後のACK待機も同じownerへ返す。認識しないnodeだけ移動段階へ進む。 */
    private boolean dispatchSemanticPrimitive(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, long actionTick) {
        if (agentExecution.primitive instanceof ActionDsl.TillKnownBlock
                || agentExecution.primitive instanceof ActionDsl.TillKnownBatch
                || agentExecution.primitive instanceof ActionDsl.PlantKnownWheat
                || agentExecution.primitive instanceof ActionDsl.PlantKnownWheatBatch
                || agentExecution.primitive instanceof ActionDsl.HarvestKnownWheat
                || agentExecution.primitive instanceof ActionDsl.HarvestKnownWheatBatch
                || agentExecution.primitive instanceof ActionDsl.OpenKnownFenceGate
                || agentExecution.primitive instanceof ActionDsl.OpenKnownPassage) {
            tickAgentBlockMutation(minecraft, session, action, actionTick);
            return true;
        }

        if (ActionEvidence.isFrameItemPrimitive(agentExecution.primitive)) {
            tickAgentFrameItem(minecraft, session, action);
            return true;
        }

        if (agentExecution.primitive instanceof ActionDsl.InspectKnownContainer
                || agentExecution.primitive instanceof ActionDsl.TakeKnownContainerStack
                || agentExecution.primitive instanceof ActionDsl.StoreKnownContainerStack
                || agentExecution.primitive instanceof ActionDsl.CraftKnownRecipe
                || agentExecution.primitive instanceof ActionDsl.SmeltKnownRecipe
                || agentExecution.primitive instanceof ActionDsl.OperateKnownMenu) {
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.menuPrimitives.tickAgentContainer(minecraft, session,
                            agentExecution.primitive, agentExecution.mutationAims,
                            agentExecution.latestWorldRevision));
            return true;
        }

        if (agentExecution.primitive instanceof ActionDsl.BrewKnownPotionBatch) {
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.menuPrimitives.tickAgentBrewing(session, agentExecution.primitive,
                            agentExecution.mutationAims, agentExecution.maxCameraDegreesPerTick));
            return true;
        }

        if (agentExecution.primitive instanceof ActionDsl.ApplyKnownBlockPlan
                || agentExecution.primitive instanceof ActionDsl.ClearKnownBlockPlan) {
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.menuPrimitives.tickAgentConstruction(session, agentExecution.primitive,
                            agentExecution.latestWorldRevision));
            return true;
        }

        if (agentExecution.primitive instanceof ActionDsl.PillarUpKnown) {
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.menuPrimitives.tickAgentPillarUp(session, agentExecution.primitive));
            return true;
        }

        if (agentExecution.primitive instanceof ActionDsl.ApplyKnownRedstoneSpec) {
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.menuPrimitives.tickAgentRedstone(minecraft, session,
                            agentExecution.primitive, agentExecution.mutationAims));
            return true;
        }

        return false;
    }

    /** 通常Actionの予算・一tick加算・JIT・node順序を所有する。 */
    private void tickAgentProgram(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, long now, MinecraftRecoveryGovernor.TickResult recovery) {
        var player = minecraft.player;
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
            applyPrimitiveOutcome(minecraft, action,
                    agentExecution.waiting.tick(minecraft, session, agentExecution.primitive), false);
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

        if (dispatchSemanticPrimitive(minecraft, session, action, actionTick)) return;
        tickAgentMovement(minecraft, session, action, usedBeforeTick, actionTick, durationLimit);
    }

    private void tickAgentMovement(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, AgentActionStore.Progress usedBeforeTick,
            long actionTick, long durationLimit) {
        var player = minecraft.player;
        KnownTraversabilitySnapshot map = agentObservations.requireAgentMap(session);
        if (agentExecution.movement.active()
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
                && agentExecution.breaking.aimComplete()) {
            tickAgentBreak(
                    minecraft, session, action, map, agentExecution.primitive);
            return;
        }
        if (!agentExecution.movement.active()
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
            result = agentExecution.movement.tick(
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
                    agentExecution.breaking.aimed();
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
                agentExecution.latestWorldRevision, reason);
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
        agentExecution.mutation.resetOccurrence();
        agentExecution.collectBatchIndex = 0;
        agentExecution.collectBatchEvidence = null;
        agentExecution.waiting.begin(advance.primitive());
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
            agentExecution.occurrenceLimit = agentExecution.program.primitiveCostBounds()
                    .get(advance.primitive().id());
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
                if (!agentExecution.frameItem.reauthorize(currentAim, session.clientTick())) {
                    failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED,
                            false, "frame_item_authorization_changed");
                    return false;
                }
            }
            Optional.ofNullable(analysis.mutationBatchPlans().get(agentExecution.primitive.id()))
                    .ifPresent(agentExecution.mutation::bindPlan);
            agentExecution.waiting.authorize(cropWaitAuthorization);
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

    private boolean beginAgentPrimitive(Minecraft minecraft, AgentActionStore.Active action,
            KnownTraversabilitySnapshot map, AgentActionStore.Progress progressBeforeTick, long currentTick) {
        try {
            PrimitiveOutcome outcome;
            try {
                outcome = agentExecution.movement.begin(minecraft, action, map, progressBeforeTick, currentTick,
                        agentExecution.primitive, new MovementExecution.BudgetEvidence(
                                agentExecution.occurrenceBaseline, agentExecution.occurrenceLimit,
                                agentExecution.startedAtNanos, agentExecution.pausedNanos),
                        agentExecution.replanning, agentExecution.mutationAims,
                        activeCollectTarget(), agentExecution.pickupInventoryBefore);
            } finally {
                if (agentExecution.movement.selectedSlot() >= 0) {
                    agentExecution.agentSelectedSlot = agentExecution.movement.selectedSlot();
                }
                if (isCollectPrimitive(agentExecution.primitive)
                        && agentExecution.movement.pickupCell() != null) {
                    agentExecution.pickupCell = agentExecution.movement.pickupCell();
                }
            }
            if (outcome.failure() != null) {
                applyPrimitiveOutcome(minecraft, action, outcome);
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

    private void tickAgentBreak(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, KnownTraversabilitySnapshot map, ActionDsl.Node block) {
        applyPrimitiveOutcome(minecraft, action,
                agentExecution.breaking.tick(minecraft, session, action, map, block));
    }

    private void tickAgentBoundedInputHold(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, ActionDsl.HoldBoundedInputs hold, boolean recordTick) {
        applyPrimitiveOutcome(minecraft, action,
                agentExecution.boundedInput.tick(minecraft, session, action, hold, recordTick,
                        agentExecution.startedAtNanos, agentExecution.pausedNanos,
                        agentExecution.latestWorldRevision, agentObservations.localSafety()), false);
    }

    private void tickAgentCobblestoneGenerator(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, ActionDsl.OperateKnownCobblestoneGenerator operation) {
        PrimitiveOutcome outcome;
        try {
            outcome = agentExecution.cobblestone.tick(minecraft, session, action, operation);
        } finally {
            if (agentExecution.cobblestone.selectedSlot() >= 0) {
                agentExecution.agentSelectedSlot = agentExecution.cobblestone.selectedSlot();
            }
        }
        applyPrimitiveOutcome(minecraft, action, outcome, false);
    }

    private void tickAgentBlockMutation(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action, long actionTick) {
        if (ActionBudgets.isMutationBatch(agentExecution.primitive)
                && !agentExecution.mutation.targetBound()) {
            PrimitiveOutcome binding;
            try {
                binding = agentExecution.mutation.bindTarget(minecraft, session, action,
                        agentExecution.occurrenceBaseline, agentExecution.occurrenceLimit,
                        agentExecution.startedAtNanos, agentExecution.pausedNanos);
            } catch (AgentPrimitivePlanner.PlanningException unavailable) {
                if (!releaseAgentInputsForHold(minecraft, "batch_reproof_input_release_failed")) return;
                applyPrimitiveOutcome(minecraft, action,
                        agentExecution.mutation.waitForReproof(actionTick, unavailable));
                return;
            }
            if (!binding.complete()) {
                applyPrimitiveOutcome(minecraft, action, binding);
                return;
            }
            agentExecution.replanning = false;
            agentActions.setPhase(action.actionId(), AgentActionStore.Phase.EXECUTING, "batch_target_reproved");
        }
        var outcome = agentExecution.mutation.tick(minecraft, session, action,
                agentExecution.primitive, agentExecution.mutationAims);
        if (outcome.replanEvidence() != null) {
            retryAgentMutationAim(minecraft, action, outcome.replanEvidence());
        } else {
            applyPrimitiveOutcome(minecraft, action, outcome);
        }
    }

    private void tickAgentFrameItem(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        applyPrimitiveOutcome(minecraft, action,
                agentExecution.frameItem.tick(minecraft, session, agentExecution.primitive), false);
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

    private void applyPrimitiveOutcome(Minecraft minecraft, AgentActionStore.Active action,
            PrimitiveOutcome outcome) {
        applyPrimitiveOutcome(minecraft, action, outcome, true);
    }

    private void applyPrimitiveOutcome(
            Minecraft minecraft, AgentActionStore.Active action, PrimitiveOutcome outcome, boolean resetReplan) {
        if (outcome.replanEvidence() != null) {
            requestAgentReplan(agentActions.get(action.actionId()).progress().ticks(), outcome.replanEvidence());
            return;
        }
        if (outcome.failure() != null) {
            failAgentAction(outcome.failure().code(), outcome.failure().recoverable(),
                    outcome.failure().evidence().getFirst(),
                    outcome.failure().evidence().subList(1, outcome.failure().evidence().size())
                            .toArray(String[]::new));
        } else if (outcome.complete()) {
            agentActions.completeNode(action.actionId());
            agentExecution.primitive = null;
            if (resetReplan) {
                agentExecution.replanning = false;
                agentExecution.replanNotBeforeTick = 0L;
                agentExecution.replanDeadlineTick = 0L;
            }
            advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
        }
    }

    private void retryAgentMutationAim(
            Minecraft minecraft, AgentActionStore.Active action, String evidence) {
        if (!agentExecution.mutation.retryAimAllowed()) {
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
        double settlingCredit = agentExecution.mutation.consumeSettlingCredit(
                agentExecution.lastPosition, position, agentExecution.movement.active());
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
        if (!agentExecution.boundedInput.close()) closed = false;
        try {
            agentExecution.movement.close();
        } catch (RuntimeException | LinkageError failure) {
            closed = false;
            McmcpMod.LOGGER.error("MCMCP Action DSL input release failed", failure);
        }
        if (!agentExecution.breaking.close()) closed = false;
        if (!agentExecution.cobblestone.close()) closed = false;
        if (!agentExecution.mutation.close()) closed = false;
        if (!agentExecution.frameItem.close()) closed = false;
        if (!agentExecution.menuPrimitives.close(
                agentExecution.primitive, agentExecution.latestWorldRevision)) closed = false;
        if (!agentExecution.fishing.close(Minecraft.getInstance(),
                sessions.snapshot().clientTick(), agentExecution.latestWorldRevision)) closed = false;
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
                agentExecution.frameItem.releaseProgressing()
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

    private static final class AgentExecution {
        private final MenuPrimitiveExecution menuPrimitives;
        private SurfacePreflightRecovery surfaceRecovery;
        private AgentAdmissionSnapshot surfaceAdmission;
        private final UUID actionId;
        private final UUID worldSessionId;
        private final ActionDslCompiler.CompiledProgram program;
        private final ActionProgramCursor cursor;
        private final MovementExecution movement;
        private final Map<String, AgentPrimitivePlanner.MutationAim> mutationAims;
        private final long startedAtNanos;
        private long pausedNanos;
        private net.minecraft.world.phys.Vec3 lastPosition;
        private float lastYaw;
        private float lastPitch;
        private ActionDsl.Node primitive;
        private final WaitExecution waiting;
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
        private boolean fishingAimComplete;
        private final FishingPrimitiveExecution fishing;
        private final KnownBreakExecution breaking;
        private final CobblestoneExecution cobblestone;
        private final BoundedInputExecution boundedInput;
        private final FrameItemExecution frameItem;
        private final BlockMutationExecution mutation;
        private int collectBatchIndex;
        private CollectBatchEvidence collectBatchEvidence;
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
                FishingPrimitiveExecution fishing, MovementExecution movement, WaitExecution waiting,
                BlockMutationExecution mutation, CobblestoneExecution cobblestone,
                KnownBreakExecution breaking, BoundedInputExecution boundedInput, FrameItemExecution frameItem) {
            this.menuPrimitives = menuPrimitives;
            this.fishing = fishing;
            actionId = action.actionId();
            this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
            program = action.program();
            cursor = new ActionProgramCursor(action.program().request().program());
            this.movement = movement;
            this.waiting = waiting;
            this.mutation = mutation;
            this.cobblestone = cobblestone;
            this.breaking = breaking;
            this.boundedInput = boundedInput;
            this.frameItem = frameItem;
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
