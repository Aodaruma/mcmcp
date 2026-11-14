package dev.aod.mcmcp.runtime;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
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

    @Test void recordedFloodedBankCanBeReachedAndStoppedWithoutWeakeningTheObservation() throws Exception {
        var witness = new Witness(START);
        var course = ElytraLandingCourse.select(START, MOTION, -90, 0, GOAL, .08, 4,
                600, 128, 344, witness::clear, witness::stoppedLanding, () -> true);
        assertThat(course).isNotNull();
        assertThat(witness.cells.size()).isLessThanOrEqualTo(2048);
        var position = START;
        var motion = MOTION;
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
                if (course == null) course = ElytraLandingCourse.select(position, motion, yaw, pitch, GOAL, .08, 4,
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
            if (position.y <= GOAL.y) {
                assertThat(witness.stoppedLanding(body(position), motion)).isTrue();
                assertThat(position.distanceTo(GOAL)).isLessThan(8);
                assertThat(camera).isLessThan(344);
                assertThat(distance).isLessThan(128);
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("never reached the witnessed bank");
    }

    @Test void allSteeringDelaysAndTheirCompleteStoppingFootprintsMustRemainWitnessed() throws Exception {
        var course = new ElytraLandingCourse(GOAL, -120, 8, 48, 20, -70);
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
    }

    private static AABB body(Vec3 p) { return new AABB(p.x - .3, p.y, p.z - .3, p.x + .3, p.y + .6, p.z + .3); }

    /** The recorded private fixture's actual flowing water, not a rectangular source-water approximation. */
    private static final class Witness {
        final Vec3 eye;
        final Map<BlockPos, Boolean> cells = new HashMap<>();
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
                    if (cells.size() >= 2048) return false;
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
                for (int a = Mth.floor(standing.minX); a <= Mth.floor(standing.maxX); a++)
                    for (int b = Mth.floor(standing.minZ); b <= Mth.floor(standing.maxZ); b++)
                        if (cell(a, 100, b) != '#' || cell(a, 101, b) != '.') return false;
                if (!clear(standing)) return false;
                x += vx; z += vz;
                if (tick >= 2) { vx *= .546; vz *= .546; }
                if (Math.hypot(vx, vz) < .005) return true;
            }
            return false;
        }
        private char cell(int x, int y, int z) {
            return x >= 290 && x <= 350 && z >= 290 && z <= 330 && y >= 100 && y <= 104
                    ? layers[y - 100][z - 290].charAt(x - 290) : '.';
        }
    }
}
