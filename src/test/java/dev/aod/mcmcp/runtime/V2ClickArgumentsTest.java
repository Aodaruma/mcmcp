package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.BoundedInputLease;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2ClickArgumentsTest {
    @Test
    void mapsAllThreeMouseButtonsToBoundedLogicalInputs() {
        assertThat(V2ClickArguments.parse(Map.of("button", "left"))
                .cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.ATTACK);
        assertThat(V2ClickArguments.parse(Map.of("button", "right"))
                .cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.USE);
        assertThat(V2ClickArguments.parse(Map.of("button", "middle"))
                .cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.PICK);
    }

    @Test
    void repeatedClicksRequireAReleaseTickAndStayFinite() {
        var sequence = V2ClickArguments.parse(Map.of(
                "button", "middle", "count", 3,
                "hold_ticks", 1, "gap_ticks", 1));
        var cursor = sequence.cursor();
        assertThat(sequence.totalTicks()).isEqualTo(5);
        assertThat(cursor.next(false, false).inputs()).containsExactly(BoundedInputLease.Input.PICK);
        assertThat(cursor.next(false, false).inputs()).isEmpty();
        assertThat(cursor.next(false, false).inputs()).containsExactly(BoundedInputLease.Input.PICK);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "middle", "count", 2, "gap_ticks", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "left", "count", 33)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
