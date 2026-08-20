package dev.aodaruma.craftagent.routine;

public record InteractBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "interact_block";

    public InteractBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        if (!expectedBefore.blockId().equals(expectedAfter.blockId())
                || expectedAfter.properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "interact_block requires a same-block property transition");
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
