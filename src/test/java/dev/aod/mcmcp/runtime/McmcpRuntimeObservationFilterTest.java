package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.ObservationFilter;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeObservationFilterTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");

    @Test
    void parsesPositionBoundsAndFacesAlongsideExistingItemFilters() {
        var positionBounds = Map.<String, Object>of(
                "dimension", DIMENSION.value(),
                "min_x", -12,
                "min_y", 63,
                "min_z", 4,
                "max_x", 18,
                "max_y", 70,
                "max_z", 24);
        var filterInput = Map.<String, Object>of(
                "displayed_items", java.util.List.of("minecraft:wheat", "minecraft:wheat_seeds"),
                "faces", java.util.List.of("up", "north"),
                "position_bounds", positionBounds);

        ObservationFilter filter = ObservationFilterArguments.parse(
                Map.of("filter", filterInput));

        assertThat(filter.displayedItems()).containsExactlyInAnyOrder(
                new ResourceId("minecraft:wheat"),
                new ResourceId("minecraft:wheat_seeds"));
        assertThat(filter.positionBounds()).contains(new ObservationFilter.PositionBounds(
                DIMENSION, -12, 63, 4, 18, 70, 24));
        assertThat(filter.faces()).containsExactlyInAnyOrder(Face.UP, Face.NORTH);
        assertThat(filter.blockIds()).isEmpty();
        assertThat(filter.cropMature()).isEmpty();
    }

    @Test
    void rejectsUnknownUpperCaseAndDuplicateFaces() {
        assertThatThrownBy(() -> ObservationFilterArguments.parse(Map.of(
                "filter", Map.of("faces", java.util.List.of("front")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationFilterArguments.parse(Map.of(
                "filter", Map.of("faces", java.util.List.of("UP")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationFilterArguments.parse(Map.of(
                "filter", Map.of("faces", java.util.List.of("up", "up")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncompleteFractionalAndReversedPositionBounds() {
        var valid = new java.util.LinkedHashMap<String, Object>();
        valid.put("dimension", DIMENSION.value());
        valid.put("min_x", 0);
        valid.put("min_y", 63);
        valid.put("min_z", 0);
        valid.put("max_x", 2);
        valid.put("max_y", 64);
        valid.put("max_z", 2);

        var incomplete = new java.util.LinkedHashMap<>(valid);
        incomplete.remove("max_z");
        assertThatThrownBy(() -> parse(incomplete))
                .isInstanceOf(IllegalArgumentException.class);

        var fractional = new java.util.LinkedHashMap<>(valid);
        fractional.put("min_x", 0.5D);
        assertThatThrownBy(() -> parse(fractional))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integer");

        var reversed = new java.util.LinkedHashMap<>(valid);
        reversed.put("min_x", 3);
        assertThatThrownBy(() -> parse(reversed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void absentFilterKeepsTheCanonicalUnboundedValue() {
        assertThat(ObservationFilterArguments.parse(Map.of()))
                .isEqualTo(ObservationFilter.NONE)
                .satisfies(filter -> {
                    assertThat(filter.positionBounds()).isEqualTo(Optional.empty());
                    assertThat(filter.faces()).isEmpty();
                });
    }

    private static ObservationFilter parse(Map<String, Object> bounds) {
        return ObservationFilterArguments.parse(Map.of(
                "filter", Map.of("position_bounds", bounds)));
    }
}
