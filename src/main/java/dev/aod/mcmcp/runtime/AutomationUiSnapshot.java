package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;

import java.util.Objects;

/** Small, read-only state used by the local HUD and Screen button. */
public record AutomationUiSnapshot(
        State state,
        boolean worldReady,
        long readySeconds,
        String detail) {
    public AutomationUiSnapshot {
        Objects.requireNonNull(state, "state");
        if (readySeconds < 0L || (state == State.READY) != (readySeconds > 0L)) {
            throw new IllegalArgumentException("Only READY may expose a positive countdown");
        }
    }

    public static AutomationUiSnapshot resolve(
            boolean worldReady,
            LocalArmingState.Snapshot control,
            long nowNanos,
            String faultCode) {
        Objects.requireNonNull(control, "control");
        if (faultCode != null) {
            return new AutomationUiSnapshot(State.FAULT, worldReady, 0L, faultCode);
        }
        return switch (control.mode()) {
            case OFF -> new AutomationUiSnapshot(
                    State.OFF, worldReady, 0L, control.lastLockReason());
            case READY -> new AutomationUiSnapshot(
                    State.READY, worldReady, control.readyRemainingSeconds(nowNanos), null);
            case AGENT -> new AutomationUiSnapshot(State.AGENT, worldReady, 0L, null);
            case RECOVERING -> new AutomationUiSnapshot(State.RECOVERING, worldReady, 0L, null);
        };
    }

    public enum State {
        OFF,
        READY,
        AGENT,
        RECOVERING,
        FAULT
    }
}
