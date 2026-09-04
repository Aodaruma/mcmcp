package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Deterministic local Phase 4 plans inside the existing private fixture arena. */
final class FixturePhase4Scenario {
    static final BlockPos ORIGIN = new BlockPos(198, 200, 194);
    static final BlockPos TARGET_A = ORIGIN.offset(4, 0, -1);
    static final BlockPos TARGET_B = ORIGIN.offset(4, 0, 0);
    static final BlockPos TARGET_C = ORIGIN.offset(4, 0, 1);
    static final BlockPos HIDDEN_TARGET = ORIGIN.offset(5, 1, 0);
    static final BlockPos HIDDEN_APERTURE = HIDDEN_TARGET.relative(Direction.WEST);
    static final BlockPos BUILD_RUNNER_START_POSE = ORIGIN;
    static final BlockPos BUILD_RUNNER_FIRST_POSE = ORIGIN.offset(1, 0, -1);
    static final BlockPos BUILD_RUNNER_SECOND_POSE = ORIGIN.offset(1, 0, 1);
    static final List<BlockPos> BUILD_RUNNER_FIRST_COLUMN = List.of(
            ORIGIN.offset(3, 0, -1), ORIGIN.offset(3, 1, -1));
    static final List<BlockPos> BUILD_RUNNER_SECOND_COLUMN = List.of(
            ORIGIN.offset(3, 0, 1), ORIGIN.offset(3, 1, 1));
    static final int BUILD_RUNNER_COBBLESTONE_COUNT = 8;
    static final BlockPos STAIRS_MATRIX_PLAYER_SUPPORT = new BlockPos(201, 199, 197);
    static final BlockPos STAIRS_MATRIX_STRAIGHT_SOURCE = new BlockPos(199, 203, 192);
    static final BlockPos STAIRS_MATRIX_INNER_SOURCE = new BlockPos(201, 203, 192);
    static final BlockPos STAIRS_MATRIX_INNER_COMPANION = new BlockPos(201, 203, 193);
    static final BlockPos STAIRS_MATRIX_OUTER_SOURCE = new BlockPos(203, 203, 193);
    static final BlockPos STAIRS_MATRIX_OUTER_COMPANION = new BlockPos(203, 203, 192);
    static final List<BlockPos> STAIRS_MATRIX_SOURCES = List.of(
            STAIRS_MATRIX_STRAIGHT_SOURCE,
            STAIRS_MATRIX_INNER_SOURCE,
            STAIRS_MATRIX_INNER_COMPANION,
            STAIRS_MATRIX_OUTER_SOURCE,
            STAIRS_MATRIX_OUTER_COMPANION);
    static final BlockPos STAIRS_MATRIX_TARGET_ANCHOR = new BlockPos(202, 200, 194);
    static final List<BlockPos> STAIRS_MATRIX_TARGETS = List.of(
            new BlockPos(202, 200, 196),
            new BlockPos(202, 200, 194),
            new BlockPos(201, 200, 194),
            new BlockPos(201, 200, 192),
            new BlockPos(202, 200, 192));
    static final int STAIRS_MATRIX_ITEM_COUNT = 8;
    static final float PLAN_YAW_DEGREES = -90.0F;
    static final float PLAN_PITCH_DEGREES = 24.0F;
    static final float HIDDEN_PITCH_DEGREES = 5.0F;

    private static final int WORKSPACE_LENGTH = 8;
    private static final int WORKSPACE_RADIUS = 2;

    private FixturePhase4Scenario() {
    }

    static void prepare(FixtureSecurity.Context context, Mode mode, Consumer<Component> output) {
        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        FixturePhase2Scenario.stop();
        FixturePhase4RouteBlocker.stop();
        FixturePhase3Scenario.stop(context);
        FixtureArena.requireInitialized(context.level());
        FixtureArena.resetPlayer(context.player());
        applyLayout(context.level(), mode);
        configureInventory(context.player(), mode);
        BlockPos playerPose = mode == Mode.DIRECTIONAL_STAIRS_MATRIX
                ? STAIRS_MATRIX_PLAYER_SUPPORT.above() : BUILD_RUNNER_START_POSE;
        teleport(context,
                playerPose.getX() + 0.5D,
                playerPose.getY(),
                playerPose.getZ() + 0.5D,
                mode == Mode.DIRECTIONAL_STAIRS_MATRIX ? 180.0F : PLAN_YAW_DEGREES,
                mode == Mode.HIDDEN ? HIDDEN_PITCH_DEGREES : PLAN_PITCH_DEGREES);
        if (mode == Mode.BUILD_RUNNER) {
            FixturePhase4RouteBlocker.arm(context);
        }

        output.accept(Component.literal("phase4.mode=" + mode.wireName
                + " phase_id=" + mode.phaseId()
                + " player=" + position(playerPose)
                + " cells=" + planLine(mode)
                + " selected_slot=" + mode.selectedSlot()
                + (mode == Mode.BUILD_RUNNER
                        ? " cobblestone=" + BUILD_RUNNER_COBBLESTONE_COUNT
                                + " poses=" + position(BUILD_RUNNER_FIRST_POSE)
                                + ";" + position(BUILD_RUNNER_SECOND_POSE)
                                + " blocker_ticks=" + FixturePhase4RouteBlocker.OCCUPANCY_TICKS
                        : "")));
    }

