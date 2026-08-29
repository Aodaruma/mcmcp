package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;

import java.util.Objects;

/** Small, read-only state used by the local HUD and Screen button. */
public record AutomationUiSnapshot(
        State state,
        boolean worldReady,
        String detail) {
    public AutomationUiSnapshot {
        Objects.requireNonNull(state, "state");
    }

    public static AutomationUiSnapshot resolve(
            boolean worldReady,
            LocalArmingState.Snapshot control,
            String faultCode) {
        return resolve(worldReady, control, false, faultCode);
    }

    public static AutomationUiSnapshot resolve(
            boolean worldReady,
            LocalArmingState.Snapshot control,
            boolean evaluationActive,
            String faultCode) {
        Objects.requireNonNull(control, "control");
        if (faultCode != null) {
            return new AutomationUiSnapshot(State.FAULT, worldReady, faultCode);
        }
        return switch (control.mode()) {
            case OFF -> new AutomationUiSnapshot(
                    State.OFF, worldReady, control.lastLockReason());
            case READY -> new AutomationUiSnapshot(
                    evaluationActive ? State.EVALUATING : State.READY,
                    worldReady,
                    null);
            case AGENT -> new AutomationUiSnapshot(State.AGENT, worldReady, null);
            case RECOVERING -> new AutomationUiSnapshot(State.RECOVERING, worldReady, null);
        };
    }

    public enum State {
        OFF,
        READY,
        EVALUATING,
        AGENT,
        RECOVERING,
        FAULT
    }
}
