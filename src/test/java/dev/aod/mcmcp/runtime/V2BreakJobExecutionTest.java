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

class V2BreakJobExecutionTest {
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void walksOnlyRequestedCellsAndPublishesAfterRelease() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 10, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 2), "minecraft:overworld");
        var driver = new FakeDriver();
        driver.begins.addAll(List.of(
                V2BreakJobExecution.BeginResult.SKIPPED,
                V2BreakJobExecution.BeginResult.STARTED,
                V2BreakJobExecution.BeginResult.STARTED));
        driver.steps.addAll(List.of(
                V2BreakJobExecution.StepResult.CONFIRMED,
                V2BreakJobExecution.StepResult.CONFIRMED));
        var released = new AtomicBoolean(false);
        var job = new V2BreakJobExecution(store, id, SESSION, request, driver, released::get);
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
        assertThat(store.get(id).brokenBlocks()).isEqualTo(2);
        store.reserve(AgentJobStore.Kind.MOVE, SESSION, 10, 100);
        assertThat(store.get(id).brokenBlocks()).isEqualTo(2);
    }

    @Test
    void cancelReleasesBeforeTerminalAndNeverBeginsAnotherTarget() {
        var store = new AgentJobStore();
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, SESSION, 10, 100);
        var request = V2BreakArguments.parse(Map.of(
                "x", 10, "y", 64, "z", 20, "dx", 1), "minecraft:overworld");
        var driver = new FakeDriver();
        driver.begins.add(V2BreakJobExecution.BeginResult.STARTED);
        driver.steps.add(V2BreakJobExecution.StepResult.RUNNING);
        var released = new AtomicBoolean(false);
        var job = new V2BreakJobExecution(store, id, SESSION, request, driver, released::get);
        store.confirm(id, 1);
        job.tick(SESSION, 1, 1, true, () -> true);
        assertThat(job.cancel().state()).isEqualTo(AgentJobStore.State.RUNNING);
        released.set(true);
        assertThat(job.tick(SESSION, 2, 2, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(driver.targets).hasSize(1);
        assertThat(driver.ticks).isEqualTo(1);
    }

    private static final class FakeDriver implements V2BreakJobExecution.Driver {
        final ArrayDeque<V2BreakJobExecution.BeginResult> begins = new ArrayDeque<>();
        final ArrayDeque<V2BreakJobExecution.StepResult> steps = new ArrayDeque<>();
        final List<NavCell> targets = new ArrayList<>();
        int ticks;

        @Override public String dimension() { return "minecraft:overworld"; }

        @Override public V2BreakJobExecution.BeginResult begin(NavCell target,
                V2BreakArguments request, BooleanSupplier outputAllowed) {
            assertThat(outputAllowed.getAsBoolean()).isTrue();
            assertThat(request.region().contains(target)).isTrue();
            targets.add(target);
            return begins.removeFirst();
        }

        @Override public V2BreakJobExecution.StepResult tick(long clientTick,
                BooleanSupplier outputAllowed) {
            assertThat(outputAllowed.getAsBoolean()).isTrue();
            ticks++;
            return steps.removeFirst();
        }

        @Override public void close() { }
    }
}
