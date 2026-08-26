package dev.aod.mcmcp.agent.observation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OmnidirectionalDirectionsTest {
    @Test
    void fixedSetIsDeterministicUnitLengthAndCoversEveryWorldAxisDirection() {
        var first = OmnidirectionalDirections.all();
        var second = OmnidirectionalDirections.all();

        assertThat(first).hasSize(2_048).containsExactlyElementsOf(second);
        assertThat(new HashSet<>(first)).hasSize(2_048);
        assertThat(first).allSatisfy(direction -> assertThat(
                direction.x() * direction.x()
                        + direction.y() * direction.y()
                        + direction.z() * direction.z())
                .isCloseTo(1.0D, within(1.0E-12D)));

        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::x).min().orElseThrow())
                .isLessThan(-0.99D);
        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::x).max().orElseThrow())
                .isGreaterThan(0.99D);
        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::y).min().orElseThrow())
                .isLessThan(-0.999D);
        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::y).max().orElseThrow())
                .isGreaterThan(0.999D);
        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::z).min().orElseThrow())
                .isLessThan(-0.99D);
        assertThat(first.stream().mapToDouble(OmnidirectionalDirections.DirectionVector::z).max().orElseThrow())
                .isGreaterThan(0.99D);
    }

    @Test
    void latitudeBandsHaveEqualSolidAngleAndKnownFixedPhase() {
        var directions = OmnidirectionalDirections.all();
        double expectedStep = 2.0D / directions.size();
        for (int index = 1; index < directions.size(); index++) {
            assertThat(directions.get(index - 1).y() - directions.get(index).y())
                    .isCloseTo(expectedStep, within(1.0E-15D));
        }

        var northFirst = directions.getFirst();
        assertThat(northFirst.y()).isEqualTo(1.0D - 1.0D / 2_048.0D);
        assertThat(northFirst.z()).isZero();
        assertThat(northFirst.x()).isPositive();
    }

    @Test
    void completionTickCountAndRateBoundsAreExact() {
        assertThat(OmnidirectionalDirections.ticksToComplete(64)).isEqualTo(32);
        assertThat(OmnidirectionalDirections.ticksToComplete(256)).isEqualTo(8);
        assertThat(OmnidirectionalDirections.ticksToComplete(512)).isEqualTo(4);
        assertThatThrownBy(() -> OmnidirectionalDirections.ticksToComplete(63))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OmnidirectionalDirections.ticksToComplete(513))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
