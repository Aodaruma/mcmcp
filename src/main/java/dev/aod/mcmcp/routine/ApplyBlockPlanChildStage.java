package dev.aod.mcmcp.routine;

/** Concrete internal transition; replace is deliberately BREAK followed by PLACE. */
public enum ApplyBlockPlanChildStage {
    BREAK("break"),
    PLACE("place");

    private final String wireName;

    ApplyBlockPlanChildStage(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
