package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.observation.BlockPosition;
import dev.aodaruma.craftagent.observation.ObservationProvenance;
import dev.aodaruma.craftagent.observation.ObservedContext;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.runtime.WorldSessionTracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionConfirmationRecorderTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final BlockTarget TARGET = new BlockTarget(OVERWORLD, 4, 64, -2);
    private static final ObservedContext CONTEXT = new ObservedContext(
            7, 15, null, null, null, true, true, List.of());

    @Test
    void recordsACompleteInteractionConfirmationInTheCurrentSession() {
        var sessionId = UUID.randomUUID();
        var memory = activeMemory(sessionId, OVERWORLD);
        var state = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "west",
                "half", "top",
                "shape", "inner_left",
                "waterlogged", "false"));

        boolean recorded = InteractionConfirmationRecorder.rememberIfCurrent(
                memory, ready(sessionId, OVERWORLD, 42), TARGET, state, state, CONTEXT);

        assertThat(recorded).isTrue();
        assertThat(memory.revision()).isEqualTo(1);
        var observation = memory.findBlock(new BlockPosition(OVERWORLD, 4, 64, -2)).orElseThrow();
        assertThat(observation.state().block()).isEqualTo("minecraft:oak_stairs");
        assertThat(observation.state().properties()).containsExactlyInAnyOrderEntriesOf(state.properties());
        assertThat(observation.observedContext()).isEqualTo(CONTEXT);
        assertThat(observation.provenance()).isEqualTo(ObservationProvenance.INTERACTION_CONFIRMATION);
        assertThat(observation.observedAtClientTick()).isEqualTo(42);
        assertThat(observation.worldSessionId()).isEqualTo(sessionId);
    }

    @Test
    void skipsHistoricalAirAfterImmediateRegeneration() {
        var sessionId = UUID.randomUUID();
        var memory = activeMemory(sessionId, OVERWORLD);
        var confirmedAir = new BlockStateFingerprint("minecraft:air", Map.of());
        var regenerated = new BlockStateFingerprint("minecraft:cobblestone", Map.of());

        boolean recorded = InteractionConfirmationRecorder.rememberIfCurrent(
                memory, ready(sessionId, OVERWORLD, 9), TARGET, confirmedAir, regenerated, CONTEXT);

        assertThat(recorded).isFalse();
        assertThat(memory.revision()).isZero();
        assertThat(memory.stats().retainedBlocks()).isZero();
    }

    @Test
    void rejectsAStaleSessionOrDifferentDimensionWithoutAdvancingRevision() {
        var memorySession = UUID.randomUUID();
        var memory = activeMemory(memorySession, OVERWORLD);
        var state = new BlockStateFingerprint("minecraft:air", Map.of());

        assertThat(InteractionConfirmationRecorder.rememberIfCurrent(
                memory, ready(UUID.randomUUID(), OVERWORLD, 3), TARGET, state, state, CONTEXT)).isFalse();
        assertThat(InteractionConfirmationRecorder.rememberIfCurrent(
                memory, ready(memorySession, "minecraft:the_nether", 3), TARGET, state, state, CONTEXT)).isFalse();
        assertThat(memory.revision()).isZero();
    }

    @Test
    void rejectsWhenMemoryHasAlreadyMovedDimensionWithinTheSameJoin() {
        var sessionId = UUID.randomUUID();
        var memory = activeMemory(sessionId, "minecraft:the_nether");
        var state = new BlockStateFingerprint("minecraft:air", Map.of());

        assertThat(InteractionConfirmationRecorder.rememberIfCurrent(
                memory, ready(sessionId, OVERWORLD, 3), TARGET, state, state, CONTEXT)).isFalse();
        assertThat(memory.revision()).isZero();
    }

    private static WorldMemory activeMemory(UUID sessionId, String dimension) {
        var memory = new WorldMemory();
        memory.startSession(sessionId, dimension);
        return memory;
    }

    private static WorldSessionTracker.Snapshot ready(
            UUID sessionId, String dimension, long tick) {
        return new WorldSessionTracker.Snapshot(
                WorldSessionTracker.Readiness.WORLD_READY, 1, tick, sessionId, dimension);
    }
}
