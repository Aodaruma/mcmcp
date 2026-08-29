package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.routine.RoutineManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimePublicErrorContractTest {
    @Test
    void onlyActiveWorkConflictsUseTaskBusy() {
        assertFailure(new ClientCommandInbox.CommandTimeoutException("read"), "server_busy");
        assertFailure(new RejectedExecutionException("full"), "server_busy");
        assertFailure(new AgentActionStore.BusyException(), "task_busy");

        UUID activeRoutineId = UUID.randomUUID();
        var routineBusy = McmcpRuntime.mapFailure(
                new RoutineManager.RoutineBusyException(activeRoutineId));
        assertThat(routineBusy.failure().code()).isEqualTo("task_busy");
        assertThat(routineBusy.failure().retryable()).isTrue();
        assertThat(routineBusy.failure().details())
                .containsEntry("active_routine_id", activeRoutineId.toString());
    }

    @Test
    void budgetDiagnosticKeepsTheBoundedNonReflectiveComponentMessage() {
        String message = "Worst-case cost exceeds effective budget components: "
                + "budget.max_ticks, budget.max_camera_degrees";
        var reply = McmcpRuntime.mapFailure(new ActionDslException(
                ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE, message));

        assertThat(reply.failure().code()).isEqualTo("program_budget_unprovable");
        assertThat(reply.failure().message()).isEqualTo(message);
        assertThat(reply.failure().retryable()).isTrue();
        assertThat(reply.failure().message()).doesNotContain("submitted", "node", "target");
    }

    private static void assertFailure(Throwable cause, String code) {
        var reply = McmcpRuntime.mapFailure(cause);
        assertThat(reply.failure().code()).isEqualTo(code);
        assertThat(reply.failure().retryable()).isTrue();
    }
}
