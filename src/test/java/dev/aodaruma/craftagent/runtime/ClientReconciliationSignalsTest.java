package dev.aodaruma.craftagent.runtime;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientReconciliationSignalsTest {
    @Test
    void revisionsAreIndependentAndSelectedInventoryRequiresRelevantPacket() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        var session = UUID.randomUUID();
        var baseline = channel.bindAndSnapshot(session);

        channel.positionCorrection(new ClientReconciliationSignals.PositionCorrection(
                7, new Vec3(1, 2, 3)));
        channel.serverRotation(new ClientReconciliationSignals.ServerRotation(90, 15));
        channel.localMotion(new ClientReconciliationSignals.LocalMotion(
                new Vec3(0.1, 0, 0), "set_entity_motion"));
        channel.inventorySync(new ClientReconciliationSignals.InventorySync(
                "container_slot", 12, false, "minecraft:bucket", 1));
        channel.inventorySync(new ClientReconciliationSignals.InventorySync(
                "player_inventory", 0, true, "minecraft:milk_bucket", 1));
        var current = channel.snapshot();

        assertThat(baseline.positionCorrectionRevision()).isZero();
        assertThat(current.positionCorrectionRevision()).isOne();
        assertThat(current.rotationRevision()).isOne();
        assertThat(current.motionRevision()).isOne();
        assertThat(current.inventoryRevision()).isEqualTo(2);
        assertThat(current.selectedSlotInventoryRevision()).isOne();
        assertThat(current.lastPositionCorrection().teleportId()).isEqualTo(7);
        assertThat(current.lastInventorySync().selectedItemId()).isEqualTo("minecraft:milk_bucket");
    }

    @Test
    void rebindingAWorldSessionDropsAllPriorEvidence() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        var first = UUID.randomUUID();
        channel.bindAndSnapshot(first);
        channel.positionCorrection(new ClientReconciliationSignals.PositionCorrection(
                3, Vec3.ZERO));

        var second = channel.bindAndSnapshot(UUID.randomUUID());

        assertThat(second.worldSessionId()).isNotEqualTo(first);
        assertThat(second.positionCorrectionRevision()).isZero();
        assertThat(second.lastPositionCorrection()).isNull();
        assertThat(second.sameSession(channel.bindAndSnapshot(second.worldSessionId()))).isTrue();
    }
}
