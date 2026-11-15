package dev.aod.mcmcp.runtime;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftEmergencyCombatTest {
    @Test void failedBowCleanupStillClosesEveryOtherOwnerAndPreservesTheFailure() {
        var closed = new java.util.ArrayList<String>();
        var sendFailure = new IllegalStateException("bow send failed");
        var shieldFailure = new LinkageError("shield hook failed");
        assertThatThrownBy(() -> MinecraftEmergencyCombat.closeAll(
                () -> { closed.add("bow"); throw sendFailure; },
                () -> { closed.add("shield"); throw shieldFailure; },
                () -> closed.add("view"), () -> closed.add("selection")))
                .isSameAs(sendFailure).hasSuppressedException(shieldFailure);
        assertThat(closed).containsExactly("bow", "shield", "view", "selection");
    }

    @Test void shieldPredictionNeedsAnApproachingMovingArrow() {
        var body = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        assertThat(MinecraftEmergencyCombat.approachingArrow(body, new Vec3(10, 1.5, 0), new Vec3(-1.6, .1, 0))).isTrue();
        assertThat(MinecraftEmergencyCombat.approachingArrow(body, new Vec3(10, 1.5, 0), new Vec3(1.6, .1, 0))).isFalse();
        assertThat(MinecraftEmergencyCombat.approachingArrow(body, new Vec3(1, 1.5, 0), Vec3.ZERO)).isFalse();
        assertThat(MinecraftEmergencyCombat.approachingArrow(body, new Vec3(10, 1.5, 5), new Vec3(-1.6, .1, 0))).isFalse();
    }
}
