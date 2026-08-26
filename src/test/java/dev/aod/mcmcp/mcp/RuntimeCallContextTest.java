package dev.aod.mcmcp.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
