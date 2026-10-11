package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2MenuArgumentsTest {
    private Map<String, Object> request() {
        return new java.util.LinkedHashMap<>(Map.of("target", "menu", "x", 0, "y", 65, "z", 0,
                "block", "minecraft:chest", "menu_type", "minecraft:generic_9x3"));
    }
    @Test void inspectAndBoundedClicksRequireExactConditions() {
        assertThat(V2MenuArguments.parse(request(), "minecraft:overworld").clicks()).isEmpty();
        var args = request();
        args.put("clicks", List.of(Map.of("type", "quick_move", "slot", 0, "item", "minecraft:stone", "count", 16)));
        assertThat(V2MenuArguments.parse(args, "minecraft:overworld").clicks()).hasSize(1);
        args.put("clicks", List.of(Map.of("type", "pickup", "slot", 0, "item", "minecraft:stone", "count", 16)));
        assertThatThrownBy(() -> V2MenuArguments.parse(args, "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        args.put("clicks", List.of(Map.of("type", "quick_move", "slot", 0, "item", "minecraft:stone")));
        assertThatThrownBy(() -> V2MenuArguments.parse(args, "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        args.remove("clicks"); args.remove("block");
        assertThatThrownBy(() -> V2MenuArguments.parse(args, "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyOrdinaryStorageMenusAllowClicks() {
        for (String type : List.of("minecraft:generic_9x3", "minecraft:generic_9x6", "minecraft:generic_3x3",
                "minecraft:hopper", "minecraft:shulker_box")) {
            assertThat(MinecraftV2MenuDriver.supportsStorageClicks(type)).isTrue();
        }
        for (String type : List.of("minecraft:crafting", "minecraft:merchant", "minecraft:furnace", "mod:generic_9x3")) {
            assertThat(MinecraftV2MenuDriver.supportsStorageClicks(type)).isFalse();
        }
    }
    @Test void menuProofRejectsPartialMovesComponentChangesAndUnrelatedSlotChanges() {
        var empty = ContainerSyncSignals.StackFingerprint.EMPTY;
        var stone = new ContainerSyncSignals.StackFingerprint("minecraft:stone", 16, 1);
        var before = List.of(stone, empty, empty);
        assertThat(MinecraftV2MenuDriver.wholeStackMoved(before, List.of(empty, stone, empty), 0)).isTrue();
        assertThat(MinecraftV2MenuDriver.wholeStackMoved(before, before, 0)).isFalse();
        assertThat(MinecraftV2MenuDriver.wholeStackMoved(before, List.of(empty,
                new ContainerSyncSignals.StackFingerprint("minecraft:stone", 15, 1), empty), 0)).isFalse();
        assertThat(MinecraftV2MenuDriver.wholeStackMoved(before, List.of(empty,
                new ContainerSyncSignals.StackFingerprint("minecraft:stone", 16, 2), empty), 0)).isFalse();
        assertThat(MinecraftV2MenuDriver.wholeStackMoved(before, List.of(empty, stone,
                new ContainerSyncSignals.StackFingerprint("minecraft:dirt", 1, 3)), 0)).isFalse();
    }
}