    static void introduceDivergence(FixtureSecurity.Context context) {
        FixtureArena.requireInitialized(context.level());
        FixtureArena.setBlock(context.level(), TARGET_B, Blocks.GOLD_BLOCK.defaultBlockState());
    }

    static void revealHidden(FixtureSecurity.Context context) {
        FixtureArena.requireInitialized(context.level());
        FixtureArena.setBlock(context.level(), HIDDEN_APERTURE, Blocks.AIR.defaultBlockState());
    }

    static void concealHidden(FixtureSecurity.Context context) {
        FixtureArena.requireInitialized(context.level());
        FixtureArena.setBlock(context.level(), HIDDEN_APERTURE, Blocks.STONE.defaultBlockState());
    }

    static Map<BlockPos, BlockState> layout(Mode mode) {
        Map<BlockPos, BlockState> result = baseWorkspace();
        switch (mode) {
            case ALL_SATISFIED -> {
                put(result, TARGET_A, Blocks.STONE.defaultBlockState());
                put(result, TARGET_B, Blocks.AIR.defaultBlockState());
                put(result, TARGET_C, Blocks.COBBLESTONE.defaultBlockState());
            }
            case MUTATIONS -> {
                put(result, TARGET_A, Blocks.STONE.defaultBlockState());
                put(result, TARGET_B, Blocks.AIR.defaultBlockState());
                put(result, TARGET_C, Blocks.DIRT.defaultBlockState());
            }
            case WATERLOGGED -> put(result, TARGET_B, Blocks.WATER.defaultBlockState());
            case DIRECTIONAL_STAIRS, HOPPER, SHORTAGE -> {
                put(result, TARGET_A, Blocks.AIR.defaultBlockState());
                put(result, TARGET_B, Blocks.AIR.defaultBlockState());
            }
            case DIRECTIONAL_STAIRS_MATRIX -> {
                put(result, STAIRS_MATRIX_PLAYER_SUPPORT, Blocks.SMOOTH_STONE.defaultBlockState());
                STAIRS_MATRIX_TARGETS.forEach(position ->
                        put(result, position, Blocks.AIR.defaultBlockState()));
                STAIRS_MATRIX_SOURCES.forEach(position ->
                        put(result, position.below(), Blocks.STONE.defaultBlockState()));
                put(result, STAIRS_MATRIX_STRAIGHT_SOURCE,
                        northBottomStairs(StairsShape.STRAIGHT));
                put(result, STAIRS_MATRIX_INNER_SOURCE,
                        northBottomStairs(StairsShape.INNER_LEFT));
                put(result, STAIRS_MATRIX_INNER_COMPANION, westBottomStraightStairs());
                put(result, STAIRS_MATRIX_OUTER_SOURCE,
                        northBottomStairs(StairsShape.OUTER_LEFT));
                put(result, STAIRS_MATRIX_OUTER_COMPANION, westBottomStraightStairs());
            }
            case DIVERGENCE -> {
                put(result, TARGET_A, Blocks.OBSIDIAN.defaultBlockState());
                put(result, TARGET_B, Blocks.DIRT.defaultBlockState());
            }
            case HIDDEN -> buildHiddenBox(result);
            case BUILD_RUNNER -> {
                BUILD_RUNNER_FIRST_COLUMN.forEach(position ->
                        put(result, position, Blocks.AIR.defaultBlockState()));
                BUILD_RUNNER_SECOND_COLUMN.forEach(position ->
                        put(result, position, Blocks.AIR.defaultBlockState()));
            }
        }
        return Map.copyOf(result);
    }

