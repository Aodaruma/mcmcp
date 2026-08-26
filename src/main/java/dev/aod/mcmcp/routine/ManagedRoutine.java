package dev.aod.mcmcp.routine;

import java.util.UUID;

/** Package-private lifecycle contract shared by every admitted routine kind. */
interface ManagedRoutine {
    UUID routineId();

    String kind();

    RoutineState state();

    long lastClientTick();

    void tick();

    void cancel(String reason);

    void completeFinalization(RoutineFailure finalizationFailure);

    void recordTerminalFinalization(RoutineFailure cleanupFailure);

    RoutineSnapshot snapshot(long afterEventSeq, int maxEvents);

    /** Releases request-scoped adapter state. Implementations must be idempotent. */
    void retire();
}
