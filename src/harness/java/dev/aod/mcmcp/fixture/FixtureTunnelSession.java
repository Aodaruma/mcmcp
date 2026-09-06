package dev.aod.mcmcp.fixture;

import java.util.Objects;
import java.util.UUID;

/** The pre-setup world identity is also required for every later status and oracle. */
record FixtureTunnelSession(UUID worldSessionId) {
    FixtureTunnelSession { Objects.requireNonNull(worldSessionId); }

    UUID requireCurrent(UUID current) {
        if (!worldSessionId.equals(current)) throw new IllegalStateException("tunnel world session changed");
        return worldSessionId;
    }
}
