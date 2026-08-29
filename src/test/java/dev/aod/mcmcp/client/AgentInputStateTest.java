package dev.aod.mcmcp.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentInputStateTest {
    @Test
    void staleLeaseIsNeutralAtTheActualInputBoundary() {
        var state = new AgentInputState();
        state.publishMovement(true, false, false, false, false, 150L);
        state.requireGoalMovementSafety(new Object(), new Object(), 1L, 2.0D);

        assertThat(state.movementSnapshot(149L).forward()).isTrue();
        assertThat(state.movementSnapshot(150L))
                .isEqualTo(new AgentInputState.MovementSnapshot(
                        true, false, false, false, false, false));
        assertThat(state.consumeGoalMovementRejection()).isTrue();
    }

    @Test
    void pauseKeepsOwnershipNeutralAndFreezesTheWatchdogClock() {
        var state = new AgentInputState();
        state.publishMovement(true, false, false, true, false);

        assertThat(state.movementSnapshot())
                .extracting(
                        AgentInputState.MovementSnapshot::owned,
                        AgentInputState.MovementSnapshot::forward,
                        AgentInputState.MovementSnapshot::right)
                .containsExactly(true, true, true);

        state.setPaused(true, 120L);
        assertThat(state.movementSnapshot())
                .isEqualTo(new AgentInputState.MovementSnapshot(
                        true, false, false, false, false, false));
        assertThat(state.watchdogTime(10_000L)).isEqualTo(120L);

        state.setPaused(false, 10_100L);
        assertThat(state.watchdogTime(10_110L)).isEqualTo(130L);
        assertThat(state.movementSnapshot().forward()).isTrue();
        assertThat(state.movementSnapshot().right()).isTrue();
    }

    @Test
    void safetySuppressionNeedsANewLeasePublishBeforeMovementCanResume() {
        var state = new AgentInputState();
        var player = new Object();
        var level = new Object();
        state.publishMovement(true, false, false, false, true);
        state.requireGoalMovementSafety(player, level, 7L, 2.5D);
        assertThat(state.goalMovementProof())
                .isEqualTo(new AgentInputState.GoalMovementProof(player, level, 7L, 2.5D));

        state.suppressMovement();
        assertThat(state.movementSnapshot())
                .isEqualTo(new AgentInputState.MovementSnapshot(
                        true, false, false, false, false, false));
        assertThat(state.goalMovementProof().required()).isFalse();

        state.publishMovement(false, false, true, false, false);
        assertThat(state.movementSnapshot().left()).isTrue();
        assertThat(state.goalMovementProof().required()).isFalse();

        state.releaseMovement();
        assertThat(state.movementSnapshot().owned()).isFalse();
        assertThat(state.goalMovementProof().required()).isFalse();
    }

    @Test
    void oneTickProofTracksOnlyAgentContributionAndConsumesDistance() {
        var state = new AgentInputState();
        var player = new Object();
        var level = new Object();
        state.publishMovement(true, false, false, false, false);
        state.requireGoalMovementSafety(player, level, 9L, 2.5D);
        state.beginPlayerMovementTick(player, level);

        state.addAgentMoveContribution(new Vec3(0.2D, 0.5D, 0.0D));
        assertThat(state.agentMoveContribution(player, level))
                .isEqualTo(new Vec3(0.2D, 0.5D, 0.0D));
        state.acceptGoalMovement(0.75D);

        assertThat(state.goalMovementProofFor(player, level).orElseThrow().distanceAllowance())
                .isEqualTo(1.75D);
        assertThat(state.agentMoveContribution(player, level))
                .isEqualTo(new Vec3(0.2D, 0.5D, 0.0D));
        state.endPlayerMovementTick(player, level);
        assertThat(state.goalMovementOutputActive()).isFalse();

        state.publishMovement(true, false, false, false, false);
        state.requireGoalMovementSafety(player, level, 9L, 2.5D);
        state.beginPlayerMovementTick(player, level);
        assertThat(state.agentMoveContribution(player, level))
                .isEqualTo(new Vec3(0.2D, 0.5D, 0.0D));
    }

    @Test
    void vanillaFrictionScalesPersistentAgentVelocityWithoutUsingNetVelocity() {
        var state = new AgentInputState();
        var player = new Object();
        var level = new Object();
        state.publishMovement(true, false, false, false, false);
        state.requireGoalMovementSafety(player, level, 1L, 1.0D);
        state.beginPlayerMovementTick(player, level);
        state.addAgentMoveContribution(new Vec3(0.1D, 0.42D, 0.0D));
        state.scaleAgentVelocity(new Vec3(0.546D, 0.98D, 0.546D));
        state.endPlayerMovementTick(player, level);

        state.publishMovement(true, false, false, false, false);
        state.requireGoalMovementSafety(player, level, 1L, 1.0D);
        state.beginPlayerMovementTick(player, level);
        var carried = state.agentMoveContribution(player, level);
        assertThat(carried.x).isCloseTo(0.0546D, org.assertj.core.data.Offset.offset(1.0E-12D));
        assertThat(carried.y).isCloseTo(0.4116D, org.assertj.core.data.Offset.offset(1.0E-12D));
        assertThat(carried.z).isZero();

        state.discardTrackedAgentVelocity();
        assertThat(state.agentMoveContribution(player, level)).isEqualTo(Vec3.ZERO);
        assertThat(state.goalMovementOutputActive()).isFalse();
        assertThat(state.consumeGoalMovementRejection()).isTrue();
    }

    @Test
    void attackUsesTheSamePauseAndSafetySuppressionBoundary() {
        var state = new AgentInputState();
        state.publishAttack();
        assertThat(state.attackActive()).isTrue();

        state.setPaused(true, 10L);
        assertThat(state.attackActive()).isFalse();
        state.setPaused(false, 20L);
        assertThat(state.attackActive()).isTrue();

        state.suppressAll();
        assertThat(state.attackActive()).isFalse();
        state.publishAttack();
        assertThat(state.attackActive()).isTrue();
        state.releaseAttack();
        assertThat(state.attackActive()).isFalse();
    }

    @Test
    void terminalOwnerNoneIsMeasuredAcrossMovementAttackProofAndTrackedVelocity() {
        var state = new AgentInputState();
        assertThat(state.inputOwnershipSnapshot().ownerNone()).isTrue();

        state.publishMovement(true, false, false, false, false);
        assertThat(state.inputOwnerNone()).isFalse();
        state.releaseMovement();
        assertThat(state.inputOwnerNone()).isTrue();

        state.publishAttack();
        assertThat(state.inputOwnershipSnapshot().attackOwned()).isTrue();
        state.releaseAttack();
        assertThat(state.inputOwnerNone()).isTrue();

        var player = new Object();
        var level = new Object();
        state.publishMovement(true, false, false, false, false);
        state.requireGoalMovementSafety(player, level, 1L, 1.0D);
        state.beginPlayerMovementTick(player, level);
        state.addAgentMoveContribution(new Vec3(0.25D, 0.0D, 0.0D));
        state.endPlayerMovementTick(player, level);
        state.suppressAllRetainingTrackedVelocity();
        state.releaseMovement();

        assertThat(state.inputOwnershipSnapshot().velocityTracked()).isTrue();
        assertThat(state.inputOwnerNone()).isFalse();
        state.discardTrackedAgentVelocity();
        assertThat(state.inputOwnerNone()).isTrue();
    }
}
