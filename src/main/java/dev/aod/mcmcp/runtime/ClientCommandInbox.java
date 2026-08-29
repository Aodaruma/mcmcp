package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.mcp.RuntimeCallContext;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Bounded HTTP-to-client-thread bridge. Emergency stop has a dedicated lane,
 * and every normal command is fenced by deadline, world generation and safety epoch.
 */
public final class ClientCommandInbox {
    public static final int DEFAULT_CAPACITY = 64;
    public static final int MAX_NORMAL_COMMANDS_PER_TICK = 1;
    public static final int MAX_CONTROL_COMMANDS_PER_TICK = 8;

    private final ArrayBlockingQueue<PendingCommand<?>> normalQueue;
    private final ArrayBlockingQueue<PendingCommand<?>> controlQueue;
    private final ConcurrentLinkedQueue<CompletableFuture<StopReceipt>> stopWaiters = new ConcurrentLinkedQueue<>();
    private StopRequest pendingStop;
    private int pendingStopDiscardedStarts;
    private final AtomicLong safetyEpoch = new AtomicLong();
    private final Object admissionGate = new Object();
    private final InputReleaseController inputRelease;
    private final LocalArmingState armingState;
    private final EmergencyStopHandler emergencyStopHandler;
    private final LongSupplier nanoTime;
    private boolean accepting = true;
    /** Guarded by {@link #admissionGate}; returned idempotently once shutdown has completed. */
    private StopReceipt terminalStopReceipt;

    public ClientCommandInbox(InputReleaseController inputRelease, LocalArmingState armingState) {
        this(DEFAULT_CAPACITY, inputRelease, armingState,
                (reason, session) -> StopProgress.COMPLETE);
    }

    public ClientCommandInbox(int capacity, InputReleaseController inputRelease, LocalArmingState armingState) {
        this(capacity, inputRelease, armingState,
                (reason, session) -> StopProgress.COMPLETE);
    }

    public ClientCommandInbox(
            int capacity,
            InputReleaseController inputRelease,
            LocalArmingState armingState,
            EmergencyStopHandler emergencyStopHandler) {
        this(capacity, inputRelease, armingState, emergencyStopHandler, System::nanoTime);
    }

    ClientCommandInbox(
            int capacity,
            InputReleaseController inputRelease,
            LocalArmingState armingState,
            EmergencyStopHandler emergencyStopHandler,
            LongSupplier nanoTime) {
        normalQueue = new ArrayBlockingQueue<>(capacity);
        controlQueue = new ArrayBlockingQueue<>(Math.max(8, Math.min(capacity, 32)));
        this.inputRelease = Objects.requireNonNull(inputRelease, "inputRelease");
        this.armingState = Objects.requireNonNull(armingState, "armingState");
        this.emergencyStopHandler = Objects.requireNonNull(emergencyStopHandler, "emergencyStopHandler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action) {
        return submit(
                name, expectedWorldGeneration, deadlineNanos, action,
                null, () -> false, ignored -> { });
    }

    <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action,
            BooleanSupplier completionAbandoned,
            Consumer<T> onAbandonedCompletion) {
        return submit(
                name, expectedWorldGeneration, deadlineNanos, action,
                null, completionAbandoned, onAbandonedCompletion);
    }

    <T> CompletableFuture<T> submitMapped(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action,
            Function<Throwable, T> failureMapper,
            BooleanSupplier completionAbandoned,
            Consumer<T> onAbandonedCompletion) {
        return submit(
                name, expectedWorldGeneration, deadlineNanos, action,
                Objects.requireNonNull(failureMapper, "failureMapper"),
                completionAbandoned, onAbandonedCompletion);
    }

