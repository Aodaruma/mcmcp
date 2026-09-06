package dev.aod.mcmcp.agent.mining;

import dev.aod.mcmcp.agent.dsl.ActionDsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Finite horizontal excavation footprint, independent of world data. */
public final class TunnelGeometry {
    public static final int MAX_LENGTH_BLOCKS = 160;
    public static final int MAX_BRANCH_LENGTH_BLOCKS = 7;
    public static final int MIN_BRANCH_SPACING_BLOCKS = 3;
    public static final int MAX_BRANCH_SPACING_BLOCKS = 16;
    public static final int MAX_EXCAVATION_CELLS = 1024;

    private TunnelGeometry() { }

    public static Plan plan(ActionDsl.Position entrance, ActionDsl.BlockFace outwardFace,
            int lengthBlocks, boolean branches, int branchLengthBlocks, int branchSpacingBlocks) {
        Objects.requireNonNull(entrance, "entrance");
        Objects.requireNonNull(outwardFace, "outwardFace");
        requireRange(lengthBlocks, 1, MAX_LENGTH_BLOCKS);
        if (branches) {
            requireRange(branchLengthBlocks, 1, MAX_BRANCH_LENGTH_BLOCKS);
            requireRange(branchSpacingBlocks, MIN_BRANCH_SPACING_BLOCKS, MAX_BRANCH_SPACING_BLOCKS);
        } else if (branchLengthBlocks != 0 || branchSpacingBlocks != 0) {
            throw new IllegalArgumentException("straight tunnel must not declare branches");
        }
        int dx = switch (outwardFace) {
            case WEST -> 1;
            case EAST -> -1;
            case NORTH, SOUTH -> 0;
            default -> throw new IllegalArgumentException("tunnel entrance face must be horizontal");
        };
        int dz = switch (outwardFace) {
            case NORTH -> 1;
            case SOUTH -> -1;
            case EAST, WEST -> 0;
            default -> throw new IllegalArgumentException("tunnel entrance face must be horizontal");
        };
        Cell first = new Cell(entrance.dimension(), entrance.x(), entrance.y(), entrance.z());
        Cell start = first.offset(-dx, 0, -dz);
        start.head();
        List<Cell> route = new ArrayList<>();
        Set<Cell> cells = new LinkedHashSet<>();
        for (int index = 1; index <= lengthBlocks; index++) {
            Cell main = start.offset(dx * index, 0, dz * index);
            add(route, cells, main);
            if (branches && index % branchSpacingBlocks == 0) {
                // Left then right, returning along the exact earlier branch before advancing.
                for (int side : new int[] { 1, -1 }) {
                    int sideX = dz * side;
                    int sideZ = -dx * side;
                    for (int step = 1; step <= branchLengthBlocks; step++) {
                        add(route, cells, main.offset(sideX * step, 0, sideZ * step));
                    }
                    for (int step = branchLengthBlocks - 1; step >= 0; step--) {
                        route.add(main.offset(sideX * step, 0, sideZ * step));
                    }
                }
            }
        }
        return new Plan(start, route, cells);
    }

    private static void add(List<Cell> route, Set<Cell> cells, Cell cell) {
        cell.head(); // Validate the upper block too, before any world operation can begin.
        route.add(cell);
        cells.add(cell);
        if (cells.size() > MAX_EXCAVATION_CELLS) {
            throw new IllegalArgumentException("tunnel excavation footprint exceeds limit");
        }
    }

    private static void requireRange(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("tunnel parameter outside bounds");
    }

    public record Cell(String dimension, int x, int y, int z) {
        public Cell {
            Objects.requireNonNull(dimension, "dimension");
            if (dimension.isEmpty() || Math.abs((long) x) > 30_000_000L
                    || Math.abs((long) z) > 30_000_000L || y < -2048 || y > 2048) {
                throw new IllegalArgumentException("tunnel cell outside coordinate bounds");
            }
        }

        public Cell offset(int dx, int dy, int dz) {
            return new Cell(dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
        }

        public Cell head() { return offset(0, 1, 0); }

        public ActionDsl.Position position() { return new ActionDsl.Position(dimension, x, y, z); }

        public boolean adjacent(Cell other) {
            return dimension.equals(other.dimension) && y == other.y
                    && Math.abs((long) x - other.x) + Math.abs((long) z - other.z) == 1L;
        }
    }

    public record Plan(Cell startFeet, List<Cell> route, Set<Cell> excavationCells) {
        public Plan {
            Objects.requireNonNull(startFeet, "startFeet");
            route = List.copyOf(Objects.requireNonNull(route, "route"));
            excavationCells = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(excavationCells, "excavationCells")));
            if (route.isEmpty() || excavationCells.isEmpty()
                    || excavationCells.size() > MAX_EXCAVATION_CELLS
                    || route.size() > 2 * MAX_EXCAVATION_CELLS
                    || excavationCells.contains(startFeet)) {
                throw new IllegalArgumentException("invalid tunnel route bounds");
            }
            Cell previous = startFeet;
            Set<Cell> visited = new LinkedHashSet<>();
            for (Cell cell : route) {
                if (!previous.adjacent(cell)) throw new IllegalArgumentException("tunnel route must use adjacent cells");
                cell.head();
                visited.add(cell);
                previous = cell;
            }
            if (!visited.equals(excavationCells)) throw new IllegalArgumentException("tunnel route and footprint differ");
        }

        public int maxBreaks() { return Math.multiplyExact(excavationCells.size(), 2); }

        public int travelBlocks() { return route.size(); }

        public boolean containsBlock(Cell block) {
            if (!startFeet.dimension.equals(block.dimension)) return false;
            if (block.y == startFeet.y) return excavationCells.contains(block);
            return block.y == startFeet.y + 1 && excavationCells.contains(block.offset(0, -1, 0));
        }
    }
}
