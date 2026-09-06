package dev.aod.mcmcp.agent.mining;

import java.util.Map;
import java.util.Set;

/** Closed natural-terrain subset; falling blocks, fluids and interactive blocks are excluded. */
public final class SafeMiningBlocks {
    private static final Set<String> IDS = Set.of(
            "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
            "minecraft:cobbled_deepslate", "minecraft:granite", "minecraft:diorite",
            "minecraft:andesite", "minecraft:tuff", "minecraft:calcite",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore");

    private SafeMiningBlocks() { }

    public static boolean allows(String blockId) { return blockId != null && IDS.contains(blockId); }

    public static boolean allowsState(String blockId, Map<String, String> properties) {
        if (!allows(blockId) || properties == null) return false;
        return "minecraft:deepslate".equals(blockId)
                ? properties.size() == 1 && properties.containsKey("axis")
                        && Set.of("x", "y", "z").contains(properties.get("axis"))
                : properties.isEmpty();
    }

    public static boolean allowsTool(String itemId) {
        return itemId != null && Set.of("minecraft:iron_pickaxe", "minecraft:diamond_pickaxe",
                "minecraft:netherite_pickaxe").contains(itemId);
    }
}
