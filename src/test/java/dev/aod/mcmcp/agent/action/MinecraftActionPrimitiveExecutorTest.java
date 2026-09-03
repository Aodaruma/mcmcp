package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftActionPrimitiveExecutorTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void closeRetainsTheExactMovementLeaseUntilARetryConfirmsRelease() throws Exception {
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        var failFirstRelease = new java.util.concurrent.atomic.AtomicBoolean(true);
        var control = new MovementInputLease.MovementControl() {
            @Override
            public void apply(java.util.Set<MovementInputLease.MovementKey> keys) {
            }

            @Override
            public void release() {
                releases.incrementAndGet();
                if (failFirstRelease.getAndSet(false)) {
                    throw new IllegalStateException("release failed once");
                }
            }
        };
        var executor = new MinecraftActionPrimitiveExecutor(4.0F);
        var ownerField = MinecraftActionPrimitiveExecutor.class.getDeclaredField("ownerId");
        ownerField.setAccessible(true);
        var owner = (UUID) ownerField.get(executor);
        var movement = MovementInputLease.acquire(
                control, owner, 0L, java.time.Duration.ofSeconds(1));
        var field = MinecraftActionPrimitiveExecutor.class.getDeclaredField("movement");
        field.setAccessible(true);
        field.set(executor, movement);

        assertThatThrownBy(executor::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("release failed once");
        assertThat(executor.active()).isTrue();

        executor.close();
        assertThat(executor.active()).isFalse();
        assertThat(releases).hasValue(2);
    }

    @Test
    void aimsInsideTheDeclaredBlockFaceRatherThanAtTheBlockCenter() {
        var target = new ActionDsl.Position(DIMENSION, 10, 64, 20);

        var west = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.WEST);
        var up = MinecraftActionPrimitiveExecutor.blockFaceAimPoint(
                target, ActionDsl.BlockFace.UP);

        assertThat(west.x).isEqualTo(10.001D);
        assertThat(west.y).isEqualTo(64.5D);
        assertThat(up.y).isEqualTo(64.999D);
    }

    @Test
    void rechecksDiagonalCornerProofBeforeMovement() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 1);
        NavCell xSide = cell(1, 64, 0);
        NavCell zSide = cell(0, 64, 1);
        map.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        map.observe(edge(session, start, xSide, TraversabilityEdge.Status.CONFIRMED, 1));
        map.observe(edge(session, start, zSide, TraversabilityEdge.Status.CONFIRMED, 1));

        RoutePlan route = new DeterministicAStar()
                .findRoute(map.snapshot().orElseThrow(), start, target)
                .route().orElseThrow();
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.CONFIRMED);

        map.observe(edge(session, start, xSide, TraversabilityEdge.Status.BLOCKED, 2));
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.REPLAN);
    }

    @Test
    void permitsOnlyProbeThatWasIncludedInCompiledRoute() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 0);
        var probeMap = new KnownTraversabilityMap();
        probeMap.startSession(session, DIMENSION, 4);
        probeMap.observe(edge(session, start, target, TraversabilityEdge.Status.PROBE_ALLOWED, 1));
        RoutePlan probeRoute = route(probeMap.snapshot().orElseThrow(), start, target);
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                probeRoute, 0, probeMap.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.PROBE);

        var confirmedMap = new KnownTraversabilityMap();
        confirmedMap.startSession(session, DIMENSION, 4);
        confirmedMap.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        RoutePlan confirmedRoute = route(confirmedMap.snapshot().orElseThrow(), start, target);
        confirmedMap.observe(edge(session, start, target, TraversabilityEdge.Status.PROBE_ALLOWED, 2));
        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                confirmedRoute, 0, confirmedMap.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.REPLAN);
    }

    @Test
    void probeRemainsRunningAcrossFreshTicksWithinItsFiniteRouteBound() {
        var first = MinecraftActionPrimitiveExecutor.runningNavigationResult(
                MinecraftActionPrimitiveExecutor.EdgeDecision.PROBE, true);
        var next = MinecraftActionPrimitiveExecutor.runningNavigationResult(
                MinecraftActionPrimitiveExecutor.EdgeDecision.PROBE, true);

        assertThat(first).isEqualTo(new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.RUNNING,
                MinecraftActionPrimitiveExecutor.Reason.PROBE_MICRO_STEP));
        assertThat(next).isEqualTo(first);
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(55, 56)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(56, 56)).isFalse();
    }

    @Test
    void fencesEveryPlanToItsExactWorldRevision() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 9);
        KnownTraversabilitySnapshot snapshot = map.snapshot().orElseThrow();
        assertThat(MinecraftActionPrimitiveExecutor.boundaryDecision(
                session, DIMENSION, 9, snapshot))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.CURRENT);
        map.advanceWorldRevision(10, List.of(), List.of());
        assertThat(MinecraftActionPrimitiveExecutor.boundaryDecision(
                session, DIMENSION, 9, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.REVISION_CHANGED);
    }

    @Test
    void revisionWindowFaceCanCrossOnlyNewerRevalidatedMapRevisions() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 9L);
        var target = new ActionDsl.Position(DIMENSION, 1, 64, 1);
        var exact = new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                session, 8L, target);
        var window = new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                session, 8L, target, true);

        assertThat(MinecraftActionPrimitiveExecutor.faceBoundaryDecision(
                exact, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.REVISION_CHANGED);
        assertThat(MinecraftActionPrimitiveExecutor.faceBoundaryDecision(
                window, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.CURRENT);

        var older = new KnownTraversabilityMap();
        older.startSession(session, DIMENSION, 7L);
        assertThat(MinecraftActionPrimitiveExecutor.faceBoundaryDecision(
                window, older.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.BoundaryDecision.REVISION_CHANGED);
    }

    @Test
    void unrelatedWorldRevisionDoesNotInvalidateAnUnchangedRouteEdge() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 64, 0);
        NavCell target = cell(1, 64, 0);
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        map.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        RoutePlan route = route(map.snapshot().orElseThrow(), start, target);

        map.advanceWorldRevision(5, List.of(), List.of());

        assertThat(MinecraftActionPrimitiveExecutor.edgeDecision(
                route, 0, map.snapshot().orElseThrow()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.EdgeDecision.CONFIRMED);
    }

    @Test
    void boundsViewChangesAndAcceptsOnlyTheSweptRouteCorridor() {
        float configuredLimit = 4.5F;
        assertThatThrownBy(() -> new MinecraftActionPrimitiveExecutor(0.0F))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MinecraftActionPrimitiveExecutor.boundedYawDelta(
                179.0F, -179.0F, configuredLimit))
                .isEqualTo(2.0F);
        assertThat(MinecraftActionPrimitiveExecutor.boundedPitchDelta(
                0.0F, 80.0F, configuredLimit))
                .isEqualTo(configuredLimit);
        var diagonal = new TraversabilityEdge.Key(cell(0, 64, 0), cell(1, 64, 1));
        assertThat(MinecraftActionPrimitiveExecutor.insideRouteCorridor(
                1.0D, 64.0D, 1.0D, diagonal)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.insideRouteCorridor(
                0.5D, 64.0D, 2.0D, diagonal)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.5D, 0.5D, 0.0F, cell(0, 64, 1)))
                .containsExactly(MovementInputLease.MovementKey.FORWARD);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.5D, 0.5D, 0.0F, cell(1, 64, 0)))
                .containsExactly(MovementInputLease.MovementKey.LEFT);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.4D, 0.0F, cell(0, 64, 0), 0.10D))
                .isNotEmpty();
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.5D, 0.0F, 0.2D, 0.5D, 0.05D))
                .containsExactly(MovementInputLease.MovementKey.RIGHT);
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.4D, 0.5D, 0.0F, 0.4D, 0.5D, 0.05D))
                .isEmpty();
        var diagonalCommand = MinecraftActionPrimitiveExecutor.commandDirection(
                30.0F,
                java.util.Set.of(
                        MovementInputLease.MovementKey.FORWARD,
                        MovementInputLease.MovementKey.LEFT));
        assertThat(diagonalCommand.horizontalDistance()).isCloseTo(
                1.0D, org.assertj.core.data.Offset.offset(1.0e-12D));
        assertThat(MinecraftActionPrimitiveExecutor.commandDirection(
                0.0F, java.util.Set.of(MovementInputLease.MovementKey.FORWARD)))
                .isEqualTo(new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D));
        assertThat(MinecraftActionPrimitiveExecutor.commandDirection(
                0.0F, java.util.Set.of(MovementInputLease.MovementKey.LEFT)))
                .isEqualTo(new net.minecraft.world.phys.Vec3(1.0D, 0.0D, 0.0D));
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(35, 36)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.navigationOutputAllowed(36, 36)).isFalse();
        var waypoint = cell(1, 64, 0);
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                1.5D, 64.9375D, 0.5D, waypoint, 0.75D)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                1.5D, 65.0D, 0.5D, waypoint, 0.75D)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                0.99D, 64.0D, 0.5D, waypoint, 0.75D)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                1.5D, 63.99D, 0.5D, waypoint, 0.75D)).isFalse();
    }

    @Test
    void zeroEdgeRouteDrivesToCenterBeforeUsingTheSettlementPath() {
        UUID session = UUID.randomUUID();
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        var cell = cell(0, 64, 0);
        map.observe(edge(
                session, cell, cell(1, 64, 0), TraversabilityEdge.Status.CONFIRMED, 1));
        var original = map.snapshot().orElseThrow();
        var route = route(original, cell, cell);

        assertThat(route.tickUpperBound()).isEqualTo(36L);
        assertThat(route.durationMillisUpperBound()).isEqualTo(1_800L);
        assertThat(MinecraftActionPrimitiveExecutor.sameCellRouteCurrent(route, original)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.sameCellDecision(
                0.01D, 64.0D, 0.5D, cell, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SameCellDecision.DRIVE);
        assertThat(MinecraftActionPrimitiveExecutor.sameCellDecision(
                0.5D, 64.9375D, 0.5D, cell, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SameCellDecision.SETTLE);
        assertThat(MinecraftActionPrimitiveExecutor.sameCellDecision(
                1.0D, 64.0D, 0.5D, cell, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SameCellDecision.OFF_ROUTE);

        map.advanceWorldRevision(5, List.of(), List.of());
        var advanced = map.snapshot().orElseThrow();
        assertThat(MinecraftActionPrimitiveExecutor.sameCellRouteCurrent(route, advanced)).isTrue();
        var newerRoute = route(advanced, cell, cell);
        assertThat(MinecraftActionPrimitiveExecutor.sameCellRouteCurrent(newerRoute, original))
                .isFalse();

        map.advanceWorldRevision(6, List.of(cell), List.of());
        assertThat(MinecraftActionPrimitiveExecutor.sameCellRouteCurrent(
                route, map.snapshot().orElseThrow())).isFalse();
    }

    @Test
    void shallowAscentUsesVanillaAutoStepAndFullBlockAscentJumps() {
        assertThat(MinecraftActionPrimitiveExecutor.jumpRequired(
                1, 0.0625D, 0.6D)).isFalse();
        assertThat(MinecraftActionPrimitiveExecutor.jumpRequired(
                1, 1.0D, 0.6D)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.jumpRequired(
                -1, -1.0D, 0.6D)).isFalse();
    }

    @Test
    void scaffoldingUsesJumpUpAndCrouchDownWhileTrackingVerticalProgress() {
        assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(
                java.util.Set.of(), 1, 1.0D, 0.6D, Locomotion.SCAFFOLDING))
                .containsExactly(MovementInputLease.MovementKey.JUMP);
        assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(
                java.util.Set.of(), 1, 0.2D, 0.6D, Locomotion.SCAFFOLDING))
                .containsExactly(MovementInputLease.MovementKey.JUMP);
        assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(
                java.util.Set.of(), -1, -1.0D, 0.6D, Locomotion.SCAFFOLDING))
                .containsExactly(MovementInputLease.MovementKey.CROUCH);
        assertThat(MinecraftActionPrimitiveExecutor.withVerticalInput(
                java.util.Set.of(), -1, -1.0D, 0.6D, Locomotion.LADDER)).isEmpty();
        var target = cell(0, 65, 0);
        assertThat(MinecraftActionPrimitiveExecutor.navigationDistance(
                0.5D, 64.0D, 0.5D, target, Locomotion.SCAFFOLDING)).isEqualTo(1.0D);
        assertThat(MinecraftActionPrimitiveExecutor.navigationDistance(
                0.5D, 64.5D, 0.5D, target, Locomotion.SCAFFOLDING)).isEqualTo(0.5D);
    }

    @Test
    void oneBlockDescentReacquiresExactTargetAfterLandingMomentumDrift() {
        UUID session = UUID.randomUUID();
        NavCell start = cell(0, 65, 0);
        NavCell target = cell(-1, 64, 0);
        var map = new KnownTraversabilityMap();
        map.startSession(session, DIMENSION, 4);
        map.observe(edge(session, start, target, TraversabilityEdge.Status.CONFIRMED, 1));
        RoutePlan route = route(map.snapshot().orElseThrow(), start, target);

        assertThat(MinecraftActionPrimitiveExecutor.insideRouteCorridor(
                -0.20D, 64.50D, 0.5D, new TraversabilityEdge.Key(start, target))).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.settlementDriftDecision(
                route, map.snapshot().orElseThrow(),
                -0.55D, 64.0D, 0.5D, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementDriftDecision.SETTLE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementDriftDecision(
                route, map.snapshot().orElseThrow(),
                -0.62D, 64.0D, 0.5D, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementDriftDecision.DRIVE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementDriftDecision(
                route, map.snapshot().orElseThrow(),
                -1.40D, 64.0D, 0.5D, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementDriftDecision.OFF_ROUTE);

        map.observe(edge(session, start, target, TraversabilityEdge.Status.BLOCKED, 2));
        assertThat(MinecraftActionPrimitiveExecutor.settlementDriftDecision(
                route, map.snapshot().orElseThrow(),
                -0.62D, 64.0D, 0.5D, 0.1D))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementDriftDecision.ROUTE_CHANGED);
    }

    @Test
    void supportedLadderLandingAddsJumpOnlyAfterFallingBelowItsHeight() {
        assertThat(MinecraftActionPrimitiveExecutor.effectiveVerticalDelta(
                0, Locomotion.LADDER,
                TraversabilityEdge.TargetSupport.CONFIRMED, 0.2D)).isEqualTo(1);
        assertThat(MinecraftActionPrimitiveExecutor.effectiveVerticalDelta(
                0, Locomotion.LADDER,
                TraversabilityEdge.TargetSupport.CONFIRMED, 0.0D)).isZero();
        assertThat(MinecraftActionPrimitiveExecutor.effectiveVerticalDelta(
                0, Locomotion.LADDER,
                TraversabilityEdge.TargetSupport.ABSENT, 0.2D)).isZero();
        assertThat(MinecraftActionPrimitiveExecutor.effectiveVerticalDelta(
                0, Locomotion.SCAFFOLDING,
                TraversabilityEdge.TargetSupport.CONFIRMED, 0.2D)).isZero();
        assertThat(MinecraftActionPrimitiveExecutor.effectiveVerticalDelta(
                -1, Locomotion.LADDER,
                TraversabilityEdge.TargetSupport.CONFIRMED, 0.2D)).isEqualTo(-1);
    }

    @Test
    void settlementAcceptsCurrentSafeEvidenceWhileStandingOnAPressurePlate() {
        long revision = 12L;
        var plate = Blocks.OAK_PRESSURE_PLATE.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, true);
        var plateShape = plate.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        assertThat(plateShape.isEmpty()).isFalse();
        assertThat(plate.getCollisionShape(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()).isTrue();
        double feetY = 64.0D;
        var destination = cell(0, 64, 0);
        var playerBox = new AABB(
                0.2D, feetY, 0.2D,
                0.8D, feetY + 1.8D, 0.8D);
        var safety = clearSafetySnapshot(revision, playerBox);

        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                0.5D, feetY, 0.5D, destination, 0.1D)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                revision, Optional.of(safety)))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.CLEAR);
        assertThat(MinecraftActionPrimitiveExecutor.settlementCompletion(
                MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.CLEAR,
                MinecraftActionPrimitiveExecutor.SETTLE_STABLE_TICKS,
                MinecraftActionPrimitiveExecutor.SETTLE_SAFETY_TICKS).status())
                .isEqualTo(MinecraftActionPrimitiveExecutor.Status.SUCCEEDED);
    }

    @Test
    void settlementAcceptsTheClearDestinationBeyondAnOpenDoorVanillaShape() {
        long revision = 15L;
        BlockPos doorPosition = new BlockPos(0, 64, 0);
        var openDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, true);
        var playerBox = new AABB(0.2D, 64.0D, 1.2D, 0.8D, 65.8D, 1.8D);
        assertThat(openDoor.getCollisionShape(EmptyBlockGetter.INSTANCE, doorPosition)
                .toAabbs().stream()
                .map(box -> box.move(doorPosition))
                .noneMatch(box -> box.intersects(playerBox))).isTrue();

        var safety = clearSafetySnapshot(revision, playerBox);
        assertThat(MinecraftActionPrimitiveExecutor.waypointReached(
                0.5D, 64.0D, 1.5D, cell(0, 64, 1), 0.1D)).isTrue();
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                revision, Optional.of(safety)))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.CLEAR);
    }

    @Test
    void settlementAcceptsFreshSafeEvidenceAfterAnUnrelatedWorldRevisionChange() {
        long changedRevision = 21L;
        var safety = clearSafetySnapshot(
                changedRevision, new AABB(0.2D, 64.0D, 0.2D, 0.8D, 65.8D, 0.8D));

        var decision = MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                changedRevision, Optional.of(safety));
        var completion = MinecraftActionPrimitiveExecutor.settlementCompletion(
                decision,
                MinecraftActionPrimitiveExecutor.SETTLE_STABLE_TICKS,
                MinecraftActionPrimitiveExecutor.SETTLE_SAFETY_TICKS);

        assertThat(decision)
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.CLEAR);
        assertThat(completion).isEqualTo(new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.SUCCEEDED,
                MinecraftActionPrimitiveExecutor.Reason.NONE));
    }

    @Test
    void settlementRequiresThreeConsecutiveCurrentClearSafetyTicks() {
        assertThat(MinecraftActionPrimitiveExecutor.settlementCompletion(
                MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.CLEAR,
                MinecraftActionPrimitiveExecutor.SETTLE_STABLE_TICKS,
                MinecraftActionPrimitiveExecutor.SETTLE_SAFETY_TICKS - 1))
                .isEqualTo(new MinecraftActionPrimitiveExecutor.TickResult(
                        MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED,
                        MinecraftActionPrimitiveExecutor.Reason.DESTINATION_SAFETY_UNVERIFIED));
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L, Optional.empty()))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.MISSING_OR_STALE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L,
                Optional.of(safetySnapshot(
                        6L,
                        ObservationRecord.Support.PRESENT,
                        ObservationRecord.Clearance.CLEAR,
                        ObservationRecord.Fluid.NONE,
                        ObservationRecord.Hazard.NONE))))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.MISSING_OR_STALE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L,
                Optional.of(safetySnapshot(
                        7L,
                        ObservationRecord.Support.PRESENT,
                        ObservationRecord.Clearance.BLOCKED,
                        ObservationRecord.Fluid.NONE,
                        ObservationRecord.Hazard.NONE))))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.UNSAFE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L,
                Optional.of(safetySnapshot(
                        7L,
                        ObservationRecord.Support.ABSENT,
                        ObservationRecord.Clearance.CLEAR,
                        ObservationRecord.Fluid.NONE,
                        ObservationRecord.Hazard.NONE))))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.UNSAFE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L,
                Optional.of(safetySnapshot(
                        7L,
                        ObservationRecord.Support.PRESENT,
                        ObservationRecord.Clearance.CLEAR,
                        ObservationRecord.Fluid.WATER,
                        ObservationRecord.Hazard.NONE))))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.UNSAFE);
        assertThat(MinecraftActionPrimitiveExecutor.settlementSafetyDecision(
                7L,
                Optional.of(safetySnapshot(
                        7L,
                        ObservationRecord.Support.PRESENT,
                        ObservationRecord.Clearance.CLEAR,
                        ObservationRecord.Fluid.NONE,
                        ObservationRecord.Hazard.CONTACT_DAMAGE))))
                .isEqualTo(MinecraftActionPrimitiveExecutor.SettlementSafetyDecision.UNSAFE);
    }

    @Test
    void steeringQuantizesToTheEightDirectionWithMaximumWorldVectorDot() {
        assertThat(MinecraftActionPrimitiveExecutor.steering(
                0.0D,
                0.0D,
                -174.75365F,
                0.464821D,
                -1.062151D,
                0.0D))
                .containsExactly(MovementInputLease.MovementKey.FORWARD);
    }

    private static RoutePlan route(
            KnownTraversabilitySnapshot snapshot, NavCell start, NavCell target) {
        return new DeterministicAStar().findRoute(snapshot, start, target).route().orElseThrow();
    }

    private static NavCell cell(int x, int y, int z) {
        return new NavCell(DIMENSION, x, y, z);
    }

    private static LocalObservationVolume.Snapshot clearSafetySnapshot(
            long revision, AABB playerBox) {
        var center = playerBox.getCenter();
        return safetySnapshot(
                revision,
                new ObservationRecord.Point(center.x, center.y, center.z),
                ObservationRecord.Support.PRESENT,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Fluid.NONE,
                ObservationRecord.Hazard.NONE);
    }

    private static LocalObservationVolume.Snapshot safetySnapshot(
            long revision,
            ObservationRecord.Support support,
            ObservationRecord.Clearance clearance,
            ObservationRecord.Fluid fluid,
            ObservationRecord.Hazard hazard) {
        return safetySnapshot(
                revision,
                new ObservationRecord.Point(0.5D, 64.9D, 0.5D),
                support,
                clearance,
                fluid,
                hazard);
    }

    private static LocalObservationVolume.Snapshot safetySnapshot(
            long revision,
            ObservationRecord.Point point,
            ObservationRecord.Support support,
            ObservationRecord.Clearance clearance,
            ObservationRecord.Fluid fluid,
            ObservationRecord.Hazard hazard) {
        var current = new ObservationRecord(
                1L,
                revision,
                0,
                point,
                point,
                point,
                support,
                clearance,
                ObservationRecord.Transition.STATIONARY,
                fluid,
                false,
                hazard,
                ObservationRecord.LoadedState.LOADED,
                support == ObservationRecord.Support.PRESENT
                        ? ObservationRecord.Drop.SUPPORTED
                        : ObservationRecord.Drop.AIRBORNE_OR_SWIMMING,
                false);
        return new LocalObservationVolume.Snapshot(1L, revision, point, current, List.of());
    }

    private static TraversabilityEdge edge(
            UUID session,
            NavCell from,
            NavCell to,
            TraversabilityEdge.Status status,
            long tick) {
        return new TraversabilityEdge(
                session,
                new TraversabilityEdge.Key(from, to),
                status,
                status == TraversabilityEdge.Status.BLOCKED
                        ? TraversabilityEdge.TargetSupport.ABSENT
                        : TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                status == TraversabilityEdge.Status.PROBE_ALLOWED
                        ? TraversabilityEdge.Transition.PARTIAL
                        : TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from,
                tick,
                4);
    }
}
