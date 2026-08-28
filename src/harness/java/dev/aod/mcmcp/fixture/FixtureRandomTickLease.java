package dev.aod.mcmcp.fixture;

/** Pure identity-bound state for saving a fixture gamerule exactly once. */
final class FixtureRandomTickLease {
    private Object server;
    private Object world;
    private Integer originalSpeed;
    private Integer targetSpeed;
    private Owner owner;

    int begin(
            Object currentServer,
            Object currentWorld,
            int currentSpeed,
            int requestedTargetSpeed,
            Owner requestedOwner) {
        if (requestedTargetSpeed < 0) {
            throw new IllegalArgumentException("random tick target must not be negative");
        }
        if (requestedOwner == null) {
            throw new IllegalArgumentException("random tick owner must be explicit");
        }
        requireCurrent(currentServer, currentWorld);
        if (server == null) {
            server = currentServer;
            world = currentWorld;
            originalSpeed = currentSpeed;
            targetSpeed = requestedTargetSpeed;
            owner = requestedOwner;
        } else if (targetSpeed != requestedTargetSpeed) {
            throw new IllegalStateException("random tick lease is active with another target");
        } else if (owner != requestedOwner) {
            throw new IllegalStateException("random tick lease is owned by another fixture");
        }
        return requestedTargetSpeed;
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

    Integer targetSpeed() {
        return targetSpeed;
    }

    Owner owner() {
        return owner;
    }

    void clear() {
        server = null;
        world = null;
        originalSpeed = null;
        targetSpeed = null;
        owner = null;
    }

    enum Owner {
        GENERIC,
        COMBINED_WHEAT
    }
}
