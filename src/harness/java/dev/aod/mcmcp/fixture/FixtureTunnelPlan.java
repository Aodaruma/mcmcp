package dev.aod.mcmcp.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Pure, finite fixture geometry and post-run audit; never calls the gameplay implementation. */
final class FixtureTunnelPlan {
    static final Cell MIN = new Cell(256, 196, 248);
    static final Cell MAX = new Cell(418, 203, 264);
    static final Cell START = new Cell(257, 200, 256);
    static final Cell ENTRANCE = new Cell(258, 200, 256);
    static final Cell GAP = new Cell(261, 200, 256);
    static final int VOLUME_SIZE = 163 * 8 * 17;

    private FixtureTunnelPlan() { }

    static Plan forMode(FixturePhase5Mode mode) {
        if (!mode.tunnel()) throw new IllegalArgumentException("not a tunnel fixture mode");
        int length = mode == FixturePhase5Mode.TUNNEL_STRAIGHT160 ? 160 : 16;
        boolean branches = mode == FixturePhase5Mode.TUNNEL_BRANCHES;
        var route = new ArrayList<Cell>();
        var feet = new LinkedHashSet<Cell>();
        for (int step = 1; step <= length; step++) {
            Cell main = START.offset(step, 0, 0);
            route.add(main);
            feet.add(main);
            if (branches && step % 4 == 0) {
                for (int sign : new int[] {-1, 1}) {
                    for (int depth = 1; depth <= 3; depth++) {
                        Cell branch = main.offset(0, 0, sign * depth);
                        route.add(branch);
                        feet.add(branch);
                    }
                    for (int depth = 2; depth >= 0; depth--)
                        route.add(main.offset(0, 0, sign * depth));
                }
            }
        }
        var excavation = new LinkedHashSet<Cell>();
        feet.forEach(cell -> { excavation.add(cell); excavation.add(cell.above()); });
        var baseline = new LinkedHashMap<Cell, Material>();
        for (int x = MIN.x; x <= MAX.x; x++) {
            for (int y = MIN.y; y <= MAX.y; y++) {
                for (int z = MIN.z; z <= MAX.z; z++) {
                    Cell cell = new Cell(x, y, z);
                    Material material;
                    if (x == MIN.x || x == MAX.x || z == MIN.z || z == MAX.z || y == MIN.y || y == MAX.y)
                        material = Material.BEDROCK;
                    else if (y == 199) material = Material.SEA_LANTERN;
                    else if (y == 202 || y >= 200 && x >= ENTRANCE.x) material = Material.STONE;
                    else material = Material.AIR;
                    if (mode == FixturePhase5Mode.TUNNEL_HAZARD && x == GAP.x && z == GAP.z
                            && y >= 197 && y <= 199) material = Material.AIR;
                    baseline.put(cell, material);
                }
            }
        }
        return new Plan(mode, length, branches, List.copyOf(route), List.copyOf(feet),
                Set.copyOf(excavation), Map.copyOf(baseline));
    }

    static boolean contains(Cell cell) {
        return cell.x >= MIN.x && cell.x <= MAX.x && cell.y >= MIN.y && cell.y <= MAX.y
                && cell.z >= MIN.z && cell.z <= MAX.z;
    }

    static Audit audit(Plan plan, Function<Cell, Material> read, double x, double y, double z) {
        int outsideChanged = 0, baselineChanged = 0;
        for (var entry : plan.baseline.entrySet()) {
            if (read.apply(entry.getKey()) != entry.getValue()) {
                baselineChanged++;
                if (!plan.excavation.contains(entry.getKey())) outsideChanged++;
            }
        }
        int completed = 0, prefix = 0, partial = 0, invalid = 0;
        boolean prefixOpen = true;
        for (Cell cell : plan.feet) {
            Material lower = read.apply(cell), upper = read.apply(cell.above());
            boolean clear = lower == Material.AIR && upper == Material.AIR;
            if (clear) completed++;
            if (prefixOpen && clear) prefix++;
            else prefixOpen = false;
            if ((lower == Material.AIR) != (upper == Material.AIR)) partial++;
            if (lower != Material.AIR && lower != Material.STONE) invalid++;
            if (upper != Material.AIR && upper != Material.STONE) invalid++;
        }
        Cell expectedPose = plan.hazard() ? START.offset(3, 0, 0) : START.offset(plan.length, 0, 0);
        boolean pose = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.hypot(x - expectedPose.x - 0.5D, z - expectedPose.z - 0.5D) <= 0.25D
                && Math.abs(y - expectedPose.y) <= 0.05D;
        int expectedCells = plan.hazard() ? 4 : plan.feet.size();
        boolean hazardPrefix = plan.hazard() && completed == 4 && prefix == 4 && partial == 0 && invalid == 0;
        boolean pass = outsideChanged == 0 && completed == expectedCells && prefix == expectedCells
                && partial == 0 && invalid == 0 && pose;
        return new Audit(baselineChanged == 0, outsideChanged, completed, prefix, partial,
                invalid, pose, hazardPrefix, pass);
    }

    enum Material { AIR, STONE, BEDROCK, SEA_LANTERN, OTHER }

    record Cell(int x, int y, int z) {
        Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        Cell above() { return offset(0, 1, 0); }
        List<Integer> coordinates() { return List.of(x, y, z); }
    }

    record Plan(FixturePhase5Mode mode, int length, boolean branches, List<Cell> route,
            List<Cell> feet, Set<Cell> excavation, Map<Cell, Material> baseline) {
        boolean hazard() { return mode == FixturePhase5Mode.TUNNEL_HAZARD; }
    }

    record Audit(boolean baselineMatches, int outsideChanged, int completedCells, int prefixCells,
            int partialCells, int invalidInsideStates, boolean poseMatch, boolean hazardPrefix, boolean pass) { }
}
