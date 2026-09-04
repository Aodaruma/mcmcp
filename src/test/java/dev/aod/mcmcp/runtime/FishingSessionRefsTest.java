package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FishingSessionRefsTest {
    @Test
    void refIsWorldBoundShortLivedAndSingleUse() {
        var refs = new FishingSessionRefs();
        UUID world = UUID.randomUUID();
        UUID bobber = UUID.randomUUID();
        String ref = refs.issue(
                world, "minecraft:overworld", bobber,
                "main_hand", "minecraft:fishing_rod", 100L);

        assertThat(ref).matches("[A-Za-z0-9_-]{24}");
        assertThat(refs.resolve(ref, world, "minecraft:overworld", 1_300L)).isPresent();
        assertThat(refs.consume(ref, world, "minecraft:overworld", 1_300L)).isPresent();
        assertThat(refs.resolve(ref, world, "minecraft:overworld", 1_300L)).isEmpty();

        String expired = refs.issue(
                world, "minecraft:overworld", bobber,
                "main_hand", "minecraft:fishing_rod", 100L);
        assertThat(refs.resolve(
                expired, world, "minecraft:overworld",
                101L + FishingSessionRefs.TTL_TICKS)).isEmpty();
    }
}
