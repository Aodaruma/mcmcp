package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import dev.aod.mcmcp.routine.MovementInputLease.MovementKey;
import dev.aod.mcmcp.safety.InputReleaseController;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/**
 * Finite, client-local recovery controller which preempts Goal input and owns only movement/jump.
 *
 * <p>The caller supplies already classified Local Observation Volume candidates. This class never
 * fills an evidence gap by reading hidden blocks or entities. Item use, placement, breaking and
 * attack deliberately remain unavailable until each has a dedicated owned channel and its stated
 * GameTest gate.</p>
 */
public final class MinecraftRecoveryGovernor implements AutoCloseable {
    private static final Duration LEASE_HORIZON = Duration.ofMillis(500);
    private static final int ATTACK_WINDOW_TICKS = 40;
    private static final int STALL_TICKS = 20;
    private static final int SAFETY_MARGIN_TICKS = 20;
    private static final int LOW_AIR_TICKS = 100;
    private static final double PROGRESS_EPSILON = 0.03D;
    private static final int CRITICAL_RELEASE_TICKS = 2;

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt((Candidate candidate) -> candidate.preventsFatalHarm() ? 0 : 1)
            .thenComparingInt(candidate -> candidate.reducesActiveHarm() ? 0 : 1)
            .thenComparingInt(candidate -> candidate.reachesStableState() ? 0 : 1)
            .thenComparingDouble(Candidate::goalDeviation)
            .thenComparing(Candidate::id);

    private final Limits limits;
    private final LeaseFactory leaseFactory;
    private final BooleanSupplier releaseAll;
    private final BooleanSupplier movementRejected;
    private final MovementSafetyProof requireMovementSafety;
    private final UUID ownerId = UUID.randomUUID();
    private final ArrayDeque<Long> attackDamageTicks = new ArrayDeque<>();
    private final ArrayDeque<Long> otherDamageTicks = new ArrayDeque<>();
    private final Set<CandidateKey> attempted = new HashSet<>();
    private final EnumSet<Danger> criticalLatches = EnumSet.noneOf(Danger.class);
    private final EnumMap<Danger, Integer> criticalSafeTicks = new EnumMap<>(Danger.class);

    private MovementInputLease movement;
    private Evidence previous;
    private UUID activeSession;
    private String activeDimension;
    private Candidate activeCandidate;
    private Position lastPosition;
    private long lastProgressTick;
    private int activeTicks;
    private double distance;
    private boolean recovering;

    public MinecraftRecoveryGovernor(Minecraft minecraft) {
        this(
                Limits.fromClientConfig(),
                (owner, now, horizon) -> MovementInputLease.acquire(
                        Objects.requireNonNull(minecraft, "minecraft"), owner, now, horizon),
                () -> new InputReleaseController().releaseAll(minecraft),
                () -> AgentInputState.global().consumeGoalMovementRejection(),
                (evidence, candidate, allowance) -> requireMovementSafety(
                        minecraft, evidence, candidate, allowance));
    }

    MinecraftRecoveryGovernor(
            Limits limits,
            LeaseFactory leaseFactory,
            BooleanSupplier releaseAll) {
        this(limits, leaseFactory, releaseAll, () -> false, (evidence, allowance) -> { });
    }

    MinecraftRecoveryGovernor(
            Limits limits,
            LeaseFactory leaseFactory,
            BooleanSupplier releaseAll,
            BooleanSupplier movementRejected,
            BiConsumer<Evidence, Double> requireMovementSafety) {
        this(
                limits,
                leaseFactory,
                releaseAll,
                movementRejected,
                (evidence, candidate, allowance) -> requireMovementSafety.accept(
                        evidence, allowance));
    }

