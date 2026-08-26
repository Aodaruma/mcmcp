package dev.aodaruma.craftagent.observation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldMemoryTest {
    @Test
    void preservesDimensionNamespacesWithinOneJoinButClearsOnNewJoin() {
        var memory = new WorldMemory(8, 4);
        var session = UUID.randomUUID();
        memory.startSession(session, "minecraft:overworld");
        var overworld = observation(session, "minecraft:overworld", 1, 20);
        memory.rememberBlock(overworld);

        memory.startSession(session, "minecraft:the_nether");
        memory.rememberBlock(observation(session, "minecraft:the_nether", 2, 30));

        assertThat(memory.findBlock(overworld.position())).contains(overworld);
        assertThat(memory.stats().retainedBlocks()).isEqualTo(2);

        memory.startSession(UUID.randomUUID(), "minecraft:overworld");
        assertThat(memory.findBlock(overworld.position())).isEmpty();
        assertThat(memory.stats().retainedBlocks()).isZero();
    }

    @Test
    void rejectsCrossSessionObservationAndEvictsLeastRecentlyUsed() {
        var session = UUID.randomUUID();
        var memory = new WorldMemory(2, 2);
        memory.startSession(session, "minecraft:overworld");
        var first = observation(session, "minecraft:overworld", 1, 10);
        var second = observation(session, "minecraft:overworld", 2, 11);
        var third = observation(session, "minecraft:overworld", 3, 12);
        memory.rememberBlock(first);
        memory.rememberBlock(second);
        memory.findBlock(first.position()); // first is now most recently used
        memory.rememberBlock(third);

        assertThat(memory.findBlock(second.position())).isEmpty();
        assertThat(memory.findBlock(first.position())).contains(first);
        assertThat(memory.stats().evictedBlocks()).isEqualTo(1);
        assertThatThrownBy(() -> memory.rememberBlock(observation(
                UUID.randomUUID(), "minecraft:overworld", 4, 13)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void playerNeverReceivesOpaqueReferenceAndEntityReferenceExpires() {
        var session = UUID.randomUUID();
        var memory = new WorldMemory();
        memory.startSession(session, "minecraft:overworld");
        var mob = memory.rememberEntity(UUID.randomUUID(), "minecraft:zombie", 1, 2, 3,
                0, 0, 0, false, false, false, "minecraft:overworld", 100);
        var player = memory.rememberEntity(UUID.randomUUID(), "minecraft:player", 4, 5, 6,
                0, 0, 0, true, false, false, "minecraft:overworld", 100);

        assertThat(mob.opaqueRef()).isNotBlank();
        assertThat(player.opaqueRef()).isNull();
        assertThat(memory.resolveEntityRef(
                mob.opaqueRef(), 200, session, "minecraft:overworld"))
                .contains(new WorldMemory.ResolvedEntityRef(
                        mob.internalUuid(), mob.type(), mob.dimension(),
                        mob.observedAtClientTick(), mob.worldSessionId()));
        assertThat(memory.resolveEntityRef(
                mob.opaqueRef(), 201, session, "minecraft:overworld")).isEmpty();
        assertThat(memory.resolveEntityRef(
                mob.opaqueRef(), 200, UUID.randomUUID(), "minecraft:overworld")).isEmpty();
        assertThat(memory.resolveEntityRef(
                mob.opaqueRef(), 200, session, "minecraft:the_nether")).isEmpty();
        assertThat(memory.resolveEntityRef(
                player.opaqueRef(), 100, session, "minecraft:overworld")).isEmpty();

        memory.startSession(session, "minecraft:the_nether");
        assertThat(memory.resolveEntityRef(
                mob.opaqueRef(), 150, session, "minecraft:the_nether")).isEmpty();
        assertThatThrownBy(() -> memory.rememberEntity(UUID.randomUUID(), "minecraft:zombie", 0, 0, 0,
                0, 0, 0, false, false, false, "minecraft:overworld", 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void detachClearsSessionSpecificEvictionStatistics() {
        var session = UUID.randomUUID();
        var memory = new WorldMemory(1, 1);
        memory.startSession(session, "minecraft:overworld");
        memory.rememberBlock(observation(session, "minecraft:overworld", 1, 10));
        memory.rememberBlock(observation(session, "minecraft:overworld", 2, 11));
        assertThat(memory.stats().evictedBlocks()).isEqualTo(1);

        memory.detachSession();

        assertThat(memory.stats().evictedBlocks()).isZero();
        assertThat(memory.stats().evictedEntities()).isZero();
    }

    private static ObservedBlock observation(UUID session, String dimension, int x, long tick) {
        return new ObservedBlock(
                new BlockPosition(dimension, x, 64, 0),
                new BlockStateView("minecraft:oak_stairs", Map.of(
                        "facing", "south",
                        "half", "bottom",
                        "shape", "straight",
                        "waterlogged", "false")),
                new ObservedContext(10, 15, null, false, false, List.of("down")),
                ObservationProvenance.LINE_OF_SIGHT_OBSERVATION,
                tick,
                session);
    }
}
