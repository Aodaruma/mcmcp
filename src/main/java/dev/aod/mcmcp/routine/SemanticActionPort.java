package dev.aod.mcmcp.routine;

/** Minecraft-independent adapter boundary for all five Phase 3 semantic actions. */
public interface SemanticActionPort {
    SemanticActionFrame observe(SemanticActionRequest request);

    SemanticActionPreparationAttempt beginPreparation(
            SemanticActionRequest request,
            long leaseExpiresAtClientTick);

    void maintainPreparation(SemanticActionPreparationAttempt attempt);

    SemanticActionPreparationEvidence preparationEvidence(
            SemanticActionPreparationAttempt attempt);

    void releasePreparation(SemanticActionPreparationAttempt attempt);

    SemanticActionAttempt dispatchPrepared(
            SemanticActionRequest request,
            SemanticActionPreparationAttempt preparation,
            long leaseExpiresAtClientTick);

    /** Direct path retained for callers which already own exact aim and hotbar preparation. */
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
