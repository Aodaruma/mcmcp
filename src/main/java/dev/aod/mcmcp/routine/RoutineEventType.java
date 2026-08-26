package dev.aod.mcmcp.routine;

/** Meaningful, low-frequency events retained for cursor-based polling. */
public enum RoutineEventType {
    PHASE_STARTED("phase_started"),
    STEP_VERIFIED("step_verified"),
    RETRYING("retrying"),
    CHECKPOINT("checkpoint"),
    NEEDS_REPLAN("needs_replan"),
    FINALIZATION_STARTED("finalization_started"),
    GOAL_VERIFIED("goal_verified"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireName;

    RoutineEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
