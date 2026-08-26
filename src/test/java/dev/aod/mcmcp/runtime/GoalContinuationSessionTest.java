package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalContinuationSessionTest {
    @Test
    void boundsContinuationChainWithoutChargingReplayOrAnotherWorld() {
        var session = new GoalContinuationSession();
        var world = UUID.randomUUID();
        session.reset(world);

        for (int index = 0; index < GoalContinuationSession.MAX_CONTINUED_ROUTINES; index++) {
            assertThat(session.canAdmit(world, GoalContinuationSession.CONTINUE_GOAL)).isTrue();
            session.remember(
                    world, UUID.randomUUID(), false, GoalContinuationSession.CONTINUE_GOAL);
        }
        assertThat(session.canAdmit(world, GoalContinuationSession.CONTINUE_GOAL)).isFalse();

        session.remember(world, UUID.randomUUID(), true, GoalContinuationSession.CONTINUE_GOAL);
        assertThat(session.remainingContinuations(world)).isZero();

        var nextWorld = UUID.randomUUID();
        assertThat(session.canAdmit(nextWorld, GoalContinuationSession.CONTINUE_GOAL)).isTrue();
        assertThat(session.remainingContinuations(nextWorld))
                .isEqualTo(GoalContinuationSession.MAX_CONTINUED_ROUTINES);
        assertThatThrownBy(() -> session.canAdmit(nextWorld, "free_form"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
