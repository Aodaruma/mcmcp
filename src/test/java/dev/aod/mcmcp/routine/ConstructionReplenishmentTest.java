package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.HotbarPayloadSyncSignals.SlotEvidence;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConstructionReplenishmentTest {
    @Test void refillNeedsConfirmedWorldAndFreshExactPayloadForOwnedSlot() {
        var selected = new StackFingerprint("minecraft:gray_concrete", 64, 123);
        var fresh = new SlotEvidence(selected, 20, 11);
        assertThat(check(true, 64, 64, selected, fresh)).isTrue();
        assertThat(check(true, 1, 64, selected, fresh)).isTrue();
        assertThat(check(false, 64, 64, selected, fresh)).isFalse();
        assertThat(check(true, 64, 64, selected, null)).isFalse();
        assertThat(check(true, 64, 64, selected, new SlotEvidence(selected, 19, 10))).isFalse();
        assertThat(check(true, 64, 62, selected, fresh)).isFalse();
        assertThat(check(true, 0, 64, selected, fresh)).isFalse();
        assertThat(check(true, 64, 64, selected,
                new SlotEvidence(new StackFingerprint("minecraft:gray_concrete", 63, 123), 20, 11))).isFalse();
        assertThat(check(true, 64, 64, selected,
                new SlotEvidence(new StackFingerprint("minecraft:gray_concrete", 64, 124), 20, 11))).isFalse();
        var wrongItem = new StackFingerprint("minecraft:stone", 64, 123);
        assertThat(check(true, 64, 64, wrongItem, new SlotEvidence(wrongItem, 20, 11))).isFalse();
    }

    private boolean check(boolean world, int before, int after, StackFingerprint selected, SlotEvidence payload) {
        return MinecraftApplyBlockPlanPort.replenishmentSynchronized(
                world, before, after, "minecraft:gray_concrete", selected, payload, 10);
    }
}
