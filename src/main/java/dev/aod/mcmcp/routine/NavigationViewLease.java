package dev.aod.mcmcp.routine;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Objects;
import java.util.UUID;

/** Bounded, owner-token-bound first-person view and hotbar control. */
public final class NavigationViewLease {
    static final float MAX_TURN_DEGREES_PER_TICK = 8.0F;
    private static final float DRIFT_EPSILON_DEGREES = 0.25F;

    private final ViewControl control;
    private final UUID ownerId;
    private final int originalSlot;
    private float expectedYaw;
    private float expectedPitch;
    private int expectedSlot;
    private boolean closed;

    private NavigationViewLease(ViewControl control, UUID ownerId) {
        this.control = Objects.requireNonNull(control, "control");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        float originalYaw = finite(control.yaw(), "yaw");
        float originalPitch = finite(control.pitch(), "pitch");
        originalSlot = control.selectedSlot();
        expectedYaw = originalYaw;
        expectedPitch = originalPitch;
        expectedSlot = originalSlot;
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
                && control.selectedSlot() == expectedSlot;
    }

    void turnToward(UUID owner, float desiredYaw, float desiredPitch) {
        requireOwner(owner);
        if (!matches(owner)) {
            throw new IllegalStateException("owned navigation view changed externally");
        }
        float yawDelta = boundedYawDelta(
                control.yaw(), finite(desiredYaw, "desiredYaw"));
        float pitchDelta = boundedPitchDelta(
                control.pitch(), finite(desiredPitch, "desiredPitch"));
        control.turn(yawDelta, pitchDelta);
        expectedYaw = finite(control.yaw(), "yaw after turn");
        expectedPitch = finite(control.pitch(), "pitch after turn");
    }

    void selectSlot(UUID owner, int slot) {
        requireOwner(owner);
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException("hotbar slot must be 0..8");
        }
        if (!matches(owner)) {
            throw new IllegalStateException("owned view or hotbar changed externally");
        }
        control.selectSlot(slot);
        expectedSlot = control.selectedSlot();
        if (expectedSlot != slot) {
            throw new IllegalStateException("hotbar selection was not applied");
        }
    }

    boolean slotSelected(UUID owner, int slot) {
        requireOwner(owner);
        return matches(owner) && control.selectedSlot() == slot;
    }

    void close(UUID owner) {
        requireOwner(owner);
        if (closed) {
            return;
        }
        control.selectSlot(originalSlot);
        closed = true;
    }

    /** Upper-bounds the camera travel produced by this lease's vanilla float actuation. */
    public static double cameraTravelUpperBound(
            float yaw, float pitch, float desiredYaw, float desiredPitch, int maxTicks) {
        yaw = finite(yaw, "yaw");
        pitch = finite(pitch, "pitch");
        desiredYaw = finite(desiredYaw, "desiredYaw");
        desiredPitch = finite(desiredPitch, "desiredPitch");
        if (maxTicks < 1) {
            throw new IllegalArgumentException("maxTicks must be positive");
        }
        double travel = 0.0D;
        for (int tick = 0; tick < maxTicks; tick++) {
            float yawDelta = boundedYawDelta(yaw, desiredYaw);
            float pitchDelta = boundedPitchDelta(pitch, desiredPitch);
            float nextYaw = yaw + vanillaTurnDelta(yawDelta);
            float nextPitch = Mth.clamp(
                    pitch + vanillaTurnDelta(pitchDelta), -90.0F, 90.0F);
            travel += Math.abs(Mth.wrapDegrees((double) nextYaw - yaw))
                    + Math.abs((double) nextPitch - pitch);
            if (nextYaw == yaw && nextPitch == pitch) {
                return travel;
            }
            yaw = nextYaw;
            pitch = nextPitch;
        }
        return travel;
    }

    private static float vanillaTurnDelta(float requestedDegrees) {
        return (float) (requestedDegrees / 0.15D) * 0.15F;
    }

    private static float boundedYawDelta(float yaw, float desiredYaw) {
        return Mth.clamp(
                Mth.wrapDegrees(desiredYaw - yaw),
                -MAX_TURN_DEGREES_PER_TICK,
                MAX_TURN_DEGREES_PER_TICK);
    }

    private static float boundedPitchDelta(float pitch, float desiredPitch) {
        return Mth.clamp(
                Mth.clamp(desiredPitch, -90.0F, 90.0F) - pitch,
                -MAX_TURN_DEGREES_PER_TICK,
                MAX_TURN_DEGREES_PER_TICK);
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

        void selectSlot(int slot);
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

        @Override
        public void selectSlot(int slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }
}
