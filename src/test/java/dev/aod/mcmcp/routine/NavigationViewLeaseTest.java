package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class NavigationViewLeaseTest {
    @Test
    void turnIsBoundedAndCloseKeepsThePlannedViewWhileRestoringTheSlot() {
        var owner = UUID.randomUUID();
        var control = new FakeViewControl(20.0F, 10.0F, 1);
        var lease = NavigationViewLease.acquire(control, owner);

        lease.turnToward(owner, 80.0F, 40.0F);
        assertThat(control.yaw).isEqualTo(28.0F);
        assertThat(control.pitch).isEqualTo(18.0F);
        lease.selectSlot(owner, 4);
        assertThat(lease.slotSelected(owner, 4)).isTrue();
        control.slot = 5;
        assertThat(lease.matches(owner)).isFalse();
        control.slot = 4;

        control.yaw += 2.0F;
        assertThat(lease.matches(owner)).isFalse();
        assertThatIllegalStateException().isThrownBy(() ->
                lease.turnToward(owner, 80.0F, 40.0F));

        lease.close(owner);
        lease.close(owner);
        assertThat(control.yaw).isEqualTo(30.0F);
        assertThat(control.pitch).isEqualTo(18.0F);
        assertThat(control.slot).isEqualTo(1);
        assertThat(control.turnCount).isOne();
    }

    @Test
    void ownerTokenIsRequiredForTurnMatchAndClose() {
        var owner = UUID.randomUUID();
        var other = UUID.randomUUID();
        var lease = NavigationViewLease.acquire(
                new FakeViewControl(0.0F, 0.0F, 0), owner);

        assertThatIllegalArgumentException().isThrownBy(() -> lease.matches(other));
        assertThatIllegalArgumentException().isThrownBy(() ->
                lease.turnToward(other, 0.0F, 0.0F));
        assertThatIllegalArgumentException().isThrownBy(() -> lease.selectSlot(other, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> lease.selectSlot(owner, 9));
        assertThatIllegalArgumentException().isThrownBy(() -> lease.close(other));
    }

    @Test
    void cameraBoundIncludesVanillaFloatActuationRounding() {
        float yaw = -150.25603F;
        float desiredYaw = 99.29556F;

        assertThat(NavigationViewLease.cameraTravelUpperBound(
                yaw, 0.0F, desiredYaw, 0.0F, 100))
                .isEqualTo(110.44847106933594D);
    }

    private static final class FakeViewControl implements NavigationViewLease.ViewControl {
        private float yaw;
        private float pitch;
        private int slot;
        private int turnCount;

        private FakeViewControl(float yaw, float pitch, int slot) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.slot = slot;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public float pitch() {
            return pitch;
        }

        @Override
        public int selectedSlot() {
            return slot;
        }

        @Override
        public void turn(float yawDeltaDegrees, float pitchDeltaDegrees) {
            yaw += yawDeltaDegrees;
            pitch += pitchDeltaDegrees;
            turnCount++;
        }

        @Override
        public void selectSlot(int selected) {
            slot = selected;
        }
    }
}
