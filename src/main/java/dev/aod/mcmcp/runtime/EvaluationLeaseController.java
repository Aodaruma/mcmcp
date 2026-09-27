package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.mcp.EvaluationTurnControl;
import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.safety.EvaluationTurnGuard;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** 評価leaseの受付fence、terminal intentとcontrol lane待機を所有する。 */
final class EvaluationLeaseController implements EvaluationTurnControl {
    private static final Duration EVALUATION_CONTROL_DISPATCH_TIMEOUT = Duration.ofSeconds(1);
    private final EvaluationTurnGuard evaluationTurns = new EvaluationTurnGuard();
    private final Object evaluationTerminalGate = new Object();
    /** evaluationTerminalGateで保護し、このcontrollerの生存中は再利用しない。 */
    private long evaluationFenceRevision;
    private PendingEvaluationTerminal pendingEvaluationTerminal;
    private final WorldSessionTracker sessions;
    private final Supplier<WorldSessionTracker.Snapshot> publishedSession;
    private final LocalArmingState arming;
    private final InputReleaseController inputRelease;
    private final ClientCommandInbox inbox;
    private final BooleanSupplier shutdown;
    private final BooleanSupplier admissionReady;
    private final BooleanSupplier actionsTerminal;
    private final BooleanSupplier automationActivityPending;

    EvaluationLeaseController(WorldSessionTracker sessions,
            Supplier<WorldSessionTracker.Snapshot> publishedSession,
            LocalArmingState arming, InputReleaseController inputRelease,
            ClientCommandInbox inbox, BooleanSupplier shutdown,
            BooleanSupplier admissionReady, BooleanSupplier automationActivityPending,
            BooleanSupplier actionsTerminal) {
        this.sessions = sessions;
        this.publishedSession = publishedSession;
        this.arming = arming;
        this.inputRelease = inputRelease;
        this.inbox = inbox;
        this.shutdown = shutdown;
        this.admissionReady = admissionReady;
        this.actionsTerminal = actionsTerminal;
        this.automationActivityPending = automationActivityPending;
    }

    EvaluationTurnGuard.Snapshot snapshot(UUID worldSessionId) {
        return evaluationTurns.snapshot(worldSessionId);
    }

    <T> T withFence(Supplier<T> work) {
        return withEvaluationTurnGate(evaluationTerminalGate, work);
    }

    private boolean releaseAllAndConfirmNoInputOwner(Minecraft minecraft) {
        boolean released = inputRelease.releaseAll(minecraft);
        boolean inputOwnerNone = inputRelease.inputOwnerNone(minecraft);
        return released && inputOwnerNone;
    }

    @Override
    public CompletionStage<EvaluationTurnControl.LeaseReceipt> acquire(
            EvaluationTurnControl.AcquireRequest request) {
        Objects.requireNonNull(request, "request");
        if (shutdown.getAsBoolean()) {
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
        var fence = publishedSession.get();
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
        McmcpRuntime.assertClientThread(minecraft);
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
        if (shutdown.getAsBoolean()
                || !runner.isAlive()
                || !identity.matches(runner)
                || !McmcpRuntime.localControlAvailable(minecraft, session)
                || !admissionReady.getAsBoolean()
                || control.mode() != LocalArmingState.Mode.READY
                || automationActivityPending.getAsBoolean()
                || evaluationTurns.snapshot(session.worldSessionId()).active()) {
            throw new IllegalStateException("evaluation turn admission is not ready");
        }
        if (!McmcpRuntime.boundedActionInputRelease(() -> releaseAllAndConfirmNoInputOwner(minecraft))) {
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
        var snapshot = evaluationTurns.snapshot(publishedSession.get().worldSessionId());
        if (snapshot.activeLease().filter(lease -> lease.leaseId().equals(leaseId)).isPresent()) {
            return evaluationTurns.awaitTerminal(snapshot.activeLease().orElseThrow())
                    .thenApply(EvaluationLeaseController::evaluationReceipt);
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

        var fence = publishedSession.get();
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
            var activeLease = evaluationTurns.snapshot(publishedSession.get().worldSessionId())
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
            var snapshot = evaluationTurns.snapshot(publishedSession.get().worldSessionId());
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

    void requestEvaluationReleaseFromAnyThread(
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
        McmcpRuntime.assertClientThread(minecraft);
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
                && actionsTerminal.getAsBoolean();
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
            receipt = evaluationTurns.snapshot(publishedSession.get().worldSessionId())
                    .previousTerminal()
                    .filter(terminal -> terminal.lease().leaseId()
                            .equals(pending.lease.leaseId()))
                    .map(EvaluationLeaseController::evaluationReceipt)
                    .orElseThrow(() -> new IllegalStateException(
                            "evaluation terminal state was not retained"));
        }
        completePendingEvaluationTerminal(pending, receipt);
        return receipt;
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

    void terminateActiveEvaluationOnClient(
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

    void terminateInvalidEvaluationLeaseOnClient(Minecraft minecraft) {
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

    void finishPendingEvaluationTerminalOnClient(Minecraft minecraft) {
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

    static <T> T withEvaluationTurnGate(Object gate, Supplier<T> work) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(work, "work");
        synchronized (gate) {
            return work.get();
        }
    }

}