    MinecraftRecoveryGovernor(
            Limits limits,
            LeaseFactory leaseFactory,
            BooleanSupplier releaseAll,
            BooleanSupplier movementRejected,
            MovementSafetyProof requireMovementSafety) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.leaseFactory = Objects.requireNonNull(leaseFactory, "leaseFactory");
        this.releaseAll = Objects.requireNonNull(releaseAll, "releaseAll");
        this.movementRejected = Objects.requireNonNull(movementRejected, "movementRejected");
        this.requireMovementSafety = Objects.requireNonNull(
                requireMovementSafety, "requireMovementSafety");
    }

    /**
     * Evaluates one active client tick. {@code preemptAndDiscardGoal} is invoked before recovery
     * output, and for every STOP signal before inputs are released.
     */
    public TickResult tick(
            Evidence evidence,
            List<Candidate> candidates,
            StopSignal stopSignal,
            Runnable preemptAndDiscardGoal,
            long nowNanos) {
        Objects.requireNonNull(evidence, "evidence");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Objects.requireNonNull(stopSignal, "stopSignal");
        Objects.requireNonNull(preemptAndDiscardGoal, "preemptAndDiscardGoal");

        if (stopSignal != StopSignal.NONE) {
            return stop(
                    preemptAndDiscardGoal,
                    stopSignal == StopSignal.ESC ? Reason.ESC : Reason.OFF,
                    emptyAssessment());
        }
        boolean sameWorld = sameWorld(previous, evidence);
        if (sameWorld && evidence.clientTick() <= previous.clientTick()) {
            return stop(preemptAndDiscardGoal, Reason.INTERNAL_INVARIANT, emptyAssessment());
        }
        if (recovering && (!activeSession.equals(evidence.worldSessionId())
                || !activeDimension.equals(evidence.dimension()))) {
            return stop(preemptAndDiscardGoal, Reason.CONTROL_TARGET_LOST, emptyAssessment());
        }

        DamageProgress damage = observe(evidence);
        Assessment assessment = assess(evidence, candidates, damage);
        if (assessment.decision() == Decision.RECOVER) {
            latchCriticalDangers(assessment);
        }
        if (recovering || !criticalLatches.isEmpty()) {
            assessment = enforceCriticalLatches(assessment, evidence, damage);
        }

        if (!recovering && assessment.decision() != Decision.RECOVER) {
            return result(
                    assessment.decision() == Decision.REPLAN
                            ? State.REPLAN_REQUIRED : State.IDLE,
                    assessment,
                    Reason.NONE,
                    null,
                    false,
                    false);
        }

        boolean preempted = false;
        if (!recovering) {
            try {
                preemptAndDiscardGoal.run();
                if (!releaseInputs()) {
                    TickResult stopped = result(
                            State.STOPPED,
                            assessment,
                            Reason.INPUT_RELEASE_FAILED,
                            null,
                            true,
                            false);
                    clearRecovery();
                    return stopped;
                }
                recovering = true;
                activeSession = evidence.worldSessionId();
                activeDimension = evidence.dimension();
                lastPosition = evidence.position();
                lastProgressTick = evidence.clientTick();
                movement = leaseFactory.acquire(ownerId, nowNanos, LEASE_HORIZON);
            } catch (RuntimeException | LinkageError failure) {
                releaseInputs();
                clearRecovery();
                throw failure;
            }
            preempted = true;
        }

        if (evidence.paused()) {
            return result(
                    State.PAUSED,
                    assessment,
                    Reason.NONE,
                    activeCandidate == null ? null : activeCandidate.id(),
                    preempted,
                    false);
        }

        accountMovement(evidence);
        if (!activeDanger(assessment)) {
            return terminal(State.RECOVERED, assessment, Reason.SAFER_STATE_REACHED, preempted);
        }
        if (activeTicks >= limits.maxTicks() || distance >= limits.maxDistance()) {
            return terminal(State.EXHAUSTED, assessment, Reason.RECOVERY_BUDGET_EXHAUSTED, preempted);
        }
        activeTicks++;

        if (movementRejected.getAsBoolean() && activeCandidate != null) {
            attempted.add(activeCandidate.key());
            activeCandidate = null;
        }
        Candidate selected = selectCandidate(evidence, candidates, assessment);
        Set<MovementKey> desired = selected == null ? Set.of() : selected.movement();
        movement.setDesired(ownerId, desired);
        if (!movement.heartbeat(ownerId, nowNanos, LEASE_HORIZON)) {
            movement = null;
            return terminal(State.EXHAUSTED, assessment, Reason.INPUT_LEASE_EXPIRED, preempted);
        }
        if (selected != null) {
            requireMovementSafety.require(
                    evidence,
                    selected,
                    Math.max(0.0D, limits.maxDistance() - distance));
        }
        return result(
                State.RECOVERING,
                assessment,
                selected == null ? Reason.WAITING_FOR_VERIFIED_CANDIDATE : Reason.NONE,
                selected == null ? null : selected.id(),
                preempted,
                false);
    }

    private static void requireMovementSafety(
            Minecraft minecraft, Evidence evidence, Candidate candidate, double allowance) {
        var player = Objects.requireNonNull(minecraft.player, "local player");
        AgentInputState.global().requireRecoveryMovementSafety(
                player,
                player.level(),
                evidence.worldRevision(),
                allowance,
                candidate.intent());
    }

    public boolean recovering() {
        return recovering;
    }

    public Usage usage() {
        return new Usage(activeTicks, distance, 0, 0, 0, 0);
    }

    private DamageProgress observe(Evidence evidence) {
        boolean sameWorld = sameWorld(previous, evidence);
        if (previous != null && !sameWorld) {
            attackDamageTicks.clear();
            otherDamageTicks.clear();
        }
        float healthDelta = sameWorld
                ? evidence.effectiveHealth() - previous.effectiveHealth() : 0.0F;
        int airDelta = sameWorld ? evidence.airSupply() - previous.airSupply() : 0;
        boolean freshDamage = sameWorld
                && healthDelta < -0.001F
                && evidence.lastDamageObservedTick() == evidence.clientTick();
        if (freshDamage) {
            (evidence.damageKind() == DamageKind.ATTACK
                    ? attackDamageTicks : otherDamageTicks).addLast(evidence.clientTick());
        }
        prune(attackDamageTicks, evidence.clientTick());
        prune(otherDamageTicks, evidence.clientTick());
        previous = evidence;
        return new DamageProgress(freshDamage, airDelta);
    }

    private static boolean sameWorld(Evidence previous, Evidence current) {
        return previous != null
                && previous.worldSessionId().equals(current.worldSessionId())
                && previous.dimension().equals(current.dimension());
    }

    private static void prune(ArrayDeque<Long> ticks, long now) {
        while (!ticks.isEmpty() && now - ticks.getFirst() > ATTACK_WINDOW_TICKS) {
            ticks.removeFirst();
        }
    }

    private Assessment assess(
            Evidence evidence,
            List<Candidate> candidates,
            DamageProgress damage) {
        var dangers = EnumSet.noneOf(Danger.class);
        int breathingEscapeTicks = candidates.stream()
                .filter(candidate -> candidate.currentFor(evidence)
                        && candidate.knownNonWorsening()
                        && candidate.reachesStableState()
                        && candidate.intent().mode()
                        == AgentInputState.RecoveryMode.REACH_BREATHING_SPACE)
                .mapToInt(Candidate::estimatedTicks)
                .min()
                .orElse(Integer.MAX_VALUE);
        int lavaEscapeTicks = candidates.stream()
                .filter(candidate -> candidate.currentFor(evidence)
                        && candidate.knownNonWorsening()
                        && candidate.reachesStableState()
                        && (candidate.intent().mode() == AgentInputState.RecoveryMode.EXIT_LAVA
                                || candidate.intent().mode()
                                == AgentInputState.RecoveryMode.CONTINUE_LAVA_ESCAPE))
                .mapToInt(Candidate::estimatedTicks)
                .min()
                .orElse(Integer.MAX_VALUE);

        if (evidence.inLava()
                && !protectionCoversEscape(evidence.fireResistanceTicks(), lavaEscapeTicks)) {
            dangers.add(Danger.LAVA);
        }

        boolean airFalling = damage.airDelta() < 0;
        boolean breathingProtectionSufficient = protectionCoversEscape(
                evidence.waterBreathingTicks(), breathingEscapeTicks);
        if (evidence.underwater() && !breathingProtectionSufficient
                && (evidence.airSupply() <= LOW_AIR_TICKS
                        || airFalling && airHarmPrecedesEscape(
                                evidence, breathingEscapeTicks, damage.airDelta()))) {
            dangers.add(Danger.DROWNING);
        }
        if (evidence.suffocating()) {
            dangers.add(Danger.SUFFOCATION);
        }
        if (dangerousFall(evidence)) {
            dangers.add(Danger.DANGEROUS_FALL);
        }
        if (attackDamageTicks.size() >= 2) {
            dangers.add(Danger.UNDER_ATTACK);
        }
        if (otherDamageTicks.size() >= 2) {
            dangers.add(Danger.UNCLASSIFIED_DAMAGE);
        }
        if (evidence.damagingSurface()) {
            dangers.add(Danger.ACTIVE_DAMAGE);
        }
        if (evidence.onFire()
                && evidence.fireResistanceTicks() != Integer.MAX_VALUE
                && (long) evidence.fireResistanceTicks()
                        < (long) evidence.remainingFireTicks() + SAFETY_MARGIN_TICKS) {
            dangers.add(Danger.BURNING);
        }
        if (evidence.effectiveHealth() <= 6.0F && dangers.isEmpty()) {
            dangers.add(Danger.LOW_HEALTH_STABLE);
        }

        boolean recover = dangers.contains(Danger.LAVA)
                || dangers.contains(Danger.DROWNING)
                || dangers.contains(Danger.SUFFOCATION)
                || dangers.contains(Danger.DANGEROUS_FALL)
                || dangers.contains(Danger.UNDER_ATTACK)
                || dangers.contains(Danger.ACTIVE_DAMAGE)
                || dangers.contains(Danger.BURNING)
                        && (damage.freshDamage()
                                || evidence.remainingFireTicks() > 80
                                || evidence.effectiveHealth() <= 6.0F);
        boolean replan = !recover && (!dangers.isEmpty()
                || damage.freshDamage()
                || evidence.onFire()
                || evidence.inLava()
                || evidence.underwater() && airFalling);
        return new Assessment(
                recover ? Decision.RECOVER : replan ? Decision.REPLAN : Decision.CONTINUE,
                dangers);
    }

    private void latchCriticalDangers(Assessment assessment) {
        if (assessment.dangers().contains(Danger.LAVA)) criticalLatches.add(Danger.LAVA);
        if (assessment.dangers().contains(Danger.DROWNING)) criticalLatches.add(Danger.DROWNING);
        if (assessment.dangers().contains(Danger.DANGEROUS_FALL)) {
            criticalLatches.add(Danger.DANGEROUS_FALL);
        }
    }

    private Assessment enforceCriticalLatches(
            Assessment assessment, Evidence evidence, DamageProgress damage) {
        for (var danger : EnumSet.copyOf(criticalLatches)) {
            if (!criticalDangerResolved(danger, evidence, damage)) {
                criticalSafeTicks.remove(danger);
                continue;
            }
            int safeTicks = criticalSafeTicks.merge(danger, 1, Integer::sum);
            if (safeTicks >= CRITICAL_RELEASE_TICKS) {
                criticalLatches.remove(danger);
                criticalSafeTicks.remove(danger);
            }
        }
        var dangers = assessment.dangers().isEmpty()
                ? EnumSet.noneOf(Danger.class)
                : EnumSet.copyOf(assessment.dangers());
        dangers.addAll(criticalLatches);
        return new Assessment(
                criticalLatches.isEmpty() ? assessment.decision() : Decision.RECOVER,
                dangers);
    }

    private static boolean criticalDangerResolved(
            Danger danger, Evidence evidence, DamageProgress damage) {
        return switch (danger) {
            case LAVA -> !evidence.inLava()
                    && (evidence.safeWaterVolume() && evidence.landing() != Landing.KNOWN_LAVA
                            || evidence.onGround() && evidence.landing() == Landing.KNOWN_SAFE);
            case DROWNING -> !evidence.underwater()
                    || evidence.waterBreathingTicks() > SAFETY_MARGIN_TICKS
                    || evidence.airSupply() > LOW_AIR_TICKS && damage.airDelta() >= 0;
            case DANGEROUS_FALL -> evidence.safeWaterVolume()
                    && !evidence.inLava()
                    && evidence.landing() != Landing.KNOWN_LAVA
                    || evidence.onGround() && evidence.landing() == Landing.KNOWN_SAFE;
            default -> true;
        };
    }

    private static boolean protectionCoversEscape(int protectionTicks, int escapeTicks) {
        return protectionTicks == Integer.MAX_VALUE
                || escapeTicks != Integer.MAX_VALUE
                && (long) protectionTicks >= (long) escapeTicks + SAFETY_MARGIN_TICKS;
    }

    private static boolean airHarmPrecedesEscape(
            Evidence evidence, int saferTicks, int airDelta) {
        if (airDelta >= 0 || saferTicks == Integer.MAX_VALUE) {
            return evidence.airSupply() <= LOW_AIR_TICKS;
        }
        int ticksToNoAir = evidence.airSupply() / -airDelta;
        return ticksToNoAir <= saferTicks + SAFETY_MARGIN_TICKS;
    }

    private static boolean dangerousFall(Evidence evidence) {
        if (evidence.onGround() || evidence.verticalVelocity() >= -0.08D
                || evidence.descentSinceGround() <= 3.0D) {
            return false;
        }
        double conservativeDamage = evidence.descentSinceGround() - 3.0D;
        return evidence.landing() != Landing.KNOWN_SAFE
                || conservativeDamage + 2.0D >= evidence.effectiveHealth();
    }

    private static boolean activeDanger(Assessment assessment) {
        return assessment.dangers().stream().anyMatch(danger ->
                danger != Danger.LOW_HEALTH_STABLE
                        && danger != Danger.UNCLASSIFIED_DAMAGE);
    }

    private Candidate selectCandidate(
            Evidence evidence,
            List<Candidate> candidates,
            Assessment assessment) {
        if (activeCandidate != null) {
            Candidate refreshed = candidates.stream()
                    .filter(candidate -> candidate.key().equals(activeCandidate.key()))
                    .filter(candidate -> usable(candidate, evidence, assessment, false))
                    .findFirst()
                    .orElse(null);
            if (refreshed != null && evidence.clientTick() - lastProgressTick < STALL_TICKS) {
                activeCandidate = refreshed;
                return refreshed;
            }
            attempted.add(activeCandidate.key());
            activeCandidate = null;
        }

        double remainingDistance = limits.maxDistance() - distance;
        int remainingTicks = limits.maxTicks() - activeTicks;
        activeCandidate = candidates.stream()
                .filter(candidate -> !attempted.contains(candidate.key()))
                .filter(candidate -> usable(candidate, evidence, assessment, true))
                .filter(candidate -> candidate.estimatedDistance() <= remainingDistance)
                .filter(candidate -> candidate.estimatedTicks() <= remainingTicks)
                .sorted(CANDIDATE_ORDER)
                .findFirst()
                .orElse(null);
        if (activeCandidate != null) {
            lastProgressTick = evidence.clientTick();
        }
        return activeCandidate;
    }

    private static boolean usable(
            Candidate candidate,
            Evidence evidence,
            Assessment assessment,
            boolean requireAdmissionEstimate) {
        if (!candidate.currentFor(evidence)
                || !candidate.collisionFree()
                || !candidate.knownNonWorsening()) {
            return false;
        }
        if (requireAdmissionEstimate
                && (candidate.estimatedTicks() <= 0 || candidate.estimatedDistance() <= 0.0D)) {
            return false;
        }
        return switch (candidate.kind()) {
            case REACH_BREATHING_SPACE -> assessment.dangers().contains(Danger.DROWNING);
            case EXIT_HAZARDOUS_FLUID -> assessment.dangers().contains(Danger.LAVA)
                    || assessment.dangers().contains(Danger.BURNING);
            case STEER_TO_KNOWN_LANDING -> assessment.dangers().contains(Danger.DANGEROUS_FALL);
            case BACK_TO_FREE_AABB -> assessment.dangers().contains(Danger.SUFFOCATION);
            case TAKE_COVER -> assessment.dangers().contains(Danger.UNDER_ATTACK);
            case RETREAT_FROM_THREAT -> assessment.dangers().contains(Danger.UNDER_ATTACK);
            case RETREAT_TO_KNOWN_SAFE -> true;
        };
    }

    private void accountMovement(Evidence evidence) {
        Position current = evidence.position();
        double horizontal = Math.hypot(current.x() - lastPosition.x(), current.z() - lastPosition.z());
        double controlledVertical = Math.max(0.0D, current.y() - lastPosition.y());
        double moved = horizontal + controlledVertical;
        distance += moved;
        if (moved >= PROGRESS_EPSILON) {
            lastProgressTick = evidence.clientTick();
        }
        lastPosition = current;
    }

    private TickResult stop(Runnable discardGoal, Reason reason, Assessment assessment) {
        try {
            discardGoal.run();
        } catch (RuntimeException | LinkageError failure) {
            releaseInputs();
            clearRecovery();
            throw failure;
        }
        boolean released = releaseInputs();
        TickResult result = result(State.STOPPED, assessment, reason, null, true, released);
        clearRecovery();
        return result;
    }

    private TickResult terminal(
            State state,
            Assessment assessment,
            Reason reason,
        boolean preempted) {
        boolean released = releaseInputs();
        TickResult result = result(state, assessment, reason, null, preempted, released);
        clearRecovery();
        return result;
    }

    private boolean releaseInputs() {
        boolean released = true;
        try {
            if (movement != null) {
                movement.close(ownerId);
            }
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        } finally {
            movement = null;
            try {
                released &= releaseAll.getAsBoolean();
            } catch (RuntimeException | LinkageError failure) {
                released = false;
            }
        }
        return released;
    }

    private void clearRecovery() {
        recovering = false;
        activeSession = null;
        activeDimension = null;
        activeCandidate = null;
        lastPosition = null;
        attempted.clear();
        criticalLatches.clear();
        criticalSafeTicks.clear();
        activeTicks = 0;
        distance = 0.0D;
    }

    @Override
    public void close() {
        releaseInputs();
        clearRecovery();
    }

    private TickResult result(
            State state,
            Assessment assessment,
            Reason reason,
            String candidateId,
            boolean goalPreempted,
            boolean inputsReleased) {
        return new TickResult(
                state,
                assessment,
                reason,
                candidateId,
                usage(),
                goalPreempted,
                inputsReleased);
    }

    private static Assessment emptyAssessment() {
        return new Assessment(Decision.STOP, Set.of());
    }

    @FunctionalInterface
    interface LeaseFactory {
        MovementInputLease acquire(UUID ownerId, long nowNanos, Duration horizon);
    }

    @FunctionalInterface
    interface MovementSafetyProof {
        void require(Evidence evidence, Candidate candidate, double allowance);
    }

    public record Limits(
            int maxTicks,
            double maxDistance,
            double maxCameraDegrees,
            int maxInteractions,
            int maxPlacements,
            int maxBreaks) {
        public Limits {
            if (maxTicks <= 0 || !Double.isFinite(maxDistance) || maxDistance <= 0.0D
                    || !Double.isFinite(maxCameraDegrees) || maxCameraDegrees < 0.0D
                    || maxInteractions < 0 || maxPlacements < 0 || maxBreaks < 0) {
                throw new IllegalArgumentException("recovery limits must be finite and non-negative");
            }
        }

        public static Limits fromClientConfig() {
            return new Limits(
                    McmcpClientConfig.recoveryMaxTicks(),
                    McmcpClientConfig.recoveryMaxDistance(),
                    McmcpClientConfig.recoveryMaxCameraDegrees(),
                    McmcpClientConfig.recoveryMaxInteractions(),
                    McmcpClientConfig.recoveryMaxPlacements(),
                    McmcpClientConfig.recoveryMaxBreaks());
        }
    }

    public record Evidence(
            long clientTick,
            UUID worldSessionId,
            String dimension,
            long worldRevision,
            Position position,
            float health,
            float absorption,
            int airSupply,
            boolean underwater,
            int waterBreathingTicks,
            boolean onFire,
            int remainingFireTicks,
            int fireResistanceTicks,
            boolean inLava,
            boolean suffocating,
            boolean onGround,
            double verticalVelocity,
            double descentSinceGround,
            Landing landing,
            long lastDamageObservedTick,
            DamageKind damageKind,
            boolean paused,
            boolean safeWaterVolume,
            boolean damagingSurface) {
        public Evidence(
                long clientTick,
                UUID worldSessionId,
                String dimension,
                long worldRevision,
                Position position,
                float health,
                float absorption,
                int airSupply,
                boolean underwater,
                int waterBreathingTicks,
                boolean onFire,
                int remainingFireTicks,
                int fireResistanceTicks,
                boolean inLava,
                boolean suffocating,
                boolean onGround,
                double verticalVelocity,
                double descentSinceGround,
                Landing landing,
                long lastDamageObservedTick,
                DamageKind damageKind,
                boolean paused) {
            this(
                    clientTick,
                    worldSessionId,
                    dimension,
                    worldRevision,
                    position,
                    health,
                    absorption,
                    airSupply,
                    underwater,
                    waterBreathingTicks,
                    onFire,
                    remainingFireTicks,
                    fireResistanceTicks,
                    inLava,
                    suffocating,
                    onGround,
                    verticalVelocity,
                    descentSinceGround,
                    landing,
                    lastDamageObservedTick,
                    damageKind,
                    paused,
                    false,
                    false);
        }

        public Evidence {
            if (clientTick < 0L || worldRevision < 0L) {
                throw new IllegalArgumentException("tick and revision must be non-negative");
            }
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (Objects.requireNonNull(dimension, "dimension").isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(landing, "landing");
            Objects.requireNonNull(damageKind, "damageKind");
            if (!Float.isFinite(health) || health < 0.0F
                    || !Float.isFinite(absorption) || absorption < 0.0F
                    || airSupply < -20 || waterBreathingTicks < 0
                    || remainingFireTicks < 0 || fireResistanceTicks < 0
                    || !Double.isFinite(verticalVelocity)
                    || !Double.isFinite(descentSinceGround) || descentSinceGround < 0.0D
                    || lastDamageObservedTick < -1L
                    || lastDamageObservedTick > clientTick) {
                throw new IllegalArgumentException("invalid recovery evidence");
            }
        }

        public float effectiveHealth() {
            return health + absorption;
        }
    }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("position coordinates must be finite");
            }
        }
    }

    public record Candidate(
            String id,
            UUID worldSessionId,
            String dimension,
            long worldRevision,
            CandidateKind kind,
            Set<MovementKey> movement,
            AgentInputState.RecoveryIntent intent,
            boolean collisionFree,
            boolean knownNonWorsening,
            boolean preventsFatalHarm,
            boolean reducesActiveHarm,
            boolean reachesStableState,
            int estimatedTicks,
            double estimatedDistance,
            double goalDeviation) {
        public Candidate {
            if (Objects.requireNonNull(id, "id").isBlank() || id.length() > 64) {
                throw new IllegalArgumentException("candidate id must contain 1..64 characters");
            }
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (Objects.requireNonNull(dimension, "dimension").isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(intent, "intent");
            var keys = Objects.requireNonNull(movement, "movement").isEmpty()
                    ? EnumSet.noneOf(MovementKey.class) : EnumSet.copyOf(movement);
            if (keys.isEmpty()
                    || keys.contains(MovementKey.FORWARD) && keys.contains(MovementKey.BACK)
                    || keys.contains(MovementKey.LEFT) && keys.contains(MovementKey.RIGHT)
                    || worldRevision < 0L || estimatedTicks <= 0
                    || !Double.isFinite(estimatedDistance) || estimatedDistance <= 0.0D
                    || !Double.isFinite(goalDeviation) || goalDeviation < 0.0D) {
                throw new IllegalArgumentException("invalid movement-only recovery candidate");
            }
            movement = Set.copyOf(keys);
        }

        public Candidate(
                String id,
                UUID worldSessionId,
                String dimension,
                long worldRevision,
                CandidateKind kind,
                Set<MovementKey> movement,
                boolean collisionFree,
                boolean knownNonWorsening,
                boolean preventsFatalHarm,
                boolean reducesActiveHarm,
                boolean reachesStableState,
                int estimatedTicks,
                double estimatedDistance,
                double goalDeviation) {
            this(
                    id,
                    worldSessionId,
                    dimension,
                    worldRevision,
                    kind,
                    movement,
                    defaultIntent(kind),
                    collisionFree,
                    knownNonWorsening,
                    preventsFatalHarm,
                    reducesActiveHarm,
                    reachesStableState,
                    estimatedTicks,
                    estimatedDistance,
                    goalDeviation);
        }

        private static AgentInputState.RecoveryIntent defaultIntent(CandidateKind kind) {
            var mode = switch (kind) {
                case REACH_BREATHING_SPACE ->
                        AgentInputState.RecoveryMode.REACH_BREATHING_SPACE;
                case EXIT_HAZARDOUS_FLUID -> AgentInputState.RecoveryMode.EXIT_LAVA;
                case STEER_TO_KNOWN_LANDING -> AgentInputState.RecoveryMode.STEER_TO_LANDING;
                case BACK_TO_FREE_AABB -> AgentInputState.RecoveryMode.ESCAPE_SUFFOCATION;
                case RETREAT_FROM_THREAT -> AgentInputState.RecoveryMode.RETREAT_FROM_THREAT;
                case RETREAT_TO_KNOWN_SAFE, TAKE_COVER ->
                        AgentInputState.RecoveryMode.RETREAT_TO_SAFE;
            };
            return new AgentInputState.RecoveryIntent(
                    mode, net.minecraft.world.phys.Vec3.ZERO, null);
        }

        private boolean currentFor(Evidence evidence) {
            return worldSessionId.equals(evidence.worldSessionId())
                    && dimension.equals(evidence.dimension())
                    && worldRevision == evidence.worldRevision();
        }

        private CandidateKey key() {
            var target = intent.target();
            return new CandidateKey(
                    id,
                    worldRevision,
                    intent.mode(),
                    quantizeTarget(target.x),
                    quantizeTarget(target.y),
                    quantizeTarget(target.z));
        }

        private static long quantizeTarget(double coordinate) {
            return (long) Math.floor(coordinate * 4.0D);
        }
    }

    public record Assessment(Decision decision, Set<Danger> dangers) {
        public Assessment {
            Objects.requireNonNull(decision, "decision");
            dangers = Set.copyOf(Objects.requireNonNull(dangers, "dangers"));
            if (decision == Decision.STOP && !dangers.isEmpty()) {
                throw new IllegalArgumentException("STOP is an external control decision");
            }
        }
    }

    public record Usage(
            int activeTicks,
            double distance,
            double cameraDegrees,
            int interactions,
            int placements,
            int breaks) {
    }

    public record TickResult(
            State state,
            Assessment assessment,
            Reason reason,
            String candidateId,
            Usage usage,
            boolean goalPreempted,
            boolean inputsReleased) {
        public TickResult {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(usage, "usage");
        }

        public boolean terminal() {
            return state == State.RECOVERED || state == State.EXHAUSTED || state == State.STOPPED;
        }
    }

    public enum State {
        IDLE,
        REPLAN_REQUIRED,
        RECOVERING,
        PAUSED,
        RECOVERED,
        EXHAUSTED,
        STOPPED
    }

    public enum Decision { CONTINUE, REPLAN, RECOVER, STOP }

    public enum Danger {
        LAVA,
        DROWNING,
        SUFFOCATION,
        DANGEROUS_FALL,
        UNDER_ATTACK,
        ACTIVE_DAMAGE,
        UNCLASSIFIED_DAMAGE,
        BURNING,
        LOW_HEALTH_STABLE
    }

    public enum CandidateKind {
        RETREAT_TO_KNOWN_SAFE,
        REACH_BREATHING_SPACE,
        EXIT_HAZARDOUS_FLUID,
        STEER_TO_KNOWN_LANDING,
        BACK_TO_FREE_AABB,
        RETREAT_FROM_THREAT,
        TAKE_COVER
    }

    public enum Landing { KNOWN_SAFE, KNOWN_LAVA, KNOWN_VOID, UNKNOWN }

    public enum DamageKind { NONE, ATTACK, FIRE, DROWNING, FALL, OTHER }

    public enum StopSignal { NONE, ESC, OFF }

    public enum Reason {
        NONE,
        WAITING_FOR_VERIFIED_CANDIDATE,
        SAFER_STATE_REACHED,
        RECOVERY_BUDGET_EXHAUSTED,
        INPUT_LEASE_EXPIRED,
        INPUT_RELEASE_FAILED,
        ESC,
        OFF,
        CONTROL_TARGET_LOST,
        INTERNAL_INVARIANT
    }

    private record DamageProgress(boolean freshDamage, int airDelta) {
    }

    private record CandidateKey(
            String id,
            long worldRevision,
            AgentInputState.RecoveryMode mode,
            long targetX,
            long targetY,
            long targetZ) {
    }
}
