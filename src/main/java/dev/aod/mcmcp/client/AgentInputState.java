package dev.aod.mcmcp.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared client-thread state for agent-owned input.
 *
 * <p>The movement command is deliberately separate from vanilla {@code KeyMapping} state. A
 * pause keeps the command and ownership but freezes both output and the watchdog clock. A safety
 * release can suppress output immediately; only a later lease heartbeat may drive it again.</p>
 */
public final class AgentInputState {
    private static final AgentInputState GLOBAL = new AgentInputState();

    private MovementSnapshot movement = MovementSnapshot.unowned();
    private boolean movementSuppressed;
    private boolean movementExpiryRequired;
    private long movementValidUntilNanos;
    private Object goalMovementPlayerIdentity;
    private Object goalMovementLevelIdentity;
    private long goalMovementWorldRevision = -1L;
    private double goalMovementAllowance;
    private RecoveryIntent goalRecoveryIntent;
    private NavigationIntent goalNavigationIntent;
    private boolean goalMovementCycleActive;
    private boolean goalMovementRejected;
    private Vec3 agentVelocityContribution = Vec3.ZERO;
    private Object agentVelocityPlayerIdentity;
    private Object agentVelocityLevelIdentity;
    private Vec3 agentMoveContribution = Vec3.ZERO;
    private boolean agentVelocityReset;
    private boolean attackOwned;
    private boolean attackSuppressed;
    private boolean paused;
    private long pauseStartedAtNanos;
    private long accumulatedPauseNanos;

    public static AgentInputState global() {
        return GLOBAL;
    }

    public synchronized void publishMovement(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump) {
        publishMovement(forward, backward, left, right, jump, 0L, false);
    }

    public synchronized void publishMovement(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            long validUntilNanos) {
        publishMovement(forward, backward, left, right, jump, validUntilNanos, true);
    }

    private void publishMovement(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            long validUntilNanos,
            boolean expiryRequired) {
        if (forward && backward || left && right) {
            throw new IllegalArgumentException("opposed agent movement cannot be published");
        }
        movement = new MovementSnapshot(true, forward, backward, left, right, jump);
        movementSuppressed = false;
        movementExpiryRequired = expiryRequired;
        movementValidUntilNanos = validUntilNanos;
        invalidateGoalMovementCycle();
    }

    /** Caps an already published lease to an earlier action hard deadline. */
    public synchronized void capMovementValidity(long validUntilNanos) {
        if (!movement.owned()) return;
        long now = watchdogTime(System.nanoTime());
        if (!movementExpiryRequired
                || validUntilNanos - now < movementValidUntilNanos - now) {
            movementExpiryRequired = true;
            movementValidUntilNanos = validUntilNanos;
        }
    }

    public synchronized void requireGoalMovementSafety(
            Object playerIdentity,
            Object levelIdentity,
            long worldRevision,
            double distanceAllowance) {
        if (!movement.owned() || worldRevision < 0L
                || !Double.isFinite(distanceAllowance) || distanceAllowance < 0.0D) {
            throw new IllegalArgumentException("goal movement proof requires an owned current input");
        }
        goalMovementPlayerIdentity = Objects.requireNonNull(playerIdentity, "playerIdentity");
        goalMovementLevelIdentity = Objects.requireNonNull(levelIdentity, "levelIdentity");
        goalMovementWorldRevision = worldRevision;
        goalMovementAllowance = distanceAllowance;
        goalRecoveryIntent = null;
        goalNavigationIntent = null;
        goalMovementCycleActive = true;
    }

    public synchronized void requireNavigationMovementSafety(
            Object playerIdentity,
            Object levelIdentity,
            long worldRevision,
            double distanceAllowance,
            NavigationIntent intent) {
        requireGoalMovementSafety(
                playerIdentity, levelIdentity, worldRevision, distanceAllowance);
        goalNavigationIntent = Objects.requireNonNull(intent, "intent");
    }

