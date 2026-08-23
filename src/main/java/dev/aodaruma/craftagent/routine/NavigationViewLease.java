package dev.aodaruma.craftagent.routine;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Objects;
import java.util.UUID;

/** Bounded, owner-token-bound first-person view control used only inside navigate_to. */
final class NavigationViewLease {
    static final float MAX_TURN_DEGREES_PER_TICK = 8.0F;
    private static final float DRIFT_EPSILON_DEGREES = 0.25F;

    private final ViewControl control;
    private final UUID ownerId;
    private final float originalYaw;
    private final float originalPitch;
    private final int selectedSlot;
    private float expectedYaw;
    private float expectedPitch;
    private boolean closed;

    private NavigationViewLease(ViewControl control, UUID ownerId) {
        this.control = Objects.requireNonNull(control, "control");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        originalYaw = finite(control.yaw(), "yaw");
        originalPitch = finite(control.pitch(), "pitch");
        selectedSlot = control.selectedSlot();
        expectedYaw = originalYaw;
        expectedPitch = originalPitch;
    }

    static NavigationViewLease acquire(LocalPlayer player, UUID ownerId) {
        return acquire(new VanillaViewControl(player), ownerId);
    }

    static NavigationViewLease acquire(ViewControl control, UUID ownerId) {
        return new NavigationViewLease(control, ownerId);
    }

    boolean matches(UUID owner) {
        requireOwner(owner);
        return !closed
                && Math.abs(Mth.wrapDegrees(control.yaw() - expectedYaw))
                        <= DRIFT_EPSILON_DEGREES
                && Math.abs(control.pitch() - expectedPitch) <= DRIFT_EPSILON_DEGREES
                && control.selectedSlot() == selectedSlot;
    }

    void turnToward(UUID owner, float desiredYaw, float desiredPitch) {
        requireOwner(owner);
        if (!matches(owner)) {
            throw new IllegalStateException("owned navigation view changed externally");
        }
        float yawDelta = Mth.clamp(
                Mth.wrapDegrees(finite(desiredYaw, "desiredYaw") - control.yaw()),
                -MAX_TURN_DEGREES_PER_TICK,
                MAX_TURN_DEGREES_PER_TICK);
        float pitchDelta = Mth.clamp(
                Mth.clamp(finite(desiredPitch, "desiredPitch"), -90.0F, 90.0F)
                        - control.pitch(),
                -MAX_TURN_DEGREES_PER_TICK,
                MAX_TURN_DEGREES_PER_TICK);
        control.turn(yawDelta, pitchDelta);
        expectedYaw = finite(control.yaw(), "yaw after turn");
        expectedPitch = finite(control.pitch(), "pitch after turn");
    }

    void close(UUID owner) {
        requireOwner(owner);
        if (closed) {
            return;
        }
        float yawDelta = Mth.wrapDegrees(originalYaw - control.yaw());
        float pitchDelta = originalPitch - control.pitch();
        control.turn(yawDelta, pitchDelta);
        closed = true;
    }

    private void requireOwner(UUID owner) {
        if (!ownerId.equals(owner)) {
            throw new IllegalArgumentException("navigation view lease owner mismatch");
        }
    }

    private static float finite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    interface ViewControl {
        float yaw();

        float pitch();

        int selectedSlot();

        void turn(float yawDeltaDegrees, float pitchDeltaDegrees);
    }

    private record VanillaViewControl(LocalPlayer player) implements ViewControl {
        private VanillaViewControl {
            Objects.requireNonNull(player, "player");
        }

        @Override
        public float yaw() {
            return player.getYRot();
        }

        @Override
        public float pitch() {
            return player.getXRot();
        }

        @Override
        public int selectedSlot() {
            return player.getInventory().getSelectedSlot();
        }

        @Override
        public void turn(float yawDeltaDegrees, float pitchDeltaDegrees) {
            player.turn(yawDeltaDegrees / 0.15D, pitchDeltaDegrees / 0.15D);
        }
    }
}
