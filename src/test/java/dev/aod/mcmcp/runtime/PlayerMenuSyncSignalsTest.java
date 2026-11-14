package dev.aod.mcmcp.runtime;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerMenuSyncSignalsTest {
    private static final ContainerSyncSignals.StackFingerprint EMPTY = ContainerSyncSignals.StackFingerprint.EMPTY;
    private static final ContainerSyncSignals.StackFingerprint PLANKS =
            new ContainerSyncSignals.StackFingerprint("minecraft:jungle_planks", 32, 123);

    @Test
    void mapsPacketStacksByInventoryIdentityAndIndexWithoutChangingCursorProof() {
        var inventory = new SimpleContainer(36);
        var other = new SimpleContainer(36);
        var slots = List.of(new Slot(other, 9, 0, 0), new Slot(inventory, 9, 0, 0),
                new Slot(inventory, 0, 0, 0));
        var channel = channel();
        channel.fullContent(0, "mcmcp:player_inventory", 17, List.of(EMPTY, EMPTY, EMPTY), PLANKS, 1);
        long cursorRevision = channel.cursorProofRevision();

        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 9, PLANKS, 2);
        var snapshot = channel.snapshot().container();
        assertThat(snapshot.slots()).containsExactly(EMPTY, PLANKS, EMPTY);
        assertThat(snapshot.stateId()).isEqualTo(17);
        assertThat(snapshot.carried()).isEqualTo(PLANKS);
        assertThat(channel.cursorProofRevision()).isEqualTo(cursorRevision);
        assertThat(inventory.getItem(9).isEmpty()).isTrue();

        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, other, 9, PLANKS, 3);
        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 35, PLANKS, 4);
        assertThat(channel.snapshot().container()).isEqualTo(snapshot);
        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 9, EMPTY, 5);
        assertThat(channel.snapshot().container().slots()).containsExactly(EMPTY, EMPTY, EMPTY);
        assertThat(snapshot.slots()).containsExactly(EMPTY, PLANKS, EMPTY);
    }

    @Test
    void partialMenuPacketsCannotInventFullInventoryEvidenceOrSurviveSessionReset() {
        var inventory = new SimpleContainer(36);
        var slots = List.of(new Slot(inventory, 9, 0, 0));
        var channel = channel();
        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 9, PLANKS, 1);
        assertThat(channel.snapshot().container()).isNull();
        channel.fullContent(0, "mcmcp:player_inventory", 17, List.of(EMPTY), EMPTY, 2);
        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 9, PLANKS, 3);
        assertThat(channel.snapshot().container().slots()).containsExactly(PLANKS);
        channel.bindAndSnapshot(UUID.randomUUID());
        PlayerMenuSyncSignals.recordSlot(channel, inventory, slots, inventory, 9, PLANKS, 4);
        assertThat(channel.snapshot().container()).isNull();
    }

    private static ContainerSyncSignals.SessionChannel channel() {
        var channel = new ContainerSyncSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        return channel;
    }
}
