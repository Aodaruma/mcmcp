package dev.aod.mcmcp.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.ActionProgramCursor;
import dev.aod.mcmcp.agent.action.CollectBatchEvidence;
import dev.aod.mcmcp.agent.action.KnownBlockBreakAttempt;
import dev.aod.mcmcp.agent.action.KnownBlockMutationAttempt;
import dev.aod.mcmcp.agent.action.KnownBrewingAttempt;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.agent.action.KnownPillarUpAttempt;
import dev.aod.mcmcp.agent.action.KnownRedstoneIdentityAttempt;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.agent.dsl.ActionDslOperationManifest;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import dev.aod.mcmcp.agent.dsl.PredicateEvaluator;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import dev.aod.mcmcp.agent.observation.ObservationFrameStore;
import dev.aod.mcmcp.agent.observation.ObservationFilter;
import dev.aod.mcmcp.agent.observation.ObservationKind;
import dev.aod.mcmcp.agent.observation.ObservationPage;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.OmnidirectionalObserver;
import dev.aod.mcmcp.agent.observation.ObservationStoreException;
import dev.aod.mcmcp.agent.observation.ObservationWireMapper;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.agent.observation.SoundClueStore;
import dev.aod.mcmcp.agent.observation.SoundPlaybackQueue;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor;
import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AutomationIndicatorController;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.client.MultiplayerAllowlist;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.mcp.EvaluationTurnControl;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import dev.aod.mcmcp.mcp.McpToolSchemas;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.observation.BlockPlanComparator;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockPlanValidationException;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.safety.EvaluationTurnGuard;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentTransportBridge;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentUiBridge;
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
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.KnownConstructionRequest;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.MinecraftPillarUpPort;
import dev.aod.mcmcp.routine.MinecraftKnownBrewingPort;
import dev.aod.mcmcp.routine.MinecraftKnownFurnacePort;
import dev.aod.mcmcp.routine.MinecraftKnownMenuPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveWorldPort;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFivePortRouter;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PlacementSupportWitness;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.StationaryBreakOperation;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.voice.SimpleVoiceChat2622Adapter;
import dev.aod.mcmcp.voice.VoiceChatAdapter;
import dev.aod.mcmcp.voice.VoiceChatEventBridge;
import dev.aod.mcmcp.voice.VoiceChatSafetyController;
import dev.aod.mcmcp.voice.VoiceTransmissionGuard;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Client runtime and the sole implementation of the MCP-to-Minecraft boundary. */
public final class McmcpRuntime implements McpRuntimePort, EvaluationTurnControl {
    static final int MAX_MUTATION_AIM_FAILURES = 3;
    static final int MAX_ACTION_INPUT_RELEASE_ATTEMPTS = 3;
    static final String REPLANNED_ROUTE_SHAPE_EVIDENCE =
            "replanned_route_shape_exceeds_occurrence";
    static final String REPLANNED_ROUTE_GLOBAL_EVIDENCE =
            "replanned_route_global_budget";
    static final String REPLANNED_ROUTE_REMAINING_EVIDENCE =
            "replanned_route_remaining_occurrence";
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String MCP_PROTOCOL_VERSION = "2026-07-28";
    private static final Duration FINALIZATION_RESERVE = Duration.ofSeconds(5);
    private static final Duration EVALUATION_CONTROL_DISPATCH_TIMEOUT = Duration.ofSeconds(1);
    private static final long ACTION_DELIVERY_CONFIRM_NANOS = Duration.ofSeconds(5).toNanos();
    private static final double MAX_SAFE_STAY_HORIZONTAL_SPEED_SQUARED = 0.01;
    static final double CROP_WAIT_OBSERVER_EPSILON_BLOCKS =
            AgentPrimitivePlanner.WAIT_WITNESS_EYE_EPSILON_BLOCKS;
    private static final float MIN_SAFE_STAY_HEALTH = 6.0F;
    /** Expanded only when a phase has passed its gate. */
    private static final Set<String> AVAILABLE_CAPABILITIES =
            Set.of("movement", "camera", "block_break", "block_interact", "block_place",
                    "inventory_transfer", "item_use", "entity_attack");

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final ObservationFrameStore agentObservationFrames = new ObservationFrameStore();
    private final DeliveredPolicyEvidenceStore deliveredAgentEvidence =
            new DeliveredPolicyEvidenceStore();
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
    private final KnownMenuOperationRefs knownMenuOperationRefs = new KnownMenuOperationRefs();
    private final FishingSessionRefs fishingSessionRefs = new FishingSessionRefs();
    private final ScopedEntityAttackConsentStore entityAttackConsent =
            new ScopedEntityAttackConsentStore();
    private final LocalArmingState arming = new LocalArmingState();
    private final InputReleaseController inputRelease = new InputReleaseController();
    private final EvaluationTurnGuard evaluationTurns = new EvaluationTurnGuard();
    private final Object evaluationTerminalGate = new Object();
    /** Guarded by {@link #evaluationTerminalGate}; never reused during this runtime lifetime. */
    private long evaluationFenceRevision;
    private final MinecraftStationaryBreakPort stationaryBreakPort;
    private final ClientReconciliationSignals reconciliationSignals;
    private final MinecraftSemanticActionPort semanticActionPort;
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
    private final VoiceChatSafetyController voiceChat;
    private final ClientCommandInbox inbox;
    private final FinalizationRetryQueue finalizationRetries = new FinalizationRetryQueue();
    private final GoalContinuationSession goalContinuation = new GoalContinuationSession();
    private RoutineWallClockDeadline activeRoutineDeadline;
    private AgentExecution agentExecution;
    private boolean pendingAgentInputRelease;
    private boolean pendingAgentReturnReady;
    private long agentControlOwnershipEpoch;
    private long lastStatefulAgentCleanupClientTick = Long.MIN_VALUE;
    private long lastStatefulAgentCleanupOwnershipEpoch = Long.MIN_VALUE;
    private AgentCleanupProgress lastStatefulAgentCleanup =
            new AgentCleanupProgress(true, true);
    private PendingEvaluationTerminal pendingEvaluationTerminal;
    private PendingAgentTerminal pendingAgentTerminal;
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
    private AutomationIndicatorController entityAttackConsentUi;

    private UUID voiceRoutineId;

