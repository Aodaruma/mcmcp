package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaitExecutionTest {
    @Test
    void finiteWaitCompletesOnExactlyItsLastTickAndResetsForTheNextOccurrence() {
        var waiting = new WaitExecution(null, null);
        var first = new ActionDsl.WaitTicks("first", 3);
        waiting.begin(first);
        assertThat(waiting.tick(null, null, first).complete()).isFalse();
        assertThat(waiting.tick(null, null, first).complete()).isFalse();
        assertThat(waiting.tick(null, null, first).complete()).isTrue();

        var second = new ActionDsl.WaitTicks("second", 1);
        waiting.begin(second);
        assertThat(waiting.tick(null, null, second).complete()).isTrue();
    }
}
