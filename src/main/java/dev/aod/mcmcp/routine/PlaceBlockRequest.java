package dev.aod.mcmcp.routine;

import java.util.Objects;

public record PlaceBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        String item,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "place_block";

    public PlaceBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        item = requireRegistryId(item, "item");
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String requireRegistryId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid " + name + " registry id");
        }
        return value;
    }
}
