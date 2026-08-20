package dev.aodaruma.craftagent.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class MinecraftObservationServiceTest {
    @Test
    void serializesEveryPropertyPresentOnTheBlockState() {
        var state = Blocks.OAK_STAIRS.defaultBlockState();

        var view = MinecraftObservationService.blockStateView(state);
        var values = state.getValues().toList();

        assertThat(view.block()).isEqualTo("minecraft:oak_stairs");
        assertThat(view.properties()).hasSameSizeAs(values);
        values.forEach(value -> assertThat(view.properties())
                .containsEntry(value.property().getName(), value.valueName()));
    }

    @Test
    void knownBlockSampleUsesActualAndPreservesTheCommonObservedBlockRecord() {
        UUID session = UUID.randomUUID();
        var position = new BlockPosition("minecraft:overworld", 4, 65, -2);
        var observation = new ObservedBlock(
                position,
                new BlockStateView("minecraft:oak_stairs", Map.of(
                        "facing", "south",
                        "half", "bottom",
                        "shape", "straight",
                        "waterlogged", "false")),
                new ObservedContext(9, 15, null, false, false, List.of("down")),
                ObservationProvenance.LINE_OF_SIGHT_OBSERVATION,
                70,
                session);
        var sample = new MinecraftObservationService.BlockSample(
                MinecraftObservationService.BlockOutcome.CURRENT,
                position,
                observation,
                List.of("north"),
                true,
                null,
                72);

        Map<String, Object> mapped = sample.toMap(72);

        assertThat(mapped).containsKeys("outcome", "position", "actual")
                .doesNotContainKeys("observation", "reason");
        @SuppressWarnings("unchecked")
        var actual = (Map<String, Object>) mapped.get("actual");
        assertThat(actual).containsKeys(
                "position", "state", "observed_context", "knowledge", "live_context", "world_session_id");
        @SuppressWarnings("unchecked")
        var state = (Map<String, Object>) actual.get("state");
        assertThat(state.get("properties")).isEqualTo(observation.state().properties());
        @SuppressWarnings("unchecked")
        var observedContext = (Map<String, Object>) actual.get("observed_context");
        assertThat(observedContext).containsKeys(
                "fluid_at_observation", "fluid_source_at_observation", "fluid_amount_at_observation");
    }

    @Test
    void valueAndReasonOutcomesAreMutuallyExclusive() {
        var position = new BlockPosition("minecraft:overworld", 0, 64, 0);
        var unknown = MinecraftObservationService.BlockSample.unknown(position, "never_observed");

        assertThat(unknown.toMap(25))
                .containsEntry("outcome", "unknown")
                .containsEntry("reason", "never_observed")
                .doesNotContainKey("actual");
        assertThatThrownBy(() -> new MinecraftObservationService.BlockSample(
                        MinecraftObservationService.BlockOutcome.UNKNOWN,
                        position,
                        null,
                        List.of(),
                        false,
                        null,
                        25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observationSourceParsingIsClosed() {
        assertThat(MinecraftObservationService.BlockSource.parse("live"))
                .isEqualTo(MinecraftObservationService.BlockSource.LIVE);
        assertThat(MinecraftObservationService.BlockSource.parse("memory"))
                .isEqualTo(MinecraftObservationService.BlockSource.MEMORY);
        assertThat(MinecraftObservationService.BlockSource.parse("live_and_memory"))
                .isEqualTo(MinecraftObservationService.BlockSource.LIVE_AND_MEMORY);
        assertThatThrownBy(() -> MinecraftObservationService.BlockSource.parse("hidden_chunk"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sampledVisibilityResultCannotRepresentAVisibleErrorOrSilentHiddenState() {
        assertThat(SampledVisibility.Result.visible(List.of("up")).visible()).isTrue();
        assertThat(SampledVisibility.Result.hidden("occluded").visible()).isFalse();
        assertThatThrownBy(() -> new SampledVisibility.Result(true, List.of(), "occluded"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampledVisibility.Result(false, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
