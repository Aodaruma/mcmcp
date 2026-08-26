package dev.aod.mcmcp.runtime;

import java.util.Objects;

/** Small, read-only view exposed to the local automation indicator. */
public record AutomationUiSnapshot(
        State state,
        boolean worldReady,
        String lockReason) {
    public AutomationUiSnapshot {
        Objects.requireNonNull(state, "state");
    }

    public static AutomationUiSnapshot resolve(
            boolean worldReady,
            boolean locked,
            boolean activityPending,
            String lockReason) {
        var state = locked
                ? State.DISABLED
                : activityPending ? State.ACTIVE : State.IDLE;
        return new AutomationUiSnapshot(state, worldReady, lockReason);
    }

    public enum State {
        ACTIVE,
        IDLE,
        DISABLED
    }
}
