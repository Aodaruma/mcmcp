package dev.aod.mcmcp.runtime;

import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameDisplaySyncSignalsTest {
    @Test
    void onlyChangedOrNewlyKnownFieldsProduceAMutationWhileEveryReceivedFieldIsAcknowledged() {
        var channel = new FrameDisplaySyncSignals.SessionChannel();
        var frame = UUID.randomUUID();
        channel.bindAndSnapshot(UUID.randomUUID());
        assertThat(channel.record(1, frame,
                new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", 0), 1)).isTrue();
        long initialRevision = channel.snapshot().packetLedgerRevision();
        assertThat(channel.record(1, frame,
                new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", 0), 2)).isFalse();
        assertThat(channel.snapshot().frame(1, frame).orElseThrow().itemEvidence().orElseThrow().packetLedgerRevision())
                .isGreaterThan(initialRevision);
        assertThat(channel.record(1, frame,
                new FrameDisplaySyncSignals.DisplayPacket(false, null, 1), 3)).isTrue();
        assertThat(channel.record(1, frame,
                new FrameDisplaySyncSignals.DisplayPacket(true, null, null), 4)).isTrue();
        assertThat(channel.record(1, frame,
                new FrameDisplaySyncSignals.DisplayPacket(false, null, null), 5)).isFalse();
        assertThat(channel.remove(1)).isTrue();
        assertThat(channel.remove(1)).isFalse();
    }

    @Test
    void onlyFieldsInTheCurrentPacketReceiveANewAcknowledgement() {
        var channel = new FrameDisplaySyncSignals.SessionChannel();
        var session = UUID.randomUUID();
        var frame = UUID.randomUUID();
        channel.bindAndSnapshot(session);
        channel.record(7, frame, new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", 3), 10);
        var before = channel.snapshot();
        channel.record(7, frame, new FrameDisplaySyncSignals.DisplayPacket(true, null, null), 11);
        var after = channel.snapshot().frame(7, frame).orElseThrow();

        assertThat(after.itemEvidence().orElseThrow().itemId()).isNull();
        assertThat(after.itemEvidence().orElseThrow().packetLedgerRevision())
                .isGreaterThan(before.packetLedgerRevision());
        assertThat(after.rotationEvidence()).isEqualTo(before.frame(7, frame).orElseThrow().rotationEvidence());
        assertThat(before.frame(7, frame).orElseThrow().itemEvidence().orElseThrow().itemId())
                .isEqualTo("minecraft:stone");
    }

    @Test
    void absentInitialFieldsStayUnknownAndUnrelatedMetadataIsNotAnAck() {
        var channel = new FrameDisplaySyncSignals.SessionChannel();
        var frame = UUID.randomUUID();
        channel.bindAndSnapshot(UUID.randomUUID());
        channel.record(2, frame, new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", null), 1);
        var before = channel.snapshot();
        assertThat(before.frame(2, frame).orElseThrow().rotationEvidence()).isEmpty();
        channel.record(2, frame, new FrameDisplaySyncSignals.DisplayPacket(false, null, null), 2);
        assertThat(channel.snapshot()).isEqualTo(before);
        channel.record(2, frame, new FrameDisplaySyncSignals.DisplayPacket(false, null, 0), 3);
        var after = channel.snapshot().frame(2, frame).orElseThrow();
        assertThat(after.itemEvidence()).isEqualTo(before.frame(2, frame).orElseThrow().itemEvidence());
        assertThat(after.rotationEvidence().orElseThrow().packetLedgerRevision()).isEqualTo(2);
    }

    @Test
    void removalIdReuseAndSessionRebindCannotReuseAnOldField() {
        var channel = new FrameDisplaySyncSignals.SessionChannel();
        var oldId = UUID.randomUUID();
        var newId = UUID.randomUUID();
        channel.bindAndSnapshot(UUID.randomUUID());
        channel.record(2, oldId, new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", 3), 1);
        channel.record(2, newId, new FrameDisplaySyncSignals.DisplayPacket(false, null, 0), 2);
        assertThat(channel.snapshot().frame(2, oldId)).isEmpty();
        assertThat(channel.snapshot().frame(2, newId).orElseThrow().itemEvidence()).isEmpty();
        channel.remove(2);
        assertThat(channel.snapshot().frame(2, newId)).isEmpty();
        channel.record(2, newId, new FrameDisplaySyncSignals.DisplayPacket(true, null, null), 3);
        assertThat(channel.snapshot().frame(2, newId).orElseThrow().rotationEvidence()).isEmpty();
        var rebound = channel.bindAndSnapshot(UUID.randomUUID());
        assertThat(rebound.frames()).isEmpty();
        assertThat(rebound.packetLedgerRevision()).isZero();
    }

    @Test
    void unboundPacketsAreIgnoredAndRetentionIsBounded() {
        var channel = new FrameDisplaySyncSignals.SessionChannel();
        var frame = UUID.randomUUID();
        var empty = new FrameDisplaySyncSignals.DisplayPacket(true, null, null);
        channel.record(0, frame, empty, 0);
        assertThat(channel.snapshot().frames()).isEmpty();
        channel.bindAndSnapshot(UUID.randomUUID());
        for (int i = 0; i <= FrameDisplaySyncSignals.MAX_TRACKED_FRAMES; i++) {
            channel.record(i, frame, empty, i);
        }
        assertThat(channel.snapshot().frames()).hasSize(FrameDisplaySyncSignals.MAX_TRACKED_FRAMES);
        assertThat(channel.snapshot().frame(0, frame)).isEmpty();
    }

    @Test
    void packetExtractionDoesNotReadLivePredictionAndDistinguishesEmptyFromAbsent() {
        var mutablePacketStack = new ItemStack(Items.STONE);
        var parsed = FrameDisplaySyncSignals.packetFields(List.of(
                new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ITEM_STACK, mutablePacketStack),
                new SynchedEntityData.DataValue<>(9, EntityDataSerializers.INT, 6),
                new SynchedEntityData.DataValue<>(3, EntityDataSerializers.BOOLEAN, true)), 8, 9);
        mutablePacketStack.setCount(0);
        assertThat(parsed).isEqualTo(new FrameDisplaySyncSignals.DisplayPacket(true, "minecraft:stone", 6));
        assertThat(FrameDisplaySyncSignals.packetFields(List.of(
                new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ITEM_STACK, ItemStack.EMPTY)), 8, 9))
                .isEqualTo(new FrameDisplaySyncSignals.DisplayPacket(true, null, null));
        assertThat(FrameDisplaySyncSignals.packetFields(List.of(
                new SynchedEntityData.DataValue<>(3, EntityDataSerializers.INT, 4)), 8, 9))
                .isEqualTo(new FrameDisplaySyncSignals.DisplayPacket(false, null, null));
        assertThatThrownBy(() -> FrameDisplaySyncSignals.packetFields(List.of(
                new SynchedEntityData.DataValue<>(9, EntityDataSerializers.INT, 8)), 8, 9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrameDisplaySyncSignals.packetFields(List.of(
                new SynchedEntityData.DataValue<>(8, EntityDataSerializers.INT, 1)), 8, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
