package dev.aodaruma.craftagent.mcp;

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
}
