package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FishingSoundWaitTest {
    @Test
    void splashMayArriveWellAfterOneHundredTicksWithinFiniteWait() {
        var condition = new ActionDsl.SoundClueCondition(
                "minecraft:entity.fishing_bobber.splash",
                100L,
                new ActionDsl.WorldBounds(
                        "minecraft:overworld",
                        new ActionDsl.WorldPoint(0, 60, 0),
                        new ActionDsl.WorldPoint(16, 80, 16)));
        var clue = new ObservationRecord.SoundClue(
                new ObservationValues.ResourceId(
                        "minecraft:entity.fishing_bobber.splash"),
                ObservationRecord.SoundCategory.NEUTRAL,
                new ObservationValues.WorldPosition(
                        new ObservationValues.ResourceId("minecraft:overworld"),
                        8, 63, 8),
                450L, 450L, 0, 1, null, 12L);

        assertThat(McmcpRuntime.soundClueMatches(condition, 450L, List.of(clue))).isTrue();
        assertThat(McmcpRuntime.soundClueMatches(
                condition, 451L + dev.aod.mcmcp.agent.observation.SoundClueStore.TTL_TICKS,
                List.of(clue))).isFalse();
    }
}
