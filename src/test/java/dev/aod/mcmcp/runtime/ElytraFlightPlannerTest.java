package dev.aod.mcmcp.runtime;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ElytraFlightPlannerTest {
    @Test void verticallyAlignedLaunchKeepsHeadingAndNonzeroGoalsKeepTheirBearing() {
        var position = new Vec3(2, 30, 3);
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(0, 5, 0), -90)).isEqualTo(-90);
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(1e-6, 5, -1e-6), 123.5F)).isEqualTo(123.5F);
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(5, 0, 0), 17)).isEqualTo(-90);
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(-5, 0, 0), 17)).isEqualTo(90);
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(0, 0, 5), 17)).isZero();
        assertThat(ElytraFlightPlanner.launchYaw(position, position.add(0, 0, -5), 17)).isEqualTo(180);
    }

    @Test void completeCourseReservesLandingAfterItsProofIsLostEvenWhenTheOldAnchorNeedsBoost() {
        var position = new Vec3(0, 30, 0);
        boolean reserved = ElytraFlightPlanner.reserveLanding(position, new Vec3(.3, -.1, 0),
                new Vec3(40, 0, 0), 600, 128, 360);
        assertThat(reserved).isFalse();
        assertThat(ElytraFlightPlanner.reserveLanding(reserved, 0)).isFalse();
        reserved = ElytraFlightPlanner.reserveLanding(reserved, 94);
        assertThat(reserved).isTrue();
        reserved = ElytraFlightPlanner.reserveLanding(reserved, 0);
        assertThat(reserved).isTrue();
        assertThat(ElytraFlightPlanner.needsBoost(position, new Vec3(0, 100, 0), 32, 4)).isTrue();
    }

    @Test void independentlyWitnessedClientAndDelayedLandingsMayOccurOnDifferentTicks() {
        var plan = ElytraFlightPlanner.plan(new Vec3(0, 2, 0), new Vec3(0, -.1, .6),
                0, -25, new Vec3(0, 0, 8), .08, 0, false, 4, box -> box.minY > 0,
                (box, speed) -> box.minY >= -.4 && box.minY <= .3
                        && box.minZ >= 5 && box.maxZ <= 30 && box.minX >= -4 && box.maxX <= 4
                        && speed.y >= -.6 && speed.y <= 0 && speed.horizontalDistance() <= .65);
        assertThat(plan).isNotNull();
        assertThat(plan.landing()).isTrue();
        assertThat(plan.boost()).isFalse();
    }
    @Test void oneWitnessedLandingDoesNotAuthorizeTheOtherPathToOvershootABank() {
        var plan = ElytraFlightPlanner.plan(new Vec3(0, 2, 0), new Vec3(0, -.1, .6),
                0, -25, new Vec3(0, 0, 8), .08, 0, false, 4, box -> box.minY > 0,
                (box, speed) -> box.minY >= -.4 && box.minY <= .3
                        && box.minZ >= 5 && box.maxZ <= 8.5 && box.minX >= -4 && box.maxX <= 4
                        && speed.y >= -.6 && speed.y <= 0 && speed.horizontalDistance() <= .65);
        assertThat(plan).isNotNull();
        // The -70 degree approach touches down near z=7.34 and z=9.27; only its first path fits this bank.
        assertThat(plan.landing() && plan.yaw() == 0 && plan.pitch() == -70)
                .as("Selected approach: %s", plan).isFalse();
        assertThat(plan.boost()).isFalse();
    }
    @Test void exhaustedCameraCanKeepAnObservedCoastWithoutTurningBackToAnOvershotLanding() {
        var plan = ElytraFlightPlanner.plan(new Vec3(0, 5, 0), new Vec3(0, -.1, .4),
                0, -25, new Vec3(0, 0, -5), .08, 0, false, 4,
                box -> box.minY > 0, (box, speed) -> false, 0);
        assertThat(plan).isNotNull();
        assertThat(net.minecraft.util.Mth.wrapDegrees(plan.yaw())).isZero();
        assertThat(plan.pitch()).isEqualTo(-25);
        assertThat(plan.boost()).isFalse();
        assertThat(ElytraFlightPlanner.plan(new Vec3(0, 5, 0), new Vec3(0, -.1, .4),
                0, -25, new Vec3(0, 0, -5), .08, 0, false, 4,
                box -> false, (box, speed) -> false, 0)).isNull();
    }

    @Test void landingReserveIncludesDescentAndDoesNotWaitForBudgetExhaustion() {
        var position = new Vec3(0, 121, 0);
        var goal = new Vec3(28, 101, 0);
        var speed = new Vec3(.7, -.2, 0);
        assertThat(ElytraFlightPlanner.reserveLanding(position, speed, goal, 500, 128, 360)).isFalse();
        assertThat(ElytraFlightPlanner.reserveLanding(position, speed, goal, 140, 128, 360)).isTrue();
        assertThat(ElytraFlightPlanner.reserveLanding(position, speed, goal, 500, 55, 360)).isTrue();
        assertThat(ElytraFlightPlanner.reserveLanding(position, speed, goal, 500, 128, 175)).isTrue();
    }

    @Test void landingSearchIsFiniteAndPrefersReachableForwardGroundOverLostAnchor() {
        var position = new Vec3(0, 121, 0);
        var speed = new Vec3(.3, -.2, 0);
        var probes = ElytraFlightPlanner.landingProbes(position, speed, -90, position);
        assertThat(probes).hasSize(26);
        assertThat(probes).allMatch(point -> point.distanceTo(position) <= 40.001);
        assertThat(ElytraFlightPlanner.landingScore(position, speed, -90, new Vec3(28, 101, 0)))
                .isLessThan(ElytraFlightPlanner.landingScore(position, speed, -90, new Vec3(0, 121, 0)))
                .isLessThan(ElytraFlightPlanner.landingScore(position, speed, -90, new Vec3(-28, 101, 0)));
    }
    @Test void rollingGlideReachesAFlatLandingWithoutOrbitingUntilItsBudgetExpires() {
        var position = new Vec3(0, 20, 0);
        var motion = new Vec3(.24, -.1, 0);
        var goal = new Vec3(25, 0, 0);
        float yaw = -90, pitch = 0;
        for (int tick = 0; tick < 500; tick++) {
            var plan = ElytraFlightPlanner.plan(position, motion, yaw, pitch, goal, .08,
                    0, false, 4, box -> box.minY > 0,
                    (box, speed) -> box.minY <= .3 && box.minY >= -.4
                            && speed.y >= -.6 && speed.y <= 0 && speed.horizontalDistance() <= .65);
            assertThat(plan).as("flight tick %s at %s with %s", tick, position, motion).isNotNull();
            yaw += net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(plan.yaw() - yaw), -8, 8);
            pitch += net.minecraft.util.Mth.clamp(plan.pitch() - pitch, -8, 8);
            motion = ElytraFlightPlanner.velocity(motion, yaw, pitch, .08, false);
            position = position.add(motion);
            if (position.y <= 0) {
                assertThat(motion.y).isGreaterThanOrEqualTo(-.6);
                assertThat(motion.horizontalDistance()).isLessThanOrEqualTo(.65);
                assertThat(position.subtract(goal).horizontalDistance()).isLessThan(2);
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("never landed: " + position + " with " + motion);
    }
    @Test void nearbyLowerLandingDoesNotRequestAnUnstoppableRocket() {
        var start = new Vec3(0, 121, 0);
        assertThat(ElytraFlightPlanner.needsBoost(start, new Vec3(25, 101, 0), 31, 4)).isFalse();
        assertThat(ElytraFlightPlanner.needsBoost(start, new Vec3(120, 101, 0), 31, 4)).isTrue();
        assertThat(ElytraFlightPlanner.needsBoost(start, new Vec3(25, 132, 0), 31, 4)).isTrue();
    }
    @Test void speedAndLatencyExtendLookahead() {
        assertThat(ElytraFlightPlanner.horizon(3, 31, 12)).isGreaterThan(ElytraFlightPlanner.horizon(0.4, 31, 4));
        assertThat(ElytraFlightPlanner.horizon(1000, 31, 1000)).isEqualTo(100);
    }
    @Test void unknownSpaceNeverAuthorizesRocket() {
        var origin = new Vec3(0, 80, 0);
        var motion = new Vec3(0, -0.2, 3);
        assertThat(ElytraFlightPlanner.plan(origin, motion, 0, 0, new Vec3(0, 80, 100), .08,
                31, true, 4, box -> box.maxZ < 12)).isNull();
        var glide = ElytraFlightPlanner.plan(origin, motion, 0, 0, new Vec3(0, 80, 100), .08,
                0, false, 4, box -> box.maxZ < 12);
        assertThat(glide).isNotNull();
        assertThat(glide.boost()).isFalse();
        assertThat(glide.fullyVerified()).isFalse();
    }
    @Test void unobstructedObservedSpacePermitsFiniteBoost() {
        var plan = ElytraFlightPlanner.plan(new Vec3(0, 100, 0), new Vec3(0, -.1, .8),
                0, 0, new Vec3(0, 100, 100), .08, 31, true, 4, box -> true);
        assertThat(plan).isNotNull();
        assertThat(plan.boost()).isTrue();
        assertThat(plan.verifiedTicks()).isEqualTo(plan.horizon());
    }
    @Test void noObservedFirstStepProducesNoCommand() {
        assertThat(ElytraFlightPlanner.plan(new Vec3(0, 100, 0), new Vec3(0, -.1, 3),
                0, 0, new Vec3(0, 100, 100), .08, 0, false, 4, box -> false)).isNull();
    }
    @Test void pullingUpConvertsForwardSpeedIntoLiftRatherThanStoppingVelocity() {
        var motion = new Vec3(0, -.2, 2.5);
        var level = ElytraFlightPlanner.velocity(motion, 0, 0, .08, false);
        var pullUp = ElytraFlightPlanner.velocity(motion, 0, -45, .08, false);
        assertThat(pullUp.y).isGreaterThan(level.y);
        assertThat(pullUp.horizontalDistance()).isGreaterThan(2);
        assertThat(pullUp.horizontalDistance()).isLessThan(level.horizontalDistance());
    }
    @Test void landingNeedsAnExplicitWitnessAndNeverEnablesBoostThroughCollision() {
        var origin = new Vec3(0, 1, 0);
        var motion = new Vec3(0, -.1, .1);
        var plan = ElytraFlightPlanner.plan(origin, motion, 0, 20, Vec3.ZERO, .08,
                0, false, 4, box -> false, (box, speed) -> true);
        assertThat(plan).isNotNull();
        assertThat(plan.landing()).isTrue();
        assertThat(ElytraFlightPlanner.plan(origin, motion, 0, 20, Vec3.ZERO, .08,
                31, true, 4, box -> false, (box, speed) -> true)).isNull();
    }
}
