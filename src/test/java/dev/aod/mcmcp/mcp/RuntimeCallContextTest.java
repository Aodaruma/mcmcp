package dev.aod.mcmcp.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RuntimeCallContextTest {
    @Test
    void cancellationIsAnImmediateWorkFence() {
        RuntimeCallContext context = RuntimeCallContext.withTimeout(Duration.ofSeconds(1));

        assertThat(context.requestId()).isNotBlank();
        assertThat(context.canBeginWork()).isTrue();

        context.cancel();

        assertThat(context.isCancelled()).isTrue();
        assertThat(context.canBeginWork()).isFalse();
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeCallContext.withTimeout(Duration.ZERO));
    }

    @Test
    void nanoDeadlinesRemainCorrectAcrossNegativeValuesAndSignedWraparound() {
        long timeout = Duration.ofSeconds(2).toNanos();
        long negativeNow = -1L;
        long nearWrap = Long.MAX_VALUE - 10L;
        long wrappedDeadline = RuntimeCallContext.deadlineAfter(nearWrap, 20L);

        assertThat(RuntimeCallContext.remainingNanos(
                RuntimeCallContext.deadlineAfter(negativeNow, timeout), negativeNow))
                .isEqualTo(timeout);
        assertThat(RuntimeCallContext.remainingNanos(wrappedDeadline, nearWrap)).isEqualTo(20L);
        assertThat(RuntimeCallContext.deadlineReached(wrappedDeadline, nearWrap)).isFalse();
        assertThat(RuntimeCallContext.deadlineReached(wrappedDeadline, wrappedDeadline)).isTrue();
    }

    @Test
    void evaluationExpectationFencesBothAbsenceAndTheExactActiveLease() {
        var control = new LeaseControl();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        var initiallyAbsent = RuntimeCallContext.EvaluationLeaseExpectation.absent(
                control.fenceSnapshot().revision());
        assertThat(initiallyAbsent.matches(control))
                .isTrue();
        control.forceLease(first);
        assertThat(initiallyAbsent.matches(control))
                .isFalse();
        var firstActive = RuntimeCallContext.EvaluationLeaseExpectation.active(
                first, control.fenceSnapshot().revision());
        assertThat(firstActive.matches(control))
                .isTrue();

        control.forceLease(second);
        assertThat(firstActive.matches(control))
                .isFalse();
        assertThat(RuntimeCallContext.EvaluationLeaseExpectation.unmanaged().matches(null))
                .isTrue();
    }

    @Test
    void absentExpectationRejectsAcquireReleaseAbaAtTheSameVisibleState() {
        var control = new LeaseControl();
        var staleAbsence = RuntimeCallContext.EvaluationLeaseExpectation.absent(
                control.fenceSnapshot().revision());

        control.forceLease(UUID.randomUUID());
        control.forceAbsent();

        assertThat(control.anyActive()).isFalse();
        assertThat(staleAbsence.matches(control)).isFalse();
        assertThat(RuntimeCallContext.EvaluationLeaseExpectation.absent(
                control.fenceSnapshot().revision()).matches(control)).isTrue();
    }

    @Test
    void releasedEvaluationReceiptCannotClaimAnIncompleteSafetyBoundary() {
        UUID leaseId = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() ->
                new EvaluationTurnControl.LeaseReceipt(
                        leaseId,
                        EvaluationTurnControl.LeaseState.RELEASED,
                        "turn_completed",
                        true,
                        false,
                        true,
                        true));
        assertThat(new EvaluationTurnControl.LeaseReceipt(
                leaseId,
                EvaluationTurnControl.LeaseState.ACTIVE,
                null,
                false,
                true,
                true,
                true).processIdentityBound()).isTrue();
    }

    private static final class LeaseControl implements EvaluationTurnControl {
        private UUID active;
        private long revision;

        @Override
        public CompletableFuture<LeaseReceipt> acquire(AcquireRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<LeaseReceipt> await(UUID leaseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<LeaseReceipt> release(UUID leaseId, ReleaseReason reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized FenceSnapshot fenceSnapshot() {
            return new FenceSnapshot(revision, active != null, active);
        }

        @Override
        public synchronized boolean active(UUID leaseId) {
            return leaseId != null && leaseId.equals(active);
        }

        @Override
        public synchronized boolean anyActive() {
            return active != null;
        }

        private synchronized void forceLease(UUID leaseId) {
            active = leaseId;
            revision++;
        }

        private synchronized void forceAbsent() {
            active = null;
            revision++;
        }
    }
}
