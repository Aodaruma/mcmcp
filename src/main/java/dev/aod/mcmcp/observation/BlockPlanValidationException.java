package dev.aod.mcmcp.observation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured, caller-safe rejection of an invalid block plan.
 *
 * <p>The public code and field path let the runtime map a rejected plan to bounded MCP
 * diagnostics without parsing an exception message. Details are restricted to immutable scalar
 * values by convention; internal Minecraft objects never cross this boundary.</p>
 */
public final class BlockPlanValidationException extends IllegalArgumentException {
    private final String code;
    private final String path;
    private final Map<String, Object> details;

    public BlockPlanValidationException(String code, String path, String message) {
        this(code, path, message, Map.of());
    }

    public BlockPlanValidationException(
            String code,
            String path,
            String message,
            Map<String, Object> details) {
        super(formatMessage(path, message));
        this.code = requireCode(code);
        this.path = requirePath(path);
        Objects.requireNonNull(details, "details");
        if (details.size() > 32) {
            throw new IllegalArgumentException("too many block-plan validation details");
        }
        details.forEach((name, value) -> {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("invalid block-plan validation detail name");
            }
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("block-plan validation details must be scalar");
            }
        });
        this.details = Map.copyOf(new LinkedHashMap<>(details));
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static String requireCode(String value) {
        Objects.requireNonNull(value, "code");
        if (!value.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid block-plan validation code");
        }
        return value;
    }

    private static String requirePath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isBlank() || value.length() > 192) {
            throw new IllegalArgumentException("invalid block-plan validation path");
        }
        return value;
    }

    private static String formatMessage(String path, String message) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        return path + ": " + message;
    }
}
