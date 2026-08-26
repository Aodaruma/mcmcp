package dev.aodaruma.craftagent.routine;

/** One exact, audited normal-use transition on the targeted block. */
public record UseItemOnBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        String item,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "use_item_on_block";

    public UseItemOnBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        item = PlaceBlockRequest.requireRegistryId(item, "item");
        if (expectedBefore.equals(expectedAfter)) {
            throw new IllegalArgumentException("use_item_on_block requires a state transition");
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
