package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.agent.observation.ObservationPage;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameItemPlannerTest {
    private static final String DIM = "minecraft:overworld";
    private static final String REF = "abcdefghijklmnopqrstuvwx";
    private static final ObservationValues.ResourceId DIMENSION = new ObservationValues.ResourceId(DIM);
    private static final AgentPrimitivePlanner.Pose POSE = new AgentPrimitivePlanner.Pose(
            new NavCell(DIM, 0, 64, 0), 0.5, 64, 0.5, 1.62, 0, 0);

    @Test
    void fixesExactlyTheObservedFrontPointAndRotationWithOneBoundedInteraction() {
        var map = map();
        var remove = new ActionDsl.RemoveVisibleFrameItem("remove", REF, "minecraft:stone");
        var frame = frame("minecraft:stone", true, 4, 1, 1, 0);
        var aim = AgentPrimitivePlanner.requireFrameItemAim(map.snapshot().orElseThrow(), POSE, frame, remove, 0);
        assertThat(aim.aimPoint()).isEqualTo(new Vec3(0.5, 65.5, 2));
        assertThat(aim.rotation()).isEqualTo(4);
        assertThat(aim.expectedItem()).contains("minecraft:stone");
        assertThat(aim.insertedItem()).isEmpty();
        var program = new ActionDsl.Program(1, Optional.empty(),
                Set.of(ActionDsl.Capability.CAMERA, ActionDsl.Capability.ENTITY_ATTACK), List.of(remove));
        var analysis = AgentPrimitivePlanner.analyze(program, map.snapshot().orElseThrow(),
                new DeterministicAStar(), POSE, frame, 4.5F);
        var cost = analysis.worstCase(remove).orElseThrow();
        assertThat(cost.ticks()).isEqualTo(ActionDslCompiler.FRAME_ITEM_TICKS);
        assertThat(cost.durationMillis()).isEqualTo(ActionDslCompiler.FRAME_ITEM_DURATION_MILLIS);
        assertThat(cost.interactions()).isOne();
        assertThat(cost.distanceBlocks()).isZero();
        assertThat(cost.cameraDegrees()).isBetween(0.25, 360.0);
    }

    @Test
    void unknownBackFaceWrongItemAndOldOrInvalidatedEvidenceNeverAuthorizeAnOperation() {
        var map = map();
        var remove = new ActionDsl.RemoveVisibleFrameItem("remove", REF, "minecraft:stone");
        for (var frame : List.of(frame("minecraft:stone", false, 4, 1, 1, 0),
                frame("minecraft:dirt", true, 4, 1, 1, 0), frame(null, true, 4, 1, 1, 0),
                frame("minecraft:stone", true, 4, 1, 102, 0))) {
            assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(
                    map.snapshot().orElseThrow(), POSE, frame, remove, 0))
                    .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        }
        map.advanceWorldRevision(1, List.of(), List.of());
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(map.snapshot().orElseThrow(),
                POSE, frame("minecraft:stone", true, 4, 1, 1, 0), remove, 1))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    @Test
    void confirmedFrameCanCrossAnUnrelatedRevisionBarrierOnlyAfterAnActualMatchingRay() {
        var map = map();
        map.advanceWorldRevision(15_170, List.of(), List.of());
        var snapshot = map.snapshot().orElseThrow();
        var remove = new ActionDsl.RemoveVisibleFrameItem("remove", REF, "minecraft:stone");
        var raw = frame("minecraft:stone", true, 2, 2_589, 2_589, 15_123);
        var store = new DeliveredPolicyEvidenceStore();
        var receipt = store.prepareDelivery(new ObservationPage("obs-0000000000000001",
                2_589, raw.orElseThrow().records(), null));
        assertThat(store.confirmDelivery(receipt)).isTrue();
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(
                snapshot, POSE, store.augment(raw), remove, 15_170))
                .hasMessage("Frame witness rejected: visual_revision_outdated");

        // Missing fog, a blocked front ray, or a changed identity cannot advance evidence.
        var missed = store.reobserveForPlanning(raw, surface -> Optional.empty(), 2_604,
                entity -> Optional.empty());
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(
                snapshot, POSE, missed, remove, 15_170))
                .hasMessage("Frame witness rejected: visual_revision_outdated");
        var fresh = (ObservationRecord.VisibleEntity) frame("minecraft:stone", true,
                2, 2_604, 2_604, 15_170).orElseThrow().records().getFirst();
        var planning = store.reobserveForPlanning(raw, surface -> Optional.empty(), 2_604,
                entity -> Optional.of(fresh));
        var aim = AgentPrimitivePlanner.requireFrameItemAim(snapshot, POSE, planning, remove, 15_170);
        assertThat(aim.observedTick()).isEqualTo(2_604);
        assertThat(aim.worldRevision()).isEqualTo(15_170);
        assertThat(raw.orElseThrow().frameCompletedTick()).isEqualTo(2_589);
        assertThat(raw.orElseThrow().records().getFirst().newestObservedTick()).isEqualTo(2_589);

        // Reobserving successfully does not renew the original delivery's 100-tick lease.
        var expired = store.reobserveForPlanning(planning, surface -> Optional.empty(), 2_690,
                entity -> { throw new AssertionError("expired delivery must not be reobserved"); });
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(
                snapshot, POSE, expired, remove, 15_170))
                .hasMessage("Frame witness rejected: display_not_authorized");
    }

    @Test
    void insertionRequiresObservedEmptyDisplayAndOriginalInteractionPose() {
        var map = map().snapshot().orElseThrow();
        var insert = new ActionDsl.InsertVisibleFrameItem("insert", REF, "minecraft:stone");
        // A static reobservation may advance the composite tick; it never rewrites the entity age.
        var empty = frame(null, true, 6, 1, 20, 0);
        var aim = AgentPrimitivePlanner.requireFrameItemAim(map, POSE, empty, insert, 0);
        assertThat(aim.observedTick()).isOne();
        assertThat(aim.expectedItem()).isEmpty();
        assertThat(aim.insertedItem()).contains("minecraft:stone");
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(map, POSE,
                frame("minecraft:dirt", true, 6, 1, 1, 0), insert, 0))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
        var moved = new AgentPrimitivePlanner.Pose(new NavCell(DIM, 1, 64, 0),
                1.5, 64, 0.5, 1.62, 0, 0);
        assertThatThrownBy(() -> AgentPrimitivePlanner.requireFrameItemAim(map, moved, empty, insert, 0))
                .isInstanceOf(AgentPrimitivePlanner.PlanningException.class);
    }

    private static KnownTraversabilityMap map() {
        var map = new KnownTraversabilityMap();
        map.startSession(UUID.randomUUID(), DIM, 0);
        return map;
    }

    private static Optional<ObservationFrame> frame(String item, boolean front, int rotation,
            long tick, long completedTick, long revision) {
        var point = new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.5, 2);
        var display = front ? new ObservationRecord.FrameDisplay(item == null ? null
                : new ObservationValues.ResourceId(item), rotation, point) : null;
        var entity = new ObservationRecord.VisibleEntity(new ObservationValues.ResourceId("minecraft:item_frame"),
                null, REF, point, new ObservationValues.Vector(0, 0, 0),
                new ObservationValues.Aabb(0.25, 65.25, 2, 0.75, 65.75, 2.0625),
                ObservationRecord.EntityHazardClass.UNKNOWN,
                new ObservationValues.WorldPosition(DIMENSION, 0.5, 65.62, 0.5), tick, revision, null, display);
        return Optional.of(new ObservationFrame("obs-0000000000000001", DIMENSION, completedTick, 16, false, List.of(entity)));
    }
}
