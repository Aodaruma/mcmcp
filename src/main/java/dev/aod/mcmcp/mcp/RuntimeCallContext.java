package dev.aod.mcmcp.mcp;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-call generation, deadline and cancellation signal shared with the runtime queue. */
public final class RuntimeCallContext {
    private final String requestId;
    private final long deadlineNanos;
    private final EvaluationLeaseExpectation evaluationLeaseExpectation;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private RuntimeCallContext(
            String requestId,
            long deadlineNanos,
            EvaluationLeaseExpectation evaluationLeaseExpectation) {
        this.requestId = requestId;
        this.deadlineNanos = deadlineNanos;
        this.evaluationLeaseExpectation = Objects.requireNonNull(
                evaluationLeaseExpectation, "evaluationLeaseExpectation");
    }

    public static RuntimeCallContext withTimeout(Duration timeout) {
        return withTimeout(timeout, EvaluationLeaseExpectation.unmanaged());
    }

    public static RuntimeCallContext withTimeout(
            Duration timeout,
            EvaluationLeaseExpectation evaluationLeaseExpectation) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(evaluationLeaseExpectation, "evaluationLeaseExpectation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long now = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        }
        catch (ArithmeticException ignored) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long deadline = deadlineAfter(now, timeoutNanos);
        return new RuntimeCallContext(
                UUID.randomUUID().toString(), deadline, evaluationLeaseExpectation);
    }

    public String requestId() {
        return requestId;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public EvaluationLeaseExpectation evaluationLeaseExpectation() {
        return evaluationLeaseExpectation;
    }

    /** Revalidates the HTTP admission decision against the live runtime guard. */
    public boolean evaluationLeaseCurrent(EvaluationTurnControl control) {
        return evaluationLeaseExpectation.matches(control);
    }

    public long remainingNanos() {
        return remainingNanos(deadlineNanos, System.nanoTime());
    }

    public boolean isExpired() {
        return remainingNanos() == 0L;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** The runtime must check this immediately before queued work or a side effect. */
    public boolean canBeginWork() {
        return !isCancelled() && !isExpired();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public static long deadlineAfter(long nowNanos, long timeoutNanos) {
        if (timeoutNanos <= 0L) {
            throw new IllegalArgumentException("timeoutNanos must be positive");
        }
        return nowNanos + timeoutNanos;
    }

    public static boolean deadlineReached(long deadlineNanos, long nowNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }

    static long remainingNanos(long deadlineNanos, long nowNanos) {
        long remaining = deadlineNanos - nowNanos;
        return remaining > 0L ? remaining : 0L;
    }

    /** Exact lease state observed when the HTTP request crossed its authentication boundary. */
    public record EvaluationLeaseExpectation(Mode mode, UUID leaseId, long fenceRevision) {
        public EvaluationLeaseExpectation {
            Objects.requireNonNull(mode, "mode");
            if ((mode == Mode.ACTIVE) != (leaseId != null)) {
                throw new IllegalArgumentException(
                        "only an active evaluation expectation carries a lease ID");
            }
            if ((mode == Mode.UNMANAGED) != (fenceRevision < 0L)) {
                throw new IllegalArgumentException(
                        "managed evaluation expectations require a non-negative fence revision");
            }
        }

        public static EvaluationLeaseExpectation unmanaged() {
            return new EvaluationLeaseExpectation(Mode.UNMANAGED, null, -1L);
        }

        public static EvaluationLeaseExpectation absent(long fenceRevision) {
            return new EvaluationLeaseExpectation(Mode.ABSENT, null, fenceRevision);
        }

        public static EvaluationLeaseExpectation active(UUID leaseId, long fenceRevision) {
            return new EvaluationLeaseExpectation(
                    Mode.ACTIVE,
                    Objects.requireNonNull(leaseId, "leaseId"),
                    fenceRevision);
        }

        public boolean matches(EvaluationTurnControl control) {
            if (mode == Mode.UNMANAGED) {
                return true;
            }
            if (control == null) {
                return false;
            }
            var current = control.fenceSnapshot();
            if (current.revision() != fenceRevision) {
                return false;
            }
            return switch (mode) {
                case UNMANAGED -> true;
                case ABSENT -> !current.isolationActive();
                case ACTIVE -> current.accepts(leaseId);
            };
        }

        public enum Mode {
            UNMANAGED,
            ABSENT,
            ACTIVE
        }
    }
}
