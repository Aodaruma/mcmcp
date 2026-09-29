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
                "move(x=1); repeat(2) { click(button=\"right\"); }",
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
                .containsEntry("completed_commands", 3);
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

    private static ScriptJobExecution execution(AgentJobStore jobs, UUID id, UUID world,
            String source, ScriptJobExecution.CommandRunner runner) {
        return new ScriptJobExecution(jobs, id, world, source,
                Set.of("move", "click"), 10_000, 100, jobs.get(id).maxOperations(),
                runner, () -> true, System.nanoTime() + Duration.ofSeconds(5).toNanos());
    }
}
