package dev.aod.mcmcp.agent.action;

import java.util.concurrent.TimeUnit;

/** Explicit v2 execution ceilings. Callers retain their shorter default budgets. */
public final class AgentJobLimits {
    public static final int MAX_SECONDS = 86_400;
    public static final int MAX_TICKS = MAX_SECONDS * 20;
    public static final int MAX_SCRIPT_WORK = 100_000_000;
    public static final int MAX_SCRIPT_ITERATIONS = 10_000_000;
    public static final int MAX_SCRIPT_CALLS = MAX_TICKS;
    public static final int MAX_DISTANCE = 4_096;

    private AgentJobLimits() { }

    /** Preserve the legacy two-minute safety ceiling for short jobs. */
    public static long wallNanos(int ticks) {
        if (ticks < 1 || ticks > MAX_TICKS) throw new IllegalArgumentException("invalid tick budget");
        return TimeUnit.MILLISECONDS.toNanos(Math.max(120_000L, ticks * 50L));
    }
}
