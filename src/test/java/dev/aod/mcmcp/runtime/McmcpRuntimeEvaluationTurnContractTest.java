package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.mcp.EvaluationTurnControl;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McmcpRuntimeEvaluationTurnContractTest {
    private static final String RUNTIME =
            "/dev/aod/mcmcp/runtime/McmcpRuntime.class";

    @Test
    void runtimeImplementsTheUnadvertisedEvaluationControlBoundary() throws Exception {
        var runtime = classNode();

        assertThat(runtime.interfaces)
                .contains("dev/aod/mcmcp/mcp/EvaluationTurnControl");
        assertThat(invocations(method(runtime, "acquire")))
                .containsSubsequence(
                        "java/lang/ProcessHandle#of",
                        "java/lang/ProcessHandle#info",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#submitControl");
        assertThat(invocations(method(runtime, "await")))
                .contains("dev/aod/mcmcp/safety/EvaluationTurnGuard#awaitTerminal");
    }

    @Test
    void acquisitionReleasesInputsBeforeTheExactGuardLeaseAndMonitorsProcessExit()
            throws Exception {
        var runtime = classNode();
        var dispatch = invocations(method(runtime, "acquireEvaluationTurnOnClient"));
        var acquisition = invocations(method(runtime, "acquireEvaluationTurnWithGateHeld"));

        assertThat(dispatch)
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#withEvaluationTurnGate");
        assertThat(acquisition)
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#automationActivityPending",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#boundedActionInputRelease",
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#tryAcquire",
                        "java/lang/ProcessHandle#onExit");
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith(
                        "lambda$acquireEvaluationTurnWithGateHeld"))
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#releaseAllAndConfirmNoInputOwner");
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith("lambda$acquire$"))
                .flatMap(method -> fieldAccesses(method).stream()))
                .contains("dev/aod/mcmcp/mcp/EvaluationTurnControl$ReleaseReason#ACQUIRE_ABANDONED");
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith("lambda$acquire$"))
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#requestEvaluationReleaseFromAnyThread");
    }

    @Test
    void everyEvaluationTerminalRunsPriorityStopBeforeGuardPublication()
            throws Exception {
        var terminal = invocations(method(classNode(),
                "terminateEvaluationLeaseOnClient"));

        assertThat(terminal)
                .contains(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#requestEmergencyStop",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#requestLocalEmergencyStop",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#requestLocalDisable",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick",
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#release",
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#revoke");
        int drain = terminal.indexOf(
                "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick");
        assertThat(drain).isLessThan(terminal.indexOf(
                "dev/aod/mcmcp/safety/EvaluationTurnGuard#release"));
        assertThat(drain).isLessThan(terminal.indexOf(
                "dev/aod/mcmcp/safety/EvaluationTurnGuard#revoke"));
        assertThat(terminal)
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#evaluationTerminalReasonIfSafe",
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#release");
    }

    @Test
    void unconfirmedCleanupCannotPublishAndLaterSuccessKeepsTheFirstReason() {
        var first = EvaluationTurnControl.ReleaseReason.LOCAL_ESCAPE;

        assertThat(McmcpRuntime.evaluationTerminalReasonIfSafe(first, false, false, false))
                .isEmpty();
        assertThat(McmcpRuntime.evaluationTerminalReasonIfSafe(first, true, false, true))
                .isEmpty();
        assertThat(McmcpRuntime.evaluationTerminalReasonIfSafe(first, true, true, false))
                .isEmpty();
        assertThat(McmcpRuntime.evaluationTerminalReasonIfSafe(first, true, true, true))
                .contains(first);
    }

    @Test
    void terminalReceiptRequiresAMeasuredOwnerNoneAfterEmergencyCleanup()
            throws Exception {
        var runtime = classNode();
        assertThat(invocations(method(runtime, "terminateEvaluationLeaseOnClient")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox$StopReceipt#inputsReleased",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox$StopReceipt#inputOwnerNone",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#evaluationActionsTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#evaluationTerminalReasonIfSafe");
        assertThat(invocations(method(runtime, "releaseAllAndConfirmNoInputOwner")))
                .containsSubsequence(
                        "dev/aod/mcmcp/safety/InputReleaseController#releaseAll",
                        "dev/aod/mcmcp/safety/InputReleaseController#inputOwnerNone");
    }

    @Test
    void evaluationTerminalRetainsTheStopFutureWithoutBlockingTheClientThread()
            throws Exception {
        var runtime = classNode();
        var terminal = invocations(method(runtime, "terminateEvaluationLeaseOnClient"));
        var pending = classNode(
                "/dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal.class");

        assertThat(terminal)
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal#stopCompletion",
                        "dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal#retainStopCompletion",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick",
                        "dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal#stopOutcome")
                .doesNotContain(
                        "java/util/concurrent/CompletableFuture#join",
                        "java/util/concurrent/Future#get");
        assertThat(fieldAccesses(pending.methods.stream()
                .filter(candidate -> candidate.name.equals("retainStopCompletion"))
                .findFirst().orElseThrow()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal#stopCompletion");
        assertThat(invocations(pending.methods.stream()
                .filter(candidate -> candidate.name.equals("retainStopCompletion"))
                .findFirst().orElseThrow()))
                .contains("java/util/concurrent/CompletableFuture#whenComplete");
    }

    @Test
    void runtimeEmergencyStopFlattensItsFutureWithoutWaitingOnTheClientThread()
            throws Exception {
        var runtime = classNode();
        var emergencyLambdas = runtime.methods.stream()
                .filter(candidate -> candidate.name.startsWith("lambda$submit$"))
                .filter(candidate -> invocations(candidate).contains(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#requestEmergencyStop"))
                .toList();

        assertThat(emergencyLambdas).hasSize(1);
        assertThat(invocations(emergencyLambdas.getFirst()))
                .contains(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick",
                        "java/util/concurrent/CompletableFuture#handle")
                .doesNotContain(
                        "java/util/concurrent/CompletableFuture#join",
                        "java/util/concurrent/Future#get");
        assertThat(invocations(method(runtime, "submit")))
                .contains("java/util/concurrent/CompletableFuture#thenCompose");
    }

    @Test
    void localAndLifecycleStopsRevokeTheEvaluationGuardInline() throws Exception {
        var runtime = classNode();
        String terminate =
                "dev/aod/mcmcp/runtime/McmcpRuntime#terminateActiveEvaluationOnClient";

        assertThat(invocations(method(runtime, "emergencyStopFromLocalKey")))
                .contains(terminate);
        assertThat(invocations(method(runtime, "disableAutomationFromUi")))
                .contains(terminate);
        for (String lifecycle : List.of(
                "onLoggingIn", "onLevelUnload", "onLoggingOut", "onPlayerClone", "shutdown")) {
            assertThat(invocations(method(runtime, lifecycle)))
                    .startsWith(
                            "dev/aod/mcmcp/runtime/McmcpRuntime#assertClientThread",
                            terminate);
        }
    }

    @Test
    void preTickSettlesPendingAndInvalidLeasesBeforeGameplayCanTick() throws Exception {
        var preTick = invocations(method(classNode(), "onPreTick"));
        assertThat(preTick)
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#finishPendingEvaluationTerminalOnClient",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#terminateInvalidEvaluationLeaseOnClient",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#retryPendingAgentInputRelease",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#tickActiveRoutine",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#tickAgentAction");
        assertThat(preTick)
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#localControlAvailable",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#terminateActiveEvaluationOnClient");
    }

    @Test
    void terminalSuccessCannotBeOverwrittenByLateControlQueueInvalidation()
            throws Exception {
        var runtime = classNode();

        assertThat(invocations(method(runtime, "onPreTick")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#finishPendingEvaluationTerminalOnClient",
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainControlsPreTick");
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith("lambda$release"))
                .flatMap(method -> invocations(method).stream()))
                .doesNotContain("java/util/concurrent/CompletableFuture#completeExceptionally");
    }

    @Test
    void firstTerminalIntentIsImmutableAcrossRemoteLocalAndLifecycleRaces()
            throws Exception {
        var runtime = classNode();
        var pending = classNode(
                "/dev/aod/mcmcp/runtime/McmcpRuntime$PendingEvaluationTerminal.class");
        var reason = pending.fields.stream()
                .filter(field -> field.name.equals("reason"))
                .findFirst()
                .orElseThrow();

        assertThat(reason.access & Opcodes.ACC_FINAL).isNotZero();
        assertThat(invocations(method(runtime, "release")))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#claimEvaluationTerminal");
        assertThat(invocations(method(runtime, "terminateActiveEvaluationOnClient")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/McmcpRuntime#claimEvaluationTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#terminateEvaluationLeaseOnClient");
        assertThat(invocations(method(runtime, "emergencyStopFromLocalKey")))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#terminateActiveEvaluationOnClient");
        assertThat(invocations(method(runtime, "disableAutomationFromUi")))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#terminateActiveEvaluationOnClient");
    }

    @Test
    void endpointFaultHasAnInternalOnlyTerminalReasonAndRevocationPath()
            throws Exception {
        assertThat(EvaluationTurnControl.ReleaseReason.ENDPOINT_FAULT.wireName())
                .isEqualTo("endpoint_fault");
        assertThatThrownBy(() -> EvaluationTurnControl.ReleaseReason.runnerValue(
                "endpoint_fault"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(invocations(method(classNode(), "reportEndpointFault")))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#requestEmergencyStop",
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#snapshot");
        assertThat(classNode().methods.stream()
                .filter(method -> method.name.startsWith("lambda$reportEndpointFault"))
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#requestEvaluationReleaseFromAnyThread");
    }

    @Test
    void uiSnapshotIsTheSinglePhysicalIsolationAuthority() throws Exception {
        var runtime = classNode();
        assertThat(invocations(method(runtime, "automationUiSnapshot")))
                .containsSubsequence(
                        "dev/aod/mcmcp/safety/EvaluationTurnGuard#snapshot",
                        "dev/aod/mcmcp/runtime/AutomationUiSnapshot#resolve");
        assertThat(runtime.methods).noneMatch(method ->
                method.name.equals("inputIsolationActive"));
        var isolation = classNode(
                "/dev/aod/mcmcp/client/InputIsolationController.class");
        assertThat(isolation.methods.stream()
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#automationUiSnapshot")
                .doesNotContain("dev/aod/mcmcp/runtime/McmcpRuntime#inputIsolationActive");
    }

    @Test
    void everyQueuedWorkCommitRevalidatesItsHttpLeaseFence() throws Exception {
        var runtime = classNode();
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith("lambda$submit$"))
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#withEvaluationLeaseFence");
        assertThat(runtime.methods.stream()
                .filter(method -> method.name.startsWith("lambda$submitPreparedAgentStart"))
                .flatMap(method -> invocations(method).stream()))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#withEvaluationLeaseFence");
        assertThat(invocations(method(runtime, "requireLiveCall")))
                .contains("dev/aod/mcmcp/mcp/RuntimeCallContext#evaluationLeaseCurrent");
        assertThat(invocations(method(runtime, "withEvaluationLeaseFence")))
                .contains("dev/aod/mcmcp/runtime/McmcpRuntime#withEvaluationTurnGate");
    }

    @Test
    void absentCommitAndLeaseAdmissionAreSerializedAndAdmissionRechecksAfterWaiting()
            throws Exception {
        var gate = new Object();
        var automationPending = new java.util.concurrent.atomic.AtomicBoolean();
        var absentCommitEntered = new java.util.concurrent.CountDownLatch(1);
        var finishAbsentCommit = new java.util.concurrent.CountDownLatch(1);
        var admissionAttempting = new java.util.concurrent.CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var absentCommit = executor.submit(() -> McmcpRuntime.withEvaluationTurnGate(
                    gate,
                    () -> {
                        absentCommitEntered.countDown();
                        try {
                            if (!finishAbsentCommit.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("test commit did not resume");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        automationPending.set(true);
                        return null;
                    }));
            assertThat(absentCommitEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var admission = executor.submit(() -> {
                admissionAttempting.countDown();
                return McmcpRuntime.withEvaluationTurnGate(
                        gate, () -> !automationPending.get());
            });
            assertThat(admissionAttempting.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(admission).isNotDone();

            finishAbsentCommit.countDown();
            absentCommit.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(admission.get(1, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void terminalWaitRevalidatesItsLeaseFenceBeforeReturningTheSnapshot()
            throws Exception {
        assertThat(invocations(method(classNode(), "submitAgentGetAction")))
                .containsSubsequence(
                        "dev/aod/mcmcp/agent/action/AgentActionStore#awaitTerminal",
                        "dev/aod/mcmcp/runtime/McmcpRuntime#withEvaluationLeaseFence");
    }

    private static ClassNode classNode() throws Exception {
        return classNode(RUNTIME);
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = McmcpRuntimeEvaluationTurnContractTest.class
                .getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }

    private static List<String> fieldAccesses(MethodNode method) {
        var fields = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                fields.add(field.owner + "#" + field.name);
            }
        }
        return List.copyOf(fields);
    }
}
