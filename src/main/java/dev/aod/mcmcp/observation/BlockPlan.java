package dev.aod.mcmcp.observation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized, immutable block-plan phase shared by comparison and bounded application.
 *
 * <p>{@link Expected#sourceState()} retains the wire state before spatial transformation;
 * {@link Expected#state()} is the safely transformed state used for world comparison and future
 * application. Keeping both preserves the Phase 1 canonical plan hash while making it impossible
 * for an executor to accidentally compare an untransformed directional state.</p>
 */
public record BlockPlan(
        BlockPosition anchor,
        Transform transform,
        List<Expected> expected,
        boolean includeMatches,
        String hash) {
    public static final int MAX_EXPECTED_BLOCKS = 512;

    public BlockPlan {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(transform, "transform");
        expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
        if (expected.isEmpty() || expected.size() > MAX_EXPECTED_BLOCKS) {
            throw new IllegalArgumentException(
                    "block plan must contain 1.." + MAX_EXPECTED_BLOCKS + " expected entries");
        }
        var ids = new HashSet<String>();
        var positions = new HashSet<BlockPosition>();
        for (var entry : expected) {
            if (!ids.add(entry.id()) || !positions.add(entry.worldPosition())) {
                throw new IllegalArgumentException("block plan ids and world positions must be unique");
            }
            if (!anchor.dimension().equals(entry.worldPosition().dimension())) {
                throw new IllegalArgumentException("block plan entries must use the anchor dimension");
            }
            if (!transform.apply(entry.rawOffset()).equals(entry.transformedOffset())) {
                throw new IllegalArgumentException("block plan entry has an inconsistent transformed offset");
            }
            long expectedX = (long) anchor.x() + entry.transformedOffset().x();
            long expectedY = (long) anchor.y() + entry.transformedOffset().y();
            long expectedZ = (long) anchor.z() + entry.transformedOffset().z();
            if (expectedX != entry.worldPosition().x()
                    || expectedY != entry.worldPosition().y()
                    || expectedZ != entry.worldPosition().z()) {
                throw new IllegalArgumentException("block plan entry has an inconsistent world position");
            }
        }
        Objects.requireNonNull(hash, "hash");
        if (!hash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid block-plan hash");
        }
    }

    /** Parses and normalizes the existing compare_block_plan wire shape. */
    public static BlockPlan parse(Map<String, Object> arguments) {
        return BlockPlanParser.parse(arguments);
    }

    /** Returns the normalized entry or rejects an operation referencing an undeclared id. */
    public Expected requireExpected(String id) {
        Objects.requireNonNull(id, "id");
        return expected.stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BlockPlanValidationException(
                        "unknown_plan_entry", "expected", "no expected entry has id " + id,
                        Map.of("id", id)));
    }

    public record Expected(
            String id,
            Offset rawOffset,
            Offset transformedOffset,
            BlockPosition worldPosition,
            BlockStateView sourceState,
            BlockStateView state,
            boolean required) {
        public Expected {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(rawOffset, "rawOffset");
            Objects.requireNonNull(transformedOffset, "transformedOffset");
            Objects.requireNonNull(worldPosition, "worldPosition");
            Objects.requireNonNull(sourceState, "sourceState");
            Objects.requireNonNull(state, "state");
            if (!sourceState.block().equals(state.block())) {
                throw new IllegalArgumentException("a spatial transform must not change the block id");
            }
        }

        /** Expected properties are required-match constraints against a live complete state. */
        public boolean matches(BlockStateView actual) {
            Objects.requireNonNull(actual, "actual");
            if (!state.block().equals(actual.block())) {
                return false;
            }
            return state.properties().entrySet().stream()
                    .allMatch(property -> property.getValue().equals(
                            actual.properties().get(property.getKey())));
        }
    }

    public record Offset(int x, int y, int z) {
    }

    /** Wire-level mirror followed by clockwise Y-axis rotation. */
    public record Transform(int rotation, String mirror) {
        private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
        private static final Set<String> MIRRORS = Set.of("none", "x", "z");

        public Transform {
            if (!ROTATIONS.contains(rotation)) {
                throw new BlockPlanValidationException(
                        "invalid_plan_transform", "transform.rotation",
                        "rotation must be 0, 90, 180, or 270",
                        Map.of("rotation", rotation));
            }
            Objects.requireNonNull(mirror, "mirror");
            if (!MIRRORS.contains(mirror)) {
                throw new BlockPlanValidationException(
                        "invalid_plan_transform", "transform.mirror",
                        "mirror must be none, x, or z",
                        Map.of("mirror", mirror));
            }
        }

        public Offset apply(Offset input) {
            Objects.requireNonNull(input, "input");
            int x = "x".equals(mirror) ? -input.x() : input.x();
            int z = "z".equals(mirror) ? -input.z() : input.z();
            return switch (rotation) {
                case 0 -> new Offset(x, input.y(), z);
                case 90 -> new Offset(-z, input.y(), x);
                case 180 -> new Offset(-x, input.y(), -z);
                case 270 -> new Offset(z, input.y(), -x);
                default -> throw new AssertionError(rotation);
            };
        }

        /** Applies the same transform to explicit BlockState property constraints. */
        public BlockStateView apply(BlockStateView state) {
            return BlockPlanStateTransformer.transform(state, this);
        }
    }
}
