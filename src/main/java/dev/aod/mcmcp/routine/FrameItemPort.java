package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.UUID;

/** Narrow, single-dispatch boundary for the displayed item in one delivered Vanilla frame. */
public interface FrameItemPort {
    Frame prepare(Request request, long clientTick);
    Frame observe(Request request, long clientTick);
    void dispatch(Request request, long clientTick);
    boolean release();

    enum Mode { REMOVE, INSERT }

    record AimPoint(double x, double y, double z) {
        public AimPoint {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("frame aim point must be finite");
            }
        }
    }

    record Request(Mode mode, String entityRef, String item, int expectedRotation,
                   UUID worldSessionId, String dimension, AimPoint frontAimPoint,
                   double cameraDegreesPerTick) {
        public Request {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(frontAimPoint, "frontAimPoint");
            if (entityRef.isBlank() || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || "minecraft:air".equals(item) || expectedRotation < 0 || expectedRotation > 7
                    || !Double.isFinite(cameraDegreesPerTick)
                    || cameraDegreesPerTick < 0.1D || cameraDegreesPerTick > 18.0D) {
                throw new IllegalArgumentException("invalid frame item request");
            }
        }
    }

    record Frame(long clientTick, long worldRevision, boolean ready, String failure,
                 boolean bodyAlive, int rotation, String displayedItem,
                 long packetRevision, boolean serverItemObserved, long itemRevision,
                 String serverItem, long inventoryRevision, int inventoryCount,
                 boolean inventoryExactChange) { }
}
