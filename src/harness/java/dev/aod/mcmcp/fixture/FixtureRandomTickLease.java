package dev.aod.mcmcp.fixture;

/** Pure identity-bound state for saving a fixture gamerule exactly once. */
final class FixtureRandomTickLease {
    static final int ACCELERATED_SPEED = 30;

    private Object server;
    private Object world;
    private Integer originalSpeed;

    int begin(Object currentServer, Object currentWorld, int currentSpeed) {
        requireCurrent(currentServer, currentWorld);
        if (server == null) {
            server = currentServer;
            world = currentWorld;
            originalSpeed = currentSpeed;
        }
        return ACCELERATED_SPEED;
    }

    void requireCurrent(Object currentServer, Object currentWorld) {
        if (server != null && (server != currentServer || world != currentWorld)) {
            throw new IllegalStateException("random tick acceleration belongs to another world");
        }
    }

    boolean active() {
        return server != null;
    }

    Object server() {
        return server;
    }

    Integer originalSpeed() {
        return originalSpeed;
    }

    void clear() {
        server = null;
        world = null;
        originalSpeed = null;
    }
}
