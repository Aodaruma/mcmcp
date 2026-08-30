package dev.aod.mcmcp.redstone;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed, Minecraft-independent specification for the first Redstone identity slice. */
public record RedstoneSpec(
        List<Component> components,
        List<TruthRow> truthTable,
        Footprint footprint,
        int rotationDegrees,
        ExecutionBounds bounds) {
    public static final int MAX_SETTLE_TICKS = 20;

    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
    private static final Set<Component> IDENTITY_COMPONENTS = Set.of(
            new Component("input", Role.INPUT, "minecraft:lever"),
            new Component("output", Role.OUTPUT, "minecraft:redstone_lamp"));
    private static final Set<TruthRow> IDENTITY_TRUTH_TABLE = Set.of(
            new TruthRow(Map.of("input", false), Map.of("output", false)),
            new TruthRow(Map.of("input", true), Map.of("output", true)));
    private static final Footprint IDENTITY_FOOTPRINT = new Footprint(2, 1, 1);

    public RedstoneSpec {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        truthTable = List.copyOf(Objects.requireNonNull(truthTable, "truthTable"));
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(bounds, "bounds");

        if (components.size() != IDENTITY_COMPONENTS.size()
                || !Set.copyOf(components).equals(IDENTITY_COMPONENTS)) {
            throw new IllegalArgumentException("identity slice requires one lever input and one lamp output");
        }
        if (truthTable.size() != IDENTITY_TRUTH_TABLE.size()
                || !Set.copyOf(truthTable).equals(IDENTITY_TRUTH_TABLE)) {
            throw new IllegalArgumentException("identity slice requires the complete two-row truth table");
        }
        if (!IDENTITY_FOOTPRINT.equals(footprint)) {
            throw new IllegalArgumentException("identity slice footprint must be 2x1x1");
        }
        if (!ROTATIONS.contains(rotationDegrees)) {
            throw new IllegalArgumentException("identity slice rotation is unsupported");
        }
        if (!bounds.stationary()
                || bounds.settleTicks() < 1
                || bounds.settleTicks() > MAX_SETTLE_TICKS) {
            throw new IllegalArgumentException("identity slice execution bounds are unsupported");
        }
    }

    public enum Role { INPUT, OUTPUT }

    public record Component(String id, Role role, String blockId) {
        public Component {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    public record TruthRow(Map<String, Boolean> inputs, Map<String, Boolean> outputs) {
        public TruthRow {
            inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
            outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
        }
    }

    public record Footprint(int x, int y, int z) {
    }

    public record ExecutionBounds(boolean stationary, int settleTicks) {
    }
}
