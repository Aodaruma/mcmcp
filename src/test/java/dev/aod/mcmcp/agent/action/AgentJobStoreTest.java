package dev.aod.mcmcp.agent.action;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static dev.aod.mcmcp.agent.action.AgentJobStore.Kind.MOVE;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.CANCELLED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.FAILED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.QUEUED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.RUNNING;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.SUCCEEDED;
import static dev.aod.mcmcp.agent.action.AgentJobStore.State.UNCONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentJobStoreTest {
    @Test
    void cannotStartBeforeDeliveryAndAbandonedJobNeverDispatches() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(MOVE, session, 2, 100);
        assertThat(store.get(id).state()).isEqualTo(UNCONFIRMED);
        assertThatThrownBy(() -> store.start(id, session)).isInstanceOf(IllegalStateException.class);
        assertThat(store.canDispatch(id, session)).isFalse();
        assertThat(store.abandon(id)).isTrue();
        assertThat(store.get(id).state()).isEqualTo(FAILED);
        assertThat(store.confirm(id, 1)).isEqualTo(AgentJobStore.Confirmation.STALE);
        assertThatThrownBy(() -> store.start(id, session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiryReleasesAdmissionAndSessionMismatchPreventsDispatch() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var expired = store.reserve(MOVE, session, 1, 100);
        assertThat(store.expireUnconfirmed(99)).isFalse();
        assertThat(store.expireUnconfirmed(100)).isTrue();
        assertThat(store.get(expired).state()).isEqualTo(FAILED);
        var id = store.reserve(MOVE, session, 1, 200);
        assertThat(store.confirm(id, 150)).isEqualTo(AgentJobStore.Confirmation.CONFIRMED);
        assertThat(store.get(id).state()).isEqualTo(QUEUED);
        assertThatThrownBy(() -> store.start(id, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        store.start(id, session);
        assertThat(store.canDispatch(id, UUID.randomUUID())).isFalse();
        assertThat(store.canDispatch(id, session)).isTrue();
    }

    @Test
    void cancellationRequiresReleaseAndStillCountsConfirmedPartialEffect() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = store.reserve(MOVE, session, 1, 100);
        store.confirm(id, 1);
        store.start(id, session);
        assertThat(store.get(id).state()).isEqualTo(RUNNING);
        assertThat(store.requestCancel(id)).isTrue();
        assertThat(store.canDispatch(id, session)).isFalse();
        store.recordOperation(id); // Server ACK can race with cancellation.
        assertThatThrownBy(() -> store.finish(id, CANCELLED, "client_request", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.finish(id, SUCCEEDED, null, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
        store.finish(id, CANCELLED, "client_request", true);
        assertThat(store.get(id).state()).isEqualTo(CANCELLED);
        assertThat(store.get(id).completedOperations()).isEqualTo(1);
        assertThat(store.requestCancel(id)).isFalse();
    }

    @Test
    void boundedOperationsAndOneTerminalRetention() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var first = store.reserve(MOVE, session, 1, 100);
        assertThatThrownBy(() -> store.reserve(MOVE, session, 1, 100))
                .isInstanceOf(IllegalStateException.class);
        store.confirm(first, 1);
        assertThatThrownBy(() -> store.finish(first, SUCCEEDED, null, true))
                .isInstanceOf(IllegalStateException.class);
        store.start(first, session);
        store.recordOperation(first);
        assertThat(store.canDispatch(first, session)).isFalse();
        assertThatThrownBy(() -> store.recordOperation(first)).isInstanceOf(IllegalStateException.class);
        store.finish(first, SUCCEEDED, null, true);
        var second = store.reserve(MOVE, session, 1, 100);
        assertThat(store.get(first).state()).isEqualTo(SUCCEEDED);
        store.confirm(second, 1);
        store.start(second, session);
        store.finish(second, FAILED, "blocked", true);
        store.reserve(MOVE, session, 1, 100);
        assertThatThrownBy(() -> store.get(first)).isInstanceOf(AgentJobStore.NotFoundException.class);
        assertThat(store.get(second).failure()).isEqualTo("blocked");
    }

    @Test
    void retainsImmutableResultAfterTheNextJobStarts() {
        var store = new AgentJobStore();
        var session = UUID.randomUUID();
        var first = store.reserve(AgentJobStore.Kind.INVENTORY, session, 1, 100);
        assertThatThrownBy(() -> store.recordResult(first, Map.of("count", 2)))
                .isInstanceOf(IllegalArgumentException.class);
        store.confirm(first, 1);
        store.start(first, session);
        store.recordResult(first, Map.of("count", 2));
        store.recordOperation(first);
        store.finish(first, SUCCEEDED, null, true);
        store.reserve(MOVE, session, 1, 100);
        assertThat(store.get(first).result()).containsEntry("count", 2);
        assertThatThrownBy(() -> store.get(first).result().put("count", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
