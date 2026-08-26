package dev.aod.mcmcp.routine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Parent-only interpreter for the bounded finite-plan IR. */
final class FinitePlanRoutine implements ManagedRoutine {
    static final String KIND = "execute_plan";

    private final UUID routineId;
    private final FinitePlanRequest request;
    private final FinitePlanPort port;
    private final RoutineEventRing events;
    private final long hardDeadlineClientTick;
    private final Deque<ExecutionFrame> stack = new ArrayDeque<>();

    private RoutineState state = RoutineState.QUEUED;
    private String phase = "queued";
    private RoutineFailure failure;
    private RoutineFailure finalizationFailure;
    private boolean goalVerified;
    private boolean finalizationCompleted;
    private boolean retired;
    private long lastClientTick;
    private long lastObservationRevision;
    private long checkpointSeq;
    private int completedSteps;
    private int executedSteps;
    private int actionAttempts;
    private FinitePlanRequest.Action activeAction;
    private FinitePlanPort.ActionAttempt actionAttempt;
    private FinitePlanRequest.WaitUntil activeWait;
    private long waitDeadlineClientTick;
    private String waitReason;
    private String wakeCondition;
    private Map<String, Object> lastEvidenceBasis = Map.of();

    FinitePlanRoutine(
            UUID routineId,
            FinitePlanRequest request,
            FinitePlanPort port,
            int eventCapacity,
            long admittedClientTick) {
        this.routineId = Objects.requireNonNull(routineId, "routineId");
        this.request = Objects.requireNonNull(request, "request");
        this.port = Objects.requireNonNull(port, "port");
        if (admittedClientTick < 0) {
            throw new IllegalArgumentException("admission tick must be non-negative");
        }
        events = new RoutineEventRing(eventCapacity);
        hardDeadlineClientTick = saturatingAdd(admittedClientTick, request.maxTicks());
        lastClientTick = admittedClientTick;
        events.append(RoutineEventType.PHASE_STARTED, admittedClientTick, 0,
                Map.of("phase", phase));
    }

    @Override
    public void tick() {
        if (state.terminal() || state == RoutineState.FINALIZING) {
            return;
        }
        final FinitePlanPort.Frame frame;
        try {
            frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("PLAN_OBSERVATION_ADAPTER_FAILURE"));
            return;
        }
        if (frame.clientTick() < lastClientTick
                || frame.observationRevision() < lastObservationRevision) {
            fail(localFailure(
                    RoutineFailure.Category.EXTERNAL,
                    "NON_MONOTONIC_OBSERVATION",
                    RoutineFailure.Recovery.USER,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("client_tick", lastClientTick,
                            "observation_revision", lastObservationRevision),
                    Map.of("client_tick", frame.clientTick(),
                            "observation_revision", frame.observationRevision()),
                    Map.of(), true));
            return;
        }
        lastClientTick = frame.clientTick();
        lastObservationRevision = frame.observationRevision();
        if (lastClientTick >= hardDeadlineClientTick) {
            fail(localFailure(
                    RoutineFailure.Category.TRANSIENT,
                    "PLAN_DEADLINE_EXPIRED",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.ROUTINE,
                    Map.of("before_client_tick", hardDeadlineClientTick),
                    Map.of("client_tick", lastClientTick),
                    Map.of(), false));
            return;
        }
        if (frame.failure() != null) {
            fail(frame.failure());
            return;
        }