    public synchronized void requireRecoveryMovementSafety(
            Object playerIdentity,
            Object levelIdentity,
            long worldRevision,
            double distanceAllowance,
            RecoveryIntent intent) {
        requireGoalMovementSafety(
                playerIdentity, levelIdentity, worldRevision, distanceAllowance);
        goalRecoveryIntent = Objects.requireNonNull(intent, "intent");
    }

    public synchronized GoalMovementProof goalMovementProof() {
        return new GoalMovementProof(
                goalMovementPlayerIdentity,
                goalMovementLevelIdentity,
                goalMovementWorldRevision,
                goalMovementAllowance,
                goalRecoveryIntent,
                goalNavigationIntent);
    }

    public synchronized Optional<GoalMovementProof> goalMovementProofFor(
            Object playerIdentity, Object levelIdentity) {
        if (!goalMovementOutputActive()
                || goalMovementPlayerIdentity != playerIdentity
                || goalMovementLevelIdentity != levelIdentity) {
            return Optional.empty();
        }
        return Optional.of(goalMovementProof());
    }

    public synchronized boolean goalMovementOutputActive() {
        return movement.owned() && !movementSuppressed && !paused && goalMovementCycleActive;
    }

    /** Captures expired Agent motion before Entity.move recomputes the external-only delta. */
    public synchronized MovementBoundary movementBoundary(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        boolean expired = movement.owned() && movementExpiryRequired && !paused
                && watchdogTime(System.nanoTime()) - movementValidUntilNanos >= 0L;
        if (!expired) {
            return new MovementBoundary(goalMovementOutputActive(), false, Vec3.ZERO, false);
        }
        Vec3 contribution = agentVelocityPlayerIdentity == player
                        && agentVelocityLevelIdentity == player.level()
                ? agentMoveContribution : Vec3.ZERO;
        boolean reset = agentVelocityReset;
        boolean rejectGoal = goalMovementCycleActive;
        movementSuppressed = true;
        invalidateGoalMovementCycle();
        if (rejectGoal) {
            goalMovementRejected = true;
        }
        return new MovementBoundary(false, true, contribution, reset);
    }

    /** Clears contribution bookkeeping after Entity.move removed the captured Agent component. */
    public synchronized void completeExpiredMovementBoundary() {
        clearAgentVelocityContribution();
    }

    /** Rejects subsequent agent output without modifying Vanilla's current external motion. */
    public synchronized void rejectGoalMovement() {
        suppressMovement();
        goalMovementRejected = true;
    }

    public synchronized boolean consumeGoalMovementRejection() {
        boolean rejected = goalMovementRejected;
        goalMovementRejected = false;
        return rejected;
    }

    /** Opens the one-LocalPlayer-tick contribution ledger after the runtime issued its proof. */
    public synchronized void beginPlayerMovementTick(
            Object playerIdentity, Object levelIdentity) {
        if (agentVelocityPlayerIdentity != null
                && (agentVelocityPlayerIdentity != playerIdentity
                        || agentVelocityLevelIdentity != levelIdentity)) {
            clearAgentVelocityContribution();
        }
        agentVelocityPlayerIdentity = Objects.requireNonNull(playerIdentity, "playerIdentity");
        agentVelocityLevelIdentity = Objects.requireNonNull(levelIdentity, "levelIdentity");
        agentMoveContribution = agentVelocityContribution;
        agentVelocityReset = false;
        if (goalMovementCycleActive
                && (goalMovementPlayerIdentity != playerIdentity
                        || goalMovementLevelIdentity != levelIdentity)) {
            invalidateGoalMovementCycle();
        }
    }

    public synchronized void endPlayerMovementTick(
            Object playerIdentity, Object levelIdentity) {
        if (goalMovementPlayerIdentity == playerIdentity
                && goalMovementLevelIdentity == levelIdentity) {
            invalidateGoalMovementCycle();
        }
        agentMoveContribution = Vec3.ZERO;
        agentVelocityReset = false;
    }

