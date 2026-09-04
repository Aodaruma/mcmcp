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
import java.util.Objects;
import java.util.Optional;
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
        assertThat(first.effects()).singleElement().satisfies(effect -> {
            assertThat(effect.kind()).isEqualTo("block_place");
            assertThat(effect.subject())
                    .isEqualTo("block:minecraft:overworld:0,65,0");
            assertThat(effect.observedBefore()).containsEntry("block", "minecraft:air");
            assertThat(effect.observedAfter()).containsEntry("block", "minecraft:stone");
            assertThat(effect.verification())
                    .isEqualTo(AgentActionStore.Verification.CONFIRMED);
            assertThat(effect.clientTick()).isEqualTo(3L);
            assertThat(effect.worldRevision()).isEqualTo(3L);
        });

        port.tick = 4;
        attempt.tick(4);
        port.tick = 5;
        attempt.tick(5);
        port.tick = 6;
        var second = attempt.tick(6);
        assertThat(second.placedDelta()).isEqualTo(1);
        assertThat(second.confirmedEntries()).isEqualTo(2);
        port.tick = 7;
        var verifying = attempt.tick(7);
        assertThat(verifying.status()).isEqualTo(KnownConstructionAttempt.Status.RUNNING);
        assertThat(verifying.evidence()).isEqualTo("construction_final_verifying");
        port.tick = 8;
        var result = attempt.tick(8);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);
        assertThat(result.evidence()).isEqualTo("construction_complete");
        assertThat(result.completedEntries()).isEqualTo(2);
        assertThat(port.dispatchedEntryIds).containsExactly("cell0", "cell1");
        assertThat(port.releaseActionCalls).isEqualTo(2);
        assertThat(port.retired).isTrue();
    }

    @Test
    void clearsThenRequiresAFreshExactAirObservation() {
        var request = clearRequest();
        var port = new FakePort(request);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_preparing");
        port.tick = 2;
        assertThat(attempt.tick(2).evidence()).isEqualTo("construction_confirming");
        port.tick = 3;
        var confirmed = attempt.tick(3);
        assertThat(confirmed.brokenDelta()).isEqualTo(1);
        assertThat(confirmed.placedDelta()).isZero();
        assertThat(confirmed.effects()).singleElement().satisfies(effect -> {
            assertThat(effect.kind()).isEqualTo("block_break");
            assertThat(effect.observedBefore()).containsEntry("block", "minecraft:stone");
            assertThat(effect.observedAfter()).containsEntry("block", "minecraft:air");
            assertThat(effect.verification())
                    .isEqualTo(AgentActionStore.Verification.CONFIRMED);
        });
        port.tick = 4;
        assertThat(attempt.tick(4).evidence()).isEqualTo("construction_final_verifying");
        port.tick = 5;

        assertThat(attempt.tick(5).status())
                .isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);
        assertThat(port.dispatchedEntryIds).containsExactly("clear0");
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
    void closingAfterDispatchRecordsOneUnknownEffectWithoutInventingAnAfterState() {
        var request = request(1);
        var port = new FakePort(request);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);
        attempt.tick(1);
        port.tick = 2;
        assertThat(attempt.tick(2).evidence()).isEqualTo("construction_confirming");

        attempt.close();
        var effects = attempt.drainEffectDeltas();
        assertThat(effects).singleElement().satisfies(effect -> {
            assertThat(effect.kind()).isEqualTo("block_place");
            assertThat(effect.observedBefore()).containsEntry("block", "minecraft:air");
            assertThat(effect.observedAfter()).isEmpty();
            assertThat(effect.verification())
                    .isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(effect.clientTick()).isEqualTo(2L);
            assertThat(effect.worldRevision()).isEqualTo(2L);
        });
        assertThat(attempt.drainEffectDeltas()).isEmpty();
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
    void resumedExactDependencyAllowsItsPendingSuccessor() {
        var request = dependencyRequest();
        var port = new FakePort(request);
        port.deferSecondAimUntilFirstConfirmation = true;
        port.states.put(request.entries().getFirst().target(), STONE);
        var attempt = new KnownConstructionAttempt(port, request, 1, 601);

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.RUNNING);
        assertThat(result.evidence()).isEqualTo("construction_preparing");
        assertThat(result.completedEntries()).isEqualTo(1);
        assertThat(result.confirmedEntries()).isZero();
        assertThat(port.activeChild.entryId()).isEqualTo("side");
        assertThat(port.dispatchedEntryIds).isEmpty();
    }

    @Test
    void preexistingExactAfterStateResumesWithoutDispatchOrPlacementCharge() {
        var request = request(1);
        var port = new FakePort(request);
        port.states.put(request.entries().getFirst().target(), STONE);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        var verifying = attempt.tick(1);
        assertThat(verifying.status()).isEqualTo(KnownConstructionAttempt.Status.RUNNING);
        assertThat(verifying.evidence()).isEqualTo("construction_final_verifying");
        assertThat(verifying.placedDelta()).isZero();
        assertThat(verifying.completedEntries()).isEqualTo(1);
        assertThat(verifying.confirmedEntries()).isZero();

        port.tick = 2;
        var result = attempt.tick(2);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);
        assertThat(result.evidence()).isEqualTo("construction_complete");
        assertThat(result.placedDelta()).isZero();
        assertThat(result.completedEntries()).isEqualTo(1);
        assertThat(result.confirmedEntries()).isZero();
        assertThat(port.beginPreparationCalls).isZero();
        assertThat(port.dispatchedEntryIds).isEmpty();
        assertThat(port.retired).isTrue();
    }

    @Test
    void freshFinalVerificationRejectsAResumedEntryThatChanged() {
        var request = request(1);
        var port = new FakePort(request);
        port.states.put(request.entries().getFirst().target(), STONE);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_final_verifying");
        port.states.put(request.entries().getFirst().target(), AIR);
        port.tick = 2;
        var result = attempt.tick(2);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_precondition_changed");
        assertThat(result.placedDelta()).isZero();
        assertThat(port.beginPreparationCalls).isZero();
        assertThat(port.dispatchedEntryIds).isEmpty();
        assertThat(port.retired).isTrue();
    }

    @Test
    void neighborDerivedImmediateStateStillRequiresExactFinalComponentState() {
        var finalConnected = new BlockStateFingerprint("minecraft:glass_pane", Map.of(
                "north", "false", "east", "true", "south", "false", "west", "false",
                "waterlogged", "false"));
        var immediateUnconnected = new BlockStateFingerprint("minecraft:glass_pane", Map.of(
                "north", "false", "east", "false", "south", "false", "west", "false",
                "waterlogged", "false"));
        BlockTarget placed = target(0, 65, 0);
        var step = new ApplyBlockPlanStep(
                "pane", ApplyBlockPlanOperation.PLACE, placed, AIR, finalConnected,
                Optional.of("minecraft:glass_pane"),
                Optional.of(PlacementSupportWitness.visible(
                        target(0, 64, 0), "up", STONE)));
        var request = new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "pane", 1, 1, List.of(step),
                new ActionBounds(DIMENSION, target(0, 64, 0), placed, 0, 15, false)));
        var port = new FakePort(request);
        port.packetAfterStates.put(placed, immediateUnconnected);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_preparing");
        port.tick = 2;
        assertThat(attempt.tick(2).evidence()).isEqualTo("construction_confirming");
        port.tick = 3;
        assertThat(attempt.tick(3).evidence()).isEqualTo("construction_entry_confirmed");
        port.states.put(placed, finalConnected);
        port.tick = 4;
        assertThat(attempt.tick(4).evidence()).isEqualTo("construction_final_verifying");
        port.tick = 5;
        assertThat(attempt.tick(5).status())
                .isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);

        var mismatchPort = new FakePort(request);
        mismatchPort.packetAfterStates.put(placed, immediateUnconnected);
        var mismatch = new KnownConstructionAttempt(mismatchPort, request, 1, 301);
        mismatch.tick(1);
        mismatchPort.tick = 2;
        mismatch.tick(2);
        mismatchPort.tick = 3;
        mismatch.tick(3);
        mismatchPort.tick = 4;
        mismatch.tick(4);
        mismatchPort.tick = 5;
        assertThat(mismatch.tick(5).status())
                .isEqualTo(KnownConstructionAttempt.Status.FAILED);
    }

    @Test
    void serverAcknowledgedStraightStairMayBecomeAnExactPlannedCornerAtFinalVerify() {
        var finalCorner = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "outer_left",
                "waterlogged", "false"));
        var immediateStraight = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "straight",
                "waterlogged", "false"));
        BlockTarget placed = target(0, 65, 0);
        var step = new ApplyBlockPlanStep(
                "corner", ApplyBlockPlanOperation.PLACE, placed, AIR, finalCorner,
                Optional.of("minecraft:oak_stairs"),
                Optional.of(PlacementSupportWitness.visible(
                        target(0, 64, 0), "up", STONE)));
        var request = new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "corner", 1, 1, List.of(step),
                new ActionBounds(DIMENSION, target(0, 64, 0), placed, 0, 15, false)));
        var port = new FakePort(request);
        port.packetAfterStates.put(placed, immediateStraight);
        var attempt = new KnownConstructionAttempt(port, request, 1, 301);

        assertThat(attempt.tick(1).evidence()).isEqualTo("construction_preparing");
        port.tick = 2;
        assertThat(attempt.tick(2).evidence()).isEqualTo("construction_confirming");
        port.tick = 3;
        assertThat(attempt.tick(3).evidence()).isEqualTo("construction_entry_confirmed");
        port.states.put(placed, finalCorner);
        port.tick = 4;
        assertThat(attempt.tick(4).evidence()).isEqualTo("construction_final_verifying");
        port.tick = 5;
        assertThat(attempt.tick(5).status())
                .isEqualTo(KnownConstructionAttempt.Status.SUCCEEDED);
    }

    @Test
    void preflightStillRejectsStateThatIsNeitherBeforeNorAfter() {
        var request = request(1);
        var port = new FakePort(request);
        port.states.put(request.entries().getFirst().target(),
                new BlockStateFingerprint("minecraft:dirt", Map.of()));
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

    @Test
    void adapterDiagnosticsIdentifyPreflightObserveWithoutChangingPublicEvidence() {
        var request = request(1);
        var port = new FakePort(request);
        var failure = new IllegalStateException("private adapter detail");
        port.observeFailure = failure;
        var diagnostics = new java.util.ArrayList<AdapterDiagnostic>();
        var attempt = new KnownConstructionAttempt(
                port, request, 1, 301,
                (call, stepIndex, cause) -> diagnostics.add(
                        new AdapterDiagnostic(call, stepIndex, cause)));

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_adapter_failed");
        assertThat(diagnostics).containsExactly(new AdapterDiagnostic(
                KnownConstructionAttempt.AdapterCall.PREFLIGHT_OBSERVE, -1, failure));
        assertThat(port.retired).isTrue();
    }

    @Test
    void adapterDiagnosticsIdentifyPreparationAndIgnoreSinkFailure() {
        var request = request(1);
        var port = new FakePort(request);
        var failure = new IllegalStateException("private preparation detail");
        port.beginPreparationFailure = failure;
        var diagnostics = new java.util.ArrayList<AdapterDiagnostic>();
        var attempt = new KnownConstructionAttempt(
                port, request, 1, 301,
                (call, stepIndex, cause) -> {
                    diagnostics.add(new AdapterDiagnostic(call, stepIndex, cause));
                    throw new IllegalStateException("diagnostic sink failed");
                });

        var result = attempt.tick(1);

        assertThat(result.status()).isEqualTo(KnownConstructionAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("construction_adapter_failed");
        assertThat(diagnostics).containsExactly(new AdapterDiagnostic(
                KnownConstructionAttempt.AdapterCall.BEGIN_PREPARATION, 0, failure));
        assertThat(port.retired).isTrue();
    }

    private record AdapterDiagnostic(
            KnownConstructionAttempt.AdapterCall call,
            int stepIndex,
            Throwable failure) { }

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

    private static KnownConstructionRequest clearRequest() {
        BlockTarget target = target(0, 65, 0);
        var step = new ApplyBlockPlanStep(
                "clear0", ApplyBlockPlanOperation.BREAK_TO_AIR,
                target, STONE, AIR, Optional.empty());
        return new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "clear", 1, 1, List.of(step),
                new ActionBounds(DIMENSION, target, target, 0, 15, true),
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK));
    }

    private static BlockTarget target(int x, int y, int z) {
        return target(DIMENSION, x, y, z);
    }

    private static BlockTarget target(String dimension, int x, int y, int z) {
        return new BlockTarget(dimension, x, y, z);
    }

    private static final class FakePort implements ApplyBlockPlanPort {
        private final Map<ApplyBlockPlanRequest, KnownConstructionRequest> requests =
                new java.util.IdentityHashMap<>();
        private final Map<BlockTarget, BlockStateFingerprint> states = new LinkedHashMap<>();
        private final Map<BlockTarget, BlockStateFingerprint> packetAfterStates =
                new LinkedHashMap<>();
        private final java.util.ArrayList<String> dispatchedEntryIds = new java.util.ArrayList<>();
        private final boolean breakOnly;
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
        private RuntimeException observeFailure;
        private RuntimeException beginPreparationFailure;
        private boolean retired;
        private ApplyBlockPlanChildAction activeChild;
        private ApplyBlockPlanPreparationAttempt preparation;
        private ApplyBlockPlanActionAttempt action;

        private FakePort(KnownConstructionRequest request) {
            requests.put(request.plan(), request);
            breakOnly = request.breakOnly();
            available = request.entries().size();
            request.entries().forEach(step -> states.put(step.target(), step.expectedBefore()));
        }

        @Override
        public ApplyBlockPlanFrame observe(ApplyBlockPlanRequest plan) {
            if (observeFailure != null) throw observeFailure;
            KnownConstructionRequest request = Objects.requireNonNull(requests.get(plan));
            var cells = new LinkedHashMap<BlockTarget, ApplyBlockPlanCellObservation>();
            for (int index = 0; index < request.entries().size(); index++) {
                var step = request.entries().get(index);
                if (omitCompletedCells && step.expectedAfter().equals(states.get(step.target()))) {
                    continue;
                }
                boolean aimFeasible = !deferSecondAimUntilFirstConfirmation
                        || index == 0
                        || STONE.equals(states.get(request.entries().getFirst().target()));
                cells.put(step.target(), new ApplyBlockPlanCellObservation(
                        step.target(), Optional.of(states.get(step.target())),
                        !request.breakOnly(), true, aimFeasible));
            }
            var inventory = new LinkedHashMap<String, Integer>();
            request.requiredResources().keySet().forEach(item -> inventory.put(item, available));
            return new ApplyBlockPlanFrame(
                    tick, tick, true, true, true, true, true, true, true,
                    cells, inventory, request.requiredResources().keySet());
        }

        @Override
        public ApplyBlockPlanPreparationAttempt beginPreparation(
                ApplyBlockPlanRequest ignored,
                ApplyBlockPlanChildAction child,
                long deadline) {
            beginPreparationCalls++;
            if (beginPreparationFailure != null) throw beginPreparationFailure;
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
                    !breakOnly, true, true, true, failure);
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
            BlockStateFingerprint packetAfter = packetAfterStates.getOrDefault(
                    activeChild.target(), activeChild.expectedAfter());
            states.put(activeChild.target(), packetAfter);
            available--;
            return new ApplyBlockPlanActionEvidence(
                    action.attemptId(), tick, tick, true, true,
                    Optional.of(packetAfter), Map.of(), null);
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
