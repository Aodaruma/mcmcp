package dev.aod.mcmcp.agent.script;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptJobExecutionTest {
    @Test
    void runsCommandsInOrderButDoesNotPublishSuccessUntilInputRelease() {
        var jobs = new AgentJobStore();
        UUID world = UUID.randomUUID();
        UUID id = confirmed(jobs, world, 3);
        var called = new ArrayList<String>();
        var execution = execution(jobs, id, world,
                "report(value={phase:\"start\"}); move(x=1); repeat(2) { click(button=\"right\"); }",
                new ScriptJobExecution.CommandRunner() {
                    @Override
                    public ScriptJobExecution.Outcome execute(String name,
                            Map<String, Object> arguments, BooleanSupplier cancelled) {
                        called.add(name);
                        return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    @Override public void cancelActive() { }
                });

        execution.run();
        assertThat(called).containsExactly("move", "click", "click");
        assertThat(execution.finishIfReleased(false).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        var finished = execution.finishIfReleased(true);
        assertThat(finished.state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(finished.completedOperations()).isEqualTo(3);
        assertThat(finished.result()).containsEntry("script_status", "success")
                .containsEntry("completed_commands", 3)
                .containsEntry("reports", java.util.List.of(Map.of("phase", "start")));
    }

    @Test
    void cancellationRacingACompletedChildRetainsItsProgressAndWaitsForRelease() {
        var jobs = new AgentJobStore();
        UUID world = UUID.randomUUID();
        UUID id = confirmed(jobs, world, 2);
        var active = new AtomicReference<ScriptJobExecution>();
        var cancelCalls = new AtomicInteger();
        var execution = execution(jobs, id, world, "click(button=\"left\"); click(button=\"left\");",
                new ScriptJobExecution.CommandRunner() {
                    @Override
                    public ScriptJobExecution.Outcome execute(String name,
                            Map<String, Object> arguments, BooleanSupplier cancelled) {
                        active.get().cancel();
                        return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    @Override public void cancelActive() { cancelCalls.incrementAndGet(); }
                });
        active.set(execution);

        execution.run();
        assertThat(execution.finishIfReleased(false).state())
                .isEqualTo(AgentJobStore.State.RUNNING);
        var finished = execution.finishIfReleased(true);
        assertThat(finished.state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(finished.completedOperations()).isEqualTo(1);
        assertThat(cancelCalls).hasValue(1);
    }

    @Test
    void invalidUnexecutedBranchFailsBeforeAnyGameCommand() {
        var jobs = new AgentJobStore();
        UUID world = UUID.randomUUID();
        UUID id = confirmed(jobs, world, 2);
        var called = new AtomicInteger();
        var execution = execution(jobs, id, world,
                "if (false) { unknown(); } move(x=1);",
                new ScriptJobExecution.CommandRunner() {
                    @Override
                    public ScriptJobExecution.Outcome execute(String name,
                            Map<String, Object> arguments, BooleanSupplier cancelled) {
                        called.incrementAndGet();
                        return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    @Override public void cancelActive() { }
                });

        execution.run();
        var finished = execution.finishIfReleased(true);
        assertThat(finished.state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(finished.failure()).isEqualTo("script_invalid");
        assertThat(called).hasValue(0);
    }

    @Test
    void explicitScriptBudgetCompletesMoreThanOldCallLimit() {
        var jobs=new AgentJobStore();var world=UUID.randomUUID();var id=confirmed(jobs,world,1500);
        var calls=new AtomicInteger();
        var execution=new ScriptJobExecution(jobs,id,world,"repeat(1500){move();}",Set.of("move"),
                10000,1500,1500,new ScriptJobExecution.CommandRunner(){
                    public ScriptJobExecution.Outcome execute(String name,Map<String,Object> args,BooleanSupplier cancelled){calls.incrementAndGet();return ScriptJobExecution.Outcome.SUCCESS;}
                    public void cancelActive(){}
                },()->true,Long.MAX_VALUE);
        execution.run();
        assertThat(execution.finishIfReleased(true).state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(calls).hasValue(1500);
    }

    @Test
    void fake24HourDeadlinePreventsSecondCommandAndWaitsForChildRelease() {
        var now=new java.util.concurrent.atomic.AtomicLong(1);
        var jobs=new AgentJobStore(now::get);var world=UUID.randomUUID();
        long deadline=Duration.ofHours(24).toNanos()+1;
        var id=jobs.reserve(AgentJobStore.Kind.SCRIPT,world,3,100);
        jobs.setDeadline(id,deadline);jobs.confirm(id,1);
        var calls=new AtomicInteger();
        var execution=new ScriptJobExecution(jobs,id,world,"repeat(3){move();}",Set.of("move"),
                1000,3,3,new ScriptJobExecution.CommandRunner(){
                    public ScriptJobExecution.Outcome execute(String name,Map<String,Object> args,BooleanSupplier cancelled){
                        calls.incrementAndGet();now.set(deadline);return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    public void cancelActive(){}
                },()->true,deadline,now::get);
        execution.run();
        assertThat(execution.finishIfReleased(false).state()).isEqualTo(AgentJobStore.State.RUNNING);
        var stopped=execution.finishIfReleased(true);
        assertThat(stopped.state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(stopped.failure()).isEqualTo("script_timeout");
        assertThat(calls).hasValue(1);
    }

    private static UUID confirmed(AgentJobStore jobs, UUID world, int maxCalls) {
        UUID id = jobs.reserve(AgentJobStore.Kind.SCRIPT, world, maxCalls,
                System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(jobs.confirm(id, System.nanoTime()))
                .isEqualTo(AgentJobStore.Confirmation.CONFIRMED);
        return id;
    }

    @Test
    void worldSafetyBoundaryRetainsTheCompletedChildWithoutDispatchingAnother() {
        var jobs = new AgentJobStore(); var world = UUID.randomUUID(); var id = confirmed(jobs, world, 2);
        var safe = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new AtomicInteger();
        var child = Map.<String, Object>of("action_id", UUID.randomUUID().toString(), "state", "succeeded",
                "result", Map.of("confirmed_count", 2, "unconfirmed", false));
        var execution = new ScriptJobExecution(jobs, id, world, "click(); click();", Set.of("click"),
                10000, 100, 2, new ScriptJobExecution.CommandRunner() {
                    public ScriptJobExecution.Outcome execute(String name, Map<String, Object> args, BooleanSupplier cancelled) {
                        calls.incrementAndGet(); safe.set(false); return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    public Map<String, Object> result() { return child; }
                    public void cancelActive() { }
                }, safe::get, Long.MAX_VALUE);
        execution.run();
        var finished = execution.finishIfReleased(true);
        assertThat(finished.failure()).isEqualTo("safety_interrupted");
        assertThat(finished.completedOperations()).isEqualTo(1);
        assertThat(calls).hasValue(1);
        assertThat(((Map<?, ?>) finished.result().get("last_command")).get("action")).isEqualTo(child);
    }

    @Test
    void failedTransferKeepsUnknownChildAndPartialQuantityEvenWhenReportsAreFull() {
        var jobs = new AgentJobStore();
        var world = UUID.randomUUID();
        var id = confirmed(jobs, world, 3);
        var calls = new AtomicInteger();
        var childId = UUID.randomUUID().toString();
        var transfer = new java.util.LinkedHashMap<String, Object>(Map.of(
                "requested_count", 10, "confirmed_count", 2, "unconfirmed", true,
                "release_pending", false, "effects", java.util.List.of(Map.of("verification", "unknown"))));
        var child = Map.<String, Object>of("action_id", childId, "kind", "inventory", "state", "failed",
                "stop_reason", "container_quantity_not_confirmed", "result", transfer);
        var execution = execution(jobs, id, world, "repeat(32){report(value=1);} click(); click();",
                new ScriptJobExecution.CommandRunner() {
                    public ScriptJobExecution.Outcome execute(String name, Map<String, Object> args, BooleanSupplier cancelled) {
                        calls.incrementAndGet(); return ScriptJobExecution.Outcome.FAILURE;
                    }
                    public Map<String, Object> result() { return child; }
                    public void cancelActive() { }
                });
        execution.run();
        transfer.put("confirmed_count", 10);
        assertThat(execution.finishIfReleased(false).state()).isEqualTo(AgentJobStore.State.RUNNING);
        var finished = execution.finishIfReleased(true);
        assertThat(finished.state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(calls).hasValue(1);
        assertThat((java.util.List<?>) finished.result().get("reports")).hasSize(32);
        var diagnostic = (Map<?, ?>) finished.result().get("last_command");
        assertThat(diagnostic.get("truncated")).isEqualTo(false);
        var retained = (Map<?, ?>) diagnostic.get("action");
        assertThat(retained.get("action_id")).isEqualTo(childId);
        var quantity = (Map<?, ?>) retained.get("result");
        assertThat(quantity.get("confirmed_count")).isEqualTo(2);
        assertThat(quantity.get("unconfirmed")).isEqualTo(true);
        assertThat(quantity.get("effects")).isEqualTo(java.util.List.of(Map.of("verification", "unknown")));
    }

    @Test
    void cancelledChildRetainsReleasePendingEvidenceWithoutFinishingTheParentEarly() {
        var jobs = new AgentJobStore(); var world = UUID.randomUUID(); var id = confirmed(jobs, world, 2);
        var child = Map.<String, Object>of("action_id", UUID.randomUUID().toString(), "state", "running",
                "result", Map.of("release_pending", true, "unconfirmed", true));
        var execution = execution(jobs, id, world, "click(); click();", new ScriptJobExecution.CommandRunner() {
            public ScriptJobExecution.Outcome execute(String name, Map<String, Object> args, BooleanSupplier cancelled) {
                jobs.requestCancel(id); return ScriptJobExecution.Outcome.CANCELLED;
            }
            public Map<String, Object> result() { return child; }
            public void cancelActive() { }
        });
        execution.run();
        assertThat(execution.finishIfReleased(false).state()).isEqualTo(AgentJobStore.State.RUNNING);
        var finished = execution.finishIfReleased(true);
        assertThat(finished.state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(((Map<?, ?>) finished.result().get("last_command")).get("action")).isEqualTo(child);
    }

    @Test
    void admissionFailureDoesNotMisattributeThePreviousSuccessfulChild() {
        var jobs = new AgentJobStore(); var world = UUID.randomUUID(); var id = confirmed(jobs, world, 3);
        var calls = new AtomicInteger();
        var execution = execution(jobs, id, world, "click(); move(); click();", new ScriptJobExecution.CommandRunner() {
            public ScriptJobExecution.Outcome execute(String name, Map<String, Object> args, BooleanSupplier cancelled) {
                return calls.incrementAndGet() == 1 ? ScriptJobExecution.Outcome.SUCCESS : ScriptJobExecution.Outcome.FAILURE;
            }
            public Map<String, Object> result() {
                return calls.get() == 1 ? Map.of("action_id", "previous-child", "state", "succeeded") : Map.of();
            }
            public void cancelActive() { }
        });
        execution.run();
        var finished = execution.finishIfReleased(true);
        var last = (Map<?, ?>) finished.result().get("last_command");
        assertThat(last.get("command")).isEqualTo("move");
        assertThat(last.get("action")).isEqualTo(Map.of());
        assertThat(calls).hasValue(2);
        assertThat(finished.completedOperations()).isEqualTo(1);
    }

    @Test
    void publishesLiveSectionAndRetainsPartialBuildCountsWithoutCountingInventoryTransfers() {
        var jobs = new AgentJobStore(); var world = UUID.randomUUID(); var id = confirmed(jobs, world, 4);
        var calls = new AtomicInteger();
        var runner = new ScriptJobExecution.CommandRunner() {
            public ScriptJobExecution.Outcome execute(String name, Map<String, Object> args, BooleanSupplier cancelled) {
                int index = calls.incrementAndGet();
                assertThat(jobs.get(id).result().get("active_command")).isEqualTo(Map.of("index", index, "command", name));
                if (index == 3) assertThat(jobs.get(id).result()).containsEntry("placed_blocks", 8L)
                        .containsEntry("already_matching_blocks", 2L);
                return index == 3 ? ScriptJobExecution.Outcome.FAILURE : ScriptJobExecution.Outcome.SUCCESS;
            }
            public Map<String, Object> result() {
                return switch (calls.get()) {
                    case 1 -> Map.of("kind", "place_block", "result", Map.of("confirmed_count", 8, "observed_count", 2));
                    case 2 -> Map.of("kind", "inventory", "result", Map.of("confirmed_count", 64));
                    default -> Map.of("kind", "break_block", "result", Map.of("confirmed_count", 3));
                };
            }
            public void cancelActive() { }
        };
        var execution = new ScriptJobExecution(jobs, id, world, "place(); inventory(); breakBlocks(); place();",
                Set.of("place", "inventory", "breakBlocks"), 1000, 10, 4, runner, () -> true, Long.MAX_VALUE);
        execution.run();
        assertThat(execution.finishIfReleased(false).state()).isEqualTo(AgentJobStore.State.RUNNING);
        var done = execution.finishIfReleased(true);
        assertThat(done.state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(done.result()).containsEntry("placed_blocks", 8L).containsEntry("broken_blocks", 3L)
                .containsEntry("already_matching_blocks", 2L).containsEntry("completed_commands", 2)
                .containsEntry("commands_started", 3).doesNotContainKey("active_command");
    }

    @Test
    void largeSectionScriptKeepsAggregateCountsBeyondOneBlockJobLimit() {
        var jobs = new AgentJobStore(); var world = UUID.randomUUID(); var id = confirmed(jobs, world, 18432);
        var execution = new ScriptJobExecution(jobs, id, world, "repeat(18432){place(dx=7);}", Set.of("place"),
                1_000_000, 18432, 18432, new ScriptJobExecution.CommandRunner() {
                    public ScriptJobExecution.Outcome execute(String n, Map<String,Object> a, BooleanSupplier c) {
                        return ScriptJobExecution.Outcome.SUCCESS;
                    }
                    public Map<String,Object> result() { return Map.of("kind", "place_block", "result", Map.of("confirmed_count", 8)); }
                    public void cancelActive() { }
                }, () -> true, Long.MAX_VALUE);
        execution.run();
        var done = execution.finishIfReleased(true);
        assertThat(done.state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(done.result()).containsEntry("placed_blocks", 147456L).containsEntry("completed_commands", 18432);
        assertThat(done.result().size()).isLessThanOrEqualTo(16);
    }

    private static ScriptJobExecution execution(AgentJobStore jobs, UUID id, UUID world,
            String source, ScriptJobExecution.CommandRunner runner) {
        return new ScriptJobExecution(jobs, id, world, source,
                Set.of("move", "click"), 10_000, 100, jobs.get(id).maxOperations(),
                runner, () -> true, System.nanoTime() + Duration.ofSeconds(5).toNanos());
    }
}
