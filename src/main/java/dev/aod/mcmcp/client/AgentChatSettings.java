package dev.aod.mcmcp.client;

/** Immutable formatting snapshot for one accepted Agent send. */
public record AgentChatSettings(boolean prefixEnabled, String prefix, boolean suffixEnabled, String suffix) {
    public static final String DEFAULT_SUFFIX = " (by Agent)";

    public String format(String text) {
        validateMessage(text);
        String formatted = (prefixEnabled ? prefix : "") + text + (suffixEnabled ? suffix : "");
        validateMessage(formatted);
        return formatted;
    }

    public static boolean validFragment(Object value) {
        if (!(value instanceof String text) || text.length() > 256) return false;
        return text.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0xA7
                || c == 0x2028 || c == 0x2029 || c >= 0xD800 && c <= 0xDFFF);
    }

    public static void validateMessage(String text) {
        if (!validFragment(text) || text.isBlank() || text.stripLeading().startsWith("/"))
            throw new IllegalArgumentException(
                    "chat text including enabled prefix/suffix must be a non-command line of 1..256 UTF-16 units without control characters");
    }
}
