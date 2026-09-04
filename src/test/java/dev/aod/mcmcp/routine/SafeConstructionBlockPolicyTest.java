package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeConstructionBlockPolicyTest {
    @Test
    void acceptsClosedFamiliesAndOnlyTheAuditedSurfaceAttachments() {
        assertThat(List.of(
                Blocks.STONE,
                Blocks.COBBLESTONE,
                Blocks.OAK_PLANKS,
                Blocks.GLASS,
                Blocks.GLASS_PANE,
                Blocks.OAK_STAIRS,
                Blocks.COBBLESTONE_STAIRS,
                Blocks.OAK_SLAB,
                Blocks.LADDER,
                Blocks.WALL_TORCH,
                Blocks.OAK_LOG,
                Blocks.STRIPPED_OAK_LOG,
                Blocks.CRIMSON_STEM))
                .allSatisfy(block -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(
                                block.defaultBlockState(), false)).isTrue());

        assertThat(List.of(
                Blocks.CHEST,
                Blocks.SAND,
                Blocks.WATER,
                Blocks.REDSTONE_BLOCK,
                Blocks.PISTON,
                Blocks.TNT,
                Blocks.STONE_SLAB,
                Blocks.SCAFFOLDING))
                .allSatisfy(block -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(
                                block.defaultBlockState(), false)).isFalse());
        assertThat(SafeConstructionBlockPolicy.allowsLiveState(
                Blocks.STONE.defaultBlockState(), true)).isFalse();
        var lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        assertThat(SafeConstructionBlockPolicy.allowsLiveState(lowerDoor, false)).isFalse();
        assertThat(SafeConstructionBlockPolicy.allowsPlacementState(lowerDoor, false)).isTrue();
        assertThat(SafeConstructionBlockPolicy.allowsPlacementState(
                lowerDoor.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                false)).isFalse();
        assertThat(SafeConstructionBlockPolicy.allowsPlacementState(
                lowerDoor.setValue(BlockStateProperties.OPEN, true), false)).isFalse();
    }

    @Test
    void admitsOnlyTheAuditedDryPartialShapeStates() {
        var topStraightStairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
        var connectedPane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(BlockStateProperties.NORTH, true);

        assertThat(List.of(
                topStraightStairs,
                Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT),
                Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_RIGHT),
                Blocks.COBBLESTONE_STAIRS.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
                connectedPane))
                .allSatisfy(state -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(state, false)).isTrue());

        assertThat(List.of(
                Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
                Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.GLASS_PANE.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true)))
                .allSatisfy(state -> assertThat(
                        SafeConstructionBlockPolicy.allowsLiveState(state, false)).isFalse());
    }

    @Test
    void requiresCompleteStrictStateAndMatchingBlockItem() {
        var oakLog = new BlockStateFingerprint(
                "minecraft:oak_log", Map.of("axis", "x"));
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                oakLog, "minecraft:oak_log");
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint(
                        "minecraft:ladder", Map.of("facing", "north", "waterlogged", "false")),
                "minecraft:ladder");
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:wall_torch", Map.of("facing", "north")),
                "minecraft:torch");
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                        "facing", "north", "half", "bottom", "shape", "outer_left",
                        "waterlogged", "false")),
                "minecraft:oak_stairs");
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_slab", Map.of(
                        "type", "top", "waterlogged", "false")),
                "minecraft:oak_slab");
        SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_door", Map.of(
                        "facing", "east", "half", "lower", "hinge", "right",
                        "open", "false", "powered", "false")),
                "minecraft:oak_door");

        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_log", Map.of()),
                "minecraft:oak_log"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                oakLog, "minecraft:stone"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:redstone_block", Map.of()),
                "minecraft:redstone_block"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
        assertThatThrownBy(() -> SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                new BlockStateFingerprint("minecraft:oak_slab", Map.of(
                        "type", "double", "waterlogged", "false")),
                "minecraft:oak_slab"))
                .isInstanceOf(
                        SafeConstructionBlockPolicy.UnsafeConstructionBlockException.class);
    }

    @Test
    void defersOnlyAuditedHorizontalNeighborPropertiesUntilFinalVerification() {
        var finalCorner = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "inner_left",
                "waterlogged", "false"));
        var immediateStraight = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "straight",
                "waterlogged", "false"));
        var wrongFacing = new BlockStateFingerprint("minecraft:oak_stairs", Map.of(
                "facing", "south", "half", "bottom", "shape", "straight",
                "waterlogged", "false"));
        var finalPane = new BlockStateFingerprint("minecraft:glass_pane", Map.of(
                "north", "true", "east", "true", "south", "false", "west", "false",
                "waterlogged", "false"));
        var immediatePane = new BlockStateFingerprint("minecraft:glass_pane", Map.of(
                "north", "false", "east", "false", "south", "false", "west", "false",
                "waterlogged", "false"));
        var wetPane = new BlockStateFingerprint("minecraft:glass_pane", Map.of(
                "north", "false", "east", "false", "south", "false", "west", "false",
                "waterlogged", "true"));

        assertThat(SafeConstructionBlockPolicy
                .placementStateMatchesFinalStableProperties(
                        finalCorner, immediateStraight)).isTrue();
        assertThat(SafeConstructionBlockPolicy
                .placementStateMatchesFinalStableProperties(
                        finalCorner, wrongFacing)).isFalse();
        assertThat(SafeConstructionBlockPolicy
                .placementStateMatchesFinalStableProperties(
                        finalPane, immediatePane)).isTrue();
        assertThat(SafeConstructionBlockPolicy
                .placementStateMatchesFinalStableProperties(
                        finalPane, wetPane)).isFalse();
        assertThat(SafeConstructionBlockPolicy
                .placementStateMatchesFinalStableProperties(
                        new BlockStateFingerprint("minecraft:stone", Map.of()),
                        new BlockStateFingerprint("minecraft:cobblestone", Map.of())))
                .isFalse();
    }
}
