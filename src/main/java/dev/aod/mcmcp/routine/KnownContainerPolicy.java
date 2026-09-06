package dev.aod.mcmcp.routine;

import java.util.Set;

/** Exact Minecraft 26.2 block-ID policy for Action-owned Vanilla storage containers. */
public final class KnownContainerPolicy {
    public static final String CHEST = "minecraft:chest";
    public static final String BARREL = "minecraft:barrel";

    private static final Set<String> COPPER_CHEST_BLOCK_IDS = Set.of(
            "minecraft:copper_chest",
            "minecraft:exposed_copper_chest",
            "minecraft:weathered_copper_chest",
            "minecraft:oxidized_copper_chest",
            "minecraft:waxed_copper_chest",
            "minecraft:waxed_exposed_copper_chest",
            "minecraft:waxed_weathered_copper_chest",
            "minecraft:waxed_oxidized_copper_chest");
    private static final Set<String> BLOCK_IDS = Set.of(
            CHEST,
            BARREL,
            "minecraft:copper_chest",
            "minecraft:exposed_copper_chest",
            "minecraft:weathered_copper_chest",
            "minecraft:oxidized_copper_chest",
            "minecraft:waxed_copper_chest",
            "minecraft:waxed_exposed_copper_chest",
            "minecraft:waxed_weathered_copper_chest",
            "minecraft:waxed_oxidized_copper_chest");

    private KnownContainerPolicy() {
    }

    public static boolean allows(String blockId) {
        return BLOCK_IDS.contains(blockId);
    }

    public static boolean isChest(String blockId) {
        return CHEST.equals(blockId) || COPPER_CHEST_BLOCK_IDS.contains(blockId);
    }

    public static boolean isBarrel(String blockId) {
        return BARREL.equals(blockId);
    }

    public static Set<String> blockIds() {
        return BLOCK_IDS;
    }

    public static Set<String> copperChestBlockIds() {
        return COPPER_CHEST_BLOCK_IDS;
    }
}
