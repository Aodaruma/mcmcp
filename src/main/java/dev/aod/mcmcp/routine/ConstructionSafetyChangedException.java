package dev.aod.mcmcp.routine;

/** Fixed internal signal that the construction universal-safety gate closed. */
public final class ConstructionSafetyChangedException extends IllegalStateException {
    public ConstructionSafetyChangedException() {
        super("construction safety changed");
    }
}