    public synchronized void addAgentMoveContribution(Vec3 addition) {
        Objects.requireNonNull(addition, "addition");
        if (!goalMovementOutputActive()) return;
        agentMoveContribution = finiteVector(agentMoveContribution.add(addition));
        agentVelocityContribution = finiteVector(agentVelocityContribution.add(addition));
    }

    public synchronized void replaceAgentMoveContribution(Vec3 contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (!goalMovementOutputActive()) return;
        agentMoveContribution = finiteVector(contribution);
        agentVelocityContribution = agentMoveContribution;
    }

    public synchronized void scaleAgentMoveContribution(Vec3 factor) {
        Objects.requireNonNull(factor, "factor");
        if (!goalMovementOutputActive()) return;
        agentMoveContribution = finiteVector(agentMoveContribution.multiply(factor));
        agentVelocityContribution = Vec3.ZERO;
        agentVelocityReset = true;
    }

    public synchronized Vec3 agentMoveContribution(
            Object playerIdentity, Object levelIdentity) {
        return goalMovementOutputActive()
                        && goalMovementPlayerIdentity == playerIdentity
                        && goalMovementLevelIdentity == levelIdentity
                ? agentMoveContribution : Vec3.ZERO;
    }

    public synchronized boolean agentVelocityReset() {
        return agentVelocityReset;
    }

    public synchronized void resolveAgentContributionAfterCollision(
            Vec3 intended, Vec3 resolved) {
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        if (!goalMovementOutputActive()) return;
        agentVelocityContribution = finiteVector(new Vec3(
                same(intended.x, resolved.x) ? agentVelocityContribution.x : 0.0D,
                same(intended.y, resolved.y) ? agentVelocityContribution.y : 0.0D,
                same(intended.z, resolved.z) ? agentVelocityContribution.z : 0.0D));
        agentMoveContribution = agentVelocityContribution;
    }

    public synchronized void scaleAgentVelocity(Vec3 factor) {
        Objects.requireNonNull(factor, "factor");
        if (!Double.isFinite(factor.x) || !Double.isFinite(factor.y)
                || !Double.isFinite(factor.z)
                || factor.x < 0.0D || factor.y < 0.0D || factor.z < 0.0D) {
            throw new IllegalArgumentException("agent velocity scale must be finite and non-negative");
        }
        if (!goalMovementOutputActive()) return;
        agentVelocityContribution = finiteVector(agentVelocityContribution.multiply(factor));
        agentMoveContribution = agentVelocityContribution;
    }

    /** A server correction replaced physical velocity, including any prior Agent contribution. */
    public synchronized void discardTrackedAgentVelocity() {
        boolean rejectCurrentOutput = goalMovementOutputActive();
        clearAgentVelocityContribution();
        if (rejectCurrentOutput) {
            rejectGoalMovement();
        }
    }

    /** Removes only velocity still attributable to this Agent from the matching local player. */
    public synchronized void neutralizeTrackedAgentVelocity(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        if (agentVelocityPlayerIdentity == player
                && agentVelocityLevelIdentity == player.level()
                && agentVelocityContribution.lengthSqr() > 0.0D) {
            player.setDeltaMovement(player.getDeltaMovement().subtract(agentVelocityContribution));
        }
        clearAgentVelocityContribution();
    }

    public synchronized void acceptGoalMovement(double resolvedDistance) {
        if (!goalMovementOutputActive() || !Double.isFinite(resolvedDistance)
                || resolvedDistance < 0.0D
                || resolvedDistance > goalMovementAllowance + 1.0E-9D) {
            throw new IllegalStateException("goal movement acceptance exceeded its proof");
        }
        goalMovementAllowance = Math.max(0.0D, goalMovementAllowance - resolvedDistance);
    }

    /** Releases ownership. Physical input is left untouched once the lease no longer owns it. */
    public synchronized void releaseMovement() {
        movement = MovementSnapshot.unowned();
        movementSuppressed = false;
        movementExpiryRequired = false;
        movementValidUntilNanos = 0L;
        goalMovementRejected = false;
        invalidateGoalMovementCycle();
    }

