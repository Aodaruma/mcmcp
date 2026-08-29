package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Assessment;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Candidate;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.CandidateKind;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.DamageKind;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Danger;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Decision;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Evidence;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Landing;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Limits;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.Position;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.State;
import dev.aod.mcmcp.agent.safety.MinecraftRecoveryGovernor.StopSignal;
import dev.aod.mcmcp.routine.MovementInputLease;
import dev.aod.mcmcp.routine.MovementInputLease.MovementKey;
import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftRecoveryGovernorTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void closeFailureRetainsRecoveryMovementOwnerForTheNextBoundedRetry() {
        var fixture = fixture(defaultLimits());
        var lava = sample(1, 20, 300, false, false, 0, true, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var exit = candidate("exit", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.FORWARD), true, true, true, 1);
        assertThat(fixture.governor.tick(
                lava, List.of(exit), StopSignal.NONE, () -> { }, 1).state())
                .isEqualTo(State.RECOVERING);

        fixture.control.failRelease = true;
        assertThatThrownBy(fixture.governor::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery input release was not confirmed");
        assertThat(fixture.control.releases).isOne();

        fixture.control.failRelease = false;
        fixture.governor.close();
        assertThat(fixture.control.releases).isEqualTo(2);
    }

    @Test
    void consecutiveCollectNodesUseWorldTicksEvenWhenActionProgressDoesNotAdvance() {
        var fixture = fixture(defaultLimits());
        long unchangedActionProgressTick = 3L;
        var pickupA = sample(100, 20, 300, false, false, 0, false, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var pickupB = sample(101, 20, 300, false, false, 0, false, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);

        var first = fixture.governor.tick(
                pickupA, List.of(), StopSignal.NONE, () -> { }, pickupA.clientTick());
        var second = fixture.governor.tick(
                pickupB, List.of(), StopSignal.NONE, () -> { }, pickupB.clientTick());

        assertThat(unchangedActionProgressTick).isEqualTo(3L);
        assertThat(first.state()).isEqualTo(State.IDLE);
        assertThat(second.state()).isEqualTo(State.IDLE);
        assertThat(second.reason()).isNotEqualTo(MinecraftRecoveryGovernor.Reason.INTERNAL_INVARIANT);
    }

    @Test
    void lowHealthAloneReplansWithoutStoppingOrOwningInput() {
        var fixture = fixture(new Limits(200, 16, 360, 8, 8, 4));

        var result = fixture.governor.tick(
                sample(1, 2, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, fixture.events::addGoalPreempt, 1);

        assertThat(result.state()).isEqualTo(State.REPLAN_REQUIRED);
        assertThat(result.assessment()).isEqualTo(
                new Assessment(Decision.REPLAN, Set.of(Danger.LOW_HEALTH_STABLE)));
        assertThat(result.goalPreempted()).isFalse();
        assertThat(fixture.control.applied).isEmpty();
    }

    @Test
    void classifiesLavaDrowningBurningFallAndRepeatedAttackFromFreshEvidence() {
        assertRecoveryDanger(
                sample(1, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                Danger.LAVA);
        assertRecoveryDanger(
                sample(1, 20, 300, false, false, 0, false, false,
                        false, -1, 8, Landing.UNKNOWN, -1, DamageKind.NONE),
                Danger.DANGEROUS_FALL);

        var drowning = fixture(defaultLimits());
        drowning.governor.tick(
                sample(1, 20, 101, true, false, 0, false, false,
                        false, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, () -> { }, 1);
        var drowningResult = drowning.governor.tick(
                sample(2, 20, 80, true, false, 0, false, false,
                        false, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, () -> { }, 2);
        assertThat(drowningResult.assessment().dangers()).contains(Danger.DROWNING);
        assertThat(drowningResult.assessment().decision()).isEqualTo(Decision.RECOVER);

        var burning = fixture(defaultLimits());
        burning.governor.tick(
                sample(1, 20, 300, false, true, 40, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, () -> { }, 1);
        var burningResult = burning.governor.tick(
                sample(2, 19, 300, false, true, 40, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, 2, DamageKind.FIRE),
                List.of(), StopSignal.NONE, () -> { }, 2);
        assertThat(burningResult.assessment().dangers()).contains(Danger.BURNING);
        assertThat(burningResult.assessment().decision()).isEqualTo(Decision.RECOVER);

        var attacked = fixture(defaultLimits());
        attacked.governor.tick(
                sample(1, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, () -> { }, 1);
        attacked.governor.tick(
                sample(2, 18, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, 2, DamageKind.ATTACK),
                List.of(), StopSignal.NONE, () -> { }, 2);
        var attackResult = attacked.governor.tick(
                sample(3, 16, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, 3, DamageKind.ATTACK),
                List.of(), StopSignal.NONE, () -> { }, 3);
        assertThat(attackResult.assessment().dangers()).contains(Danger.UNDER_ATTACK);
        assertThat(attackResult.assessment().decision()).isEqualTo(Decision.RECOVER);
    }

    @Test
    void criticalLavaDrowningAndFallRequireTwoConsecutiveSafeTicksToRelease() {
        assertCriticalLatch(
                sample(1, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                sample(2, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                sample(3, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                Danger.LAVA);
        assertCriticalLatch(
                sample(1, 20, 80, true, false, 0, false, false,
                        false, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                sample(2, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                sample(3, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                Danger.DROWNING);
        assertCriticalLatch(
                sample(1, 20, 300, false, false, 0, false, false,
                        false, -1, 8, Landing.UNKNOWN, -1, DamageKind.NONE),
                sample(2, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                sample(3, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                Danger.DANGEROUS_FALL);
    }

    @Test
    void onlyInfiniteProtectionSuppressesAnEmergencyWithoutAVerifiedEscape() {
        var lava = sample(1, 20, 300, false, false, 0, true, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var finiteLava = fixture(defaultLimits()).governor.tick(
                withProtection(lava, 0, 200),
                List.of(), StopSignal.NONE, () -> { }, 1);
        var infiniteLava = fixture(defaultLimits()).governor.tick(
                withProtection(lava, 0, Integer.MAX_VALUE),
                List.of(), StopSignal.NONE, () -> { }, 1);

        assertThat(finiteLava.assessment().dangers()).contains(Danger.LAVA);
        assertThat(finiteLava.state()).isEqualTo(State.RECOVERING);
        assertThat(infiniteLava.assessment().dangers()).doesNotContain(Danger.LAVA);
        assertThat(infiniteLava.state()).isEqualTo(State.REPLAN_REQUIRED);

        var drowning = sample(1, 20, 80, true, false, 0, false, false,
                false, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var finiteBreathing = fixture(defaultLimits()).governor.tick(
                withProtection(drowning, 200, 0),
                List.of(), StopSignal.NONE, () -> { }, 1);
        var infiniteBreathing = fixture(defaultLimits()).governor.tick(
                withProtection(drowning, Integer.MAX_VALUE, 0),
                List.of(), StopSignal.NONE, () -> { }, 1);

        assertThat(finiteBreathing.assessment().dangers()).contains(Danger.DROWNING);
        assertThat(finiteBreathing.state()).isEqualTo(State.RECOVERING);
        assertThat(infiniteBreathing.assessment().dangers()).doesNotContain(Danger.DROWNING);
        assertThat(infiniteBreathing.state()).isEqualTo(State.IDLE);
    }

    @Test
    void repeatedUnclassifiedDamageReplansInsteadOfWaitingInRecovery() {
        var fixture = fixture(defaultLimits());
        fixture.governor.tick(
                sample(1, 20, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(), StopSignal.NONE, () -> { }, 1);
        fixture.governor.tick(
                sample(2, 18, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, 2, DamageKind.OTHER),
                List.of(), StopSignal.NONE, () -> { }, 2);

        var repeated = fixture.governor.tick(
                sample(3, 16, 300, false, false, 0, false, false,
                        true, 0, 0, Landing.KNOWN_SAFE, 3, DamageKind.OTHER),
                List.of(), StopSignal.NONE, () -> { }, 3);

        assertThat(repeated.assessment()).isEqualTo(
                new Assessment(Decision.REPLAN, Set.of(Danger.UNCLASSIFIED_DAMAGE)));
        assertThat(repeated.state()).isEqualTo(State.REPLAN_REQUIRED);
        assertThat(fixture.control.applied).isEmpty();
    }

    @Test
    void preemptsBeforeDrivingChoosesDeterministicallyAndEscStopsImmediately() {
        var fixture = fixture(defaultLimits());
        Evidence lava = sample(1, 20, 300, false, false, 0, true, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var lowerPriority = candidate("b", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.BACK), false, true, false, 1);
        var fatalAvoidance = candidate("a", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.FORWARD, MovementKey.JUMP), true, true, true, 4);

        var running = fixture.governor.tick(
                lava,
                List.of(lowerPriority, fatalAvoidance),
                StopSignal.NONE,
                fixture.events::addGoalPreempt,
                1);

        assertThat(running.state()).isEqualTo(State.RECOVERING);
        assertThat(running.candidateId()).isEqualTo("a");
        assertThat(running.goalPreempted()).isTrue();
        assertThat(fixture.events.values).startsWith("preempt", "release-all", "movement:[]");
        assertThat(fixture.control.applied.getLast())
                .containsExactlyInAnyOrder(MovementKey.FORWARD, MovementKey.JUMP);

        var stopped = fixture.governor.tick(
                sample(2, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(fatalAvoidance),
                StopSignal.ESC,
                fixture.events::addGoalPreempt,
                2);
        assertThat(stopped.state()).isEqualTo(State.STOPPED);
        assertThat(stopped.inputsReleased()).isTrue();
        assertThat(fixture.control.releases).isEqualTo(1);
        assertThat(fixture.events.values.getLast()).isEqualTo("release-all");
    }

    @Test
    void exhaustsAtTheFiniteActiveTickBudgetWithAllInputsReleased() {
        var fixture = fixture(new Limits(2, 16, 360, 8, 8, 4));
        Candidate exit = candidate("exit", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.FORWARD), true, true, true, 1);
        for (long tick = 1; tick <= 2; tick++) {
            var running = fixture.governor.tick(
                    sample(tick, 20, 300, false, false, 0, true, false,
                            true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                    List.of(exit), StopSignal.NONE, () -> { }, tick);
            assertThat(running.state()).isEqualTo(State.RECOVERING);
        }

        var exhausted = fixture.governor.tick(
                sample(3, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(exit), StopSignal.NONE, () -> { }, 3);

        assertThat(exhausted.state()).isEqualTo(State.EXHAUSTED);
        assertThat(exhausted.usage().activeTicks()).isEqualTo(2);
        assertThat(exhausted.inputsReleased()).isTrue();
        assertThat(fixture.control.releases).isEqualTo(1);
    }

    @Test
    void bindsEveryRecoveryTickToTheExactMovementGateAndAbandonsRejectedCandidate() {
        var fixture = fixture(defaultLimits());
        Evidence lava = sample(1, 20, 300, false, false, 0, true, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var first = candidate("first", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.FORWARD), true, true, true, 1);
        var fallback = candidate("fallback", CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(MovementKey.BACK), true, true, true, 2);

        var started = fixture.governor.tick(
                lava, List.of(first, fallback), StopSignal.NONE, () -> { }, 1);
        fixture.events.movementRejected = true;
        var switched = fixture.governor.tick(
                sample(2, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(first, fallback), StopSignal.NONE, () -> { }, 2);

        assertThat(started.candidateId()).isEqualTo("first");
        assertThat(switched.candidateId()).isEqualTo("fallback");
        assertThat(fixture.control.applied.getLast()).containsExactly(MovementKey.BACK);
        assertThat(fixture.events.proofs)
                .containsExactly(new Proof(7, 16), new Proof(7, 16));
    }

    @Test
    void rejectedCandidateDoesNotBlacklistAnotherTargetWithTheSameDisplayId() {
        var fixture = fixture(defaultLimits());
        Evidence lava = sample(1, 20, 300, false, false, 0, true, false,
                true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE);
        var first = candidateAt("exit", MovementKey.FORWARD, new Vec3(1, 64, 0));
        var movedTarget = candidateAt("exit", MovementKey.BACK, new Vec3(2, 64, 0));

        fixture.governor.tick(
                lava, List.of(first), StopSignal.NONE, () -> { }, 1);
        fixture.events.movementRejected = true;
        var switched = fixture.governor.tick(
                sample(2, 20, 300, false, false, 0, true, false,
                        true, 0, 0, Landing.KNOWN_SAFE, -1, DamageKind.NONE),
                List.of(movedTarget), StopSignal.NONE, () -> { }, 2);

        assertThat(switched.candidateId()).isEqualTo("exit");
        assertThat(fixture.control.applied.getLast()).containsExactly(MovementKey.BACK);
    }

    private static void assertRecoveryDanger(Evidence evidence, Danger danger) {
        var fixture = fixture(defaultLimits());
        var result = fixture.governor.tick(
                evidence, List.of(), StopSignal.NONE, () -> { }, evidence.clientTick());
        assertThat(result.assessment().decision()).isEqualTo(Decision.RECOVER);
        assertThat(result.assessment().dangers()).contains(danger);
    }

    private static void assertCriticalLatch(
            Evidence danger, Evidence firstSafe, Evidence secondSafe, Danger expected) {
        var fixture = fixture(defaultLimits());
        var started = fixture.governor.tick(
                danger, List.of(), StopSignal.NONE, () -> { }, danger.clientTick());
        var held = fixture.governor.tick(
                firstSafe, List.of(), StopSignal.NONE, () -> { }, firstSafe.clientTick());
        var released = fixture.governor.tick(
                secondSafe, List.of(), StopSignal.NONE, () -> { }, secondSafe.clientTick());

        assertThat(started.assessment().dangers()).contains(expected);
        assertThat(held.state()).isEqualTo(State.RECOVERING);
        assertThat(held.assessment().dangers()).contains(expected);
        assertThat(released.state()).isEqualTo(State.RECOVERED);
        assertThat(released.assessment().dangers()).doesNotContain(expected);
    }

    private static Limits defaultLimits() {
        return new Limits(200, 16, 360, 8, 8, 4);
    }

    private static Fixture fixture(Limits limits) {
        var control = new FakeMovementControl();
        var events = new Events(control);
        var governor = new MinecraftRecoveryGovernor(
                limits,
                (owner, now, horizon) -> MovementInputLease.acquire(control, owner, now, horizon),
                events::releaseAll,
                events::consumeMovementRejection,
                (evidence, allowance) -> events.proofs.add(
                        new Proof(evidence.worldRevision(), allowance)));
        return new Fixture(governor, control, events);
    }

    private static Evidence sample(
            long tick,
            float health,
            int air,
            boolean underwater,
            boolean onFire,
            int fireTicks,
            boolean lava,
            boolean suffocating,
            boolean onGround,
            double verticalVelocity,
            double descent,
            Landing landing,
            long damageTick,
            DamageKind damageKind) {
        return new Evidence(
                tick,
                SESSION,
                DIMENSION,
                7,
                new Position(0.5, 64, 0.5),
                health,
                0,
                air,
                underwater,
                0,
                onFire,
                fireTicks,
                0,
                lava,
                suffocating,
                onGround,
                verticalVelocity,
                descent,
                landing,
                damageTick,
                damageKind,
                false);
    }

    private static Evidence withProtection(
            Evidence source, int waterBreathingTicks, int fireResistanceTicks) {
        return new Evidence(
                source.clientTick(),
                source.worldSessionId(),
                source.dimension(),
                source.worldRevision(),
                source.position(),
                source.health(),
                source.absorption(),
                source.airSupply(),
                source.underwater(),
                waterBreathingTicks,
                source.onFire(),
                source.remainingFireTicks(),
                fireResistanceTicks,
                source.inLava(),
                source.suffocating(),
                source.onGround(),
                source.verticalVelocity(),
                source.descentSinceGround(),
                source.landing(),
                source.lastDamageObservedTick(),
                source.damageKind(),
                source.paused(),
                source.safeWaterVolume(),
                source.damagingSurface());
    }

    private static Candidate candidate(
            String id,
            CandidateKind kind,
            Set<MovementKey> movement,
            boolean preventsFatal,
            boolean reducesHarm,
            boolean stable,
            double deviation) {
        return new Candidate(
                id,
                SESSION,
                DIMENSION,
                7,
                kind,
                movement,
                true,
                true,
                preventsFatal,
                reducesHarm,
                stable,
                5,
                2,
                deviation);
    }

    private static Candidate candidateAt(String id, MovementKey movement, Vec3 target) {
        return new Candidate(
                id,
                SESSION,
                DIMENSION,
                7,
                CandidateKind.EXIT_HAZARDOUS_FLUID,
                Set.of(movement),
                new AgentInputState.RecoveryIntent(
                        AgentInputState.RecoveryMode.EXIT_LAVA, target, null),
                true,
                true,
                true,
                true,
                true,
                5,
                2,
                1);
    }

    private record Fixture(
            MinecraftRecoveryGovernor governor,
            FakeMovementControl control,
            Events events) {
    }

    private static final class Events {
        private final List<String> values = new ArrayList<>();
        private final List<Proof> proofs = new ArrayList<>();
        private final FakeMovementControl control;
        private boolean movementRejected;

        private Events(FakeMovementControl control) {
            this.control = control;
            control.events = values;
        }

        private void addGoalPreempt() {
            values.add("preempt");
        }

        private boolean releaseAll() {
            values.add("release-all");
            return true;
        }

        private boolean consumeMovementRejection() {
            boolean result = movementRejected;
            movementRejected = false;
            return result;
        }
    }

    private record Proof(long worldRevision, double allowance) {
    }

    private static final class FakeMovementControl implements MovementInputLease.MovementControl {
        private final List<Set<MovementKey>> applied = new ArrayList<>();
        private List<String> events = List.of();
        private int releases;
        private boolean failRelease;

        @Override
        public void apply(Set<MovementKey> keys) {
            applied.add(Set.copyOf(keys));
            events.add("movement:" + keys);
        }

        @Override
        public void release() {
            releases++;
            events.add("movement-release");
            if (failRelease) {
                throw new IllegalStateException("movement release failed");
            }
        }
    }
}
