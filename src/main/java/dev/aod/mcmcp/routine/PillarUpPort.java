package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.UUID;

/** Minecraft-independent boundary for one jump-and-place pillar primitive. */
public interface PillarUpPort {
    Handle begin(KnownPillarUpRequest request, long leaseExpiresAtClientTick);

    void maintain(Handle handle);

    Evidence evidence(Handle handle);

    void startJump(Handle handle);

    void placeOnce(Handle handle);

    void release(Handle handle);

    record Handle(UUID attemptId, long issuedClientTick, long leaseExpiresAtClientTick) {
        public Handle {
            Objects.requireNonNull(attemptId, "attemptId");
            if (issuedClientTick < 0L || leaseExpiresAtClientTick <= issuedClientTick) {
                throw new IllegalArgumentException("pillar lease must follow its issue tick");
            }
        }
    }

    record Evidence(
            UUID attemptId,
            long clientTick,
            boolean prepared,
            boolean targetCleared,
            boolean blockConfirmed,
            boolean inventoryConfirmed,
            boolean yConfirmed,
            String failure) {
        public Evidence {
            Objects.requireNonNull(attemptId, "attemptId");
            if (clientTick < 0L) throw new IllegalArgumentException("clientTick must be non-negative");
        }
    }
}
