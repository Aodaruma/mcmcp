package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;
import java.util.Set;

/** One-use ON intent, fenced by the connection and both control/stop epochs. Never resumes work. */
final class WorldTransitionControl {
    private Pending pending;

    Candidate capture(Object connection, String dimension, LocalArmingState.Snapshot control,
            long safetyEpoch, String multiplayerConsent, boolean eligible) {
        var previous = pending;
        pending = null;
        if (!eligible || connection == null) return null;
        if (previous != null && previous.matches(connection, control, safetyEpoch)) {
            return new Candidate(connection, previous.candidate.dimension(), previous.candidate.capabilities(),
                    previous.candidate.multiplayerConsent(), safetyEpoch);
        }
        if (control.locked() || dimension == null) return null;
        return new Candidate(connection, dimension, control.capabilities(), multiplayerConsent, safetyEpoch);
    }

    void suspend(Candidate candidate, ClientCommandInbox.StopReceipt receipt,
            LocalArmingState.Snapshot control, long safetyEpoch, boolean cleanupComplete) {
        pending = null;
        if (candidate == null || receipt == null || !cleanupComplete || !control.locked()
                || !receipt.inputsReleased() || !receipt.inputOwnerNone()
                || safetyEpoch != candidate.safetyEpoch() + 1 || receipt.safetyEpoch() != safetyEpoch
                || !("level_or_dimension_change".equals(receipt.reason())
                    || "player_respawn".equals(receipt.reason()))) return;
        pending = new Pending(candidate, control.controlEpoch(), safetyEpoch);
    }

    Candidate take(Object connection, String dimension, LocalArmingState.Snapshot control,
            long safetyEpoch, boolean ready) {
        var previous = pending;
        pending = null;
        if (previous == null || !ready || !previous.matches(connection, control, safetyEpoch)
                || dimension == null || dimension.equals(previous.candidate.dimension())) return null;
        return previous.candidate;
    }

    void clear() { pending = null; }

    record Candidate(Object connection, String dimension, Set<String> capabilities,
            String multiplayerConsent, long safetyEpoch) {
        Candidate { capabilities = Set.copyOf(capabilities); }
    }

    private record Pending(Candidate candidate, long controlEpoch, long safetyEpoch) {
        boolean matches(Object connection, LocalArmingState.Snapshot control, long currentSafetyEpoch) {
            return candidate.connection() == connection && control.locked()
                    && control.controlEpoch() == controlEpoch && currentSafetyEpoch == safetyEpoch;
        }
    }
}
