package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentActionStore.RendererRecoveryStage;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.observation.*;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.*;
import dev.aod.mcmcp.safety.InputReleaseController;
import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.aod.mcmcp.runtime.SurfacePreflightRecovery.Decision.*;
import static dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.*;

/** Uses the production inbox, lease, recovery gate and actual known-ray reobservation seam. */
class SurfacePreflightRecoveryTest {
    private static final ResourceId DIM = new ResourceId("minecraft:overworld");
    private static final VisibleSurface CHEST = new VisibleSurface(
            new BlockPosition(DIM, 1, 64, 0), ObservationRecord.Face.UP,
            new ResourceId("minecraft:chest"), ObservationRecord.ShapeClass.PARTIAL, null,
            new WorldPosition(DIM, 1.5, 64.875, 0.5),
            new WorldPosition(DIM, -1.5, 65.62, 0.5), 10, 10);
    private static final ObservationFrame RAW = new ObservationFrame("obs-0000000000000001",
            DIM, 10, 16, false, List.of(CHEST));
    private static final ActionDsl.InspectKnownContainer NODE = new ActionDsl.InspectKnownContainer(
            "inspect", new ActionDsl.Position(DIM.value(), 1, 64, 0), "minecraft:chest");
    private static final ActionDsl.Budget BUDGET = new ActionDsl.Budget(1_000, 20, 0, 360, 3, 0, 0);
    private static final VisibleSurface TABLE = new VisibleSurface(
            CHEST.position(), ObservationRecord.Face.UP,
            new ResourceId("minecraft:crafting_table"), ObservationRecord.ShapeClass.OPAQUE, null,
            new WorldPosition(DIM, 1.5, 65, 0.5), CHEST.eyeOrigin(), 10, 10);
    private static final ActionDsl.CraftKnownRecipe CRAFT = new ActionDsl.CraftKnownRecipe(
            "craft", "abcdefghijklmnopqrstuvwx", "sha256:" + "a".repeat(64),
            "minecraft:oak_planks", "default_components_only", 2,
            "crafting_table", NODE.target(),
            new ActionDsl.BlockStateSpec("minecraft:crafting_table", Map.of()), 2);
    private static final ResourceId SNOW_BLOCK = new ResourceId("minecraft:snow_block");
    private static final VisibleSurface SNOW = new VisibleSurface(
            new BlockPosition(DIM, -590, 79, 64), ObservationRecord.Face.UP, SNOW_BLOCK,
            new ObservationRecord.BlockStateView(SNOW_BLOCK, Map.of()), SNOW_BLOCK,
            ObservationRecord.ShapeClass.OPAQUE, null,
            new WorldPosition(DIM, -589.5, 80, 64.5),
            new WorldPosition(DIM, -589.5, 81.62, 64.5), 10, 10);
    private static final ActionDsl.ExtendKnownFloor FLOOR = new ActionDsl.ExtendKnownFloor(
            "floor", new ActionDsl.Position(DIM.value(), -590, 79, 64),
            new ActionDsl.BlockStateSpec(SNOW_BLOCK.value(), Map.of()), ActionDsl.BlockFace.EAST,
            "psr_00000000000000000000000000000000");
    private static final ActionDsl.ApplyKnownBlockPlan PLACEMENT = new ActionDsl.ApplyKnownBlockPlan(
            "light", new ActionDsl.Position(DIM.value(), -590, 80, 64),
            new ActionDsl.BlockPlanTransform(ActionDsl.BlockPlanRotation.DEGREES_0, ActionDsl.BlockPlanMirror.NONE),
            List.of(new ActionDsl.BlockPlanEntry("torch", new ActionDsl.Offset(0, 0, 0),
                    Optional.empty(), Optional.empty(), Optional.of(FLOOR.placementStateRef()),
                    new ActionDsl.PlacementSupport(FLOOR.support(), ActionDsl.BlockFace.UP,
                            Optional.of(FLOOR.expectedSupport()), Optional.empty()))));

