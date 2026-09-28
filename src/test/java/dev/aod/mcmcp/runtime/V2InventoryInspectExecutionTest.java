package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;

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

    @Test
    void readbackWaitsForDeliveryAndRetainsResultAfterRelease() {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        var calls = new AtomicInteger();
        var releaseAttempts = new AtomicInteger();
        var execution = new V2InventoryJobExecution(jobs, id, session,
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

    @Test
    void cancellationAndUnsafeWorldNeverReadInventory() {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        var calls = new AtomicInteger();
        var execution = new V2InventoryJobExecution(jobs, id, session,
                new StubDriver(calls, Map.of()), () -> true);
        jobs.confirm(id, 2);
        assertThat(execution.tick(UUID.randomUUID(), 3, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.FAILED);
        assertThat(calls).hasValue(0);

        var second = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        var cancelled = new V2InventoryJobExecution(jobs, second, session,
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
        var execution = new V2InventoryJobExecution(jobs, id, session,
                new V2InventoryJobExecution.Driver() {
                    @Override
                    public void begin(long clientTick, BooleanSupplier allowed) { }

                    @Override
                    public V2InventoryJobExecution.Step tick(
                            long clientTick, BooleanSupplier allowed) {
                        return V2InventoryJobExecution.Step.RUNNING;
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

    private static final class StubDriver implements V2InventoryJobExecution.Driver {
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
        public V2InventoryJobExecution.Step tick(
                long clientTick, BooleanSupplier outputAllowed) {
            calls.incrementAndGet();
            result = captured;
            return V2InventoryJobExecution.Step.CONFIRMED;
        }

        @Override
        public Map<String, Object> result() { return result; }

        @Override
        public void close() { }
    }
}
