package dev.aodaruma.craftagent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local-arm-scoped budget and intent lookup for an MCP-client-owned one-shot chain. */
final class GoalContinuationSession {
    static final String FINISH_GOAL = "finish_goal";
    static final String CONTINUE_GOAL = "continue_goal";
    static final int MAX_CONTINUED_ROUTINES = 16;

    private final Map<UUID, String> intents = new LinkedHashMap<>();
    private UUID worldSessionId;
    private int continuedRoutines;

    void reset(UUID sessionId) {
        worldSessionId = Objects.requireNonNull(sessionId, "sessionId");
        continuedRoutines = 0;
        intents.clear();
    }

    void clear() {
        worldSessionId = null;
        continuedRoutines = 0;
        intents.clear();
    }

    boolean canAdmit(UUID sessionId, String intent) {
        requireIntent(intent);
        ensureSession(sessionId);
        return !CONTINUE_GOAL.equals(intent) || continuedRoutines < MAX_CONTINUED_ROUTINES;
    }

    void remember(UUID sessionId, UUID routineId, boolean replay, String intent) {
        requireIntent(intent);
        ensureSession(sessionId);
        if (replay) {
            return;
        }
        if (CONTINUE_GOAL.equals(intent)) {
            if (continuedRoutines >= MAX_CONTINUED_ROUTINES) {
                throw new IllegalStateException("continuation routine budget is exhausted");
            }
            continuedRoutines++;
        }
        intents.put(Objects.requireNonNull(routineId, "routineId"), intent);
    }

    String consumeIntent(UUID routineId) {
        return intents.remove(Objects.requireNonNull(routineId, "routineId"));
    }

    int remainingContinuations(UUID sessionId) {
        ensureSession(sessionId);
        return MAX_CONTINUED_ROUTINES - continuedRoutines;
    }

    private void ensureSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (!sessionId.equals(worldSessionId)) {
            reset(sessionId);
        }
    }

    static void requireIntent(String intent) {
        if (!FINISH_GOAL.equals(intent) && !CONTINUE_GOAL.equals(intent)) {
            throw new IllegalArgumentException("completion_intent is unsupported");
        }
    }
}
