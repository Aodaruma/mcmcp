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

    @Test void nativePoolCourseKeepsItsCompleteStoppingProofWhileTheIntentPointIsHidden() throws Exception {
        reachesBank(new Vec3(313.80585704457116, 116.93588034947514, 310.9449298222381),
                new Vec3(.26631392605976606, -.1702227658014497, .02266135378820751),
                new Vec3(350.01772676121544, 101, 293.9572138626892),
                -97.99646f, -25, 122.64, 294.99646, 10);
    }

    @Test void nativeLatePoolCourseReplansBeforeAnOccludedIntentCanDiscardIt() throws Exception {
        int replansWithHiddenIntent = reachesBank(
                new Vec3(314.65703959597187, 116.45594764518285, 310.91879269643084),
                new Vec3(.292248601426861, -.1570524242445533, -.02665926721280446),
                new Vec3(350.01772676121544, 101, 293.9572138626892),
                -115.477295f, -1, 120, 252, 1);
        assertThat(replansWithHiddenIntent).isPositive();
    }

    @Test void nativeColdPoolCanAdmitAnIntermediateBearingAndReproveItUntilStopped() throws Exception {
        reachesBank(new Vec3(313.6599713426558, 116.62836115826624, 310.0783677973563),
                new Vec3(.28443661293351635, -.18175430358695083, -.012404228753377367),
                new Vec3(349.1407972436873, 101, 328.5475560107047),
                -74.51059f, -25, 122, 269, 0);
        reachesBank(new Vec3(314.2513740472922, 116.28766251190576, 310.1097553350223),
                new Vec3(.2993803127995384, -.1669337725760785, .025665165850017015),
                new Vec3(349.1407972436873, 101, 328.5475560107047),
                -62.31413f, -9, 121, 241, 0);
    }

    @Test void aTimedOutSearchCanCoastOnCurrentEvidenceUntilAFullCourseIsAdmitted() throws Exception {
        var position = new Vec3(310.60044722708494, 119.88169569133191, 310.48503149011594);
        var motion = new Vec3(.0467804657904617, -.3498281062551076, -.014968509884056683);
        var goal = new Vec3(347.14081006201536, 101, 294.2157994554327);
        double distance = 0;
        for (int tick = 0; tick < 3; tick++) {
            var witness = new Witness(position);
            var expired = new ElytraLandingCourse.SearchBudget(() -> true, System.nanoTime() - 1);
            assertThat(ElytraLandingCourse.select(position, motion, -114, 0, goal, .08, 4,
                    595 - tick, 126 - distance, 320, witness::clear, witness::stoppedLanding, expired)).isNull();
            assertThat(expired.expired()).isTrue();
            var coast = ElytraFlightPlanner.coast(position, motion, -114, 0, .08, 4,
                    595 - tick, 126 - distance, witness::clear, () -> true);
            assertThat(coast).isNotNull();
            assertThat(coast.landing()).isFalse();
            assertThat(coast.boost()).isFalse();
            assertThat(witness.supports).isEmpty();
            motion = ElytraFlightPlanner.velocity(motion, coast.yaw(), coast.pitch(), .08, false);
            position = position.add(motion);
            distance += motion.length();
        }
        reachesBank(position, motion, goal, -114, 0, 126 - distance, 320, 0);
    }

    @Test void aCoastCannotAuthorizeRecordedWaterAsAirOrUseAnExhaustedObservationCache() throws Exception {
        var position = new Vec3(330, 107, 310);
        var witness = new Witness(position);
        assertThat(witness.clear(body(position).inflate(.2))).isTrue();
        assertThat(ElytraFlightPlanner.coast(position, new Vec3(0, -.3, 0), -90, 0, .08, 4,
                600, 128, witness::clear, () -> true)).isNull();
        witness = new Witness(START);
        for (int index = 0; index < 2048; index++) witness.cells.put(new BlockPos(index, -1000, 0), true);
        assertThat(ElytraFlightPlanner.coast(START, MOTION, -90, 0, .08, 4,
                600, 128, witness::clear, () -> true)).isNull();
    }

    @Test void onlyACurrentCompleteProofCanRetainADescendingGoalAwayFromAThreat() throws Exception {
        var witness = new Witness(START);
        var selected = ElytraLandingCourse.select(START, MOTION, -90, 0, GOAL, .08, 4,
                600, 128, 344, witness::clear, witness::stoppedLanding, () -> true);
        assertThat(selected).isNotNull();
        assertThat(ElytraLandingCourse.retainsProvenGoal(START, GOAL, null, selected.verifiedTicks())).isTrue();
        witness = new Witness(START);
        int invalidated = selected.course().verifiedTicks(START, MOTION, -90, 0, .08, 0, 4,
                600, 128, 344, witness::clear, (body, motion) -> false, () -> true);
        assertThat(invalidated).isZero();
        assertThat(ElytraLandingCourse.retainsProvenGoal(START, GOAL, null, invalidated)).isFalse();
        assertThat(ElytraLandingCourse.retainsProvenGoal(START, START.add(0, 1, 0), null,
                selected.verifiedTicks())).isFalse();
        assertThat(ElytraLandingCourse.retainsProvenGoal(START, GOAL, GOAL.add(1, 0, 0),
                selected.verifiedTicks())).isFalse();
    }

    private static void reachesBank(Vec3 start, Vec3 initialMotion, Vec3 goal) throws Exception {
        reachesBank(start, initialMotion, goal, -90, 0, 128, 344, 0);
    }

    private static int reachesBank(Vec3 start, Vec3 initialMotion, Vec3 goal, float initialYaw, float initialPitch,
            double distanceBudget, double cameraBudget, int minimumHiddenIntentTicks) throws Exception {
        var witness = new Witness(start);
        var selected = ElytraLandingCourse.select(start, initialMotion, initialYaw, initialPitch, goal, .08, 4,
                600, distanceBudget, cameraBudget, witness::clear, witness::stoppedLanding, () -> true);
        assertThat(selected).isNotNull();
        var course = selected.course();
        assertThat(witness.cells.size() + witness.supports.size()).isLessThanOrEqualTo(2048);
        var position = start;
        var motion = initialMotion;
        float yaw = initialYaw, pitch = initialPitch;
        double camera = 0, distance = 0;
        int started = 0;
        int hiddenIntentProofTicks = 0;
        int replansWithHiddenIntent = 0;
        for (int tick = 0; tick < 160; tick++) {
            if (tick > 0) witness = new Witness(position);
            if (tick > 0) {
                selected = course.proveOrReplan(position, motion, yaw, pitch, .08, tick - started, 4,
                        600 - tick, distanceBudget - distance, cameraBudget - camera,
                        witness::clear, witness::stoppedLanding, () -> true);
                if (selected == null) selected = ElytraLandingCourse.select(position, motion, yaw, pitch, goal, .08, 4,
                            600 - tick, distanceBudget - distance, cameraBudget - camera,
                            witness::clear, witness::stoppedLanding, () -> true);
                assertThat(selected).as("fresh replacement at tick %s: %s, rejected=%s", tick, position, witness.rejection).isNotNull();
            }
            boolean replanned = selected.course() != course;
            if (replanned) started = tick;
            course = selected.course();
            int verified = selected.verifiedTicks();
            if (minimumHiddenIntentTicks > 0 && !new Witness(position).clear(new AABB(
                    goal.x - .4, 101.01, goal.z - .4, goal.x + .4, 102.8, goal.z + .4))) {
                assertThat(ElytraLandingCourse.retainsProvenGoal(position, goal, null, verified)).isTrue();
                hiddenIntentProofTicks++;
                if (replanned) replansWithHiddenIntent++;
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
                assertThat(camera).isLessThan(cameraBudget);
                assertThat(distance).isLessThan(distanceBudget);
                assertThat(hiddenIntentProofTicks).isGreaterThanOrEqualTo(minimumHiddenIntentTicks);
                return replansWithHiddenIntent;
            }
        }
        throw new AssertionError("never reached the witnessed bank");
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
        assertThat(course.proveOrReplan(START, MOTION, -90, 0, .08, 0, 4,
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
        assertThat(search.expired()).isTrue();
        assertThat(authorized).isTrue();
        assertThat(ElytraLandingCourse.boundedSearch(authorized::get).getAsBoolean()).isTrue();
        var denied = new ElytraLandingCourse.SearchBudget(() -> false,
                System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
        assertThat(denied.getAsBoolean()).isFalse();
        assertThat(denied.expired()).isFalse();
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
