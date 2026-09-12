package dev.aod.mcmcp.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentInputStateTest {
    @Test
    void repeatedTargetGapsDoNotLatchOrChargeContinuationTicks() {
        var state = new AgentInputState();
        var target = new java.util.concurrent.atomic.AtomicReference<>(AgentInputState.BoundedDispatchDecision.ALLOW);
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        state.setRepeatingBoundedDispatchGuard(target::get, () -> starts.incrementAndGet() <= 3);
        state.publishUse(Long.MAX_VALUE);
        assertThat(state.maintainsBoundedUse()).isFalse(); // No Agent-started use yet.
        for (int repetition = 1; repetition <= 3; repetition++) {
            assertThat(state.beginBoundedInput()).isTrue();
            for (int tick = 0; tick < 10; tick++) {
                assertThat(state.allowsBoundedDispatch()).isTrue();
                assertThat(state.maintainsBoundedUse()).isTrue();
            }
            target.set(AgentInputState.BoundedDispatchDecision.WAIT);
            assertThat(state.beginBoundedInput()).isFalse();
            assertThat(state.boundedDispatchRejected()).isFalse();
            assertThat(state.maintainsBoundedUse()).isTrue();
            assertThat(starts.get()).isEqualTo(repetition);
            target.set(AgentInputState.BoundedDispatchDecision.ALLOW);
        }
        assertThat(state.beginBoundedInput()).isFalse();
        assertThat(state.boundedBudgetExhausted()).isTrue();
        assertThat(state.maintainsBoundedUse()).isFalse();
        state.publishUse(Long.MAX_VALUE);
        assertThat(state.beginBoundedInput()).isFalse();
        assertThat(starts.get()).isEqualTo(4);
        state.releaseUse();
        state.clearBoundedDispatchGuard();
        assertThat(state.maintainsBoundedUse()).isFalse();
        assertThat(state.boundedBudgetExhausted()).isFalse();
    }

    @Test
    void repeatedAttackWaitsButSafetyFailureCannotBeRepublishedAway() {
        var state = new AgentInputState();
        var target = new java.util.concurrent.atomic.AtomicReference<>(AgentInputState.BoundedDispatchDecision.WAIT);
        state.setRepeatingBoundedDispatchGuard(target::get, () -> true);
        state.publishAttack(Long.MAX_VALUE);
        assertThat(state.allowsBoundedDispatch()).isFalse();
        assertThat(state.attackActive()).isTrue();
        target.set(AgentInputState.BoundedDispatchDecision.ALLOW);
        assertThat(state.beginBoundedInput()).isTrue();
        target.set(AgentInputState.BoundedDispatchDecision.STOP);
        assertThat(state.allowsBoundedDispatch()).isFalse();
        assertThat(state.attackActive()).isFalse();
        target.set(AgentInputState.BoundedDispatchDecision.ALLOW);
        state.publishAttack(Long.MAX_VALUE);
        assertThat(state.beginBoundedInput()).isFalse();
        assertThat(state.boundedDispatchRejected()).isTrue();
    }

    @Test
    void pauseAndWatchdogDoNotPreserveAnOngoingUse() {
        var state = new AgentInputState();
        state.setRepeatingBoundedDispatchGuard(() -> AgentInputState.BoundedDispatchDecision.ALLOW, () -> true);
        state.publishUse(Long.MAX_VALUE);
        assertThat(state.beginBoundedInput()).isTrue();
        state.setPaused(true, 100L);
        assertThat(state.maintainsBoundedUse()).isFalse();
        state.setPaused(false, 200L);
        assertThat(state.maintainsBoundedUse()).isTrue();
        state.publishUse(1L);
        assertThat(state.maintainsBoundedUse()).isFalse();
    }

    @Test
    void brokenRepetitionAccountingSuppressesInput() {
        var state = new AgentInputState();
        state.publishUse(Long.MAX_VALUE);
        state.setRepeatingBoundedDispatchGuard(() -> AgentInputState.BoundedDispatchDecision.ALLOW,
                () -> { throw new IllegalStateException(); });
        assertThat(state.beginBoundedInput()).isFalse();
        assertThat(state.useActive()).isFalse();
        assertThat(state.boundedDispatchRejected()).isTrue();
    }

    @Test
    void changedFocusBetweenPublicationAndDispatchLatchesUntilCleanup() {
        var state = new AgentInputState();
        var matches = new java.util.concurrent.atomic.AtomicBoolean(true);
        state.setBoundedDispatchGuard(matches::get);
        state.publishAttack();
        assertThat(state.allowsBoundedDispatch()).isTrue();
        matches.set(false);
        assertThat(state.allowsBoundedDispatch()).isFalse();
        assertThat(state.attackActive()).isFalse();
        matches.set(true);
        state.publishAttack();
        assertThat(state.allowsBoundedDispatch()).isFalse();
        assertThat(state.boundedDispatchRejected()).isTrue();
        state.releaseAttack();
        state.clearBoundedDispatchGuard();
        assertThat(state.boundedDispatchRejected()).isFalse();
        assertThat(state.allowsBoundedDispatch()).isTrue();
    }

    @Test
    void failedDispatchProofSuppressesUseInsteadOfSendingInput() {
        var state = new AgentInputState();
        state.publishUse(Long.MAX_VALUE);
        state.setBoundedDispatchGuard(() -> { throw new IllegalStateException(); });
        assertThat(state.allowsBoundedDispatch()).isFalse();
        assertThat(state.useActive()).isFalse();
        assertThat(state.boundedDispatchRejected()).isTrue();
    }

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
    void boundedAttackAndUseExpireNeutralButRetainOwnershipUntilClosed() {
        var state = new AgentInputState();
        state.publishAttack(100L);
        state.publishUse(100L);

        assertThat(state.attackActive()).isFalse();
        assertThat(state.useActive()).isFalse();
        assertThat(state.inputOwnershipSnapshot().attackOwned()).isTrue();
        assertThat(state.inputOwnershipSnapshot().useOwned()).isTrue();

        state.releaseAttack();
        state.releaseUse();
        assertThat(state.inputOwnerNone()).isTrue();
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

        state.publishUse(Long.MAX_VALUE);
        assertThat(state.inputOwnershipSnapshot().useOwned()).isTrue();
        state.releaseUse();
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
