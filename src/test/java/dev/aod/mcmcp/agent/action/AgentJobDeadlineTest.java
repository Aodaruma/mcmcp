package dev.aod.mcmcp.agent.action;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class AgentJobDeadlineTest {
    @Test
    void wallDeadlineSurvivesIdleTimeAndCannotBeExtendedOrDispatchAfter24Hours() {
        var now = new AtomicLong(1);
        var jobs = new AgentJobStore(now::get);
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.MOVE, session, AgentJobLimits.MAX_TICKS, 100);
        long deadline = 1 + TimeUnit.HOURS.toNanos(24);
        jobs.setDeadline(id, deadline);
        jobs.confirm(id, 2);
        jobs.start(id, session);
        now.set(deadline - 1);
        assertThat(jobs.canDispatch(id, session)).isTrue();
        assertThatThrownBy(() -> jobs.setDeadline(id, deadline + 1)).isInstanceOf(IllegalStateException.class);
        now.set(deadline);
        assertThat(jobs.canDispatch(id, session)).isFalse();
        assertThat(jobs.expired(id)).isTrue();
        assertThat(jobs.get(id).timing()).containsEntry("remaining_seconds", 0.0);
        assertThatThrownBy(() -> jobs.finish(id, AgentJobStore.State.FAILED, "duration_limit", false))
                .isInstanceOf(IllegalStateException.class);
        jobs.finish(id, AgentJobStore.State.FAILED, "duration_limit", true);
        var timing = jobs.get(id).timing();
        now.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertThat(jobs.get(id).timing()).isEqualTo(timing);
    }

    @Test
    void largerExplicitCountStillHasAnExactBoundAndCancellationPreventsDispatch() {
        var jobs = new AgentJobStore();
        var session = UUID.randomUUID();
        var id = jobs.reserve(AgentJobStore.Kind.SCRIPT, session, 1500, 100);
        jobs.confirm(id, 1); jobs.start(id, session);
        for (int i=0; i<1500; i++) {
            assertThat(jobs.canDispatch(id, session)).isTrue();
            jobs.recordOperation(id);
        }
        assertThat(jobs.canDispatch(id, session)).isFalse();
        jobs.requestCancel(id);
        assertThatThrownBy(() -> jobs.finish(id, AgentJobStore.State.SUCCEEDED, null, true))
                .isInstanceOf(IllegalStateException.class);
        jobs.finish(id, AgentJobStore.State.CANCELLED, "client_request", true);
    }
}
