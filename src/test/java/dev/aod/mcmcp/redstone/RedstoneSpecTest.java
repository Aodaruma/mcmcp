package dev.aod.mcmcp.redstone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedstoneSpecTest {
    @Test
    void acceptsOnlyTheFourIdentityRotations() {
        for (int rotation : List.of(0, 90, 180, 270)) {
            assertThat(identity(rotation).rotationDegrees()).isEqualTo(rotation);
            assertThat(fanOut(rotation).outputCount()).isEqualTo(2);
            assertThat(wireIdentity(rotation).wireCount()).isOne();
        }
    }

    @Test
    void rejectsEveryUnsupportedIdentityVariant() {
        var extraComponents = new ArrayList<>(components());
        extraComponents.add(new RedstoneSpec.Component(
                "extra", RedstoneSpec.Role.OUTPUT, "minecraft:redstone_lamp"));
        var extraRows = new ArrayList<>(rows());
        extraRows.add(new RedstoneSpec.TruthRow(
                Map.of("input", false), Map.of("output", true)));

        List<Runnable> invalid = List.of(
                () -> spec(components().subList(0, 1), rows(), footprint(), 0, bounds()),
                () -> spec(extraComponents, rows(), footprint(), 0, bounds()),
                () -> spec(List.of(components().getFirst(), components().getFirst()),
                        rows(), footprint(), 0, bounds()),
                () -> spec(List.of(
                                new RedstoneSpec.Component(
                                        "input", RedstoneSpec.Role.INPUT, "minecraft:button"),
                                components().getLast()),
                        rows(), footprint(), 0, bounds()),
                () -> spec(components(), rows().subList(0, 1), footprint(), 0, bounds()),
                () -> spec(components(), extraRows, footprint(), 0, bounds()),
                () -> spec(components(), List.of(rows().getFirst(), rows().getFirst()),
                        footprint(), 0, bounds()),
                () -> spec(components(), List.of(
                                new RedstoneSpec.TruthRow(Map.of(), Map.of("output", false)),
                                rows().getLast()),
                        footprint(), 0, bounds()),
                () -> spec(components(), rows(), new RedstoneSpec.Footprint(3, 1, 1),
                        0, bounds()),
                () -> spec(fanOutComponents(), rows(), fanOutFootprint(), 0, bounds()),
                () -> spec(wireComponents(), fanOutRows(), fanOutFootprint(), 0, bounds()),
                () -> spec(wireComponents(), rows(), footprint(), 0, bounds()),
                () -> spec(components(), fanOutRows(), footprint(), 0, bounds()),
                () -> spec(components(), rows(), footprint(), 45, bounds()),
                () -> spec(components(), rows(), footprint(), 0,
                        new RedstoneSpec.ExecutionBounds(false, 2)),
                () -> spec(components(), rows(), footprint(), 0,
                        new RedstoneSpec.ExecutionBounds(true, 0)),
                () -> spec(components(), rows(), footprint(), 0,
                        new RedstoneSpec.ExecutionBounds(
                                true, RedstoneSpec.MAX_SETTLE_TICKS + 1)));

        invalid.forEach(candidate -> assertThatThrownBy(candidate::run)
                .isInstanceOf(IllegalArgumentException.class));
    }

    private static RedstoneSpec identity(int rotation) {
        return spec(components(), rows(), footprint(), rotation, bounds());
    }

    private static RedstoneSpec fanOut(int rotation) {
        return spec(
                fanOutComponents(), fanOutRows(), fanOutFootprint(), rotation, bounds());
    }

    private static RedstoneSpec wireIdentity(int rotation) {
        return spec(wireComponents(), rows(), fanOutFootprint(), rotation, bounds());
    }

    private static RedstoneSpec spec(
            List<RedstoneSpec.Component> components,
            List<RedstoneSpec.TruthRow> rows,
            RedstoneSpec.Footprint footprint,
            int rotation,
            RedstoneSpec.ExecutionBounds bounds) {
        return new RedstoneSpec(components, rows, footprint, rotation, bounds);
    }

    private static List<RedstoneSpec.Component> components() {
        return List.of(
                new RedstoneSpec.Component(
                        "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                new RedstoneSpec.Component(
                        "output", RedstoneSpec.Role.OUTPUT, "minecraft:redstone_lamp"));
    }

    private static List<RedstoneSpec.TruthRow> rows() {
        return List.of(
                new RedstoneSpec.TruthRow(
                        Map.of("input", false), Map.of("output", false)),
                new RedstoneSpec.TruthRow(
                        Map.of("input", true), Map.of("output", true)));
    }

    private static List<RedstoneSpec.Component> fanOutComponents() {
        return List.of(
                components().getFirst(),
                components().getLast(),
                new RedstoneSpec.Component(
                        "output_2", RedstoneSpec.Role.OUTPUT, "minecraft:redstone_lamp"));
    }

    private static List<RedstoneSpec.Component> wireComponents() {
        return List.of(
                components().getFirst(),
                components().getLast(),
                new RedstoneSpec.Component(
                        "wire", RedstoneSpec.Role.WIRE, "minecraft:redstone_wire"));
    }

    private static List<RedstoneSpec.TruthRow> fanOutRows() {
        return List.of(
                new RedstoneSpec.TruthRow(
                        Map.of("input", false),
                        Map.of("output", false, "output_2", false)),
                new RedstoneSpec.TruthRow(
                        Map.of("input", true),
                        Map.of("output", true, "output_2", true)));
    }

    private static RedstoneSpec.Footprint footprint() {
        return new RedstoneSpec.Footprint(2, 1, 1);
    }

    private static RedstoneSpec.Footprint fanOutFootprint() {
        return new RedstoneSpec.Footprint(3, 1, 1);
    }

    private static RedstoneSpec.ExecutionBounds bounds() {
        return new RedstoneSpec.ExecutionBounds(true, 2);
    }
}
