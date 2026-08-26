package dev.aodaruma.craftagent.routine;

/** Closed, auditable operation set accepted by the bounded block-plan routine. */
public enum ApplyBlockPlanOperation {
    VERIFY_ONLY("verify_only", false),
    BREAK_TO_AIR("break_to_air", true),
    PLACE("place", true),
    REPLACE("replace", true);

    private final String wireName;
    private final boolean mutating;

    ApplyBlockPlanOperation(String wireName, boolean mutating) {
        this.wireName = wireName;
        this.mutating = mutating;
    }

    public String wireName() {
        return wireName;
    }

    public boolean mutating() {
        return mutating;
    }
}
