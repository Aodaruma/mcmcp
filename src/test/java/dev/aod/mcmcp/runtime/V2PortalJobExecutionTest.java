package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

class V2PortalJobExecutionTest {
    static class FakePort implements V2PortalJobExecution.Port {
        int begins, ticks, closes;
        boolean contact, fail, closeFails;
        public void begin(long tick, BooleanSupplier allowed) { begins++; }
        public V2PortalJobExecution.Step tick(long tick, BooleanSupplier allowed) { ticks++; return fail ? V2PortalJobExecution.Step.FAILED : V2PortalJobExecution.Step.RUNNING; }
        public boolean contactObserved() { return contact; }
        public boolean currentPositionSafe() { return true; }
        public void close() { closes++; if (closeFails) throw new IllegalStateException(); }
    }
    static class Fixture {
        final AgentJobStore jobs = new AgentJobStore();
        final UUID source = UUID.randomUUID();
        final Object connection = new Object();
        final FakePort port = new FakePort();
        final UUID id = jobs.reserve(AgentJobStore.Kind.INTERACT, source, 200, Long.MAX_VALUE);
        final V2PortalJobExecution execution = new V2PortalJobExecution(jobs,id,source,connection,
                V2PortalArguments.parse(Map.of("target","portal","block","minecraft:end_portal",
                        "x",0,"y",64,"z",0,"expected_dimension","minecraft:the_end"),"minecraft:overworld"),port,()->true);
        void start() { jobs.confirm(id,0); execution.tick(source,1,100,true,()->true); }
        void transition() { start(); port.contact=true; assertThat(execution.prepareWorldBoundary(connection,200)).isTrue(); }
    }

    @Test void confirmsNewSessionOnlyAfterContactAndReleaseWithoutReplayingInput() {
        var f=new Fixture(); f.transition(); int calls=f.port.ticks; int closes=f.port.closes;
        assertThat(f.execution.pollArrival(null,null,f.connection,false,true,300).state()).isEqualTo(AgentJobStore.State.RUNNING);
        UUID arrival=UUID.randomUUID();
        assertThat(f.execution.pollArrival(arrival,"minecraft:the_end",f.connection,true,true,400).state()).isEqualTo(AgentJobStore.State.RUNNING);
        assertThat(f.execution.pollArrival(arrival,"minecraft:the_end",f.connection,false,true,500).state()).isEqualTo(AgentJobStore.State.RUNNING);
        f.execution.pollArrival(arrival,"minecraft:the_end",f.connection,true,true,600);
        var done=f.execution.pollArrival(arrival,"minecraft:the_end",f.connection,true,true,500_000_600);
        assertThat(done.state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(done.result()).containsEntry("arrival_session_id",arrival.toString())
                .containsEntry("arrival_confirmed",true).containsEntry("inputs_released",true);
        assertThat(f.port.ticks).isEqualTo(calls);
        assertThat(f.port.closes).isEqualTo(closes);
    }

    @Test void unrelatedWorldBoundaryAndUnreleasedInputCannotBecomeAnArrivalWait() {
        var f=new Fixture(); f.start();
        assertThat(f.execution.prepareWorldBoundary(f.connection,200)).isFalse();
        f.port.contact=true;
        assertThat(f.execution.prepareWorldBoundary(new Object(),200)).isFalse();
        f.port.closeFails=true;
        assertThat(f.execution.prepareWorldBoundary(f.connection,200)).isFalse();
        assertThat(f.execution.stop("world_boundary").state()).isEqualTo(AgentJobStore.State.RUNNING);
        f.port.closeFails=false;
        assertThat(f.execution.stop("world_boundary").state()).isEqualTo(AgentJobStore.State.FAILED);
    }

    @Test void oldContactCannotAuthorizeABoundaryAfterThePortalDisappears() {
        var f = new Fixture(); f.port.contact = true; f.start();
        assertThat(f.jobs.get(f.id).result()).containsEntry("contact_observed", true);
        f.port.contact = false;
        assertThat(f.execution.prepareWorldBoundary(f.connection, 200)).isFalse();
        assertThat(f.execution.stop("world_boundary").state()).isEqualTo(AgentJobStore.State.FAILED);
    }

    @Test void rejectsSameSessionWrongWorldReconnectAndMissingInputRelease() {
        for(int scenario=0;scenario<4;scenario++) {
            var f=new Fixture(); f.transition();
            var result=f.execution.pollArrival(scenario==0?f.source:UUID.randomUUID(),
                    scenario==1?"minecraft:overworld":"minecraft:the_end", scenario==2?new Object():f.connection,
                    true,scenario!=3,300);
            assertThat(result.state()).isEqualTo(AgentJobStore.State.FAILED);
            assertThat(result.result()).containsEntry("arrival_confirmed",false);
        }
    }

    @Test void cancellationBeforeDeliveryAndAfterContactCannotSucceed() {
        var undelivered=new Fixture();
        assertThat(undelivered.execution.cancel().state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(undelivered.port.begins).isZero();
        var queued=new Fixture(); queued.jobs.confirm(queued.id,0);
        assertThat(queued.execution.cancel().state()).isEqualTo(AgentJobStore.State.CANCELLED);
        var f=new Fixture(); f.transition(); f.jobs.requestCancel(f.id);
        assertThat(f.execution.pollArrival(UUID.randomUUID(),"minecraft:the_end",f.connection,true,true,300).state())
                .isEqualTo(AgentJobStore.State.CANCELLED);
    }

    @Test void portalDisappearanceAndBothTimeoutPhasesStopWithoutRetry() {
        var vanished=new Fixture(); vanished.port.fail=true; vanished.start();
        assertThat(vanished.jobs.get(vanished.id).state()).isEqualTo(AgentJobStore.State.FAILED);
        vanished.execution.tick(vanished.source,2,200,true,()->true);
        assertThat(vanished.port.ticks).isEqualTo(1);
        var entering=new Fixture(); entering.start();
        assertThat(entering.execution.tick(entering.source,2,10_000_000_101L,true,()->true).state()).isEqualTo(AgentJobStore.State.FAILED);
        var waiting=new Fixture(); waiting.transition();
        assertThat(waiting.execution.pollArrival(null,null,waiting.connection,false,true,10_000_000_101L).state()).isEqualTo(AgentJobStore.State.FAILED);
    }
}
