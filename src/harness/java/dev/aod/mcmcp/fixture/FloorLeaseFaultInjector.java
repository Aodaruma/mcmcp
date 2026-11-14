package dev.aod.mcmcp.fixture;

import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

/** One bounded stall per explicitly armed private fixture; absent from the product JAR. */
public final class FloorLeaseFaultInjector {
    private static final Set<String> MODES = Set.of("entry_before_placement", "entry_after_confirmation",
            "heartbeat_after_confirmation");
    private static String mode = "disabled";
    private static boolean fired;
    private static long elapsedMillis;
    private FloorLeaseFaultInjector() { }

    static void arm() {
        mode = "disabled"; fired = false; elapsedMillis = 0;
        if (!privateFixture()) return;
        var path = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config/mcmcp-v2-priorities/lease-fault.txt");
        try {
            if (!Files.isRegularFile(path)) return;
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(65);
                if (bytes.length > 64) throw new IllegalArgumentException("Lease fault mode too long");
                String requested = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (requested.isEmpty()) return;
                if (!MODES.contains(requested)) throw new IllegalArgumentException("Unknown lease fault mode");
                mode = requested;
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read fixture lease fault mode", failure);
        }
    }

    public static void beforeCheck(Object attempt, boolean entry) {
        if (fired || mode.equals("disabled") || !privateFixture()) return;
        try {
            var movement = attempt.getClass().getDeclaredField("movement"); movement.setAccessible(true);
            var confirmed = attempt.getClass().getDeclaredField("confirmed"); confirmed.setAccessible(true);
            if (movement.get(attempt) == null || mode.startsWith("entry_") != entry
                    || confirmed.getBoolean(attempt) != !mode.equals("entry_before_placement")) return;
            fired = true;
            long start = System.nanoTime();
            Thread.sleep(3000);
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Lease fixture fields changed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lease fixture interrupted", interrupted);
        }
    }

    static Map<String, Object> report() {
        return Map.of("mode", mode, "fired", fired, "elapsed_ms", elapsedMillis);
    }

    private static boolean privateFixture() {
        var mc = Minecraft.getInstance();
        return Boolean.getBoolean("mcmcp.testHarness") && mc.player != null
                && mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isPublished();
    }
}
