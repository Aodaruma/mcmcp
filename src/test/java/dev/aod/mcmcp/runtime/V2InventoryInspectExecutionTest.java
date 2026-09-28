package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2InventoryInspectExecutionTest {
    @Test
    void inspectionFiltersModItemsWithoutLosingSlotIdentity() {
        var request = V2InventoryInspectArguments.parse(Map.of(
                "operation", "inspect", "item", "example:backpack"));
        var stacks = new ArrayList<StackFingerprint>();
        for (int slot = 0; slot < 41; slot++) stacks.add(StackFingerprint.EMPTY);
        stacks.set(2, new StackFingerprint("example:backpack", 1, 17));
        stacks.set(18, new StackFingerprint("minecraft:stone", 20, 18));
        stacks.set(40, new StackFingerprint("example:backpack", 2, 17));
        var result = V2InventoryReadback.capture(stacks, 2, request);
        assertThat(result).containsEntry("slot_count", 41)
                .containsEntry("empty_slots", 38)
                .containsEntry("matching_count", 3)
                .containsEntry("selected_slot", 2);
        assertThat(result.get("slots")).isEqualTo(List.of(
                Map.of("slot", 2, "item", "example:backpack", "count", 1),
                Map.of("slot", 40, "item", "example:backpack", "count", 2)));
        assertThatThrownBy(() -> V2InventoryInspectArguments.parse(Map.of(
                "operation", "transfer"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = AgentJobStore.Kind.class, names = {"INVENTORY", "INTERACT"})
    void readbackWaitsForDeliveryAndRetainsResultAfterRelease(AgentJobStore.Kind kind) {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(kind, session, 1, 100);
        var calls = new AtomicInteger();
        var releaseAttempts = new AtomicInteger();
        var execution = new V2OperationJobExecution(jobs, id, session,
                new StubDriver(calls, Map.of("slots", 2)),
                () -> releaseAttempts.incrementAndGet() >= 2);
        assertThat(execution.tick(session, 1, 1, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.UNCONFIRMED);
        assertThat(calls).hasValue(0);
        jobs.confirm(id, 2);
        assertThat(execution.tick(session, 3, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(calls).hasValue(1);
        assertThat(jobs.get(id).result()).containsEntry("slots", 2);
        assertThat(execution.tick(session, 4, 4, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = AgentJobStore.Kind.class, names = {"INVENTORY", "INTERACT"})
    void cancellationAndUnsafeWorldNeverReadInventory(AgentJobStore.Kind kind) {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(kind, session, 1, 100);
        var calls = new AtomicInteger();
        var execution = new V2OperationJobExecution(jobs, id, session,
                new StubDriver(calls, Map.of()), () -> true);
        jobs.confirm(id, 2);
        assertThat(execution.tick(UUID.randomUUID(), 3, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.FAILED);
        assertThat(calls).hasValue(0);

        var second = jobs.reserve(kind, session, 1, 100);
        var cancelled = new V2OperationJobExecution(jobs, second, session,
                new StubDriver(calls, Map.of()), () -> true);
        jobs.confirm(second, 2);
        assertThat(cancelled.cancel().state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void cancellationKeepsConfirmedAndUnconfirmedDropCounts() {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 12, 100);
        var execution = new V2OperationJobExecution(jobs, id, session,
                new V2OperationJobExecution.Driver() {
                    @Override
                    public void begin(long clientTick, BooleanSupplier allowed) { }

                    @Override
                    public V2OperationJobExecution.Step tick(
                            long clientTick, BooleanSupplier allowed) {
                        return V2OperationJobExecution.Step.RUNNING;
                    }

                    @Override
                    public Map<String, Object> result() {
                        return Map.of("confirmed_count", 2, "unconfirmed_count", 1);
                    }

                    @Override
                    public void close() { }
                }, () -> true);
        jobs.confirm(id, 2);
        assertThat(execution.tick(session, 1, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        var cancelled = execution.cancel();
        assertThat(cancelled.state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(cancelled.result()).containsEntry("confirmed_count", 2)
                .containsEntry("unconfirmed_count", 1);
    }

    @ParameterizedTest
    @EnumSource(value = AgentJobStore.Kind.class, names = {"INVENTORY", "INTERACT"})
    void cancellationCapturesCleanupEffectsEvenWhenMenuReleaseNeedsAnotherTick(AgentJobStore.Kind kind) {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(kind, session, 100, 100);
        var closes = new AtomicInteger();
        var ticks = new AtomicInteger();
        var released = new AtomicInteger();
        var execution = new V2OperationJobExecution(jobs, id, session,
                new V2OperationJobExecution.Driver() {
                    @Override
                    public void begin(long clientTick, BooleanSupplier allowed) { }

                    @Override
                    public V2OperationJobExecution.Step tick(long clientTick, BooleanSupplier allowed) {
                        ticks.incrementAndGet();
                        return V2OperationJobExecution.Step.RUNNING;
                    }

                    @Override
                    public Map<String, Object> result() {
                        return closes.get() == 0 ? Map.of("confirmed_count", 0)
                                : Map.of("confirmed_count", 7, "unconfirmed", true);
                    }

                    @Override
                    public boolean allowsScreenChange() { return true; }

                    @Override
                    public void close() {
                        if (closes.incrementAndGet() == 1) throw new IllegalStateException("release pending");
                    }
                }, () -> { released.incrementAndGet(); return true; });
        assertThat(execution.allowsScreenChange()).isFalse();
        jobs.confirm(id, 2);
        execution.tick(session, 1, 3, true, () -> true);
        assertThat(execution.allowsScreenChange()).isTrue();
        var pending = execution.cancel();
        assertThat(pending.state()).isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(pending.result()).containsEntry("confirmed_count", 7).containsEntry("unconfirmed", true);
        assertThat(released).hasValue(0);
        var terminal = execution.tick(session, 2, 4, false, () -> false);
        assertThat(terminal.state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(terminal.result()).containsEntry("confirmed_count", 7).containsEntry("unconfirmed", true);
        assertThat(ticks).hasValue(1);
        assertThat(released).hasValue(1);
    }

    private static final class StubDriver implements V2OperationJobExecution.Driver {
        private final AtomicInteger calls;
        private final Map<String, Object> captured;
        private Map<String, Object> result = Map.of();

        private StubDriver(AtomicInteger calls, Map<String, Object> captured) {
            this.calls = calls;
            this.captured = captured;
        }

        @Override
        public void begin(long clientTick, BooleanSupplier outputAllowed) { }

        @Override
        public V2OperationJobExecution.Step tick(
                long clientTick, BooleanSupplier outputAllowed) {
            calls.incrementAndGet();
            result = captured;
            return V2OperationJobExecution.Step.CONFIRMED;
        }

        @Override
        public Map<String, Object> result() { return result; }

        @Override
        public void close() { }
    }
}
