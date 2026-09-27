package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineManager;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;
import dev.aod.mcmcp.runtime.RuntimeFailures.FailureWithDetailsException;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.voice.VoiceChatSafetyController;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/** 旧routineの期限・音声所有権・終了retryを所有する。Actionの状態は保持しない。 */
final class RoutineLifecycle {
    private static final Duration FINALIZATION_RESERVE = Duration.ofSeconds(5);
    private static final double MAX_SAFE_STAY_HORIZONTAL_SPEED_SQUARED = 0.01;
    private static final float MIN_SAFE_STAY_HEALTH = 6.0F;
    private final RoutineManager routines;
    private final WorldSessionTracker sessions;
    private final LocalArmingState arming;
    private final InputReleaseController inputRelease;
    private final VoiceChatSafetyController voiceChat;
    private final MinecraftObservationService observations;
    private final ScreenOwnershipSignals screenOwnership;
    private final Runnable returnControlReady;
    private final java.util.function.Consumer<RuntimeCallContext> liveCall;
    private final FinalizationRetryQueue finalizationRetries = new FinalizationRetryQueue();
    private final GoalContinuationSession goalContinuation = new GoalContinuationSession();
    private RoutineWallClockDeadline activeRoutineDeadline;
    private UUID voiceRoutineId;
    private boolean paused;
    private long pauseStartedAtNanos;

    RoutineLifecycle(RoutineManager routines, WorldSessionTracker sessions,
            LocalArmingState arming, InputReleaseController inputRelease,
            VoiceChatSafetyController voiceChat, MinecraftObservationService observations,
            ScreenOwnershipSignals screenOwnership, Runnable returnControlReady,
            java.util.function.Consumer<RuntimeCallContext> liveCall) {
        this.routines = routines;
        this.sessions = sessions;
        this.arming = arming;
        this.inputRelease = inputRelease;
        this.voiceChat = voiceChat;
        this.observations = observations;
        this.screenOwnership = screenOwnership;
        this.returnControlReady = returnControlReady;
        this.liveCall = liveCall;
    }

    void requireLiveCall(RuntimeCallContext context) { liveCall.accept(context); }
    FinalizationRetryQueue finalizationRetries() { return finalizationRetries; }
    boolean hasPendingFinalizations() { return finalizationRetries.hasPending(); }
    boolean hasVoiceOwner() { return voiceRoutineId != null; }
    boolean endOwnedVoice() { return endVoiceFor(voiceRoutineId); }
    void clearContinuation() { goalContinuation.clear(); }
    void resetContinuation(UUID sessionId) { goalContinuation.reset(sessionId); }
    void clearSession() {
        finalizationRetries.clear();
        goalContinuation.clear();
        voiceRoutineId = null;
    }
    void clearDeadline() { activeRoutineDeadline = null; }
    boolean withinDeadline(UUID routineId, long nowNanos) {
        return activeRoutineDeadline != null && activeRoutineDeadline.allows(routineId, nowNanos);
    }
    void onPauseChanged(boolean paused, long nowNanos) {
        if (paused) {
            pauseStartedAtNanos = nowNanos;
        } else {
            shiftDeadline(ActionBudgets.nonNegativeNanoElapsed(pauseStartedAtNanos, nowNanos));
            pauseStartedAtNanos = 0L;
        }
        this.paused = paused;
    }
    private void shiftDeadline(long pausedNanos) {
        if (activeRoutineDeadline != null) {
            activeRoutineDeadline = activeRoutineDeadline.shiftStart(pausedNanos);
        }
    }
    private boolean releaseAllAndConfirmNoInputOwner(Minecraft minecraft) {
        boolean released = inputRelease.releaseAll(minecraft);
        boolean inputOwnerNone = inputRelease.inputOwnerNone(minecraft);
        return released && inputOwnerNone;
    }

    void retryPendingFinalizations(Minecraft minecraft) {
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
                returnControlReady.run();
            }
        }
    }

    void finishTerminalRoutine(Minecraft minecraft, UUID routineId) {
        var before = routines.getRoutine(routineId, Long.MAX_VALUE, 1);
        var terminal = finalizeTerminalRoutine(minecraft, before).snapshot();
        if (terminal.finalizationFailure() != null) {
            returnControlReady.run();
        }
    }

    TerminalCleanup finalizeTerminalRoutine(
            Minecraft minecraft,
            RoutineSnapshot terminalSnapshot) {
        return finalizeRoutineBoundary(minecraft, terminalSnapshot);
    }

    TerminalCleanup finalizeRoutineBoundary(
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
                        returnControlReady.run();
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
        returnControlReady.run();
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
                AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen()),
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
        returnControlReady.run();
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

    boolean endVoiceFor(UUID routineId) {
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

    record TerminalCleanup(
            RoutineSnapshot snapshot,
            boolean inputsReleased,
            VoiceEndOutcome voice) {
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
            long elapsedNanos = ActionBudgets.activeElapsedNanos(startedAtNanos, pausedNanos, nowNanos);
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
                    ActionBudgets.saturatingNonNegativeAdd(this.pausedNanos, pausedNanos));
        }
    }

    RoutineManager.StartReceipt admitWithVoiceSafety(
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
            requireLiveCall(context);
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
            requireLiveCall(context);
            receipt = admission.get();
        }
        catch (RuntimeException | LinkageError failure) {
            returnControlReady.run();
            var voiceEnd = endVoiceSessionFor(null);
            throw withVoiceEndFailureDiagnostics(failure, voiceEnd);
        }
        if (!receipt.reused()) {
            long startedAtNanos = System.nanoTime();
            activeRoutineDeadline = RoutineWallClockDeadline.start(
                    receipt.routineId(), maxDurationSeconds, startedAtNanos);
            if (paused) pauseStartedAtNanos = startedAtNanos;
        }
        voiceRoutineId = receipt.routineId();
        goalContinuation.remember(
                worldSessionId, receipt.routineId(), receipt.reused(), completionIntent);
        return receipt;
    }

}
