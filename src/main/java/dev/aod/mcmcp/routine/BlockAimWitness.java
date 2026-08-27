package dev.aod.mcmcp.routine;

import java.util.Objects;

/** Exact planner-selected block face and point used by one block mutation. */
public record BlockAimWitness(
        BlockTarget block,
        Face face,
        double x,
        double y,
        double z) {
    public BlockAimWitness {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(face, "face");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || x < block.x() || x > block.x() + 1.0D
                || y < block.y() || y > block.y() + 1.0D
                || z < block.z() || z > block.z() + 1.0D) {
            throw new IllegalArgumentException("aim point must be finite and inside its block");
        }
    }

    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }
}
