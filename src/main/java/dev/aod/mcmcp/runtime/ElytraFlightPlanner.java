package dev.aod.mcmcp.runtime;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

/** Rolling, finite Vanilla flight predictions. Unknown space is rejected by the supplied witness. */
final class ElytraFlightPlanner {
    private ElytraFlightPlanner() { }
    record Plan(float yaw, float pitch, boolean boost, int verifiedTicks, int horizon, double score, boolean landing) {
        boolean fullyVerified() { return verifiedTicks == horizon || landing; }
    }

    static float launchYaw(Vec3 position, Vec3 goal, float currentYaw) {
        return goal.subtract(position).horizontalDistanceSqr() < 1e-8 ? currentYaw
                : (float) Math.toDegrees(Math.atan2(position.x - goal.x, goal.z - position.z));
    }

    static boolean reserveLanding(boolean alreadyReserved, int verifiedCourseTicks) {
        return alreadyReserved || verifiedCourseTicks > 0;
    }

    static boolean reserveLanding(Vec3 position, Vec3 motion, Vec3 goal, long ticks, double distance, double camera) {
        double drop = Math.max(0, position.y - goal.y);
        return ticks <= Math.max(100, drop * 5 + 60)
                || distance <= Math.max(24, drop * 2 + motion.length() * 30)
                || camera <= 180;
    }

    static java.util.List<Vec3> landingProbes(Vec3 position, Vec3 motion, float yaw, Vec3 preferred) {
        var result = new java.util.ArrayList<Vec3>();
        if (preferred != null) result.add(preferred);
        result.add(position);
        double heading = motion.horizontalDistance() > .15
                ? Math.toDegrees(Math.atan2(-motion.x, motion.z)) : yaw;
        for (int distance : new int[] {8, 16, 28, 40})
            for (int offset : new int[] {0, -30, 30, -70, 70, 180})
                result.add(position.add(Vec3.directionFromRotation(0, (float) heading + offset).scale(distance)));
        return java.util.List.copyOf(result);
    }

    static double landingScore(Vec3 position, Vec3 motion, float yaw, Vec3 goal) {
        double targetYaw = Math.toDegrees(Math.atan2(position.x - goal.x, goal.z - position.z));
        double drop = Math.max(0, position.y - goal.y);
        double glideDistance = Math.max(8, drop * 1.3 + motion.horizontalDistance() * 16);
        return Math.abs(goal.subtract(position).horizontalDistance() - glideDistance)
                + Math.abs(Mth.wrapDegrees(targetYaw - yaw)) * .3
                + Math.max(0, goal.y - position.y) * 8;
    }

    static int horizon(double speed, int boostTicks, int latencyTicks) {
        return Math.min(100, Math.max(16 + latencyTicks, boostTicks + 12 + latencyTicks
                + (int) Math.ceil(Math.min(8, speed) * 4)));
    }

    static Plan coast(Vec3 position, Vec3 motion, float yaw, float pitch, double gravity, int latencyTicks,
            long remainingTicks, double remainingDistance, Predicate<AABB> witnessed, BooleanSupplier allowed) {
        int horizon = horizon(motion.length(), 0, latencyTicks);
        if (horizon < 1 || horizon > remainingTicks || !allowed.getAsBoolean()) return null;
        var body = new AABB(position.x - .3, position.y, position.z - .3,
                position.x + .3, position.y + .6, position.z + .3);
        if (!witnessed.test(body.inflate(.2))) return null;
        double distance = 0;
        // With no turn, phase change or boost, every steering-delay path is identical.
        for (int tick = 0; tick < horizon; tick++) {
            if (!allowed.getAsBoolean()) return null;
            motion = velocity(motion, yaw, pitch, gravity, false);
            distance += motion.length();
            if (!Double.isFinite(distance) || distance > remainingDistance
                    || !witnessed.test(body.expandTowards(motion).inflate(.2))) return null;
            body = body.move(motion);
        }
        return allowed.getAsBoolean() ? new Plan(yaw, pitch, false, horizon, horizon, 0, false) : null;
    }

    static boolean needsBoost(Vec3 position, Vec3 goal, int boostTicks, int latencyTicks) {
        // A rocket cannot be cancelled: reserve its full travel plus steering time before a nearby landing.
        return goal.y > position.y + 6
                || goal.subtract(position).horizontalDistance() > 1.7 * (boostTicks + latencyTicks + 12);
    }