    private <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action,
            Function<Throwable, T> failureMapper,
            BooleanSupplier completionAbandoned,
            Consumer<T> onAbandonedCompletion) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(completionAbandoned, "completionAbandoned");
        Objects.requireNonNull(onAbandonedCompletion, "onAbandonedCompletion");
        var result = new CompletableFuture<T>();
        synchronized (admissionGate) {
            if (!accepting) {
                var failure = new RejectedExecutionException("runtime is stopping");
                return failureMapper == null
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(failureMapper.apply(failure));
            }
            var command = new PendingCommand<>(
                    name, expectedWorldGeneration, safetyEpoch.get(), deadlineNanos,
                    action, result, failureMapper, completionAbandoned,
                    onAbandonedCompletion, nanoTime);
            if (!normalQueue.offer(command)) {
                var failure = new RejectedExecutionException("client command inbox is full");
                return failureMapper == null
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(failureMapper.apply(failure));
            }
        }
        return result;
    }

    /**
     * Enqueues bounded cancellation/control work for the next pre-tick, ahead of reads.
     * Starts remain on the post-tick queue so they validate against a freshly picked target.
     */
    public <T> CompletableFuture<T> submitControl(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action) {
        return submitControl(
                name,
                expectedWorldGeneration,
                deadlineNanos,
                action,
                () -> false,
                ignored -> { });
    }

    public <T> CompletableFuture<T> submitControl(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action,
            BooleanSupplier completionAbandoned,
            Consumer<T> onAbandonedCompletion) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(completionAbandoned, "completionAbandoned");
        Objects.requireNonNull(onAbandonedCompletion, "onAbandonedCompletion");
        var result = new CompletableFuture<T>();
        synchronized (admissionGate) {
            if (!accepting) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("runtime is stopping"));
            }
            var command = new PendingCommand<>(
                    name, expectedWorldGeneration, safetyEpoch.get(), deadlineNanos,
                    action, result, null, completionAbandoned,
                    onAbandonedCompletion, nanoTime);
            if (!controlQueue.offer(command)) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("client control inbox is full"));
            }
        }
        return result;
    }

    <T> CompletableFuture<T> submitControlMapped(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action,
            Function<Throwable, T> failureMapper) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(failureMapper, "failureMapper");
        var result = new CompletableFuture<T>();
        synchronized (admissionGate) {
            if (!accepting) {
                return CompletableFuture.completedFuture(
                        failureMapper.apply(new RejectedExecutionException("runtime is stopping")));
            }
            var command = new PendingCommand<>(
                    name, expectedWorldGeneration, safetyEpoch.get(), deadlineNanos,
                    action, result, failureMapper, () -> false, ignored -> { }, nanoTime);
            if (!controlQueue.offer(command)) {
                return CompletableFuture.completedFuture(
                        failureMapper.apply(new RejectedExecutionException(
                                "client control inbox is full")));
            }
        }
        return result;
    }

    public <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            Duration timeout,
            Callable<T> action) {
        Objects.requireNonNull(timeout, "timeout");
        return submit(
                name,
                expectedWorldGeneration,
                RuntimeCallContext.deadlineAfter(nanoTime.getAsLong(), timeout.toNanos()),
                action);
    }

    /** Safe to call from any thread; stops work while retaining an existing local READY lease. */
    public CompletableFuture<StopReceipt> requestEmergencyStop(String reason) {
        synchronized (admissionGate) {
            if (!accepting) {
                if (terminalStopReceipt != null) {
                    return CompletableFuture.completedFuture(terminalStopReceipt);
                }
                // Shutdown has been admitted but its client-thread release has not completed yet.
                // Join the existing stop rather than creating an undrainable post-shutdown request.
                var waiter = new CompletableFuture<StopReceipt>();
                stopWaiters.add(waiter);
                return waiter;
            }
            return requestEmergencyStopLocked(reason, true);
        }
    }

    /** Trusted local UI path: stop work and explicitly revoke the local lease. */
    CompletableFuture<StopReceipt> requestLocalDisable() {
        synchronized (admissionGate) {
            if (!accepting) {
                return requestEmergencyStop("local_ui_disabled");
            }
            return requestEmergencyStopLocked("local_ui_disabled", false);
        }
    }

    /** Trusted physical Esc path: stop the active operation but retain the current local lease. */
    CompletableFuture<StopReceipt> requestLocalEmergencyStop() {
        synchronized (admissionGate) {
            if (!accepting) {
                return requestEmergencyStop("local_emergency_key");
            }
            return requestEmergencyStopLocked("local_emergency_key", true);
        }
    }

    /** Runs the priority safety lane before vanilla processes the rest of the client tick. */
    public void drainEmergencyStopPreTick(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("command inbox must drain on the Minecraft client thread");
        }
        processEmergencyStop(minecraft, session);
    }

    /** Drains cancellation/control work after emergency stop and before routine input acquisition. */
    public void drainControlsPreTick(WorldSessionTracker.Snapshot session) {
        drainControls(session.generation(), nanoTime.getAsLong());
    }

    void drainControls(long currentGeneration, long nowNanos) {
        drainQueue(controlQueue, MAX_CONTROL_COMMANDS_PER_TICK, currentGeneration, nowNanos);
    }

    /**
     * Runs Phase 1 read commands after vanilla has refreshed crosshair targeting for this tick.
     * Mutating routines use a separate pre-tick lane in later phases.
     */
    public void drainReadsPostTick(WorldSessionTracker.Snapshot session) {
        drainQueue(
                normalQueue,
                MAX_NORMAL_COMMANDS_PER_TICK,
                session.generation(),
                nanoTime.getAsLong());
    }

    void drainNormal(long currentGeneration, long nowNanos) {
        drainQueue(normalQueue, MAX_NORMAL_COMMANDS_PER_TICK, currentGeneration, nowNanos);
    }

    private void drainQueue(
            ArrayBlockingQueue<PendingCommand<?>> queue,
            int limit,
            long currentGeneration,
            long nowNanos) {
        for (int i = 0; i < limit; i++) {
            var command = queue.poll();
            if (command == null) {
                return;
            }
            boolean claimed;
            synchronized (admissionGate) {
                claimed = command.claimIfCurrent(currentGeneration, safetyEpoch.get(), nowNanos);
            }
            if (claimed) {
                // Claim is the linearization point. A concurrent stop can now be accepted
                // immediately and will be processed by the priority lane next tick.
                command.runClaimed();
            }
        }
    }

    public void shutdown(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        synchronized (admissionGate) {
            accepting = false;
            requestEmergencyStopLocked("client_shutdown", false);
        }
        if (minecraft.isSameThread()) {
            processEmergencyStop(minecraft, session);
        }
        failPending(new RejectedExecutionException("runtime stopped"));
    }

    public long safetyEpoch() {
        return safetyEpoch.get();
    }

    public int queuedCommands() {
        return normalQueue.size() + controlQueue.size();
    }

    /** Used by physical-input fencing so a queued mutating start cannot outlive user input. */
    public boolean hasPendingCommand(String name) {
        Objects.requireNonNull(name, "name");
        synchronized (admissionGate) {
            return normalQueue.stream().anyMatch(command -> command.name().equals(name))
                    || controlQueue.stream().anyMatch(command -> command.name().equals(name));
        }
    }

    private void processEmergencyStop(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        synchronized (admissionGate) {
            var request = pendingStop;
            pendingStop = null;
            if (request == null && stopWaiters.isEmpty()) {
                return;
            }
            request = request == null ? new StopRequest("operator_stop", false) : request;
            var reason = request.reason();
            var beforeStop = armingState.snapshot(session.worldSessionId());
            boolean releaseCommandsSucceeded = inputRelease.releaseAll(minecraft);
            StopProgress handlerProgress;
            try {
                handlerProgress = Objects.requireNonNull(
                        emergencyStopHandler.stop(reason, session),
                        "emergency stop handler returned no progress");
            }
            catch (RuntimeException | LinkageError failure) {
                handlerProgress = StopProgress.FAILED;
            }
            int discarded = failPending(
                    new CommandInvalidatedException("invalidated by emergency stop"));
            pendingStopDiscardedStarts = Math.addExact(
                    pendingStopDiscardedStarts, discarded);
            if (retainPendingStop(handlerProgress, request.keepReady())) {
                // Keep the original waiters until a later client tick confirms
                // menu/cursor/view/slot cleanup. OFF intent was already applied at admission;
                // a later OFF request can still replace a retained keep-ready request.
                pendingStop = request;
                return;
            }
            boolean inputOwnerNone;
            try {
                inputOwnerNone = inputRelease.inputOwnerNone(minecraft);
            } catch (RuntimeException | LinkageError failure) {
                inputOwnerNone = false;
            }
            StopProof stopProof = stopProof(
                    releaseCommandsSucceeded,
                    handlerProgress == StopProgress.COMPLETE,
                    inputOwnerNone);
            boolean locked = settleArmingAfterStop(
                    armingState,
                    beforeStop,
                    session.worldSessionId(),
                    request.keepReady(),
                    stopProof.terminalSafe(),
                    reason);
            var receipt = new StopReceipt(
                    reason,
                    safetyEpoch.get(),
                    session.generation(),
                    stopProof.inputsReleased(),
                    stopProof.inputOwnerNone(),
                    locked,
                    pendingStopDiscardedStarts);
            pendingStopDiscardedStarts = 0;
            if (!accepting) {
                terminalStopReceipt = receipt;
            }
            CompletableFuture<StopReceipt> waiter;
            while ((waiter = stopWaiters.poll()) != null) {
                waiter.complete(receipt);
            }
        }
    }

    private int failPending(Throwable failure) {
        var pending = new ArrayList<PendingCommand<?>>();
        normalQueue.drainTo(pending);
        controlQueue.drainTo(pending);
        int discardedStarts = Math.toIntExact(pending.stream()
                .filter(command -> command.name().equals("start_routine")
                        || command.name().equals("agent_start_action"))
                .count());
        pending.forEach(command -> command.completeFailure(failure));
        return discardedStarts;
    }

    /** Caller must hold {@link #admissionGate}. */
    private CompletableFuture<StopReceipt> requestEmergencyStopLocked(
            String reason, boolean keepReady) {
        var future = new CompletableFuture<StopReceipt>();
        stopWaiters.add(future);
        safetyEpoch.incrementAndGet();
        var request = new StopRequest(sanitizeReason(reason), keepReady);
        if (!keepReady) {
            // OFF is the caller's final authorization intent, independent of the bounded
            // physical cleanup that the client lane must still finish before its waiter resolves.
            armingState.lock(request.reason());
        }
        if (pendingStop == null || pendingStop.keepReady()) {
            pendingStop = request;
        }
        return future;
    }

    static boolean settleArmingAfterStop(
            LocalArmingState armingState,
            LocalArmingState.Snapshot beforeStop,
            java.util.UUID currentSessionId,
            boolean keepReady,
            boolean inputsReleased,
            String reason) {
        if (inputsReleased
                && keepReady
                && !beforeStop.locked()
                && currentSessionId != null
                && currentSessionId.equals(beforeStop.worldSessionId())) {
            armingState.arm(currentSessionId, beforeStop.capabilities());
            return false;
        }
        armingState.lock(reason);
        return true;
    }

    /** Keeps release execution and measured ownership as independent terminal proofs. */
    static StopProof stopProof(
            boolean releaseCommandsSucceeded,
            boolean handlerSucceeded,
            boolean inputOwnerNone) {
        return new StopProof(
                releaseCommandsSucceeded && handlerSucceeded,
                inputOwnerNone);
    }

    static boolean retainPendingStop(StopProgress progress, boolean keepReady) {
        Objects.requireNonNull(progress, "progress");
        return progress == StopProgress.PENDING;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_stop";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }

    private record StopRequest(String reason, boolean keepReady) {
    }

    record StopProof(boolean inputsReleased, boolean inputOwnerNone) {
        boolean terminalSafe() {
            return inputsReleased && inputOwnerNone;
        }
    }

    private record PendingCommand<T>(
            String name,
            long worldGeneration,
            long safetyEpoch,
            long deadlineNanos,
            Callable<T> action,
            CompletableFuture<T> result,
            Function<Throwable, T> failureMapper,
            BooleanSupplier completionAbandoned,
            Consumer<T> onAbandonedCompletion,
            LongSupplier nanoTime) {
        private boolean claimIfCurrent(long currentGeneration, long currentSafetyEpoch, long nowNanos) {
            if (result.isDone()) {
                return false;
            }
            if (RuntimeCallContext.deadlineReached(deadlineNanos, nowNanos)) {
                completeFailure(new CommandTimeoutException(name));
                return false;
            }
            if (worldGeneration != currentGeneration || safetyEpoch != currentSafetyEpoch) {
                completeFailure(new CommandInvalidatedException(name));
                return false;
            }
            return true;
        }

        private void runClaimed() {
            try {
                // Deadline remains an execution fence after the command has been claimed.
                if (RuntimeCallContext.deadlineReached(
                        deadlineNanos, nanoTime.getAsLong())) {
                    completeFailure(new CommandTimeoutException(name));
                    return;
                }
                T value = action.call();
                if (completionAbandoned.getAsBoolean()
                        || RuntimeCallContext.deadlineReached(
                                deadlineNanos, nanoTime.getAsLong())) {
                    onAbandonedCompletion.accept(value);
                    completeFailure(new CommandTimeoutException(name));
                } else if (!result.complete(value)) {
                    onAbandonedCompletion.accept(value);
                }
            } catch (Throwable failure) {
                completeFailure(failure);
            }
        }

        private void completeFailure(Throwable failure) {
            if (failureMapper == null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                result.complete(failureMapper.apply(failure));
            } catch (Throwable mappingFailure) {
                result.completeExceptionally(mappingFailure);
            }
        }
    }

    public record StopReceipt(
            String reason,
            long safetyEpoch,
            long worldGeneration,
            boolean inputsReleased,
            boolean inputOwnerNone,
            boolean locked,
            int discardedPendingStarts) {
    }

    @FunctionalInterface
    public interface EmergencyStopHandler {
        /** Runs on the Minecraft client thread before stop waiters are completed. */
        StopProgress stop(String reason, WorldSessionTracker.Snapshot session);
    }

    public enum StopProgress { COMPLETE, PENDING, FAILED }

    public static final class CommandTimeoutException extends RuntimeException {
        public CommandTimeoutException(String command) {
            super("command deadline expired: " + command);
        }
    }

    public static final class CommandInvalidatedException extends RuntimeException {
        public CommandInvalidatedException(String command) {
            super("command was invalidated: " + command);
        }
    }
}
