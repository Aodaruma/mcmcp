package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.observation.BlockPosition;
import dev.aodaruma.craftagent.observation.BlockStateView;
import dev.aodaruma.craftagent.observation.ObservationProvenance;
import dev.aodaruma.craftagent.observation.ObservedBlock;
import dev.aodaruma.craftagent.observation.ObservedContext;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.runtime.WorldSessionTracker;

import java.util.Objects;

/** Safely promotes a server-confirmed interaction result into session-scoped world memory. */
final class InteractionConfirmationRecorder {
    private InteractionConfirmationRecorder() {
    }

    /**
     * Records only when the confirmed transition is still the client's currently accepted state.
     * If immediate regeneration has already replaced it, the historical transition is not written
     * as current world memory.
     */
    static boolean rememberIfCurrent(
            WorldMemory memory,
            WorldSessionTracker.Snapshot session,
            BlockTarget target,
            BlockStateFingerprint confirmedState,
            BlockStateFingerprint currentAcceptedState,
            ObservedContext currentContext) {
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(confirmedState, "confirmedState");
        Objects.requireNonNull(currentAcceptedState, "currentAcceptedState");
        Objects.requireNonNull(currentContext, "currentContext");
        if (session == null
                || !session.worldReady()
                || session.clientTick() < 0
                || session.worldSessionId() == null
                || !target.dimension().equals(session.dimension())
                || !session.worldSessionId().equals(memory.sessionId())
                || !confirmedState.equals(currentAcceptedState)) {
            return false;
        }

        try {
            memory.rememberBlock(new ObservedBlock(
                    new BlockPosition(target.dimension(), target.x(), target.y(), target.z()),
                    new BlockStateView(currentAcceptedState.blockId(), currentAcceptedState.properties()),
                    currentContext,
                    ObservationProvenance.INTERACTION_CONFIRMATION,
                    session.clientTick(),
                    session.worldSessionId()));
            return true;
        } catch (IllegalArgumentException staleSession) {
            // WorldMemory also validates its current dimension under the same lock. A level
            // replacement between snapshots is a normal fail-closed skip, never routine failure.
            return false;
        }
    }
}
