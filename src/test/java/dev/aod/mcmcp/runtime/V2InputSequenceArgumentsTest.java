package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InputSequenceArgumentsTest {
    @Test
    void parsesSimultaneousStepsAndFiniteRepetition() {
        var sequence = V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("forward", "sneak"),
                        "hold_ticks", 2, "gap_ticks", 1, "repeat", 2),
                Map.of("inputs", List.of("use"), "hold_ticks", 1))));
        assertThat(sequence.sequence().totalTicks()).isEqualTo(7);
        assertThat(V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("pick"), "hold_ticks", 1))))
                .sequence().totalTicks()).isEqualTo(1);
    }

    @Test
    void coordinateStopConditionIsBoundedAndChecksPlayerPosition() {
        var request = V2InputSequenceArguments.parse(Map.of(
                "steps", List.of(Map.of("inputs", List.of("forward"),
                        "hold_ticks", 20)),
                "stop_when", Map.of("x", 3, "y", 64, "z", -2,
                        "radius", 0.8D)));
        var condition = (V2StopCondition.Position) request.stopWhen();
        assertThat(condition.reached(new Vec3(3.5D, 64.0D, -1.5D)))
                .isTrue();
        assertThat(condition.reached(new Vec3(1.5D, 64.0D, -1.5D)))
                .isFalse();
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of(
                "steps", List.of(Map.of("inputs", List.of("forward"),
                        "hold_ticks", 20)),
                "stop_when", Map.of("x", 3, "y", 64, "z", -2,
                        "radius", 100D))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOpposedOrDuplicateInputsBeforeAdmission() {
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("forward", "back"), "hold_ticks", 1)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("opposed");
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("use", "use"), "hold_ticks", 1)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("jump", "arbitrary_key"), "hold_ticks", 1)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown");
    }

    @Test
    void rejectsUnboundedDurationAndExtraKeys() {
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("use"), "hold_ticks", 1200, "repeat", 2)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2InputSequenceArguments.parse(Map.of("steps", List.of(
                Map.of("inputs", List.of("use"), "hold_ticks", 1,
                        "code", "anything")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
