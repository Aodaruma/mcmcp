package dev.aod.mcmcp.fixture;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.*;

/** Read-only tick alignment of the real flight decision and movement, in the private fixture only. */
public final class FlightRecoveryProfiler {
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final List<Map<String, Object>> SAMPLES = new ArrayList<>();
    private static String caseName;
    private static Object flight, observation;
    private static Map<String, Object> before;
    private static Map<String, Object> supportAttempt, firstSupportFailure;
    private static int supportAttempts;
    private static long started;
    private FlightRecoveryProfiler() { }

    static void start(String name) { caseName = name; SAMPLES.clear(); before = null; }
    public static void observe(Object value) { if (before != null) observation = value; }
    public static void before(Object runtime) {
        before = null; flight = null; observation = null;
        supportAttempt = null; firstSupportFailure = null; supportAttempts = 0;
        var mc = Minecraft.getInstance();
        if (caseName == null || !Boolean.getBoolean("mcmcp.testHarness") || mc.player == null
                || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().isPublished() || SAMPLES.size() >= 1000) return;
        try {
            var recovery = field(runtime, "v2Emergency");
            if (recovery == null || (flight = field(recovery, "flight")) == null) return;
            before = player(mc); started = System.nanoTime();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Flight probe fields changed", failure); }
    }
    public static void after() {
        if (before == null) return;
        long elapsed = System.nanoTime() - started;
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("before", before); row.put("after", player(Minecraft.getInstance()));
            row.put("tick_work_ns", elapsed); row.put("flight_tick", field(flight, "lastTick"));
            var target = (Vec3) field(flight, "landingTarget");
            row.put("landing_target", target == null ? List.of() : vector(target));
            var plan = field(flight, "lastPlan");
            var selected = new LinkedHashMap<String, Object>();
            if (plan != null) for (var key : List.of("yaw", "pitch", "boost", "verifiedTicks", "horizon", "score", "landing"))
                selected.put(key, field(plan, key));
            row.put("plan", selected);
            var resultMethod = flight.getClass().getDeclaredMethod("result"); resultMethod.setAccessible(true);
            var result = (Map<?, ?>) resultMethod.invoke(flight);
            if (result.get("landing_approach") != null) row.put("landing_approach", result.get("landing_approach"));
            if (observation != null) {
                var method = observation.getClass().getDeclaredMethod("diagnostic"); method.setAccessible(true);
                row.put("observation", method.invoke(observation));
                var cells = (Map<?, ?>) field(observation, "cells"); row.put("sampled_cells", cells.size());
                row.put("landing_visibility", Map.of("eye", vector((Vec3) field(observation, "eye")),
                        "radius", field(observation, "radius"), "fog", field(observation, "landingFog"),
                        "support_probe_truncated", supportAttempts > 16,
                        "first_support_failure", firstSupportFailure == null ? Map.of() : firstSupportFailure));
            }
            SAMPLES.add(Map.copyOf(row));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Flight probe fields changed", failure); }
        finally { before = null; flight = null; observation = null; }
    }
    public static void supportStart(Vec3 eye, BlockPos support, double ground, double radius, double fog) {
        supportAttempt = null;
        if (before == null || firstSupportFailure != null || ++supportAttempts > 16) return;
        var target = new Vec3(support.getX() + .5, ground, support.getZ() + .5);
        supportAttempt = new LinkedHashMap<>(Map.of("eye", vector(eye), "support",
                List.of(support.getX(), support.getY(), support.getZ()), "ground", ground,
                "radius", radius, "fog", fog, "distance", eye.distanceTo(target),
                "rays", new ArrayList<Map<String, Object>>()));
    }
    @SuppressWarnings("unchecked")
    public static void supportRay(ClipContext.Block mode, BlockHitResult hit) {
        if (supportAttempt == null) return;
        var rays = (List<Map<String, Object>>) supportAttempt.get("rays");
        if (rays.size() >= 2) return;
        var pos = hit.getBlockPos();
        rays.add(Map.of("mode", mode.name(), "type", hit.getType().name(), "block",
                List.of(pos.getX(), pos.getY(), pos.getZ()), "location", vector(hit.getLocation()),
                "face", hit.getDirection().name(), "inside", hit.isInside(), "world_border", hit.isWorldBorderHit()));
    }
    public static void supportEnd(boolean visible) {
        if (supportAttempt != null && !visible) firstSupportFailure = Map.copyOf(supportAttempt);
        supportAttempt = null;
    }
    static Map<String, Object> finish() {
        if (caseName == null) return Map.of();
        var result = Map.<String, Object>of("case", caseName, "samples", List.copyOf(SAMPLES), "truncated", SAMPLES.size() >= 1000);
        caseName = null; return result;
    }
    private static Map<String, Object> player(Minecraft mc) {
        var p = mc.player;
        return Map.of("tick", p.tickCount, "position", vector(p.position()), "velocity", vector(p.getDeltaMovement()),
                "yaw", p.getYRot(), "pitch", p.getXRot(), "gravity", p.getGravity(), "on_ground", p.onGround(),
                "flying", p.isFallFlying(), "water", p.isInWater(), "health", p.getHealth());
    }
    private static List<Double> vector(Vec3 value) { return List.of(value.x, value.y, value.z); }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        String key = object.getClass().getName() + "." + name;
        var field = FIELDS.get(key);
        if (field == null) { field = object.getClass().getDeclaredField(name); field.setAccessible(true); FIELDS.put(key, field); }
        return field.get(object);
    }
}
