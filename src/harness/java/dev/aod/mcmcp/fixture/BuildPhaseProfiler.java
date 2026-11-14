package dev.aod.mcmcp.fixture;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.lang.reflect.Field;
import java.util.*;

/** Read-only, bounded phase sampling in the opt-in private benchmark world. */
public final class BuildPhaseProfiler {
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final List<Map<String, Object>> SAMPLES = new ArrayList<>();
    private static String caseName;
    private static long started, tickStarted;
    private static String phase;
    private static Object target;
    private static boolean truncated;
    private BuildPhaseProfiler() { }

    static void start(String name) {
        caseName = name; SAMPLES.clear(); started = System.nanoTime(); truncated = false;
        FloorLeaseFaultInjector.arm();
    }

    public static void before(Object runtime) {
        tickStarted = 0;
        var mc = Minecraft.getInstance();
        if (caseName == null || !Boolean.getBoolean("mcmcp.testHarness") || mc.player == null
                || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().isPublished()) return;
        if (SAMPLES.size() >= 6000) { truncated = true; return; }
        try {
            phase = classify(runtime, mc);
            tickStarted = System.nanoTime();
        } catch (ReflectiveOperationException failure) {
            caseName = null;
            throw new IllegalStateException("Benchmark phase fields changed", failure);
        }
    }

    public static void after() {
        if (tickStarted == 0 || phase.equals("idle")) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        long now = System.nanoTime();
        SAMPLES.add(Map.of("phase", phase, "target", String.valueOf(target),
                "time_ns", tickStarted - started, "tick_work_ns", now - tickStarted,
                "position", List.of(player.getX(), player.getY(), player.getZ()),
                "velocity", List.of(player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z),
                "health", player.getHealth(), "on_ground", player.onGround()));
    }

    static Map<String, Object> finish() {
        if (caseName == null) return Map.of();
        var counts = new TreeMap<String, Integer>();
        SAMPLES.forEach(row -> counts.merge((String) row.get("phase"), 1, Integer::sum));
        var result = Map.<String, Object>of("case", caseName, "ticks", SAMPLES.size(),
                "phase_ticks", counts, "truncated", truncated, "samples", List.copyOf(SAMPLES),
                "lease_fault", FloorLeaseFaultInjector.report());
        caseName = null;
        return result;
    }

    private static String classify(Object runtime, Minecraft mc) throws ReflectiveOperationException {
        target = "";
        var job = field(runtime, "v2PlaceExecution");
        if (job != null) {
            var driver = field(job, "driver");
            target = field(driver, "target");
            var floor = field(driver, "floorExtension");
            if (floor == null) return "place." + field(driver, "stage");
            String floorPhase = field(floor, "phase").toString();
            if (floorPhase.equals("LEAN") || floorPhase.equals("ADVANCE")) {
                var pos = (BlockPos) field(floor, "support");
                var dir = (Direction) field(floor, "direction");
                double progress = (mc.player.getX() - pos.getX() - 0.5) * dir.getStepX()
                        + (mc.player.getZ() - pos.getZ() - 0.5) * dir.getStepZ();
                return "floor." + floorPhase + (progress < (floorPhase.equals("LEAN") ? 0.55 : 0.90) ? ".move" : ".settle");
            }
            var construction = field(floor, "construction");
            if (construction != null) {
                String stage = field(construction, "phase").toString();
                if (stage.equals("PREPARING")) {
                    var port = field(floor, "placementPort");
                    for (var prep : ((Map<?, ?>) field(port, "preparations")).values()) {
                        if (field(field(prep, "plan"), "pendingStaging") != null) return "floor.material_staging";
                    }
                }
                return "floor.construction." + stage;
            }
            return "floor." + floorPhase;
        }
        if (field(runtime, "v2MoveExecution") != null) return "move";
        if (field(runtime, "v2InventoryExecution") != null) return "inventory";
        return field(runtime, "scriptExecution") == null ? "idle" : "script_switch";
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        String key = object.getClass().getName() + "." + name;
        Field field = FIELDS.get(key);
        if (field == null) {
            field = object.getClass().getDeclaredField(name); field.setAccessible(true); FIELDS.put(key, field);
        }
        return field.get(object);
    }
}
