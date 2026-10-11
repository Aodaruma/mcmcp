package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2LookArgumentsTest {
    @Test
    void exactPointPreservesFractionsAndDoesNotAcceptInteractionOrMovement() {
        var request = V2LookArguments.parse(Map.of("x", 1.25, "y", 64.75, "z", -2.5));
        assertThat(request.x()).isEqualTo(1.25);
        assertThat(request.y()).isEqualTo(64.75);
        assertThat(request.z()).isEqualTo(-2.5);
        assertThat(request.maxTicks()).isEqualTo(100);
        for (String field : new String[]{"item", "advance", "button", "command"}) {
            assertThatThrownBy(() -> V2LookArguments.parse(Map.of("x", 1, "y", 64, "z", 2, field, true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnboundedTargetsAndTime() {
        for (double x : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            assertThatThrownBy(() -> new V2LookArguments(x, 64, 0, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new V2LookArguments(1, 64, 2, 601))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
