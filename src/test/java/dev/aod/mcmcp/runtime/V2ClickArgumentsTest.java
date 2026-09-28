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
                .sequence().cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.ATTACK);
        assertThat(V2ClickArguments.parse(Map.of("button", "right"))
                .sequence().cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.USE);
        assertThat(V2ClickArguments.parse(Map.of("button", "middle"))
                .sequence().cursor().next(false, false).inputs())
                .containsExactly(BoundedInputLease.Input.PICK);
    }

    @Test
    void repeatedClicksRequireAReleaseTickAndStayFinite() {
        var sequence = V2ClickArguments.parse(Map.of(
                "button", "middle", "count", 3,
                "hold_ticks", 1, "gap_ticks", 1)).sequence();
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

    @Test
    void exactTargetsAreOptionalButCannotBeMixedOrPartiallySpecified() {
        assertThat(V2ClickArguments.parse(Map.of("button", "right")).target()).isNull();
        assertThat(V2ClickArguments.parse(Map.of("button", "right",
                "x", 12, "y", 64, "z", -3, "block", "minecraft:lever")).target())
                .isEqualTo(new V2ClickArguments.BlockTarget(12, 64, -3, "minecraft:lever"));
        String entityRef = "AbCdef0123456789_-ABCDEF";
        assertThat(V2ClickArguments.parse(Map.of("button", "right",
                "entity_ref", entityRef, "entity_type", "minecraft:cow")).target())
                .isEqualTo(new V2ClickArguments.EntityRefTarget(entityRef, "minecraft:cow"));
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "right", "x", 12, "y", 64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "right", "x", 12, "y", 64, "z", -3,
                "entity_ref", entityRef)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "right", "entity_ref", "not-a-valid-ref")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "right", "entity_uuid", "9a7f26c8-6872-4e8b-a6f9-a6404e9afab9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2ClickArguments.parse(Map.of(
                "button", "right", "entity_type", "minecraft:cow")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
