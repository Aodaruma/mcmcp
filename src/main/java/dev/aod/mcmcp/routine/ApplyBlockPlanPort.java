package dev.aod.mcmcp.routine;

/**
 * Minecraft-independent adapter boundary for the parent-owned local block-plan workflow.
 * Preparation may own bounded aim and hotbar selection, but must never move the player.
 */
public interface ApplyBlockPlanPort {
    ApplyBlockPlanFrame observe(ApplyBlockPlanRequest request);

    ApplyBlockPlanPreparationAttempt beginPreparation(
            ApplyBlockPlanRequest request,
            ApplyBlockPlanChildAction child,
            long leaseExpiresAtClientTick);

    void maintainPreparation(ApplyBlockPlanPreparationAttempt attempt);

    ApplyBlockPlanPreparationEvidence preparationEvidence(
            ApplyBlockPlanPreparationAttempt attempt);

    void releasePreparation(ApplyBlockPlanPreparationAttempt attempt);

    ApplyBlockPlanActionAttempt dispatchPrepared(
            ApplyBlockPlanRequest request,
            ApplyBlockPlanChildAction child,
            ApplyBlockPlanPreparationAttempt preparation,
            long leaseExpiresAtClientTick);

    void maintainAction(ApplyBlockPlanActionAttempt attempt);

    ApplyBlockPlanActionEvidence actionEvidence(ApplyBlockPlanActionAttempt attempt);

    void releaseAction(ApplyBlockPlanActionAttempt attempt);

    /** Drops all plan-scoped observations and ownership after every outcome. */
    void retire(ApplyBlockPlanRequest request);
}
