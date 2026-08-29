package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerSyncSignalsTest {
    private static final ContainerSyncSignals.StackFingerprint STONE =
            new ContainerSyncSignals.StackFingerprint("minecraft:stone", 32, 1234);
    private static final ContainerSyncSignals.StackFingerprint DIRT =
            new ContainerSyncSignals.StackFingerprint("minecraft:dirt", 8, 5678);

    @Test
    void fullContentCapturesAllSlotsAndCarriedAsAnImmutablePacketSnapshot() {
        var session = UUID.randomUUID();
        var channel = new ContainerSyncSignals.SessionChannel();
        channel.bindAndSnapshot(session);
        channel.openScreen(7, "minecraft:generic_9x3", 10);
        var mutableSlots = new ArrayList<>(List.of(STONE, DIRT));

        var recorded = channel.fullContent(7, "minecraft:generic_9x3", 41,
                mutableSlots, ContainerSyncSignals.StackFingerprint.EMPTY, 11);
        mutableSlots.set(0, DIRT);

        assertThat(recorded.applied()).isTrue();
        assertThat(recorded.snapshot().packetLedgerRevision()).isEqualTo(2);
        assertThat(recorded.snapshot().container().worldSessionId()).isEqualTo(session);
        assertThat(recorded.snapshot().container().containerId()).isEqualTo(7);
        assertThat(recorded.snapshot().container().menuTypeId())
                .isEqualTo("minecraft:generic_9x3");
        assertThat(recorded.snapshot().container().stateId()).isEqualTo(41);
        assertThat(recorded.snapshot().container().slots()).containsExactly(STONE, DIRT);
        assertThat(recorded.snapshot().container().carried().empty()).isTrue();
    }

    @Test
    void stateIdWrapIsStoredExactlyWithoutMagnitudeOrdering() {
        var channel = new ContainerSyncSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        channel.openScreen(3, "minecraft:hopper", 1);
        channel.fullContent(3, "minecraft:hopper", Integer.MAX_VALUE,
                List.of(STONE), ContainerSyncSignals.StackFingerprint.EMPTY, 2);

        var wrapped = channel.slot(3, "minecraft:hopper", Integer.MIN_VALUE,
                0, DIRT, 3);

        assertThat(wrapped.applied()).isTrue();
        assertThat(wrapped.snapshot().container().stateId()).isEqualTo(Integer.MIN_VALUE);
        assertThat(wrapped.snapshot().container().slots()).containsExactly(DIRT);
        assertThat(wrapped.snapshot().packetLedgerRevision()).isEqualTo(3);
    }

    @Test
    void wrongIdentityAndSlotStillAdvanceLedgerButCannotMutateTheSnapshot() {
        var channel = new ContainerSyncSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        channel.openScreen(3, "minecraft:hopper", 1);
        channel.fullContent(3, "minecraft:hopper", 2,
                List.of(STONE), ContainerSyncSignals.StackFingerprint.EMPTY, 2);

        var wrongId = channel.slot(4, "minecraft:hopper", 3, 0, DIRT, 3);
        var wrongSlot = channel.slot(3, "minecraft:hopper", 4, 9, DIRT, 4);

        assertThat(wrongId.applied()).isFalse();
        assertThat(wrongId.rejectionReason()).isEqualTo("container_identity_mismatch");
        assertThat(wrongSlot.applied()).isFalse();
        assertThat(wrongSlot.rejectionReason()).isEqualTo("slot_out_of_bounds");
        assertThat(wrongSlot.snapshot().container().slots()).containsExactly(STONE);
        assertThat(wrongSlot.snapshot().packetLedgerRevision()).isEqualTo(4);
    }

    @Test
    void rebindingDropsEveryPacketFromThePriorWorldSession() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var channel = new ContainerSyncSignals.SessionChannel();
        channel.bindAndSnapshot(first);
        channel.openScreen(5, "minecraft:crafting", 1);
        channel.fullContent(5, "minecraft:crafting", 1,
                List.of(STONE), ContainerSyncSignals.StackFingerprint.EMPTY, 2);

        var rebound = channel.bindAndSnapshot(second);

        assertThat(rebound.worldSessionId()).isEqualTo(second);
        assertThat(rebound.packetLedgerRevision()).isZero();
        assertThat(rebound.lastOpenScreen()).isNull();
        assertThat(rebound.container()).isNull();
        assertThat(rebound.data()).isEmpty();
        assertThat(rebound.lastClose()).isNull();
    }

    @Test
    void menuDataIsSessionAndContainerBoundAndOpenClearsOlderValues() {
        var channel = new ContainerSyncSignals.SessionChannel();
        UUID session = UUID.randomUUID();
        channel.bindAndSnapshot(session);
        channel.openScreen(4, "minecraft:brewing_stand", 1);
        channel.fullContent(4, "minecraft:brewing_stand", 7,
                List.of(STONE), ContainerSyncSignals.StackFingerprint.EMPTY, 2);

        var applied = channel.data(4, "minecraft:brewing_stand", 0, 400, 3);
        assertThat(applied.applied()).isTrue();
        assertThat(applied.snapshot().data().get(0).value()).isEqualTo(400);
        assertThat(applied.snapshot().data().get(0).worldSessionId()).isEqualTo(session);

        var wrong = channel.data(5, "minecraft:brewing_stand", 1, 20, 4);
        assertThat(wrong.applied()).isFalse();
        assertThat(wrong.rejectionReason()).isEqualTo("container_identity_mismatch");

        var reopened = channel.openScreen(6, "minecraft:brewing_stand", 5);
        assertThat(reopened.snapshot().container()).isNull();
        assertThat(reopened.snapshot().data()).isEmpty();
    }
}