    @Test
    void singlePlacementWaitsAtEveryPreDispatchStageUsingOnlyItsOriginalSupport() {
        var fixture = new Fixture(PLACEMENT, SNOW);
        int tick = 10;
        for (var stage : List.of(RendererRecoveryStage.CAPTURE, RendererRecoveryStage.COMMIT,
                RendererRecoveryStage.DISPATCH, RendererRecoveryStage.JIT)) {
            var pending = fixture.submit(stage, stage == RendererRecoveryStage.JIT);
            fixture.drain(tick++, false);
            assertThat(pending).as("missing fog at %s", stage).isNotDone();
            assertThat(fixture.interactions).hasValue(0);
            fixture.drain(tick++, true);
            assertThat(pending.join()).isEqualTo("ready");
        }
        assertThat(fixture.rays).hasValue(4);
        assertThat(fixture.interactions).hasValue(1);
        assertThat(fixture.recovery.summary().missingStages()).isEqualTo(1 | 2 | 4 | 8);
        assertThat(fixture.recovery.summary().revalidatedStages()).isEqualTo(1 | 2 | 4 | 8);
        assertThat(fixture.store.augment(Optional.of(fixture.raw)).orElseThrow().records()).containsExactly(SNOW);
        assertThat(fixture.recovery.evaluate(fixture.store, 30, 1_500_000_000L, true))
                .isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
    }

