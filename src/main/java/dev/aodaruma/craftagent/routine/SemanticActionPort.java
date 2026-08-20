package dev.aodaruma.craftagent.routine;

/** Minecraft-independent adapter boundary for all five Phase 3 semantic actions. */
public interface SemanticActionPort {
    SemanticActionFrame observe(SemanticActionRequest request);

    SemanticActionAttempt dispatch(
            SemanticActionRequest request,
            long leaseExpiresAtClientTick);

    void maintain(SemanticActionAttempt attempt);

    /** Stops held attack/movement/use input while retaining evidence until release. */
    void stopInput(SemanticActionAttempt attempt);

    SemanticActionEvidence evidence(SemanticActionAttempt attempt);

    void release(SemanticActionAttempt attempt);

    /** Drops request-scoped observations/baselines after every terminal outcome. */
    void retire(SemanticActionRequest request);
}
