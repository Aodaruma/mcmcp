package dev.aod.mcmcp.agent.dsl;

import java.util.Objects;

/** Stable domain category for Action DSL validation and compilation failures. */
public final class ActionDslException extends IllegalArgumentException {
    private final Code code;

    public ActionDslException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ActionDslException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_ARGUMENT,
        PROGRAM_TOO_COMPLEX,
        PROGRAM_BUDGET_UNPROVABLE,
        PREDICATE_UNAVAILABLE,
        CAPABILITY_DENIED
    }
}