    static List<PlanCell> plan(Mode mode) {
        return switch (mode) {
            case ALL_SATISFIED -> List.of(
                    verify("stone_done", TARGET_A, Blocks.STONE.defaultBlockState()),
                    verify("air_done", TARGET_B, Blocks.AIR.defaultBlockState()),
                    verify("cobble_done", TARGET_C, Blocks.COBBLESTONE.defaultBlockState()));
            case MUTATIONS -> List.of(
                    cell("break_stone", TARGET_A, "break_to_air",
                            Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), null),
                    cell("place_cobble", TARGET_B, "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE),
                    cell("replace_dirt", TARGET_C, "replace",
                            Blocks.DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE));
            case WATERLOGGED -> List.of(cell(
                    "waterlogged_slab", TARGET_B, "place",
                    Blocks.WATER.defaultBlockState(), waterloggedSlab(), Items.SMOOTH_STONE_SLAB));
            case DIRECTIONAL_STAIRS -> List.of(cell(
                    "east_stairs", TARGET_B, "place",
                    Blocks.AIR.defaultBlockState(), eastBottomStairs(), Items.OAK_STAIRS));
            case DIRECTIONAL_STAIRS_MATRIX -> List.of(
                    verify("matrix_straight_source", STAIRS_MATRIX_STRAIGHT_SOURCE,
                            northBottomStairs(StairsShape.STRAIGHT)),
                    verify("matrix_inner_source", STAIRS_MATRIX_INNER_SOURCE,
                            northBottomStairs(StairsShape.INNER_LEFT)),
                    verify("matrix_inner_companion", STAIRS_MATRIX_INNER_COMPANION,
                            westBottomStraightStairs()),
                    verify("matrix_outer_source", STAIRS_MATRIX_OUTER_SOURCE,
                            northBottomStairs(StairsShape.OUTER_LEFT)),
                    verify("matrix_outer_companion", STAIRS_MATRIX_OUTER_COMPANION,
                            westBottomStraightStairs()));
            case HOPPER -> List.of(cell(
                    "down_hopper", TARGET_B, "place",
                    Blocks.AIR.defaultBlockState(), downHopper(), Items.HOPPER));
            case SHORTAGE -> List.of(
                    cell("cobble_one", TARGET_A, "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE),
                    cell("cobble_two", TARGET_B, "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE));
            case DIVERGENCE -> List.of(
                    cell("slow_break", TARGET_A, "break_to_air",
                            Blocks.OBSIDIAN.defaultBlockState(), Blocks.AIR.defaultBlockState(), null),
                    verify("guard_cell", TARGET_B, Blocks.DIRT.defaultBlockState()));
            case HIDDEN -> List.of(verify(
                    "hidden_gold", HIDDEN_TARGET, Blocks.GOLD_BLOCK.defaultBlockState()));
            case BUILD_RUNNER -> List.of(
                    cell("runner_first_base", BUILD_RUNNER_FIRST_COLUMN.get(0), "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE),
                    cell("runner_first_top", BUILD_RUNNER_FIRST_COLUMN.get(1), "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE),
                    cell("runner_second_base", BUILD_RUNNER_SECOND_COLUMN.get(0), "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE),
                    cell("runner_second_top", BUILD_RUNNER_SECOND_COLUMN.get(1), "place",
                            Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Items.COBBLESTONE));
        };
    }

    static BlockState waterloggedSlab() {
        return Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, true);
    }

    static BlockState eastBottomStairs() {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState northBottomStairs(StairsShape shape) {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.STAIRS_SHAPE, shape)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState westBottomStraightStairs() {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState downHopper() {
        return Blocks.HOPPER.defaultBlockState()
                .setValue(BlockStateProperties.FACING_HOPPER, Direction.DOWN)
                .setValue(BlockStateProperties.ENABLED, true);
    }

    private static Map<BlockPos, BlockState> baseWorkspace() {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x = 0; x < WORKSPACE_LENGTH; x++) {
            for (int z = -WORKSPACE_RADIUS; z <= WORKSPACE_RADIUS; z++) {
                result.put(new BlockPos(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) {
                    result.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        return result;
    }

    private static void buildHiddenBox(Map<BlockPos, BlockState> result) {
        for (int x = HIDDEN_TARGET.getX() - 1; x <= HIDDEN_TARGET.getX() + 1; x++) {
            for (int y = HIDDEN_TARGET.getY() - 1; y <= HIDDEN_TARGET.getY() + 1; y++) {
                for (int z = HIDDEN_TARGET.getZ() - 1; z <= HIDDEN_TARGET.getZ() + 1; z++) {
                    boolean shell = x == HIDDEN_TARGET.getX() - 1
                            || x == HIDDEN_TARGET.getX() + 1
                            || y == HIDDEN_TARGET.getY() - 1
                            || y == HIDDEN_TARGET.getY() + 1
                            || z == HIDDEN_TARGET.getZ() - 1
                            || z == HIDDEN_TARGET.getZ() + 1;
                    if (shell) {
                        put(result, new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        put(result, HIDDEN_TARGET, Blocks.GOLD_BLOCK.defaultBlockState());
        put(result, HIDDEN_APERTURE, Blocks.STONE.defaultBlockState());
    }

    private static void applyLayout(ServerLevel level, Mode mode) {
        layout(mode).forEach((offset, state) -> FixtureArena.setBlock(level, ORIGIN.offset(offset), state));
    }

    private static void configureInventory(ServerPlayer player, Mode mode) {
        switch (mode) {
            case MUTATIONS -> player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 8));
            case WATERLOGGED -> player.getInventory().setItem(
                    7, new ItemStack(Items.SMOOTH_STONE_SLAB, 4));
            case DIRECTIONAL_STAIRS -> player.getInventory().setItem(
                    7, new ItemStack(Items.OAK_STAIRS, 4));
            case DIRECTIONAL_STAIRS_MATRIX -> player.getInventory().setItem(
                    7, new ItemStack(Items.OAK_STAIRS, STAIRS_MATRIX_ITEM_COUNT));
            case HOPPER -> player.getInventory().setItem(7, new ItemStack(Items.HOPPER, 2));
            case SHORTAGE -> player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 1));
            case DIVERGENCE -> player.getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_PICKAXE));
            case BUILD_RUNNER -> player.getInventory().setItem(
                    1, new ItemStack(Items.COBBLESTONE, BUILD_RUNNER_COBBLESTONE_COUNT));
            case ALL_SATISFIED, HIDDEN -> {
            }
        }
        player.getInventory().setSelectedSlot(mode.selectedSlot());
        synchronizeInventory(player);
    }

    private static void teleport(
            FixtureSecurity.Context context,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {
        if (!context.player().teleportTo(
                context.level(), x, y, z, Set.<Relative>of(), yaw, pitch, false)) {
            throw new IllegalStateException("Phase 4 fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static void synchronizeInventory(ServerPlayer player) {
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static PlanCell verify(String id, BlockPos target, BlockState state) {
        return cell(id, target, "verify_only", state, state, null);
    }

    private static PlanCell cell(
            String id,
            BlockPos target,
            String operation,
            BlockState before,
            BlockState after,
            Item item) {
        return new PlanCell(id, target, operation, before, after, Optional.ofNullable(item));
    }

    private static void put(Map<BlockPos, BlockState> layout, BlockPos absolute, BlockState state) {
        layout.put(absolute.subtract(ORIGIN), state);
    }

    private static String planLine(Mode mode) {
        StringBuilder result = new StringBuilder();
        for (var cell : plan(mode)) {
            if (!result.isEmpty()) {
                result.append(';');
            }
            result.append(cell.id()).append('@').append(position(cell.target()))
                    .append(':').append(cell.operation())
                    .append(':').append(stateText(cell.before())).append("->")
                    .append(stateText(cell.after()));
            cell.item().ifPresent(item -> result.append(":item=")
                    .append(BuiltInRegistries.ITEM.getKey(item)));
        }
        return result.toString();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String stateText(BlockState state) {
        return blockId(state) + state.getValues();
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    record PlanCell(
            String id,
            BlockPos target,
            String operation,
            BlockState before,
            BlockState after,
            Optional<Item> item) {
        PlanCell {
            item = item == null ? Optional.empty() : item;
        }
    }

    enum Mode {
        ALL_SATISFIED("all_satisfied", "fixture_all_satisfied", 0),
        MUTATIONS("mutations", "fixture_mutations", 0),
        WATERLOGGED("waterlogged", "fixture_waterlogged", 7),
        DIRECTIONAL_STAIRS("directional_stairs", "fixture_directional_stairs", 7),
        DIRECTIONAL_STAIRS_MATRIX(
                "directional_stairs_matrix", "fixture_directional_stairs_matrix", 7),
        HOPPER("hopper", "fixture_hopper", 7),
        SHORTAGE("shortage", "fixture_shortage", 1),
        DIVERGENCE("divergence", "fixture_divergence", 0),
        HIDDEN("hidden", "fixture_hidden", 0),
        BUILD_RUNNER("build_runner", "fixture_build_runner", 1);

        private final String wireName;
        private final String phaseId;
        private final int selectedSlot;

        Mode(String wireName, String phaseId, int selectedSlot) {
            this.wireName = wireName;
            this.phaseId = phaseId;
            this.selectedSlot = selectedSlot;
        }

        String phaseId() {
            return phaseId;
        }

        int selectedSlot() {
            return selectedSlot;
        }
    }
}
