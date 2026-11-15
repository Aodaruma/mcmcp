package dev.aod.mcmcp.runtime;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A finite unpowered approach, with room to descend and brake before touching a small landing. */
record ElytraLandingCourse(Vec3 goal, float yaw, int approachTicks, int flareTicks, int coastTicks, float finalPitch) {
    private static final int MAX_TICKS = 128;

    static boolean descendingGoal(Vec3 position, Vec3 goal) {
        return goal != null && goal.y < position.y;
    }

    static boolean retainsProvenGoal(Vec3 position, Vec3 goal, Vec3 threat, int verifiedTicks) {
        return verifiedTicks > 0 && descendingGoal(position, goal)
                && (threat == null || goal.distanceToSqr(threat) >= 36);
    }

    record Selection(ElytraLandingCourse course, int verifiedTicks) { }

    static BooleanSupplier boundedSearch(BooleanSupplier allowed) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20);
        return () -> allowed.getAsBoolean() && System.nanoTime() - deadline < 0;
    }

    float pitch(int elapsed) {
        return elapsed < approachTicks ? 0 : elapsed < approachTicks + flareTicks ? -25
                : elapsed < approachTicks + flareTicks + coastTicks ? 0 : finalPitch;
    }

    static Selection select(Vec3 position, Vec3 motion, float yaw, float pitch, Vec3 goal,
            double gravity, int latency, long remainingTicks, double remainingDistance, double remainingCamera,
            Predicate<AABB> clear, BiPredicate<AABB, Vec3> stoppedLanding, BooleanSupplier allowed) {
        if (!descendingGoal(position, goal) || latency > 20) return null;
        float bearing = (float) Math.toDegrees(Math.atan2(position.x - goal.x, goal.z - position.z));
        // These are control intervals, not destinations or a guarantee that a landing exists.
        for (int offset : new int[] {0, -5, 5, -15, 15})
            for (int approach : new int[] {0, 8, 16})
                for (int flare : new int[] {16, 32, 48, 64})
                    for (int coast : new int[] {8, 20, 24, 32})
                        for (float finalPitch : new float[] {-45, -50, -55, -60, -65, -70}) {
                            if (!allowed.getAsBoolean()) return null;
                            var course = new ElytraLandingCourse(goal, bearing + offset, approach, flare, coast, finalPitch);
                            int verified = course.verifiedTicks(position, motion, yaw, pitch, gravity, 0, latency,
                                    remainingTicks, remainingDistance, remainingCamera, clear, stoppedLanding, allowed);
                            if (verified > 0) return new Selection(course, verified);
                        }
        return null;
    }

    Selection replan(Vec3 position, Vec3 motion, float yaw, float pitch, double gravity, int elapsed,
            int latency, long remainingTicks, double remainingDistance, double remainingCamera,
            Predicate<AABB> clear, BiPredicate<AABB, Vec3> stoppedLanding, BooleanSupplier allowed) {
        var attempted = new java.util.HashSet<ElytraLandingCourse>();
        for (int first : new int[] {0, -2, 2, -4, 4, -8, 8})
            for (int second : new int[] {0, -2, 2, -4, 4, -8, 8})
                for (int third : new int[] {0, -2, 2, -4, 4, -8, 8})
                    for (float finalPitch : new float[] {this.finalPitch, -55, -60, -65}) {
                        if (!allowed.getAsBoolean()) return null;
                        int a = Math.max(0, approachTicks + first - elapsed);
                        int b = Math.max(a, approachTicks + flareTicks + second - elapsed);
                        int c = Math.max(b, approachTicks + flareTicks + coastTicks + third - elapsed);
                        var course = new ElytraLandingCourse(goal, this.yaw, a, b - a, c - b, finalPitch);
                        if (!attempted.add(course)) continue;
                        int verified = course.verifiedTicks(position, motion, yaw, pitch, gravity, 0, latency,
                                remainingTicks, remainingDistance, remainingCamera, clear, stoppedLanding, allowed);
                        if (verified > 0) return new Selection(course, verified);
                    }
        return null;
    }

    int verifiedTicks(Vec3 position, Vec3 motion, float yaw, float pitch, double gravity, int elapsed, int latency,
            long remainingTicks, double remainingDistance, double remainingCamera,
            Predicate<AABB> clear, BiPredicate<AABB, Vec3> stoppedLanding, BooleanSupplier allowed) {
        int horizon = (int) Math.min(MAX_TICKS, Math.max(0, remainingTicks - 32));
        if (horizon < 1 || latency > 20) return 0;
        var paths = new ArrayList<Path>(4);
        // First reject impossible physics without consuming the bounded world-observation cache.
        for (int[] delay : new int[][] {{0, 0}, {latency, latency}, {0, latency}, {latency, 0}}) {
            if (!allowed.getAsBoolean()) return 0;
            var path = predict(position, motion, yaw, pitch, gravity, elapsed, horizon, delay[0], delay[1],
                    remainingDistance, remainingCamera, allowed);
            if (path == null) return 0;
            paths.add(path);
        }
        for (var path : paths) {
            for (var contact : path.contacts()) {
                if (!allowed.getAsBoolean()) return 0;
                if (!stoppedLanding.test(contact.body(), contact.motion())) return 0;
            }
            for (var swept : path.sweeps()) {
                if (!allowed.getAsBoolean() || !clear.test(swept)) return 0;
            }
        }
        return allowed.getAsBoolean() ? paths.stream().mapToInt(path -> path.sweeps().size()).max().orElse(0) : 0;
    }

    private Path predict(Vec3 position, Vec3 motion, float yaw, float pitch, double gravity, int elapsed,
            int horizon, int turnDelay, int phaseDelay, double distanceBudget, double cameraBudget, BooleanSupplier allowed) {
        var sweeps = new ArrayList<AABB>();
        var contacts = new ArrayList<Contact>();
        double distance = 0, camera = 0;
        for (int tick = 0; tick < horizon; tick++) {
            if (!allowed.getAsBoolean()) return null;
            if (tick >= turnDelay) {
                float dy = Mth.clamp(Mth.wrapDegrees(this.yaw - yaw), -8, 8);
                float dp = Mth.clamp(pitch(elapsed + Math.max(0, tick - phaseDelay)) - pitch, -8, 8);
                camera += Math.abs(dy) + Math.abs(dp);
                if (camera > cameraBudget) return null;
                yaw += dy; pitch += dp;
            }
            motion = ElytraFlightPlanner.velocity(motion, yaw, pitch, gravity, false);
            var next = position.add(motion);
            distance += motion.length();
            if (distance > distanceBudget) return null;
            var body = new AABB(position.x - .3, position.y, position.z - .3,
                    position.x + .3, position.y + .6, position.z + .3);
            var swept = body.expandTowards(motion).inflate(.2);
            if (next.y <= goal.y + .3 && next.distanceTo(goal) < 8 && motion.y >= -.6
                    && motion.y <= 0 && motion.horizontalDistance() <= .65) {
                contacts.add(new Contact(body.move(motion), motion));
                // The padding may reach the floor before the feet; its support is proved separately.
                swept = new AABB(swept.minX, goal.y + .01, swept.minZ, swept.maxX, swept.maxY, swept.maxZ);
            }
            if (position.y >= goal.y && next.y <= goal.y) {
                if (next.y < goal.y - .4 || next.distanceTo(goal) >= 8 || motion.y < -.6
                        || motion.horizontalDistance() > .65
                        || distance + motion.horizontalDistance() * 15 > distanceBudget) return null;
                sweeps.add(swept);
                return new Path(List.copyOf(sweeps), List.copyOf(contacts));
            }
            if (next.y < goal.y) return null;
            sweeps.add(swept);
            position = next;
        }
        return null;
    }

    private record Contact(AABB body, Vec3 motion) { }
    private record Path(List<AABB> sweeps, List<Contact> contacts) { }
}
