package dev.aod.mcmcp.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentInputDeadlineTest {
    @Test
    void realDeadlineSuppressesEveryChannelEvenAfterPauseAndFreshLeasePublication() {
        var input=new AgentInputState();
        long now=System.nanoTime();
        input.setPaused(true,now);
        input.setActionDeadline(now-1);
        input.publishUse(input.watchdogTime(now)+1_000_000_000L);
        input.publishAttack(input.watchdogTime(now)+1_000_000_000L);
        input.publishPick(input.watchdogTime(now)+1_000_000_000L);
        input.publishMovement(true,false,false,false,false,input.watchdogTime(now)+1_000_000_000L);
        input.setPaused(false,now+1);
        assertThat(input.useActive()).isFalse();
        assertThat(input.attackActive()).isFalse();
        assertThat(input.pickActive()).isFalse();
        assertThat(input.movementSnapshot().forward()).isFalse();
        input.publishUse(input.watchdogTime(now)+1_000_000_000L);
        assertThat(input.useActive()).isFalse();
    }
}
