package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static dev.aod.mcmcp.runtime.EmergencyRecoveryPolicy.*;
import static org.assertj.core.api.Assertions.assertThat;

class EmergencyRecoveryPolicyTest {
    @org.junit.jupiter.api.Test void missingSupportTriggersBeforeGroundFlagOrFallDistanceUpdate() {
        org.assertj.core.api.Assertions.assertThat(EmergencyRecoveryPolicy.unsupportedDescent(true, false, false, 0)).isTrue();
        org.assertj.core.api.Assertions.assertThat(EmergencyRecoveryPolicy.unsupportedDescent(false, false, false, -0.078)).isTrue();
        org.assertj.core.api.Assertions.assertThat(EmergencyRecoveryPolicy.unsupportedDescent(true, true, false, 0)).isFalse();
        org.assertj.core.api.Assertions.assertThat(EmergencyRecoveryPolicy.unsupportedDescent(false, false, false, 0.3)).isFalse();
    }
    @Test void ladderPauseOrDescentIsNotAFallButLosingTheLadderIs() {
        assertThat(unsupportedDescent(false, false, true, 0)).isFalse();
        assertThat(unsupportedDescent(false, false, true, -.15)).isFalse();
        assertThat(unsupportedDescent(false, false, false, -.15)).isTrue();
    }
    @Test void fallPrefersFlightThenWaterThenLocalClutch() {
        var items = Set.of("minecraft:water_bucket", "minecraft:cobweb", "minecraft:oak_boat");
        assertThat(choose(new Situation(Danger.FALL, true, true, true, false, true, true, items),
                true, true, true, true, false).response()).isEqualTo(Response.ELYTRA);
        assertThat(choose(new Situation(Danger.FALL, false, true, true, false, true, true, items),
                true, true, true, true, false).response()).isEqualTo(Response.LAND_IN_WATER);
        assertThat(choose(new Situation(Danger.FALL, false, false, false, false, true, true, items),
                true, true, true, true, false).item()).isEqualTo("minecraft:water_bucket");
        assertThat(choose(new Situation(Danger.FALL, false, false, false, true, true, true, items),
                true, true, true, true, false).item()).isEqualTo("minecraft:cobweb");
    }
    @Test void playerAndPassiveNeutralNeverTriggerRetaliation() {
        for (var danger : new Danger[] {Danger.PLAYER_ATTACK, Danger.PLAYER_INTERFERENCE, Danger.MOB_INTERFERENCE, Danger.NONE})
            assertThat(choose(new Situation(danger, false, false, false, false, false, true, Set.of()),
                    true, true, true, true, false).response()).isNotEqualTo(Response.RETALIATE);
        assertThat(choose(new Situation(Danger.MOB_ATTACK, false, false, false, false, false, true, Set.of()),
                true, true, true, true, false).response()).isEqualTo(Response.RETALIATE);
    }
    @Test void passiveMobContactRetreatsWithoutCombatOrDisconnect() {
        assertThat(displacedByContact(false, 0.1)).isFalse();
        assertThat(displacedByContact(true, 0)).isFalse();
        assertThat(displacedByContact(true, 0.04)).isTrue();
        assertThat(choose(new Situation(Danger.MOB_INTERFERENCE, true, true, false, false, true, true,
                Set.of("minecraft:bow", "minecraft:diamond_sword")), true, true, true, true, true).response())
                .isEqualTo(Response.RETREAT);
    }
    @Test void resumeWaitsForClearanceEvenAfterPushVelocityStops() {
        var trader = new net.minecraft.world.phys.AABB(310.55, 121, 310.2, 311.15, 122.9, 310.8);
        var stoppedBesideTrader = new net.minecraft.world.phys.AABB(309.6, 121, 310.2, 310.2, 122.8, 310.8);
        assertThat(displacedByContact(true, 0)).isFalse();
        assertThat(contactCleared(stoppedBesideTrader, trader)).isFalse();
        assertThat(contactCleared(stoppedBesideTrader.move(-1, 0, 0), trader)).isTrue();
    }
    @Test void fatalFallDoesNotDisconnectUnlessEnabled() {
        var fall = new Situation(Danger.FALL, false, false, false, false, true, false, Set.of());
        assertThat(choose(fall, true, true, true, true, false).response()).isEqualTo(Response.RETREAT);
        assertThat(choose(fall, true, true, true, true, true).response()).isEqualTo(Response.DISCONNECT);
    }
    @Test void weaponOrderRequiresUsableRangedCorridor() {
        assertThat(weaponPriority("minecraft:bow", true, true)).isLessThan(weaponPriority("minecraft:diamond_sword", true, true));
        assertThat(weaponPriority("minecraft:bow", true, false)).isEqualTo(Integer.MAX_VALUE);
        assertThat(weaponPriority("minecraft:bow", false, true)).isEqualTo(Integer.MAX_VALUE);
        assertThat(weaponPriority("minecraft:iron_sword", true, true)).isLessThan(weaponPriority("minecraft:iron_axe", true, true));
        assertThat(weaponPriority("minecraft:iron_axe", true, true)).isLessThan(weaponPriority("minecraft:iron_pickaxe", true, true));
        assertThat(boat("minecraft:oak_chest_boat")).isFalse();
        assertThat(boat("mod:oak_boat")).isFalse();
    }
}
