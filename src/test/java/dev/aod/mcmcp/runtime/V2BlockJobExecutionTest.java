package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class V2BlockJobExecutionTest {
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void walksOnlyRequestedCellsAndPublishesAfterRelease() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 10, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 2), "minecraft:overworld");
        var driver = new FakeDriver();
        driver.begins.addAll(List.of(
                V2BlockJobExecution.BeginResult.SKIPPED,
                V2BlockJobExecution.BeginResult.STARTED,
                V2BlockJobExecution.BeginResult.STARTED));
        driver.steps.addAll(List.of(
                V2BlockJobExecution.StepResult.CONFIRMED,
                V2BlockJobExecution.StepResult.CONFIRMED));
        var released = new AtomicBoolean(false);
        var job = new V2BlockJobExecution<>(store, id, SESSION,
                AgentJobStore.Kind.BREAK_BLOCK, request, driver, released::get);
        assertThat(job.tick(SESSION, 1, 1, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.UNCONFIRMED);
        assertThat(driver.targets).isEmpty();
        store.confirm(id, 2);
        job.tick(SESSION, 2, 2, true, () -> true);
        job.tick(SESSION, 3, 3, true, () -> true);
        assertThat(job.tick(SESSION, 4, 4, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(job.completedBlocks()).isEqualTo(2);
        released.set(true);
        assertThat(job.tick(SESSION, 5, 5, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(driver.targets).containsExactly(
                new NavCell("minecraft:overworld", 10, 64, 20),
                new NavCell("minecraft:overworld", 11, 64, 20),
                new NavCell("minecraft:overworld", 12, 64, 20));
        assertThat(store.get(id).completedOperations()).isEqualTo(2);
        assertThat(store.get(id).scannedCells()).isEqualTo(3);
        assertThat(store.get(id).completedBlocks()).isEqualTo(2);
        store.reserve(AgentJobStore.Kind.MOVE, SESSION, 10, 100);
        assertThat(store.get(id).completedBlocks()).isEqualTo(2);
    }

    @Test
    void cancelReleasesBeforeTerminalAndNeverBeginsAnotherTarget() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 10, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 1), "minecraft:overworld");
        var driver = new FakeDriver();
        driver.begins.add(V2BlockJobExecution.BeginResult.STARTED);
        driver.steps.add(V2BlockJobExecution.StepResult.RUNNING);
        var released = new AtomicBoolean(false);
        var job = new V2BlockJobExecution<>(store, id, SESSION,
                AgentJobStore.Kind.BREAK_BLOCK, request, driver, released::get);
        store.confirm(id, 1);
        job.tick(SESSION, 1, 1, true, () -> true);
        assertThat(job.cancel().state()).isEqualTo(AgentJobStore.State.RUNNING);
        released.set(true);
        assertThat(job.tick(SESSION, 2, 2, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(driver.targets).hasSize(1);
        assertThat(driver.ticks).isEqualTo(1);
    }

    @Test
    void approachCanSkipAConditionMismatchOnlyAfterItsDriverChecksTheTarget() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 10, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 1, "advance", true),
                "minecraft:overworld");
        var driver = new FakeDriver();
        driver.begins.addAll(List.of(
                V2BlockJobExecution.BeginResult.STARTED,
                V2BlockJobExecution.BeginResult.STARTED));
        driver.steps.addAll(List.of(
                V2BlockJobExecution.StepResult.RUNNING,
                V2BlockJobExecution.StepResult.SKIPPED,
                V2BlockJobExecution.StepResult.CONFIRMED));
        var job = new V2BlockJobExecution<>(store, id, SESSION,
                AgentJobStore.Kind.BREAK_BLOCK, request, driver, () -> true);
        store.confirm(id, 1);
        job.tick(SESSION, 1, 1, true, () -> true);
        job.tick(SESSION, 2, 2, true, () -> true);
        assertThat(job.tick(SESSION, 3, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(store.get(id).scannedCells()).isEqualTo(2);
        assertThat(store.get(id).completedBlocks()).isEqualTo(1);
        assertThat(driver.targets).containsExactly(
                new NavCell("minecraft:overworld", 10, 64, 20),
                new NavCell("minecraft:overworld", 11, 64, 20));
    }

    @Test
    void advancingBreakClearsBothHeightsBeforeTheNextTunnelColumn() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 12, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 2, "dy", 1,
                "advance", true), "minecraft:overworld");
        var driver = new FakeDriver();
        for (int i = 0; i < 6; i++) {
            driver.begins.add(V2BlockJobExecution.BeginResult.STARTED);
            driver.steps.add(V2BlockJobExecution.StepResult.CONFIRMED);
        }
        var job = new V2BlockJobExecution<>(store, id, SESSION,
                AgentJobStore.Kind.BREAK_BLOCK, request, driver, () -> true);
        store.confirm(id, 1);
        for (int tick = 1; tick <= 6; tick++) {
            job.tick(SESSION, tick, tick, true, () -> true);
        }
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(driver.targets).containsExactly(
                new NavCell("minecraft:overworld", 10, 64, 20),
                new NavCell("minecraft:overworld", 10, 65, 20),
                new NavCell("minecraft:overworld", 11, 64, 20),
                new NavCell("minecraft:overworld", 11, 65, 20),
                new NavCell("minecraft:overworld", 12, 64, 20),
                new NavCell("minecraft:overworld", 12, 65, 20));
        assertThat(store.get(id).completedBlocks()).isEqualTo(6);
    }

    @Test
    void placeUsesTheSameDeliveryAndReleaseGateWithSeparateProgress() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.PLACE_BLOCK, SESSION, 10, 100);
        var request = V2PlaceArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 1,
                "block", "minecraft:cobblestone"), "minecraft:overworld");
        var targets = new ArrayList<NavCell>();
        var driver = new V2BlockJobExecution.Driver<V2PlaceArguments>() {
            @Override public String dimension() { return "minecraft:overworld"; }
            @Override public V2BlockJobExecution.BeginResult begin(NavCell target,
                    V2PlaceArguments args, BooleanSupplier outputAllowed) {
                assertThat(outputAllowed.getAsBoolean()).isTrue();
                assertThat(args.region().contains(target)).isTrue();
                targets.add(target);
                return V2BlockJobExecution.BeginResult.STARTED;
            }
            @Override public V2BlockJobExecution.StepResult tick(long clientTick,
                    BooleanSupplier outputAllowed) {
                return V2BlockJobExecution.StepResult.CONFIRMED;
            }
            @Override public void close() { }
        };
        var released = new AtomicBoolean(false);
        var job = new V2BlockJobExecution<>(store, id, SESSION,
                AgentJobStore.Kind.PLACE_BLOCK, request, driver, released::get);
        assertThat(job.tick(SESSION, 1, 1, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.UNCONFIRMED);
        store.confirm(id, 2);
        job.tick(SESSION, 2, 2, true, () -> true);
        assertThat(job.tick(SESSION, 3, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(store.get(id).scannedCells()).isEqualTo(2);
        assertThat(store.get(id).completedBlocks()).isEqualTo(2);
        released.set(true);
        assertThat(job.tick(SESSION, 4, 4, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(targets).containsExactly(
                new NavCell("minecraft:overworld", 10, 64, 20),
                new NavCell("minecraft:overworld", 11, 64, 20));
    }

    private static final class FakeDriver
            implements V2BlockJobExecution.Driver<V2BreakArguments> {
        final ArrayDeque<V2BlockJobExecution.BeginResult> begins = new ArrayDeque<>();
        final ArrayDeque<V2BlockJobExecution.StepResult> steps = new ArrayDeque<>();
        final List<NavCell> targets = new ArrayList<>();
        int ticks;

        @Override public String dimension() { return "minecraft:overworld"; }

        @Override public V2BlockJobExecution.BeginResult begin(NavCell target,
                V2BreakArguments request, BooleanSupplier outputAllowed) {
            assertThat(outputAllowed.getAsBoolean()).isTrue();
            assertThat(request.region().contains(target)).isTrue();
            targets.add(target);
            return begins.removeFirst();
        }

        @Override public V2BlockJobExecution.StepResult tick(long clientTick,
                BooleanSupplier outputAllowed) {
            assertThat(outputAllowed.getAsBoolean()).isTrue();
            ticks++;
            return steps.removeFirst();
        }

        @Override public void close() { }
    }
}
