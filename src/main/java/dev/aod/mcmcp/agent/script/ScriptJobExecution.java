package dev.aod.mcmcp.agent.script;

import dev.aod.mcmcp.agent.action.AgentJobStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Runs the bounded language on a worker while a client-tick owner executes each command. */
public final class ScriptJobExecution implements Runnable {
    public enum Outcome { SUCCESS, FAILURE, CANCELLED }

    public interface CommandRunner {
        /** Return only after the command and all of its owned inputs have reached a terminal state. */
        Outcome execute(String name, Map<String, Object> arguments, BooleanSupplier cancelled);

        /** Request that the currently running game command stop and release its inputs. */
        void cancelActive();
    }

    private final AgentJobStore jobs;
    private final UUID actionId;
    private final UUID worldSessionId;
    private final String source;
    private final Set<String> commands;
    private final ActionScript.Budget budget;
    private final CommandRunner runner;
    private final BooleanSupplier stillSafe;
    private final long deadlineNanos;
    private volatile ActionScript.Result result;
    private volatile String unexpectedFailure;

    public ScriptJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                              String source, Set<String> commands,
                              int workBudget, int iterationBudget, int callBudget,
                              CommandRunner runner, BooleanSupplier stillSafe,
                              long deadlineNanos) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.worldSessionId = Objects.requireNonNull(worldSessionId, "worldSessionId");
        this.source = Objects.requireNonNull(source, "source");
        this.commands = Set.copyOf(commands);
        this.budget = new ActionScript.Budget(workBudget, iterationBudget, callBudget);
        if (callBudget < 1) throw new IllegalArgumentException("script call budget must be positive");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.stillSafe = Objects.requireNonNull(stillSafe, "stillSafe");
        this.deadlineNanos = deadlineNanos;
        var job = jobs.get(actionId);
        if (job.kind() != AgentJobStore.Kind.SCRIPT
                || !job.worldSessionId().equals(worldSessionId)
                || job.maxOperations() != callBudget) {
            throw new IllegalArgumentException("script job does not match its session or budget");
        }
    }

    /** The caller starts this only after the action response has been delivered. */
    @Override
    public void run() {
        try {
            if (jobs.get(actionId).state() != AgentJobStore.State.QUEUED) return;
            jobs.start(actionId, worldSessionId);
            result = ActionScript.run(source, commands, (command, cancelled) -> {
                if (aborted() || !jobs.canDispatch(actionId, worldSessionId)) {
                    return ActionScript.Outcome.CANCELLED;
                }
                var outcome = runner.execute(command.name(), command.arguments(), cancelled);
                if (outcome == Outcome.SUCCESS) {
                    // A completed child is irreversible even if cancellation raced its reply.
                    jobs.recordOperation(actionId);
                }
                return switch (outcome) {
                    case SUCCESS -> ActionScript.Outcome.SUCCESS;
                    case FAILURE -> ActionScript.Outcome.FAILURE;
                    case CANCELLED -> ActionScript.Outcome.CANCELLED;
                };
            }, this::aborted, budget);
        } catch (RuntimeException | LinkageError failure) {
            unexpectedFailure = "script_runtime_failed";
            runner.cancelActive();
        }
    }

    /** Cancellation is an intent; the game owner must still release its inputs. */
    public void cancel() {
        var job = jobs.get(actionId);
        if (!job.state().terminal()) {
            jobs.requestCancel(actionId);
            if (job.state() == AgentJobStore.State.UNCONFIRMED) {
                jobs.finish(actionId, AgentJobStore.State.CANCELLED, "client_request", true);
                return;
            }
            runner.cancelActive();
        }
    }

    /** Called on the client tick; a terminal script result is gated on child input release. */
    public AgentJobStore.Snapshot finishIfReleased(boolean inputsReleased) {
        var job = jobs.get(actionId);
        if (job.state().terminal() || result == null && unexpectedFailure == null) return job;
        if (!inputsReleased) return job;
        String failure = unexpectedFailure;
        AgentJobStore.State terminal = AgentJobStore.State.FAILED;
        if (job.cancelRequested()) {
            terminal = AgentJobStore.State.CANCELLED;
            failure = "client_request";
        } else if (failure == null) {
            if (!stillSafe.getAsBoolean()) {
                failure = expired() ? "script_timeout" : "safety_interrupted";
            } else {
                terminal = switch (result.status()) {
                    case SUCCESS -> AgentJobStore.State.SUCCEEDED;
                    case CANCELLED -> AgentJobStore.State.CANCELLED;
                    case INVALID, LIMIT, FAILED -> AgentJobStore.State.FAILED;
                };
                failure = switch (result.status()) {
                    case SUCCESS -> null;
                    case CANCELLED -> "script_cancelled";
                    case INVALID -> "script_invalid";
                    case LIMIT -> "script_limit";
                    case FAILED -> "script_command_failed";
                };
            }
        }
        if (result != null && job.state() == AgentJobStore.State.RUNNING) {
            var details = new LinkedHashMap<String, Object>();
            details.put("script_status", result.status().name().toLowerCase(java.util.Locale.ROOT));
            details.put("work", result.work());
            details.put("iterations", result.iterations());
            details.put("calls", result.calls());
            details.put("completed_commands", job.completedOperations());
            jobs.recordResult(actionId, details);
        }
        try {
            jobs.finish(actionId, terminal, failure, true);
        } catch (IllegalStateException racedCancel) {
            if (!jobs.get(actionId).cancelRequested() || terminal != AgentJobStore.State.SUCCEEDED) {
                throw racedCancel;
            }
            jobs.finish(actionId, AgentJobStore.State.CANCELLED, "client_request", true);
        }
        return jobs.get(actionId);
    }

    private boolean aborted() {
        return jobs.get(actionId).cancelRequested() || !stillSafe.getAsBoolean() || expired();
    }

    private boolean expired() {
        return System.nanoTime() - deadlineNanos >= 0L;
    }
}
