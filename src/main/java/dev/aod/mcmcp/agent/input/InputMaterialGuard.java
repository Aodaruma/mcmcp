package dev.aod.mcmcp.agent.input;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Optional stationary material contract; empty-slot waiting never extends the job deadline. */
public final class InputMaterialGuard {
    public enum Decision { HOLD, WAIT, ITEM_CHANGED, POSE_CHANGED, REFILL_TIMEOUT, OFFHAND_PRESENT }
    private final String item;
    private final int slot;
    private final double x, y, z;
    private final float yaw, pitch;
    private final long waitNanos;
    private Long emptySince;

    public InputMaterialGuard(String item, int slot, double x, double y, double z,
            float yaw, float pitch, int waitSeconds) {
        this.item = Objects.requireNonNull(item);
        if (waitSeconds < 1 || waitSeconds > 300) throw new IllegalArgumentException("invalid refill wait");
        this.slot = slot;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        waitNanos = TimeUnit.SECONDS.toNanos(waitSeconds);
    }

    public Decision check(long now, int currentSlot, String heldItem, boolean offhandEmpty,
            double px, double py, double pz, float currentYaw, float currentPitch) {
        if (currentSlot != slot) return Decision.ITEM_CHANGED;
        if (!offhandEmpty) return Decision.OFFHAND_PRESENT;
        double distance = Math.pow(px - x, 2) + Math.pow(py - y, 2) + Math.pow(pz - z, 2);
        double turn = Math.IEEEremainder(currentYaw - yaw, 360.0);
        if (!Double.isFinite(distance) || distance > 0.25 * 0.25
                || !Double.isFinite(turn) || Math.abs(turn) > 2.0
                || !Float.isFinite(currentPitch) || Math.abs(currentPitch - pitch) > 2.0) {
            return Decision.POSE_CHANGED;
        }
        // Expired refill waits cannot be revived by a late matching inventory update.
        if (emptySince != null && now - emptySince >= waitNanos) return Decision.REFILL_TIMEOUT;
        if (heldItem == null) {
            if (emptySince == null) emptySince = now;
            return Decision.WAIT;
        }
        if (!item.equals(heldItem)) return Decision.ITEM_CHANGED;
        emptySince = null;
        return Decision.HOLD;
    }
}
