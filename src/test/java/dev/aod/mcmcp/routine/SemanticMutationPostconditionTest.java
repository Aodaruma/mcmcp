package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticMutationPostconditionTest {
    private static final BlockTarget TARGET =
            new BlockTarget("minecraft:overworld", 1, 64, 1);
    private static final ActionBounds BOUNDS =
            new ActionBounds(TARGET.dimension(), TARGET, TARGET, 0, 5, false);

    @Test
    void tillAcceptsLaterHydrationButNotAReplacementBlock() {
        var request = new UseItemOnBlockRequest(
                TARGET,
                new BlockStateFingerprint("minecraft:dirt", Map.of()),
                "minecraft:iron_hoe",
                new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0")),
                BOUNDS);

        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "7"))))
                .isTrue();
        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint("minecraft:dirt", Map.of())))
                .isFalse();
    }

    @Test
    void wheatPlacementAcceptsGrowthButNotAnotherCropOrInvalidAge() {
        var request = new PlaceBlockRequest(
                TARGET,
                new BlockStateFingerprint("minecraft:air", Map.of()),
                "minecraft:wheat_seeds",
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")),
                BOUNDS);

        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "7"))))
                .isTrue();
        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint("minecraft:carrots", Map.of("age", "7"))))
                .isFalse();
        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "8"))))
                .isFalse();

        var nonAirPrecondition = new PlaceBlockRequest(
                TARGET,
                new BlockStateFingerprint("minecraft:stone", Map.of()),
                "minecraft:wheat_seeds",
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")),
                BOUNDS);
        assertThat(SemanticMutationPostcondition.matches(
                nonAirPrecondition,
                new BlockStateFingerprint("minecraft:wheat", Map.of("age", "7"))))
                .isFalse();
    }

    @Test
    void ordinaryMutationStillUsesTheRequestedPropertySubset() {
        var request = new InteractBlockRequest(
                TARGET,
                new BlockStateFingerprint("minecraft:oak_fence_gate", Map.of("open", "false")),
                new BlockStateFingerprint("minecraft:oak_fence_gate", Map.of("open", "true")),
                BOUNDS);

        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint(
                        "minecraft:oak_fence_gate",
                        Map.of("open", "true", "facing", "north"))))
                .isTrue();
        assertThat(SemanticMutationPostcondition.matches(
                request,
                new BlockStateFingerprint(
                        "minecraft:oak_fence_gate",
                        Map.of("open", "false", "facing", "north"))))
                .isFalse();

        var dispatched = new BlockStateFingerprint(
                "minecraft:oak_fence_gate", Map.of("open", "true", "facing", "north"));
        assertThat(SemanticMutationPostcondition.matches(
                request,
                dispatched,
                new BlockStateFingerprint(
                        "minecraft:oak_fence_gate",
                        Map.of("open", "true", "facing", "south"))))
                .isFalse();
    }
}
