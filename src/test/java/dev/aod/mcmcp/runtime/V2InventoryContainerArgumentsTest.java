package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InventoryContainerArgumentsTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockStateFingerprint BARREL =
            new BlockStateFingerprint("minecraft:barrel", Map.of("facing", "up", "open", "false"));

    @Test
    void inspectionUsesLiveTargetStateWithoutRequiringPublicObservationReferences() {
        var input = inspect();
        input.put("item", "example:backpack");
        var request = (V2InventoryContainerArguments) V2InventoryRequest.parse(input);
        var operation = request.operation(DIMENSION, BARREL, new Vec3(4.5, 65, 8.5), 8);
        assertThat(request.advance()).isTrue();
        assertThat(request.itemId()).isEqualTo("example:backpack");
        assertThat(operation.parameters()).containsEntry("goal", Map.of("minimum_destination_count", 0))
                .containsEntry("stack", Map.of("item", "minecraft:air", "stack_policy", "item_id_any_components"))
                .doesNotContainKey("transfer_count");
        assertThat(operation.parameters().get("container")).isEqualTo(Map.of(
                "target", Map.of("dimension", DIMENSION, "x", 4, "y", 65, "z", 8),
                "expected_state", Map.of("block", BARREL.blockId(), "properties", BARREL.properties())));
        assertThat(V2InventoryRequest.parse(Map.of("operation", "inspect")))
                .isInstanceOf(V2InventoryInspectArguments.class);
    }

    @Test
    void exactQuantitySurvivesBothTransferDirections() {
        for (String direction : new String[]{"take", "store"}) {
            var input = transfer();
            input.put("direction", direction);
            var request = (V2InventoryContainerArguments) V2InventoryRequest.parse(input);
            var operation = request.operation(DIMENSION, BARREL, new Vec3(4.5, 65, 8.5), 8);
            assertThat(operation.parameters()).containsEntry("transfer_count", 13)
                    .containsEntry("max_transfer_count", 13)
                    .containsEntry("direction", direction.equals("take")
                            ? "container_to_player" : "player_to_container")
                    .containsEntry("stack", Map.of("item", "example:material",
                            "stack_policy", "item_id_any_components"));
        }
    }

    @Test
    void rejectsIncompleteOrUnboundedRequestsAndMismatchedLiveEvidence() {
        for (String required : new String[]{"x", "y", "z", "item", "count", "direction", "target"}) {
            var input = transfer();
            input.remove(required);
            assertThatThrownBy(() -> V2InventoryRequest.parse(input))
                    .as(required).isInstanceOf(IllegalArgumentException.class);
        }
        for (var invalid : Map.<String, Object>of("count", 897, "x", 4.5,
                "max_distance", Double.NaN, "max_ticks", 0, "direction", "both",
                "target", "menu", "frame_id", "unneeded").entrySet()) {
            var input = transfer();
            input.put(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> V2InventoryRequest.parse(input))
                    .as(invalid.getKey()).isInstanceOf(IllegalArgumentException.class);
        }
        var input = inspect();
        input.put("block", "minecraft:barrel");
        var request = (V2InventoryContainerArguments) V2InventoryRequest.parse(input);
        assertThatThrownBy(() -> request.operation(DIMENSION,
                new BlockStateFingerprint("minecraft:stone", Map.of()), new Vec3(4.5, 65, 8.5), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request.operation(DIMENSION, BARREL, new Vec3(6, 65, 8.5), 8))
                .isInstanceOf(IllegalArgumentException.class);
        input.put("count", 1);
        assertThatThrownBy(() -> V2InventoryRequest.parse(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> inspect() {
        return new LinkedHashMap<>(Map.of("operation", "inspect", "target", "container",
                "x", 4, "y", 65, "z", 8));
    }

    private static Map<String, Object> transfer() {
        var input = inspect();
        input.putAll(Map.of("operation", "transfer", "direction", "take",
                "item", "example:material", "count", 13));
        return input;
    }
}
