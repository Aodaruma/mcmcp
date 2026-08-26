package dev.aod.mcmcp.observation;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativeWorldEditTest {
    private static final String KEY = "3d7d9ed2-3bab-4a5a-a7aa-9b59b4f43243";

    @Test
    void parsesBoundedFillWithACompleteRuntimeBlockState() {
        var request = CreativeWorldEdit.parseRequest(Map.of(
                "operation", "fill",
                "region", Map.of(
                        "dimension", "minecraft:overworld",
                        "min", Map.of("x", 0, "y", 64, "z", 0),
                        "max", Map.of("x", 63, "y", 64, "z", 63)),
                "state", MinecraftObservationService.blockStateView(
                        Blocks.REPEATER.defaultBlockState()).toMap(),
                "idempotency_key", KEY));

        assertThat(request.dimension()).isEqualTo("minecraft:overworld");
        assertThat(request.requestedChanges()).isEqualTo(CreativeWorldEdit.MAX_BLOCKS);
    }

    @Test
    void rejectsIncompleteStateAndFillBeyondTheTransactionLimit() {
        assertThatThrownBy(() -> CreativeWorldEdit.parseRequest(Map.of(
                "operation", "set_block",
                "position", Map.of(
                        "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0),
                "state", Map.of("block", "minecraft:repeater", "properties", Map.of()),
                "idempotency_key", KEY)))
                .isInstanceOf(BlockPlanValidationException.class)
                .hasMessageContaining("complete runtime BlockState");

        assertThatThrownBy(() -> CreativeWorldEdit.parseRequest(Map.of(
                "operation", "fill",
                "region", Map.of(
                        "dimension", "minecraft:overworld",
                        "min", Map.of("x", 0, "y", 64, "z", 0),
                        "max", Map.of("x", 63, "y", 65, "z", 63)),
                "state", Map.of("block", "minecraft:stone", "properties", Map.of()),
                "idempotency_key", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096");
    }

    @Test
    void rejectsAirToChestBeforeAnyMutationCanRun() {
        var simulatedWorld = new AtomicReference<>(Blocks.AIR.defaultBlockState());

        assertThatThrownBy(() -> {
            CreativeWorldEdit.parseRequest(Map.of(
                    "operation", "set_block",
                    "position", Map.of(
                            "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0),
                    "state", MinecraftObservationService.blockStateView(
                            Blocks.CHEST.defaultBlockState()).toMap(),
                    "idempotency_key", KEY));
            simulatedWorld.set(Blocks.CHEST.defaultBlockState());
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block entity");
        assertThat(simulatedWorld.get()).isEqualTo(Blocks.AIR.defaultBlockState());
    }

    @Test
    void rejectsCommandStringsSelectorsNbtAndNonAllowlistedEntities() {
        assertThatThrownBy(() -> CreativeWorldEdit.parseRequest(Map.of(
                "operation", "command",
                "command", "gamerule doDaylightCycle false",
                "idempotency_key", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation must be");

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("type", "minecraft:cow");
        entity.put("position", Map.of("x", 0, "y", 64, "z", 0));
        entity.put("yaw", 0);
        entity.put("pitch", 0);
        entity.put("nbt", Map.of("Invulnerable", true));
        assertThatThrownBy(() -> CreativeWorldEdit.parseRequest(Map.of(
                "operation", "summon_entities",
                "dimension", "minecraft:overworld",
                "entities", List.of(entity),
                "idempotency_key", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly");

        entity.remove("nbt");
        entity.put("type", "minecraft:creeper");
        assertThatThrownBy(() -> CreativeWorldEdit.parseRequest(Map.of(
                "operation", "summon_entities",
                "dimension", "minecraft:overworld",
                "entities", List.of(entity),
                "idempotency_key", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe entity allowlist");
    }

    @Test
    void undoAndRedoBindAnExplicitHistoryHead() {
        UUID transaction = UUID.randomUUID();
        for (String operation : List.of("undo", "redo")) {
            var request = CreativeWorldEdit.parseRequest(Map.of(
                    "operation", operation,
                    "expected_transaction_id", transaction.toString(),
                    "idempotency_key", KEY));

            assertThat(request.requestedChanges()).isOne();
            assertThat(request.dimension()).isNull();
        }
    }

    @Test
    void wallClockTimeoutHandlesNegativeOriginsAndLongWraparound() {
        long duration = CreativeWorldEdit.JOB_TIMEOUT.toNanos();
        long negativeStart = -duration * 2;
        long nearWrap = Long.MAX_VALUE - duration / 2;

        assertThat(CreativeWorldEdit.timeoutElapsed(
                negativeStart, CreativeWorldEdit.JOB_TIMEOUT, negativeStart + duration - 1)).isFalse();
        assertThat(CreativeWorldEdit.timeoutElapsed(
                negativeStart, CreativeWorldEdit.JOB_TIMEOUT, negativeStart + duration)).isTrue();
        assertThat(CreativeWorldEdit.timeoutElapsed(
                nearWrap, CreativeWorldEdit.JOB_TIMEOUT, nearWrap + duration - 1)).isFalse();
        assertThat(CreativeWorldEdit.timeoutElapsed(
                nearWrap, CreativeWorldEdit.JOB_TIMEOUT, nearWrap + duration)).isTrue();
    }
}