    /**
     * Makes an already-owned command neutral until its lease explicitly publishes again.
     * Ownership is retained so stale physical input cannot leak through during cleanup.
     */
    public synchronized void suppressMovement() {
        if (movement.owned()) {
            movementSuppressed = true;
        }
        invalidateGoalMovementCycle();
        clearAgentVelocityContribution();
    }

    public synchronized void publishAttack() {
        attackOwned = true;
        attackSuppressed = false;
    }

    public synchronized void releaseAttack() {
        attackOwned = false;
        attackSuppressed = false;
    }

    /** Neutralizes all agent channels until their owning leases explicitly publish again. */
    public synchronized void suppressAll() {
        suppressMovement();
        if (attackOwned) {
            attackSuppressed = true;
        }
    }

    public synchronized boolean attackActive() {
        return attackOwned && !attackSuppressed && !paused;
    }

    public synchronized MovementSnapshot movementSnapshot() {
        return movementSnapshot(System.nanoTime());
    }

    /** Enforces the watchdog at the real input boundary and removes any stale Agent inertia. */
    public synchronized MovementSnapshot movementSnapshot(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        boolean expired = movement.owned() && movementExpiryRequired && !paused
                && watchdogTime(System.nanoTime()) - movementValidUntilNanos >= 0L;
        if (expired) {
            boolean rejectGoal = goalMovementCycleActive;
            neutralizeTrackedAgentVelocity(player);
            movementSuppressed = true;
            invalidateGoalMovementCycle();
            if (rejectGoal) {
                goalMovementRejected = true;
            }
        }
        return currentMovementSnapshot();
    }

    synchronized MovementSnapshot movementSnapshot(long nowNanos) {
        expireMovementIfNeeded(nowNanos);
        return currentMovementSnapshot();
    }

    private MovementSnapshot currentMovementSnapshot() {
        if (!movement.owned()) {
            return movement;
        }
        if (paused || movementSuppressed) {
            return MovementSnapshot.ownedNeutral();
        }
        return movement;
    }

    private void expireMovementIfNeeded(long nowNanos) {
        if (!movement.owned() || !movementExpiryRequired || paused
                || watchdogTime(nowNanos) - movementValidUntilNanos < 0L) {
            return;
        }
        boolean rejectGoal = goalMovementCycleActive;
        suppressMovement();
        if (rejectGoal) {
            goalMovementRejected = true;
        }
    }

    /** Updates the real single-player pause state and its frozen watchdog clock. */
    public synchronized void setPaused(boolean paused, long nowNanos) {
        if (this.paused == paused) {
            return;
        }
        if (paused) {
            pauseStartedAtNanos = nowNanos;
            invalidateGoalMovementCycle();
        } else {
            accumulatedPauseNanos = saturatedAdd(
                    accumulatedPauseNanos, nonNegativeElapsed(pauseStartedAtNanos, nowNanos));
            pauseStartedAtNanos = 0L;
        }
        this.paused = paused;
    }

    /**
     * Converts {@link System#nanoTime()} into a clock which does not advance while paused.
     * Lease implementations use this for both deadline checks and renewal.
     */
    public synchronized long watchdogTime(long nowNanos) {
        long frozen = accumulatedPauseNanos;
        if (paused) {
            frozen = saturatedAdd(frozen, nonNegativeElapsed(pauseStartedAtNanos, nowNanos));
        }
        return nowNanos - frozen;
    }

    public synchronized boolean paused() {
        return paused;
    }

