package dev.aod.mcmcp.adminbridge;

/** Transport-facing contract kept free of Minecraft types for pure endpoint tests. */
interface AdminBridgeApi {
    FixtureScript validate(String fixtureId) throws AdminBridgeException;

    Status status() throws AdminBridgeException;

    ApplyResult apply(String fixtureId, String expectedHash, String expectedWorldSession)
            throws AdminBridgeException;

    record Status(
            String state,
            String worldSessionId,
            double playerX,
            double playerY,
            double playerZ,
            LeaseStatus randomTickLease) {
    }

    record ApplyResult(
            String fixtureId,
            String fixtureSha256,
            String worldSessionId,
            int commandsDispatched,
            long declaredChangedBlocks,
            LeaseStatus randomTickLease) {
    }

    record LeaseStatus(boolean active, Integer target, long remainingSeconds) {
    }
}
