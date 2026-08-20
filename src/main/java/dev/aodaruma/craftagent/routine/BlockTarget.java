package dev.aodaruma.craftagent.routine;

import java.util.Objects;

/** Minecraft-independent identity for the single block owned by stationary_break. */
public record BlockTarget(String dimension, int x, int y, int z) {
    private static final int HORIZONTAL_LIMIT = 30_000_000;

    public BlockTarget {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid dimension registry id");
        }
        if (Math.abs((long) x) > HORIZONTAL_LIMIT || Math.abs((long) z) > HORIZONTAL_LIMIT) {
            throw new IllegalArgumentException("target is outside Minecraft horizontal bounds");
        }
        if (y < -2_048 || y > 2_047) {
            throw new IllegalArgumentException("target is outside supported vertical bounds");
        }
    }
}
