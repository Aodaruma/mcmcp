package dev.aod.mcmcp.routine;

import java.util.Objects;

public record NavigateToRequest(
        BlockTarget target,
        double horizontalToleranceBlocks,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "navigate_to";

    public NavigateToRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(horizontalToleranceBlocks)
                || horizontalToleranceBlocks < 0.25D
                || horizontalToleranceBlocks > 2.0D) {
            throw new IllegalArgumentException("horizontal tolerance must be in 0.25..2.0");
        }
        if (!bounds.contains(target) || bounds.maxTravelBlocks() < 1 || bounds.allowBreak()) {
            throw new IllegalArgumentException("navigate_to target/bounds are inconsistent");
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