    private volatile WorldSessionTracker.Snapshot publishedSession = sessions.snapshot();
    private volatile boolean paused;
    private volatile Thread clientThread;
    private volatile String endpointFaultCode;
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
                ClientPredictionSignals.global());
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
        terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
        clearAgentSessionState();
        stopForLifecycle(minecraft, "world_join");
        routines.clearSession("world_join");
        finalizationRetries.clear();
        goalContinuation.clear();
        voiceRoutineId = null;
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
        terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
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
        terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
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
        terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
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
        if (paused) {
            pauseStartedAtNanos = nowNanos;
            releaseAgentInputsForHold(minecraft, "pause_input_release_failed");
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

    /** Accepts only copied position-sound values and performs a bounded enqueue. */
    public void onPositionSoundEvent(
            String soundEvent,
            SoundSource source,
            double x,
            double y,
            double z) {
        soundPlaybacks.capturePositionSound(soundEvent, source, x, y, z);
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
        finishPendingEvaluationTerminalOnClient(minecraft);
        terminateInvalidEvaluationLeaseOnClient(minecraft);
        var evaluationSession = sessions.snapshot();
        if (evaluationTurns.snapshot(evaluationSession.worldSessionId()).active()
                && evaluationSession.worldReady()
                && !localControlAvailable(minecraft, evaluationSession)) {
            terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.PLAYER_UNAVAILABLE);
        }
        boolean pendingReleaseCompleted = retryPendingAgentInputRelease(minecraft);
        // A retained stop waiter owns the priority lane even when the shared release retry
        // remains pending or fails closed. Drain it before any ordinary world/action work.
        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        if (!pendingReleaseCompleted || pendingAgentInputRelease) {
            publishSession();
            return;
        }
        try {
            synchronizeWorld(minecraft);
            trackRecoveryDescent(minecraft);
            if (!pendingReleaseClockAdvanced) {
                sessions.tick();
            }
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
        entityAttackConsent.clear();
        if (anyActive()) {
            terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.LOCAL_ESCAPE);
        } else {
            runPriorityStop(
                    inbox::requestLocalEmergencyStop,
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        }
        goalContinuation.clear();
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
                evaluationTurns.snapshot(session.worldSessionId()).active(),
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
                || minecraft.gui.screen() != null
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
        endpointFaultCode = sanitizeLocalCode(code);
        entityAttackConsent.clear();
        inbox.requestEmergencyStop("endpoint_fault");
        evaluationTurns.snapshot(publishedSession.worldSessionId()).activeLease()
                .ifPresent(lease -> requestEvaluationReleaseFromAnyThread(
                        lease.leaseId(), EvaluationTurnControl.ReleaseReason.ENDPOINT_FAULT));
    }

    public void clearEndpointFault() {
        endpointFaultCode = null;
    }

    /** Uses the same priority lane as Esc so a UI stop releases every owned input inline. */
    public void disableAutomationFromUi(Minecraft minecraft) {
        assertClientThread(minecraft);
        entityAttackConsent.clear();
        if (anyActive()) {
            terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.LOCAL_UI_DISABLED);
        } else {
            runPriorityStop(
                    inbox::requestLocalDisable,
                    () -> inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot()));
        }
        arming.lock("local_ui_disabled");
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
                || pendingAgentInputRelease
                || pendingAgentTerminal != null
                || agentExecution != null
                || pendingAgentAdmission != null
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
        entityAttackConsent.clear();
        recoveryDescent.reset();
        agentObservationFrames.clear();
        deliveredAgentEvidence.clear();
        soundClues.clear();
        soundPlaybacks.clear();
        soundPlaybackTruncated = false;
        fishingSessionRefs.clear();
        latestLocalObservation = null;
        knownTraversability.clearWorld();
        knownTraversabilityRevision = 0L;
        localSafety = LocalObservationProjector.CurrentSafety.REPLAN;
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
        terminateActiveEvaluationOnClient(
                minecraft, EvaluationTurnControl.ReleaseReason.CLIENT_SHUTDOWN);
        shutdown = true;
        entityAttackConsent.clear();
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
                applyBlockPlanPort::clearSession,
                pillarUpPort::clearSession);
        clearPhaseFivePortSessions();
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
        Objects.requireNonNull(request, "request");
        if (shutdown) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("runtime is stopping"));
        }
        var runner = ProcessHandle.of(request.runnerProcessId())
                .filter(ProcessHandle::isAlive)
                .orElse(null);
        if (runner == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("evaluation runner is not alive"));
        }
        var started = runner.info().startInstant();
        if (started.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("evaluation runner start identity is unavailable"));
        }
        var identity = new EvaluationTurnGuard.RunnerIdentity(
                runner.pid(), started);
        var fence = publishedSession;
        long deadline = RuntimeCallContext.deadlineAfter(
                System.nanoTime(), EVALUATION_CONTROL_DISPATCH_TIMEOUT.toNanos());
        var delivered = new CompletableFuture<EvaluationTurnControl.LeaseReceipt>();
        java.util.function.Consumer<EvaluationTurnControl.LeaseReceipt> releaseAbandoned =
                receipt -> {
                    if (receipt != null
                            && receipt.state() == EvaluationTurnControl.LeaseState.ACTIVE) {
                        requestEvaluationReleaseFromAnyThread(
                                receipt.leaseId(),
                                EvaluationTurnControl.ReleaseReason.ACQUIRE_ABANDONED);
                    }
                };
        var submitted = inbox.submitControl(
                "evaluation_turn_acquire",
                fence.generation(),
                deadline,
                () -> acquireEvaluationTurnOnClient(request, runner, identity),
                delivered::isDone,
                releaseAbandoned);
        submitted.whenComplete((receipt, failure) -> {
            if (failure != null) {
                delivered.completeExceptionally(failure);
                return;
            }
            if (!delivered.complete(receipt)) {
                releaseAbandoned.accept(receipt);
            }
        });
        return delivered;
    }

    private EvaluationTurnControl.LeaseReceipt acquireEvaluationTurnOnClient(
            EvaluationTurnControl.AcquireRequest request,
            ProcessHandle runner,
            EvaluationTurnGuard.RunnerIdentity identity) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        return withEvaluationTurnGate(
                evaluationTerminalGate,
                () -> acquireEvaluationTurnWithGateHeld(
                        minecraft, request, runner, identity));
    }

    private EvaluationTurnControl.LeaseReceipt acquireEvaluationTurnWithGateHeld(
            Minecraft minecraft,
            EvaluationTurnControl.AcquireRequest request,
            ProcessHandle runner,
            EvaluationTurnGuard.RunnerIdentity identity) {
        // This entire admission is serialized with ABSENT/ACTIVE call commits and terminal
        // claims. Recheck every mutable condition after waiting for that gate.
        var session = sessions.snapshot();
        var control = arming.snapshot(session.worldSessionId());
        if (shutdown
                || !runner.isAlive()
                || !identity.matches(runner)
                || !localControlAvailable(minecraft, session)
                || paused
                || minecraft.isPaused()
                || endpointFaultCode != null
                || control.mode() != LocalArmingState.Mode.READY
                || automationActivityPending()
                || evaluationTurns.snapshot(session.worldSessionId()).active()) {
            throw new IllegalStateException("evaluation turn admission is not ready");
        }
        if (!boundedActionInputRelease(() -> releaseAllAndConfirmNoInputOwner(minecraft))) {
            arming.lock("input_release_failed");
            throw new IllegalStateException("evaluation input preflight release failed");
        }
        var lease = evaluationTurns.tryAcquire(
                        Objects.requireNonNull(session.worldSessionId(), "worldSessionId"),
                        request.leaseId(),
                        identity,
                        request.maximumDuration())
                .orElseThrow(() -> new IllegalStateException("evaluation lease is already active"));
        evaluationFenceRevision = Math.incrementExact(evaluationFenceRevision);
        try {
            runner.onExit().thenRun(() -> requestEvaluationReleaseFromAnyThread(
                    lease.leaseId(), EvaluationTurnControl.ReleaseReason.RUNNER_PROCESS_EXITED));
        } catch (RuntimeException | LinkageError failure) {
            terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.RUNNER_PROCESS_EXITED);
            throw new IllegalStateException("evaluation runner cannot be monitored", failure);
        }
        if (!runner.isAlive() || !identity.matches(runner)) {
            terminateActiveEvaluationOnClient(
                    minecraft, EvaluationTurnControl.ReleaseReason.RUNNER_PROCESS_EXITED);
            throw new IllegalStateException("evaluation runner exited during admission");
        }
        return new EvaluationTurnControl.LeaseReceipt(
                lease.leaseId(),
                EvaluationTurnControl.LeaseState.ACTIVE,
                null,
                false,
                true,
                true,
                true);
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> await(UUID leaseId) {
        Objects.requireNonNull(leaseId, "leaseId");
        var snapshot = evaluationTurns.snapshot(publishedSession.worldSessionId());
        if (snapshot.activeLease().filter(lease -> lease.leaseId().equals(leaseId)).isPresent()) {
            return evaluationTurns.awaitTerminal(snapshot.activeLease().orElseThrow())
                    .thenApply(McmcpRuntime::evaluationReceipt);
        }
        if (snapshot.previousTerminal()
                .filter(terminal -> terminal.lease().leaseId().equals(leaseId)).isPresent()) {
            return CompletableFuture.completedFuture(evaluationReceipt(
                    snapshot.previousTerminal().orElseThrow()));
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("unknown evaluation lease"));
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> release(
            UUID leaseId,
            EvaluationTurnControl.ReleaseReason reason) {
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(reason, "reason");
        final EvaluationTerminalClaim claim;
        try {
            claim = claimEvaluationTerminal(leaseId, reason);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (claim.completedReceipt() != null) {
            return CompletableFuture.completedFuture(claim.completedReceipt());
        }
        if (!claim.owner()) {
            return claim.pending().completion.copy();
        }

        var fence = publishedSession;
        long deadline = RuntimeCallContext.deadlineAfter(
                System.nanoTime(), EVALUATION_CONTROL_DISPATCH_TIMEOUT.toNanos());
        var submitted = inbox.submitControl(
                "evaluation_turn_release",
                fence.generation(),
                deadline,
                () -> terminateEvaluationLeaseOnClient(
                        Minecraft.getInstance(), claim.pending()));
        submitted.whenComplete((receipt, failure) -> {
            if (failure != null) {
                // Claiming the terminal intent is the ownership hand-off. Queue invalidation
                // cannot publish failure or discard that intent; pre-tick/lifecycle cleanup
                // keeps retrying while the guard remains physically isolating input.
                return;
            } else if (receipt != null) {
                completePendingEvaluationTerminal(claim.pending(), receipt);
            }
        });
        return claim.pending().completion.copy();
    }

    @Override
    public boolean active(UUID leaseId) {
        return leaseId != null && fenceSnapshot().accepts(leaseId);
    }

    @Override
    public boolean anyActive() {
        return fenceSnapshot().isolationActive();
    }

    @Override
    public EvaluationTurnControl.FenceSnapshot fenceSnapshot() {
        synchronized (evaluationTerminalGate) {
            var activeLease = evaluationTurns.snapshot(publishedSession.worldSessionId())
                    .activeLease()
                    .orElse(null);
            UUID acceptedLeaseId = activeLease != null && pendingEvaluationTerminal == null
                    ? activeLease.leaseId() : null;
            return new EvaluationTurnControl.FenceSnapshot(
                    evaluationFenceRevision,
                    activeLease != null,
                    acceptedLeaseId);
        }
    }

    private EvaluationTerminalClaim claimEvaluationTerminal(
            UUID leaseId,
            EvaluationTurnControl.ReleaseReason reason) {
        synchronized (evaluationTerminalGate) {
            var snapshot = evaluationTurns.snapshot(publishedSession.worldSessionId());
            var activeLease = snapshot.activeLease().orElse(null);
            if (activeLease == null) {
                var terminal = snapshot.previousTerminal()
                        .filter(value -> value.lease().leaseId().equals(leaseId))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "unknown evaluation lease"));
                return new EvaluationTerminalClaim(
                        null, false, evaluationReceipt(terminal));
            }
            if (!activeLease.leaseId().equals(leaseId)) {
                throw new IllegalArgumentException("unknown evaluation lease");
            }
            if (pendingEvaluationTerminal != null) {
                if (!pendingEvaluationTerminal.lease.equals(activeLease)) {
                    throw new IllegalStateException(
                            "evaluation terminal intent belongs to another lease");
                }
                return new EvaluationTerminalClaim(
                        pendingEvaluationTerminal, false, null);
            }
            var pending = new PendingEvaluationTerminal(activeLease, reason);
            pendingEvaluationTerminal = pending;
            evaluationFenceRevision = Math.incrementExact(evaluationFenceRevision);
            return new EvaluationTerminalClaim(pending, true, null);
        }
    }

    private void requestEvaluationReleaseFromAnyThread(
            UUID leaseId,
            EvaluationTurnControl.ReleaseReason reason) {
        release(leaseId, reason).whenComplete((ignored, failure) -> {
            if (failure != null && active(leaseId)) {
                McmcpMod.LOGGER.warn(
                        "MCMCP evaluation lease release could not reach the client lane: {}",
                        reason.wireName());
            }
        });
    }

    private EvaluationTurnControl.LeaseReceipt terminateEvaluationLeaseOnClient(
            Minecraft minecraft,
            PendingEvaluationTerminal pending) {
        assertClientThread(minecraft);
        if (pending.completion.isDone()) {
            return pending.completedReceipt();
        }
        CompletableFuture<ClientCommandInbox.StopReceipt> stop = pending.stopCompletion();
        if (stop == null) {
            stop = switch (pending.reason) {
                case LOCAL_ESCAPE -> inbox.requestLocalEmergencyStop();
                case LOCAL_UI_DISABLED -> inbox.requestLocalDisable();
                default -> inbox.requestEmergencyStop(
                        "evaluation_" + pending.reason.wireName());
            };
            pending.retainStopCompletion(stop);
        }
        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        PendingEvaluationStopOutcome stopOutcome = pending.stopOutcome();
        if (stopOutcome == null) {
            return null;
        }
        ClientCommandInbox.StopReceipt stopReceipt = stopOutcome.failure() == null
                ? stopOutcome.receipt() : null;
        if (stopReceipt == null) {
            pending.retryStopAfter(stop);
            arming.lock(EvaluationTurnControl.ReleaseReason.INPUT_RELEASE_FAILED.wireName());
            return null;
        }
        boolean inputsReleased = stopReceipt.inputsReleased();
        boolean inputOwnerNone = stopReceipt.inputOwnerNone();
        boolean allActionsTerminal = inputsReleased
                && inputOwnerNone
                && evaluationActionsTerminal();
        var releasableReason = evaluationTerminalReasonIfSafe(
                pending.reason, inputsReleased, inputOwnerNone, allActionsTerminal);
        if (releasableReason.isEmpty()) {
            arming.lock(EvaluationTurnControl.ReleaseReason.INPUT_RELEASE_FAILED.wireName());
            if (!inputsReleased || !inputOwnerNone) {
                // A terminally unsafe receipt does not prove that later idempotent release
                // attempts will fail. Retain the lease/fence and retry through the same lane.
                pending.retryStopAfter(stop);
            }
            return null;
        }
        var terminalReason = releasableReason.orElseThrow();
        if (locksLocalArming(terminalReason)) {
            arming.lock(terminalReason.wireName());
        }
        boolean terminalized = terminalReason == EvaluationTurnControl.ReleaseReason.TURN_COMPLETED
                ? evaluationTurns.release(pending.lease, terminalReason.wireName())
                : evaluationTurns.revoke(pending.lease, terminalReason.wireName());
        EvaluationTurnControl.LeaseReceipt receipt;
        if (terminalized) {
            receipt = new EvaluationTurnControl.LeaseReceipt(
                    pending.lease.leaseId(),
                    EvaluationTurnControl.LeaseState.RELEASED,
                    terminalReason.wireName(),
                    true,
                    true,
                    true,
                    true);
        } else {
            receipt = evaluationTurns.snapshot(publishedSession.worldSessionId())
                    .previousTerminal()
                    .filter(terminal -> terminal.lease().leaseId()
                            .equals(pending.lease.leaseId()))
                    .map(McmcpRuntime::evaluationReceipt)
                    .orElseThrow(() -> new IllegalStateException(
                            "evaluation terminal state was not retained"));
        }
        completePendingEvaluationTerminal(pending, receipt);
        return receipt;
    }

    private boolean evaluationActionsTerminal() {
        return agentActions.active().isEmpty()
                && routines.activeRoutineId().isEmpty()
                && pendingAgentTerminal == null
                && !pendingAgentInputRelease
                && agentExecution == null
                && pendingAgentAdmission == null
                && !finalizationRetries.hasPending()
                && voiceRoutineId == null
                && !inbox.hasPendingCommand("start_routine")
                && !inbox.hasPendingCommand("agent_start_action");
    }

    static Optional<EvaluationTurnControl.ReleaseReason> evaluationTerminalReasonIfSafe(
            EvaluationTurnControl.ReleaseReason firstIntent,
            boolean inputsReleased,
            boolean inputOwnerNone,
            boolean allActionsTerminal) {
        Objects.requireNonNull(firstIntent, "firstIntent");
        return inputsReleased && inputOwnerNone && allActionsTerminal
                ? Optional.of(firstIntent)
                : Optional.empty();
    }

    private void terminateActiveEvaluationOnClient(
            Minecraft minecraft,
            EvaluationTurnControl.ReleaseReason reason) {
        var activeLease = evaluationTurns.snapshot(sessions.snapshot().worldSessionId())
                .activeLease().orElse(null);
        if (activeLease == null) {
            return;
        }
        var claim = claimEvaluationTerminal(activeLease.leaseId(), reason);
        if (claim.completedReceipt() == null) {
            terminateEvaluationLeaseOnClient(minecraft, claim.pending());
        }
    }

    private void terminateInvalidEvaluationLeaseOnClient(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!evaluationTurns.snapshot(session.worldSessionId()).active()) {
            return;
        }
        var control = arming.snapshot(session.worldSessionId());
        if (control.locked()) {
            var reason = control.lastLockReason() != null
                    && control.lastLockReason().contains("input_release_failed")
                    ? EvaluationTurnControl.ReleaseReason.INPUT_RELEASE_FAILED
                    : EvaluationTurnControl.ReleaseReason.RUNNER_FAILURE;
            terminateActiveEvaluationOnClient(minecraft, reason);
            return;
        }
        var invalidation = evaluationTurns
                .leaseNeedingRevocation(session.worldSessionId())
                .orElse(null);
        if (invalidation == null) {
            return;
        }
        var reason = switch (invalidation.reason()) {
            case LEASE_EXPIRED -> EvaluationTurnControl.ReleaseReason.LEASE_EXPIRED;
            case WORLD_SESSION_CHANGED -> EvaluationTurnControl.ReleaseReason.WORLD_CHANGED;
        };
        terminateActiveEvaluationOnClient(minecraft, reason);
    }

    private void finishPendingEvaluationTerminalOnClient(Minecraft minecraft) {
        PendingEvaluationTerminal pending;
        synchronized (evaluationTerminalGate) {
            pending = pendingEvaluationTerminal;
        }
        if (pending != null && !pending.completion.isDone()) {
            terminateEvaluationLeaseOnClient(minecraft, pending);
        }
    }

    private void completePendingEvaluationTerminal(
            PendingEvaluationTerminal pending,
            EvaluationTurnControl.LeaseReceipt receipt) {
        synchronized (evaluationTerminalGate) {
            if (pendingEvaluationTerminal == pending) {
                pendingEvaluationTerminal = null;
            }
        }
        pending.rememberCompletedReceipt(receipt);
        pending.completion.complete(receipt);
    }

    private static boolean locksLocalArming(
            EvaluationTurnControl.ReleaseReason reason) {
        return switch (reason) {
            case LOCAL_UI_DISABLED, WORLD_CHANGED, PLAYER_UNAVAILABLE, ENDPOINT_FAULT,
                    CLIENT_SHUTDOWN, INPUT_RELEASE_FAILED -> true;
            case TURN_COMPLETED, RUNNER_FAILURE, EVALUATION_DEADLINE,
                    LAUNCHER_TEARDOWN, RUNNER_CONNECTION_CLOSED,
                    RUNNER_PROCESS_EXITED, LOCAL_ESCAPE, LEASE_EXPIRED,
                    ACQUIRE_ABANDONED -> false;
        };
    }

    private static EvaluationTurnControl.LeaseReceipt evaluationReceipt(
            EvaluationTurnGuard.Terminal terminal) {
        return new EvaluationTurnControl.LeaseReceipt(
                terminal.lease().leaseId(),
                EvaluationTurnControl.LeaseState.RELEASED,
                terminal.reason(),
                true,
                true,
                true,
                true);
    }

    private static final class PendingEvaluationTerminal {
        private final EvaluationTurnGuard.Lease lease;
        private final EvaluationTurnControl.ReleaseReason reason;
        private final CompletableFuture<EvaluationTurnControl.LeaseReceipt> completion =
                new CompletableFuture<>();
        private CompletableFuture<ClientCommandInbox.StopReceipt> stopCompletion;
        private ClientCommandInbox.StopReceipt stopReceipt;
        private Throwable stopFailure;
        private boolean stopSettled;
        private EvaluationTurnControl.LeaseReceipt completedReceipt;

        private PendingEvaluationTerminal(
                EvaluationTurnGuard.Lease lease,
                EvaluationTurnControl.ReleaseReason reason) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        private synchronized CompletableFuture<ClientCommandInbox.StopReceipt> stopCompletion() {
            return stopCompletion;
        }

        private synchronized void retainStopCompletion(
                CompletableFuture<ClientCommandInbox.StopReceipt> stop) {
            Objects.requireNonNull(stop, "stop");
            if (stopCompletion != null) {
                return;
            }
            stopCompletion = stop;
            stop.whenComplete((receipt, failure) -> {
                synchronized (this) {
                    if (stopCompletion == stop) {
                        stopReceipt = receipt;
                        stopFailure = failure;
                        stopSettled = true;
                    }
                }
            });
        }

        private synchronized PendingEvaluationStopOutcome stopOutcome() {
            return stopSettled
                    ? new PendingEvaluationStopOutcome(stopReceipt, stopFailure)
                    : null;
        }

        private synchronized void retryStopAfter(
                CompletableFuture<ClientCommandInbox.StopReceipt> completedStop) {
            if (stopCompletion != completedStop || !stopSettled) {
                return;
            }
            stopCompletion = null;
            stopReceipt = null;
            stopFailure = null;
            stopSettled = false;
        }

        private synchronized void rememberCompletedReceipt(
                EvaluationTurnControl.LeaseReceipt receipt) {
            completedReceipt = Objects.requireNonNull(receipt, "receipt");
        }

        private synchronized EvaluationTurnControl.LeaseReceipt completedReceipt() {
            return completedReceipt;
        }
    }

    private record PendingEvaluationStopOutcome(
            ClientCommandInbox.StopReceipt receipt,
            Throwable failure) {
    }

    private record EvaluationTerminalClaim(
            PendingEvaluationTerminal pending,
            boolean owner,
            EvaluationTurnControl.LeaseReceipt completedReceipt) {
        private EvaluationTerminalClaim {
            if ((pending == null) == (completedReceipt == null)) {
                throw new IllegalArgumentException(
                        "claim must contain either pending or completed terminal state");
            }
        }
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
                                : mapFailure(failure));
                    });
            return inbox.submitControlMapped(
                    command.toolName(),
                    fence.generation(),
                    context.deadlineNanos(),
                    emergency,
                    failure -> CompletableFuture.completedFuture(mapFailure(failure)))
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
                requireReady(session);
                PreparedObservationPage prepared = getAgentObservation(observation.arguments());
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
                        work, McmcpRuntime::mapFailure)
                : inbox.submitMapped(
                        command.toolName(), fence.generation(), context.deadlineNanos(),
                        work, McmcpRuntime::mapFailure, () -> false,
                        this::abandonUnconfirmedDelivery);
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
            UUID requestedId = actionId(command.arguments());
            int requestedWaitMillis = agentActionWaitTimeoutMillis(command.arguments());
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
                    () -> RuntimeReply.success(actionPayload(snapshot)));
            return CompletableFuture.completedFuture(reply);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            context.cancel();
            return CompletableFuture.completedFuture(RuntimeReply.failure(
                    "server_busy", "The action terminal wait was interrupted", true));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
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
            predicateRequirements = predicateRequirements(request.program());
            localSafetyRequired = actionAdmissionRequiresLocalSafety(request.program());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
        }
        var fence = publishedSession;
        var capture = inbox.submit(
                command.toolName(),
                fence.generation(),
                context.deadlineNanos(),
                () -> withEvaluationLeaseFence(
                        context,
                        command.toolName(),
                        () -> captureAgentAdmission(
                                Minecraft.getInstance(),
                                sessions.snapshot(),
                                predicateRequirements,
                                localSafetyRequired,
                                containsRecipeReference(request.program()))));
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
            prepared = prepareAgentAction(request, source, snapshot, context);
            requireLiveCall(context, command.toolName());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(mapFailure(failure));
        }

        java.util.concurrent.Callable<RuntimeReply> commit = () ->
                withEvaluationLeaseFence(context, command.toolName(), () ->
                        RuntimeReply.success(commitAgentAction(
                                Minecraft.getInstance(),
                                sessions.snapshot(),
                                prepared,
                                context)));
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
                    "confirmed", deliveredAgentEvidence.confirmDelivery(delivery.receiptId()));
            case AbandonObservationDelivery delivery -> Map.of(
                    "abandoned", deliveredAgentEvidence.abandonDelivery(delivery.receiptId()));
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

    private PreparedObservationPage getAgentObservation(Map<String, Object> arguments) {
        Set<String> required = Set.of("schema_version", "frame_id", "kinds", "cursor", "limit");
        requireAllowedKeys(arguments, "agent_get_observation",
                Set.of("schema_version", "frame_id", "kinds", "filter", "cursor", "limit"));
        if (!arguments.keySet().containsAll(required)
                || arguments.size() < required.size()
                || arguments.size() > required.size() + 1) {
            throw new IllegalArgumentException(
                    "agent_get_observation must contain schema_version, frame_id, kinds, cursor, "
                            + "and limit; filter is optional");
        }
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
        ObservationFilter filter = observationFilterArgument(arguments);
        try {
            ObservationPage page = agentObservationFrames.page(
                    stringArgument(arguments, "frame_id"),
                    kinds,
                    filter,
                    cursor,
                    intArgument(arguments, "limit"));
            UUID receiptId = deliveredAgentEvidence.prepareDelivery(page);
            Map<String, Object> wirePage = ObservationWireMapper.page(page, surface ->
                    deliveredAgentEvidence.preparedPlacementStateRef(receiptId, surface)
                            .orElse(null));
            return new PreparedObservationPage(wirePage, receiptId);
        } catch (ObservationStoreException failure) {
            throw new RuntimeInvocationException(
                    failure.code().name().toLowerCase(Locale.ROOT),
                    failure.getMessage(),
                    failure.code() != ObservationStoreException.Code.INVALID_CURSOR,
                    Map.of());
        }
    }

    private static ObservationFilter observationFilterArgument(Map<String, Object> arguments) {
        return ObservationFilterArguments.parse(arguments);
    }

    private void abandonUnconfirmedDelivery(RuntimeReply reply) {
        if (reply != null
                && reply.deliveryReceipt()
                        instanceof McpRuntimePort.ObservationDeliveryReceipt observation) {
            deliveredAgentEvidence.abandonDelivery(observation.receiptId());
        }
    }

    private record PreparedObservationPage(Map<String, Object> wirePage, UUID receiptId) {
        private PreparedObservationPage {
            wirePage = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(
                            Objects.requireNonNull(wirePage, "wirePage")));
            Objects.requireNonNull(receiptId, "receiptId");
        }
    }

    /**
     * Planner view containing the current frame plus only static surfaces that were actually
     * returned to the MCP client. Every consumer still applies its ordinary revision, pose,
     * reach, age, commit, and JIT fences; dynamic evidence is never extended by this view.
     */
    private Optional<ObservationFrame> agentPlanningFrame() {
        return deliveredAgentEvidence.augment(agentObservationFrames.latestFrame());
    }

    private AgentAdmissionSnapshot captureAgentAdmission(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PredicateRequirements predicateRequirements,
            boolean localSafetyRequired,
            boolean recipeReferenceRequired) {
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
        if (localSafetyRequired
                && localSafety != LocalObservationProjector.CurrentSafety.CONTINUE) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The Local Observation Volume does not permit action admission.",
                    true,
                    Map.of());
        }
        if (recipeReferenceRequired) {
            recipeCatalog.refreshFromClient(
                    minecraft,
                    Objects.requireNonNull(session.worldSessionId(), "worldSessionId"),
                    session.clientTick());
        }
        var map = requireAgentMap(session);
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        final long visualBarrierWorldRevision;
        try {
            visualBarrierWorldRevision = visualBarrierWorldRevision(map, reconciliation);
        } catch (AgentPrimitivePlanner.PlanningException mismatch) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The visual evidence boundary does not match the traversability map.",
                    true,
                    Map.of());
        }
        var player = Objects.requireNonNull(minecraft.player, "player");
        var predicateSnapshot = AdmissionPolicySnapshot.capture(
                policySnapshot(minecraft), predicateRequirements);
        return new AgentAdmissionSnapshot(
                session,
                lock,
                map,
                playerPose(player, session.dimension()),
                agentPlanningFrame(),
                localSafety,
                localSafetyRequired,
                predicateRequirements,
                predicateSnapshot,
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                minecraft.isMultiplayerServer(),
                multiplayerPolicyAllows(minecraft),
                reconciliation,
                visualBarrierWorldRevision,
                reconciliation.positionCorrectionRevision());
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
            ActionDslSource source,
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
        if (snapshot.control().capabilities().contains("inventory_transfer")) {
            allowed.add(ActionDsl.Capability.INVENTORY_TRANSFER);
        }
        if (snapshot.control().capabilities().contains("item_use")) {
            allowed.add(ActionDsl.Capability.ITEM_USE);
        }
        if (snapshot.control().capabilities().contains("entity_attack")) {
            allowed.add(ActionDsl.Capability.ENTITY_ATTACK);
        }
        ActionDslCompiler.CompiledProgram program = ActionDslCompiler.compile(
                request, this::admissionPrimitiveCost, allowed);
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
                            snapshot.visualBarrierWorldRevision(),
                            primitiveSurfaceRevisionBarrier(
                                    primitive,
                                    snapshot.map(),
                                    snapshot.reconciliation()),
                            context::canBeginWork))
                    .orElseGet(McmcpRuntime::emptyPrimitiveAnalysis);
            initialPrimitive.flatMap(analysis::worstCase).ifPresent(cost ->
                    ActionDslCompiler.requireWithinBudget(cost, program.effectiveBudget()));
        } catch (AgentPrimitivePlanner.PlanningException failure) {
            throw planningFailure(failure);
        }
        return new PreparedAgentAction(snapshot, program, source, analysis, initialPrimitive);
    }

    private AgentPrimitivePlanner.Analysis analyzePrimitive(
            ActionDsl.Program program,
            ActionDsl.Node primitive,
            KnownTraversabilitySnapshot map,
            AgentPrimitivePlanner.Pose pose,
            Optional<ObservationFrame> frame,
            float cameraDegreesPerTick,
            long visualBarrierWorldRevision,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
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
                visualBarrierWorldRevision,
                surfaceRevisionBarrier,
                canContinue,
                deliveredAgentEvidence::resolvePlacementState);
    }

    private static AgentPrimitivePlanner.Analysis emptyPrimitiveAnalysis() {
        return new AgentPrimitivePlanner.Analysis(
                Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
    }

    private static Optional<ActionDsl.Node> firstPrimitive(
            ActionDsl.Program program, PolicySnapshot snapshot) {
        return Optional.ofNullable(new ActionProgramCursor(program).next(snapshot).primitive());
    }

    private static boolean containsRecipeReference(ActionDsl.Program program) {
        return program.body().stream().anyMatch(McmcpRuntime::containsRecipeReference);
    }

    private static boolean containsRecipeReference(ActionDsl.Node node) {
        if (node instanceof ActionDsl.CraftKnownRecipe
                || node instanceof ActionDsl.SmeltKnownRecipe) {
            return true;
        }
        if (node instanceof ActionDsl.If conditional) {
            return conditional.thenBranch().stream().anyMatch(McmcpRuntime::containsRecipeReference)
                    || conditional.elseBranch().stream()
                            .anyMatch(McmcpRuntime::containsRecipeReference);
        }
        return node instanceof ActionDsl.Repeat repeat
                && repeat.body().stream().anyMatch(McmcpRuntime::containsRecipeReference);
    }

    private static boolean requiresWorldPlanning(ActionDsl.Node node) {
        return !(node instanceof ActionDsl.WaitTicks
                || node instanceof ActionDsl.OperateKnownMenu
                || node instanceof ActionDsl.ReelKnownFishingSession
                || node instanceof ActionDsl.OperateKillZone
                || node instanceof ActionDsl.WaitUntil wait
                        && wait.condition() instanceof ActionDsl.SoundClueCondition);
    }

    static AgentPrimitivePlanner.ApproachPlan requireRuntimeApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            AgentPrimitivePlanner.Pose startPose,
            ActionDsl.ApproachKnownSurface approach,
            Optional<ObservationFrame> planningFrame,
            long surfaceBarrierWorldRevision) {
        AgentPrimitivePlanner.requireKnownSurface(
                map,
                planningFrame,
                approach.target(),
                approach.expectedBlock(),
                surfaceBarrierWorldRevision);
        return AgentPrimitivePlanner.requireApproachPlan(
                map,
                pathfinder,
                startPose,
                approach.target(),
                approach.expectedBlock(),
                planningFrame,
                surfaceBarrierWorldRevision);
    }

    static AgentPrimitivePlanner.ApproachPlan requireRuntimeKnownPlacementApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            AgentPrimitivePlanner.Pose startPose,
            ActionDsl.ApproachKnownPlacement approach,
            Optional<ObservationFrame> planningFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            PlacementStateResolver placementStates) {
        return AgentPrimitivePlanner.requireKnownPlacementApproachPlan(
                map,
                pathfinder,
                startPose,
                approach,
                planningFrame,
                surfaceRevisionBarrier,
                placementStates);
    }

    static boolean actionAdmissionRequiresLocalSafety(ActionDsl.Program program) {
        Objects.requireNonNull(program, "program");
        return program.body().size() != 1
                || !(program.body().getFirst() instanceof ActionDsl.OperateKnownMenu);
    }

    static Optional<ActionDslCompiler.Cost> structuralPrimitiveCost(ActionDsl.Node node) {
        long durationMillis = node instanceof ActionDsl.CraftKnownRecipe
                ? ActionDslCompiler.KNOWN_CRAFTING_DURATION_MILLIS
                : node instanceof ActionDsl.SmeltKnownRecipe smelt
                        ? ActionDslCompiler.knownSmeltingDurationMillis(smelt.maxSmelts())
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_DURATION_MILLIS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_DURATION_MILLIS
                : node instanceof ActionDsl.CastKnownFishingRod
                        ? ActionDslCompiler.KNOWN_FISHING_DURATION_MILLIS : 0L;
        long ticks = node instanceof ActionDsl.CraftKnownRecipe
                ? ActionDslCompiler.KNOWN_CRAFTING_TICKS
                : node instanceof ActionDsl.SmeltKnownRecipe smelt
                        ? ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts())
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_TICKS
                : node instanceof ActionDsl.CastKnownFishingRod
                        ? ActionDslCompiler.KNOWN_FISHING_TICKS : 0L;
        long interactions = node instanceof ActionDsl.TillKnownBlock
                        || node instanceof ActionDsl.OpenKnownFenceGate
                        || node instanceof ActionDsl.OpenKnownPassage
                        || node instanceof ActionDsl.InspectKnownContainer
                ? 1L
                : node instanceof ActionDsl.TillKnownBatch batch
                        ? batch.targets().size()
                : node instanceof ActionDsl.TakeKnownContainerStack ? 3L
                : node instanceof ActionDsl.StoreKnownContainerStack ? 3L
                : node instanceof ActionDsl.CraftKnownRecipe craft
                        ? ActionDslCompiler.knownCraftInteractions(craft.maxCrafts())
                : node instanceof ActionDsl.SmeltKnownRecipe
                        ? ActionDslCompiler.KNOWN_SMELTING_INTERACTIONS
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_INTERACTIONS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_INTERACTIONS
                : node instanceof ActionDsl.CastKnownFishingRod ? 2L : 0L;
        if (node instanceof ActionDsl.OperateKillZone operation) {
            return Optional.of(ActionDslCompiler.intrinsicKillZoneCost(operation));
        }
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return Optional.of(ActionDslCompiler.intrinsicCobblestoneGeneratorCost(operation));
        }
        long breaks = node instanceof ActionDsl.BreakKnownFace
                        || node instanceof ActionDsl.BreakKnownBlock
                        || node instanceof ActionDsl.HarvestKnownWheat
                ? 1L : 0L;
        if (node instanceof ActionDsl.HarvestKnownWheatBatch batch) {
            breaks = batch.targets().size();
        }
        long placements = node instanceof ActionDsl.PlantKnownWheat ? 1L : 0L;
        if (node instanceof ActionDsl.PlantKnownWheatBatch batch) {
            placements = batch.targets().size();
        }
        return Optional.of(new ActionDslCompiler.Cost(
                durationMillis, ticks, 0.0D, 0.0D, interactions, breaks, placements));
    }

    private Optional<ActionDslCompiler.Cost> admissionPrimitiveCost(ActionDsl.Node node) {
        if (node instanceof ActionDsl.PillarUpKnown pillar) {
            return Optional.of(pillarAdmissionCost(
                    pillar, deliveredAgentEvidence::resolvePlacementState));
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            long placements = plan.entries().stream()
                    .mapToLong(this::rememberedPlacementCells)
                    .sum();
            return Optional.of(ActionDslCompiler.intrinsicKnownBlockPlanCost(
                    plan.entries().size(), placements));
        }
        return structuralPrimitiveCost(node);
    }

    static ActionDslCompiler.Cost pillarAdmissionCost(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        Optional<PillarSource> source = resolvePillarSource(pillar, placementStates);
        source.ifPresent(value -> KnownPillarUpRequest.requireSourceStateAndItem(
                new BlockStateFingerprint(
                        value.state().block(), value.state().properties()),
                value.item()));
        // The footprint is fixed at one. Unknown/evicted refs need no identity guess for cost;
        // the admission planner resolves them separately and reports TARGET_UNKNOWN.
        return ActionDslCompiler.intrinsicPillarUpCost();
    }

    private long rememberedPlacementCells(ActionDsl.BlockPlanEntry entry) {
        Optional<ActionDsl.BlockStateSpec> state = entry.sourceState();
        if (state.isEmpty()) {
            state = entry.placementStateRef()
                    .flatMap(deliveredAgentEvidence::resolvePlacementState)
                    .map(remembered -> new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(),
                            remembered.state().properties()));
        }
        // Unknown/evicted references remain fail-closed here and are rejected by planning.
        return state.map(value -> (long) SafeConstructionBlocks
                        .placementCellCount(value.block()))
                .orElse(2L);
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
        ActionDsl.OperateKillZone killZone = soleKillZone(prepared.program().request().program());
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

    private static ActionDsl.OperateKillZone soleKillZone(ActionDsl.Program program) {
        return program.body().size() == 1
                        && program.body().getFirst() instanceof ActionDsl.OperateKillZone operation
                ? operation : null;
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
                || minecraft.gui.screen() != null) {
            throw new RuntimeInvocationException(
                    "unsafe_state", "Kill-zone consent requires a grounded survival player with no Screen.",
                    true, Map.of());
        }
        String heldItem = BuiltInRegistries.ITEM.getKey(
                player.getMainHandItem().getItem()).toString();
        if (!operation.mainHandItem().equals(heldItem)) {
            throw new RuntimeInvocationException(
                    "target_changed", "The declared main-hand item is not currently held.",
                    true, Map.of());
        }
        AttackProfile profile = requireKnownAttackProfile(player.getMainHandItem());
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
        requireKillZoneBarrier(
                level, player, station, zone, operation.entityTypeAllowlist());
        String structure = killZoneStructureFingerprint(level, station, zone);
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
        appendIdentity(binding, scope.toString());
        return new KillZoneAdmission(sha256Identity(binding), scope);
    }

    private static AttackProfile requireKnownAttackProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile", "The main hand has no supported attack item.",
                    false, Map.of());
        }
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean sword = item.matches("minecraft:(wooden|stone|copper|iron|golden|diamond|netherite)_sword");
        boolean axe = item.matches("minecraft:(wooden|stone|copper|iron|golden|diamond|netherite)_axe");
        if (!sword && !axe) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile",
                    "Only audited Vanilla swords and axes are supported; MOD profiles need an adapter.",
                    false, Map.of());
        }
        var canonical = new StringBuilder(item);
        for (var entry : stack.getComponentsPatch().entrySet()) {
            if (entry.getKey() != DataComponents.DAMAGE
                    && entry.getKey() != DataComponents.ENCHANTMENTS) {
                throw new RuntimeInvocationException(
                        "unsupported_attack_profile",
                        "The held stack has an unaudited attack-relevant component patch.",
                        false, Map.of());
            }
        }
        if (!stack.getEnchantments().isEmpty()) {
            throw new RuntimeInvocationException(
                    "unsupported_attack_profile",
                    "The initial production slice accepts only unenchanted Vanilla swords and axes.",
                    false, Map.of());
        }
        return new AttackProfile(
                sha256Identity(canonical),
                sword
                        ? ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SWEEP
                        : ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET);
    }

    private static String killZoneStructureFingerprint(
            net.minecraft.client.multiplayer.ClientLevel level,
            ScopedEntityAttackConsentStore.Bounds station,
            ScopedEntityAttackConsentStore.Bounds zone) {
        int minX = Mth.floor(Math.min(station.minX(), zone.minX())) - 1;
        int minY = Mth.floor(Math.min(station.minY(), zone.minY())) - 1;
        int minZ = Mth.floor(Math.min(station.minZ(), zone.minZ())) - 1;
        int maxX = Mth.ceil(Math.max(station.maxX(), zone.maxX())) + 1;
        int maxY = Mth.ceil(Math.max(station.maxY(), zone.maxY())) + 1;
        int maxZ = Mth.ceil(Math.max(station.maxZ(), zone.maxZ())) + 1;
        long cells = Math.multiplyExact(
                Math.multiplyExact((long) maxX - minX + 1L, (long) maxY - minY + 1L),
                (long) maxZ - minZ + 1L);
        if (cells > 8_192L) {
            throw new RuntimeInvocationException(
                    "unsupported_kill_zone", "The structure witness exceeds 8192 loaded cells.",
                    false, Map.of());
        }
        var canonical = new StringBuilder();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    var pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        throw new RuntimeInvocationException(
                                "target_unknown", "Every structure witness cell must be loaded.",
                                true, Map.of());
                    }
                    BlockState state = level.getBlockState(pos);
                    appendIdentity(canonical, x + "," + y + "," + z);
                    appendIdentity(canonical,
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    state.getValues()
                            .map(value -> value.property().getName() + "=" + value.valueName())
                            .sorted()
                            .forEach(value -> appendIdentity(canonical, value));
                    state.getCollisionShape(level, pos).toAabbs().stream()
                            .map(box -> box.minX + "," + box.minY + "," + box.minZ + ","
                                    + box.maxX + "," + box.maxY + "," + box.maxZ)
                            .sorted()
                            .forEach(value -> appendIdentity(canonical, value));
                    var fluid = state.getFluidState();
                    appendIdentity(canonical, fluid.isEmpty() ? "empty"
                            : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString()
                                    + ":" + fluid.getAmount() + ":" + fluid.isSource());
                }
            }
        }
        return sha256Identity(canonical);
    }

    private static void requireKillZoneBarrier(
            net.minecraft.client.multiplayer.ClientLevel level,
            Player player,
            ScopedEntityAttackConsentStore.Bounds station,
            ScopedEntityAttackConsentStore.Bounds zone,
            List<String> allowedTypes) {
        Set<String> auditedTypes = Set.of(
                "minecraft:armor_stand",
                "minecraft:zombie",
                "minecraft:skeleton");
        if (!auditedTypes.containsAll(allowedTypes)) {
            throw unsafeKillZone(
                    "The initial fixture accepts only audited armor-stand and basic zombie/skeleton types.");
        }
        AABB playerBox = player.getBoundingBox();
        AABB safetyVolume = playerBox.inflate(8.0D);
        if (zone.minX() < safetyVolume.minX || zone.minY() < safetyVolume.minY
                || zone.minZ() < safetyVolume.minZ || zone.maxX() > safetyVolume.maxX
                || zone.maxY() > safetyVolume.maxY || zone.maxZ() > safetyVolume.maxZ) {
            throw unsafeKillZone(
                    "The complete kill zone must remain inside the eight-block hazard volume.");
        }
        int cellX = Mth.floor((playerBox.minX + playerBox.maxX) * 0.5D);
        int cellY = Mth.floor(playerBox.minY + 1.0e-5D);
        int cellZ = Mth.floor((playerBox.minZ + playerBox.maxZ) * 0.5D);
        if (Mth.floor(playerBox.minX) != Mth.floor(playerBox.maxX - 1.0e-5D)
                || Mth.floor(playerBox.minZ) != Mth.floor(playerBox.maxZ - 1.0e-5D)) {
            throw unsafeKillZone("The player must stand wholly inside one safety-cell column.");
        }

        double dx = (zone.minX() + zone.maxX()) * 0.5D - player.getX();
        double dz = (zone.minZ() + zone.maxZ()) * 0.5D - player.getZ();
        int frontX = cellX;
        int frontZ = cellZ;
        if (Math.abs(dx) >= Math.abs(dz) && dx > 0.0D && zone.minX() >= cellX + 2.0D) {
            frontX++;
        } else if (Math.abs(dx) >= Math.abs(dz) && dx < 0.0D
                && zone.maxX() <= cellX - 1.0D) {
            frontX--;
        } else if (Math.abs(dz) > Math.abs(dx) && dz > 0.0D
                && zone.minZ() >= cellZ + 2.0D) {
            frontZ++;
        } else if (Math.abs(dz) > Math.abs(dx) && dz < 0.0D
                && zone.maxZ() <= cellZ - 1.0D) {
            frontZ--;
        } else {
            throw unsafeKillZone(
                    "The kill zone must lie wholly beyond one cardinal face of the safety cell.");
        }

        BlockPos front = new BlockPos(frontX, cellY, frontZ);
        if (!exactFullCollisionCube(level, front)) {
            throw unsafeKillZone(
                    "The attack face lower block must be a full collision cube.");
        }
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            BlockPos low = new BlockPos(cellX + side[0], cellY, cellZ + side[1]);
            if (!low.equals(front) && !exactFullCollisionCube(level, low)) {
                throw unsafeKillZone("The other three lower safety-cell walls must be full cubes.");
            }
            if (low.equals(front)) {
                if (!exactCollisionBox(
                        level, low.above(), new AABB(0, 0.5D, 0, 1, 1, 1))) {
                    throw unsafeKillZone(
                            "The attack face requires an exact upper top slab over its half-block slit.");
                }
            } else if (!exactFullCollisionCube(level, low.above())) {
                throw unsafeKillZone("Every upper safety-cell wall must be a full cube.");
            }
        }
        if (!exactFullCollisionCube(level, new BlockPos(cellX, cellY - 1, cellZ))
                || !exactFullCollisionCube(level, new BlockPos(cellX, cellY + 2, cellZ))) {
            throw unsafeKillZone("The safety cell requires a full-cube support and roof.");
        }
        for (String allowedType : allowedTypes) {
            Identifier id = Identifier.tryParse(allowedType);
            var type = id == null ? null
                    : BuiltInRegistries.ENTITY_TYPE.get(id).map(Holder::value).orElse(null);
            if (type == null || type.getDimensions().height() <= 1.0F) {
                throw unsafeKillZone(
                        "Every allowed entity type must be taller than the fixed one-block opening.");
            }
        }
    }

    private static RuntimeInvocationException unsafeKillZone(String message) {
        return new RuntimeInvocationException(
                "unsafe_kill_zone", message, false, Map.of());
    }

    private static boolean exactFullCollisionCube(
            net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
        return exactCollisionBox(level, pos, new AABB(0, 0, 0, 1, 1, 1));
    }

    private static boolean exactCollisionBox(
            net.minecraft.client.multiplayer.ClientLevel level,
            BlockPos pos,
            AABB expected) {
        if (!level.isLoaded(pos)) return false;
        List<AABB> boxes = level.getBlockState(pos).getCollisionShape(level, pos).toAabbs();
        return boxes.size() == 1 && boxes.getFirst().equals(expected);
    }

    private record AttackProfile(
            String fingerprint,
            ScopedEntityAttackConsentStore.AttackSideEffectProfile sideEffects) {
        private AttackProfile {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(sideEffects, "sideEffects");
        }
    }

    private record KillZoneAdmission(
            String policyBindingHash,
            ScopedEntityAttackConsentStore.Scope scope) {
        private KillZoneAdmission {
            Objects.requireNonNull(policyBindingHash, "policyBindingHash");
            Objects.requireNonNull(scope, "scope");
        }
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
                || captured.localSafetyRequired()
                        && (captured.localSafety()
                                != LocalObservationProjector.CurrentSafety.CONTINUE
                                || localSafety
                                != LocalObservationProjector.CurrentSafety.CONTINUE)
                || McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F
                        != captured.cameraDegreesPerTick()
                || minecraft.isMultiplayerServer() != captured.multiplayerServer()
                || multiplayerPolicyAllows(minecraft) != captured.multiplayerAllowed()) {
            return false;
        }
        final KnownTraversabilitySnapshot currentMap;
        final AdmissionPolicySnapshot currentPredicates;
        final ClientReconciliationSignals.Snapshot currentReconciliation;
        final long currentVisualBarrierWorldRevision;
        final ToLongFunction<ActionDsl.Position> currentSurfaceRevisionBarrier;
        try {
            currentMap = requireAgentMap(session);
            currentReconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"), session.worldSessionId());
            currentVisualBarrierWorldRevision = visualBarrierWorldRevision(
                    currentMap, currentReconciliation);
            currentSurfaceRevisionBarrier = prepared.initialPrimitive()
                    .map(primitive -> primitiveSurfaceRevisionBarrier(
                            primitive, currentMap, currentReconciliation))
                    .orElseGet(() -> surfaceRevisionBarrier(
                            currentMap, currentReconciliation));
            if (currentReconciliation.positionCorrectionRevision()
                    != captured.positionCorrectionRevision()) {
                return false;
            }
            currentPredicates = AdmissionPolicySnapshot.capture(
                    policySnapshot(minecraft), captured.predicateRequirements());
            validatePredicateAvailability(
                    prepared.program().request().program(), currentPredicates);
        } catch (RuntimeException | LinkageError changed) {
            return false;
        }
        Optional<ObservationFrame> currentPlanningFrame = agentPlanningFrame();
        return firstPrimitive(prepared.program().request().program(), currentPredicates)
                        .equals(prepared.initialPrimitive())
                && routeDependenciesCurrent(currentMap, prepared.analysis().routeDependencies())
                && prepared.analysis().knownTargets().stream().allMatch(target ->
                        prepared.initialPrimitive()
                                        .filter(ActionDsl.FaceKnownPosition.class::isInstance)
                                        .isPresent()
                                ? AgentPrimitivePlanner.knownFacingTarget(
                                        currentMap, currentPlanningFrame, target)
                                : AgentPrimitivePlanner.knownTarget(
                                        currentMap,
                                        currentPlanningFrame,
                                        target,
                                        currentSurfaceRevisionBarrier.applyAsLong(target)))
                && prepared.analysis().knownFacingSurfaces().stream().allMatch(surface ->
                        AgentPrimitivePlanner.knownFacingSurface(
                                currentMap, currentPlanningFrame, surface))
                && prepared.analysis().knownSurfaces().stream().allMatch(surface ->
                        AgentPrimitivePlanner.knownSurface(
                                currentMap,
                                currentPlanningFrame,
                                surface,
                                currentSurfaceRevisionBarrier.applyAsLong(surface.position())))
                && prepared.initialPrimitive()
                        .filter(ActionDsl.CollectVisibleItem.class::isInstance)
                        .map(ActionDsl.CollectVisibleItem.class::cast)
                        .map(target -> AgentPrimitivePlanner.visibleItemCurrent(
                                currentMap,
                                currentPlanningFrame,
                                target,
                                currentVisualBarrierWorldRevision,
                                session.clientTick(),
                                visibleItemEvidenceMaxAgeTicks(
                                        McmcpClientConfig.raysPerTick())))
                        .orElse(true)
                && prepared.initialPrimitive()
                        .filter(ActionDsl.CollectVisibleItemBatch.class::isInstance)
                        .map(ActionDsl.CollectVisibleItemBatch.class::cast)
                        .map(batch -> AgentPrimitivePlanner.visibleBatchItemAabbs(
                                        currentMap,
                                        currentPlanningFrame,
                                        batch,
                                        currentVisualBarrierWorldRevision,
                                        session.clientTick(),
                                        visibleItemEvidenceMaxAgeTicks(
                                                McmcpClientConfig.raysPerTick()))
                                .stream().allMatch(Optional::isPresent))
                        .orElse(true)
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
        return actionPayload(agentActions.get(actionId(arguments)));
    }

    static int agentActionWaitTimeoutMillis(Map<String, Object> arguments) {
        requireAllowedKeys(
                arguments, "agent_get_action", Set.of("action_id", "wait_timeout_ms"));
        if (!arguments.containsKey("action_id")) {
            throw new IllegalArgumentException("agent_get_action must contain action_id");
        }
        int timeoutMillis = arguments.containsKey("wait_timeout_ms")
                ? intArgument(arguments, "wait_timeout_ms")
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
        requireExactKeys(arguments, "agent_cancel_action", Set.of("action_id"));
        UUID requestedId = actionId(arguments);
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

    private static UUID actionId(Map<String, Object> arguments) {
        try {
            return UUID.fromString(stringArgument(arguments, "action_id"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("action_id must be a UUID", failure);
        }
    }

    static Map<String, Object> actionPayload(AgentActionStore.Snapshot snapshot) {
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
        result.put("effects", snapshot.effects().stream().map(effect -> {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("seq", effect.seq());
            payload.put("node_id", effect.nodeId());
            payload.put("kind", effect.kind());
            payload.put("subject", effect.subject());
            payload.put("observed_before", effect.observedBefore());
            payload.put("observed_after", effect.observedAfter());
            payload.put("verification", effect.verification().wireName());
            payload.put("client_tick", effect.clientTick());
            payload.put("world_revision", effect.worldRevision());
            return Map.copyOf(payload);
        }).toList());
        var aggregate = snapshot.effectAggregate();
        result.put("effect_aggregate", Map.of(
                "total_effects", aggregate.totalEffects(),
                "retained_effects", aggregate.retainedEffects(),
                "confirmed_effects", aggregate.confirmedEffects(),
                "qualified_effects", aggregate.qualifiedEffects(),
                "unknown_effects", aggregate.unknownEffects(),
                "dispatched_attacks", aggregate.dispatchedAttacks(),
                "confirmed_attacks", aggregate.confirmedAttacks(),
                "unknown_attacks", aggregate.unknownAttacks()));
        Map<String, Object> partialPayload = null;
        if (snapshot.partial() != null) {
            var partial = snapshot.partial();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("has_confirmed_effects", partial.hasConfirmedEffects());
            payload.put("interrupted_node_id", partial.interruptedNodeId());
            payload.put("remaining_node_upper_bound", partial.remainingNodeUpperBound());
            payload.put(
                    "resume_requires_reobservation",
                    partial.resumeRequiresReobservation());
            partialPayload = payload;
        }
        result.put("partial", partialPayload);
        result.put("source", snapshot.source().sourcePayload());
        result.put("template", snapshot.source().templatePayload());
        result.put(
                "reference_requirements",
                snapshot.source().referenceRequirementPayload());
        return result;
    }

    private static Set<String> availableCapabilities(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        return AVAILABLE_CAPABILITIES;
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
                merchantOffers = merchantOfferPayload(
                        session.worldSessionId(), screen.getMenu().containerId, open, snapshot);
            }
            if (lock.mode() == LocalArmingState.Mode.READY) {
                knownMenu = knownMenuPayload(
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
                statePayload(
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
                entityAttackConsentPayload(entityAttackConsentSnapshot(session, lock)));
        if (!arguments.isEmpty()) {
            requireReady(session);
            result.put("recipe_query", getRecipes(minecraft, session, arguments));
        }
        if (merchantOffers != null) {
            result.put("merchant_offers", merchantOffers);
        }
        if (knownMenu != null) {
            result.put("known_menu", knownMenu);
        }
        result.put("observation", agentObservationFrames.announceLatestSummary()
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

    static Map<String, Object> merchantOfferPayload(
            UUID worldSessionId,
            int containerId,
            ContainerSyncSignals.OpenScreenEvidence open,
            MerchantOfferSignals.Snapshot snapshot) {
        if (open == null
                || snapshot == null
                || !worldSessionId.equals(open.worldSessionId())
                || !worldSessionId.equals(snapshot.worldSessionId())
                || containerId != open.containerId()
                || containerId != snapshot.containerId()
                || !"minecraft:merchant".equals(open.menuTypeId())
                || snapshot.openPacketRevision() != open.packetLedgerRevision()
                || snapshot.receivedTick() < open.receivedTick()) {
            return null;
        }
        return Map.of(
                "world_session_id", worldSessionId.toString(),
                "container_id", containerId,
                "signal_revision", snapshot.revision(),
                "open_packet_revision", snapshot.openPacketRevision(),
                "received_tick", snapshot.receivedTick(),
                "offers", MerchantOfferView.from(snapshot).stream()
                        .map(MerchantOfferView::toMap)
                        .toList());
    }

    static Map<String, Object> knownMenuPayload(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ContainerSyncSignals signals,
            KnownMenuOperationRefs references) {
        KnownMenuProfileSupport.Context context = KnownMenuProfileSupport.current(
                minecraft, session.worldSessionId(), signals).orElse(null);
        if (context == null) return null;

        var operations = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        long deadline = session.clientTick() > Long.MAX_VALUE - 1_200L
                ? Long.MAX_VALUE : session.clientTick() + 1_200L;
        for (int sourceSlot : context.transferableStorageSlots()) {
            ItemStack source = context.menu().slots.get(sourceSlot).getItem();
            if (!context.canTransferEntireStack(sourceSlot)) {
                continue;
            }
            if (operations.size() == KnownMenuOperationRefs.MAX_LEASES) {
                truncated = true;
                continue;
            }
            int baseline = exactPlayerCount(context, source);
            int expected = Math.addExact(baseline, source.getCount());
            String operationReference = references.issue(
                    context.referenceContext(session.worldSessionId(), session.clientTick()),
                    sourceSlot,
                    context.snapshot().slots().get(sourceSlot),
                    source,
                    context.snapshot().slots(),
                    baseline,
                    expected,
                    KnownMenuOperationRefs.TRANSFER_TO_PLAYER,
                    deadline);
            operations.add(Map.of(
                    "operation_ref", operationReference,
                    "kind", KnownMenuOperationRefs.TRANSFER_TO_PLAYER,
                    "stack", Map.of(
                            "item", context.snapshot().slots().get(sourceSlot).itemId(),
                            "count", source.getCount(),
                            "damage", source.getDamageValue(),
                            "max_damage", source.getMaxDamage()),
                    "expected_inventory_count", expected,
                    "valid_through_client_tick", deadline));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("profile_id", context.profile().profileId());
        payload.put("profile_hash", context.profile().profileHash());
        payload.put("menu_type", context.profile().menuType());
        payload.put("operations_truncated", truncated);
        payload.put("operations", List.copyOf(operations));
        return Map.copyOf(payload);
    }

    private static int exactPlayerCount(
            KnownMenuProfileSupport.Context context, ItemStack expected) {
        int count = 0;
        for (int slot : context.playerSlots()) {
            ItemStack actual = context.menu().slots.get(slot).getItem();
            if (ItemStack.isSameItemSameComponents(actual, expected)) {
                count = Math.addExact(count, actual.getCount());
            }
        }
        return count;
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory) {
        return statePayload(
                lock, paused, world, inventory, List.of(),
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
        return statePayload(
                lock, paused, world, inventory, List.of(), multiplayerEnabled,
                visualRadiusBlocks, raysPerTick);
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory,
            List<Map<String, Object>> standardPotions,
            boolean multiplayerEnabled,
            int visualRadiusBlocks,
            int raysPerTick) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(standardPotions, "standardPotions");

        var control = new LinkedHashMap<String, Object>();
        control.put("mode", lock.mode().name().toLowerCase(Locale.ROOT));
        // Kept nullable for MCP clients written against schema version 1; READY no longer expires.
        control.put("ready_expires_at", null);
        control.put("game_paused", paused);
        control.put("granted_capabilities", lock.capabilities().stream().sorted().toList());

        var actionDsl = new LinkedHashMap<String, Object>();
        actionDsl.put("version", 1);
        actionDsl.put("max_ast_depth", 4);
        actionDsl.put("max_source_nodes", 64);
        actionDsl.put("max_executed_nodes", 256);
        actionDsl.put("max_repeat_count", 16);
        actionDsl.put(
                "allowed_capabilities", AVAILABLE_CAPABILITIES.stream().sorted().toList());
        actionDsl.put(
                "available_operations",
                ActionDslOperationManifest.operationPayload(lock.capabilities()));
        actionDsl.put(
                "reference_descriptors",
                ActionDslOperationManifest.referenceDescriptorPayload());
        actionDsl.put(
                "missing_capability_guidance",
                ActionDslOperationManifest.missingCapabilityGuidance());
        var policy = Map.<String, Object>ofEntries(
                Map.entry("profile", "survival_omnidirectional"),
                Map.entry("multiplayer_enabled", multiplayerEnabled),
                Map.entry("max_duration_ms", Math.toIntExact(
                        ActionDslValidator.MAX_ACTION_DURATION_MILLIS)),
                Map.entry("max_ticks", ActionDslValidator.MAX_ACTION_TICKS),
                Map.entry("max_distance_blocks", 32),
                Map.entry("max_camera_degrees", ActionDslValidator.MAX_ACTION_CAMERA_DEGREES),
                Map.entry("max_blocks_broken", 8),
                Map.entry("max_interactions", ActionDslValidator.MAX_INTERACTIONS),
                Map.entry("max_blocks_placed", 8),
                Map.entry("omnidirectional_visual_radius_blocks", visualRadiusBlocks),
                Map.entry(
                        "local_observation_radius_blocks",
                        (int) LocalObservationVolume.RADIUS_BLOCKS),
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
        result.put("standard_potions", List.copyOf(standardPotions));
        result.put(
                "entity_attack_consent",
                entityAttackConsentPayload(ScopedEntityAttackConsentStore.Snapshot.none()));
        result.put("recipe_query", null);
        result.put("policy", policy);
        result.put("observation", null);
        result.put("action", null);
        return result;
    }

    static Map<String, Object> entityAttackConsentPayload(
            ScopedEntityAttackConsentStore.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var result = new LinkedHashMap<String, Object>();
        result.put("state", snapshot.state().name().toLowerCase(Locale.ROOT));
        result.put("policy_binding_hash", snapshot.policyBindingHash());
        if (snapshot.scope() == null) {
            result.put("scope", null);
        } else {
            var scope = snapshot.scope();
            result.put("scope", Map.ofEntries(
                    Map.entry("dimension", scope.dimension()),
                    Map.entry(
                            "player_station_bounds",
                            entityAttackConsentBoundsPayload(scope.playerStationBounds())),
                    Map.entry(
                            "target_kill_zone_bounds",
                            entityAttackConsentBoundsPayload(scope.targetKillZoneBounds())),
                    Map.entry("entity_type_allowlist", scope.entityTypeAllowlist()),
                    Map.entry("main_hand", Map.of(
                            "item", scope.mainHandItem(),
                            "attack_effects_bound", true)),
                    Map.entry(
                            "side_effect_profile",
                            scope.attackSideEffectProfile().name().toLowerCase(Locale.ROOT)),
                    Map.entry("structure_bound", true),
                    Map.entry("max_attacks", scope.maxAttacks()),
                    Map.entry("minimum_interval_ticks", scope.minimumIntervalTicks()),
                    Map.entry(
                            "max_operation_duration_ticks",
                            scope.maxOperationDurationTicks())));
        }
        boolean granted = snapshot.state() == ScopedEntityAttackConsentStore.State.GRANTED;
        result.put("consent_ref", granted ? snapshot.consentRef() : null);
        result.put("valid_before_tick", granted ? snapshot.validBeforeClientTick() : null);
        return result;
    }

    private static Map<String, Double> entityAttackConsentBoundsPayload(
            ScopedEntityAttackConsentStore.Bounds bounds) {
        return Map.of(
                "min_x", bounds.minX(),
                "min_y", bounds.minY(),
                "min_z", bounds.minZ(),
                "max_x", bounds.maxX(),
                "max_y", bounds.maxY(),
                "max_z", bounds.maxZ());
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
                                        "only an automation-opened canonical vanilla chest or barrel is used",
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
            returnControlReady();
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
            releaseAgentControl(minecraft);
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
                KillZoneAdmission killAuthorization = pending.killZoneAdmission();
                KillZoneAdmission currentKillAuthorization = null;
                if (killAuthorization != null) {
                    ActionDsl.OperateKillZone operation = Objects.requireNonNull(
                            soleKillZone(action.program().request().program()),
                            "kill-zone operation");
                    currentKillAuthorization = requireKillZoneAdmission(
                            minecraft, session, pending.prepared().source(), operation);
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
                agentExecution = nextExecution;
                if (killAuthorization != null) {
                    boolean consumed = currentKillAuthorization.equals(killAuthorization)
                            && (transportApprovalConsumed
                                    || entityAttackConsent.consumeExactForActionStart(
                                            Objects.requireNonNull(soleKillZone(
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
                                    soleKillZone(action.program().request().program())),
                            killAuthorization.scope(),
                            session.clientTick(),
                            minecraft.player.getHealth(),
                            minecraft.player.getAbsorptionAmount());
                    if (!advanceAgentProgram(
                            minecraft, agentActions.get(action.actionId()).progress())) {
                        return;
                    }
                }
                agentControlOwnershipEpoch = Math.incrementExact(agentControlOwnershipEpoch);
                pendingAgentAdmission = null;
                if (paused) {
                    pauseStartedAtNanos = startedAtNanos;
                }
            }
            if (agentExecution.killZone != null
                    && minecraft.player != null
                    && killZoneHealthDecreased(agentExecution.killZone, minecraft.player)) {
                safetyInterruptKillZone(
                        minecraft, session, action, agentExecution.killZone, "health_decreased");
                return;
            }
            if (paused) {
                releaseAgentInputsForHold(minecraft, "pause_input_release_failed");
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
                    && pickupInventoryIncreased(
                            agentExecution.pickupInventoryBefore,
                            inventoryItemCount(player, collect.displayedItem()))) {
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
                            || localSafety == LocalObservationProjector.CurrentSafety.REPLAN)) {
                failAgentAction(
                        AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                        true,
                        "cobblestone_generator_safety_changed");
                return;
            }
            if (!(agentExecution.primitive instanceof ActionDsl.OperateKnownMenu)
                    && !(agentExecution.primitive instanceof ActionDsl.PillarUpKnown)
                    && (recovery.state() == MinecraftRecoveryGovernor.State.REPLAN_REQUIRED
                            || localSafety == LocalObservationProjector.CurrentSafety.REPLAN)) {
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
            if (agentExecution.primitive
                    instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
                tickAgentCobblestoneGenerator(minecraft, session, action, operation);
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

            ActionDsl.CollectVisibleItem activeCollect = activeCollectTarget();
            if (activeCollect != null
                    && (agentExecution.pickupInventoryBefore >= 0
                            || agentExecution.collectBatchEvidence != null)) {
                if (agentExecution.pickupArrivalTick >= 0L) {
                    tickAgentPickupConfirmation(minecraft, session, action, activeCollect);
                    return;
                }
                if (agentExecution.pickupCell != null) {
                    var pickupMap = requireAgentMap(session);
                    long visualBarrierWorldRevision = visualBarrierWorldRevision(
                            pickupMap,
                            reconciliationSignals.bindAndSnapshot(
                                    Objects.requireNonNull(minecraft.level, "level"),
                                    session.worldSessionId()));
                    if (!AgentPrimitivePlanner.visibleItemPickupCellCurrent(
                            pickupMap,
                            agentPlanningFrame(),
                            activeCollect,
                            agentExecution.pickupCell,
                            visualBarrierWorldRevision,
                            session.clientTick(),
                            visibleItemEvidenceMaxAgeTicks(
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

            if (agentExecution.primitive instanceof ActionDsl.InspectKnownContainer
                    || agentExecution.primitive instanceof ActionDsl.TakeKnownContainerStack
                    || agentExecution.primitive instanceof ActionDsl.StoreKnownContainerStack
                    || agentExecution.primitive instanceof ActionDsl.CraftKnownRecipe
                    || agentExecution.primitive instanceof ActionDsl.SmeltKnownRecipe
                    || agentExecution.primitive instanceof ActionDsl.OperateKnownMenu) {
                tickAgentContainer(minecraft, session, action);
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.BrewKnownPotionBatch) {
                tickAgentBrewing(minecraft, session, action);
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.ApplyKnownBlockPlan
                    || agentExecution.primitive instanceof ActionDsl.ClearKnownBlockPlan) {
                tickAgentConstruction(minecraft, session, action);
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.PillarUpKnown) {
                tickAgentPillarUp(minecraft, session, action);
                return;
            }

            if (agentExecution.primitive instanceof ActionDsl.ApplyKnownRedstoneSpec) {
                tickAgentRedstone(minecraft, session, action);
                return;
            }

            KnownTraversabilitySnapshot map = requireAgentMap(session);
            if (agentExecution.primitiveExecutor.active()
                    && (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition
                            || agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace
                            || isKnownBreak(agentExecution.primitive)
                            || agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod)) {
                var faceReconciliation = reconciliationSignals.bindAndSnapshot(
                        Objects.requireNonNull(minecraft.level, "level"),
                        session.worldSessionId());
                var faceSurfaceBarrier = surfaceRevisionBarrier(map, faceReconciliation);
                boolean faceEvidenceCurrent;
                if (agentExecution.primitive instanceof ActionDsl.FaceKnownPosition face) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownFacingTarget(
                            map, agentPlanningFrame(), face.target());
                } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace face) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownFacingSurface(
                            map,
                            agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    face.target(), face.face(), face.expectedBlock()));
                } else if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod cast) {
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownExactSurface(
                            map,
                            agentPlanningFrame(),
                            cast.target(),
                            cast.face(),
                            cast.expectedState(),
                            faceSurfaceBarrier.applyAsLong(cast.target()));
                } else {
                    var block = agentExecution.primitive;
                    faceEvidenceCurrent = AgentPrimitivePlanner.knownSurface(
                            map,
                            agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    breakTarget(block), breakFace(block), breakBlockId(block)),
                            faceSurfaceBarrier.applyAsLong(breakTarget(block)));
                    if (faceEvidenceCurrent && block instanceof ActionDsl.BreakKnownBlock exact) {
                        try {
                            AgentPrimitivePlanner.requireKnownBreakSurface(
                                    map, agentPlanningFrame(), exact,
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
            if (isKnownBreak(agentExecution.primitive)
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
            // The movement tick itself can enter the pickup area and let vanilla collect the
            // witnessed item before the next observation frame. Bind contact against the still
            // fresh policy-visible AABB and reconcile the post-move absolute inventory now.
            if (agentExecution.primitive instanceof ActionDsl.CollectVisibleItemBatch
                    && !reconcileCollectBatchEvidence(minecraft, session, action)) {
                return;
            }
            switch (result.status()) {
                case RUNNING -> {
                    if (shouldVerifyReplanHeartbeat(agentExecution.replanning, result)) {
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
                    if (isKnownBreak(agentExecution.primitive)) {
                        agentExecution.breakAimComplete = true;
                        agentExecution.replanning = false;
                        agentExecution.replanNotBeforeTick = 0L;
                        agentExecution.replanDeadlineTick = 0L;
                        return;
                    }
                    ActionDsl.CollectVisibleItem completedCollect = activeCollectTarget();
                    if (completedCollect != null) {
                        long visualBarrierWorldRevision = visualBarrierWorldRevision(
                                map,
                                reconciliationSignals.bindAndSnapshot(
                                        Objects.requireNonNull(minecraft.level, "level"),
                                        session.worldSessionId()));
                        var itemBounds = AgentPrimitivePlanner.visibleItemAabb(
                                map,
                                agentPlanningFrame(),
                                completedCollect,
                                visualBarrierWorldRevision,
                                session.clientTick(),
                                visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
                        if (itemBounds.isEmpty() || !playerPickupAreaIntersects(
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

    /** Returns false when the action became terminal while advancing control nodes. */
    private void tickKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            boolean actionHardDeadlineReached,
            boolean newDispatchBudgetReached) {
        KillZoneExecution operation = Objects.requireNonNull(agentExecution.killZone, "killZone");
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        long tick = session.clientTick();

        String hazard = killZoneHardHazard(minecraft, player, operation);
        if (hazard != null) {
            safetyInterruptKillZone(minecraft, session, action, operation, hazard);
            return;
        }

        AttackProfile currentProfile;
        try {
            currentProfile = requireKnownAttackProfile(player.getMainHandItem());
        } catch (RuntimeInvocationException changed) {
            safetyInterruptKillZone(
                    minecraft, session, action, operation, "attack_profile_changed");
            return;
        }
        if (!operation.scope.mainHandItem().equals(
                        BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString())
                || !operation.scope.attackProfileFingerprint().equals(currentProfile.fingerprint())
                || operation.scope.attackSideEffectProfile() != currentProfile.sideEffects()) {
            safetyInterruptKillZone(minecraft, session, action, operation, "attack_profile_changed");
            return;
        }

        if (operation.pending != null) {
            KillZoneAttackAttempt attempt = operation.pending;
            LivingEntity target = attempt.target;
            boolean armorStandHit = armorStandHitConfirmed(attempt);
            boolean confirmed = armorStandHit || target.getHealth() < attempt.healthBefore
                    || (!target.isAlive() && target.getHealth() <= 0.0F);
            if (confirmed) {
                operation.confirmedAttacks++;
                recordKillZoneAttackEffect(
                        session, action, attempt, AgentActionStore.Verification.CONFIRMED,
                        target.getHealth(), armorStandHit ? "armor_stand_hit_event"
                                : target.isAlive() ? "health_decreased" : "dead");
                operation.pending = null;
            } else if (target.isRemoved() || killZonePendingMustClose(
                    tick, attempt.effectDeadlineTick, actionHardDeadlineReached)) {
                operation.unknownAttacks++;
                operation.noRetryEntityIds.add(target.getUUID());
                recordKillZoneAttackEffect(
                        session, action, attempt, AgentActionStore.Verification.UNKNOWN,
                        target.getHealth(), target.isRemoved()
                                ? "despawned_or_unloaded"
                                : actionHardDeadlineReached
                                        ? "action_budget_deadline" : "effect_timeout");
                operation.pending = null;
            } else {
                return;
            }
        }

        boolean durationComplete = actionHardDeadlineReached || newDispatchBudgetReached
                || tick - operation.startedAtClientTick
                >= operation.scope.maxOperationDurationTicks();
        boolean countComplete = operation.dispatchedAttacks >= operation.scope.maxAttacks();
        if (durationComplete || countComplete) {
            finishKillZone(minecraft, session, action, operation,
                    actionHardDeadlineReached ? "action_budget_reached"
                            : countComplete ? "attack_limit_reached"
                            : newDispatchBudgetReached ? "dispatch_budget_reached"
                            : "duration_reached");
            return;
        }
        if (operation.lastDispatchTick != Long.MIN_VALUE
                && tick - operation.lastDispatchTick < operation.scope.minimumIntervalTicks()) {
            return;
        }
        if (player.getAttackStrengthScale(0.0F) < 0.99F) {
            return;
        }

        KillZoneTarget target = currentKillZoneTarget(minecraft, session, operation);
        if (target == null) return;
        if (!killZoneStructureFingerprint(
                        level,
                        operation.scope.playerStationBounds(),
                        operation.scope.targetKillZoneBounds())
                .equals(operation.scope.structureFingerprint())) {
            safetyInterruptKillZone(minecraft, session, action, operation, "structure_changed");
            return;
        }
        if (!killZoneCollateralSafe(level, player, target.entity(), operation.scope)) {
            safetyInterruptKillZone(minecraft, session, action, operation, "collateral_not_proved");
            return;
        }

        // Reserve before semantic dispatch. Unknown outcomes and exceptions never return this slot.
        operation.dispatchedAttacks++;
        operation.lastDispatchTick = tick;
        float healthBefore = target.entity().getHealth();
        long armorStandLastHitBefore = armorStandLastHit(target.entity());
        try {
            minecraft.gameMode.attack(player, target.entity());
            player.swing(InteractionHand.MAIN_HAND);
            agentActions.recordInteraction(action.actionId());
        } catch (RuntimeException | LinkageError dispatchFailure) {
            operation.unknownAttacks++;
            operation.noRetryEntityIds.add(target.entity().getUUID());
            var synthetic = new KillZoneAttackAttempt(
                    target.entity(), target.entityRef(), healthBefore,
                    armorStandLastHitBefore, tick);
            recordKillZoneAttackEffect(
                    session, action, synthetic, AgentActionStore.Verification.UNKNOWN,
                    target.entity().getHealth(), "dispatch_exception");
            throw dispatchFailure;
        }
        operation.pending = new KillZoneAttackAttempt(
                target.entity(), target.entityRef(), healthBefore,
                armorStandLastHitBefore, tick);
    }

    private String killZoneHardHazard(
            Minecraft minecraft, Player player, KillZoneExecution operation) {
        float health = player.getHealth();
        float effectiveHealth = effectiveHealth(player);
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) return "player_mode_or_life";
        if (effectiveHealth < operation.lastEffectiveHealth) return "health_decreased";
        if (health < MIN_SAFE_STAY_HEALTH) return "health_floor";
        if (player.hurtTime > 0) return "active_damage";
        if (player.isOnFire()) return "on_fire";
        if (player.fallDistance > 0.0F || !player.onGround()) return "fall_or_support";
        if (player.getAirSupply() < player.getMaxAirSupply()) return "air_loss";
        if (player.isPassenger() || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying) return "unsupported_locomotion";
        if (minecraft.gui.screen() != null) return "screen_open";
        AABB box = player.getBoundingBox();
        if (!operation.scope.playerStationBounds().contains(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) return "station_departed";
        if (!minecraft.level.noCollision(player, box.deflate(1.0e-5D))) return "player_collision";
        if (player.getDeltaMovement().lengthSqr() > 0.01D) return "unexpected_motion";
        AABB safety = box.inflate(8.0D);
        if (!minecraft.level.getEntities(player, safety,
                entity -> entity instanceof Projectile && entity.isAlive()).isEmpty()) {
            return "projectile_present";
        }
        if (!minecraft.level.getEntities(player, box.inflate(0.125D),
                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty()) {
            return "living_contact";
        }
        for (Entity entity : minecraft.level.getEntities(player, safety,
                entity -> entity.isAlive() && (entity instanceof Enemy
                        || entity instanceof Mob mob && mob.getTarget() == player))) {
            if (!(entity instanceof LivingEntity living)
                    || !operation.scope.entityTypeAllowlist().contains(entityType(entity))
                    || !wholeBoxInside(operation.scope.targetKillZoneBounds(), living.getBoundingBox())) {
                return "hostile_outside_policy";
            }
            if (living.hasLineOfSight(player)) return "hostile_has_player_los";
        }
        return null;
    }

    private KillZoneTarget currentKillZoneTarget(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            KillZoneExecution operation) {
        if (!(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || target instanceof Player
                || !target.isAlive()
                || operation.noRetryEntityIds.contains(target.getUUID())
                || !operation.scope.entityTypeAllowlist().contains(entityType(target))
                || !wholeBoxInside(operation.scope.targetKillZoneBounds(), target.getBoundingBox())
                || target.getBoundingBox().getYsize() <= 1.0D
                || !clearKillZoneCrosshairRay(minecraft, hit)
                || target instanceof Mob mob
                        && (mob instanceof Enemy || mob.getTarget() == minecraft.player)
                        && target.hasLineOfSight(minecraft.player)
                || !minecraft.player.isWithinEntityInteractionRange(target, 0.0D)) {
            return null;
        }
        Optional<ObservationFrame> frame = agentObservationFrames.latestFrame();
        if (frame.isEmpty() || frame.orElseThrow().visibleEntitiesTruncated()
                || !session.dimension().equals(frame.orElseThrow().dimension().value())
                || session.clientTick() < frame.orElseThrow().frameCompletedTick()
                || session.clientTick() - frame.orElseThrow().frameCompletedTick() > 2L) {
            return null;
        }
        for (ObservationRecord record : frame.orElseThrow().records()) {
            if (!(record instanceof ObservationRecord.VisibleEntity visible)
                    || visible.entityRef() == null
                    || visible.observedTick() + 2L < session.clientTick()
                    || !visible.entityType().value().equals(entityType(target))) continue;
            Optional<Entity> resolved = observations.resolveLoadedEntityRefIdentity(
                    minecraft, session.clientTick(), session.worldSessionId(), session.dimension(),
                    visible.entityRef(), minecraft.player.entityInteractionRange() + 1.0D);
            if (resolved.orElse(null) == target) {
                return new KillZoneTarget(target, visible.entityRef());
            }
        }
        return null;
    }

    private static boolean clearKillZoneCrosshairRay(
            Minecraft minecraft, EntityHitResult hit) {
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 hitLocation = hit.getLocation();
        if (!hit.getEntity().getBoundingBox().inflate(1.0e-5D).contains(hitLocation)
                || eye.distanceToSqr(hitLocation) < 1.0e-8D) {
            return false;
        }
        HitResult obstruction = minecraft.level.clip(new ClipContext(
                eye,
                hitLocation,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                minecraft.player));
        return obstruction.getType() == HitResult.Type.MISS;
    }

    private static boolean killZoneCollateralSafe(
            net.minecraft.client.multiplayer.ClientLevel level,
            Player player,
            LivingEntity target,
            ScopedEntityAttackConsentStore.Scope scope) {
        if (scope.attackSideEffectProfile()
                == ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET) {
            return true;
        }
        if (scope.attackSideEffectProfile()
                != ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SWEEP) {
            return false;
        }
        AABB effects = target.getBoundingBox().inflate(1.0D, 0.25D, 1.0D);
        for (LivingEntity candidate : level.getEntitiesOfClass(
                LivingEntity.class, effects, Entity::isAlive)) {
            if (candidate == player
                    || candidate instanceof Player
                    || !scope.entityTypeAllowlist().contains(entityType(candidate))
                    || !wholeBoxInside(scope.targetKillZoneBounds(), candidate.getBoundingBox())) {
                return false;
            }
        }
        return true;
    }

    private void recordKillZoneAttackEffect(
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KillZoneAttackAttempt attempt,
            AgentActionStore.Verification verification,
            float healthAfter,
            String outcome) {
        long revision = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(Minecraft.getInstance().level, "level"),
                session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                action.actionId(),
                "entity_attack",
                "refhash:" + sha256Identity(new StringBuilder(attempt.entityRef))
                        .substring("sha256:".length()),
                Map.of("entity_type", entityType(attempt.target), "health", attempt.healthBefore),
                Map.of("health", healthAfter, "outcome", outcome),
                verification,
                session.clientTick(),
                revision);
    }

    private void finishKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KillZoneExecution operation,
            String reason) {
        if (operation.confirmedAttacks < 1) {
            failAgentAction(
                    AgentActionStore.FailureCode.CONDITION_TIMEOUT,
                    true,
                    "kill_zone_no_confirmed_attack");
            return;
        }
        long revision = reconciliationSignals.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level, "level"),
                session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                action.actionId(), "kill_zone_summary", "operation",
                Map.of("max_attacks", operation.scope.maxAttacks()),
                Map.of(
                        "dispatched_attacks", operation.dispatchedAttacks,
                        "confirmed_attacks", operation.confirmedAttacks,
                        "unknown_attacks", operation.unknownAttacks,
                        "completion_reason", reason),
                AgentActionStore.Verification.CONFIRMED,
                session.clientTick(), revision);
        agentActions.completeNode(action.actionId());
        agentExecution.primitive = null;
        advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
    }

    private void safetyInterruptKillZone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            KillZoneExecution operation,
            String reason) {
        AgentInputState.global().releaseAttack();
        closePendingKillZoneEffectForTerminal(reason);
        float current = minecraft.player == null ? 0.0F : minecraft.player.getHealth();
        float currentAbsorption = minecraft.player == null
                ? 0.0F : minecraft.player.getAbsorptionAmount();
        float currentEffective = current + currentAbsorption;
        long revision = minecraft.level == null ? 0L : reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId()).worldRevision();
        agentActions.recordEffect(
                action.actionId(), "safety_interrupted", "player",
                Map.of(
                        "health_before", operation.lastHealth,
                        "absorption_before", operation.lastAbsorption,
                        "effective_health_before", operation.lastEffectiveHealth,
                        "effective_health_previous", operation.lastEffectiveHealth),
                Map.of(
                        "health_current", current,
                        "absorption_current", currentAbsorption,
                        "effective_health_current", currentEffective,
                        "health_delta", currentEffective - operation.lastEffectiveHealth,
                        "dispatched_attacks", operation.dispatchedAttacks,
                        "confirmed_attacks", operation.confirmedAttacks,
                        "unknown_attacks", operation.unknownAttacks,
                        "reason", reason),
                AgentActionStore.Verification.CONFIRMED,
                session.clientTick(), revision);
        failAgentAction(
                AgentActionStore.FailureCode.SAFETY_INTERRUPTED,
                false,
                reason);
    }

    private static boolean wholeBoxInside(
            ScopedEntityAttackConsentStore.Bounds bounds, AABB box) {
        return bounds.contains(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static String entityType(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static float effectiveHealth(Player player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static long armorStandLastHit(LivingEntity target) {
        return target instanceof ArmorStand stand ? stand.lastHit : Long.MIN_VALUE;
    }

    static boolean armorStandHitEventAdvanced(long before, long after) {
        return before != Long.MIN_VALUE && after > before;
    }

    static boolean killZonePendingMustClose(
            long currentTick, long effectDeadlineTick, boolean actionHardDeadlineReached) {
        return actionHardDeadlineReached || currentTick >= effectDeadlineTick;
    }

    private static boolean armorStandHitConfirmed(KillZoneAttackAttempt attempt) {
        return armorStandHitEventAdvanced(
                attempt.armorStandLastHitBefore, armorStandLastHit(attempt.target));
    }

    static boolean healthDecreased(
            float previousHealth,
            float previousAbsorption,
            float currentHealth,
            float currentAbsorption) {
        return currentHealth < previousHealth
                || currentAbsorption < previousAbsorption
                || currentHealth + currentAbsorption
                        < previousHealth + previousAbsorption;
    }

    private static boolean killZoneHealthDecreased(
            KillZoneExecution operation, Player player) {
        float currentHealth = player.getHealth();
        float currentAbsorption = player.getAbsorptionAmount();
        if (healthDecreased(
                operation.lastHealth,
                operation.lastAbsorption,
                currentHealth,
                currentAbsorption)) {
            return true;
        }
        operation.lastHealth = currentHealth;
        operation.lastAbsorption = currentAbsorption;
        operation.lastEffectiveHealth = currentHealth + currentAbsorption;
        return false;
    }

    private boolean advanceAgentProgram(
            Minecraft minecraft, AgentActionStore.Progress occurrenceBaseline) {
        ActionProgramCursor.Advance advance = agentExecution.cursor.next(policySnapshot(minecraft));
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
        agentExecution.fishingAttempt = null;
        agentExecution.primitivePlanDeadlineTick = Math.addExact(
                occurrenceBaseline.ticks(), primitiveReobservationTicks(advance.primitive()));
        agentExecution.mutationAims.clear();
        agentExecution.pickupInventoryBefore = advance.primitive()
                instanceof ActionDsl.CollectVisibleItem collect
                ? pickupOccurrenceBaseline(
                        -1,
                        inventoryItemCount(
                                Objects.requireNonNull(minecraft.player, "player"),
                                collect.displayedItem()))
                : -1;
        agentExecution.pickupArrivalTick = -1L;
        agentExecution.pickupCell = null;
        if (advance.primitive() instanceof ActionDsl.CollectVisibleItemBatch batch) {
            agentExecution.collectBatchEvidence = new CollectBatchEvidence(
                    batch.targets(),
                    collectBatchInventoryCounts(
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
            var map = requireAgentMap(session);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"),
                    session.worldSessionId());
            long visualBarrierWorldRevision = visualBarrierWorldRevision(map, reconciliation);
            boolean worldPlanning = requiresWorldPlanning(agentExecution.primitive);
            var analysis = worldPlanning
                    ? analyzePrimitive(
                            action.program().request().program(),
                            agentExecution.primitive,
                            map,
                            playerPose(player, session.dimension()),
                            agentPlanningFrame(),
                            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                            visualBarrierWorldRevision,
                            primitiveSurfaceRevisionBarrier(
                                    agentExecution.primitive, map, reconciliation),
                            () -> true)
                    : emptyPrimitiveAnalysis();
            ActionDslCompiler.Cost cost = (worldPlanning
                            ? analysis.worstCase(agentExecution.primitive)
                            : Optional.ofNullable(action.program().primitiveCostBounds()
                                    .get(agentExecution.primitive.id())))
                    .orElseThrow(() -> new IllegalStateException(
                            "JIT primitive analysis did not produce a cost"));
            long activeElapsedNanos = activeElapsedNanos(
                    agentExecution, System.nanoTime());
            if (!fitsRemainingBudget(
                    progress,
                    action.program().effectiveBudget(),
                    firstPrimitiveRemainingCost(progress, cost, activeElapsedNanos),
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
                            ? requireCropWaitAuthorization(
                                    session,
                                    wait,
                                    analysis,
                                    visualBarrierWorldRevision,
                                    player.position(),
                                    player.getEyePosition())
                            : null;
            if (agentExecution.retainOccurrenceBaseline) {
                agentExecution.occurrenceLimit = occurrenceCostIncludingConsumed(
                        progress, agentExecution.occurrenceBaseline, cost);
                agentExecution.retainOccurrenceBaseline = false;
            } else {
                agentExecution.occurrenceBaseline = progress;
                agentExecution.occurrenceLimit = cost;
            }
            agentExecution.mutationAims.putAll(analysis.mutationAims());
            Optional.ofNullable(analysis.mutationBatchPlans().get(agentExecution.primitive.id()))
                    .ifPresent(plan -> agentExecution.mutationBatchPlan = plan);
            agentExecution.cropWaitAuthorization = cropWaitAuthorization;
            agentExecution.primitivePlanDeadlineTick = 0L;
            if (agentExecution.primitivePlanning) {
                agentActions.setPhase(
                        action.actionId(), AgentActionStore.Phase.EXECUTING, "jit_primitive_bound");
                agentExecution.primitivePlanning = false;
            }
            return true;
        } catch (AgentPrimitivePlanner.PlanningException unavailable) {
            if (!releaseAgentInputsForHold(minecraft, "jit_replan_input_release_failed")) {
                return false;
            }
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

    static CropWaitAuthorization requireCropWaitAuthorization(
            WorldSessionTracker.Snapshot session,
            ActionDsl.WaitUntil wait,
            AgentPrimitivePlanner.Analysis analysis,
            long visualBarrierWorldRevision,
            Vec3 playerPosition,
            Vec3 observerEye) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(wait, "wait");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(observerEye, "observerEye");
        ActionDsl.Position target = ((ActionDsl.CropMatureCondition) wait.condition()).target();
        AgentPrimitivePlanner.KnownSurface visibleWheat = analysis.knownSurfaces().stream()
                .filter(surface -> surface.position().equals(target)
                        && surface.block().equals("minecraft:wheat")
                        && surface.eyeOrigin() != null)
                .findFirst()
                .orElse(null);
        double epsilonSquared = CROP_WAIT_OBSERVER_EPSILON_BLOCKS
                * CROP_WAIT_OBSERVER_EPSILON_BLOCKS;
        if (!session.worldReady()
                || !Objects.equals(session.dimension(), target.dimension())
                || visibleWheat == null
                || observerEye.distanceToSqr(visibleWheat.eyeOrigin())
                        > epsilonSquared) {
            throw new IllegalStateException(
                    "crop wait authorization requires current-origin visible wheat evidence");
        }
        return new CropWaitAuthorization(
                session.worldSessionId(), session.dimension(), target,
                visualBarrierWorldRevision, playerPosition, visibleWheat.eyeOrigin());
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
        CropWaitVisibilityState visibility = cropWaitVisibilityState(
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
        return cropWaitLiveState(true, level.getBlockState(position));
    }

    static CropWaitVisibilityState cropWaitVisibilityState(
            CropWaitAuthorization authorization,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Position requestedTarget,
            long currentVisualBarrierWorldRevision,
            Vec3 currentPlayerPosition,
            Vec3 currentObserverEye) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        Objects.requireNonNull(currentPlayerPosition, "currentPlayerPosition");
        Objects.requireNonNull(currentObserverEye, "currentObserverEye");
        if (authorization == null || !authorization.matches(session, requestedTarget)) {
            return CropWaitVisibilityState.WORLD_CHANGED;
        }
        double epsilonSquared = CROP_WAIT_OBSERVER_EPSILON_BLOCKS
                * CROP_WAIT_OBSERVER_EPSILON_BLOCKS;
        if (currentVisualBarrierWorldRevision != authorization.visualBarrierWorldRevision()
                || currentPlayerPosition.distanceToSqr(authorization.playerPosition())
                        > epsilonSquared
                || currentObserverEye.distanceToSqr(authorization.observerEye())
                        > epsilonSquared) {
            return CropWaitVisibilityState.VISIBILITY_INVALIDATED;
        }
        return CropWaitVisibilityState.CURRENT;
    }

    static CropWaitLiveState cropWaitLiveState(boolean loaded, BlockState state) {
        if (!loaded) return CropWaitLiveState.UNLOADED;
        Objects.requireNonNull(state, "state");
        if (!state.is(Blocks.WHEAT)) return CropWaitLiveState.TARGET_CHANGED;
        return state.getValue(BlockStateProperties.AGE_7) == 7
                ? CropWaitLiveState.MATURE : CropWaitLiveState.PENDING;
    }

    private boolean soundClueMatched(
            Minecraft minecraft, ActionDsl.SoundClueCondition condition, long currentTick) {
        var player = minecraft.player;
        FishingHook hook = player == null ? null : player.fishing;
        if (player == null || !ownedFishingHook(player, hook, null)
                || !pointInside(condition.bounds(), sessions.snapshot().dimension(),
                        hook.getX(), hook.getY(), hook.getZ())) {
            return false;
        }
        List<ObservationRecord.SoundClue> nearby = soundClues.snapshot(currentTick).clues()
                .stream()
                .filter(clue -> {
                    double dx = clue.position().x() - hook.getX();
                    double dy = clue.position().y() - hook.getY();
                    double dz = clue.position().z() - hook.getZ();
                    return dx * dx + dy * dy + dz * dz <= 4.0;
                })
                .toList();
        return soundClueMatches(condition, currentTick, nearby);
    }

    static boolean soundClueMatches(
            ActionDsl.SoundClueCondition condition,
            long currentTick,
            List<ObservationRecord.SoundClue> clues) {
        if (condition.sinceTick() > currentTick) return false;
        return clues.stream().anyMatch(clue ->
                clue.soundEvent().value().equals(condition.soundEvent())
                        && clue.lastObservedTick() >= condition.sinceTick()
                        && currentTick >= clue.lastObservedTick()
                        && currentTick - clue.lastObservedTick() <= SoundClueStore.TTL_TICKS
                        && pointInside(condition.bounds(), clue.position().dimension().value(),
                                clue.position().x(), clue.position().y(), clue.position().z()));
    }

    static boolean pointInside(
            ActionDsl.WorldBounds bounds, String dimension, double x, double y, double z) {
        Objects.requireNonNull(bounds, "bounds");
        return bounds.dimension().equals(dimension)
                && x >= bounds.min().x() && x <= bounds.max().x()
                && y >= bounds.min().y() && y <= bounds.max().y()
                && z >= bounds.min().z() && z <= bounds.max().z();
    }

    private static boolean isAgentWait(ActionDsl.Node primitive) {
        return primitive instanceof ActionDsl.WaitTicks || primitive instanceof ActionDsl.WaitUntil;
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
            long visualBarrierWorldRevision = visualBarrierWorldRevision(map, reconciliation);
            var surfaceRevisionBarrier = surfaceRevisionBarrier(map, reconciliation);
            if (agentExecution.primitive instanceof ActionDsl.NavigateToKnown navigate) {
                RoutePlan route = AgentPrimitivePlanner.requireRoute(
                        map,
                        agentPathfinder,
                        playerCell(player, map.dimension()),
                        navigate.target());
                var pose = playerPose(player, map.dimension());
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.navigationReplanCost(route, pose)
                        : AgentPrimitivePlanner.navigationCost(route, pose);
                if (agentExecution.replanning) {
                    String evidence = replannedRouteBudgetFailure(
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
                } else if (!fitsRemainingBudget(
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
                Optional<ObservationFrame> approachFrame = agentPlanningFrame();
                long approachSurfaceBarrier =
                        surfaceRevisionBarrier.applyAsLong(approach.target());
                var pose = playerPose(player, map.dimension());
                AgentPrimitivePlanner.ApproachPlan plan =
                        requireRuntimeApproachPlan(
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
                    String evidence = replannedRouteBudgetFailure(
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
                } else if (!fitsRemainingBudget(
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
                var pose = playerPose(player, map.dimension());
                AgentPrimitivePlanner.ApproachPlan plan =
                        requireRuntimeKnownPlacementApproachPlan(
                                map,
                                agentPathfinder,
                                pose,
                                approach,
                                agentPlanningFrame(),
                                surfaceRevisionBarrier,
                                deliveredAgentEvidence::resolvePlacementState);
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.navigationReplanCost(plan.route(), pose)
                        : AgentPrimitivePlanner.navigationCost(plan.route(), pose);
                if (agentExecution.replanning) {
                    String evidence = replannedRouteBudgetFailure(
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
                } else if (!fitsRemainingBudget(
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
                        agentPlanningFrame(),
                        face.target());
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
            } else if (agentExecution.primitive instanceof ActionDsl.FaceKnownBlockFace face) {
                var target = AgentPrimitivePlanner.requireKnownBlockFaceTarget(
                        map,
                        agentPlanningFrame(),
                        face);
                cost = AgentPrimitivePlanner.faceCost(
                        playerPose(player, map.dimension()),
                        face,
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                if (!fitsRemainingBudget(
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
                        agentPlanningFrame(),
                        block,
                        surfaceRevisionBarrier.applyAsLong(block.target()));
                cost = AgentPrimitivePlanner.breakCost(
                        playerPose(player, map.dimension()),
                        block,
                        breakAim.point(),
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
                if (toolSlot < 0 || !inventoryCanReceiveKnownBreakDrops(
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
                                agentPlanningFrame(),
                                block,
                                surfaceRevisionBarrier.applyAsLong(block.target()));
                cost = AgentPrimitivePlanner.breakCost(
                        playerPose(player, map.dimension()),
                        block,
                        breakAim.point(),
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
                            "break_known_block");
                    return false;
                }
                int remainingBreaks = Math.toIntExact(Math.max(
                        1L,
                        action.program().worstCaseCost().blocksBroken()
                                - progressBeforeTick.blocksBroken()));
                int toolSlot = findDurableHotbarTool(
                        player, block.toolItem(), remainingBreaks);
                if (toolSlot < 0 || !inventoryCanReceiveKnownBreakDrops(
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
                if (!exactFishingRodHeld(player, cast.hand(), cast.rodItem())
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
                        playerCell(player, map.dimension()),
                        agentPlanningFrame(),
                        collect,
                        visualBarrierWorldRevision,
                        currentTick,
                        visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
                var pose = playerPose(player, map.dimension());
                cost = agentExecution.replanning
                        ? AgentPrimitivePlanner.pickupReplanCost(pickup.route(), pose)
                        : AgentPrimitivePlanner.pickupCost(pickup.route(), pose);
                if (agentExecution.replanning) {
                    String evidence = replannedRouteBudgetFailure(
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
                } else if (!fitsRemainingBudget(
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
            if (!replanDeadlineReached(actionTick, agentExecution.replanDeadlineTick)) {
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
                && pickupInventoryIncreased(
                agentExecution.pickupInventoryBefore,
                inventoryItemCount(player, collect.displayedItem()))) {
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
        int inventoryAfter = inventoryItemCount(
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
        KnownTraversabilitySnapshot map = requireAgentMap(session);
        long visualBarrierWorldRevision = visualBarrierWorldRevision(
                map,
                reconciliationSignals.bindAndSnapshot(
                        Objects.requireNonNull(minecraft.level, "level"),
                        session.worldSessionId()));
        List<Optional<dev.aod.mcmcp.agent.observation.ObservationValues.Aabb>> bounds =
                AgentPrimitivePlanner.visibleBatchItemAabbs(
                        map,
                        agentPlanningFrame(),
                        batch,
                        visualBarrierWorldRevision,
                        session.clientTick(),
                        visibleItemEvidenceMaxAgeTicks(McmcpClientConfig.raysPerTick()));
        boolean currentTargetContact = false;
        for (int index = agentExecution.collectBatchIndex;
                index < batch.targets().size(); index++) {
            if (!evidence.credited(index)
                    && bounds.get(index).filter(aabb -> playerPickupAreaIntersects(
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
                    collectBatchInventoryCounts(player, batch),
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

    private static int inventoryItemCount(
            net.minecraft.client.player.LocalPlayer player, String item) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static Map<String, Integer> collectBatchInventoryCounts(
            net.minecraft.client.player.LocalPlayer player,
            ActionDsl.CollectVisibleItemBatch batch) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(batch, "batch");
        var counts = new LinkedHashMap<String, Integer>();
        for (ActionDsl.CollectTarget target : batch.targets()) {
            counts.computeIfAbsent(
                    target.displayedItem(), item -> inventoryItemCount(player, item));
        }
        return counts;
    }

    static boolean pickupInventoryIncreased(int before, int current) {
        if (before < 0 || current < 0) {
            throw new IllegalArgumentException("pickup inventory counts must be non-negative");
        }
        return current > before;
    }

    private void tickAgentFishing(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var gameMode = Objects.requireNonNull(minecraft.gameMode, "gameMode");
        if (agentExecution.fishingAttempt == null) {
            if (agentExecution.primitive instanceof ActionDsl.CastKnownFishingRod cast) {
                if (!exactFishingRodHeld(player, cast.hand(), cast.rodItem())
                        || player.fishing != null) {
                    failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                            "fishing_cast_precondition_changed");
                    return;
                }
                gameMode.useItem(player, fishingHand(cast.hand()));
                agentActions.recordInteraction(action.actionId());
                agentExecution.fishingAttempt = FishingAttempt.cast(
                        cast.hand(), cast.rodItem(), session.clientTick());
                return;
            }
            var reel = (ActionDsl.ReelKnownFishingSession) agentExecution.primitive;
            FishingSessionRefs.Session granted = fishingSessionRefs.consume(
                            reel.fishingSessionRef(), session.worldSessionId(),
                            session.dimension(), session.clientTick())
                    .orElse(null);
            FishingHook hook = player.fishing;
            if (granted == null
                    || !granted.hand().equals(reel.hand())
                    || !granted.rodItem().equals(reel.rodItem())
                    || !exactFishingRodHeld(player, reel.hand(), reel.rodItem())
                    || !ownedFishingHook(player, hook, granted.bobberId())) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                        "fishing_session_unavailable");
                return;
            }
            ItemStack rod = player.getItemInHand(fishingHand(reel.hand()));
            gameMode.useItem(player, fishingHand(reel.hand()));
            agentActions.recordInteraction(action.actionId());
            agentExecution.fishingAttempt = FishingAttempt.reel(
                    reel.hand(), reel.rodItem(), granted.bobberId(),
                    rod.getDamageValue(), inventoryCounts(player), session.clientTick());
            return;
        }

        FishingAttempt attempt = agentExecution.fishingAttempt;
        if (attempt.mode == FishingMode.CAST) {
            FishingHook hook = player.fishing;
            if (ownedFishingHook(player, hook, null)) {
                String reference = fishingSessionRefs.issue(
                        session.worldSessionId(), session.dimension(), hook.getUUID(),
                        attempt.hand, attempt.rodItem, session.clientTick());
                agentActions.recordEffect(
                        action.actionId(), "fishing_cast", "minecraft:fishing_bobber",
                        Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                                "bobber_present", false),
                        Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                                "bobber_present", true, "fishing_session_ref", reference),
                        AgentActionStore.Verification.CONFIRMED,
                        session.clientTick(), agentExecution.latestWorldRevision);
                finishFishingPrimitive(minecraft, action);
                return;
            }
        } else {
            FishingHook hook = player.fishing;
            if (hook == null || hook.isRemoved()) {
                int damageAfter = fishingRodDamage(player, attempt.hand, attempt.rodItem);
                Map<String, Integer> inventoryAfter = inventoryCounts(player);
                agentActions.recordEffect(
                        action.actionId(), "fishing_reel", "minecraft:fishing_bobber",
                        Map.of("hand", attempt.hand, "rod_damage", attempt.rodDamageBefore,
                                "inventory_count", totalInventoryCount(attempt.inventoryBefore),
                                "bobber_present", true),
                        Map.of("hand", attempt.hand, "rod_damage", damageAfter,
                                "inventory_count", totalInventoryCount(inventoryAfter),
                                "bobber_present", false),
                        AgentActionStore.Verification.CONFIRMED,
                        session.clientTick(), agentExecution.latestWorldRevision);
                attempt.effectRecorded = true;
                finishFishingPrimitive(minecraft, action);
                return;
            } else if (!ownedFishingHook(player, hook, attempt.bobberId)) {
                failAgentAction(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                        "owned_bobber_changed");
                return;
            }
        }
        if (session.clientTick() >= attempt.deadlineTick) {
            failAgentAction(AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC, true,
                    "fishing_ack_timeout");
        }
    }

    private void finishFishingPrimitive(
            Minecraft minecraft, AgentActionStore.Active action) {
        agentExecution.fishingAttempt = null;
        agentExecution.fishingAimComplete = false;
        closeAgentPrimitiveExecutor();
        agentActions.completeNode(action.actionId());
        agentExecution.primitive = null;
        advanceAgentProgram(minecraft, agentActions.get(action.actionId()).progress());
    }

    private static InteractionHand fishingHand(String hand) {
        return "main_hand".equals(hand) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    private static boolean exactFishingRodHeld(
            net.minecraft.client.player.LocalPlayer player, String hand, String rodItem) {
        ItemStack stack = player.getItemInHand(fishingHand(hand));
        return !stack.isEmpty()
                && rodItem.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static int fishingRodDamage(
            net.minecraft.client.player.LocalPlayer player, String hand, String rodItem) {
        ItemStack stack = player.getItemInHand(fishingHand(hand));
        return !stack.isEmpty()
                && rodItem.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                ? stack.getDamageValue() : -1;
    }

    private static boolean ownedFishingHook(
            net.minecraft.client.player.LocalPlayer player, FishingHook hook, UUID expectedId) {
        return hook != null && !hook.isRemoved() && hook.getOwner() == player
                && (expectedId == null || expectedId.equals(hook.getUUID()));
    }

    private static Map<String, Integer> inventoryCounts(
            net.minecraft.client.player.LocalPlayer player) {
        var counts = new LinkedHashMap<String, Integer>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    private static int totalInventoryCount(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    static int pickupOccurrenceBaseline(int existing, int current) {
        if (existing < -1 || current < 0) {
            throw new IllegalArgumentException("pickup inventory counts are invalid");
        }
        return existing < 0 ? current : existing;
    }

    static int visibleItemEvidenceMaxAgeTicks(int raysPerTick) {
        if (raysPerTick < OmnidirectionalObserver.MIN_RAYS_PER_TICK
                || raysPerTick > OmnidirectionalObserver.MAX_RAYS_PER_TICK) {
            throw new IllegalArgumentException("raysPerTick is outside the observer policy");
        }
        return Math.ceilDiv(OmnidirectionalObserver.DIRECTION_COUNT, raysPerTick);
    }

    static boolean playerPickupAreaIntersects(
            AABB playerBounds, dev.aod.mcmcp.agent.observation.ObservationValues.Aabb itemBounds) {
        Objects.requireNonNull(playerBounds, "playerBounds");
        Objects.requireNonNull(itemBounds, "itemBounds");
        AABB pickupArea = playerBounds.inflate(1.0D, 0.5D, 1.0D);
        return pickupArea.intersects(new AABB(
                itemBounds.minX(), itemBounds.minY(), itemBounds.minZ(),
                itemBounds.maxX(), itemBounds.maxY(), itemBounds.maxZ()));
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
            long surfaceBarrierWorldRevision = surfaceRevisionBarrier(map, reconciliation)
                    .applyAsLong(breakTarget(block));
            if (!breakTargetStateMatches(minecraft, block)) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "break_target_changed");
                return;
            }
            if (!AgentPrimitivePlanner.knownSurface(
                            map,
                            agentPlanningFrame(),
                            new AgentPrimitivePlanner.KnownSurface(
                                    breakTarget(block), breakFace(block), breakBlockId(block)),
                            surfaceBarrierWorldRevision)
                    || !breakSourceControlled(minecraft, block)) {
                requestAgentReplan(actionTick, "break_target_reobservation");
                return;
            }
            try {
                var target = new BlockTarget(
                        breakTarget(block).dimension(),
                        breakTarget(block).x(),
                        breakTarget(block).y(),
                        breakTarget(block).z());
                var expected = stationaryBreakPort.captureExpectedSource(
                        target, Set.of(breakBlockId(block)));
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
                                inventoryItemCount(player, breakExpectedDrop(block)), 1));
                var request = new StationaryBreakRequest(
                        target,
                        expected,
                        new StationaryBreakGoal(
                                breakExpectedDrop(block), minimumInventoryCount),
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
            recordBreakEffects(
                    action.actionId(), breakTarget(block),
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

    private void tickAgentCobblestoneGenerator(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.OperateKnownCobblestoneGenerator operation) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        ActionDsl.BreakKnownBlock block = cobblestoneGeneratorBreak(operation);
        if (agentExecution.cobblestoneGeneratorAttempt == null) {
            int currentCount = inventoryItemCount(player, operation.expectedDrop());
            if (currentCount < operation.minimumInventoryCount()
                    && operation.minimumInventoryCount() - currentCount > operation.maxBreaks()) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        "cobblestone_goal_exceeds_max_breaks");
                return;
            }
            int toolSlot = findDurableHotbarTool(
                    player, operation.toolItem(), operation.maxBreaks());
            if (toolSlot < 0 || !inventoryCanReceiveKnownBreakDrops(player, action.program())) {
                failAgentAction(
                        AgentActionStore.FailureCode.WORLD_CHANGED,
                        true,
                        toolSlot < 0
                                ? "required_iron_pickaxe_unavailable"
                                : "inventory_full");
                return;
            }
            if (!breakTargetStateMatches(minecraft, block)
                    || !breakSourceControlled(minecraft, block)) {
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
        // exact target, state, face, reach, and tool are all rechecked before another lease.
        if (breakTargetStateMatches(minecraft, block)
                && !breakSourceControlled(minecraft, block)) {
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
        if (isMutationBatch(mutation)) {
            if (!bindMutationBatchTarget(minecraft, session, action, actionTick)) {
                return;
            }
            mutation = agentExecution.mutationBatchTarget;
        }
        if (agentExecution.blockMutationAttempt == null) {
            SemanticActionRequest request = blockMutationRequest(
                    mutation,
                    isMutationBatch(agentExecution.primitive)
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
                if (isMutationBatch(agentExecution.primitive)
                        && mutationBatchDisposition(
                                agentExecution.mutationBatchIndex,
                                agentExecution.mutationBatchPlan.steps().size(),
                                false) != BatchTargetDisposition.STOP) {
                    throw new IllegalStateException("Failed batch target must stop dispatch");
                }
                if (retryableMutationAimFailure(result.evidence())) {
                    if (!mutationAimRetriesAllowed(agentExecution.primitive)) {
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
                if (isMutationBatch(agentExecution.primitive)) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), batchTargetTrace(mutation));
                    BatchTargetDisposition disposition = mutationBatchDisposition(
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

    private void tickAgentContainer(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.containerAttempt == null) {
            PhaseFiveRequest request = containerRequest(
                    minecraft,
                    session,
                    agentExecution.primitive,
                    agentExecution.mutationAims.get(agentExecution.primitive.id()));
            boolean smelting = agentExecution.primitive instanceof ActionDsl.SmeltKnownRecipe;
            boolean knownMenu = agentExecution.primitive instanceof ActionDsl.OperateKnownMenu;
            long deadline = Math.addExact(
                    session.clientTick(),
                    smelting
                            ? ActionDslCompiler.knownSmeltingTicks(
                                    ((ActionDsl.SmeltKnownRecipe) agentExecution.primitive)
                                            .maxSmelts())
                            : knownMenu
                                    ? ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS
                            : AgentPrimitivePlanner.CONTAINER_OPERATION_TICK_UPPER_BOUND);
            PhaseFivePort port = smelting ? knownFurnacePort
                    : knownMenu ? knownMenuPort : phaseFiveInventoryPort;
            agentExecution.containerAttempt = new KnownContainerAttempt(
                    port, request, session.clientTick(), deadline);
        }
        KnownContainerAttempt.TickResult result =
                agentExecution.containerAttempt.tick(session.clientTick());
        recordContainerEffects(
                action.actionId(), agentExecution.primitive, result.effects());
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(action.actionId());
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence());
            case SUCCEEDED -> {
                agentExecution.containerAttempt = null;
                if (agentExecution.primitive instanceof ActionDsl.InspectKnownContainer) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), containerItemsTrace(result.items()));
                } else if (agentExecution.primitive instanceof ActionDsl.TakeKnownContainerStack) {
                    var take = (ActionDsl.TakeKnownContainerStack) agentExecution.primitive;
                    agentActions.recordNodeEvidence(
                            action.actionId(), "container_transfer=" + take.item());
                } else if (agentExecution.primitive instanceof ActionDsl.StoreKnownContainerStack) {
                    var store = (ActionDsl.StoreKnownContainerStack) agentExecution.primitive;
                    agentActions.recordNodeEvidence(
                            action.actionId(), "container_store=" + store.item());
                } else if (agentExecution.primitive instanceof ActionDsl.CraftKnownRecipe craft) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), "craft_complete=" + craft.goalItem());
                } else if (agentExecution.primitive instanceof ActionDsl.OperateKnownMenu) {
                    agentActions.recordNodeEvidence(
                            action.actionId(), "menu_transfer_complete");
                } else {
                    var smelt = (ActionDsl.SmeltKnownRecipe) agentExecution.primitive;
                    agentActions.recordNodeEvidence(
                            action.actionId(), "smelt_complete=" + smelt.goalItem());
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

    private void tickAgentBrewing(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.brewingAttempt == null) {
            final KnownBrewingRequest request;
            try {
                request = brewingRequest(
                        (ActionDsl.BrewKnownPotionBatch) agentExecution.primitive,
                        agentExecution.mutationAims.get(agentExecution.primitive.id()),
                        agentExecution.maxCameraDegreesPerTick);
            } catch (RuntimeException rejected) {
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "brewing_request_rejected");
                return;
            }
            long deadline = Math.addExact(
                    session.clientTick(), KnownBrewingRequest.MAX_TICKS);
            agentExecution.brewingAttempt = new KnownBrewingAttempt(
                    knownBrewingPort, request, session.clientTick(), deadline);
        }
        KnownBrewingAttempt.TickResult result =
                agentExecution.brewingAttempt.tick(session.clientTick());
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(action.actionId());
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence());
            case SUCCEEDED -> {
                agentExecution.brewingAttempt = null;
                agentActions.recordNodeEvidence(
                        action.actionId(),
                        "brewing_complete=" + result.verifiedPotions());
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

    private void tickAgentConstruction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.constructionAttempt == null) {
            final KnownConstructionRequest request;
            try {
                request = agentExecution.primitive instanceof ActionDsl.ApplyKnownBlockPlan plan
                        ? constructionRequest(
                                plan, deliveredAgentEvidence::resolvePlacementState)
                        : constructionRequest(
                                (ActionDsl.ClearKnownBlockPlan) agentExecution.primitive);
            } catch (RuntimeException rejected) {
                // Registry/state diagnostics may contain submitted property names or values.
                // Keep the public trace fixed and non-reflective.
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "construction_request_rejected");
                return;
            }
            long ticks = Math.multiplyExact(
                    request.entries().size(), (long) KnownConstructionAttempt.TICKS_PER_ENTRY);
            long deadline = Math.addExact(session.clientTick(), ticks);
            agentExecution.constructionAttempt = new KnownConstructionAttempt(
                    applyBlockPlanPort, request, session.clientTick(), deadline,
                    (call, stepIndex, failure) -> McmcpMod.LOGGER.error(
                            "MCMCP known-construction adapter failed: call={}, step_index={}",
                            call, stepIndex, failure));
        }
        KnownConstructionAttempt.TickResult result =
                agentExecution.constructionAttempt.tick(session.clientTick());
        recordConstructionEffects(action.actionId(), result.effects());
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(action.actionId());
        }
        for (int count = 0; count < result.brokenDelta(); count++) {
            agentActions.recordBlockBreak(action.actionId());
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence());
            case SUCCEEDED -> {
                agentExecution.constructionAttempt = null;
                agentActions.recordNodeEvidence(
                        action.actionId(),
                        "construction_complete=" + result.completedEntries()
                                + ",server_confirmed=" + result.confirmedEntries());
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

    private void recordConstructionEffects(
            UUID actionId, List<KnownConstructionAttempt.EffectDelta> effects) {
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    effect.kind(),
                    effect.subject(),
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    agentExecution.latestWorldRevision);
        }
    }

    private void recordContainerEffects(
            UUID actionId,
            ActionDsl.Node primitive,
            List<KnownContainerAttempt.EffectDelta> effects) {
        if (effects.isEmpty()) return;
        final String kind;
        final ActionDsl.Position target;
        final String item;
        if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            kind = "container_take";
            target = take.target();
            item = take.item();
        } else if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            kind = "container_store";
            target = store.target();
            item = store.item();
        } else {
            throw new IllegalStateException(
                    "container transfer effect has no transfer primitive");
        }
        String subject = "container:" + target.dimension() + ":"
                + target.x() + "," + target.y() + "," + target.z() + "/" + item;
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    kind,
                    subject,
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    agentExecution.latestWorldRevision);
        }
    }

    private void tickAgentPillarUp(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.pillarUpAttempt == null) {
            final KnownPillarUpRequest request;
            try {
                request = pillarUpRequest(
                        (ActionDsl.PillarUpKnown) agentExecution.primitive,
                        deliveredAgentEvidence::resolvePlacementState);
            } catch (RuntimeException rejected) {
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "pillar_request_rejected");
                return;
            }
            long deadline = Math.addExact(
                    session.clientTick(), KnownPillarUpAttempt.MAX_TICKS);
            agentExecution.pillarUpAttempt = new KnownPillarUpAttempt(
                    pillarUpPort, request, session.clientTick(), deadline);
        }
        KnownPillarUpAttempt.TickResult result =
                agentExecution.pillarUpAttempt.tick(session.clientTick());
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(action.actionId());
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence());
            case SUCCEEDED -> {
                agentExecution.pillarUpAttempt = null;
                agentActions.recordNodeEvidence(action.actionId(), "pillar_up_complete=1");
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

    static KnownPillarUpRequest pillarUpRequest(ActionDsl.PillarUpKnown pillar) {
        return pillarUpRequest(pillar, PlacementStateResolver.none());
    }

    static KnownPillarUpRequest pillarUpRequest(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(pillar, "pillar");
        Objects.requireNonNull(placementStates, "placementStates");
        PillarSource source = resolvePillarSource(pillar, placementStates)
                .orElseThrow(() -> new IllegalArgumentException(
                        "pillar placement_state_ref is unknown"));
        return new KnownPillarUpRequest(
                new BlockTarget(
                        pillar.support().dimension(),
                        pillar.support().x(),
                        pillar.support().y(),
                        pillar.support().z()),
                new BlockStateFingerprint(
                        pillar.expectedSupport().block(),
                        pillar.expectedSupport().properties()),
                new BlockStateFingerprint(
                        source.state().block(),
                        source.state().properties()),
                source.item());
    }

    private static Optional<PillarSource> resolvePillarSource(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        if (pillar.placementStateRef().isPresent()) {
            return placementStates.resolve(pillar.placementStateRef().orElseThrow())
                    .map(remembered -> new PillarSource(
                            new ActionDsl.BlockStateSpec(
                                    remembered.state().block().value(),
                                    remembered.state().properties()),
                            remembered.placementItem().value()));
        }
        return Optional.of(new PillarSource(
                pillar.sourceState().orElseThrow(), pillar.item().orElseThrow()));
    }

    private record PillarSource(ActionDsl.BlockStateSpec state, String item) {
        private PillarSource {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(item, "item");
        }
    }

    private void tickAgentRedstone(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action) {
        if (agentExecution.redstoneAttempt == null) {
            var player = Objects.requireNonNull(minecraft.player, "player");
            var redstone = (ActionDsl.ApplyKnownRedstoneSpec) agentExecution.primitive;
            int outputCount = (int) redstone.components().stream()
                    .filter(component -> component.role() == RedstoneSpec.Role.OUTPUT)
                    .count();
            int wireCount = (int) redstone.components().stream()
                    .filter(component -> component.role() == RedstoneSpec.Role.WIRE)
                    .count();
            if (inventoryItemCount(player, "minecraft:redstone_lamp") < outputCount
                    || inventoryItemCount(player, "minecraft:lever") < 1
                    || inventoryItemCount(player, "minecraft:redstone") < wireCount) {
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "redstone_items_unavailable");
                return;
            }
            final RedstoneIdentityRequest request;
            try {
                var lampAims = new ArrayList<AgentPrimitivePlanner.MutationAim>();
                lampAims.add(Objects.requireNonNull(
                        agentExecution.mutationAims.get(redstone.id() + "/lamp"),
                        "lamp aim"));
                if (outputCount == 2) {
                    lampAims.add(Objects.requireNonNull(
                            agentExecution.mutationAims.get(redstone.id() + "/lamp_2"),
                            "second lamp aim"));
                }
                request = redstoneIdentityRequest(
                        redstone,
                        session.worldSessionId(),
                        lampAims,
                        Objects.requireNonNull(
                                agentExecution.mutationAims.get(redstone.id() + "/lever"),
                                "lever aim"),
                        wireCount == 1
                                ? Optional.of(Objects.requireNonNull(
                                        agentExecution.mutationAims.get(redstone.id() + "/wire"),
                                        "wire aim"))
                                : Optional.empty());
            } catch (RuntimeException rejected) {
                failAgentAction(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "redstone_request_rejected");
                return;
            }
            long deadline = Math.addExact(
                    session.clientTick(),
                    ActionDslCompiler.intrinsicKnownRedstoneCost(
                            redstone.timing().settleTicks(), outputCount, wireCount).ticks());
            List<BlockPosition> lamps = request.lampTargets().stream()
                    .map(target -> new BlockPosition(
                            target.dimension(), target.x(), target.y(), target.z()))
                    .toList();
            BlockTarget lever = request.leverTarget();
            Optional<BlockPosition> wire = request.wireTarget().map(target -> new BlockPosition(
                    target.dimension(), target.x(), target.y(), target.z()));
            List<BlockPosition> halo = request.safetyEnvelope().keySet().stream()
                    .map(target -> new BlockPosition(
                            target.dimension(), target.x(), target.y(), target.z()))
                    .toList();
            agentExecution.redstoneAttempt = new KnownRedstoneIdentityAttempt(
                    semanticActionPort,
                    request,
                    tick -> observations.observeBlocks(
                            minecraft,
                            tick,
                            lamps,
                            MinecraftObservationService.BlockSource.LIVE),
                    tick -> observations.observeBlock(
                            minecraft,
                            tick,
                            new BlockPosition(
                                    lever.dimension(), lever.x(), lever.y(), lever.z()),
                            MinecraftObservationService.BlockSource.LIVE),
                    tick -> wire.map(position -> observations.observeBlock(
                                    minecraft,
                                    tick,
                                    position,
                                    MinecraftObservationService.BlockSource.LIVE))
                            .orElse(null),
                    tick -> observations.observeBlocks(
                            minecraft,
                            tick,
                            halo,
                            MinecraftObservationService.BlockSource.LIVE),
                    session.clientTick(),
                    deadline);
        }
        KnownRedstoneIdentityAttempt.TickResult result =
                agentExecution.redstoneAttempt.tick(session.clientTick());
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(action.actionId());
        }
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(action.actionId());
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> failAgentAction(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence());
            case SUCCEEDED -> {
                agentExecution.redstoneAttempt = null;
                agentActions.recordNodeEvidence(
                        action.actionId(),
                        "redstone_identity_observations=" + result.outputObservations());
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
            var map = requireAgentMap(session);
            var reconciliation = reconciliationSignals.bindAndSnapshot(
                    Objects.requireNonNull(minecraft.level, "level"), session.worldSessionId());
            AgentPrimitivePlanner.Pose currentPose = playerPose(player, session.dimension());
            var analysis = analyzePrimitive(
                    action.program().request().program(),
                    step.primitive(),
                    map,
                    currentPose,
                    agentPlanningFrame(),
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F,
                    visualBarrierWorldRevision(map, reconciliation),
                    surfaceRevisionBarrier(map, reconciliation),
                    () -> true);
            ActionDslCompiler.Cost cost = analysis.worstCase(step.primitive()).orElseThrow();
            AgentPrimitivePlanner.MutationAim freshAim = analysis.mutationAims()
                    .get(step.primitive().id());
            if (freshAim == null) {
                throw new IllegalStateException("Fresh batch target aim is unavailable");
            }
            ActionDslCompiler.Cost requiredRemainder = mutationBatchRequiredRemainder(
                    plan,
                    agentExecution.mutationBatchIndex,
                    currentPose,
                    freshAim,
                    cost,
                    McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
            AgentActionStore.Progress progress = agentActions.get(action.actionId()).progress();
            if (!fitsMutationBatchRemainder(
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
            if (replanDeadlineReached(
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
        if (!isMutationBatch(agentExecution.primitive)
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

    private static boolean isMutationBatch(ActionDsl.Node node) {
        return node instanceof ActionDsl.TillKnownBatch
                || node instanceof ActionDsl.PlantKnownWheatBatch
                || node instanceof ActionDsl.HarvestKnownWheatBatch;
    }

    static boolean mutationAimRetriesAllowed(ActionDsl.Node node) {
        return !isMutationBatch(Objects.requireNonNull(node, "node"));
    }

    static BatchTargetDisposition mutationBatchDisposition(
            int completedBeforeTarget, int targetCount, boolean serverConfirmed) {
        if (targetCount < 1
                || targetCount > ActionDslValidator.MAX_MUTATION_BATCH_TARGETS
                || completedBeforeTarget < 0
                || completedBeforeTarget >= targetCount) {
            throw new IllegalArgumentException("invalid mutation batch progress");
        }
        if (!serverConfirmed) return BatchTargetDisposition.STOP;
        return completedBeforeTarget + 1 == targetCount
                ? BatchTargetDisposition.COMPLETE
                : BatchTargetDisposition.CONTINUE;
    }

    enum BatchTargetDisposition { STOP, CONTINUE, COMPLETE }

    static ActionDslCompiler.Cost mutationBatchRequiredRemainder(
            AgentPrimitivePlanner.MutationBatchPlan plan,
            int currentIndex,
            AgentPrimitivePlanner.Pose currentPose,
            AgentPrimitivePlanner.MutationAim freshCurrentAim,
            ActionDslCompiler.Cost freshCurrent,
            float cameraLimit) {
        return AgentPrimitivePlanner.recostMutationBatchRemainder(
                plan,
                currentIndex,
                currentPose,
                freshCurrentAim,
                freshCurrent,
                cameraLimit);
    }

    private static String batchTargetTrace(ActionDsl.Node mutation) {
        ActionDsl.Position target = switch (mutation) {
            case ActionDsl.TillKnownBlock till -> till.target();
            case ActionDsl.PlantKnownWheat plant -> plant.target();
            case ActionDsl.HarvestKnownWheat harvest -> harvest.target();
            default -> throw new IllegalArgumentException("node is not a batch target");
        };
        return "batch_target=" + target.x() + "," + target.y() + "," + target.z();
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ApplyKnownBlockPlan plan) {
        return constructionRequest(plan, PlacementStateResolver.none());
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ApplyKnownBlockPlan plan,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(placementStates, "placementStates");
        var transform = new BlockPlan.Transform(
                plan.transform().rotation().degrees(),
                plan.transform().mirror().wireName());
        var entries = new ArrayList<ApplyBlockPlanStep>(plan.entries().size());
        var prior = new LinkedHashMap<String, ApplyBlockPlanStep>();
        BlockTarget minimum = null;
        BlockTarget maximum = null;
        for (ActionDsl.BlockPlanEntry entry : plan.entries()) {
            ActionDsl.BlockStateSpec sourceState;
            String item;
            if (entry.placementStateRef().isPresent()) {
                PlacementStateResolver.PlacementState remembered = placementStates
                        .resolve(entry.placementStateRef().orElseThrow())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "construction placement_state_ref is unknown"));
                sourceState = new ActionDsl.BlockStateSpec(
                        remembered.state().block().value(), remembered.state().properties());
                item = remembered.placementItem().value();
            } else {
                sourceState = entry.sourceState().orElseThrow();
                item = entry.item().orElseThrow();
            }
            ActionDsl.Offset offset = plan.transform().apply(entry.offset());
            var target = new BlockTarget(
                    plan.anchor().dimension(),
                    Math.addExact(plan.anchor().x(), offset.x()),
                    Math.addExact(plan.anchor().y(), offset.y()),
                    Math.addExact(plan.anchor().z(), offset.z()));
            BlockStateView transformed = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(
                            sourceState.block(), sourceState.properties()),
                    transform,
                    "construction.entry.source_state");
            var expectedAfter = new BlockStateFingerprint(
                    transformed.block(), transformed.properties());
            ActionDsl.PlacementSupport support = entry.support();
            var supportTarget = new BlockTarget(
                    support.position().dimension(),
                    support.position().x(),
                    support.position().y(),
                    support.position().z());
            String face = support.face().name().toLowerCase(Locale.ROOT);
            PlacementSupportWitness witness;
            if (support.expectedState().isPresent()) {
                ActionDsl.BlockStateSpec state = support.expectedState().orElseThrow();
                witness = PlacementSupportWitness.visible(
                        supportTarget,
                        face,
                        new BlockStateFingerprint(state.block(), state.properties()));
            } else {
                String dependencyId = support.dependencyEntryId().orElseThrow();
                ApplyBlockPlanStep dependency = prior.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "construction dependency is not an earlier entry");
                }
                witness = PlacementSupportWitness.confirmedDependency(
                        supportTarget,
                        face,
                        dependency.expectedAfter(),
                        dependencyId);
            }
            var step = new ApplyBlockPlanStep(
                    entry.id(),
                    ApplyBlockPlanOperation.PLACE,
                    target,
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    expectedAfter,
                    Optional.of(item),
                    Optional.of(witness));
            entries.add(step);
            prior.put(entry.id(), step);
            minimum = minimum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.min(minimum.x(), target.x()),
                    Math.min(minimum.y(), target.y()),
                    Math.min(minimum.z(), target.z()));
            maximum = maximum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.max(maximum.x(), target.x()),
                    Math.max(maximum.y(), target.y()),
                    Math.max(maximum.z(), target.z()));
            if (MinecraftApplyBlockPlanPort.supportedDoorPlacement(expectedAfter)) {
                maximum = new BlockTarget(
                        target.dimension(), maximum.x(),
                        Math.max(maximum.y(), Math.addExact(target.y(), 1)), maximum.z());
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("construction plan is empty");
        }
        int maxDurationSeconds = Math.multiplyExact(entries.size(), 15);
        return new KnownConstructionRequest(
                "construction",
                entries,
                new ActionBounds(
                        plan.anchor().dimension(),
                        Objects.requireNonNull(minimum, "minimum"),
                        Objects.requireNonNull(maximum, "maximum"),
                        0,
                        maxDurationSeconds,
                        false));
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ClearKnownBlockPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var transform = new BlockPlan.Transform(
                plan.transform().rotation().degrees(),
                plan.transform().mirror().wireName());
        var entries = new ArrayList<ApplyBlockPlanStep>(plan.entries().size());
        BlockTarget minimum = null;
        BlockTarget maximum = null;
        for (ActionDsl.ClearBlockPlanEntry entry : plan.entries()) {
            ActionDsl.Offset offset = plan.transform().apply(entry.offset());
            var target = new BlockTarget(
                    plan.anchor().dimension(),
                    Math.addExact(plan.anchor().x(), offset.x()),
                    Math.addExact(plan.anchor().y(), offset.y()),
                    Math.addExact(plan.anchor().z(), offset.z()));
            BlockStateView transformed = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(
                            entry.expectedBefore().block(),
                            entry.expectedBefore().properties()),
                    transform,
                    "construction.clear.expected_before");
            entries.add(new ApplyBlockPlanStep(
                    entry.id(),
                    ApplyBlockPlanOperation.BREAK_TO_AIR,
                    target,
                    new BlockStateFingerprint(
                            transformed.block(), transformed.properties()),
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    Optional.empty(),
                    Optional.empty()));
            minimum = minimum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.min(minimum.x(), target.x()),
                    Math.min(minimum.y(), target.y()),
                    Math.min(minimum.z(), target.z()));
            maximum = maximum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.max(maximum.x(), target.x()),
                    Math.max(maximum.y(), target.y()),
                    Math.max(maximum.z(), target.z()));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("construction clear plan is empty");
        }
        return new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "construction-clear",
                1,
                1,
                entries,
                new ActionBounds(
                        plan.anchor().dimension(),
                        Objects.requireNonNull(minimum, "minimum"),
                        Objects.requireNonNull(maximum, "maximum"),
                        0,
                        Math.multiplyExact(entries.size(), 15),
                        true),
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK));
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            AgentPrimitivePlanner.MutationAim lampAim,
            AgentPrimitivePlanner.MutationAim leverAim) {
        return redstoneIdentityRequest(
                redstone, worldSessionId, List.of(lampAim), leverAim, Optional.empty());
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            List<AgentPrimitivePlanner.MutationAim> lampAims,
            AgentPrimitivePlanner.MutationAim leverAim) {
        return redstoneIdentityRequest(
                redstone, worldSessionId, lampAims, leverAim, Optional.empty());
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            List<AgentPrimitivePlanner.MutationAim> lampAims,
            AgentPrimitivePlanner.MutationAim leverAim,
            Optional<AgentPrimitivePlanner.MutationAim> wireAim) {
        Objects.requireNonNull(redstone, "redstone");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        lampAims = List.copyOf(Objects.requireNonNull(lampAims, "lampAims"));
        Objects.requireNonNull(leverAim, "leverAim");
        Objects.requireNonNull(wireAim, "wireAim");
        var spec = new RedstoneSpec(
                redstone.components(),
                redstone.truthTable(),
                redstone.footprint(),
                redstone.rotation(),
                new RedstoneSpec.ExecutionBounds(
                        true, redstone.timing().settleTicks()));
        ActionDsl.Position anchor = redstone.anchor();
        var firstLampTarget = new BlockTarget(
                anchor.dimension(), anchor.x(), anchor.y(), anchor.z());
        int x = switch (redstone.rotation()) {
            case 0 -> 1;
            case 180 -> -1;
            case 90, 270 -> 0;
            default -> throw new IllegalArgumentException("unsupported redstone rotation");
        };
        int z = switch (redstone.rotation()) {
            case 90 -> 1;
            case 270 -> -1;
            case 0, 180 -> 0;
            default -> throw new IllegalArgumentException("unsupported redstone rotation");
        };
        var leverTarget = new BlockTarget(
                anchor.dimension(), anchor.x() + (1 + spec.wireCount()) * x, anchor.y(),
                anchor.z() + (1 + spec.wireCount()) * z);
        var lampTargets = new ArrayList<BlockTarget>();
        lampTargets.add(firstLampTarget);
        if (spec.outputCount() == 2) {
            lampTargets.add(new BlockTarget(
                    anchor.dimension(), anchor.x() + 2 * x, anchor.y(), anchor.z() + 2 * z));
        }
        Optional<BlockTarget> wireTarget = spec.wireCount() == 1
                ? Optional.of(new BlockTarget(
                        anchor.dimension(), anchor.x() + x, anchor.y(), anchor.z() + z))
                : Optional.empty();
        if (wireAim.isPresent() != wireTarget.isPresent()) {
            throw new IllegalArgumentException("redstone wire aim does not match the specification");
        }
        if (lampAims.size() != lampTargets.size()) {
            throw new IllegalArgumentException("redstone lamp aims do not match the specification");
        }
        var targets = new ArrayList<>(lampTargets);
        targets.add(leverTarget);
        wireTarget.ifPresent(targets::add);
        var minimum = new BlockTarget(
                anchor.dimension(),
                targets.stream().mapToInt(BlockTarget::x).min().orElseThrow(),
                anchor.y() - 1,
                targets.stream().mapToInt(BlockTarget::z).min().orElseThrow());
        var maximum = new BlockTarget(
                anchor.dimension(),
                targets.stream().mapToInt(BlockTarget::x).max().orElseThrow(),
                anchor.y(),
                targets.stream().mapToInt(BlockTarget::z).max().orElseThrow());
        var bounds = new ActionBounds(
                anchor.dimension(), minimum, maximum, 0, 30, false);
        return new RedstoneIdentityRequest(
                spec,
                worldSessionId,
                lampTargets,
                leverTarget,
                lampAims.stream().map(McmcpRuntime::blockAimWitness).toList(),
                blockAimWitness(leverAim),
                wireAim.map(McmcpRuntime::blockAimWitness),
                bounds);
    }

    static KnownBrewingRequest brewingRequest(
            ActionDsl.BrewKnownPotionBatch brew,
            AgentPrimitivePlanner.MutationAim brewingAim,
            float maxCameraDegreesPerTick) {
        Objects.requireNonNull(brew, "brew");
        if (!StandardPotionPolicy.BREWING_STAND.equals(brew.expectedBlock())) {
            throw new IllegalArgumentException("brewing target must be a brewing stand");
        }
        var target = new BlockTarget(
                brew.target().dimension(),
                brew.target().x(),
                brew.target().y(),
                brew.target().z());
        KnownBrewingRequest base = new KnownBrewingRequest(
                target,
                brew.input(),
                brew.ingredientItem(),
                brew.fuelItem(),
                brew.expectedOutput(),
                maxCameraDegreesPerTick);
        PhaseFiveRequest operation = withInventoryAim(
                base.operation(), brew.target(), brewingAim);
        return new KnownBrewingRequest(
                base.target(), base.input(), base.ingredientItem(), base.fuelItem(),
                base.expectedOutput(), base.maxCameraDegreesPerTick(), operation);
    }

    private static PhaseFiveRequest containerRequest(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive,
            AgentPrimitivePlanner.MutationAim inventoryAim) {
        if (primitive instanceof ActionDsl.OperateKnownMenu operation) {
            var player = Objects.requireNonNull(minecraft.player, "player");
            return knownMenuRequest(operation, new BlockTarget(
                    session.dimension(),
                    Mth.floor(player.getX()),
                    Mth.floor(player.getY()),
                    Mth.floor(player.getZ())));
        }
        if (primitive instanceof ActionDsl.CraftKnownRecipe craft) {
            return withInventoryAim(craftRequest(craft), craft.target(), inventoryAim);
        }
        if (primitive instanceof ActionDsl.SmeltKnownRecipe smelt) {
            return smeltRequest(smelt, inventoryAim);
        }
        ActionDsl.Position position;
        String expectedBlock;
        String item;
        String stackPolicy;
        int minimumDestinationCount;
        if (primitive instanceof ActionDsl.InspectKnownContainer inspect) {
            position = inspect.target();
            expectedBlock = inspect.expectedBlock();
            item = "minecraft:air";
            stackPolicy = "item_id_any_components";
            minimumDestinationCount = 0;
        } else if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            position = take.target();
            expectedBlock = take.expectedBlock();
            item = take.item();
            stackPolicy = take.stackPolicy();
            minimumDestinationCount = take.minimumInventoryCount();
        } else if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            position = store.target();
            expectedBlock = store.expectedBlock();
            item = store.item();
            stackPolicy = store.stackPolicy();
            minimumDestinationCount = store.minimumContainerCount();
        } else {
            throw new IllegalArgumentException("node is not a known container operation");
        }
        if (!position.dimension().equals(session.dimension())) {
            throw new IllegalArgumentException("container target dimension changed");
        }
        BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
        var level = Objects.requireNonNull(minecraft.level, "level");
        BlockStateFingerprint state = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(
                level.getBlockState(blockPos));
        if (!expectedBlock.equals(state.blockId())) {
            throw new IllegalArgumentException("container target block changed");
        }
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var targetMap = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
        var stateMap = Map.<String, Object>of(
                "block", state.blockId(),
                "properties", state.properties());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("container", Map.of("target", targetMap, "expected_state", stateMap));
        parameters.put("direction", knownContainerTransferDirection(primitive));
        parameters.put("stack", Map.of("item", item, "stack_policy", stackPolicy));
        parameters.put("goal", Map.of(
                "minimum_destination_count", minimumDestinationCount));
        parameters.put("max_transfer_count", 64);
        parameters.put("max_stack_moves", 1);
        parameters.put("retain_view_on_release", true);
        parameters.put("max_camera_degrees_per_tick",
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0D);
        parameters.put("aim_point", inventoryAimPoint(position, inventoryAim));
        knownContainerRoutingLabel(primitive).ifPresent(label -> parameters.put(
                "routing_label", Map.of(
                        "entity_ref", label.entityRef(),
                        "item", label.item())));
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0, 20, false);
        return new PhaseFiveRequest(
                "transfer_items", parameters, bounds, minimumDestinationCount, "items");
    }

    static String knownContainerTransferDirection(ActionDsl.Node primitive) {
        Objects.requireNonNull(primitive, "primitive");
        if (primitive instanceof ActionDsl.StoreKnownContainerStack) {
            return "player_to_container";
        }
        if (primitive instanceof ActionDsl.InspectKnownContainer
                || primitive instanceof ActionDsl.TakeKnownContainerStack) {
            return "container_to_player";
        }
        throw new IllegalArgumentException("node is not a known container operation");
    }

    private static Optional<ActionDsl.RoutingLabel> knownContainerRoutingLabel(
            ActionDsl.Node primitive) {
        if (primitive instanceof ActionDsl.InspectKnownContainer inspect) {
            return inspect.routingLabel();
        }
        if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            return take.routingLabel();
        }
        if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            return store.routingLabel();
        }
        return Optional.empty();
    }

    private static PhaseFiveRequest withInventoryAim(
            PhaseFiveRequest request,
            ActionDsl.Position target,
            AgentPrimitivePlanner.MutationAim aim) {
        var parameters = new LinkedHashMap<String, Object>(request.parameters());
        parameters.put("aim_point", inventoryAimPoint(target, aim));
        return new PhaseFiveRequest(
                request.kind(), parameters, request.bounds(),
                request.expectedUnits(), request.progressUnit());
    }

    static Map<String, Object> inventoryAimPoint(
            ActionDsl.Position target,
            AgentPrimitivePlanner.MutationAim aim) {
        if (aim == null || !target.equals(aim.block())) {
            throw new IllegalArgumentException("menu aim witness is unavailable");
        }
        Vec3 point = aim.point();
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || point.x < target.x() || point.x > target.x() + 1.0D
                || point.y < target.y() || point.y > target.y() + 1.0D
                || point.z < target.z() || point.z > target.z() + 1.0D) {
            throw new IllegalArgumentException("menu aim witness is outside its target");
        }
        return Map.of(
                "dimension", target.dimension(),
                "x", point.x,
                "y", point.y,
                "z", point.z);
    }

    static PhaseFiveRequest knownMenuRequest(
            ActionDsl.OperateKnownMenu operation, BlockTarget position) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(position, "position");
        return new PhaseFiveRequest(
                MinecraftKnownMenuPort.KIND,
                Map.of("operation_ref", operation.operationRef()),
                new PhaseFiveBounds(
                        position.dimension(), position, position, 0, 30, false),
                1,
                "items");
    }

    static PhaseFiveRequest craftRequest(ActionDsl.CraftKnownRecipe craft) {
        ActionDsl.Position position = craft.target();
        var expected = new BlockStateFingerprint(
                craft.expectedState().block(), craft.expectedState().properties());
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var targetMap = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
        var stateMap = Map.<String, Object>of(
                "block", expected.blockId(),
                "properties", expected.properties());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("recipe_ref", craft.recipeRef());
        parameters.put("recipe_fingerprint", craft.recipeFingerprint());
        parameters.put("goal", Map.of(
                "item", craft.goalItem(),
                "stack_policy", craft.stackPolicy(),
                "minimum_inventory_count", craft.minimumInventoryCount()));
        parameters.put("station", Map.of(
                "kind", craft.stationKind(),
                "target", targetMap,
                "expected_state", stateMap));
        parameters.put("max_crafts", craft.maxCrafts());
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0, 20, false);
        return new PhaseFiveRequest(
                "craft_items", parameters, bounds, craft.minimumInventoryCount(), "items");
    }

    static PhaseFiveRequest smeltRequest(
            ActionDsl.SmeltKnownRecipe smelt,
            AgentPrimitivePlanner.MutationAim smeltingAim) {
        ActionDsl.Position position = smelt.target();
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("recipe_ref", smelt.recipeRef());
        parameters.put("recipe_fingerprint", smelt.recipeFingerprint());
        parameters.put("goal", Map.of(
                "item", smelt.goalItem(),
                "stack_policy", smelt.stackPolicy(),
                "minimum_inventory_count", smelt.minimumInventoryCount()));
        parameters.put("station", Map.of(
                "kind", smelt.stationKind(),
                "target", Map.of(
                        "dimension", target.dimension(),
                        "x", target.x(), "y", target.y(), "z", target.z()),
                "expected_state", Map.of(
                        "block", smelt.expectedState().block(),
                        "properties", smelt.expectedState().properties())));
        parameters.put("fuel", Map.of(
                "item", smelt.fuelItem(),
                "stack_policy", smelt.fuelStackPolicy()));
        parameters.put("max_smelts", smelt.maxSmelts());
        long ticks = ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts());
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0,
                Math.toIntExact((ticks + 19L) / 20L), false);
        PhaseFiveRequest request = new PhaseFiveRequest(
                "smelt_items", parameters, bounds, smelt.maxSmelts(), "smelts");
        return withInventoryAim(request, position, smeltingAim);
    }

    private static String containerItemsTrace(List<KnownContainerAttempt.ItemCount> items) {
        var output = new StringBuilder("container_items=");
        for (var item : items) {
            String entry = (output.length() == "container_items=".length() ? "" : ",")
                    + item.item() + ":" + item.count();
            if (output.length() + entry.length() > 252) {
                output.append(",...");
                break;
            }
            output.append(entry);
        }
        return output.toString();
    }

    private void retryAgentMutationAim(
            Minecraft minecraft, AgentActionStore.Active action, String evidence) {
        agentExecution.blockMutationAttempt.close();
        agentExecution.blockMutationAttempt = null;
        agentExecution.mutationAimFailures++;
        if (!mutationAimRetryAllowed(agentExecution.mutationAimFailures)) {
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

    static boolean retryableMutationAimFailure(String evidence) {
        return "aim_raycast_unavailable".equals(evidence);
    }

    static boolean mutationAimRetryAllowed(int failureCount) {
        if (failureCount < 1) {
            throw new IllegalArgumentException("failureCount must be positive");
        }
        return failureCount < MAX_MUTATION_AIM_FAILURES;
    }

    static SemanticActionRequest blockMutationRequest(
            ActionDsl.Node node, AgentPrimitivePlanner.MutationAim plannedAim) {
        Objects.requireNonNull(plannedAim, "plannedAim");
        ActionDsl.Position position = switch (node) {
            case ActionDsl.TillKnownBlock value -> value.target();
            case ActionDsl.PlantKnownWheat value -> value.target();
            case ActionDsl.HarvestKnownWheat value -> value.target();
            case ActionDsl.OpenKnownFenceGate value -> value.target();
            case ActionDsl.OpenKnownPassage value -> value.target();
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
            case ActionDsl.OpenKnownPassage passage -> new InteractBlockRequest(
                    target,
                    new BlockStateFingerprint(passage.expectedBlock(), Map.of("open", "false")),
                    new BlockStateFingerprint(passage.expectedBlock(), Map.of("open", "true")),
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
        agentExecution.replanDeadlineTick = agentReplanDeadlineTick(
                agentExecution.primitive,
                actionTick,
                agentExecution.occurrenceBaseline.ticks(),
                agentExecution.occurrenceLimit.ticks());
    }

    static long agentReplanWindowTicks(ActionDsl.Node primitive) {
        return isKnownBreak(primitive)
                ? AgentPrimitivePlanner.BREAK_REOBSERVATION_TICKS
                : 20L;
    }

    static long agentReplanDeadlineTick(
            ActionDsl.Node primitive,
            long actionTick,
            long occurrenceStartTick,
            long occurrenceTickLimit) {
        long observationDeadline = Math.addExact(actionTick, agentReplanWindowTicks(primitive));
        if (!(primitive instanceof ActionDsl.NavigateToKnown)
                && !(primitive instanceof ActionDsl.ApproachKnownSurface)
                && !(primitive instanceof ActionDsl.ApproachKnownPlacement)
                && !(primitive instanceof ActionDsl.CollectVisibleItem)) {
            return observationDeadline;
        }
        long admittedNavigationDeadline = Math.addExact(
                occurrenceStartTick, Math.addExact(occurrenceTickLimit, 1L));
        return Math.max(observationDeadline, admittedNavigationDeadline);
    }

    static long recoveryEvidenceClientTick(WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        if (!session.worldReady() || session.clientTick() < 0L) {
            throw new IllegalStateException("recovery evidence requires a ready world clock");
        }
        return session.clientTick();
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
        long clientTick = recoveryEvidenceClientTick(session);
        var evidence = recoveryEvidence(player, session, map, local, clientTick);
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
        RecoveryHazards hazards = recoveryHazards(
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
            boolean localSafetyRequired,
            PredicateRequirements predicateRequirements,
            AdmissionPolicySnapshot predicateSnapshot,
            float cameraDegreesPerTick,
            boolean multiplayerServer,
            boolean multiplayerAllowed,
            ClientReconciliationSignals.Snapshot reconciliation,
            long visualBarrierWorldRevision,
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
            Objects.requireNonNull(reconciliation, "reconciliation");
            if (McmcpRuntime.visualBarrierWorldRevision(map, reconciliation)
                    != visualBarrierWorldRevision) {
                throw new IllegalArgumentException("visual barrier snapshot mismatch");
            }
            if (positionCorrectionRevision < 0L) {
                throw new IllegalArgumentException(
                        "positionCorrectionRevision must be non-negative");
            }
        }
    }

    private record PreparedAgentAction(
            AgentAdmissionSnapshot snapshot,
            ActionDslCompiler.CompiledProgram program,
            ActionDslSource source,
            AgentPrimitivePlanner.Analysis analysis,
            Optional<ActionDsl.Node> initialPrimitive) {
        private PreparedAgentAction {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(analysis, "analysis");
            Objects.requireNonNull(initialPrimitive, "initialPrimitive");
        }
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
        if (initialPrimitive.filter(McmcpRuntime::isKnownBreak).isEmpty()) {
            return true;
        }
        var player = minecraft.player;
        if (player == null) return false;
        var breaks = new ArrayList<ActionDsl.Node>();
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
            if (findDurableHotbarTool(player, breakToolItem(block), requiredDurability) < 0) {
                return false;
            }
        }
        return inventoryCanReceiveKnownBreakDrops(player, program);
    }

    private static boolean isKnownBreak(ActionDsl.Node node) {
        return node instanceof ActionDsl.BreakKnownFace
                || node instanceof ActionDsl.BreakKnownBlock
                || node instanceof ActionDsl.OperateKnownCobblestoneGenerator;
    }

    private static ActionDsl.Position breakTarget(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.target();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.target();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.target();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    private static ActionDsl.BlockFace breakFace(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.face();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.face();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.face();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    private static String breakBlockId(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.expectedBlock();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.expectedState().block();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.expectedState().block();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    private static String breakToolItem(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.toolItem();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.toolItem();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.toolItem();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    private static String breakExpectedDrop(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.expectedBlock();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.expectedDrop();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.expectedDrop();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    private static void collectBreakNodes(
            List<ActionDsl.Node> nodes, List<ActionDsl.Node> output) {
        for (var node : nodes) {
            if (isKnownBreak(node)) {
                output.add(node);
            } else if (node instanceof ActionDsl.If conditional) {
                collectBreakNodes(conditional.thenBranch(), output);
                collectBreakNodes(conditional.elseBranch(), output);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                collectBreakNodes(repeat.body(), output);
            }
        }
    }

    private static ActionDsl.BreakKnownBlock cobblestoneGeneratorBreak(
            ActionDsl.OperateKnownCobblestoneGenerator operation) {
        return new ActionDsl.BreakKnownBlock(
                operation.id(), operation.target(), operation.face(),
                operation.expectedState(), operation.toolItem(), operation.expectedDrop(),
                operation.minimumInventoryCount());
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

    private static boolean inventoryCanReceiveKnownBreakDrops(
            net.minecraft.client.player.LocalPlayer player,
            ActionDslCompiler.CompiledProgram program) {
        var breaks = new ArrayList<ActionDsl.Node>();
        collectBreakNodes(program.request().program().body(), breaks);
        var dropItems = new LinkedHashSet<String>();
        breaks.forEach(block -> dropItems.add(breakExpectedDrop(block)));
        if (dropItems.isEmpty()) return true;
        int requiredPerType = Math.toIntExact(program.worstCaseCost().blocksBroken());
        var inventory = player.getInventory();
        int emptySlots = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) emptySlots++;
        }
        int newStacksNeeded = 0;
        for (String itemId : dropItems) {
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
            Minecraft minecraft, ActionDsl.Node block) {
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
                || !breakTarget(block).dimension().equals(
                        level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                breakTarget(block).x(), breakTarget(block).y(), breakTarget(block).z());
        if (!level.isLoaded(position)
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(position)
                || hit.getDirection() != Direction.valueOf(breakFace(block).name())
                || !player.isWithinBlockInteractionRange(position, 0.0D)
                || !level.getWorldBorder().isWithinBounds(position)
                || player.blockActionRestricted(level, position, gameMode.getPlayerMode())) {
            return false;
        }
        var state = level.getBlockState(position);
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        float destroyProgress = state.getDestroyProgress(player, level, position);
        if (!breakBlockId(block).equals(blockId)
                || !SafeBreakSourcePolicy.allowsLiveState(
                        state, level.getBlockEntity(position) != null)
                || destroyProgress <= 0.0F
                || destroyProgress * StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS < 1.0F) {
            return false;
        }
        if (block instanceof ActionDsl.BreakKnownBlock exact) {
            var live = MinecraftStationaryBreakPort.fingerprintForPolicy(state);
            if (!new BlockStateFingerprint(
                            exact.expectedState().block(), exact.expectedState().properties())
                    .equals(live)
                    || !SafeBreakSourcePolicy.allowsKnownBlockCombination(
                            exact.expectedState().block(), exact.toolItem(), exact.expectedDrop())) {
                return false;
            }
        }
        int selected = player.getInventory().getSelectedSlot();
        if (selected < 0 || selected >= Inventory.getSelectionSize()) return false;
        var tool = player.getInventory().getItem(selected);
        return !tool.isEmpty()
                && breakToolItem(block).equals(
                        BuiltInRegistries.ITEM.getKey(tool.getItem()).toString())
                && tool.isDamageableItem()
                && tool.getMaxDamage() - tool.getDamageValue() >= 1;
    }

    private static boolean breakTargetStateMatches(
            Minecraft minecraft, ActionDsl.Node block) {
        var level = minecraft.level;
        if (level == null || !breakTarget(block).dimension().equals(
                level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                breakTarget(block).x(), breakTarget(block).y(), breakTarget(block).z());
        if (!level.isLoaded(position)) return false;
        var state = level.getBlockState(position);
        if (!breakBlockId(block).equals(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return false;
        return !(block instanceof ActionDsl.BreakKnownBlock exact)
                || new BlockStateFingerprint(
                        exact.expectedState().block(), exact.expectedState().properties())
                        .equals(MinecraftStationaryBreakPort.fingerprintForPolicy(state));
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

    static long visualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(reconciliation, "reconciliation");
        return AgentPrimitivePlanner.requireVisualBarrierWorldRevision(
                map,
                reconciliation.worldSessionId(),
                reconciliation.worldRevision(),
                reconciliation.visualBarrierWorldRevision());
    }

    static ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        visualBarrierWorldRevision(map, reconciliation);
        return position -> AgentPrimitivePlanner.requireSurfaceBarrierWorldRevision(
                map,
                reconciliation.surfaceBarrierWorldRevision(
                        position.x(), position.y(), position.z()));
    }

    static ToLongFunction<ActionDsl.Position> waitTargetSurfaceRevisionBarrier(
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        visualBarrierWorldRevision(map, reconciliation);
        return position -> AgentPrimitivePlanner.requireSurfaceBarrierWorldRevision(
                map,
                reconciliation.waitTargetSurfaceBarrierWorldRevision(
                        position.x(), position.y(), position.z()));
    }

    static ToLongFunction<ActionDsl.Position> primitiveSurfaceRevisionBarrier(
            ActionDsl.Node primitive,
            KnownTraversabilitySnapshot map,
            ClientReconciliationSignals.Snapshot reconciliation) {
        Objects.requireNonNull(primitive, "primitive");
        return primitive instanceof ActionDsl.WaitUntil
                && ((ActionDsl.WaitUntil) primitive).condition()
                        instanceof ActionDsl.CropMatureCondition
                ? waitTargetSurfaceRevisionBarrier(map, reconciliation)
                : surfaceRevisionBarrier(map, reconciliation);
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

    /**
     * Returns a fixed, non-reflective diagnostic when a freshly planned movement route cannot
     * use the reserve admitted for its original logical occurrence.
     */
    static String replannedRouteBudgetFailure(
            AgentActionStore.Progress used,
            AgentActionStore.Progress occurrenceBaseline,
            ActionDslCompiler.Cost occurrenceLimit,
            ActionDsl.Budget globalBudget,
            ActionDslCompiler.Cost retry,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(occurrenceBaseline, "occurrenceBaseline");
        Objects.requireNonNull(occurrenceLimit, "occurrenceLimit");
        Objects.requireNonNull(globalBudget, "globalBudget");
        Objects.requireNonNull(retry, "retry");
        if (!costFitsLimit(retry, occurrenceLimit)) {
            return REPLANNED_ROUTE_SHAPE_EVIDENCE;
        }
        if (!fitsRemainingBudget(used, globalBudget, retry, activeElapsedNanos)) {
            return REPLANNED_ROUTE_GLOBAL_EVIDENCE;
        }
        if (!fitsOccurrenceBudget(used, occurrenceBaseline, occurrenceLimit, retry)) {
            return REPLANNED_ROUTE_REMAINING_EVIDENCE;
        }
        return null;
    }

    private static boolean costFitsLimit(
            ActionDslCompiler.Cost cost, ActionDslCompiler.Cost limit) {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(limit, "limit");
        return fits(0L, cost.durationMillis(), limit.durationMillis())
                && fits(0L, cost.ticks(), limit.ticks())
                && fits(0.0D, cost.distanceBlocks(), limit.distanceBlocks())
                && fits(0.0D, cost.cameraDegrees(), limit.cameraDegrees())
                && fits(0L, cost.interactions(), limit.interactions())
                && fits(0L, cost.blocksBroken(), limit.blocksBroken())
                && fits(0L, cost.blocksPlaced(), limit.blocksPlaced());
    }

    static ActionDslCompiler.Cost firstPrimitiveRemainingCost(
            AgentActionStore.Progress used,
            ActionDslCompiler.Cost planned,
            long activeElapsedNanos) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(planned, "planned");
        if (used.ticks() != 0L || activeElapsedNanos <= 0L) return planned;
        long elapsedMillis = Math.ceilDiv(activeElapsedNanos, 1_000_000L);
        return new ActionDslCompiler.Cost(
                Math.max(0L, planned.durationMillis() - elapsedMillis),
                planned.ticks(),
                planned.distanceBlocks(),
                planned.cameraDegrees(),
                planned.interactions(),
                planned.blocksBroken(),
                planned.blocksPlaced());
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
                || primitive instanceof ActionDsl.ApproachKnownSurface
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.ApproachKnownPlacement
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.CollectVisibleItem
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.CollectVisibleItemBatch
                        && used.distanceTravelled() >= budget.maxDistanceBlocks()
                || primitive instanceof ActionDsl.FaceKnownPosition
                        && used.cameraDegrees() >= budget.maxCameraDegrees()
                || primitive instanceof ActionDsl.FaceKnownBlockFace
                        && used.cameraDegrees() >= budget.maxCameraDegrees()
                || isKnownBreak(primitive)
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
        return fitsOccurrenceBudget(used, baseline, limit, next);
    }

    private static boolean fitsOccurrenceBudget(
            AgentActionStore.Progress used,
            AgentActionStore.Progress baseline,
            ActionDslCompiler.Cost limit,
            ActionDslCompiler.Cost next) {
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(next, "next");
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

    static boolean fitsMutationBatchRemainder(
            AgentActionStore.Progress used,
            AgentActionStore.Progress occurrenceBaseline,
            ActionDslCompiler.Cost occurrenceLimit,
            ActionDsl.Budget globalBudget,
            ActionDslCompiler.Cost requiredRemainder,
            long activeElapsedNanos) {
        return fitsRemainingBudget(
                        used, globalBudget, requiredRemainder, activeElapsedNanos)
                && fitsOccurrenceBudget(
                        used, occurrenceBaseline, occurrenceLimit, requiredRemainder);
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
        double settlingCredit = batchTillSettlingCredit(
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

    static double batchTillSettlingCredit(
            net.minecraft.world.phys.Vec3 previous,
            net.minecraft.world.phys.Vec3 current,
            double allowance,
            boolean qualifiedWindow,
            boolean inputNeutral) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!qualifiedWindow || !inputNeutral
                || !Double.isFinite(allowance) || allowance <= 0.0D) return 0.0D;
        double dx = current.x - previous.x;
        double dz = current.z - previous.z;
        double descent = previous.y - current.y;
        if (Math.hypot(dx, dz) > 1.0e-6D
                || descent <= 0.0D
                || descent > allowance + 1.0e-6D) {
            return 0.0D;
        }
        return Math.min(descent, allowance);
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

    private boolean closeAgentPrimitiveExecutor() {
        if (agentExecution == null) return true;
        boolean closed = true;
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
                    if (isKnownBreak(agentExecution.primitive)) {
                        recordBreakEffects(
                                agentExecution.actionId,
                                breakTarget(agentExecution.primitive),
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
        if (agentExecution.containerAttempt != null) {
            KnownContainerAttempt container = agentExecution.containerAttempt;
            try {
                container.close();
                agentExecution.containerAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (container.releaseStatus()
                        != KnownContainerAttempt.ReleaseStatus.PROGRESSING) {
                    McmcpMod.LOGGER.error("MCMCP known-container release failed", failure);
                }
            } finally {
                try {
                    int releasedInteractions = container.drainReleaseInteractionDelta();
                    for (int count = 0; count < releasedInteractions; count++) {
                        agentActions.recordInteraction(agentExecution.actionId);
                    }
                    recordContainerEffects(
                            agentExecution.actionId,
                            agentExecution.primitive,
                            container.drainEffectDeltas());
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-container release usage capture failed", failure);
                }
            }
        }
        if (agentExecution.brewingAttempt != null) {
            KnownBrewingAttempt brewing = agentExecution.brewingAttempt;
            try {
                brewing.close();
                agentExecution.brewingAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (brewing.releaseStatus() != KnownBrewingAttempt.ReleaseStatus.PROGRESSING) {
                    McmcpMod.LOGGER.error("MCMCP known-brewing release failed", failure);
                }
            } finally {
                try {
                    int releasedInteractions = brewing.drainReleaseInteractionDelta();
                    for (int count = 0; count < releasedInteractions; count++) {
                        agentActions.recordInteraction(agentExecution.actionId);
                    }
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-brewing release usage capture failed", failure);
                }
            }
        }
        if (agentExecution.constructionAttempt != null) {
            KnownConstructionAttempt construction = agentExecution.constructionAttempt;
            try {
                construction.close();
                agentExecution.constructionAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-construction release failed", failure);
            } finally {
                try {
                    recordConstructionEffects(
                            agentExecution.actionId,
                            construction.drainEffectDeltas());
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-construction effect capture failed", failure);
                }
            }
        }
        if (agentExecution.pillarUpAttempt != null) {
            try {
                agentExecution.pillarUpAttempt.close();
                agentExecution.pillarUpAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-pillar release failed", failure);
            }
        }
        if (agentExecution.redstoneAttempt != null) {
            try {
                agentExecution.redstoneAttempt.close();
                agentExecution.redstoneAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-redstone release failed", failure);
            }
        }
        if (agentExecution.fishingAttempt != null) {
            try {
                if (releaseFishingAttempt(Minecraft.getInstance(), agentExecution.fishingAttempt)) {
                    agentExecution.fishingAttempt = null;
                } else {
                    closed = false;
                }
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-fishing release failed", failure);
            }
        }
        agentExecution.breakAimComplete = false;
        agentExecution.fishingAimComplete = false;
        return closed;
    }

    private boolean releaseFishingAttempt(Minecraft minecraft, FishingAttempt attempt) {
        var player = minecraft.player;
        if (player == null) return true;
        long tick = sessions.snapshot().clientTick();
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            if (attempt.mode == FishingMode.CAST && tick < attempt.deadlineTick) return false;
            recordUnknownFishingEffect(player, attempt, false, tick);
            return true;
        }
        long cleanupDeadline = attempt.cleanupDispatched
                ? attempt.cleanupDeadlineTick : attempt.deadlineTick;
        if (tick > cleanupDeadline) {
            recordUnknownFishingEffect(player, attempt,
                    ownedFishingHook(player, hook, attempt.bobberId), tick);
            fishingSessionRefs.clear();
            arming.lock("fishing_cleanup_unconfirmed");
            return true;
        }
        if (attempt.mode == FishingMode.REEL && !ownedFishingHook(player, hook, attempt.bobberId)) {
            return false;
        }
        if (!attempt.cleanupDispatched) {
            if (!exactFishingRodHeld(player, attempt.hand, attempt.rodItem)
                    || minecraft.gameMode == null) {
                return false;
            }
            minecraft.gameMode.useItem(player, fishingHand(attempt.hand));
            agentActions.recordInteraction(agentExecution.actionId);
            attempt.cleanupDispatched = true;
            attempt.cleanupDeadlineTick = Math.addExact(tick, 20L);
            return false;
        }
        // Returning false keeps terminal publication behind the bounded stateful cleanup fence.
        return false;
    }

    private void recordUnknownFishingEffect(
            net.minecraft.client.player.LocalPlayer player,
            FishingAttempt attempt,
            boolean bobberPresent,
            long clientTick) {
        if (attempt.effectRecorded || agentExecution == null) return;
        Map<String, Object> before;
        Map<String, Object> after;
        String kind;
        if (attempt.mode == FishingMode.CAST) {
            kind = "fishing_cast";
            before = Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                    "bobber_present", false);
            after = Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                    "bobber_present", bobberPresent);
        } else {
            kind = "fishing_reel";
            before = Map.of("hand", attempt.hand, "rod_damage", attempt.rodDamageBefore,
                    "inventory_count", totalInventoryCount(attempt.inventoryBefore),
                    "bobber_present", true);
            after = Map.of("hand", attempt.hand,
                    "rod_damage", fishingRodDamage(player, attempt.hand, attempt.rodItem),
                    "inventory_count", totalInventoryCount(inventoryCounts(player)),
                    "bobber_present", bobberPresent);
        }
        agentActions.recordEffect(
                agentExecution.actionId, kind, "minecraft:fishing_bobber",
                before, after, AgentActionStore.Verification.UNKNOWN,
                clientTick, agentExecution.latestWorldRevision);
        attempt.effectRecorded = true;
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
            AgentActionStore.FailureCode code, boolean recoverable, String evidence) {
        // Do not publish a terminal snapshot while an await worker could still observe an
        // Agent-owned key as pressed. Input release must happen first; READY is published only
        // after the terminal Action snapshot has awakened its waiters.
        var active = agentActions.active();
        if (active.isEmpty()) {
            releaseAgentControl(Minecraft.getInstance());
            return;
        }
        closePendingKillZoneEffectForTerminal(evidence);
        var terminal = PendingAgentTerminal.failure(
                active.orElseThrow().actionId(),
                new AgentActionStore.Failure(code, recoverable, List.of(evidence)));
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
        if (agentExecution == null || agentExecution.killZone == null
                || agentExecution.killZone.pending == null) return;
        KillZoneExecution operation = agentExecution.killZone;
        KillZoneAttackAttempt attempt = operation.pending;
        operation.pending = null;
        boolean armorStandHit = armorStandHitConfirmed(attempt);
        boolean confirmed = armorStandHit || attempt.target.getHealth() < attempt.healthBefore
                || (!attempt.target.isAlive() && attempt.target.getHealth() <= 0.0F);
        if (confirmed) operation.confirmedAttacks++;
        else {
            operation.unknownAttacks++;
            operation.noRetryEntityIds.add(attempt.target.getUUID());
        }
        var session = sessions.snapshot();
        agentActions.recordEffect(
                agentExecution.actionId,
                "entity_attack",
                "refhash:" + sha256Identity(new StringBuilder(attempt.entityRef))
                        .substring("sha256:".length()),
                Map.of("entity_type", entityType(attempt.target), "health", attempt.healthBefore),
                Map.of(
                        "health", attempt.target.getHealth(),
                        "outcome", armorStandHit ? "armor_stand_hit_event"
                                : confirmed ? "terminal_confirmed" : "terminal_unknown"),
                confirmed ? AgentActionStore.Verification.CONFIRMED
                        : AgentActionStore.Verification.UNKNOWN,
                Math.max(0L, session.clientTick()),
                Math.max(0L, agentExecution.latestWorldRevision));
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
            McmcpMod.LOGGER.error(
                    "MCMCP terminal input cleanup remained unconfirmed after {} attempts; "
                            + "the owner and local control lock were retained",
                    MAX_ACTION_INPUT_RELEASE_ATTEMPTS);
            return false;
        }
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
                agentExecution.containerAttempt != null
                        && agentExecution.containerAttempt.releaseStatus()
                                == KnownContainerAttempt.ReleaseStatus.PROGRESSING
                || agentExecution.brewingAttempt != null
                        && agentExecution.brewingAttempt.releaseStatus()
                                == KnownBrewingAttempt.ReleaseStatus.PROGRESSING
                || agentExecution.fishingAttempt != null);
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
                returnControlReady();
            }
        }
    }

    private void finishTerminalRoutine(Minecraft minecraft, UUID routineId) {
        var before = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        var terminal = finalizeTerminalRoutine(minecraft, before).snapshot();
        if (terminal.finalizationFailure() != null) {
            returnControlReady();
        }
    }

    private ClientCommandInbox.StopProgress stopActiveRoutineForEmergency(
            String reason,
            WorldSessionTracker.Snapshot session) {
        goalContinuation.clear();
        AgentActionStore.FailureCode actionCode = "local_ui_disabled".equals(reason)
                ? AgentActionStore.FailureCode.USER_DISABLED
                : AgentActionStore.FailureCode.EMERGENCY_STOP;
        var activeAgent = agentActions.active();
        PendingAgentTerminal actionTerminal = activeAgent
                .map(action -> PendingAgentTerminal.failure(
                        action.actionId(),
                        new AgentActionStore.Failure(
                                actionCode, true, List.of(sanitizeLocalCode(reason)))))
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
            boolean voiceEnded = endVoiceFor(voiceRoutineId);
            return voiceEnded ? actionProgress : ClientCommandInbox.StopProgress.FAILED;
        }
        try {
            var cancelled = routines.cancelRoutine(active.orElseThrow(), reason, Long.MAX_VALUE, 1);
            var cleanup = finalizeTerminalRoutine(Minecraft.getInstance(), cancelled);
            if (!cleanup.inputsReleased() || !cleanup.voice().success()) {
                return ClientCommandInbox.StopProgress.FAILED;
            }
            return actionProgress;
        }
        catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP routine cancellation failed during emergency stop", failure);
            endVoiceFor(active.orElseThrow());
            return ClientCommandInbox.StopProgress.FAILED;
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
                        returnControlReady();
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
        returnControlReady();
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
        returnControlReady();
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
                inputsReleased = releaseAllAndConfirmNoInputOwner(minecraft);
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
        return withEvaluationTurnGate(evaluationTerminalGate, () -> {
            requireLiveCall(context, command);
            return work.get();
        });
    }

    static <T> T withEvaluationTurnGate(Object gate, Supplier<T> work) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(work, "work");
        synchronized (gate) {
            return work.get();
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

    private record AgentCleanupProgress(boolean primitiveClosed, boolean recoveryClosed) {
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
            boolean supportedDoorPlace = step.operation() == ApplyBlockPlanOperation.PLACE
                    && "minecraft:air".equals(step.expectedBefore().blockId())
                    && step.expectedBefore().properties().isEmpty()
                    && step.requiredItemId().filter("minecraft:oak_door"::equals).isPresent()
                    && MinecraftApplyBlockPlanPort.supportedDoorPlacement(step.expectedAfter())
                    && request.bounds().contains(new BlockTarget(
                            step.target().dimension(), step.target().x(),
                            Math.addExact(step.target().y(), 1), step.target().z()));
            if (step.operation().mutating()
                    && !supportedDoorPlace
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
                    || blockItem instanceof DoubleHighBlockItem && !supportedDoorPlace
                    || placedBlock instanceof DoorBlock && !supportedDoorPlace
                    || placedBlock instanceof BedBlock
                    || placedBlock.defaultBlockState().hasProperty(
                            BlockStateProperties.DOUBLE_BLOCK_HALF) && !supportedDoorPlace
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
                terminateActiveEvaluationOnClient(
                        minecraft, EvaluationTurnControl.ReleaseReason.WORLD_CHANGED);
                clearAgentSessionState();
                routines.clearSession("dimension_changed");
                finalizationRetries.clear();
                goalContinuation.clear();
                voiceRoutineId = null;
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
            knownTraversability.startSession(
                    after.worldSessionId(), dimension, reconciliation.worldRevision());
            knownTraversabilityRevision = reconciliation.worldRevision();
            screenOwnership.bindWorldSession(minecraft.level, after.worldSessionId());
            MerchantOfferSignals.global().bindSession(minecraft.level, after.worldSessionId());
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

    static boolean mutationAffects(
            ClientReconciliationSignals.WorldMutation mutation, NavCell cell) {
        if (mutation.navigationImpact() == ClientReconciliationSignals.NavigationImpact.NONE) {
            return false;
        }
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
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        long worldRevision = reconciliation.worldRevision();
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
                        reconciliation.visualRevision(),
                        fogDistance,
                        entity -> {
                            var position = entity.position();
                            var velocity = entity.getDeltaMovement();
                            String entityType = BuiltInRegistries.ENTITY_TYPE
                                    .getKey(entity.getType()).toString();
                            return memory.rememberVisibleEntityReference(
                                    session.worldSessionId(),
                                    session.dimension(),
                                    entity.getUUID(),
                                    entityType,
                                    position.x,
                                    position.y,
                                    position.z,
                                    velocity.x,
                                    velocity.y,
                                    velocity.z,
                                    entity.isVehicle(),
                                    entity.isPassenger(),
                                    session.clientTick());
                        })
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

    enum CropWaitLiveState {
        PENDING,
        MATURE,
        TARGET_CHANGED,
        UNLOADED,
        VISIBILITY_INVALIDATED,
        WORLD_CHANGED
    }

    enum CropWaitVisibilityState {
        CURRENT,
        VISIBILITY_INVALIDATED,
        WORLD_CHANGED
    }

    record CropWaitAuthorization(
            UUID worldSessionId,
            String dimension,
            ActionDsl.Position target,
            long visualBarrierWorldRevision,
            Vec3 playerPosition,
            Vec3 observerEye) {
        CropWaitAuthorization {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(playerPosition, "playerPosition");
            Objects.requireNonNull(observerEye, "observerEye");
            if (!dimension.equals(target.dimension())) {
                throw new IllegalArgumentException(
                        "crop wait authorization must stay in one dimension");
            }
            if (visualBarrierWorldRevision < 0L
                    || !finite(playerPosition) || !finite(observerEye)) {
                throw new IllegalArgumentException(
                        "crop wait authorization fence must be finite and non-negative");
            }
        }

        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x)
                    && Double.isFinite(value.y)
                    && Double.isFinite(value.z);
        }

        boolean matches(
                WorldSessionTracker.Snapshot session,
                ActionDsl.Position requestedTarget) {
            return session.worldReady()
                    && worldSessionId.equals(session.worldSessionId())
                    && dimension.equals(session.dimension())
                    && target.equals(requestedTarget);
        }
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
        private FishingAttempt fishingAttempt;
        private int mutationAimFailures;
        private KnownBlockBreakAttempt blockBreakAttempt;
        private StationaryBreakOperation cobblestoneGeneratorAttempt;
        private long cobblestoneGeneratorCheckpoint;
        private boolean cobblestoneGeneratorUnknownRecorded;
        private KnownBlockMutationAttempt blockMutationAttempt;
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
        private KnownContainerAttempt containerAttempt;
        private KnownBrewingAttempt brewingAttempt;
        private KnownConstructionAttempt constructionAttempt;
        private KnownPillarUpAttempt pillarUpAttempt;
        private KnownRedstoneIdentityAttempt redstoneAttempt;
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
                long positionCorrectionRevision) {
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

    /** Runtime-only authority copied from the consumed consent; never serialized as a bearer. */
    private static final class KillZoneExecution {
        private static final long EFFECT_DEADLINE_TICKS = 10L;

        private final ActionDsl.OperateKillZone operation;
        private final ScopedEntityAttackConsentStore.Scope scope;
        private final long startedAtClientTick;
        private final float healthBaseline;
        private final float absorptionBaseline;
        private final float effectiveHealthBaseline;
        private float lastHealth;
        private float lastAbsorption;
        private float lastEffectiveHealth;
        private final Set<UUID> noRetryEntityIds = new LinkedHashSet<>();
        private long lastDispatchTick = Long.MIN_VALUE;
        private int dispatchedAttacks;
        private int confirmedAttacks;
        private int unknownAttacks;
        private KillZoneAttackAttempt pending;

        private KillZoneExecution(
                ActionDsl.OperateKillZone operation,
                ScopedEntityAttackConsentStore.Scope scope,
                long startedAtClientTick,
                float healthBaseline,
                float absorptionBaseline) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.scope = Objects.requireNonNull(scope, "scope");
            if (startedAtClientTick < 0L || !Float.isFinite(healthBaseline)
                    || !Float.isFinite(absorptionBaseline)) {
                throw new IllegalArgumentException("Invalid kill-zone execution baseline");
            }
            this.startedAtClientTick = startedAtClientTick;
            this.healthBaseline = healthBaseline;
            this.absorptionBaseline = absorptionBaseline;
            effectiveHealthBaseline = healthBaseline + absorptionBaseline;
            lastHealth = healthBaseline;
            lastAbsorption = absorptionBaseline;
            lastEffectiveHealth = effectiveHealthBaseline;
        }
    }

    private static final class KillZoneAttackAttempt {
        private final LivingEntity target;
        private final String entityRef;
        private final float healthBefore;
        private final long armorStandLastHitBefore;
        private final long effectDeadlineTick;

        private KillZoneAttackAttempt(
                LivingEntity target,
                String entityRef,
                float healthBefore,
                long armorStandLastHitBefore,
                long dispatchTick) {
            this.target = Objects.requireNonNull(target, "target");
            this.entityRef = Objects.requireNonNull(entityRef, "entityRef");
            this.healthBefore = healthBefore;
            this.armorStandLastHitBefore = armorStandLastHitBefore;
            effectDeadlineTick = Math.addExact(
                    dispatchTick, KillZoneExecution.EFFECT_DEADLINE_TICKS);
        }
    }

    private record KillZoneTarget(LivingEntity entity, String entityRef) {
        private KillZoneTarget {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(entityRef, "entityRef");
        }
    }

    private enum FishingMode { CAST, REEL }

    private static final class FishingAttempt {
        private final FishingMode mode;
        private final String hand;
        private final String rodItem;
        private final UUID bobberId;
        private final int rodDamageBefore;
        private final Map<String, Integer> inventoryBefore;
        private final long deadlineTick;
        private final long startTick;
        private boolean effectRecorded;
        private boolean cleanupDispatched;
        private long cleanupDeadlineTick;

        private FishingAttempt(
                FishingMode mode,
                String hand,
                String rodItem,
                UUID bobberId,
                int rodDamageBefore,
                Map<String, Integer> inventoryBefore,
                long startTick) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.hand = Objects.requireNonNull(hand, "hand");
            this.rodItem = Objects.requireNonNull(rodItem, "rodItem");
            this.bobberId = bobberId;
            this.rodDamageBefore = rodDamageBefore;
            this.inventoryBefore = Map.copyOf(inventoryBefore);
            this.startTick = startTick;
            deadlineTick = Math.addExact(startTick, ActionDslCompiler.KNOWN_FISHING_TICKS);
        }

        private static FishingAttempt cast(String hand, String rodItem, long startTick) {
            return new FishingAttempt(
                    FishingMode.CAST, hand, rodItem, null, -1, Map.of(), startTick);
        }

        private static FishingAttempt reel(
                String hand,
                String rodItem,
                UUID bobberId,
                int rodDamageBefore,
                Map<String, Integer> inventoryBefore,
                long startTick) {
            return new FishingAttempt(
                    FishingMode.REEL, hand, rodItem,
                    Objects.requireNonNull(bobberId, "bobberId"),
                    rodDamageBefore, inventoryBefore, startTick);
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