    private static long nonNegativeElapsed(long startNanos, long nowNanos) {
        long elapsed = nowNanos - startNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void clearGoalMovementProof() {
        goalMovementPlayerIdentity = null;
        goalMovementLevelIdentity = null;
        goalMovementWorldRevision = -1L;
        goalMovementAllowance = 0.0D;
        goalRecoveryIntent = null;
        goalNavigationIntent = null;
    }

    private void invalidateGoalMovementCycle() {
        goalMovementCycleActive = false;
        clearGoalMovementProof();
        agentMoveContribution = Vec3.ZERO;
        agentVelocityReset = false;
    }

    private void clearAgentVelocityContribution() {
        agentVelocityContribution = Vec3.ZERO;
        agentVelocityPlayerIdentity = null;
        agentVelocityLevelIdentity = null;
        agentMoveContribution = Vec3.ZERO;
        agentVelocityReset = false;
    }

    private static Vec3 finiteVector(Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException("agent movement contribution must be finite");
        }
        return value;
    }

    private static boolean same(double left, double right) {
        return Math.abs(left - right) <= 1.0E-7D;
    }

    public record MovementSnapshot(
            boolean owned,
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump) {
        public MovementSnapshot {
            if (forward && backward || left && right) {
                throw new IllegalArgumentException("opposed agent movement is invalid");
            }
            if (!owned && (forward || backward || left || right || jump)) {
                throw new IllegalArgumentException("unowned movement must be neutral");
            }
        }

        private static MovementSnapshot unowned() {
            return new MovementSnapshot(false, false, false, false, false, false);
        }

        private static MovementSnapshot ownedNeutral() {
            return new MovementSnapshot(true, false, false, false, false, false);
        }
    }

    public record GoalMovementProof(
            Object playerIdentity,
            Object levelIdentity,
            long worldRevision,
            double distanceAllowance,
            RecoveryIntent recoveryIntent,
            NavigationIntent navigationIntent) {
        public GoalMovementProof(
                Object playerIdentity,
                Object levelIdentity,
                long worldRevision,
                double distanceAllowance) {
            this(playerIdentity, levelIdentity, worldRevision, distanceAllowance, null, null);
        }

        public GoalMovementProof(
                Object playerIdentity,
                Object levelIdentity,
                long worldRevision,
                double distanceAllowance,
                RecoveryIntent recoveryIntent) {
            this(
                    playerIdentity,
                    levelIdentity,
                    worldRevision,
                    distanceAllowance,
                    recoveryIntent,
                    null);
        }

        public GoalMovementProof {
            if (recoveryIntent != null && navigationIntent != null) {
                throw new IllegalArgumentException("movement proof cannot have two intents");
            }
        }

        public boolean required() {
            return worldRevision >= 0L;
        }
    }

    public record NavigationIntent(Vec3 target, int verticalDelta) {
        public NavigationIntent {
            target = finiteVector(Objects.requireNonNull(target, "target"));
            if (verticalDelta < -1 || verticalDelta > 1) {
                throw new IllegalArgumentException("vertical delta must be within -1..1");
            }
        }
    }

    public record RecoveryIntent(RecoveryMode mode, Vec3 target, Vec3 threatOrigin) {
        public RecoveryIntent {
            Objects.requireNonNull(mode, "mode");
            target = finiteVector(Objects.requireNonNull(target, "target"));
            if (threatOrigin != null) {
                threatOrigin = finiteVector(threatOrigin);
            }
            if (mode == RecoveryMode.RETREAT_FROM_THREAT && threatOrigin == null) {
                throw new IllegalArgumentException("threat retreat requires a source position");
            }
        }
    }

    public enum RecoveryMode {
        REACH_BREATHING_SPACE,
        EXIT_LAVA,
        CONTINUE_LAVA_ESCAPE,
        ENTER_WATER,
        STEER_TO_LANDING,
        ESCAPE_SUFFOCATION,
        EXIT_DAMAGE_SURFACE,
        RETREAT_FROM_THREAT,
        RETREAT_TO_SAFE
    }

    public record MovementBoundary(
            boolean active,
            boolean expired,
            Vec3 contribution,
            boolean velocityReset) {
        public MovementBoundary {
            Objects.requireNonNull(contribution, "contribution");
            if (active && expired || !expired && contribution.lengthSqr() > 0.0D) {
                throw new IllegalArgumentException("invalid movement boundary state");
            }
        }
    }
}
