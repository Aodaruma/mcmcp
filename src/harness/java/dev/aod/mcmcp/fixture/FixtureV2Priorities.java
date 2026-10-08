package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in disposable-world preparation. Fixed cases only, never a production tool or command channel. */
final class FixtureV2Priorities {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private String previous = "";
    private int ticks;
    private volatile boolean pending;

    static void install() {
        if (Boolean.getBoolean("mcmcp.fixture.v2Priorities")) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(new FixtureV2Priorities()::tick);
            LOGGER.warn("MCMCP v2 priorities fixture enabled; fixed setup cases only");
        }
    }

    private void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (ticks == 0) LOGGER.info("MCMCP v2 priorities fixture ticking; directory={}", minecraft.gameDirectory);
        if (++ticks % 20 != 0 || pending || minecraft.player == null || minecraft.getSingleplayerServer() == null
                || !inputIdle()) return;
        Path root = minecraft.gameDirectory.toPath().resolve("config/mcmcp-v2-priorities");
        Path request = root.resolve("request.txt");
        try {
            if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 80) return;
            String value = Files.readString(request).strip();
            if (value.equals(previous) || !FixtureV2PrioritiesConfig.validRequest(value)) return;
            previous = value;
            pending = true;
            LOGGER.info("MCMCP v2 priorities fixture request: {}", value);
            var server = minecraft.getSingleplayerServer();
            UUID playerId = minecraft.player.getUUID();
            server.execute(() -> {
                var report = new LinkedHashMap<String, Object>();
                report.put("request", value);
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) throw new IllegalStateException("owner missing");
                    var decision = FixtureSecurity.authorize(player.createCommandSourceStack());
                    if (!decision.allowed()) throw new IllegalStateException(decision.rejection());
                    String kind = value.substring(value.indexOf(':') + 1);
                    if (!kind.equals("report")) {
                        if (!inputIdle()) throw new IllegalStateException("input owned");
                        var commands = commands(kind);
                        var source = player.createCommandSourceStack().withMaximumPermission(server.operatorUserPermissions()).withSuppressedOutput();
                        for (String command : commands) Commands.validateParseResults(server.getCommands().getDispatcher().parse(command, source));
                        for (String command : commands) {
                            if (!FixtureSecurity.reauthorize(decision.context()).allowed()) throw new IllegalStateException("fixture ownership changed");
                            server.getCommands().performPrefixedCommand(source, command);
                        }
                        if (kind.equals("water_air")) player.setAirSupply(120);
                    }
                    var inventory = new ArrayList<Object>();
                    for (int slot = 0; slot <= 40; slot++) {
                        var stack = player.getInventory().getItem(slot);
                        if (!stack.isEmpty()) inventory.add(Map.of("slot", slot, "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                "count", stack.getCount(), "components", stack.getComponents().toString()));
                    }
                    report.put("inventory", inventory);
                    report.put("cursor_empty", player.containerMenu.getCarried().isEmpty());
                    report.put("position", List.of(player.getX(), player.getY(), player.getZ()));
                    report.put("air", player.getAirSupply());
                    report.put("status", "prepared");
                } catch (Exception failure) {
                    report.put("status", "failed"); report.put("failure", failure.toString());
                    LOGGER.error("MCMCP v2 priorities fixture failed", failure);
                } finally {
                    try { Files.writeString(root.resolve("receipt.json"), new com.google.gson.Gson().toJson(report)); }
                    catch (java.io.IOException failure) { LOGGER.error("MCMCP v2 priorities fixture receipt failed", failure); }
                    pending = false;
                }
            });
        } catch (java.io.IOException failure) { LOGGER.error("MCMCP v2 priorities fixture request read failed", failure); }
    }

    private static boolean inputIdle() {
        var input = AgentInputState.global().inputOwnershipSnapshot();
        // Vanilla player ticks retain a velocity-ledger identity even when no agent owns input.
        return !input.movementOwned() && !input.attackOwned() && !input.useOwned()
                && !input.pickOwned() && !input.goalProofRetained();
    }

    private static List<String> commands(String kind) {
        var result = new ArrayList<>(List.of("gamemode creative @s", "ride @s dismount",
                "kill @e[tag=mcmcp_v2_fixture,x=300,y=100,z=300,dx=20,dy=8,dz=20]", "fill 300 100 300 320 100 320 minecraft:stone",
                "fill 300 101 300 320 108 320 minecraft:air", "clear @s", "effect clear @s",
                "tp @s 305.5 101 305.5 180 0", "setblock 305 101 303 minecraft:crafting_table",
                "recipe give @s *", "time set noon", "weather clear"));
        switch (kind) {
            case "components" -> result.addAll(List.of(
                    "item replace entity @s hotbar.0 with minecraft:oak_log[minecraft:custom_name='Named material'] 16",
                    "item replace entity @s hotbar.1 with minecraft:fishing_rod[minecraft:damage=10,minecraft:custom_name='Used rod']",
                    "item replace entity @s hotbar.2 with minecraft:carrot",
                    "item replace entity @s hotbar.3 with minecraft:cobblestone[minecraft:custom_name='Named stone'] 16"));
            case "inventory" -> {
                for(int slot=0;slot<36;slot++) result.add("item replace entity @s " + (slot < 9 ? "hotbar."+slot : "inventory."+(slot-9))
                        + " with minecraft:stone[minecraft:custom_name='variant-" + slot + "'] 1");
                result.addAll(List.of("item replace entity @s hotbar.0 with minecraft:iron_helmet",
                        "item replace entity @s hotbar.1 with minecraft:shield",
                        "item replace entity @s inventory.0 with minecraft:oak_planks[minecraft:custom_name='same'] 8",
                        "item replace entity @s inventory.1 with minecraft:oak_planks[minecraft:custom_name='same'] 16"));
            }
            case "cake" -> result.addAll(List.of("give @s minecraft:milk_bucket 6", "give @s minecraft:wheat 6",
                    "give @s minecraft:sugar 4", "give @s minecraft:egg 2"));
            case "cancel" -> result.add("give @s minecraft:oak_log[minecraft:custom_name='Cancel material'] 64");
            case "blueprint" -> result.add("give @s minecraft:cobblestone 32");
            case "water", "water_air" -> result.addAll(List.of("fill 305 100 305 315 104 315 minecraft:stone",
                    "fill 306 101 306 314 104 314 minecraft:water", "tp @s 310.5 102 310.5 0 0"));
            case "boat" -> result.addAll(List.of("fill 300 101 300 320 101 320 minecraft:stone",
                    "fill 305 101 305 317 101 317 minecraft:water", "tp @s 303.5 102 306.5 -90 25",
                    "summon minecraft:oak_boat 306.5 101.45 306.5 {Tags:[\"mcmcp_v2_fixture\"],Rotation:[-90f,0f]}"));
            case "minecart" -> result.addAll(List.of("fill 304 101 306 316 101 306 minecraft:rail[shape=east_west]",
                    "fill 314 101 306 316 101 306 minecraft:powered_rail[shape=east_west,powered=false]",
                    "tp @s 304.5 101 304.5 0 15", "summon minecraft:minecart 304.5 101.1 306.5 {Tags:[\"mcmcp_v2_fixture\"]}"));
            case "horse" -> result.addAll(List.of("tp @s 303.5 101 306.5 -90 0",
                    "summon minecraft:horse 306.5 101 306.5 {Tags:[\"mcmcp_v2_fixture\"],Tame:1b,NoAI:1b,equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}"));
            case "mod_break" -> {
                var block = BuiltInRegistries.BLOCK.keySet().stream().filter(id -> id.getNamespace().equals("shogi"))
                        .sorted(Comparator.comparing(Object::toString)).findFirst().orElseThrow();
                result.add("setblock 305 101 303 " + block);
                result.add("item replace entity @s hotbar.0 with minecraft:netherite_pickaxe");
                result.add("item replace entity @s hotbar.1 with minecraft:netherite_axe");
            }
            default -> throw new IllegalArgumentException("unknown fixture");
        }
        result.addAll(List.of("gamemode survival @s", "effect give @s minecraft:saturation 120 0 true"));
        if (kind.equals("water")) result.add("effect give @s minecraft:water_breathing 120 0 true");
        return List.copyOf(result);
    }
}
