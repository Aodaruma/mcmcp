package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.HotbarPayloadSyncSignals.SlotEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V2HeldStackEvidenceTest {
    private static final StackFingerprint BUCKET = new StackFingerprint("minecraft:bucket", 1, 1);
    private static final StackFingerprint MILK = new StackFingerprint("minecraft:milk_bucket", 1, 2);

    @Test
    void clientPredictionAndOldPayloadCannotSatisfyRequestedItem() {
        var evidence = new V2HeldStackEvidence(BUCKET, 10);
        evidence.observe(null, MILK);
        assertThat(evidence.matches(MILK.itemId())).isFalse();
        evidence.observe(new SlotEvidence(MILK, 99, 10), MILK);
        assertThat(evidence.changed()).isFalse();
        evidence.observe(new SlotEvidence(MILK, 100, 11), BUCKET);
        assertThat(evidence.after()).isNull();
        evidence.observe(new SlotEvidence(MILK, 100, 11), MILK);
        assertThat(evidence.changed()).isTrue();
        assertThat(evidence.matches(MILK.itemId())).isTrue();
    }

    @Test
    void laterMismatchRevokesPreviouslyMatchedPostcondition() {
        var evidence = new V2HeldStackEvidence(BUCKET, 10);
        evidence.observe(new SlotEvidence(MILK, 100, 11), MILK);
        evidence.observe(new SlotEvidence(MILK, 100, 11), BUCKET);
        assertThat(evidence.matches(MILK.itemId())).isFalse();
        assertThat(evidence.changed()).isFalse();
        evidence.observe(new SlotEvidence(BUCKET, 101, 12), BUCKET);
        assertThat(evidence.matches(BUCKET.itemId())).isTrue();
        assertThat(evidence.changed()).isFalse();
    }

    @Test
    void damageAndQuantityChangesCountOnlyWithMatchingServerPayload() {
        var tool = new StackFingerprint("minecraft:shears", 1, 30);
        var used = new StackFingerprint("minecraft:shears", 1, 31);
        var evidence = new V2HeldStackEvidence(tool, 10);
        evidence.observe(new SlotEvidence(used, 100, 11), used);
        assertThat(evidence.changed()).isTrue();
        var food = new StackFingerprint("minecraft:wheat", 2, 40);
        evidence = new V2HeldStackEvidence(food, 10);
        var remaining = new StackFingerprint("minecraft:wheat", 1, 40);
        evidence.observe(new SlotEvidence(remaining, 100, 11), remaining);
        assertThat(evidence.changed()).isTrue();
    }
}
