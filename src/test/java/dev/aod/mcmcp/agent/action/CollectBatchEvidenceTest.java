package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectBatchEvidenceTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String WHEAT = "minecraft:wheat";

    @Test
    void inventoryIncreaseBeforeContactIsNotCarriedForward() {
        var evidence = evidence(twoWheatTargets(), Map.of(WHEAT, 10));

        assertThat(evidence.reconcile(Map.of(WHEAT, 11), 1L, 60L, 0)).isEmpty();
        evidence.recordContact(0, 2L);
        assertThat(evidence.reconcile(Map.of(WHEAT, 11), 2L, 60L, 0)).isEmpty();

        assertThat(evidence.reconcile(Map.of(WHEAT, 12), 3L, 60L, 0))
                .containsExactly(new CollectBatchEvidence.Credit(0, false, 11, 12));
        assertThat(evidence.baselineCount(WHEAT)).isEqualTo(10);
    }

    @Test
    void futureTargetNeedsContactAndAFollowingAbsoluteIncreaseForIncidentalCredit() {
        var evidence = evidence(twoWheatTargets(), Map.of(WHEAT, 10));

        evidence.recordContact(1, 1L);
        assertThat(evidence.reconcile(Map.of(WHEAT, 11), 1L, 60L, 0))
                .containsExactly(new CollectBatchEvidence.Credit(1, true, 10, 11));
        assertThat(evidence.credited(0)).isFalse();
        assertThat(evidence.credited(1)).isTrue();
    }

    @Test
    void sameTickContactsConsumeOnlyTheObservedDeltaInListedOrder() {
        var evidence = evidence(twoWheatTargets(), Map.of(WHEAT, 10));

        evidence.recordContact(1, 5L);
        evidence.recordContact(0, 5L);

        assertThat(evidence.reconcile(Map.of(WHEAT, 12), 5L, 60L, 0))
                .containsExactly(
                        new CollectBatchEvidence.Credit(0, false, 10, 12),
                        new CollectBatchEvidence.Credit(1, true, 10, 12));
    }

    @Test
    void surplusStackDeltaCannotCreditAnUncontactedOrLaterContactedTarget() {
        var evidence = evidence(twoWheatTargets(), Map.of(WHEAT, 0));

        evidence.recordContact(0, 1L);
        assertThat(evidence.reconcile(Map.of(WHEAT, 64), 1L, 60L, 0))
                .containsExactly(new CollectBatchEvidence.Credit(0, false, 0, 64));

        evidence.recordContact(1, 2L);
        assertThat(evidence.reconcile(Map.of(WHEAT, 64), 2L, 60L, 1)).isEmpty();
        assertThat(evidence.reconcile(Map.of(WHEAT, 65), 3L, 60L, 1))
                .containsExactly(new CollectBatchEvidence.Credit(1, false, 64, 65));
    }

    @Test
    void staleContactAndInventoryDecreaseFailClosed() {
        var stale = evidence(twoWheatTargets(), Map.of(WHEAT, 10));
        stale.recordContact(0, 1L);
        assertThat(stale.reconcile(Map.of(WHEAT, 11), 4L, 2L, 0)).isEmpty();

        var decreased = evidence(twoWheatTargets(), Map.of(WHEAT, 10));
        assertThatThrownBy(() -> decreased.reconcile(
                Map.of(WHEAT, 9), 1L, 60L, 0))
                .isInstanceOf(CollectBatchEvidence.InventoryDecreasedException.class);
    }

    @Test
    void itemTypesUseIndependentAbsoluteBaselines() {
        var targets = List.of(
                target(WHEAT, 1.5D),
                target("minecraft:wheat_seeds", 2.5D));
        var evidence = evidence(targets, Map.of(WHEAT, 3, "minecraft:wheat_seeds", 7));
        evidence.recordContact(0, 1L);
        evidence.recordContact(1, 1L);

        assertThat(evidence.reconcile(
                Map.of(WHEAT, 4, "minecraft:wheat_seeds", 9), 1L, 60L, 0))
                .extracting(CollectBatchEvidence.Credit::targetIndex)
                .containsExactly(0, 1);
    }

    private static CollectBatchEvidence evidence(
            List<ActionDsl.CollectTarget> targets, Map<String, Integer> baselines) {
        return new CollectBatchEvidence(targets, baselines);
    }

    private static List<ActionDsl.CollectTarget> twoWheatTargets() {
        return List.of(target(WHEAT, 1.5D), target(WHEAT, 2.5D));
    }

    private static ActionDsl.CollectTarget target(String item, double x) {
        return new ActionDsl.CollectTarget(
                item, new ActionDsl.WorldPosition(DIMENSION, x, 64.1D, 0.5D));
    }
}
