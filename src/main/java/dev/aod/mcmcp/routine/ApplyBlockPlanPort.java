package dev.aod.mcmcp.routine;

/**
 * Minecraft-independent adapter boundary for the parent-owned local block-plan workflow.
 * Preparation may own bounded aim and hotbar selection, but must never move the player.
 */
public interface ApplyBlockPlanPort {
    /** Read-only receipt for the one preparation SWAP; absent means no send was attempted. */
    default java.util.Optional<StagingEvidence> stagingEvidence(ApplyBlockPlanPreparationAttempt attempt) {
        return java.util.Optional.empty();
    }

    record StagingEvidence(java.util.Map<String, Object> before, java.util.Map<String, Object> after,
            boolean confirmed, long clientTick, long worldRevision) {
        public StagingEvidence {
            before = java.util.Map.copyOf(before);
            after = java.util.Map.copyOf(after);
            if (!confirmed && !after.isEmpty()) throw new IllegalArgumentException("unknown swap has no after state");
            if (clientTick < 0 || worldRevision < 0) throw new IllegalArgumentException("invalid staging clocks");
        }
    }

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
