package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2ScriptArgumentsTest {
    @Test
    void parsesAFiniteSourceBudget() {
        var parsed = V2ScriptArguments.parse(Map.of("source", "move(x=1);",
                "max_calls", 3, "max_duration_ticks", 60));
        assertThat(parsed.source()).isEqualTo("move(x=1);");
        assertThat(parsed.calls()).isEqualTo(3);
        assertThat(parsed.maxDurationTicks()).isEqualTo(60);
        assertThatThrownBy(() -> V2ScriptArguments.parse(Map.of("source", "move();",
                "max_calls", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ScriptArguments.parse(Map.of("source", "move();",
                "max_duration_ticks", 72_001))).isInstanceOf(IllegalArgumentException.class);
    }
}