    static Vec3 velocity(Vec3 motion, float yaw, float pitch, double gravity, boolean boost) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double horizontal = look.horizontalDistance(), speed = motion.horizontalDistance();
        double lift = Math.pow(Math.cos(Math.toRadians(pitch)), 2);
        Vec3 next = motion.add(0, gravity * (-1 + 0.75 * lift), 0);
        if (next.y < 0 && horizontal > 1e-6) {
            double converted = -next.y * 0.1 * lift;
            next = next.add(look.x / horizontal * converted, converted, look.z / horizontal * converted);
        }
        if (pitch < 0 && horizontal > 1e-6) {
            double climb = speed * -Math.sin(Math.toRadians(pitch)) * 0.04;
            next = next.add(-look.x / horizontal * climb, climb * 3.2, -look.z / horizontal * climb);
        }
        if (horizontal > 1e-6)
            next = next.add((look.x / horizontal * speed - next.x) * 0.1, 0,
                    (look.z / horizontal * speed - next.z) * 0.1);
        next = next.multiply(0.9900000095367432, 0.9800000190734863, 0.9900000095367432);
        return boost ? next.add(look.scale(0.1)).add(look.scale(1.5).subtract(next).scale(0.5)) : next;
    }

    static Plan plan(Vec3 position, Vec3 motion, float yaw, float pitch, Vec3 goal, double gravity,
                     int boostTicks, boolean newBoost, int latencyTicks, Predicate<AABB> witnessed) {
        return plan(position, motion, yaw, pitch, goal, gravity, boostTicks, newBoost, latencyTicks,
                witnessed, (box, speed) -> false);
    }

    static Plan plan(Vec3 position, Vec3 motion, float yaw, float pitch, Vec3 goal, double gravity,
                     int boostTicks, boolean newBoost, int latencyTicks, Predicate<AABB> witnessed,
                     BiPredicate<AABB, Vec3> landingWitness) {
        return plan(position, motion, yaw, pitch, goal, gravity, boostTicks, newBoost, latencyTicks,
                witnessed, landingWitness, Double.POSITIVE_INFINITY);
    }

    static Plan plan(Vec3 position, Vec3 motion, float yaw, float pitch, Vec3 goal, double gravity,
                     int boostTicks, boolean newBoost, int latencyTicks, Predicate<AABB> witnessed,
                     BiPredicate<AABB, Vec3> landingWitness, double cameraBudget) {
        double desiredYaw = Math.toDegrees(Math.atan2(position.x - goal.x, goal.z - position.z));
        int horizon = horizon(motion.length(), boostTicks, latencyTicks);
        Plan best = null;
        double[] offsets = cameraBudget < 180 ? new double[] {0, -30, 30, -65, 65, 180, yaw - desiredYaw}
                : new double[] {0, -30, 30, -65, 65, 180};
        float[] pitches = cameraBudget < 180 ? new float[] {-25, -45, -70, 0, 20, 40, pitch}
                : new float[] {-25, -45, -70, 0, 20, 40};
        for (double offset : offsets) {
            for (float desiredPitch : pitches) {
                float targetYaw = (float) (desiredYaw + offset);
                // Do not start a turn which cannot be finished within the remaining camera travel.
                if (Math.abs(Mth.wrapDegrees(targetYaw - yaw)) + Math.abs(desiredPitch - pitch) > cameraBudget) continue;
                float actualYaw = yaw, actualPitch = pitch;
                Vec3 at = position, velocity = motion;
                int verified = 0;
                boolean landing = false;
                // Immediate client steering and delayed server steering must both fit the observation.
                Vec3 immediateAt = position, immediateVelocity = motion;
                float immediateYaw = yaw, immediatePitch = pitch;
                boolean immediateLanded = false, delayedLanded = false;
                for (int tick = 0; tick < horizon; tick++) {
                    if (!immediateLanded) {
                        immediateYaw += Mth.clamp(Mth.wrapDegrees(targetYaw - immediateYaw), -8, 8);
                        immediatePitch += Mth.clamp(desiredPitch - immediatePitch, -8, 8);
                        immediateVelocity = velocity(immediateVelocity, immediateYaw, immediatePitch, gravity, tick < boostTicks);
                        var body = new AABB(immediateAt.x - 0.3, immediateAt.y, immediateAt.z - 0.3,
                                immediateAt.x + 0.3, immediateAt.y + 0.6, immediateAt.z + 0.3);
                        if (!witnessed.test(body.expandTowards(immediateVelocity).inflate(0.2))) {
                            immediateLanded = !newBoost && tick >= boostTicks && immediateAt.distanceTo(goal) < 8
                                    && landingWitness.test(body.move(immediateVelocity), immediateVelocity);
                            if (!immediateLanded) break;
                        }
                        immediateAt = immediateAt.add(immediateVelocity);
                    }
                    if (!delayedLanded) {
                        // A delayed path must also reach its own witnessed landing, possibly on a later tick.
                        if (tick >= latencyTicks) {
                            actualYaw += Mth.clamp(Mth.wrapDegrees(targetYaw - actualYaw), -8, 8);
                            actualPitch += Mth.clamp(desiredPitch - actualPitch, -8, 8);
                        }
                        velocity = velocity(velocity, actualYaw, actualPitch, gravity, tick < boostTicks);
                        var body = new AABB(at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 0.6, at.z + 0.3);
                        if (!witnessed.test(body.expandTowards(velocity).inflate(0.2))) {
                            delayedLanded = !newBoost && tick >= boostTicks && at.distanceTo(goal) < 8
                                    && landingWitness.test(body.move(velocity), velocity);
                            if (!delayedLanded) break;
                        }
                        at = at.add(velocity);
                    }
                    verified++;
                    landing = immediateLanded && delayedLanded;
                    if (landing) break;
                }
                // Partial evidence may guide an already-flying player, but may never authorize a new boost.
                if (verified == 0 || newBoost && verified != horizon) continue;
                double score = (landing ? horizon + 1 : verified) * 1000.0 - at.distanceTo(goal) - velocity.length() * 2
                        - Math.abs(Mth.wrapDegrees(targetYaw - yaw)) * 0.01;
                if (best == null || score > best.score())
                    best = new Plan(targetYaw, desiredPitch, newBoost, verified, horizon, score, landing);
            }
        }
        return best;
    }
}
