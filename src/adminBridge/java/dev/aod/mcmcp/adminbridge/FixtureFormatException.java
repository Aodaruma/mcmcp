package dev.aod.mcmcp.adminbridge;

/** Fixed-category rejection for an untrusted external fixture definition. */
public final class FixtureFormatException extends Exception {
    private final String code;

    public FixtureFormatException(String code) {
        super(code);
        this.code = code;
    }

    public FixtureFormatException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