        try {
            if (state == RoutineState.QUEUED) {
                stack.push(new SequenceCursor(request.steps()));
                startPhase("execute", RoutineState.RUNNING);
            } else if (actionAttempt != null) {
                advanceAction();
            } else if (activeWait != null) {
                advanceWait();
            } else {
                advanceInterpreter();
            }
        } catch (RuntimeException | LinkageError adapterFailure) {
            fail(adapterFailure("PLAN_ACTION_ADAPTER_FAILURE"));
        }
    }

    private void advanceInterpreter() {
        var current = stack.peek();
        if (current == null) {
            beginFinalization();
            return;
        }
        if (current instanceof SequenceCursor sequence) {
            if (sequence.complete()) {
                completeSequence(sequence);
                return;
            }
            switch (sequence.current()) {
                case FinitePlanRequest.Action action -> beginAction(action);
                case FinitePlanRequest.Assert assertion -> evaluateAssertion(assertion);
                case FinitePlanRequest.WaitUntil wait -> beginWait(wait);
                case FinitePlanRequest.RepeatUntil repeat -> stack.push(
                        new RepeatCursor(repeat, saturatingDeadline(repeat.maxTicks())));
            }
            return;
        }
        advanceRepeat((RepeatCursor) current);
    }

    private void completeSequence(SequenceCursor sequence) {
        if (stack.pop() != sequence) {
            throw new IllegalStateException("plan stack changed unexpectedly");
        }
        if (stack.peek() instanceof RepeatCursor repeat) {
            if (!repeat.bodyRunning) {
                throw new IllegalStateException("completed sequence is not an active repeat body");
            }
            repeat.bodyRunning = false;
            repeat.iterations++;
            startPhase("repeat_condition", RoutineState.RUNNING);
        } else if (stack.isEmpty()) {
            beginFinalization();
        }
    }

    private void beginAction(FinitePlanRequest.Action action) {
        countExecution();
        var issued = Objects.requireNonNull(
                port.begin(routineId, action, hardDeadlineClientTick),
                "adapter returned no action attempt");
        if (!routineId.equals(issued.parentRoutineId())
                || !action.id().equals(issued.stepId())
                || issued.issuedClientTick() != lastClientTick
                || issued.issuedObservationRevision() < lastObservationRevision
                || issued.hardDeadlineClientTick() != hardDeadlineClientTick) {
            safeRelease(issued);
            throw new IllegalStateException("adapter violated action begin contract");
        }
        activeAction = action;
        actionAttempt = issued;
        actionAttempts++;
        startPhase("action", RoutineState.RUNNING);
    }

    private void advanceAction() {
        var current = actionAttempt;
        var evidence = Objects.requireNonNull(
                port.evidence(current), "adapter returned no action evidence");
        if (!current.attemptId().equals(evidence.attemptId())
                || evidence.clientTick() < current.issuedClientTick()
                || evidence.clientTick() > lastClientTick
                || evidence.observationRevision() < current.issuedObservationRevision()) {
            throw new IllegalStateException("adapter violated action evidence contract");
        }
        lastObservationRevision = Math.max(
                lastObservationRevision, evidence.observationRevision());
        lastEvidenceBasis = evidence.basis();
        if (evidence.failure() != null) {
            fail(evidence.failure());
            return;
        }
        if (!evidence.confirmed()) {
            port.maintain(current);
            setWaiting(
                    "action_confirmation",
                    hardDeadlineClientTick,
                    "the child action has positive terminal evidence");
            return;
        }

        var completed = activeAction;
        releaseAction();
        completeCurrentStep(completed.id(), "action", evidence.basis());
    }

    private void evaluateAssertion(FinitePlanRequest.Assert assertion) {
        countExecution();
        var condition = condition(assertion.condition());
        if (condition.status() != FinitePlanPort.ConditionStatus.SATISFIED) {
            fail(conditionFailure(
                    condition.status() == FinitePlanPort.ConditionStatus.UNKNOWN
                            ? "ASSERTION_UNKNOWN" : "ASSERTION_UNSATISFIED",
                    condition));
            return;
        }
        completeCurrentStep(assertion.id(), "assert", condition.basis());
    }

    private void beginWait(FinitePlanRequest.WaitUntil wait) {
        countExecution();
        activeWait = wait;
        waitDeadlineClientTick = saturatingDeadline(wait.maxTicks());
        advanceWait();
    }

    private void advanceWait() {
        var condition = condition(activeWait.condition());
        if (condition.status() == FinitePlanPort.ConditionStatus.SATISFIED) {
            var completed = activeWait;
            activeWait = null;
            completeCurrentStep(completed.id(), "wait_until", condition.basis());
            return;
        }
        if (lastClientTick >= waitDeadlineClientTick) {
            fail(localFailure(
                    RoutineFailure.Category.TRANSIENT,
                    "WAIT_CONDITION_TIMEOUT",
                    RoutineFailure.Recovery.REPLAN,
                    RoutineFailure.Scope.STEP,
                    Map.of("condition_status", "SATISFIED"),
                    Map.of("condition_status", condition.status().name()),
                    condition.basis(), false));
            return;
        }
        setWaiting(
                "condition_wait",
                waitDeadlineClientTick,
                "the declared condition is positively satisfied");
    }

    private void advanceRepeat(RepeatCursor repeat) {
        if (repeat.bodyRunning) {
            throw new IllegalStateException("repeat body frame is missing");
        }
        var condition = condition(repeat.step.until());
        if (condition.status() == FinitePlanPort.ConditionStatus.UNKNOWN) {
            if (lastClientTick >= repeat.deadlineClientTick) {
                fail(repeatFailure("REPEAT_CONDITION_TIMEOUT", repeat, condition));
            } else {
                setWaiting(
                        "repeat_condition_unknown",
                        repeat.deadlineClientTick,
                        "the repeat condition becomes currently known");
            }
            return;
        }
        countExecution();
        if (condition.status() == FinitePlanPort.ConditionStatus.SATISFIED) {
            stack.pop();
            completeCurrentStep(repeat.step.id(), "repeat_until", condition.basis());
            return;
        }
        if (lastClientTick >= repeat.deadlineClientTick
                || repeat.iterations >= repeat.step.maxIterations()) {
            fail(repeatFailure(
                    lastClientTick >= repeat.deadlineClientTick
                            ? "REPEAT_CONDITION_TIMEOUT" : "REPEAT_LIMIT_EXHAUSTED",
                    repeat,
                    condition));
            return;
        }
        repeat.bodyRunning = true;
        stack.push(new SequenceCursor(repeat.step.steps()));
        startPhase("execute", RoutineState.RUNNING);
    }

    private FinitePlanPort.ConditionEvidence condition(FinitePlanRequest.Condition condition) {
        var evidence = Objects.requireNonNull(
                port.evaluate(condition), "adapter returned no condition evidence");
        if (evidence.clientTick() != lastClientTick
                || evidence.observationRevision() < lastObservationRevision) {
            throw new IllegalStateException("adapter violated condition evidence contract");
        }
        lastObservationRevision = evidence.observationRevision();
        lastEvidenceBasis = evidence.basis();
        return evidence;
    }

    private void completeCurrentStep(String stepId, String operation, Map<String, Object> basis) {
        var current = stack.peek();
        if (!(current instanceof SequenceCursor sequence) || sequence.complete()
                || !sequence.current().id().equals(stepId)) {
            throw new IllegalStateException("completed step does not match the plan cursor");
        }
        sequence.index++;
        completedSteps++;
        checkpointSeq++;
        lastEvidenceBasis = Map.copyOf(basis);
        events.append(RoutineEventType.STEP_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("step_id", stepId, "op", operation));
        events.append(RoutineEventType.CHECKPOINT, lastClientTick, lastObservationRevision,
                Map.of("checkpoint_seq", checkpointSeq, "step_id", stepId));
        startPhase("execute", RoutineState.RUNNING);
    }

    private void countExecution() {
        executedSteps++;
        if (executedSteps > FinitePlanRequest.MAX_EXECUTED_STEPS) {
            throw new IllegalStateException("validated plan exceeded its execution budget");
        }
    }

    private void beginFinalization() {
        releaseAction();
        goalVerified = true;
        events.append(RoutineEventType.GOAL_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("kind", kind(), "completed_steps", completedSteps));
        state = RoutineState.FINALIZING;
        phase = "finalizing";
        events.append(RoutineEventType.FINALIZATION_STARTED, lastClientTick,
                lastObservationRevision, Map.of("phase", phase));
    }

    @Override
    public void cancel(String reason) {
        if (state.terminal()) {
            return;
        }
        releaseAction();
        activeWait = null;
        state = RoutineState.CANCELLED;
        phase = "cancelled";
        events.append(RoutineEventType.CANCELLED, lastClientTick, lastObservationRevision,
                Map.of("reason", sanitizeReason(reason)));
    }

    @Override
    public void completeFinalization(RoutineFailure cleanupFailure) {
        if (state != RoutineState.FINALIZING) {
            throw new IllegalStateException("routine is not finalizing");
        }
        if (cleanupFailure != null
                && cleanupFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("finalization failure must use FINALIZATION scope");
        }
        finalizationCompleted = true;
        finalizationFailure = cleanupFailure;
        if (cleanupFailure != null) {
            fail(cleanupFailure);
            return;
        }
        state = RoutineState.SUCCEEDED;
        phase = "succeeded";
        events.append(RoutineEventType.SUCCEEDED, lastClientTick, lastObservationRevision,
                Map.of("action_attempts", actionAttempts));
    }

    @Override
    public void recordTerminalFinalization(RoutineFailure cleanupFailure) {
        if (!state.terminal()) {
            throw new IllegalStateException("routine is not terminal");
        }
        if (cleanupFailure != null
                && cleanupFailure.scope() != RoutineFailure.Scope.FINALIZATION) {
            throw new IllegalArgumentException("cleanup failure must use FINALIZATION scope");
        }
        if (finalizationCompleted) {
            if (!Objects.equals(finalizationFailure, cleanupFailure)) {
                throw new IllegalStateException("terminal finalization outcome is already recorded");
            }
            return;
        }
        finalizationCompleted = true;
        finalizationFailure = cleanupFailure;
    }

    @Override
    public RoutineSnapshot snapshot(long afterEventSeq, int maxEvents) {
        int expected = goalVerified ? completedSteps : Math.max(completedSteps + 1, 1);
        var diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("plan_id", request.planId());
        diagnostics.put("completed_steps", completedSteps);
        diagnostics.put("executed_steps", executedSteps);
        diagnostics.put("action_attempts", actionAttempts);
        diagnostics.put("stack_depth", stack.size());
        diagnostics.put("last_evidence_basis", lastEvidenceBasis);
        return new RoutineSnapshot(
                routineId,
                kind(),
                state,
                phase,
                goalVerified,
                new RoutineProgress(completedSteps, expected, "steps"),
                state.terminal() ? null : currentStep(),
                new RoutineCheckpoint(checkpointSeq, lastObservationRevision),
                new RoutineVerification(
                        completedSteps,
                        expected,
                        goalVerified ? 0 : 1),
                List.of(),
                state == RoutineState.WAITING
                        ? new RoutineWait(waitReason, waitDeadlineClientTick, wakeCondition)
                        : null,
                lastClientTick,
                diagnostics,
                failure,
                finalizationCompleted,
                finalizationFailure,
                events.page(afterEventSeq, maxEvents));
    }

    private RoutineStep currentStep() {
        FinitePlanRequest.Step step = activeAction != null ? activeAction
                : activeWait != null ? activeWait
                : currentStackStep();
        if (step == null) {
            return new RoutineStep("execute_plan", Map.of("plan_id", request.planId()));
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("plan_id", request.planId());
        fields.put("step_id", step.id());
        fields.put("op", operation(step));
        if (step instanceof FinitePlanRequest.Action action) {
            fields.put("routine_kind", action.kind().wireName());
        } else if (step instanceof FinitePlanRequest.RepeatUntil repeat) {
            var cursor = stack.peek() instanceof RepeatCursor value ? value : null;
            fields.put("max_iterations", repeat.maxIterations());
            fields.put("completed_iterations", cursor == null ? 0 : cursor.iterations);
        }
        return new RoutineStep("plan_step", fields);
    }

    private FinitePlanRequest.Step currentStackStep() {
        var frame = stack.peek();
        if (frame instanceof SequenceCursor sequence && !sequence.complete()) {
            return sequence.current();
        }
        return frame instanceof RepeatCursor repeat ? repeat.step : null;
    }

    private static String operation(FinitePlanRequest.Step step) {
        return switch (step) {
            case FinitePlanRequest.Action ignored -> "action";
            case FinitePlanRequest.Assert ignored -> "assert";
            case FinitePlanRequest.WaitUntil ignored -> "wait_until";
            case FinitePlanRequest.RepeatUntil ignored -> "repeat_until";
        };
    }

    @Override
    public UUID routineId() {
        return routineId;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public RoutineState state() {
        return state;
    }

    @Override
    public long lastClientTick() {
        return lastClientTick;
    }

    @Override
    public void retire() {
        if (retired) {
            return;
        }
        retired = true;
        releaseAction();
        activeWait = null;
        port.retire(request);
    }

    private void startPhase(String nextPhase, RoutineState nextState) {
        phase = nextPhase;
        state = nextState;
        waitReason = null;
        wakeCondition = null;
        events.append(RoutineEventType.PHASE_STARTED, lastClientTick,
                lastObservationRevision, Map.of("phase", nextPhase));
    }

    private void setWaiting(String reason, long deadline, String wake) {
        phase = reason;
        state = RoutineState.WAITING;
        waitReason = reason;
        waitDeadlineClientTick = deadline;
        wakeCondition = wake;
    }

    private void fail(RoutineFailure outcome) {
        if (state.terminal()) {
            return;
        }
        releaseAction();
        activeWait = null;
        failure = Objects.requireNonNull(outcome, "outcome");
        state = RoutineState.FAILED;
        phase = "failed";
        if (outcome.recovery() == RoutineFailure.Recovery.REPLAN) {
            events.append(RoutineEventType.NEEDS_REPLAN, lastClientTick,
                    lastObservationRevision,
                    Map.of("code", outcome.code(),
                            "suggested_snapshot_scopes", outcome.suggestedSnapshotScopes()));
        }
        events.append(RoutineEventType.FAILED, lastClientTick, lastObservationRevision,
                Map.of("category", outcome.category().wireName(),
                        "code", outcome.code(), "attempts", outcome.attempts()));
    }

    private RoutineFailure conditionFailure(
            String code, FinitePlanPort.ConditionEvidence evidence) {
        return localFailure(
                RoutineFailure.Category.PRECONDITION,
                code,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("condition_status", "SATISFIED"),
                Map.of("condition_status", evidence.status().name()),
                evidence.basis(), false);
    }

    private RoutineFailure repeatFailure(
            String code,
            RepeatCursor repeat,
            FinitePlanPort.ConditionEvidence evidence) {
        return localFailure(
                RoutineFailure.Category.TRANSIENT,
                code,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("condition_status", "SATISFIED"),
                Map.of("condition_status", evidence.status().name(),
                        "iterations", repeat.iterations,
                        "max_iterations", repeat.step.maxIterations()),
                evidence.basis(), false);
    }

    private RoutineFailure adapterFailure(String code) {
        return localFailure(
                RoutineFailure.Category.EXTERNAL,
                code,
                RoutineFailure.Recovery.USER,
                RoutineFailure.Scope.ROUTINE,
                Map.of(), Map.of(), Map.of("action_attempts", actionAttempts), true);
    }

    private RoutineFailure localFailure(
            RoutineFailure.Category category,
            String code,
            RoutineFailure.Recovery recovery,
            RoutineFailure.Scope scope,
            Map<String, Object> expected,
            Map<String, Object> observed,
            Map<String, Object> evidence,
            boolean requiresUser) {
        return new RoutineFailure(
                category, code, false, recovery, scope, actionAttempts,
                expected, observed, evidence,
                List.of("player", "inventory", "target"), requiresUser);
    }

    private void releaseAction() {
        var current = actionAttempt;
        actionAttempt = null;
        activeAction = null;
        if (current != null) {
            safeRelease(current);
        }
    }

    private void safeRelease(FinitePlanPort.ActionAttempt attempt) {
        try {
            port.release(attempt);
        } catch (RuntimeException | LinkageError ignored) {
            // The integration's global ownership reset remains the final lifecycle fence.
        }
    }

    private long saturatingDeadline(int maxTicks) {
        return Math.min(hardDeadlineClientTick, saturatingAdd(lastClientTick, maxTicks));
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "operator_cancel";
        }
        var normalized = reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 96));
    }

    private sealed interface ExecutionFrame permits SequenceCursor, RepeatCursor {
    }

    private static final class SequenceCursor implements ExecutionFrame {
        private final List<FinitePlanRequest.Step> steps;
        private int index;

        private SequenceCursor(List<FinitePlanRequest.Step> steps) {
            this.steps = List.copyOf(steps);
        }

        private boolean complete() {
            return index >= steps.size();
        }

        private FinitePlanRequest.Step current() {
            return steps.get(index);
        }
    }

    private static final class RepeatCursor implements ExecutionFrame {
        private final FinitePlanRequest.RepeatUntil step;
        private final long deadlineClientTick;
        private int iterations;
        private boolean bodyRunning;

        private RepeatCursor(FinitePlanRequest.RepeatUntil step, long deadlineClientTick) {
            this.step = step;
            this.deadlineClientTick = deadlineClientTick;
        }
    }
}
