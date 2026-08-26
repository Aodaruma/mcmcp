package dev.aod.mcmcp.agent.observation;

import java.util.Objects;

/** Bounded public-domain failures produced by observation retention and pagination. */
public final class ObservationStoreException extends Exception {
    private final Code code;

    ObservationStoreException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        FRAME_EXPIRED,
        INVALID_CURSOR,
        SERVER_BUSY
    }
}
