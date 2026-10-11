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
        default Map<String, Object> result() { return Map.of(); }
        default boolean mayContinueAfterFailure() { return false; }
        default Map<String, Object> observe(Map<String, Object> arguments, BooleanSupplier cancelled) {
            throw new IllegalArgumentException("script observations unavailable");
        }
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
    private final java.util.function.LongSupplier clock;
    private volatile ActionScript.Result result;
    private volatile String unexpectedFailure;
    private Map<String, Object> lastCommand = Map.of();
    private Map<String, Object> activeCommand = Map.of();
    private int commandsStarted;
    private long placedBlocks;
    private long brokenBlocks;
    private long matchingBlocks;
    private Map<String, ScriptResources.Resource> resources = Map.of();

    public ScriptJobExecution withResources(Map<String, ScriptResources.Resource> resources) {
        if (jobs.get(actionId).state() != AgentJobStore.State.UNCONFIRMED)
            throw new IllegalStateException("resources must be bound before delivery");
        this.resources = Map.copyOf(resources);
        return this;
    }

    public ScriptJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                              String source, Set<String> commands,
                              int workBudget, int iterationBudget, int callBudget,
                              CommandRunner runner, BooleanSupplier stillSafe,
                              long deadlineNanos) {
        this(jobs, actionId, worldSessionId, source, commands, workBudget, iterationBudget,
                callBudget, runner, stillSafe, deadlineNanos, System::nanoTime);
    }

    ScriptJobExecution(AgentJobStore jobs, UUID actionId, UUID worldSessionId,
                              String source, Set<String> commands,
                              int workBudget, int iterationBudget, int callBudget,
                              CommandRunner runner, BooleanSupplier stillSafe,
                              long deadlineNanos, java.util.function.LongSupplier clock) {
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
        this.clock = Objects.requireNonNull(clock);
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
            result = ActionScript.run(source, commands, new ActionScript.Sink() {
              public ActionScript.Outcome execute(ActionScript.Command command, BooleanSupplier cancelled) {
                if (aborted() || !jobs.canDispatch(actionId, worldSessionId)) {
                    return ActionScript.Outcome.CANCELLED;
                }
                activeCommand = Map.of("index", ++commandsStarted, "command", command.name());
                jobs.recordResult(actionId, progressDetails());
                final Outcome outcome;
                try {
                    outcome = runner.execute(command.name(), command.arguments(), cancelled);
                } catch (RuntimeException | LinkageError failure) {
                    lastCommand = ScriptChildDiagnostic.capture(command.name(), Outcome.FAILURE, Map.of());
                    activeCommand = Map.of();
                    jobs.recordResult(actionId, progressDetails());
                    throw failure;
                }
                var child = runner.result();
                lastCommand = ScriptChildDiagnostic.capture(command.name(), outcome, child);
                retainBlockProgress(command.name(), child);
                activeCommand = Map.of();
                if (outcome == Outcome.SUCCESS) {
                    // A completed child is irreversible even if cancellation raced its reply.
                    jobs.recordOperation(actionId);
                }
                jobs.recordResult(actionId, progressDetails());
                return switch (outcome) {
                    case SUCCESS -> ActionScript.Outcome.SUCCESS;
                    case FAILURE -> ActionScript.Outcome.FAILURE;
                    case CANCELLED -> ActionScript.Outcome.CANCELLED;
                };
              }
              public Map<String, Object> result() { return runner.result(); }
              public boolean mayContinueAfterFailure() { return runner.mayContinueAfterFailure(); }
              public Map<String, Object> observe(Map<String, Object> args, BooleanSupplier cancelled) {
                  return runner.observe(args, cancelled);
              }
            }, this::aborted, budget, resources);
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
            if (expired() || !stillSafe.getAsBoolean()) {
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
        if (job.state() == AgentJobStore.State.RUNNING) {
            var details = progressDetails();
            details.remove("active_command");
            details.put("script_status", result == null ? "failed"
                    : result.status().name().toLowerCase(java.util.Locale.ROOT));
            if (result != null) {
                details.put("work", result.work());
                details.put("iterations", result.iterations());
                details.put("calls", result.calls());
                details.put("reports", result.reports());
                if (!result.detail().isEmpty()) details.put("detail", result.detail());
            }
            details.put("resources", resources.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Map.of("format", entry.getValue().format(), "sha256", entry.getValue().sha256()))));
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

    private LinkedHashMap<String, Object> progressDetails() {
        var details = new LinkedHashMap<String, Object>();
        details.put("script_status", "running");
        details.put("commands_started", commandsStarted);
        details.put("completed_commands", jobs.get(actionId).completedOperations());
        details.put("placed_blocks", placedBlocks);
        details.put("broken_blocks", brokenBlocks);
        details.put("already_matching_blocks", matchingBlocks);
        if (!activeCommand.isEmpty()) details.put("active_command", activeCommand);
        if (!lastCommand.isEmpty()) details.put("last_command", lastCommand);
        return details;
    }

    private void retainBlockProgress(String command, Map<String, Object> child) {
        if (!(child.get("result") instanceof Map<?, ?> details)) return;
        // Count acknowledged changes even when later movement or cancellation stopped the child.
        if (command.equals("place") && "place_block".equals(child.get("kind"))) {
            placedBlocks += blockCount(details.get("confirmed_count"));
            matchingBlocks += blockCount(details.get("observed_count"));
        } else if (command.equals("breakBlocks") && "break_block".equals(child.get("kind"))) {
            brokenBlocks += blockCount(details.get("confirmed_count"));
        }
    }

    private static long blockCount(Object value) {
        if (!(value instanceof Number number)) return 0;
        long count = number.longValue();
        return count >= 0 && count <= 4096 && number.doubleValue() == count ? count : 0;
    }

    private boolean aborted() {
        return jobs.get(actionId).cancelRequested() || !stillSafe.getAsBoolean() || expired();
    }

    private boolean expired() {
        return clock.getAsLong() - deadlineNanos >= 0L;
    }
}
