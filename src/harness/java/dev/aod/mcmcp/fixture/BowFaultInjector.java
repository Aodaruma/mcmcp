package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One exception in an explicitly armed private bow fixture; never included in the product JAR. */
public final class BowFaultInjector {
    private static final Set<String> MODES = Set.of("cancel_first_runtime", "cancel_second_runtime", "fire_runtime",
            "cancel_first_linkage", "cancel_second_linkage", "fire_linkage");
    private static volatile Fault fault = new Fault("disabled");
    private static volatile FixtureSecurity.Context context;
    private static Object clientPlayer, clientLevel;
    private static long deadline;
    private static int arrowsAtFault = -1, bowDamageAtFault = -1;

    private BowFaultInjector() { }

    static void arm(FixtureSecurity.Context authorized, String kind) {
        fault = new Fault("disabled"); context = null;
        clientPlayer = null; clientLevel = null; deadline = 0;
        arrowsAtFault = -1; bowDamageAtFault = -1;
        if (!kind.equals("recovery_equipment_bow") || !FixtureSecurity.reauthorize(authorized).allowed()) return;
        var mc = Minecraft.getInstance();
        if (!Boolean.getBoolean("mcmcp.fixture.v2Priorities") || mc.player == null || mc.level == null
                || !mc.player.getUUID().equals(authorized.player().getUUID())) return;
        try {
            String mode = readMode(mc.gameDirectory.toPath().resolve("config/mcmcp-v2-priorities/bow-fault.txt"));
            clientPlayer = mc.player; clientLevel = mc.level;
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
            context = authorized;
            fault = new Fault(mode);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read fixture bow fault mode", failure);
        }
    }

    static String readMode(Path path) throws java.io.IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "disabled";
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid bow fault file");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(65);
            if (bytes.length > 64) throw new IllegalArgumentException("Bow fault mode too long");
            String mode = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (mode.isEmpty()) return "disabled";
            if (!MODES.contains(mode)) throw new IllegalArgumentException("Unknown bow fault mode");
            return mode;
        }
    }

    public static void before(Object owner, String point) {
        var mc = Minecraft.getInstance();
        var authorized = context;
        boolean allowed = contextMatches(mc, authorized) && mc.player == owner && System.nanoTime() - deadline < 0
                && AgentInputState.global().inputOwnershipSnapshot().useOwned();
        var armed = fault;
        if (!armed.matches(point, allowed)) return;
        arrowsAtFault = arrowCount(); bowDamageAtFault = bowDamage();
        armed.fire(point, allowed);
    }

    static Map<String, Object> report() {
        var mc = Minecraft.getInstance();
        if (!contextMatches(mc, context)) return Map.of();
        var input = AgentInputState.global().inputOwnershipSnapshot();
        var report = new LinkedHashMap<String, Object>();
        report.put("mode", fault.mode); report.put("fired", fault.fired); report.put("point", fault.point);
        report.put("input_owner_none", input.ownerNone());
        report.put("input_channels_none", !input.movementOwned() && !input.attackOwned() && !input.useOwned()
                && !input.pickOwned() && !input.goalProofRetained());
        report.put("velocity_tracked", input.velocityTracked());
        report.put("local_using_item", mc.player != null && mc.player.isUsingItem());
        report.put("arrow_items_at_fault", arrowsAtFault); report.put("arrow_items_now", arrowCount());
        report.put("bow_damage_at_fault", bowDamageAtFault); report.put("bow_damage_now", bowDamage());
        return Map.copyOf(report);
    }

    private static boolean contextMatches(Minecraft mc, FixtureSecurity.Context authorized) {
        return Boolean.getBoolean("mcmcp.testHarness") && Boolean.getBoolean("mcmcp.fixture.v2Priorities")
                && mc.isSameThread() && mc.player != null && mc.player == clientPlayer && mc.level == clientLevel
                && authorized != null && mc.getSingleplayerServer() == authorized.server()
                && authorized.server().isSingleplayer() && !authorized.server().isDedicatedServer()
                && !authorized.server().isPublished() && authorized.server().getPlayerList().getPlayerCount() == 1
                && authorized.server().getPlayerList().getPlayer(authorized.player().getUUID()) == authorized.player()
                && authorized.server().isSingleplayerOwner(authorized.player().nameAndId())
                && mc.player.getUUID().equals(authorized.player().getUUID());
    }

    private static int arrowCount() {
        var player = Minecraft.getInstance().player;
        if (player == null) return -1;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)) count += stack.getCount();
        }
        return count;
    }

    private static int bowDamage() {
        var player = Minecraft.getInstance().player;
        if (player == null) return -1;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(Items.BOW)) return stack.getDamageValue();
        }
        return -1;
    }

    static final class Fault {
        private final String mode;
        private boolean fired;
        private String point = "none";
        Fault(String mode) {
            if (!mode.equals("disabled") && !MODES.contains(mode)) throw new IllegalArgumentException("Unknown bow fault mode");
            this.mode = mode;
        }
        boolean matches(String point, boolean allowed) {
            return allowed && !fired && (mode.equals(point + "_runtime") || mode.equals(point + "_linkage"));
        }
        void fire(String point, boolean allowed) {
            if (!matches(point, allowed)) return;
            fired = true; this.point = point;
            if (mode.endsWith("_linkage")) throw new LinkageError("MCMCP fixture bow fault");
            throw new IllegalStateException("MCMCP fixture bow fault");
        }
    }
}
