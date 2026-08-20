package dev.aodaruma.craftagent.routine;

/**
 * Minecraft adapter boundary. Every method is invoked by the client-thread tick integration.
 * Implementations must use normal player input and vanilla prediction/ack signals only.
 */
public interface StationaryBreakPort {
    StationaryBreakFrame observe(StationaryBreakRequest request);

    AttackAttempt beginAttack(
            StationaryBreakRequest request,
            long leaseExpiresAtClientTick);

    void holdAttack(AttackAttempt attempt);

    /** Releases only the attack key while retaining prediction evidence for server confirmation. */
    void stopAttackInput(AttackAttempt attempt);

    PredictionEvidence predictionEvidence(AttackAttempt attempt);

    void releaseAttack(AttackAttempt attempt);

    /** Drops request-scoped baselines after every terminal outcome. */
    void retire(StationaryBreakRequest request);
}
