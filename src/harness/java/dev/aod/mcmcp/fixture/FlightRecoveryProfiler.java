package dev.aod.mcmcp.fixture;

import net.minecraft.client.Minecraft;
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
    private static long started;
    private FlightRecoveryProfiler() { }

    static void start(String name) { caseName = name; SAMPLES.clear(); before = null; }
    public static void observe(Object value) { if (before != null) observation = value; }
    public static void before(Object runtime) {
        before = null; flight = null; observation = null;
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
            }
            SAMPLES.add(Map.copyOf(row));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Flight probe fields changed", failure); }
        finally { before = null; flight = null; observation = null; }
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
