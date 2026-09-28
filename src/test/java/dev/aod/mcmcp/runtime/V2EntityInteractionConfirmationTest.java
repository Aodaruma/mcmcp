package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.HotbarPayloadSyncSignals.SlotEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V2EntityInteractionConfirmationTest {
    @Test
    void unrelatedPickupAfterPassCannotConfirmDefaultInteraction() {
        var before = new StackFingerprint("minecraft:wheat", 1, 40);
        var pickedUp = new StackFingerprint("minecraft:wheat", 2, 40);
        var held = new V2HeldStackEvidence(before, 10);
        held.observe(new SlotEvidence(pickedUp, 100, 11), pickedUp);
        assertThat(held.changed()).isTrue();
        assertThat(MinecraftV2EntityInteractDriver.confirmed(false, null, held)).isFalse();
        assertThat(MinecraftV2EntityInteractDriver.confirmed(true, null, held)).isTrue();
    }

    @Test
    void passRequiresExplicitFreshServerPostcondition() {
        var bucket = new StackFingerprint("minecraft:bucket", 1, 1);
        var milk = new StackFingerprint("minecraft:milk_bucket", 1, 2);
        var held = new V2HeldStackEvidence(bucket, 10);
        assertThat(MinecraftV2EntityInteractDriver.confirmed(false, milk.itemId(), held)).isFalse();
        held.observe(new SlotEvidence(milk, 100, 10), milk);
        assertThat(MinecraftV2EntityInteractDriver.confirmed(true, milk.itemId(), held)).isFalse();
        held.observe(new SlotEvidence(milk, 100, 11), milk);
        assertThat(MinecraftV2EntityInteractDriver.confirmed(false, milk.itemId(), held)).isTrue();
        assertThat(MinecraftV2EntityInteractDriver.confirmed(false, null, held)).isFalse();
        held.observe(new SlotEvidence(milk, 100, 11), bucket);
        assertThat(MinecraftV2EntityInteractDriver.confirmed(false, milk.itemId(), held)).isFalse();
    }
}
