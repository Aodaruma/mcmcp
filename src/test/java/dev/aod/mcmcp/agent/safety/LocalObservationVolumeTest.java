package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Drop;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Point;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Support;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Transition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class LocalObservationVolumeTest {
    private static final Point ORIGIN = new Point(0.0D, 64.0D, 0.0D);

    @Test
    void publicationUsesEuclideanRadiusRatherThanAnAxisAlignedCube() {
        var inside = probe(1, new Point(4.0D, 64.0D, 0.0D));
        var outsideDiagonal = probe(1, new Point(3.0D, 64.0D, 3.0D));

        assertThat(LocalObservationVolume.boundedTransitions(
                ORIGIN,
                List.of(inside, outsideDiagonal)))
                .containsExactly(inside);
    }

    @Test
    void onlyProbeAllowedSafeLoadedSupportCanExpand() {
        assertThat(probe(1, new Point(1.0D, 64.0D, 0.0D)).canExpand()).isTrue();
        assertThat(unknown(1, new Point(1.0D, 64.0D, 0.0D)).canExpand()).isFalse();
        assertThat(blocked(1, new Point(1.0D, 64.0D, 0.0D)).canExpand()).isFalse();
        assertThat(LocalObservationVolume.goalMovementSafe(
                probe(1, new Point(1.0D, 64.0D, 0.0D)))).isTrue();
        assertThat(LocalObservationVolume.goalMovementSafe(new ObservationRecord(
                1L,
                7L,
                1,
                ORIGIN,
                new Point(1.0D, 64.0D, 0.0D),
                new Point(1.0D, 64.0D, 0.0D),
                Support.PRESENT,
                Clearance.CLEAR,
                Transition.PROBE_ALLOWED,
                Fluid.WATER,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                Drop.AIRBORNE_OR_SWIMMING,
                false))).isFalse();
        var airborneStep = new ObservationRecord(
                1L,
                7L,
                1,
                ORIGIN,
                new Point(1.0D, 64.4D, 0.0D),
                new Point(1.0D, 64.4D, 0.0D),
                Support.ABSENT,
                Clearance.CLEAR,
                Transition.PROBE_ALLOWED,
                Fluid.NONE,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                Drop.WITHIN_WALKING_LIMIT,
                false);
        assertThat(airborneStep.canExpand()).isFalse();
        assertThat(LocalObservationVolume.goalIntendedMovementSafe(airborneStep)).isTrue();
        assertThat(LocalObservationVolume.goalIntendedMovementSafe(blocked(
                1, new Point(1.0D, 64.0D, 0.0D)))).isFalse();
    }

    @Test
    void adjacentProbeEndsAtTheDestinationCellCenterFromFractionalNegativeCoordinates() {
        var box = new AABB(
                -9.781D, 56.0D, -15.433D,
                -9.181D, 57.8D, -14.833D);

        var delta = LocalObservationVolume.adjacentCellCenterDelta(box, 0, 1);
        var end = box.move(delta).getCenter();

        assertThat(delta.x).isCloseTo(-0.019D, offset(1.0E-12D));
        assertThat(delta.z).isCloseTo(0.633D, offset(1.0E-12D));
        assertThat(end.x).isCloseTo(-9.5D, offset(1.0E-12D));
        assertThat(end.z).isCloseTo(-14.5D, offset(1.0E-12D));
    }

    @Test
    void shallowLandingSweepContinuesFromTheHorizontalEndpoint() {
        var start = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        var horizontalDelta = new Vec3(1.0D, 0.0D, 0.0D);
        var horizontal = SweptAabbPath.segments(start, horizontalDelta, horizontalDelta);
        var horizontalEnd = start.move(horizontalDelta);

        var path = LocalObservationVolume.appendWalkingLandingSegments(
                horizontal, horizontalEnd, new Vec3(0.0D, -0.0625D, 0.0D));

        assertThat(path).extracting(SweptAabbPath.AxisSegment::axis)
                .containsExactly(SweptAabbPath.Axis.X, SweptAabbPath.Axis.Y);
        assertThat(path.get(1).start()).isEqualTo(horizontalEnd);
        assertThat(path.getLast().end()).isEqualTo(horizontalEnd.move(0.0D, -0.0625D, 0.0D));
    }

    @Test
    void descendingTowardAWaypointCannotWorsenHorizontalDistance() {
        var start = new Point(0.0D, 64.0D, 0.0D);
        var intent = new AgentInputState.NavigationIntent(
                new Vec3(0.0D, 63.0D, 0.0D), -1);

        assertThat(LocalObservationVolume.movesTowardNavigationTarget(
                start, new Point(0.0D, 63.9D, 0.0D), intent)).isTrue();
        assertThat(LocalObservationVolume.movesTowardNavigationTarget(
                start, new Point(0.2D, 63.9D, 0.0D), intent)).isFalse();
    }

    @Test
    void transitionDepthCannotExceedTheFixedSixHopBound() {
        assertThatThrownBy(() -> probe(
                LocalObservationVolume.MAX_TRANSITIONS + 1,
                new Point(1.0D, 64.0D, 0.0D)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionEndpointsPreserveStepHeightForThreeDimensionalMapEdges() {
        var stepped = probe(1, new Point(1.0D, 64.5D, 0.0D));

        assertThat(LocalObservationVolume.boundedTransitions(ORIGIN, List.of(stepped)))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.from().y()).isEqualTo(64.0D);
                    assertThat(record.to().y()).isEqualTo(64.5D);
                });
    }

    @Test
    void eachPublishedRecordCarriesItsObservationRevision() {
        var record = probe(1, new Point(1.0D, 64.0D, 0.0D));

        assertThat(record.worldRevision()).isEqualTo(7L);
        assertThatThrownBy(() -> new ObservationRecord(
                1L,
                -2L,
                1,
                ORIGIN,
                new Point(1.0D, 64.0D, 0.0D),
                new Point(1.0D, 64.0D, 0.0D),
                Support.PRESENT,
                Clearance.CLEAR,
                Transition.PROBE_ALLOWED,
                Fluid.NONE,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                Drop.SUPPORTED,
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeTickRebasingChangesOnlyTheObservationClock() {
        var source = probe(1, new Point(1.0D, 64.0D, 0.0D));

        var rebased = LocalObservationVolume.atTick(source, 99L);

        assertThat(rebased.observedTick()).isEqualTo(99L);
        assertThat(rebased.worldRevision()).isEqualTo(source.worldRevision());
        assertThat(rebased.requestedTo()).isEqualTo(source.requestedTo());
        assertThat(rebased.hazard()).isEqualTo(source.hazard());
    }

    @Test
    void blockedEdgeRetainsRequestedEndpointWhenResolverDoesNotMove() {
        var requested = new Point(1.0D, 64.0D, 0.0D);
        var record = new ObservationRecord(
                1L,
                7L,
                1,
                ORIGIN,
                requested,
                ORIGIN,
                Support.PRESENT,
                Clearance.BLOCKED,
                Transition.BLOCKED,
                Fluid.NONE,
                false,
                Hazard.COLLISION,
                LoadedState.LOADED,
                Drop.SUPPORTED,
                false);

        assertThat(record.requestedTo()).isEqualTo(requested);
        assertThat(record.to()).isEqualTo(ORIGIN);
        assertThat(LocalObservationVolume.boundedTransitions(ORIGIN, List.of(record)))
                .containsExactly(record);
    }

    @Test
    void recoveryAllowsOnlyAgentContributionThatImprovesTheSelectedLavaExit() {
        var current = record(
                0, ORIGIN, Support.PRESENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.LAVA, false, Hazard.LAVA, Drop.SUPPORTED);
        var path = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.PRESENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.LAVA, false, Hazard.LAVA, Drop.SUPPORTED);
        var endpoint = new LocalObservationVolume.EndpointSafety(
                Support.PRESENT,
                Clearance.CLEAR,
                Fluid.LAVA,
                false,
                Hazard.LAVA,
                LoadedState.LOADED,
                Drop.SUPPORTED);
        var intent = new AgentInputState.RecoveryIntent(
                AgentInputState.RecoveryMode.EXIT_LAVA,
                new Vec3(3.0D, 64.0D, 0.0D),
                null);

        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current,
                path,
                endpoint,
                ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D),
                intent)).isTrue();
        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current,
                path,
                endpoint,
                ORIGIN,
                new Point(0.05D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.05D, 0.0D, 0.0D),
                intent)).isFalse();
        assertThat(LocalObservationVolume.goalIntendedMovementSafe(path)).isFalse();
    }

    @Test
    void threatRetreatRequiresBothTargetProgressAndMarginalSourceSeparation() {
        var current = record(
                0, ORIGIN, Support.PRESENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.NONE, false, Hazard.NONE, Drop.SUPPORTED);
        var path = probe(1, new Point(0.2D, 64.0D, 0.0D));
        var endpoint = new LocalObservationVolume.EndpointSafety(
                Support.PRESENT,
                Clearance.CLEAR,
                Fluid.NONE,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                Drop.SUPPORTED);
        var intent = new AgentInputState.RecoveryIntent(
                AgentInputState.RecoveryMode.RETREAT_FROM_THREAT,
                new Vec3(3.0D, 64.0D, 0.0D),
                new Vec3(-1.0D, 64.0D, 0.0D));

        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current,
                path,
                endpoint,
                ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D),
                intent)).isTrue();
        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current,
                path,
                endpoint,
                ORIGIN,
                new Point(0.05D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.05D, 0.0D, 0.0D),
                intent)).isFalse();
    }

    @Test
    void damageSurfaceExitAllowsMonotonicProgressAcrossTheSameDamagingBlock() {
        var current = record(
                0, ORIGIN, Support.PRESENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.NONE, false, Hazard.FIRE_DAMAGE, Drop.SUPPORTED);
        var path = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.PRESENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.NONE, false, Hazard.FIRE_DAMAGE, Drop.SUPPORTED);
        var endpoint = new LocalObservationVolume.EndpointSafety(
                Support.PRESENT,
                Clearance.CLEAR,
                Fluid.NONE,
                false,
                Hazard.FIRE_DAMAGE,
                LoadedState.LOADED,
                Drop.SUPPORTED);
        var intent = new AgentInputState.RecoveryIntent(
                AgentInputState.RecoveryMode.EXIT_DAMAGE_SURFACE,
                new Vec3(3.0D, 64.0D, 0.0D),
                null);

        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current, path, endpoint, ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D), intent)).isTrue();
        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current, path, endpoint, ORIGIN,
                new Point(0.05D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.05D, 0.0D, 0.0D), intent)).isFalse();
        var differentHazard = new LocalObservationVolume.EndpointSafety(
                Support.PRESENT, Clearance.CLEAR, Fluid.NONE, false,
                Hazard.FREEZING, LoadedState.LOADED, Drop.SUPPORTED);
        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current, path, differentHazard, ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D), intent)).isFalse();
    }

    @Test
    void continuingLavaEscapeIsAllowedOnlyAfterLeavingLavaWithoutAnotherHazard() {
        var clearCurrent = record(
                0, ORIGIN, Support.ABSENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.NONE, false, Hazard.NONE, Drop.AIRBORNE_OR_SWIMMING);
        var clearPath = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.ABSENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.NONE, false, Hazard.NONE,
                Drop.AIRBORNE_OR_SWIMMING);
        var clearEndpoint = endpoint(Fluid.NONE, Hazard.NONE);

        assertThat(recoveryMovementSafe(
                clearCurrent, clearPath, clearEndpoint,
                AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE)).isTrue();
        assertThat(recoveryMovementSafe(
                record(0, ORIGIN, Support.ABSENT, Clearance.CLEAR, Transition.STATIONARY,
                        Fluid.LAVA, false, Hazard.LAVA, Drop.AIRBORNE_OR_SWIMMING),
                clearPath,
                clearEndpoint,
                AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE)).isFalse();
        assertThat(recoveryMovementSafe(
                record(0, ORIGIN, Support.ABSENT, Clearance.CLEAR, Transition.STATIONARY,
                        Fluid.NONE, false, Hazard.FALL, Drop.AIRBORNE_OR_SWIMMING),
                clearPath,
                clearEndpoint,
                AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE)).isFalse();
        assertThat(recoveryMovementSafe(
                clearCurrent,
                record(1, new Point(0.2D, 64.0D, 0.0D), Support.ABSENT,
                        Clearance.CLEAR, Transition.PROBE_ALLOWED, Fluid.LAVA, false,
                        Hazard.LAVA, Drop.AIRBORNE_OR_SWIMMING),
                clearEndpoint,
                AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE)).isFalse();
    }

    @Test
    void lavaExitAndWaterEntryRejectNewDamageOnThePathOrEndpoint() {
        var safePath = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.ABSENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.NONE, false, Hazard.NONE,
                Drop.AIRBORNE_OR_SWIMMING);
        var firePath = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.ABSENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.NONE, false, Hazard.FIRE_DAMAGE,
                Drop.AIRBORNE_OR_SWIMMING);
        var lavaCurrent = record(
                0, ORIGIN, Support.ABSENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.LAVA, false, Hazard.LAVA, Drop.AIRBORNE_OR_SWIMMING);
        var dryCurrent = record(
                0, ORIGIN, Support.PRESENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.NONE, false, Hazard.NONE, Drop.SUPPORTED);

        assertThat(recoveryMovementSafe(
                lavaCurrent, safePath, endpoint(Fluid.NONE, Hazard.NONE),
                AgentInputState.RecoveryMode.EXIT_LAVA)).isTrue();
        assertThat(recoveryMovementSafe(
                lavaCurrent, firePath, endpoint(Fluid.NONE, Hazard.NONE),
                AgentInputState.RecoveryMode.EXIT_LAVA)).isFalse();
        assertThat(recoveryMovementSafe(
                lavaCurrent, safePath, endpoint(Fluid.NONE, Hazard.CONTACT_DAMAGE),
                AgentInputState.RecoveryMode.EXIT_LAVA)).isFalse();

        assertThat(recoveryMovementSafe(
                dryCurrent, safePath, endpoint(Fluid.WATER, Hazard.NONE),
                AgentInputState.RecoveryMode.ENTER_WATER)).isTrue();
        assertThat(recoveryMovementSafe(
                dryCurrent, firePath, endpoint(Fluid.WATER, Hazard.NONE),
                AgentInputState.RecoveryMode.ENTER_WATER)).isFalse();
        assertThat(recoveryMovementSafe(
                dryCurrent, safePath, endpoint(Fluid.WATER, Hazard.FREEZING),
                AgentInputState.RecoveryMode.ENTER_WATER)).isFalse();
    }

    @Test
    void damageHazardGuardFailsClosedForUnknownAndDifferentDamageKinds() {
        assertThat(LocalObservationVolume.avoidsNewDamageHazard(
                Hazard.FIRE_DAMAGE, Hazard.FIRE_DAMAGE, Hazard.NONE)).isTrue();
        assertThat(LocalObservationVolume.avoidsNewDamageHazard(
                Hazard.FIRE_DAMAGE, Hazard.CONTACT_DAMAGE, Hazard.NONE)).isFalse();
        assertThat(LocalObservationVolume.avoidsNewDamageHazard(
                Hazard.FIRE_DAMAGE, Hazard.UNKNOWN, Hazard.NONE)).isFalse();
    }

    @Test
    void breathingSpaceCanBeReachedHorizontallyUnderACeiling() {
        var current = record(
                0, ORIGIN, Support.ABSENT, Clearance.CLEAR, Transition.STATIONARY,
                Fluid.WATER, false, Hazard.NONE, Drop.AIRBORNE_OR_SWIMMING);
        var path = record(
                1, new Point(0.2D, 64.0D, 0.0D), Support.ABSENT, Clearance.CLEAR,
                Transition.PROBE_ALLOWED, Fluid.WATER, false, Hazard.NONE,
                Drop.AIRBORNE_OR_SWIMMING);
        var endpoint = new LocalObservationVolume.EndpointSafety(
                Support.PRESENT, Clearance.CLEAR, Fluid.NONE, false,
                Hazard.NONE, LoadedState.LOADED, Drop.SUPPORTED);
        var intent = new AgentInputState.RecoveryIntent(
                AgentInputState.RecoveryMode.REACH_BREATHING_SPACE,
                new Vec3(3.0D, 64.0D, 0.0D),
                null);

        assertThat(LocalObservationVolume.recoveryMovementSafe(
                current, path, endpoint, ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D), intent)).isTrue();
    }

    @Test
    void publicRecordHasNoRawWorldIdentityChannel() {
        var componentTypes = Arrays.stream(ObservationRecord.class.getRecordComponents())
                .map(component -> component.getType().getName())
                .toList();

        assertThat(componentTypes)
                .noneMatch(type -> type.contains("BlockState"))
                .noneMatch(type -> type.contains("BlockPos"))
                .noneMatch(type -> type.contains("ResourceLocation"))
                .noneMatch(type -> type.equals(String.class.getName()));
    }

    private static ObservationRecord probe(int depth, Point to) {
        return new ObservationRecord(
                1L,
                7L,
                depth,
                ORIGIN,
                to,
                to,
                Support.PRESENT,
                Clearance.CLEAR,
                Transition.PROBE_ALLOWED,
                Fluid.NONE,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                Drop.SUPPORTED,
                false);
    }

    private static ObservationRecord unknown(int depth, Point to) {
        return new ObservationRecord(
                1L,
                7L,
                depth,
                ORIGIN,
                to,
                to,
                Support.UNKNOWN,
                Clearance.UNKNOWN,
                Transition.UNKNOWN,
                Fluid.UNKNOWN,
                false,
                Hazard.UNKNOWN,
                LoadedState.UNKNOWN,
                Drop.UNKNOWN,
                true);
    }

    private static ObservationRecord blocked(int depth, Point to) {
        return new ObservationRecord(
                1L,
                7L,
                depth,
                ORIGIN,
                to,
                to,
                Support.PRESENT,
                Clearance.BLOCKED,
                Transition.BLOCKED,
                Fluid.NONE,
                false,
                Hazard.COLLISION,
                LoadedState.LOADED,
                Drop.SUPPORTED,
                false);
    }

    private static boolean recoveryMovementSafe(
            ObservationRecord current,
            ObservationRecord path,
            LocalObservationVolume.EndpointSafety endpoint,
            AgentInputState.RecoveryMode mode) {
        return LocalObservationVolume.recoveryMovementSafe(
                current,
                path,
                endpoint,
                ORIGIN,
                new Point(0.2D, 64.0D, 0.0D),
                new Point(0.1D, 64.0D, 0.0D),
                new Vec3(0.2D, 0.0D, 0.0D),
                new AgentInputState.RecoveryIntent(
                        mode, new Vec3(3.0D, 64.0D, 0.0D), null));
    }

    private static LocalObservationVolume.EndpointSafety endpoint(
            Fluid fluid, Hazard hazard) {
        return new LocalObservationVolume.EndpointSafety(
                Support.PRESENT,
                Clearance.CLEAR,
                fluid,
                false,
                hazard,
                LoadedState.LOADED,
                fluid == Fluid.NONE ? Drop.SUPPORTED : Drop.AIRBORNE_OR_SWIMMING);
    }

    private static ObservationRecord record(
            int depth,
            Point to,
            Support support,
            Clearance clearance,
            Transition transition,
            Fluid fluid,
            boolean suffocation,
            Hazard hazard,
            Drop drop) {
        return new ObservationRecord(
                1L,
                7L,
                depth,
                ORIGIN,
                to,
                to,
                support,
                clearance,
                transition,
                fluid,
                suffocation,
                hazard,
                LoadedState.LOADED,
                drop,
                false);
    }
}
