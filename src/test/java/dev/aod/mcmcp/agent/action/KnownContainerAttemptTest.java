package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFiveFrame;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PhaseFiveResult;
import dev.aod.mcmcp.routine.RoutineFailure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownContainerAttemptTest {
    @Test
    void missingSafeHotbarItemReportsFixedCauseAndRemedyBeforeOrAfterBegin() {
        for (boolean beforeBegin : new boolean[] {true, false}) {
            for (String code : List.of("INVENTORY_SAFE_OPEN_HAND_REQUIRED", "INVENTORY_SAFE_OPEN_HAND_UNAVAILABLE")) {
                var port = new FakePort();
                var operation = new KnownContainerAttempt(port, request(), 1, 101);
                var failure = failure(code, Map.of(
                        "item", "private:item_name", "hotbar", "private inventory",
                        "remedy", "arbitrary adapter instructions", "crosshair", "entity"));
                port.tick = 1;
                if (beforeBegin) {
                    port.observationFailure = failure;
                } else {
                    operation.tick(1);
                    port.tick = 2;
                    port.evidenceFailure = failure;
                }
                var result = operation.tick(port.tick);
                assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.FAILED);
                assertThat(result.evidence()).isEqualTo(code.toLowerCase(java.util.Locale.ROOT));
                assertThat(result.diagnostics()).containsExactly(
                        "safe_open_hand=no_side_effect_free_hotbar_item",
                        "remedy=prepare_empty_hotbar_slot_or_plain_material_or_safe_mining_tool");
                assertThat(result.interactionDelta()).isZero();
                assertThat(result.items()).isEmpty();
                assertThat(result.effects()).isEmpty();
                operation.close();
            }
        }
    }

    @Test
    void unrelatedFailuresCannotInjectSafeHandRemediesOrReflectAdapterDetails() {
        for (boolean beforeBegin : new boolean[] {true, false}) {
            for (String code : List.of("OTHER_FAILURE", "INVENTORY_SAFE_OPEN_HAND_CHANGED")) {
                var port = new FakePort();
                var operation = new KnownContainerAttempt(port, request(), 1, 101);
                var failure = failure(code, Map.of(
                        "safe_open_hand", "no_side_effect_free_hotbar_item",
                        "remedy", "private instructions", "item", "private:item_name"));
                port.tick = 1;
                if (beforeBegin) {
                    port.observationFailure = failure;
                } else {
                    operation.tick(1);
                    port.tick = 2;
                    port.evidenceFailure = failure;
                }
                var result = operation.tick(port.tick);
                assertThat(result.evidence()).isEqualTo(code.toLowerCase(java.util.Locale.ROOT));
                assertThat(result.diagnostics()).isEmpty();
                assertThat(result.items()).isEmpty();
                assertThat(result.effects()).isEmpty();
                operation.close();
            }
        }
    }

    @Test
    void retainsCompatibleAimFailureCodeAndOnlyFixedCrosshairDiagnostics() {
        for (boolean beforeBegin : new boolean[] {true, false}) {
            for (String kind : List.of("entity", "block_other", "miss", "unavailable", "world_border")) {
                var port = new FakePort();
                var operation = new KnownContainerAttempt(port, request(), 1, 101);
                var failure = failure("CONTAINER_AIM_OCCLUDED", Map.of(
                        "crosshair", kind, "target", "private coordinates",
                        "entity_id", "private identity"));
                port.tick = 1;
                if (beforeBegin) {
                    port.observationFailure = failure;
                } else {
                    operation.tick(1);
                    port.tick = 2;
                    port.evidenceFailure = failure;
                }
                var result = operation.tick(port.tick);
                assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.FAILED);
                assertThat(result.evidence()).isEqualTo("container_aim_occluded");
                assertThat(result.diagnostics()).containsExactly("container_crosshair=" + kind);
                assertThat(result.interactionDelta()).isZero();
                assertThat(result.items()).isEmpty();
                assertThat(result.effects()).isEmpty();
                operation.close();
            }
        }
    }

    @Test
    void unknownCrosshairValuesAndOtherFailuresNeverReflectAdditionalAdapterData() {
        for (Map<String, Object> observed : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("crosshair", "entity:private-id at private-position"),
                Map.<String, Object>of("crosshair", Map.of("secret", "private-data")))) {
            var port = new FakePort();
            port.tick = 1;
            port.observationFailure = failure("CONTAINER_AIM_OCCLUDED", observed);
            var operation = new KnownContainerAttempt(port, request(), 1, 101);
            var result = operation.tick(1);
            assertThat(result.evidence()).isEqualTo("container_aim_occluded");
            assertThat(result.diagnostics()).isEmpty();
            operation.close();
        }
        var port = new FakePort();
        port.tick = 1;
        port.observationFailure = failure("OTHER_FAILURE", Map.of("crosshair", "entity"));
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        var result = operation.tick(1);
        assertThat(result.evidence()).isEqualTo("other_failure");
        assertThat(result.diagnostics()).isEmpty();
        operation.close();
    }

    private static RoutineFailure failure(String code, Map<String, Object> observed) {
        return new RoutineFailure(RoutineFailure.Category.PRECONDITION, code, false,
                RoutineFailure.Recovery.REPLAN, RoutineFailure.Scope.STEP, 1,
                Map.of(), observed, Map.of("private", "must not be reflected"), List.of(), false);
    }

    @Test
    void fullInspectionPreservesAll54TypesAndOriginalSnapshotAcrossReleaseRetry() {
        var port = new FakePort();
        port.completeInspection = true;
        port.items = java.util.stream.IntStream.range(0, 54).mapToObj(index ->
                Map.<String, Object>of("item", "minecraft:item_%02d".formatted(index), "count", 64))
                .toList();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        for (int tick = 1; tick <= 2; tick++) { port.tick = tick; operation.tick(tick); }
        port.releaseFailuresRemaining = 1;
        port.tick = 3;
        assertThat(operation.tick(3).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        assertThatThrownBy(operation::inspectionContents).isInstanceOf(IllegalStateException.class);
        port.items = List.of(); // Later evidence must not replace the captured immutable snapshot.
        port.tick = 4;
        assertThat(operation.tick(4).status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(operation.inspectionContents().items()).hasSize(54);
        assertThat(operation.inspectionContents().observedClientTick()).isEqualTo(2);
        assertThat(operation.inspectionContents().packetRevision()).isEqualTo(2);
    }

    @Test
    void missingOrTruncatedInspectionCannotBePublishedAsComplete() {
        for (boolean markedComplete : new boolean[] {false, true}) {
            var port = new FakePort();
            port.completeInspection = markedComplete;
            port.truncated = true;
            var operation = new KnownContainerAttempt(port, request(), 1, 101);
            for (int tick = 1; tick <= 2; tick++) { port.tick = tick; operation.tick(tick); }
            port.tick = 3;
            if (markedComplete) {
                assertThatThrownBy(() -> operation.tick(3)).isInstanceOf(IllegalStateException.class);
                operation.close();
            } else {
                operation.tick(3);
            }
            assertThatThrownBy(operation::inspectionContents).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void reportsLargeChestTotalsAbovePlayerCapacityAfterConfirmedRelease() {
        for (int count : new int[] {2_305, 3_000, 3_456}) {
            var port = new FakePort();
            port.items = List.of(Map.of("item", "minecraft:cobblestone", "count", count));
            var operation = new KnownContainerAttempt(port, request(), 1, 101);
            port.tick = 1;
            operation.tick(1);
            port.tick = 2;
            operation.tick(2);
            port.tick = 3;
            var result = operation.tick(3);
            assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
            assertThat(result.items()).containsExactly(
                    new KnownContainerAttempt.ItemCount("minecraft:cobblestone", count));
            assertThat(result.interactionDelta()).isOne();
            assertThat(result.effects()).isEmpty();
            assertThat(port.releases).isOne();
            assertThat(port.retires).isOne();
        }
    }

    @Test
    void aggregateStillRejectsCountsOutsideSupportedDoubleChestCapacity() {
        assertThatThrownBy(() -> new KnownContainerAttempt.ItemCount("minecraft:stone", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownContainerAttempt.ItemCount("minecraft:stone", 3_457))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyServerConfirmedItemsAndAccountsForTheActualOpen() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);

        port.tick = 1;
        assertThat(operation.tick(1).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);

        port.tick = 2;
        assertThat(operation.tick(2).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        assertThat(port.maintained).isTrue();

        port.tick = 3;
        var result = operation.tick(3);
        assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(result.interactionDelta()).isOne();
        assertThat(result.items()).containsExactly(
                new KnownContainerAttempt.ItemCount("minecraft:iron_hoe", 1),
                new KnownContainerAttempt.ItemCount("minecraft:wheat_seeds", 64));
        assertThat(port.releases).isOne();
        assertThat(port.retires).isOne();
    }

    @Test
    void closeRetriesTheSameRoutedAttemptAfterATransientReleaseFailure() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        assertThat(operation.tick(1).status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        port.releaseFailuresRemaining = 1;

        assertThatThrownBy(operation::close).hasMessage("container release failed once");
        operation.close();

        assertThat(port.releases).isEqualTo(2);
        assertThat(port.retires).isOne();
    }

    @Test
    void faultedCleanupRetainsOwnerUntilLateEvidenceConfirmsRelease() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.releaseFailuresRemaining = 4;
        port.extraBasis = Map.of("release_fault", true);

        for (int retry = 0; retry < 4; retry++) {
            assertThatThrownBy(operation::close).hasMessage("container release failed once");
            assertThat(operation.releaseStatus()).isEqualTo(KnownContainerAttempt.ReleaseStatus.FAULT);
            assertThat(port.retires).isZero();
        }

        port.extraBasis = Map.of();
        operation.close();
        assertThat(operation.releaseStatus()).isEqualTo(KnownContainerAttempt.ReleaseStatus.CONFIRMED);
        assertThat(port.retires).isOne();
        operation.close();
        assertThat(port.releases).isEqualTo(5);
        assertThat(port.retires).isOne();
        assertThat(operation.drainReleaseInteractionDelta()).isZero();
    }

    @Test
    void cleanupUsageRemainsDrainableWhileReleaseIsRetried() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.releaseFailuresRemaining = 1;
        port.releaseAddsInteraction = true;

        assertThatThrownBy(operation::close).hasMessage("container release failed once");
        assertThat(operation.releaseStatus())
                .isEqualTo(KnownContainerAttempt.ReleaseStatus.PROGRESSING);
        assertThat(operation.drainReleaseInteractionDelta()).isOne();
        assertThat(port.retires).isZero();

        operation.close();
        assertThat(port.retires).isOne();
    }

    @Test
    void successfulEvidenceStaysPrivateWhileReleaseProgressesAcrossTicks() {
        var port = new FakePort();
        var operation = new KnownContainerAttempt(port, request(), 1, 4);
        port.tick = 1;
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);
        port.releaseFailuresRemaining = 1;
        port.releaseAddsInteraction = true;

        port.tick = 3;
        var releasing = operation.tick(3);
        assertThat(releasing.status()).isEqualTo(KnownContainerAttempt.Status.RUNNING);
        assertThat(releasing.items()).isEmpty();
        assertThat(operation.releaseStatus())
                .isEqualTo(KnownContainerAttempt.ReleaseStatus.PROGRESSING);
        assertThat(port.releases).isOne();
        assertThat(port.retires).isZero();

        port.tick = 4;
        var released = operation.tick(4);
        assertThat(released.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(released.interactionDelta()).isOne();
        assertThat(released.items()).containsExactly(
                new KnownContainerAttempt.ItemCount("minecraft:iron_hoe", 1),
                new KnownContainerAttempt.ItemCount("minecraft:wheat_seeds", 64));
        assertThat(port.releases).isEqualTo(2);
        assertThat(port.retires).isOne();
    }

    @Test
    void recordsOnlyTheExactServerConfirmedContainerTransferCounts() {
        var port = new FakePort();
        port.exactTransfer = true;
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);
        port.tick = 3;

        var result = operation.tick(3);

        assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.SUCCEEDED);
        assertThat(result.effects()).singleElement().satisfies(effect -> {
            assertThat(effect.observedBefore()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("source_count", 12, "destination_count", 3));
            assertThat(effect.observedAfter()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("source_count", 7, "destination_count", 8, "transferred", 5));
            assertThat(effect.verification())
                    .isEqualTo(AgentActionStore.Verification.CONFIRMED);
            assertThat(effect.clientTick()).isEqualTo(3L);
            assertThat(effect.worldRevision()).isEqualTo(2L);
        });
    }

    @Test
    void inconsistentReadbackRetainsObservedCountsWithoutClaimingAMoveOrReflectingUnknownReasons() {
        for (String reason : List.of("transfer_readback_did_not_confirm_exact_full_stack_move",
                "unknown private adapter text")) {
            var port = new FakePort();
            port.exactTransfer = true;
            var operation = new KnownContainerAttempt(port, request(), 1, 101);
            port.tick = 1;
            operation.tick(1);
            port.tick = 2;
            operation.tick(2);
            port.inconclusiveReason = reason;
            port.extraBasis = Map.of("open_count", 2, "container_clicks", 1,
                    "source_before", 64, "destination_before", 0,
                    "source_after", 64, "destination_after", 64,
                    "transfer_readback_observed", true);
            port.tick = 3;

            var result = operation.tick(3);
            assertThat(result.status()).isEqualTo(KnownContainerAttempt.Status.FAILED);
            assertThat(result.evidence()).isEqualTo("container_ambiguous");
            assertThat(result.diagnostics()).containsExactlyElementsOf(
                    reason.startsWith("transfer_readback_")
                            ? List.of("container_transfer_readback_mismatch") : List.of());
            operation.close();
            assertThat(operation.drainEffectDeltas()).singleElement().satisfies(effect -> {
                assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
                assertThat(effect.observedBefore()).containsExactlyInAnyOrderEntriesOf(
                        Map.of("source_count", 64, "destination_count", 0));
                assertThat(effect.observedAfter()).containsExactlyInAnyOrderEntriesOf(
                        Map.of("source_count", 64, "destination_count", 64));
                assertThat(effect.observedAfter()).doesNotContainKey("transferred");
            });
            assertThat(port.releases).isOne();
        }
    }

    @Test
    void unobservedAfterCountsNeverBecomeEvidenceWhenAClickWasOnlyPredicted() {
        var port = new FakePort();
        port.exactTransfer = true;
        port.holdPending = true;
        port.extraBasis = Map.of("source_before", 64, "destination_before", 0,
                "source_after", 0, "destination_after", 64, "transfer_readback_observed", false);
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);
        operation.close();

        assertThat(operation.drainEffectDeltas()).singleElement().satisfies(effect -> {
            assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(effect.observedBefore()).containsEntry("source_count", 64);
            assertThat(effect.observedAfter()).isEmpty();
        });
    }

    @Test
    void closingAnUnconfirmedTransferDispatchRecordsUnknownWithoutAnAfterCount() {
        var port = new FakePort();
        port.exactTransfer = true;
        port.holdPending = true;
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        port.tick = 1;
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);

        operation.close();

        assertThat(operation.drainEffectDeltas()).singleElement().satisfies(effect -> {
            assertThat(effect.verification())
                    .isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(effect.observedAfter()).isEmpty();
        });
        assertThat(operation.drainEffectDeltas()).isEmpty();
    }

    @Test
    void interruptedBatchSeparatesConfirmedPrefixFromUnknownLastClickAndDoesNotDuplicateOnReleaseRetry() {
        for (boolean readback : List.of(false, true)) {
            var port = new FakePort();
            port.holdPending = true;
            port.releaseFailuresRemaining = 1;
            var basis = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "open_count", 2, "container_clicks", 3,
                    "source_before", 200, "destination_before", 2400,
                    "confirmed_transfer_count", 128, "confirmed_stack_moves", 2,
                    "confirmed_source_count", 72, "confirmed_destination_count", 2528,
                    "transfer_in_flight", true, "pending_stack_count", 64));
            basis.putAll(Map.of("pending_source_before", 72, "pending_destination_before", 2528,
                    "transfer_readback_observed", readback, "source_after", 72, "destination_after", 2592));
            port.extraBasis = basis;
            port.tick = 1;
            var operation = new KnownContainerAttempt(port, request(), 1, 101);
            operation.tick(1);
            port.tick = 2;
            assertThat(operation.tick(2).effects()).isEmpty();
            assertThatThrownBy(operation::close).isInstanceOf(IllegalStateException.class);
            var effects = operation.drainEffectDeltas();
            assertThat(effects).hasSize(2);
            assertThat(effects.get(0).verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED);
            assertThat(effects.get(0).observedBefore()).containsEntry("source_count", 200);
            assertThat(effects.get(0).observedAfter()).containsEntry("source_count", 72)
                    .containsEntry("destination_count", 2528).containsEntry("transferred", 128);
            assertThat(effects.get(1).verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(effects.get(1).observedBefore()).containsEntry("source_count", 72)
                    .containsEntry("destination_count", 2528);
            assertThat(effects.get(1).observedAfter()).doesNotContainKey("transferred");
            if (readback) assertThat(effects.get(1).observedAfter()).containsEntry("destination_count", 2592);
            else assertThat(effects.get(1).observedAfter()).isEmpty();
            operation.close();
            assertThat(operation.drainEffectDeltas()).isEmpty();
        }
    }

    @Test
    void stoppingBetweenBatchClicksRecordsOnlyConfirmedPrefix() {
        var port = new FakePort();
        port.holdPending = true;
        port.extraBasis = Map.of("container_clicks", 2,
                "source_before", 200, "destination_before", 0,
                "confirmed_transfer_count", 128,
                "confirmed_source_count", 72, "confirmed_destination_count", 128,
                "transfer_in_flight", false);
        port.tick = 1;
        var operation = new KnownContainerAttempt(port, request(), 1, 101);
        operation.tick(1);
        port.tick = 2;
        operation.tick(2);
        operation.close();
        assertThat(operation.drainEffectDeltas()).singleElement().satisfies(effect -> {
            assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED);
            assertThat(effect.observedAfter()).containsEntry("transferred", 128);
        });
    }

    private static PhaseFiveRequest request() {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        return new PhaseFiveRequest(
                "transfer_items",
                Map.of(),
                new PhaseFiveBounds(target.dimension(), target, target, 0, 120, false),
                0,
                "items");
    }

    private static final class FakePort implements PhaseFivePort {
        private List<Map<String, Object>> items = List.of(
                Map.of("item", "minecraft:iron_hoe", "count", 1),
                Map.of("item", "minecraft:wheat_seeds", "count", 64));
        private long tick;
        private PhaseFiveAttempt attempt;
        private boolean maintained;
        private int releases;
        private int retires;
        private int releaseFailuresRemaining;
        private int interactions;
        private boolean releaseAddsInteraction;
        private boolean releasePending;
        private boolean releaseConfirmed;
        private boolean exactTransfer;
        private boolean holdPending;
        private boolean completeInspection;
        private boolean truncated;
        private RoutineFailure observationFailure;
        private RoutineFailure evidenceFailure;
        private Map<String, Object> extraBasis = Map.of();
        private String inconclusiveReason;

        @Override
        public PhaseFiveFrame observe(PhaseFiveRequest request) {
            return new PhaseFiveFrame(tick, maintained ? 2 : 1, observationFailure);
        }

        @Override
        public PhaseFiveAttempt begin(
                UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
            attempt = new PhaseFiveAttempt(
                    routineId, request.kind(), tick, 1, hardDeadlineClientTick, Map.of());
            return attempt;
        }

        @Override
        public void maintain(PhaseFiveAttempt attempt) {
            maintained = true;
            interactions = Math.max(interactions, exactTransfer ? 2 : 1);
        }

        @Override
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            var basis = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "open_count", Math.min(interactions, 1),
                    "container_clicks", Math.max(0, interactions - 1),
                    "recipe_placements", 0,
                    "release_pending", releasePending,
                    "release_confirmed", releaseConfirmed,
                    "release_fault", false));
            basis.putAll(extraBasis);
            if (evidenceFailure != null) {
                return new PhaseFiveEvidence.Failed(attempt.attemptId(), tick,
                        maintained ? 2 : 1, evidenceFailure, basis);
            }
            if (inconclusiveReason != null) {
                return new PhaseFiveEvidence.Inconclusive(attempt.attemptId(), tick,
                        maintained ? 2 : 1, PhaseFiveEvidence.Certainty.AMBIGUOUS, inconclusiveReason, basis);
            }
            if (!maintained || holdPending) {
                return new PhaseFiveEvidence.Pending(attempt.attemptId(), tick, 1, basis);
            }
            var resultBasis = new java.util.LinkedHashMap<String, Object>();
            resultBasis.put("available_source_items", items);
            if (completeInspection) {
                resultBasis.put("complete_container_inspection", true);
                resultBasis.put("available_source_items_truncated", truncated);
                resultBasis.put("contents_world_session_id", "550e8400-e29b-41d4-a716-446655440000");
                resultBasis.put("contents_observed_tick", 2L);
                resultBasis.put("contents_packet_revision", 2L);
            }
            if (exactTransfer) {
                resultBasis.put("source_count_before", 12);
                resultBasis.put("source_count_after", 7);
                resultBasis.put("destination_count_before", 3);
                resultBasis.put("destination_count_after", 8);
                resultBasis.put("transferred", 5);
            }
            var result = new PhaseFiveResult(0, true, resultBasis, List.of());
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), tick, 2, result, basis);
        }

        @Override
        public void release(PhaseFiveAttempt attempt) {
            releases++;
            releasePending = true;
            if (releaseAddsInteraction) {
                interactions++;
                releaseAddsInteraction = false;
            }
            if (releaseFailuresRemaining-- > 0) {
                throw new IllegalStateException("container release failed once");
            }
            releasePending = false;
            releaseConfirmed = true;
        }

        @Override
        public void retire(PhaseFiveRequest request) {
            retires++;
        }
    }
}
