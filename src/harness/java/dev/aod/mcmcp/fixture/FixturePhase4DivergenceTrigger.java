package dev.aod.mcmcp.fixture;

/** Pure one-shot gate for the Phase 4 live divergence fault injection. */
final class FixturePhase4DivergenceTrigger {
    static final int REQUIRED_CONSECUTIVE_OWNED_BREAK_TICKS = 3;

    private int consecutiveOwnedBreakTicks;
    private boolean fired;

    boolean observe(boolean ownedBreakActive) {
        if (fired) {
            return false;
        }
        if (!ownedBreakActive) {
            consecutiveOwnedBreakTicks = 0;
            return false;
        }
        consecutiveOwnedBreakTicks++;
        if (consecutiveOwnedBreakTicks < REQUIRED_CONSECUTIVE_OWNED_BREAK_TICKS) {
            return false;
        }
        fired = true;
        return true;
    }
}
