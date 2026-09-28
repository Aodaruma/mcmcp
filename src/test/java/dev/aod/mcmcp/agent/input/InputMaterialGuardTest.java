package dev.aod.mcmcp.agent.input;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static dev.aod.mcmcp.agent.input.InputMaterialGuard.Decision.*;

class InputMaterialGuardTest {
    private InputMaterialGuard guard() {
        return new InputMaterialGuard("minecraft:black_concrete_powder", 5, 1, 64, 2, 179, 10, 30);
    }
    @Test
    void sameMaterialRefillCanResumeButLateRefillCannotRestartExpiredWait() {
        var g=guard();
        assertThat(g.check(1,5,null,true,1,64,2,179,10)).isEqualTo(WAIT);
        assertThat(g.check(TimeUnit.SECONDS.toNanos(29),5,"minecraft:black_concrete_powder",true,1,64,2,-180,10)).isEqualTo(HOLD);
        assertThat(g.check(TimeUnit.SECONDS.toNanos(30),5,null,true,1,64,2,179,10)).isEqualTo(WAIT);
        assertThat(g.check(TimeUnit.SECONDS.toNanos(60),5,"minecraft:black_concrete_powder",true,1,64,2,179,10)).isEqualTo(REFILL_TIMEOUT);
    }
    @Test
    void differentItemsSlotOffhandAndPoseNeverContinueUse() {
        assertThat(guard().check(1,5,"minecraft:flint_and_steel",true,1,64,2,179,10)).isEqualTo(ITEM_CHANGED);
        assertThat(guard().check(1,4,null,true,1,64,2,179,10)).isEqualTo(ITEM_CHANGED);
        assertThat(guard().check(1,5,null,false,1,64,2,179,10)).isEqualTo(OFFHAND_PRESENT);
        assertThat(guard().check(1,5,null,true,1.3,64,2,179,10)).isEqualTo(POSE_CHANGED);
        assertThat(guard().check(1,5,null,true,1,64,2,175,10)).isEqualTo(POSE_CHANGED);
    }
}
