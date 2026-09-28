package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.NavCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A bounded, inclusive coordinate box shared by v2 break and placement jobs. */
public record BlockWorkRegion(String dimension, int x, int y, int z,
                              int dx, int dy, int dz) {
    public static final int MAX_CELLS = 4_096;

    public BlockWorkRegion {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
        Math.addExact(x, dx);
        Math.addExact(y, dy);
        Math.addExact(z, dz);
        long width = axisLength(dx);
        long height = axisLength(dy);
        long depth = axisLength(dz);
        if (width > MAX_CELLS || height > MAX_CELLS || depth > MAX_CELLS
                || width * height * depth > MAX_CELLS) {
            throw new IllegalArgumentException("block work region exceeds 4096 cells");
        }
    }

    public int size() {
        return Math.toIntExact(axisLength(dx) * axisLength(dy) * axisLength(dz));
    }

    public boolean contains(NavCell cell) {
        Objects.requireNonNull(cell, "cell");
        return dimension.equals(cell.dimension())
                && between(cell.x(), x, x + dx)
                && between(cell.y(), y, y + dy)
                && between(cell.z(), z, z + dz);
    }

    /** X changes fastest, then Z, then Y; negative offsets walk in the requested direction. */
    public List<NavCell> cells() {
        var result = new ArrayList<NavCell>(size());
        int stepX = Integer.compare(dx, 0);
        int stepY = Integer.compare(dy, 0);
        int stepZ = Integer.compare(dz, 0);
        for (int iy = 0; iy <= Math.abs((long) dy); iy++) {
            for (int iz = 0; iz <= Math.abs((long) dz); iz++) {
                for (int ix = 0; ix <= Math.abs((long) dx); ix++) {
                    result.add(new NavCell(dimension,
                            x + ix * stepX, y + iy * stepY, z + iz * stepZ));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Complete each vertical column before advancing along X/Z. */
    public List<NavCell> cellsByColumn() {
        var result = new ArrayList<NavCell>(size());
        int stepX = Integer.compare(dx, 0);
        int stepY = Integer.compare(dy, 0);
        int stepZ = Integer.compare(dz, 0);
        for (int iz = 0; iz <= Math.abs((long) dz); iz++) {
            for (int ix = 0; ix <= Math.abs((long) dx); ix++) {
                for (int iy = 0; iy <= Math.abs((long) dy); iy++) {
                    result.add(new NavCell(dimension,
                            x + ix * stepX, y + iy * stepY, z + iz * stepZ));
                }
            }
        }
        return List.copyOf(result);
    }

    private static long axisLength(int delta) {
        return Math.abs((long) delta) + 1L;
    }

    private static boolean between(int value, int start, int end) {
        return value >= Math.min(start, end) && value <= Math.max(start, end);
    }
}
