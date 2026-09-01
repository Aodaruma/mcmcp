package dev.aod.mcmcp.construction;

import java.util.LinkedHashSet;
import java.util.Set;

/** Canonical registry-id allowlist shared by construction observation and execution. */
public final class SafeConstructionBlocks {
    /** One-way yaw+pitch displacement; the adapter restores the same admitted pose. */
    public static final double MAX_ONE_WAY_CAMERA_DEGREES = 40.0D;
    private static final Set<String> NON_COPY_VISIBLE_STATE_IDS = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:obsidian",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker");
    private static final Set<String> IDS = ids();

    private SafeConstructionBlocks() {
    }

    public static boolean allows(String blockId) {
        return blockId != null && IDS.contains(blockId);
    }

    public static boolean isSurfaceAttachment(String blockId) {
        return "minecraft:ladder".equals(blockId)
                || "minecraft:wall_torch".equals(blockId);
    }

    /**
     * Complete BlockState properties may cross the policy boundary only for audited construction,
     * support, or owned-menu target contracts. Other visible blocks retain only visual identity.
     */
    public static boolean allowsVisibleState(String blockId) {
        return allows(blockId) || NON_COPY_VISIBLE_STATE_IDS.contains(blockId);
    }

    private static Set<String> ids() {
        var ids = new LinkedHashSet<String>();
        ids.addAll(Set.of(
                "minecraft:stone",
                "minecraft:cobblestone",
                "minecraft:smooth_stone",
                "minecraft:bricks",
                "minecraft:stone_bricks",
                "minecraft:deepslate",
                "minecraft:cobbled_deepslate",
                "minecraft:polished_deepslate",
                "minecraft:glass"));
        for (String wood : Set.of(
                "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "pale_oak")) {
            ids.add("minecraft:" + wood + "_planks");
            ids.add("minecraft:" + wood + "_log");
            ids.add("minecraft:stripped_" + wood + "_log");
            ids.add("minecraft:" + wood + "_wood");
            ids.add("minecraft:stripped_" + wood + "_wood");
        }
        ids.addAll(Set.of(
                "minecraft:bamboo_planks",
                "minecraft:ladder",
                "minecraft:wall_torch",
                "minecraft:crimson_planks",
                "minecraft:warped_planks",
                "minecraft:crimson_stem",
                "minecraft:stripped_crimson_stem",
                "minecraft:crimson_hyphae",
                "minecraft:stripped_crimson_hyphae",
                "minecraft:warped_stem",
                "minecraft:stripped_warped_stem",
                "minecraft:warped_hyphae",
                "minecraft:stripped_warped_hyphae"));
        return Set.copyOf(ids);
    }
}
