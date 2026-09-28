package dev.aod.mcmcp.agent.navigation;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static dev.aod.mcmcp.agent.action.AgentJobStore.Kind.MOVE;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.CANCELLED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.RUNNING;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.SUCCEEDED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.UNCONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

class CoordinateMoveJobExecutionTest {
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void knownGoalRunsOnlyAfterDeliveryAndPublishesSuccessAfterRelease() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(RUNNING_STEP, SUCCESS_STEP);
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, cell(1), driver, released::get);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(UNCONFIRMED);
        assertThat(driver.routes).isEmpty();
        store.confirm(id, 2);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 2, 2, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(driver.routes).singleElement().satisfies(route ->
                assertThat(route.cells()).containsExactly(cell(0), cell(1)));
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 3, 3, true, 1, () -> true).state()).isEqualTo(RUNNING);
        released.set(true);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 4, 4, true, 1, () -> true).state()).isEqualTo(SUCCEEDED);
        assertThat(store.get(id).completedOperations()).isEqualTo(2);
    }

    @Test
    void unknownGoalPlansNextSegmentOnlyAfterFreshEvidence() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(RUNNING_STEP, SUCCESS_STEP, RUNNING_STEP, SUCCESS_STEP);
        var execution = execution(store, id, cell(2), driver, () -> true);
        store.confirm(id, 1);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true);
        execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 2, 2, true, 1, () -> true);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 3, 3, true, 1, () -> true).state()).isEqualTo(RUNNING);
        assertThat(driver.routes).hasSize(1);

        map.observe(edge(cell(1), cell(2)));
        execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 4, 4, true, 1, () -> true);
        assertThat(driver.routes).hasSize(2);
        assertThat(driver.routes.get(1).cells()).containsExactly(cell(1), cell(2));
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(2), SESSION,
                0, 5, 5, true, 2, () -> true).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(2), SESSION,
                0, 6, 6, true, 2, () -> true).state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void cancelStopsBeforeFurtherMovementAndWaitsForRelease() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(RUNNING_STEP);
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, cell(1), driver, released::get);
        store.confirm(id, 1);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true);
        assertThat(execution.cancel().state()).isEqualTo(RUNNING);
        released.set(true);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 2, 2, true, 0, () -> true).state()).isEqualTo(CANCELLED);
        assertThat(driver.ticks).isEqualTo(1);
    }

    @Test
    void initiallyUnobservedGoalWaitsForEvidenceThenMoves() {
        var map = map();
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(RUNNING_STEP, SUCCESS_STEP);
        var execution = execution(store, id, cell(1), driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(driver.routes).isEmpty();

        map.observe(edge(cell(0), cell(1)));
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 2, 2, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(driver.routes).hasSize(1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 3, 3, true, 1, () -> true).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 4, 4, true, 1, () -> true).state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void nearbyArrivalUsesOneSharedJobAndStopsBeforeUnobservedGoalCell() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(RUNNING_STEP, SUCCESS_STEP);
        var execution = new CoordinateMoveJobExecution(store, id, SESSION,
                cell(2), 1.0D, 0.25D, 16.0D, driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(driver.routes).singleElement().satisfies(route ->
                assertThat(route.cells()).containsExactly(cell(0), cell(1)));
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 2, 2, true, 1, () -> true).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 3, 3, true, 1, () -> true).state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void finalRouteChecksNextTickPositionEvenWhenInputBudgetIsSpent() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 1, 100);
        var driver = new FakeDriver(SUCCESS_STEP);
        var execution = execution(store, id, cell(1), driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION,
                0, 2, 2, true, 1, () -> true).state()).isEqualTo(SUCCEEDED);
        assertThat(driver.ticks).isEqualTo(1);
    }

    @Test
    void finalRouteDoesNotReissueInputWhenNextTickStillMissesGoal() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 4, 100);
        var driver = new FakeDriver(SUCCESS_STEP);
        var execution = execution(store, id, cell(1), driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 2, 2, true, 0, () -> true).state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(store.get(id).failure()).isEqualTo("goal_not_reached");
        assertThat(driver.routes).hasSize(1);
        assertThat(driver.ticks).isEqualTo(1);
    }

    @Test
    void invalidatedFinalRouteFailsWithoutReissuingMovement() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED,
                MinecraftActionPrimitiveExecutor.Reason.DESTINATION_SAFETY_UNVERIFIED));
        var execution = execution(store, id, cell(1), driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state())
                .isEqualTo(AgentJobStore.State.FAILED);
        assertThat(store.get(id).failure()).isEqualTo("route_replan_required");
        assertThat(driver.routes).hasSize(1);
    }

    @Test
    void optionalPathWorkReplansOnlyAfterFreshEvidenceAndStillObeysTickLimit() {
        var map = map(edge(cell(0), cell(1)));
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(new MinecraftActionPrimitiveExecutor.TickResult(
                MinecraftActionPrimitiveExecutor.Status.REPLAN_REQUIRED,
                MinecraftActionPrimitiveExecutor.Reason.DESTINATION_SAFETY_UNVERIFIED), RUNNING_STEP);
        var execution = execution(store, id, cell(1), driver, () -> true);
        execution.clearPathWith(new FakeObstacles());
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 1, 1, true, 0, () -> true).state()).isEqualTo(RUNNING);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 2, 2, true, 0, () -> true);
        assertThat(driver.routes).hasSize(1);
        map.observe(edge(cell(1), cell(2)));
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 3, 3, true, 0, () -> true);
        assertThat(driver.routes).hasSize(2);
        assertThat(store.get(id).result()).containsEntry("path_replans", 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION,
                0, 11, 11, true, 0, () -> true).state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(store.get(id).failure()).isEqualTo("tick_limit");
    }

    @Test
    void obstacleWorkIsDeliveryGatedAndCancellationWaitsForItsRelease() {
        var map = map();
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver();
        var released = new AtomicBoolean(false);
        var execution = execution(store, id, cell(2), driver, released::get);
        var edits = new FakeObstacles();
        execution.clearPathWith(edits);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 1, 1, true, 0, () -> true);
        assertThat(edits.ticks).isZero();
        store.confirm(id, 2);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 2, 2, true, 0, () -> true);
        assertThat(edits.ticks).isEqualTo(1);
        assertThat(execution.cancel().state()).isEqualTo(RUNNING);
        released.set(true);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 3, 3, true, 0, () -> true).state()).isEqualTo(CANCELLED);
        assertThat(edits.ticks).isEqualTo(1);
        assertThat(edits.closed).isTrue();
        assertThat(store.get(id).result()).containsEntry("path_changed_count", 1);
        assertThat(driver.routes).isEmpty();
    }

    @Test
    void confirmedPathEditStillRequiresFreshNavigationEvidence() {
        var map = map();
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver(SUCCESS_STEP);
        var execution = execution(store, id, cell(1), driver, () -> true);
        var edits = new FakeObstacles();
        edits.step = CoordinateMoveJobExecution.ObstacleStep.CHANGED;
        execution.clearPathWith(edits);
        store.confirm(id, 1);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 1, 1, true, 0, () -> true);
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 2, 2, true, 0, () -> true);
        assertThat(driver.routes).isEmpty();
        assertThat(edits.ticks).isEqualTo(1);
        map.observe(edge(cell(0), cell(1)));
        execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 3, 3, true, 0, () -> true);
        assertThat(driver.routes).hasSize(1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(1), SESSION, 0, 4, 4, true, 1, () -> true).state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void metConditionStopsWithoutMovementButCannotBypassSafetyOrDelivery() {
        var map = map();
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver();
        var execution = execution(store, id, cell(2), driver, () -> true);
        execution.stopWhen(() -> true);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 1, 1, true, 0, () -> true).state()).isEqualTo(UNCONFIRMED);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 2, 2, true, 0, () -> true).state()).isEqualTo(SUCCEEDED);
        assertThat(store.get(id).result()).containsEntry("stop_condition_met", true);
        assertThat(driver.routes).isEmpty();
        var next = store.reserve(MOVE, SESSION, 10, 100);
        var unsafe = execution(store, next, cell(2), driver, () -> true);
        unsafe.stopWhen(() -> true);
        store.confirm(next, 3);
        assertThat(unsafe.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0, 4, 4, false, 0, () -> true).state()).isEqualTo(AgentJobStore.State.FAILED);
    }

    @Test
    void observationWaitAlsoConsumesTheElapsedTickBudget() {
        var map = map();
        var store = new AgentJobStore();
        var id = store.reserve(MOVE, SESSION, 10, 100);
        var driver = new FakeDriver();
        var execution = execution(store, id, cell(2), driver, () -> true);
        store.confirm(id, 1);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0,
                1, 1, true, 0, () -> true).state()).isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(execution.tick(map.snapshot().orElseThrow(), cell(0), SESSION, 0,
                11, 11, true, 0, () -> true).state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(store.get(id).failure()).isEqualTo("tick_limit");
        assertThat(driver.routes).isEmpty();
    }

    private static class FakeObstacles implements CoordinateMoveJobExecution.ObstacleDriver {
        int ticks;
        boolean closed;
        CoordinateMoveJobExecution.ObstacleStep step = CoordinateMoveJobExecution.ObstacleStep.WORKING;
        public CoordinateMoveJobExecution.ObstacleStep tick(NavCell current, long tick, BooleanSupplier allowed) {
            assertThat(allowed.getAsBoolean()).isTrue(); ticks++; return step;
        }
        public java.util.Map<String, Object> result() { return java.util.Map.of("path_changed_count", 1); }
        public void close() { closed = true; }
    }

    private static CoordinateMoveJobExecution execution(AgentJobStore store, UUID id,
            NavCell goal, FakeDriver driver, BooleanSupplier release) {
        return new CoordinateMoveJobExecution(
                store, id, SESSION, goal, 0.25D, 256.0D, driver, release);
    }

    private static KnownTraversabilityMap map(TraversabilityEdge... edges) {
        var map = new KnownTraversabilityMap();
        map.startSession(SESSION, "overworld", 0);
        for (var edge : edges) map.observe(edge);
        return map;
    }

    private static NavCell cell(int x) {
        return new NavCell("overworld", x, 64, 0);
    }

    private static TraversabilityEdge edge(NavCell from, NavCell to) {
        return new TraversabilityEdge(SESSION, new TraversabilityEdge.Key(from, to),
                TraversabilityEdge.Status.CONFIRMED,
                TraversabilityEdge.TargetSupport.CONFIRMED,
                TraversabilityEdge.Clearance.CONFIRMED,
                TraversabilityEdge.Transition.CONFIRMED,
                TraversabilityEdge.Fluid.NONE,
                TraversabilityEdge.Hazard.NONE,
                TraversabilityEdge.Provenance.LOCAL_VOLUME,
                from, 1, 0);
    }

    private static final MinecraftActionPrimitiveExecutor.TickResult RUNNING_STEP =
            new MinecraftActionPrimitiveExecutor.TickResult(
                    MinecraftActionPrimitiveExecutor.Status.RUNNING,
                    MinecraftActionPrimitiveExecutor.Reason.NONE);
    private static final MinecraftActionPrimitiveExecutor.TickResult SUCCESS_STEP =
            new MinecraftActionPrimitiveExecutor.TickResult(
                    MinecraftActionPrimitiveExecutor.Status.SUCCEEDED,
                    MinecraftActionPrimitiveExecutor.Reason.NONE);

    private static final class FakeDriver implements CoordinateMoveJobExecution.MovementDriver {
        final ArrayDeque<MinecraftActionPrimitiveExecutor.TickResult> results = new ArrayDeque<>();
        final List<RoutePlan> routes = new ArrayList<>();
        boolean active;
        int ticks;

        FakeDriver(MinecraftActionPrimitiveExecutor.TickResult... steps) {
            results.addAll(List.of(steps));
        }

        @Override
        public void begin(RoutePlan route, double tolerance) {
            routes.add(route);
            active = true;
        }

        @Override
        public MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map,
                double remainingDistance, long clientTick, BooleanSupplier outputAllowed) {
            assertThat(outputAllowed.getAsBoolean()).isTrue();
            ticks++;
            var result = results.removeFirst();
            if (result.terminal()) active = false;
            return result;
        }

        @Override
        public boolean active() { return active; }

        @Override
        public void close() { active = false; }
    }
}
