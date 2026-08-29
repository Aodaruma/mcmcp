package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.ApplyBlockPlanActionAttempt;
import dev.aod.mcmcp.routine.ApplyBlockPlanActionEvidence;
import dev.aod.mcmcp.routine.ApplyBlockPlanCellObservation;
import dev.aod.mcmcp.routine.ApplyBlockPlanChildAction;
import dev.aod.mcmcp.routine.ApplyBlockPlanFrame;
import dev.aod.mcmcp.routine.ApplyBlockPlanOperation;
import dev.aod.mcmcp.routine.ApplyBlockPlanPort;
import dev.aod.mcmcp.routine.ApplyBlockPlanPreparationAttempt;
import dev.aod.mcmcp.routine.ApplyBlockPlanPreparationEvidence;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.ApplyBlockPlanStep;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.ConstructionSafetyChangedException;
import dev.aod.mcmcp.routine.KnownConstructionRequest;
import dev.aod.mcmcp.routine.PlacementSupportWitness;
import dev.aod.mcmcp.routine.RoutineFailure;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownConstructionAttemptTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final BlockStateFingerprint STONE =
            new BlockStateFingerprint("minecraft:stone", Map.of());

    @Test
    void placesInDeclaredOrderAndReportsOnlyServerConfirmedProgress() {
        var request = request(2);
        var port = new FakePort(request);
        var attempt = new KnownConstructionAttempt(port, request, 1, 601);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_preparing");
        port.tick = 2;
        assertThat(attempt.tick(2).evidence()).isEqualTo("construction_confirming");
        port.tick = 3;
        var first = attempt.tick(3);
        assertThat(first.placedDelta()).isEqualTo(1);
        assertThat(first.completedEntries()).isEqualTo(1);
        assertThat(first.confirmedEntries()).isEqualTo(1);

        port.tick = 4;
        attempt.tick(4);
        port.tick = 5;
        attempt.tick(5);
        port.tick = 6;
        var second = attempt.tick(6);
        assertThat(second.placedDelta()).isEqualTo(1);
        assertThat(second.confirmedEntries()).isEqualTo(2);
        port.tick = 7;
        var result = attempt.tick(7);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);
        assertThat(result.evidence()).isEqualTo("construction_complete");
        assertThat(result.completedEntries()).isEqualTo(2);
        assertThat(port.dispatchedEntryIds).containsExactly("cell0", "cell1");
        assertThat(port.releaseActionCalls).isEqualTo(2);
        assertThat(port.retired).isTrue();
    }

    @Test
    void insufficientWholePlanResourcesFailBeforeAnyPreparationOrPacketBoundary() {
        var request = request(3);
        var port = new FakePort(request);
        port.available = 2;
        var attempt = new KnownConstructionAttempt(port, request, 1, 901);

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_insufficient_resources");
        assertThat(port.beginPreparationCalls).isZero();
        assertThat(port.dispatchedEntryIds).isEmpty();
        assertThat(port.retired).isTrue();
    }

    @Test
    void failedEntryStopsTheEntireSuffixAndReleasesOwnedPreparation() {
        var request = request(3);
        var port = new FakePort(request);
        port.preparationFailureStep = 1;
        var attempt = new KnownConstructionAttempt(port, request, 1, 901);

        attempt.tick(1);
        port.tick = 2;
        attempt.tick(2);
        port.tick = 3;
        attempt.tick(3);
        port.tick = 4;
        attempt.tick(4);
        port.tick = 5;
        var result = attempt.tick(5);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_precondition_changed");
        assertThat(result.placedDelta()).isZero();
        assertThat(result.confirmedEntries()).isEqualTo(1);
        assertThat(port.dispatchedEntryIds).containsExactly("cell0");
        assertThat(port.releasePreparationCalls).isEqualTo(2);
        assertThat(port.retired).isTrue();
    }

    @Test
    void deadlineCannotExceedFifteenSecondsPerEntry() {
        var request = request(2);
        assertThatThrownBy(() -> new KnownConstructionAttempt(
                new FakePort(request), request, 1, 602))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entries*300");
    }

    @Test
    void confirmedDeltaWaitsUntilOwnedActionReleaseIsVerified() {
        var request = request(1);
        var port = new FakePort(request);
        port.releaseActionFailuresRemaining = 1;
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        attempt.tick(1);
        port.tick = 2;
        attempt.tick(2);
        port.tick = 3;
        var pendingRelease = attempt.tick(3);
        assertThat(pendingRelease.evidence()).isEqualTo("construction_releasing");
        assertThat(pendingRelease.placedDelta()).isZero();
        assertThat(pendingRelease.confirmedEntries()).isEqualTo(1);

        port.tick = 4;
        var released = attempt.tick(4);
        assertThat(released.evidence()).isEqualTo("construction_entry_confirmed");
        assertThat(released.placedDelta()).isEqualTo(1);
        assertThat(port.releaseActionCalls).isEqualTo(2);
    }

    @Test
    void dependencyAimIsCheckedOnlyAfterItsEarlierEntryIsConfirmed() {
        var request = dependencyRequest();
        var port = new FakePort(request);
        port.deferSecondAimUntilFirstConfirmation = true;
        var attempt = new KnownConstructionAttempt(port, request, 1, 601);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_preparing");
        port.tick = 2;
        attempt.tick(2);
        port.tick = 3;
        assertThat(attempt.tick(3).placedDelta()).isEqualTo(1);

        // The first confirmed block may no longer be policy-visible after its successor target is
        // considered. It must not be re-required as a target observation.
        port.omitCompletedCells = true;
        port.tick = 4;
        var secondPreparation = attempt.tick(4);
        assertThat(secondPreparation.status()).isEqualTo(KnownConstructionAttempt.Status.RUNNING);
        assertThat(secondPreparation.evidence()).isEqualTo("construction_preparing");
        assertThat(port.beginPreparationCalls).isEqualTo(2);
    }

    @Test
    void preexistingAfterStateIsNotSilentlySkipped() {
        var request = request(1);
        var port = new FakePort(request);
        port.states.put(request.entries().getFirst().target(), STONE);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_precondition_changed");
        assertThat(port.beginPreparationCalls).isZero();
    }

    @Test
    void packetAdjacentSafetyClosureKeepsAFixedSafetyFailure() {
        var request = request(1);
        var port = new FakePort(request);
        port.safetyChangeOnPreparation = true;
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_safety_changed");
        assertThat(port.retired).isTrue();
    }

    private static KnownConstructionRequest request(int count) {
        var entries = new java.util.ArrayList<ApplyBlockPlanStep>();
        for (int index = 0; index < count; index++) {
            BlockTarget target = target(index, 65, 0);
            BlockTarget support = target(index, 64, 0);
            entries.add(new ApplyBlockPlanStep(
                    "cell" + index,
                    ApplyBlockPlanOperation.PLACE,
                    target,
                    AIR,
                    STONE,
                    Optional.of("minecraft:stone"),
                    Optional.of(PlacementSupportWitness.visible(
                            support, "up", STONE))));
        }
        var bounds = new ActionBounds(
                DIMENSION, target(0, 64, 0), target(count - 1, 65, 0),
                0, Math.min(120, count * 15), false);
        return new KnownConstructionRequest(
                new ApplyBlockPlanRequest("copy", 1, 1, entries, bounds));
    }

    private static KnownConstructionRequest dependencyRequest() {
        BlockTarget firstTarget = target(0, 65, 0);
        BlockTarget secondTarget = target(1, 65, 0);
        var entries = List.of(
                new ApplyBlockPlanStep(
                        "base", ApplyBlockPlanOperation.PLACE, firstTarget,
                        AIR, STONE, Optional.of("minecraft:stone"),
                        Optional.of(PlacementSupportWitness.visible(
                                target(0, 64, 0), "up", STONE))),
                new ApplyBlockPlanStep(
                        "side", ApplyBlockPlanOperation.PLACE, secondTarget,
                        AIR, STONE, Optional.of("minecraft:stone"),
                        Optional.of(PlacementSupportWitness.confirmedDependency(
                                firstTarget, "east", STONE, "base"))));
        return new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "copy", 1, 1, entries,
                new ActionBounds(
                        DIMENSION, firstTarget, secondTarget, 0, 30, false)));
    }

    private static BlockTarget target(int x, int y, int z) {
        return new BlockTarget(DIMENSION, x, y, z);
    }

    private static final class FakePort implements ApplyBlockPlanPort {
        private final KnownConstructionRequest request;
        private final Map<BlockTarget, BlockStateFingerprint> states = new LinkedHashMap<>();
        private final java.util.ArrayList<String> dispatchedEntryIds = new java.util.ArrayList<>();
        private long tick = 1;
        private int available;
        private int preparationFailureStep = -1;
        private int beginPreparationCalls;
        private int releasePreparationCalls;
        private int releaseActionCalls;
        private int releaseActionFailuresRemaining;
        private boolean deferSecondAimUntilFirstConfirmation;
        private boolean omitCompletedCells;
        private boolean safetyChangeOnPreparation;
        private boolean retired;
        private ApplyBlockPlanChildAction activeChild;
        private ApplyBlockPlanPreparationAttempt preparation;
        private ApplyBlockPlanActionAttempt action;

        private FakePort(KnownConstructionRequest request) {
            this.request = request;
            this.available = request.entries().size();
            request.entries().forEach(step -> states.put(step.target(), AIR));
        }

        @Override
        public ApplyBlockPlanFrame observe(ApplyBlockPlanRequest ignored) {
            var cells = new LinkedHashMap<BlockTarget, ApplyBlockPlanCellObservation>();
            for (int index = 0; index < request.entries().size(); index++) {
                var step = request.entries().get(index);
                if (omitCompletedCells && !AIR.equals(states.get(step.target()))) {
                    continue;
                }
                boolean aimFeasible = !deferSecondAimUntilFirstConfirmation
                        || index == 0
                        || STONE.equals(states.get(request.entries().getFirst().target()));
                cells.put(step.target(), new ApplyBlockPlanCellObservation(
                        step.target(), Optional.of(states.get(step.target())),
                        true, true, aimFeasible));
            }
            return new ApplyBlockPlanFrame(
                    tick, tick, true, true, true, true, true, true, true,
                    cells, Map.of("minecraft:stone", available),
                    Set.of("minecraft:stone"));
        }

        @Override
        public ApplyBlockPlanPreparationAttempt beginPreparation(
                ApplyBlockPlanRequest ignored,
                ApplyBlockPlanChildAction child,
                long deadline) {
            beginPreparationCalls++;
            if (safetyChangeOnPreparation) {
                throw new ConstructionSafetyChangedException();
            }
            activeChild = child;
            preparation = new ApplyBlockPlanPreparationAttempt(
                    UUID.randomUUID(), child.stepIndex(), tick, tick, deadline);
            return preparation;
        }

        @Override public void maintainPreparation(ApplyBlockPlanPreparationAttempt ignored) { }

        @Override
        public ApplyBlockPlanPreparationEvidence preparationEvidence(
                ApplyBlockPlanPreparationAttempt ignored) {
            RoutineFailure failure = activeChild.stepIndex() == preparationFailureStep
                    ? new RoutineFailure(
                            RoutineFailure.Category.PRECONDITION,
                            "FIXTURE_PRECONDITION",
                            false,
                            RoutineFailure.Recovery.REPLAN,
                            RoutineFailure.Scope.STEP,
                            1, Map.of(), Map.of(), Map.of(), List.of(), false)
                    : null;
            return new ApplyBlockPlanPreparationEvidence(
                    preparation.attemptId(), tick, tick,
                    Optional.of(states.get(activeChild.target())),
                    true, true, true, true, failure);
        }

        @Override
        public void releasePreparation(ApplyBlockPlanPreparationAttempt ignored) {
            releasePreparationCalls++;
        }

        @Override
        public ApplyBlockPlanActionAttempt dispatchPrepared(
                ApplyBlockPlanRequest ignored,
                ApplyBlockPlanChildAction child,
                ApplyBlockPlanPreparationAttempt ignoredPreparation,
                long deadline) {
            dispatchedEntryIds.add(child.entryId());
            activeChild = child;
            action = new ApplyBlockPlanActionAttempt(
                    UUID.randomUUID(), child.stepIndex(), tick, tick, deadline);
            return action;
        }

        @Override public void maintainAction(ApplyBlockPlanActionAttempt ignored) { }

        @Override
        public ApplyBlockPlanActionEvidence actionEvidence(
                ApplyBlockPlanActionAttempt ignored) {
            states.put(activeChild.target(), activeChild.expectedAfter());
            available--;
            return new ApplyBlockPlanActionEvidence(
                    action.attemptId(), tick, tick, true, true,
                    Optional.of(activeChild.expectedAfter()), Map.of(), null);
        }

        @Override
        public void releaseAction(ApplyBlockPlanActionAttempt ignored) {
            releaseActionCalls++;
            if (releaseActionFailuresRemaining-- > 0) {
                throw new IllegalStateException("action release failed once");
            }
        }

        @Override
        public void retire(ApplyBlockPlanRequest ignored) {
            retired = true;
        }
    }
}
