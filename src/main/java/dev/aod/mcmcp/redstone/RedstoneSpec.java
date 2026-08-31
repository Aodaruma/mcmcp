package dev.aod.mcmcp.redstone;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed, Minecraft-independent specification for the supported Redstone identity slices. */
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
    private static final Set<Component> FAN_OUT_COMPONENTS = Set.of(
            new Component("input", Role.INPUT, "minecraft:lever"),
            new Component("output", Role.OUTPUT, "minecraft:redstone_lamp"),
            new Component("output_2", Role.OUTPUT, "minecraft:redstone_lamp"));
    private static final Set<TruthRow> IDENTITY_TRUTH_TABLE = Set.of(
            new TruthRow(Map.of("input", false), Map.of("output", false)),
            new TruthRow(Map.of("input", true), Map.of("output", true)));
    private static final Set<TruthRow> FAN_OUT_TRUTH_TABLE = Set.of(
            new TruthRow(
                    Map.of("input", false),
                    Map.of("output", false, "output_2", false)),
            new TruthRow(
                    Map.of("input", true),
                    Map.of("output", true, "output_2", true)));
    private static final Footprint IDENTITY_FOOTPRINT = new Footprint(2, 1, 1);
    private static final Footprint FAN_OUT_FOOTPRINT = new Footprint(3, 1, 1);

    public RedstoneSpec {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        truthTable = List.copyOf(Objects.requireNonNull(truthTable, "truthTable"));
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(bounds, "bounds");

        boolean identity = components.size() == IDENTITY_COMPONENTS.size()
                && Set.copyOf(components).equals(IDENTITY_COMPONENTS);
        boolean fanOut = components.size() == FAN_OUT_COMPONENTS.size()
                && Set.copyOf(components).equals(FAN_OUT_COMPONENTS);
        if (!identity && !fanOut) {
            throw new IllegalArgumentException(
                    "identity slice requires one lever input and one or two lamp outputs");
        }
        Set<TruthRow> expectedTruthTable = fanOut ? FAN_OUT_TRUTH_TABLE : IDENTITY_TRUTH_TABLE;
        Footprint expectedFootprint = fanOut ? FAN_OUT_FOOTPRINT : IDENTITY_FOOTPRINT;
        if (truthTable.size() != expectedTruthTable.size()
                || !Set.copyOf(truthTable).equals(expectedTruthTable)) {
            throw new IllegalArgumentException(
                    "identity slice requires the complete matching two-row truth table");
        }
        if (!expectedFootprint.equals(footprint)) {
            throw new IllegalArgumentException("identity slice footprint does not match its outputs");
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

    public int outputCount() {
        return components.size() - 1;
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
