package dev.aod.mcmcp.adminbridge;

/** Fixed response category; raw Minecraft or parser messages never cross the admin API. */
final class AdminBridgeException extends Exception {
    private final String code;

    AdminBridgeException(String code) {
        super(code);
        this.code = code;
    }

    AdminBridgeException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
