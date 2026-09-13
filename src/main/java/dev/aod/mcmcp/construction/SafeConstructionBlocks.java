package dev.aod.mcmcp.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Shared Vanilla construction families; custom implementations are never inferred safe. */
public final class SafeConstructionBlocks {
    /** One-way yaw+pitch displacement; the adapter restores the same admitted pose. */
    public static final double MAX_ONE_WAY_CAMERA_DEGREES = 40.0D;
    private static final Set<String> NON_COPY_VISIBLE_STATE_IDS = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:obsidian",
            "minecraft:white_wool",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker");
    private static final Set<String> IDS = ids();

    private SafeConstructionBlocks() {
    }

    public static boolean allows(String blockId) {
        if (blockId == null) return false;
        if (IDS.contains(blockId)) return true;
        Block block = vanillaBlock(blockId);
        if (block == null) return false;
        Class<?> type = block.getClass();
        return type == Block.class || type == RotatedPillarBlock.class
                || type == DropExperienceBlock.class || type == TransparentBlock.class
                || type == StainedGlassBlock.class
                || isStair(blockId) || isSlab(blockId) || isPane(blockId);
    }

    private static Block vanillaBlock(String blockId) {
        if (blockId == null) return null;
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !"minecraft".equals(id.getNamespace())
                || !blockId.equals(id.toString())) return null;
        return BuiltInRegistries.BLOCK.get(id).map(holder -> holder.value()).orElse(null);
    }

    public static boolean isStair(String blockId) {
        Block block = vanillaBlock(blockId);
        return block != null && block.getClass() == StairBlock.class;
    }

    public static boolean isSlab(String blockId) {
        Block block = vanillaBlock(blockId);
        return block != null && block.getClass() == SlabBlock.class;
    }

    public static boolean isPane(String blockId) {
        Block block = vanillaBlock(blockId);
        return block != null && (block.getClass() == IronBarsBlock.class
                || block.getClass() == StainedGlassPaneBlock.class);
    }

    public static boolean isSurfaceAttachment(String blockId) {
        return "minecraft:ladder".equals(blockId)
                || "minecraft:torch".equals(blockId)
                || "minecraft:wall_torch".equals(blockId);
    }

    /** Number of cells mutated by one admitted placement source. */
    public static int placementCellCount(String blockId) {
        Objects.requireNonNull(blockId, "blockId");
        return "minecraft:oak_door".equals(blockId) ? 2 : 1;
    }

    /**
     * State-sensitive shape boundary for the construction allowlist.
     *
     * <p>Most admitted blocks must remain full collision cubes. The explicit partial-shape slice
     * is limited to ordinary dry Vanilla stairs, non-double slabs and panes, the audited oak
     * door, and ladder/torch attachments. Behavioural subclasses stay outside these families.</p>
     */
    public static boolean allowsConstructionState(BlockState state) {
        Objects.requireNonNull(state, "state");
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!allows(blockId)
                || !state.getFluidState().isEmpty()
                || state.hasProperty(BlockStateProperties.WATERLOGGED)
                        && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return "minecraft:oak_door".equals(blockId)
                    && !state.getValue(BlockStateProperties.OPEN)
                    && !state.getValue(BlockStateProperties.POWERED);
        }
        if (isStair(blockId)) return state.hasProperty(BlockStateProperties.STAIRS_SHAPE);
        if (isSlab(blockId)) return state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
        if (isPane(blockId)) return true;
        return Block.isShapeFullBlock(state.getCollisionShape(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) || isSurfaceAttachment(blockId);
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
                "minecraft:glass",
                "minecraft:glass_pane",
                "minecraft:oak_stairs",
                "minecraft:cobblestone_stairs",
                "minecraft:oak_slab",
                "minecraft:oak_door"));
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
                "minecraft:torch",
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
