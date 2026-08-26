package dev.aod.mcmcp.routine;

import java.util.UUID;

/** Minecraft-independent boundary shared by the six Phase 5 operation adapters. */
public interface PhaseFivePort {
    PhaseFiveFrame observe(PhaseFiveRequest request);

    /** Begins the sole attempt. Implementations must use routineId as the attempt authority. */
    PhaseFiveAttempt begin(
            UUID routineId,
            PhaseFiveRequest request,
            long hardDeadlineClientTick);

    void maintain(PhaseFiveAttempt attempt);

    PhaseFiveEvidence evidence(PhaseFiveAttempt attempt);

    void release(PhaseFiveAttempt attempt);

    /** Drops request-scoped observations and ownership after every terminal outcome. */
    void retire(PhaseFiveRequest request);
}
