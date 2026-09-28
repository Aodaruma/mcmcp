package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2BlockInteractArgumentsTest {
    @Test
    void oneBlockCanBeNamedWithoutAFilter() {
        var request = V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 1, "y", 64, "z", -2),
                "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(1);
        assertThat(request.accepts("example:machine")).isTrue();
        assertThat(request.advance()).isTrue();
    }

    @Test
    void rangeNeedsExplicitBlockFilterAndKeepsModIdentifiers() {
        var request = V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 1, "y", 64, "z", -2,
                "dx", 2, "block", "example:machine", "max_interactions", 2,
                "expected_after_properties", Map.of("powered", "true")),
                "minecraft:overworld");
        assertThat(request.region().size()).isEqualTo(3);
        assertThat(request.maxBlocks()).isEqualTo(2);
        assertThat(request.accepts("example:machine")).isTrue();
        assertThat(request.accepts("minecraft:lever")).isFalse();
        assertThatThrownBy(() -> V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 1, "y", 64, "z", -2, "dx", 1),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successRequiresARealStateChangeAndAllRequestedConditions() {
        var request = V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 0, "y", 64, "z", 0,
                "expected_after_block", "minecraft:lever",
                "expected_after_properties", Map.of("powered", "true")),
                "minecraft:overworld");
        var before = new BlockStateFingerprint("minecraft:lever", Map.of("powered", "false"));
        var after = new BlockStateFingerprint("minecraft:lever", Map.of("powered", "true"));
        assertThat(MinecraftV2BlockInteractDriver.matchesExpected(request, before, before))
                .isFalse();
        assertThat(MinecraftV2BlockInteractDriver.matchesExpected(request, before, after))
                .isTrue();
        assertThat(MinecraftV2BlockInteractDriver.matchesExpected(request, before,
                new BlockStateFingerprint("minecraft:lever", Map.of("powered", "false"))))
                .isFalse();
    }

    @Test
    void rejectsUnboundedOrUnknownRequests() {
        assertThatThrownBy(() -> V2BlockInteractArguments.parse(Map.of(
                "target", "entity", "x", 0, "y", 64, "z", 0),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 0, "y", 64, "z", 0,
                "max_ticks", 1201),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2BlockInteractArguments.parse(Map.of(
                "target", "block", "x", 0, "y", 64, "z", 0,
                "raw_packet", "open"),
                "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
    }
}
