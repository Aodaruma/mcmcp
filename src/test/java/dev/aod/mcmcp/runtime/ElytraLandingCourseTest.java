package dev.aod.mcmcp.runtime;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ElytraLandingCourseTest {
    private static final Vec3 START = new Vec3(314.7903699386222, 118.96322085134673, 310.5);
    private static final Vec3 MOTION = new Vec3(.05952456415649368, -.2869482726471545, 0);
    private static final Vec3 GOAL = new Vec3(349.33973759412766, 101, 290.5011074542999);

    @Test void aSupportedPlatformAboveTheCurrentFlightCannotRemainADescendingGoal() {
        var fallen = new Vec3(314.65, 120.92, 310.5);
        assertThat(ElytraLandingCourse.descendingGoal(fallen, new Vec3(322.65, 121, 310.5))).isFalse();
        assertThat(ElytraLandingCourse.descendingGoal(fallen, new Vec3(322.65, 120.92, 310.5))).isFalse();
        assertThat(ElytraLandingCourse.descendingGoal(fallen, GOAL)).isTrue();
        assertThat(ElytraLandingCourse.descendingGoal(fallen, null)).isFalse();
    }

    @Test void recordedWaterCanHideADryStoppingFloorEvenWhenItsStandingAirIsVisible() throws Exception {
        var witness = new Witness(START);
        var standingAir = new Vec3(345.5, 102.5, 292.5);
        assertThat(witness.shapes.stream().noneMatch(shape -> shape.clip(witness.eye, standingAir).isPresent())).isTrue();
        assertThat(witness.visibleSupport(new BlockPos(345, 100, 292))).isFalse();
        assertThat(witness.visibleSupport(new BlockPos(349, 100, 290))).isTrue();
    }

    @Test void recordedFloodedBankCanBeReachedAndStoppedWithoutWeakeningTheObservation() throws Exception {
        reachesBank(START, MOTION, GOAL);
    }

    @Test void nativeReselectedLowerBankMustAlsoHaveVisibleStoppingSupports() throws Exception {
        var underLip = new Vec3(314.69999998807907, 119.58493859291438, 310.5061103886526);
        assertThat(new Witness(underLip).clear(body(underLip).inflate(.2))).isFalse();
        var clearStart = new Vec3(314.81104254261817, 119.03582048849036, 310.5061103886526);
        assertThat(new Witness(clearStart).clear(body(clearStart).inflate(.2))).isTrue();
        // The first safe admission is three ticks later, before the old eight-tick retry.
        reachesBank(clearStart, new Vec3(.054910817660004226, -.17892583470763604, 0),
                new Vec3(349.33973759412766, 101, 290.5072178429525));
    }

    private static void reachesBank(Vec3 start, Vec3 initialMotion, Vec3 goal) throws Exception {
        var witness = new Witness(start);
        var course = ElytraLandingCourse.select(start, initialMotion, -90, 0, goal, .08, 4,
                600, 128, 344, witness::clear, witness::stoppedLanding, () -> true);
        assertThat(course).isNotNull();
        assertThat(witness.cells.size() + witness.supports.size()).isLessThanOrEqualTo(2048);
        var position = start;
        var motion = initialMotion;
        float yaw = -90, pitch = 0;
        double camera = 0, distance = 0;
        int started = 0;
        for (int tick = 0; tick < 160; tick++) {
            witness = new Witness(position);
            int verified = course.verifiedTicks(position, motion, yaw, pitch, .08, tick - started, 4,
                    600 - tick, 128 - distance, 344 - camera, witness::clear, witness::stoppedLanding, () -> true);
            if (verified == 0) {
                course = course.replan(position, motion, yaw, pitch, .08, tick - started, 4,
                        600 - tick, 128 - distance, 344 - camera, witness::clear, witness::stoppedLanding, () -> true);
                if (course == null) course = ElytraLandingCourse.select(position, motion, yaw, pitch, goal, .08, 4,
                            600 - tick, 128 - distance, 344 - camera, witness::clear, witness::stoppedLanding, () -> true);
                started = tick;
                assertThat(course).as("fresh replacement at tick %s: %s, rejected=%s", tick, position, witness.rejection).isNotNull();
            }
            float dy = Mth.clamp(Mth.wrapDegrees(course.yaw() - yaw), -8, 8);
            float dp = Mth.clamp(course.pitch(tick - started) - pitch, -8, 8);
            yaw += dy; pitch += dp; camera += Math.abs(dy) + Math.abs(dp);
            motion = ElytraFlightPlanner.velocity(motion, yaw, pitch, .08, false);
            position = position.add(motion);
            distance += motion.length();
            if (position.y <= goal.y) {
                assertThat(witness.stoppedLanding(body(position), motion)).isTrue();
                assertThat(position.distanceTo(goal)).isLessThan(8);
                assertThat(camera).isLessThan(344);
                assertThat(distance).isLessThan(128);
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("never reached the witnessed bank");
    }

    @Test void allSteeringDelaysAndTheirCompleteStoppingFootprintsMustRemainWitnessed() throws Exception {
        float bearing = (float) Math.toDegrees(Math.atan2(START.x - GOAL.x, GOAL.z - START.z));
        var course = new ElytraLandingCourse(GOAL, bearing, 0, 16, 32, -55);
        var witness = new Witness(START);
        assertThat(course.verifiedTicks(START, MOTION, -90, 0, .08, 0, 4, 600, 128, 344,
                witness::clear, witness::stoppedLanding, () -> true)).isPositive();
        witness = new Witness(START);
        assertThat(course.verifiedTicks(START, MOTION, -90, 0, .08, 0, 4, 600, 128, 344,
                box -> false, witness::stoppedLanding, () -> true)).isZero();
        witness = new Witness(START);
        assertThat(course.verifiedTicks(START, MOTION, -90, 0, .08, 0, 4, 600, 128, 344,
                witness::clear, (box, speed) -> false, () -> true)).isZero();
        witness = new Witness(START);
        var bank = witness;
        assertThat(course.verifiedTicks(START, MOTION, -90, 0, .08, 0, 4, 600, 128, 344,
                witness::clear, (box, speed) -> box.maxX < 348 && bank.stoppedLanding(box, speed), () -> true))
                .as("the immediate landing cannot authorize a delayed overshoot").isZero();
    }

    @Test void aCompleteLandingMustFitTheExistingTimeDistanceAndCameraBudgets() throws Exception {
        for (var budget : List.of(new double[] {64, 128, 344}, new double[] {600, 20, 344},
                new double[] {600, 128, 100})) {
            var witness = new Witness(START);
            assertThat(ElytraLandingCourse.select(START, MOTION, -90, 0, GOAL, .08, 4,
                    (long) budget[0], budget[1], budget[2], witness::clear, witness::stoppedLanding, () -> true)).isNull();
        }
    }

    @Test void cancellationStopsCandidateSearchAndPredictionBeforeReadingAnyWorldEvidence() {
        var evidence = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<AABB> clear = box -> { evidence.incrementAndGet(); return true; };
        java.util.function.BiPredicate<AABB, Vec3> support = (box, speed) -> { evidence.incrementAndGet(); return true; };
        var course = new ElytraLandingCourse(GOAL, -120, 8, 48, 20, -70);
        assertThat(ElytraLandingCourse.select(START, MOTION, -90, 0, GOAL, .08, 4,
                600, 128, 344, clear, support, () -> false)).isNull();
        assertThat(course.replan(START, MOTION, -90, 0, .08, 0, 4,
                600, 128, 344, clear, support, () -> false)).isNull();
        assertThat(course.verifiedTicks(START, MOTION, -90, 0, .08, 0, 4,
                600, 128, 344, clear, support, () -> false)).isZero();
        assertThat(evidence).hasValue(0);
    }

    @Test void aSearchDeadlineExpiresEvenWhileTheActionRemainsAuthorized() throws InterruptedException {
        var authorized = new java.util.concurrent.atomic.AtomicBoolean(true);
        var search = ElytraLandingCourse.boundedSearch(authorized::get);
        Thread.sleep(30);
        assertThat(search.getAsBoolean()).isFalse();
        assertThat(authorized).isTrue();
        assertThat(ElytraLandingCourse.boundedSearch(authorized::get).getAsBoolean()).isTrue();
    }

    private static AABB body(Vec3 p) { return new AABB(p.x - .3, p.y, p.z - .3, p.x + .3, p.y + .6, p.z + .3); }

    /** The recorded private fixture's actual flowing water, not a rectangular source-water approximation. */
    private static final class Witness {
        final Vec3 eye;
        final Map<BlockPos, Boolean> cells = new HashMap<>();
        final Map<BlockPos, Boolean> supports = new HashMap<>();
        final String[][] layers = new String[5][41];
        final List<AABB> shapes = new ArrayList<>();
        String rejection;
        Witness(Vec3 position) throws Exception {
            eye = position.add(0, .4, 0);
            try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                    "/flight/recovery-bank.json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(input).getAsJsonArray();
                for (var layer : data) {
                    var item = layer.getAsJsonObject();
                    int y = item.get("y").getAsInt();
                    var rows = item.getAsJsonArray("rows");
                    for (int z = 0; z < 41; z++) layers[y - 100][z] = rows.get(z).getAsString();
                    var active = new HashMap<String, Integer>();
                    for (int z = 0; z <= 41; z++) {
                        String row = z == 41 ? ".".repeat(61) : layers[y - 100][z];
                        var runs = new HashSet<String>();
                        for (int start = 0, end = 1; end <= 61; end++) {
                            if (end == 61 || row.charAt(end) != row.charAt(start)) {
                                if (row.charAt(start) != '.') runs.add(start + "," + end + "," + row.charAt(start));
                                start = end;
                            }
                        }
                        for (var run : List.copyOf(active.keySet())) if (!runs.contains(run)) {
                            var parts = run.split(",");
                            double height = parts[2].equals("#") ? 1 : Integer.parseInt(parts[2]) / 9.0;
                            shapes.add(new AABB(290 + Integer.parseInt(parts[0]), y, 290 + active.remove(run),
                                    290 + Integer.parseInt(parts[1]), y + height, 290 + z));
                        }
                        for (var run : runs) active.putIfAbsent(run, z);
                    }
                }
            }
            shapes.add(new AABB(315, 120, 308, 341, 121, 315));
            shapes.add(new AABB(308, 120, 307, 341, 121, 308));
        }
        boolean clear(AABB box) {
            if (box.getSize() > 96 || box.getCenter().distanceTo(eye) + box.getSize() > 96) return false;
            for (var p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                Boolean known = cells.get(p);
                if (known == null) {
                    if (cells.size() + supports.size() >= 2048) return false;
                    var point = Vec3.atCenterOf(p);
                    known = point.distanceTo(eye) <= 96 && cell(p.getX(), p.getY(), p.getZ()) == '.'
                            && shapes.stream().noneMatch(shape -> shape.contains(point) || shape.clip(eye, point).isPresent());
                    cells.put(p.immutable(), known);
                }
                if (!known) { rejection = p.toString(); return false; }
            }
            return true;
        }
        boolean stoppedLanding(AABB box, Vec3 motion) {
            if (box.minY < 100.6 || box.minY > 101.3 || motion.y > 0 || motion.y < -.6
                    || motion.horizontalDistance() > .65) return false;
            double x = box.getCenter().x, z = box.getCenter().z, vx = motion.x, vz = motion.z;
            for (int tick = 0; tick < 32; tick++) {
                var standing = new AABB(Math.min(x, x + vx) - .4, 101.01, Math.min(z, z + vz) - .4,
                        Math.max(x, x + vx) + .4, 102.8, Math.max(z, z + vz) + .4);
                for (int a = Mth.floor(standing.minX); a <= Mth.floor(standing.maxX); a++) {
                    for (int b = Mth.floor(standing.minZ); b <= Mth.floor(standing.maxZ); b++) {
                        if (cell(a, 100, b) != '#' || cell(a, 101, b) != '.') return false;
                        var support = new BlockPos(a, 100, b);
                        if (!supports.containsKey(support)) {
                            if (cells.size() + supports.size() >= 2048) return false;
                            supports.put(support, visibleSupport(support));
                        }
                        if (!supports.get(support)) return false;
                    }
                }
                if (!clear(standing)) return false;
                x += vx; z += vz;
                if (tick >= 2) { vx *= .546; vz *= .546; }
                if (Math.hypot(vx, vz) < .005) return true;
            }
            return false;
        }
        boolean visibleSupport(BlockPos support) {
            return FlightObservation.visibleLandingSupport(eye, support, 101, 96, 96,
                    pos -> true, (mode, end) -> ray(end));
        }
        private BlockHitResult ray(Vec3 end) {
            Vec3 closest = null;
            AABB hitShape = null;
            for (var shape : shapes) {
                var hit = shape.clip(eye, end);
                if (hit.isPresent() && (closest == null || eye.distanceToSqr(hit.get()) < eye.distanceToSqr(closest))) {
                    closest = hit.get(); hitShape = shape;
                }
            }
            if (closest == null) return BlockHitResult.miss(end, Direction.DOWN, BlockPos.containing(end));
            var inside = closest.add(end.subtract(eye).normalize().scale(1e-7));
            var face = Math.abs(closest.y - hitShape.maxY) < 1e-6 ? Direction.UP : Direction.NORTH;
            return new BlockHitResult(closest, face, BlockPos.containing(inside), false);
        }
        private char cell(int x, int y, int z) {
            return x >= 290 && x <= 350 && z >= 290 && z <= 330 && y >= 100 && y <= 104
                    ? layers[y - 100][z - 290].charAt(x - 290) : '.';
        }
    }
}
