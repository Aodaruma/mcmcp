package dev.aod.mcmcp.routine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Matches an authoritative mutation result while allowing only the vanilla state evolution that
 * can legitimately happen after the requested transition and before the client observes it.
 */
public final class SemanticMutationPostcondition {
    private static final Set<String> TILLABLE_BLOCKS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path");
    private static final Set<String> HOE_ITEMS = Set.of(
            "minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:iron_hoe",
            "minecraft:golden_hoe", "minecraft:diamond_hoe", "minecraft:netherite_hoe");
    private static final BlockStateFingerprint INITIAL_FARMLAND =
            new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0"));
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final Map<String, Crop> CROPS = Map.of(
            "minecraft:wheat_seeds", new Crop("minecraft:wheat", 7),
            "minecraft:carrot", new Crop("minecraft:carrots", 7),
            "minecraft:potato", new Crop("minecraft:potatoes", 7),
            "minecraft:beetroot_seeds", new Crop("minecraft:beetroots", 3));

    private SemanticMutationPostcondition() {
    }

    public static boolean matches(
            SemanticActionRequest request, BlockStateFingerprint liveState) {
        return matches(request, expectedAfter(request), liveState);
    }

    /** Uses the dispatch-frozen exact result for ordinary mutations. */
    public static boolean matches(
            SemanticActionRequest request,
            BlockStateFingerprint dispatchedExpected,
            BlockStateFingerprint liveState) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(dispatchedExpected, "dispatchedExpected");
        Objects.requireNonNull(liveState, "liveState");
        BlockStateFingerprint expected = expectedAfter(request);
        if (dispatchedExpected.matches(liveState)) {
            return true;
        }
        if (request instanceof UseItemOnBlockRequest use && closedTill(use)) {
            return "minecraft:farmland".equals(liveState.blockId())
                    && integerPropertyInRange(liveState, "moisture", 0, 7);
        }
        if (request instanceof PlaceBlockRequest place) {
            Crop crop = CROPS.get(place.item());
            if (crop != null
                    && AIR.equals(place.expectedBefore())
                    && expected.equals(new BlockStateFingerprint(
                            crop.blockId(), Map.of("age", "0")))) {
                return crop.blockId().equals(liveState.blockId())
                        && integerPropertyInRange(liveState, "age", 0, crop.maxAge());
            }
        }
        return false;
    }

    private static boolean closedTill(UseItemOnBlockRequest request) {
        return TILLABLE_BLOCKS.contains(request.expectedBefore().blockId())
                && request.expectedBefore().properties().isEmpty()
                && HOE_ITEMS.contains(request.item())
                && INITIAL_FARMLAND.equals(request.expectedAfter());
    }

    private static BlockStateFingerprint expectedAfter(SemanticActionRequest request) {
        return switch (request) {
            case BreakBlockRequest value -> value.expectedAfter();
            case PlaceBlockRequest value -> value.expectedAfter();
            case UseItemOnBlockRequest value -> value.expectedAfter();
            case InteractBlockRequest value -> value.expectedAfter();
            default -> throw new IllegalArgumentException(
                    "request is not a finite block mutation");
        };
    }

    private static boolean integerPropertyInRange(
            BlockStateFingerprint state, String property, int minimum, int maximum) {
        String value = state.properties().get(property);
        if (value == null) return false;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= minimum && parsed <= maximum;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private record Crop(String blockId, int maxAge) {
    }
}
