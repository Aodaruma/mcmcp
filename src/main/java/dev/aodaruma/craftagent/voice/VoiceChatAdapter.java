package dev.aodaruma.craftagent.voice;

/**
 * Narrow boundary around the version-pinned Simple Voice Chat internals.
 *
 * <p>Every failure is data rather than an exception crossing into the routine
 * scheduler. Callers must treat anything other than {@link Availability#READY}
 * and a successful read/write as a closed safety gate.</p>
 */
public interface VoiceChatAdapter {
    Probe probe();

    ReadResult readState();

    WriteResult setMuted(boolean muted);

    enum Availability {
        NOT_INSTALLED,
        READY,
        INCOMPATIBLE,
        UNAVAILABLE
    }

    record Probe(
            Availability availability,
            String detectedModVersion,
            String adapterVersion,
            String failureCode) {
        public Probe {
            if (availability == null) {
                throw new IllegalArgumentException("availability is required");
            }
            if (adapterVersion == null || adapterVersion.isBlank()) {
                throw new IllegalArgumentException("adapterVersion is required");
            }
        }

        public boolean ready() {
            return availability == Availability.READY;
        }
    }

    record State(boolean connected, boolean muted) {
    }

    record ReadResult(boolean success, State state, String failureCode) {
        public static ReadResult success(State state) {
            if (state == null) {
                throw new IllegalArgumentException("state is required");
            }
            return new ReadResult(true, state, null);
        }

        public static ReadResult failure(String failureCode) {
            return new ReadResult(false, null, normalizeFailureCode(failureCode));
        }
    }

    record WriteResult(boolean success, String failureCode) {
        public static WriteResult succeeded() {
            return new WriteResult(true, null);
        }

        public static WriteResult failure(String failureCode) {
            return new WriteResult(false, normalizeFailureCode(failureCode));
        }
    }

    private static String normalizeFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return "adapter_failure";
        }
        var normalized = failureCode.replaceAll("[^a-z0-9_]+", "_");
        return normalized.substring(0, Math.min(96, normalized.length()));
    }
}
