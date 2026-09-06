package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.routine.RoutineManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimePublicErrorContractTest {
    @Test
    void rendererWaitTimeoutHasAFixedDiagnosisButUnrelatedFailuresAreNotReclassified() {
        var recovery = new SurfacePreflightRecovery(new dev.aod.mcmcp.agent.dsl.ActionDsl.Budget(
                1000, 20, 0, 0, 0, 0, 0));
        var timeout = new ClientCommandInbox.CommandTimeoutException("untrusted secret");
        assertThat(McmcpRuntime.mapAdmissionFailure(timeout, recovery).failure().code()).isEqualTo("server_busy");
        recovery.evaluate(dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore.SurfaceLeaseStatus.VALID,
                10, 0, false);
        var reply = McmcpRuntime.mapAdmissionFailure(timeout, recovery);
        assertThat(reply.failure().details()).containsEntry("admission_reason", "renderer_evidence_timeout");
        assertThat(reply.failure().message()).doesNotContain("untrusted", "secret");
        assertThat(McmcpRuntime.mapAdmissionFailure(new RejectedExecutionException("full"), recovery)
                .failure().code()).isEqualTo("server_busy");
        assertThat(McmcpRuntime.mapAdmissionFailure(
                new ClientCommandInbox.CommandInvalidatedException("untrusted secret"), recovery)
                .failure().message()).doesNotContain("renderer", "untrusted", "secret");
    }

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

    @Test
    void admissionDiagnosticsSurvivePublicMappingWithoutChangingTheSafetyErrorContract() {
        var messages = new HashSet<String>();
        var executionEvidence = new HashSet<String>();
        for (var reason : McmcpRuntime.AdmissionFenceFailure.values()) {
            var reply = McmcpRuntime.mapFailure(McmcpRuntime.admissionPreflightFailure(reason));

            assertThat(reply.failure().code()).isEqualTo("unsafe_state");
            assertThat(reply.failure().retryable()).isTrue();
            assertThat(reply.failure().message())
                    .startsWith("The world, local control, pose, observation, or policy changed during preflight.")
                    .endsWith("Reason: " + reason.code() + ".")
                    .hasSizeLessThan(512);
            assertThat(reply.failure().details()).containsOnlyKeys("admission_reason")
                    .containsEntry("admission_reason", reason.code());
            assertThat(reason.code()).matches("[a-z_]+");
            assertThat(reason.executionEvidence()).matches("admission_[a-z_]+_before_execution")
                    .hasSizeLessThan(128);
            assertThat(messages.add(reply.failure().message())).isTrue();
            assertThat(executionEvidence.add(reason.executionEvidence())).isTrue();
        }
    }

    private static void assertFailure(Throwable cause, String code) {
        var reply = McmcpRuntime.mapFailure(cause);
        assertThat(reply.failure().code()).isEqualTo(code);
        assertThat(reply.failure().retryable()).isTrue();
    }
}
