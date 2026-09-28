package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
        var execution = new V2InventoryInspectExecution(jobs, id, session,
                () -> { calls.incrementAndGet(); return Map.of("slots", 2); },
                () -> releaseAttempts.incrementAndGet() >= 2);
        assertThat(execution.tick(session, 1, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.UNCONFIRMED);
        assertThat(calls).hasValue(0);
        jobs.confirm(id, 2);
        assertThat(execution.tick(session, 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(calls).hasValue(1);
        assertThat(jobs.get(id).result()).containsEntry("slots", 2);
        assertThat(execution.tick(session, 4, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancellationAndUnsafeWorldNeverReadInventory() {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        var calls = new AtomicInteger();
        var execution = new V2InventoryInspectExecution(jobs, id, session,
                () -> { calls.incrementAndGet(); return Map.of(); }, () -> true);
        jobs.confirm(id, 2);
        assertThat(execution.tick(UUID.randomUUID(), 3, true, () -> true).state())
                .isEqualTo(AgentJobStore.State.FAILED);
        assertThat(calls).hasValue(0);

        var second = jobs.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        var cancelled = new V2InventoryInspectExecution(jobs, second, session,
                () -> { calls.incrementAndGet(); return Map.of(); }, () -> true);
        jobs.confirm(second, 2);
        assertThat(cancelled.cancel().state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(calls).hasValue(0);
    }
}