    @Test
    void singlePlacementCannotUseAChangedOccludedOrFogHiddenSupportAfterWaiting() {
        for (int obstruction = 0; obstruction < 3; obstruction++) {
            var fixture = new Fixture(PLACEMENT, SNOW);
            var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
            fixture.drain(10, true);
            assertThat(capture.join()).isEqualTo("ready");
            var commit = fixture.submit(RendererRecoveryStage.COMMIT, true);
            fixture.drain(11, false);
            assertThat(commit).isNotDone();
            if (obstruction == 0) fixture.block = new ResourceId("minecraft:stone");
            if (obstruction == 1) fixture.block = null;
            if (obstruction == 2) fixture.fogDistance = 1;
            fixture.drain(12, true);
            assertThatThrownBy(commit::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
            assertThat(fixture.interactions).hasValue(0);
            assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
        }
    }

    @Test
    void placementRecoveryExcludesMultiEntryAndDependencySupports() {
        var entry = PLACEMENT.entries().getFirst();
        var recovery = new Fixture(PLACEMENT, SNOW).recovery;
        assertThat(recovery.applies(PLACEMENT)).isTrue();
        assertThat(recovery.applies(new ActionDsl.ApplyKnownBlockPlan("many", PLACEMENT.anchor(),
                PLACEMENT.transform(), List.of(entry, entry)))).isFalse();
        var dependency = new ActionDsl.BlockPlanEntry("dependent", entry.offset(), entry.sourceState(),
                entry.item(), entry.placementStateRef(), new ActionDsl.PlacementSupport(FLOOR.support(),
                        ActionDsl.BlockFace.UP, Optional.empty(), Optional.of("earlier")));
        assertThat(SurfacePreflightRecovery.target(new ActionDsl.ApplyKnownBlockPlan("dependent",
                PLACEMENT.anchor(), PLACEMENT.transform(), List.of(dependency)))).isNull();
        assertThat(SurfacePreflightRecovery.target(new ActionDsl.ApplyKnownBlockPlan("empty",
                PLACEMENT.anchor(), PLACEMENT.transform(), List.of()))).isNull();
    }

    @Test
    void placementRendererWaitSharesTheOriginalThreeHundredTickEnvelope() {
        var used = new AgentActionStore.Progress(AgentActionStore.Phase.EXECUTING,
                "light", 0, 1, 0, 0, 0, 0, 0, 7, false);
        var planned = ActionDslCompiler.intrinsicKnownBlockPlanCost(1);
        var remaining = ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, true, PLACEMENT, planned, 350_000_000L);
        assertThat(remaining).isEqualTo(new ActionDslCompiler.Cost(14_650, 293, 0, 80, 0, 0, 1));
        assertThat(ActionBudgets.fitsRemainingBudget(used,
                new ActionDsl.Budget(15_000, 300, 0, 80, 0, 0, 1), remaining, 350_000_000L)).isTrue();
        assertThat(ActionBudgets.fitsRemainingBudget(used,
                new ActionDsl.Budget(15_000, 299, 0, 80, 0, 0, 1), remaining, 350_000_000L)).isFalse();
        assertThat(ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, false, PLACEMENT, planned, 350_000_000L)).isEqualTo(planned);
        var many = new ActionDsl.ApplyKnownBlockPlan("many", PLACEMENT.anchor(), PLACEMENT.transform(),
                List.of(PLACEMENT.entries().getFirst(), PLACEMENT.entries().getFirst()));
        var manyCost = ActionDslCompiler.intrinsicKnownBlockPlanCost(2);
        assertThat(ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, true, many, manyCost, 350_000_000L)).isEqualTo(manyCost);
    }

    @Test
    void floorSupportWaitsAcrossCaptureCommitDispatchAndJitWithoutRenewingItsWitness() {
        var fixture = new Fixture(FLOOR, SNOW);
        int tick = 10;
        for (var stage : List.of(RendererRecoveryStage.CAPTURE, RendererRecoveryStage.COMMIT,
                RendererRecoveryStage.DISPATCH, RendererRecoveryStage.JIT)) {
            var pending = fixture.submit(stage, stage == RendererRecoveryStage.JIT);
            fixture.drain(tick++, false);
            assertThat(pending).as("missing fog at %s", stage).isNotDone();
            assertThat(fixture.interactions).hasValue(0);
            fixture.drain(tick++, true);
            assertThat(pending.join()).isEqualTo("ready");
        }
        assertThat(fixture.rays).hasValue(4);
        assertThat(fixture.interactions).hasValue(1);
        assertThat(fixture.recovery.consumedTicks(17)).isEqualTo(7);
        assertThat(fixture.recovery.summary().missingStages()).isEqualTo(1 | 2 | 4 | 8);
        assertThat(fixture.recovery.summary().revalidatedStages()).isEqualTo(1 | 2 | 4 | 8);
        assertThat(fixture.store.augment(Optional.of(fixture.raw)).orElseThrow().records())
                .containsExactly(SNOW);
        // Neither the new floor nor a different expected block inherits this support lease.
        assertThat(fixture.recovery.applies(new ActionDsl.ExtendKnownFloor("other",
                new ActionDsl.Position(DIM.value(), -589, 79, 64), FLOOR.expectedSupport(),
                FLOOR.direction(), FLOOR.placementStateRef()))).isFalse();
        assertThat(fixture.recovery.applies(new ActionDsl.ExtendKnownFloor("other", FLOOR.support(),
                new ActionDsl.BlockStateSpec("minecraft:stone", Map.of()),
                FLOOR.direction(), FLOOR.placementStateRef()))).isFalse();
        assertThat(fixture.recovery.evaluate(fixture.store, 30, 1_500_000_000L, true))
                .isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
    }

    @Test
    void floorSupportChangedOccludedOrOutsideFogCannotBeAuthorizedWhenRenderingReturns() {
        for (int obstruction = 0; obstruction < 3; obstruction++) {
            var fixture = new Fixture(FLOOR, SNOW);
            var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
            fixture.drain(10, true);
            assertThat(capture.join()).isEqualTo("ready");
            var commit = fixture.submit(RendererRecoveryStage.COMMIT, true);
            fixture.drain(11, false);
            assertThat(commit).isNotDone();
            if (obstruction == 0) fixture.block = new ResourceId("minecraft:stone");
            if (obstruction == 1) fixture.block = null;
            if (obstruction == 2) fixture.fogDistance = 1;
            fixture.drain(12, true);
            assertThatThrownBy(commit::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
            assertThat(fixture.interactions).hasValue(0);
            assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
        }
    }

    @Test
    void captureCommitAndDispatchEachWaitForTheirOwnFreshRenderWithoutRenewingEvidence() {
        var fixture = new Fixture();
        var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
        fixture.drain(10, false);
        assertThat(capture).isNotDone();
        assertThat(fixture.rays).hasValue(0);
        fixture.drain(11, true);
        assertThat(capture.join()).isEqualTo("ready");

        var commit = fixture.submit(RendererRecoveryStage.COMMIT, false);
        fixture.drain(12, false);
        assertThat(commit).isNotDone();
        assertThat(fixture.rays).hasValue(1);
        fixture.drain(13, true);
        assertThat(commit.join()).isEqualTo("ready");

        var dispatch = fixture.submit(RendererRecoveryStage.DISPATCH, true);
        fixture.drain(14, false);
        assertThat(dispatch).isNotDone();
        assertThat(fixture.interactions).hasValue(0);
        fixture.drain(15, true);
        assertThat(dispatch.join()).isEqualTo("ready");
        fixture.drain(16, true);
        assertThat(fixture.rays).hasValue(3);
        assertThat(fixture.interactions).hasValue(1);
        assertThat(fixture.recovery.consumedTicks(15)).isEqualTo(5);
        assertThat(fixture.recovery.executionStartNanos(750_000_000)).isEqualTo(500_000_000);
        assertThat(RAW.records()).containsExactly(CHEST);
        assertThat(fixture.store.augment(Optional.of(RAW)).orElseThrow().records()).containsExactly(CHEST);
        assertThat(fixture.recovery.summary().missingStages()).isEqualTo(1 | 2 | 4);
        assertThat(fixture.recovery.summary().revalidatedStages()).isEqualTo(1 | 2 | 4);
    }

    @Test
    void chargedRendererWaitStillFitsTheExactContainerReservation() {
        var fixture = new Fixture();
        var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
        fixture.drain(10, false);
        fixture.drain(11, true);
        assertThat(capture.join()).isEqualTo("ready");
        long consumedTicks = fixture.recovery.consumedTicks(11);
        long now = 11L * 50_000_000L;
        long elapsedNanos = now - fixture.recovery.executionStartNanos(now);
        var used = new AgentActionStore.Progress(
                AgentActionStore.Phase.EXECUTING,
                "inspect", 0, 1, 0, 0, 0, 0, 0,
                Math.toIntExact(consumedTicks), false);
        var planned = new ActionDslCompiler.Cost(30_000, 600, 0, 38, 1, 0, 0);
        var remaining = ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, true, NODE, planned, elapsedNanos);

        assertThat(consumedTicks).isOne();
        assertThat(ActionBudgets.fitsRemainingBudget(
                used,
                new ActionDsl.Budget(30_000, 600, 0, 360, 1, 0, 0),
                remaining,
                elapsedNanos)).isTrue();
    }

    @Test
    void floorRendererWaitConsumesItsOriginalEnvelopeWithoutReservingAnotherFourHundredTicks() {
        var used = new AgentActionStore.Progress(AgentActionStore.Phase.EXECUTING,
                "floor", 0, 1, 0, 0, 0, 0, 0, 7, false);
        var planned = ActionDslCompiler.intrinsicFloorExtensionCost();
        var remaining = ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, true, FLOOR, planned, 350_000_000L);
        var budget = new ActionDsl.Budget(20_000, 400, 2, 720, 0, 0, 1);
        assertThat(remaining).isEqualTo(new ActionDslCompiler.Cost(19_650, 393, 2, 720, 0, 0, 1));
        assertThat(ActionBudgets.fitsRemainingBudget(used, budget, remaining, 350_000_000L)).isTrue();
        assertThat(ActionBudgets.fitsRemainingBudget(used,
                new ActionDsl.Budget(20_000, 399, 2, 720, 0, 0, 1), remaining, 350_000_000L)).isFalse();
        assertThat(ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, false, FLOOR, planned, 350_000_000L)).isEqualTo(planned);
        assertThat(ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                used, true, FLOOR, planned, -1)).isEqualTo(planned);
    }

    @Test
    void recoveredRendererDoesNotAuthorizeAChangedOrOccludedChest() {
        for (boolean occluded : List.of(false, true)) {
            var fixture = new Fixture();
            var capture = fixture.submit(false);
            fixture.drain(10, true);
            assertThat(capture.join()).isEqualTo("ready");
            var commit = fixture.submit(true);
            fixture.drain(11, false);
            fixture.block = occluded ? null : new ResourceId("minecraft:stone");
            fixture.drain(12, true);
            assertThatThrownBy(commit::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
            fixture.drain(13, true);
            assertThat(fixture.interactions).hasValue(0);
            assertThat(fixture.rays).hasValue(2);
            assertThat(fixture.recovery.summary().missingStages()).isEqualTo(RendererRecoveryStage.CAPTURE.mask());
            assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
        }
    }

    @Test
    void aRealOneBlockFogLimitStillPreventsTargetAuthorizationAfterRecovery() {
        var fixture = new Fixture();
        var start = fixture.submit(true);
        fixture.drain(10, false);
        fixture.fogDistance = 1.0;
        fixture.drain(11, true);
        assertThatThrownBy(start::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        assertThat(fixture.interactions).hasValue(0);
        assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
    }

    @Test
    void deliveryExpiryAndUnknownTargetsAreNeverRendererWaits() {
        var recovery = new SurfacePreflightRecovery(BUDGET);
        assertThat(recovery.evaluate(VALID, 10, 0, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(recovery.evaluate(DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.DELIVERY_EXPIRED, 11, 1, false))
                .isEqualTo(SurfacePreflightRecovery.Decision.DELIVERY_EXPIRED);
        assertThat(recovery.evaluate(NOT_DELIVERED, 11, 1, false)).isEqualTo(TARGET_NOT_DELIVERED);
        assertThat(new SurfacePreflightRecovery(BUDGET).evaluate(NOT_DELIVERED, 10, 0, true))
                .isEqualTo(TARGET_NOT_DELIVERED);
    }

    @Test
    void successfulIntermediatePhaseCannotResetTheOriginalTickOrWallBudget() {
        var ticks = new SurfacePreflightRecovery(new ActionDsl.Budget(10_000, 3, 0, 360, 3, 0, 0));
        assertThat(ticks.evaluate(VALID, 10, 0, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(ticks.evaluate(VALID, 11, 1, true)).isEqualTo(READY);
        assertThat(ticks.evaluate(VALID, 12, 2, false)).isEqualTo(RENDERER_EVIDENCE_MISSING);
        assertThat(ticks.evaluate(VALID, 13, 3, true)).isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
        var wall = new SurfacePreflightRecovery(BUDGET);
        wall.evaluate(VALID, 10, 0, false);
        assertThat(wall.evaluate(VALID, 11, 1_000_000_000L, true)).isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
    }

    @Test
    void laterNodesAtTheSameTargetKeepTheOriginalLeaseWithoutEnablingUnrelatedTargets() {
        var fixture = new Fixture();
        var lease = fixture.recovery.lease();
        assertThat(fixture.recovery.applies(new ActionDsl.ApproachKnownSurface(
                "approach", NODE.target(), NODE.expectedBlock()))).isTrue();
        assertThat(fixture.recovery.applies(new ActionDsl.InspectKnownContainer(
                "other", new ActionDsl.Position(DIM.value(), 2, 64, 0), NODE.expectedBlock()))).isFalse();
        fixture.recovery.capture(new ActionDsl.InspectKnownContainer(
                "other", NODE.target(), "minecraft:barrel"), fixture.store);
        assertThat(fixture.recovery.lease()).isSameAs(lease);
        assertThat(fixture.recovery.applies(NODE)).isTrue();
    }

    @Test
    void fogReturningAloneDoesNotMarkRevalidationAndStagesCannotValidateEachOther() {
        var recovery = new SurfacePreflightRecovery(BUDGET);
        recovery.evaluate(VALID, 10, 0, false);
        assertThat(recovery.noteMissing(RendererRecoveryStage.INITIAL_OPEN)).isTrue();
        assertThat(recovery.evaluate(VALID, 11, 50_000_000L, true)).isEqualTo(READY);
        assertThat(recovery.summary().revalidatedStages()).isZero();
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.JIT)).isFalse();
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.INITIAL_OPEN)).isTrue();
        for (int repeat = 0; repeat < 1000; repeat++) {
            assertThat(recovery.noteMissing(RendererRecoveryStage.INITIAL_OPEN)).isFalse();
        }
        // Historical "at least once", not current readiness: a later missing interval remains pending.
        assertThat(recovery.summary().missingStages()).isEqualTo(16);
        assertThat(recovery.summary().revalidatedStages()).isEqualTo(16);
        assertThat(recovery.noteRevalidated(RendererRecoveryStage.INITIAL_OPEN)).isFalse();
        assertThat(recovery.executionStartNanos(1_000_000_000L)).isZero();
    }

    @Test
    void craftingWaitsForItsOriginalTableAtEveryStageBeforeTheFirstInteraction() {
        var fixture = new Fixture(CRAFT, TABLE);
        int tick = 10;
        for (var stage : List.of(RendererRecoveryStage.CAPTURE, RendererRecoveryStage.COMMIT,
                RendererRecoveryStage.DISPATCH, RendererRecoveryStage.JIT, RendererRecoveryStage.INITIAL_OPEN)) {
            var pending = fixture.submit(stage, stage == RendererRecoveryStage.INITIAL_OPEN);
            fixture.drain(tick++, false);
            assertThat(pending).as("missing fog at %s", stage).isNotDone();
            assertThat(fixture.interactions).hasValue(0);
            fixture.drain(tick++, true);
            assertThat(pending.join()).isEqualTo("ready");
        }
        assertThat(fixture.rays).hasValue(5);
        assertThat(fixture.interactions).hasValue(1);
        assertThat(fixture.recovery.summary().missingStages()).isEqualTo(1 | 2 | 4 | 8 | 16);
        assertThat(fixture.recovery.summary().revalidatedStages()).isEqualTo(1 | 2 | 4 | 8 | 16);
        assertThat(fixture.store.augment(Optional.of(fixture.raw)).orElseThrow().records()).containsExactly(TABLE);
        assertThat(fixture.recovery.evaluate(fixture.store, 30, 1_500_000_000L, true))
                .isEqualTo(RENDERER_EVIDENCE_TIMEOUT);
    }

    @Test
    void craftingCannotOpenAChangedOccludedOrFogHiddenTableAfterWaiting() {
        for (int obstruction = 0; obstruction < 3; obstruction++) {
            var fixture = new Fixture(CRAFT, TABLE);
            var capture = fixture.submit(RendererRecoveryStage.CAPTURE, false);
            fixture.drain(10, true);
            assertThat(capture.join()).isEqualTo("ready");
            var opening = fixture.submit(RendererRecoveryStage.INITIAL_OPEN, true);
            fixture.drain(11, false);
            assertThat(opening).isNotDone();
            if (obstruction == 0) fixture.block = new ResourceId("minecraft:stone");
            if (obstruction == 1) fixture.block = null;
            if (obstruction == 2) fixture.fogDistance = 1;
            fixture.drain(12, true);
            assertThatThrownBy(opening::join).hasCauseInstanceOf(AgentPrimitivePlanner.PlanningException.class);
            assertThat(fixture.interactions).hasValue(0);
            assertThat(fixture.recovery.summary().revalidatedStages()).isZero();
        }
    }

    @Test
    void craftingRendererWaitUsesOnlyTheReservationHeadroomAndPreservesMenuTime() {
        var planned = new ActionDslCompiler.Cost(30_000, 600, 0, 38, 9, 0, 0);
        var budget = new ActionDsl.Budget(30_000, 600, 0, 360, 9, 0, 0);
        for (int waitedTicks : List.of(1, 200, 201)) {
            var used = new AgentActionStore.Progress(AgentActionStore.Phase.EXECUTING,
                    "craft", 0, 1, 0, 0, 0, 0, 0, waitedTicks, false);
            long elapsed = waitedTicks * 50_000_000L;
            var remaining = ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                    used, true, CRAFT, planned, elapsed);
            assertThat(remaining.ticks()).isEqualTo(Math.max(400, 600 - waitedTicks));
            assertThat(remaining.durationMillis()).isEqualTo(Math.max(20_000, 30_000 - 50L * waitedTicks));
            assertThat(remaining.interactions()).isEqualTo(9);
            assertThat(ActionBudgets.fitsRemainingBudget(used, budget, remaining, elapsed))
                    .isEqualTo(waitedTicks <= 200);
            assertThat(ActionBudgets.firstRecoveredSurfacePrimitiveRemainingCost(
                    used, false, CRAFT, planned, elapsed)).isEqualTo(planned);
        }
    }

    private static final class Fixture {
        private final AtomicLong now = new AtomicLong();
        private final AtomicLong tick = new AtomicLong(10);
        private final AtomicBoolean fog = new AtomicBoolean();
        private final AtomicInteger rays = new AtomicInteger();
        private final AtomicInteger interactions = new AtomicInteger();
        private final DeliveredPolicyEvidenceStore store = new DeliveredPolicyEvidenceStore();
        private final SurfacePreflightRecovery recovery = new SurfacePreflightRecovery(BUDGET);
        private final KnownTraversabilityMap map = new KnownTraversabilityMap();
        private final ClientCommandInbox inbox = new ClientCommandInbox(8, new InputReleaseController(),
                new LocalArmingState(), (reason, session) -> ClientCommandInbox.StopProgress.COMPLETE, now::get);
        private final ActionDsl.Node primitive;
        private final ObservationFrame raw;
        private ResourceId block;
        private double fogDistance = 16;

        private Fixture() {
            this(NODE, CHEST);
        }

        private Fixture(ActionDsl.Node primitive, VisibleSurface surface) {
            this.primitive = primitive;
            block = surface.block();
            raw = new ObservationFrame(RAW.frameId(), DIM, 10, 16, false, List.of(surface));
            var receipt = store.prepareDelivery(new ObservationPage(raw.frameId(), 10, List.of(surface), null));
            assertThat(store.confirmDelivery(receipt)).isTrue();
            recovery.capture(primitive, store);
            map.startSession(new UUID(0, 1), DIM.value(), 65);
        }

        private CompletableFuture<String> submit(boolean dispatch) {
            return submit(RendererRecoveryStage.CAPTURE, dispatch);
        }

        private CompletableFuture<String> submit(RendererRecoveryStage stage, boolean dispatch) {
            return inbox.submitControl("agent_start_action", 1, Long.MAX_VALUE, () -> {
                var decision = recovery.evaluate(store, tick.get(), now.get(), fog.get());
                if (decision == RENDERER_EVIDENCE_MISSING) {
                    recovery.noteMissing(stage);
                    throw new ClientCommandInbox.DeferControl();
                }
                assertThat(decision).isEqualTo(READY);
                var lease = recovery.lease();
                var planning = fog.get() ? store.reobserveForPlanning(Optional.of(raw), known ->
                        SurfaceReobservationFixture.reobserve(known, tick.get(), 65, fogDistance, block, rays),
                        tick.get(), entity -> Optional.empty(), known -> lease != null && lease.targets(known))
                        : store.augment(Optional.of(raw));
                if (lease != null) planning = store.restrictToSurfaceLease(planning, lease);
                var target = primitive instanceof ActionDsl.ApplyKnownBlockPlan plan
                        ? new SurfacePreflightRecovery.Target(plan.entries().getFirst().support().position(),
                                plan.entries().getFirst().support().expectedState().orElseThrow().block())
                        : primitive instanceof ActionDsl.ExtendKnownFloor floor
                                ? new SurfacePreflightRecovery.Target(floor.support(), floor.expectedSupport().block())
                                : primitive instanceof ActionDsl.CraftKnownRecipe craft
                                        ? new SurfacePreflightRecovery.Target(craft.target(), craft.expectedState().block())
                                : new SurfacePreflightRecovery.Target(NODE.target(), NODE.expectedBlock());
                AgentPrimitivePlanner.requireKnownSurface(map.snapshot().orElseThrow(), planning,
                        target.position(), target.block(), 65);
                recovery.noteRevalidated(stage);
                if (dispatch) interactions.incrementAndGet();
                return "ready";
            });
        }

        private void drain(long currentTick, boolean currentFog) {
            tick.set(currentTick);
            now.set(currentTick * 50_000_000L);
            fog.set(currentFog);
            inbox.drainControls(1, now.get());
        }
    }
}
