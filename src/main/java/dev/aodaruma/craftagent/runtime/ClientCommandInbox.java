package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.safety.InputReleaseController;
import dev.aodaruma.craftagent.safety.LocalArmingState;
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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<String> pendingStopReason = new AtomicReference<>();
    private final AtomicLong safetyEpoch = new AtomicLong();
    private final Object admissionGate = new Object();
    private final InputReleaseController inputRelease;
    private final LocalArmingState armingState;
    private final EmergencyStopHandler emergencyStopHandler;
    private boolean accepting = true;
    /** Guarded by {@link #admissionGate}; returned idempotently once shutdown has completed. */
    private StopReceipt terminalStopReceipt;

    public ClientCommandInbox(InputReleaseController inputRelease, LocalArmingState armingState) {
        this(DEFAULT_CAPACITY, inputRelease, armingState, (reason, session) -> true);
    }

    public ClientCommandInbox(int capacity, InputReleaseController inputRelease, LocalArmingState armingState) {
        this(capacity, inputRelease, armingState, (reason, session) -> true);
    }

    public ClientCommandInbox(
            int capacity,
            InputReleaseController inputRelease,
            LocalArmingState armingState,
            EmergencyStopHandler emergencyStopHandler) {
        normalQueue = new ArrayBlockingQueue<>(capacity);
        controlQueue = new ArrayBlockingQueue<>(Math.max(8, Math.min(capacity, 32)));
        this.inputRelease = Objects.requireNonNull(inputRelease, "inputRelease");
        this.armingState = Objects.requireNonNull(armingState, "armingState");
        this.emergencyStopHandler = Objects.requireNonNull(emergencyStopHandler, "emergencyStopHandler");
    }

    public <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            long deadlineNanos,
            Callable<T> action) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        var result = new CompletableFuture<T>();
        synchronized (admissionGate) {
            if (!accepting) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("runtime is stopping"));
            }
            var command = new PendingCommand<>(
                    name, expectedWorldGeneration, safetyEpoch.get(), deadlineNanos, action, result);
            if (!normalQueue.offer(command)) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("client command inbox is full"));
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
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        var result = new CompletableFuture<T>();
        synchronized (admissionGate) {
            if (!accepting) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("runtime is stopping"));
            }
            var command = new PendingCommand<>(
                    name, expectedWorldGeneration, safetyEpoch.get(), deadlineNanos, action, result);
            if (!controlQueue.offer(command)) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("client control inbox is full"));
            }
        }
        return result;
    }

    public <T> CompletableFuture<T> submit(
            String name,
            long expectedWorldGeneration,
            Duration timeout,
            Callable<T> action) {
        return submit(name, expectedWorldGeneration, System.nanoTime() + timeout.toNanos(), action);
    }

    /** Safe to call from any thread; completion occurs only after client-thread release and lock. */
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
            return requestEmergencyStopLocked(reason);
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
        drainQueue(controlQueue, MAX_CONTROL_COMMANDS_PER_TICK, session.generation(), System.nanoTime());
    }

    /**
     * Runs Phase 1 read commands after vanilla has refreshed crosshair targeting for this tick.
     * Mutating routines use a separate pre-tick lane in later phases.
     */
    public void drainReadsPostTick(WorldSessionTracker.Snapshot session) {
        drainQueue(normalQueue, MAX_NORMAL_COMMANDS_PER_TICK, session.generation(), System.nanoTime());
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
            requestEmergencyStopLocked("client_shutdown");
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
            var reason = pendingStopReason.getAndSet(null);
            if (reason == null && stopWaiters.isEmpty()) {
                return;
            }
            if (reason == null) {
                reason = "operator_stop";
            }
            boolean inputsReleased = inputRelease.releaseAll(minecraft);
            try {
                inputsReleased &= emergencyStopHandler.stop(reason, session);
            }
            catch (RuntimeException | LinkageError failure) {
                inputsReleased = false;
            }
            armingState.lock(reason);
            int discarded = failPending(new CommandInvalidatedException("invalidated by emergency stop"));
            var receipt = new StopReceipt(
                    reason, safetyEpoch.get(), session.generation(), inputsReleased, true, discarded);
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
                .filter(command -> command.name().equals("start_routine"))
                .count());
        pending.forEach(command -> command.result.completeExceptionally(failure));
        return discardedStarts;
    }

    /** Caller must hold {@link #admissionGate}. */
    private CompletableFuture<StopReceipt> requestEmergencyStopLocked(String reason) {
        var future = new CompletableFuture<StopReceipt>();
        stopWaiters.add(future);
        safetyEpoch.incrementAndGet();
        pendingStopReason.set(sanitizeReason(reason));
        return future;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_stop";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }

    private record PendingCommand<T>(
            String name,
            long worldGeneration,
            long safetyEpoch,
            long deadlineNanos,
            Callable<T> action,
            CompletableFuture<T> result) {
        private boolean claimIfCurrent(long currentGeneration, long currentSafetyEpoch, long nowNanos) {
            if (result.isDone()) {
                return false;
            }
            if (nowNanos > deadlineNanos) {
                result.completeExceptionally(new CommandTimeoutException(name));
                return false;
            }
            if (worldGeneration != currentGeneration || safetyEpoch != currentSafetyEpoch) {
                result.completeExceptionally(new CommandInvalidatedException(name));
                return false;
            }
            return true;
        }

        private void runClaimed() {
            try {
                // Deadline remains an execution fence after the command has been claimed.
                if (System.nanoTime() > deadlineNanos) {
                    result.completeExceptionally(new CommandTimeoutException(name));
                    return;
                }
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }

    public record StopReceipt(
            String reason,
            long safetyEpoch,
            long worldGeneration,
            boolean inputsReleased,
            boolean locked,
            int discardedPendingStarts) {
    }

    @FunctionalInterface
    public interface EmergencyStopHandler {
        /** Runs on the Minecraft client thread before stop waiters are completed. */
        boolean stop(String reason, WorldSessionTracker.Snapshot session);
    }

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
