package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One bounded workspace covering Phase 5 families and the combined wheat E2E. */
final class FixturePhase5Scenario {
    static final BlockPos WORKSPACE_MIN = new BlockPos(193, 199, 193);
    static final BlockPos WORKSPACE_MAX = new BlockPos(206, 204, 206);

    static final BlockPos CRAFTING_TABLE = new BlockPos(195, 200, 194);
    static final BlockPos FURNACE = new BlockPos(196, 200, 194);
    static final BlockPos BREWING_STAND = new BlockPos(197, 200, 194);
    static final BlockPos REDSTONE_LAMP_TARGET = new BlockPos(201, 200, 194);
    static final BlockPos REDSTONE_LEVER_TARGET = new BlockPos(202, 200, 194);
    static final BlockPos TRANSFER_BARREL = new BlockPos(199, 200, 194);

    static final List<BlockPos> GENERALIZATION_FAN_OUT_LAMPS = List.of(
            REDSTONE_LAMP_TARGET, REDSTONE_LAMP_TARGET.relative(Direction.EAST, 2));
    static final List<BlockPos> GENERALIZATION_CONSTRUCTION_BLOCKS = List.of(
            new BlockPos(194, 200, 197), new BlockPos(195, 200, 197));
    static final BlockPos GENERALIZATION_CHEST_WEST = new BlockPos(194, 200, 203);
    static final BlockPos GENERALIZATION_CHEST_EAST =
            GENERALIZATION_CHEST_WEST.relative(Direction.EAST);
    static final BlockPos GENERALIZATION_LADDER_BASE = new BlockPos(204, 200, 200);
    static final List<BlockPos> GENERALIZATION_LADDER_RUNGS = List.of(
            GENERALIZATION_LADDER_BASE,
            GENERALIZATION_LADDER_BASE.above(),
            GENERALIZATION_LADDER_BASE.above(2),
            GENERALIZATION_LADDER_BASE.above(3));
    static final List<BlockPos> GENERALIZATION_LADDER_BACKING = GENERALIZATION_LADDER_RUNGS.stream()
            .map(position -> position.relative(Direction.WEST))
            .toList();
    static final BlockPos GENERALIZATION_LADDER_LOWER_LANDING =
            GENERALIZATION_LADDER_BASE.relative(Direction.EAST);
    static final BlockPos GENERALIZATION_LADDER_UPPER_LANDING =
            GENERALIZATION_LADDER_LOWER_LANDING.above(3);
    static final List<BlockPos> GENERALIZATION_LADDER_UPPER_PLATFORM = List.of(
            new BlockPos(205, 202, 199), new BlockPos(205, 202, 200),
            new BlockPos(205, 202, 201), new BlockPos(206, 202, 199),
            new BlockPos(206, 202, 200), new BlockPos(206, 202, 201));
    static final BlockPos GENERALIZATION_SCAFFOLDING_BASE = new BlockPos(200, 200, 199);
    static final List<BlockPos> GENERALIZATION_SCAFFOLDING_COLUMN = List.of(
            GENERALIZATION_SCAFFOLDING_BASE,
            GENERALIZATION_SCAFFOLDING_BASE.above(),
            GENERALIZATION_SCAFFOLDING_BASE.above(2),
            GENERALIZATION_SCAFFOLDING_BASE.above(3));
    static final BlockPos GENERALIZATION_SCAFFOLDING_LOWER_LANDING =
            GENERALIZATION_SCAFFOLDING_BASE.relative(Direction.WEST);
    static final BlockPos GENERALIZATION_SCAFFOLDING_UPPER_LANDING =
            GENERALIZATION_SCAFFOLDING_LOWER_LANDING.above(3);
    static final List<BlockPos> GENERALIZATION_SCAFFOLDING_UPPER_PLATFORM = List.of(
            new BlockPos(198, 202, 198), new BlockPos(198, 202, 199),
            new BlockPos(198, 202, 200), new BlockPos(199, 202, 198),
            new BlockPos(199, 202, 199), new BlockPos(199, 202, 200));
    static final BlockPos GENERALIZATION_WIRE_LAMP_TARGET = new BlockPos(201, 200, 204);
    static final BlockPos GENERALIZATION_WIRE_TARGET =
            GENERALIZATION_WIRE_LAMP_TARGET.relative(Direction.EAST);
    static final BlockPos GENERALIZATION_WIRE_LEVER_TARGET =
            GENERALIZATION_WIRE_TARGET.relative(Direction.EAST);
    static final BlockPos GENERALIZATION_WIRE_OBSERVATION_PEDESTAL =
            GENERALIZATION_WIRE_LEVER_TARGET.relative(Direction.EAST, 2);
    static final List<BlockPos> GENERALIZATION_WIRE_SUPPORTS = List.of(
            GENERALIZATION_WIRE_LAMP_TARGET.below(),
            GENERALIZATION_WIRE_TARGET.below(),
            GENERALIZATION_WIRE_LEVER_TARGET.below());

    static final BlockPos COMBINED_SUPPLY_CHEST = new BlockPos(199, 200, 195);
    static final BlockPos COMBINED_FARM_GATE = new BlockPos(199, 200, 199);
    static final BlockPos COMBINED_FARM_WATER = new BlockPos(197, 199, 201);
    static final List<BlockPos> COMBINED_FARM_SUPPORTS = combinedFarmSupports();
    static final List<BlockPos> COMBINED_FARM_CROPS = COMBINED_FARM_SUPPORTS.stream()
            .map(BlockPos::above)
            .toList();
    static final List<BlockPos> COMBINED_FARM_FENCE = combinedFarmFence();
    static final int COMBINED_HOE_DAMAGE = 37;
    static final int COMBINED_SEED_COUNT = 64;
    static final int COMBINED_WHEAT_GOAL = 64;

    static final BlockPos CROP_WATER = new BlockPos(192, 199, 198);
    static final List<BlockPos> CROP_SUPPORTS = List.of(
            new BlockPos(193, 199, 198),
            new BlockPos(194, 199, 198),
            new BlockPos(195, 199, 198));
    static final List<BlockPos> MATURE_CROPS = CROP_SUPPORTS.stream()
            .map(BlockPos::above)
            .toList();

    static final BlockPos TREE_SUPPORT = new BlockPos(204, 199, 198);
    static final List<BlockPos> TREE_LOGS = List.of(
            TREE_SUPPORT.above(), TREE_SUPPORT.above(2), TREE_SUPPORT.above(3));
    static final BlockPos TREE_SAPLING_POSITION = TREE_SUPPORT.above();
    static final List<BlockPos> TREE_GROWTH_CLEARANCE = List.of(
            TREE_SUPPORT.above(4),
            TREE_SUPPORT.above(2).relative(Direction.NORTH),
            TREE_SUPPORT.above(2).relative(Direction.SOUTH),
            TREE_SUPPORT.above(2).relative(Direction.WEST),
            TREE_SUPPORT.above(2).relative(Direction.EAST));
    static final BlockPos TREE_ENCLOSURE_MIN = new BlockPos(199, 200, 195);
    static final BlockPos TREE_ENCLOSURE_MAX = new BlockPos(206, 200, 201);
    static final List<BlockPos> TREE_ENCLOSURE = treeEnclosure();

    static final BlockPos BED_FOOT = new BlockPos(196, 200, 204);
    static final BlockPos BED_HEAD = BED_FOOT.relative(Direction.EAST);

    static final List<BlockPos> SURVEY_WAYPOINTS = List.of(
            new BlockPos(201, 200, 204),
            new BlockPos(204, 200, 204));
    static final List<BlockPos> SURVEY_SAMPLES = List.of(
            new BlockPos(200, 199, 203),
            new BlockPos(201, 199, 203),
            new BlockPos(202, 199, 204),
            new BlockPos(203, 199, 204),
            new BlockPos(204, 199, 205),
            new BlockPos(205, 199, 205));

    private static final List<ResourceKey<Recipe<?>>> KNOWN_RECIPE_KEYS = List.of(
            recipeKey("oak_planks"), recipeKey("stick"));
    private static final List<ContainerEntry> TRANSFER_CONTENTS = List.of(
            new ContainerEntry(0, Items.COBBLESTONE, 12),
            new ContainerEntry(8, Items.OAK_LOG, 4));
    private static final List<ContainerEntry> GENERALIZATION_CHEST_WEST_CONTENTS = List.of(
            new ContainerEntry(0, Items.COBBLESTONE, 12));
    private static final List<ContainerEntry> GENERALIZATION_CHEST_EAST_CONTENTS = List.of(
            new ContainerEntry(26, Items.OAK_LOG, 4));

    private FixturePhase5Scenario() {
    }

    static void prepare(
            FixtureSecurity.Context context,
            FixturePhase5Mode mode,
            Consumer<Component> output) {
        if (mode == FixturePhase5Mode.IRON_FARM) {
            FixtureCombinedWheatScenario.rollbackForReplacement(context);
            FixturePhase2Scenario.stop();
            FixturePhase3Scenario.stop(context);
            FixturePhase4RouteBlocker.stop();
            FixtureIronFarmScenario.prepare(context, output);
            return;
        }
        if (mode == FixturePhase5Mode.RESET) {
            FixtureArena.load(context);
            resetKnownRecipes(context);
            output.accept(Component.literal("phase5.mode=reset arena=baseline recipes=cleared"));
            return;
        }

        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        if (mode == FixturePhase5Mode.COMBINED_WHEAT) {
            FixtureRandomTicks.requireInactiveForCombinedWheat(context);
        }
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        FixturePhase4RouteBlocker.stop();
        FixtureArena.requireInitialized(context.level());
        discardItemEntities(
                context.level(), AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX));
        FixtureArena.resetPlayer(context.player());
        if (mode == FixturePhase5Mode.COMBINED_WHEAT) {
            resetCombinedWheatWorkspace(context.level());
        } else if (mode == FixturePhase5Mode.GENERALIZATION) {
            applyGeneralizationLayout(context.level());
            configureGeneralizationChest(context.level());
        } else {
            resetStatefulWorkstations(context.level());
            applyLayout(context.level());
            if (mode == FixturePhase5Mode.REDSTONE) {
                FixtureArena.setBlock(context.level(),
                        REDSTONE_LEVER_TARGET.relative(Direction.SOUTH),
                        Blocks.AIR.defaultBlockState());
            }
            configureBarrel(context.level());
            configureFurnace(context.level());
            configureBrewingStand(context.level());
        }
        resetKnownRecipes(context);
        if (mode == FixturePhase5Mode.RECIPES || mode == FixturePhase5Mode.CRAFT) {
            context.player().awardRecipesByKey(KNOWN_RECIPE_KEYS);
        } else if (mode == FixturePhase5Mode.SMELT) {
            context.player().awardRecipesByKey(List.of(
                    recipeKey("iron_ingot_from_smelting_raw_iron")));
        }
        configureInventory(context.player(), mode);
        if (mode == FixturePhase5Mode.SLEEP) {
            prepareSafeNight(context.level());
        }
        teleport(context, pose(mode));

        if (mode == FixturePhase5Mode.COMBINED_WHEAT) {
            FixtureCombinedWheatScenario.arm(context, output);
            output.accept(Component.literal("phase5.mode=combined_wheat"
                    + " chest=" + position(COMBINED_SUPPLY_CHEST)
                    + " gate=" + position(COMBINED_FARM_GATE)
                    + " dirt=" + positions(COMBINED_FARM_SUPPORTS)
                    + " water=" + position(COMBINED_FARM_WATER)
                    + " hoe=minecraft:iron_hoe damage=" + COMBINED_HOE_DAMAGE
                    + " seeds=" + COMBINED_SEED_COUNT
                    + " wheat_goal=" + COMBINED_WHEAT_GOAL
                    + " selected_slot=" + mode.selectedSlot()));
            return;
        }
        if (mode == FixturePhase5Mode.GENERALIZATION) {
            output.accept(Component.literal("phase5.mode=generalization"
                    + " fan_out=" + positions(GENERALIZATION_FAN_OUT_LAMPS)
                    + " lever=" + position(REDSTONE_LEVER_TARGET)
                    + " wire=" + position(GENERALIZATION_WIRE_LAMP_TARGET)
                    + "->" + position(GENERALIZATION_WIRE_TARGET)
                    + "->" + position(GENERALIZATION_WIRE_LEVER_TARGET)
                    + " ladder=" + position(GENERALIZATION_LADDER_LOWER_LANDING)
                    + "->" + position(GENERALIZATION_LADDER_UPPER_LANDING)
                    + " scaffolding=" + position(GENERALIZATION_SCAFFOLDING_LOWER_LANDING)
                    + "->" + position(GENERALIZATION_SCAFFOLDING_UPPER_LANDING)
                    + " construction=" + positions(GENERALIZATION_CONSTRUCTION_BLOCKS)
                    + " chest=" + position(GENERALIZATION_CHEST_WEST)
                    + ";" + position(GENERALIZATION_CHEST_EAST)
                    + " selected_slot=" + mode.selectedSlot()));
            return;
        }

        output.accept(Component.literal("phase5.mode=" + mode.wireName()
                + " table=" + position(CRAFTING_TABLE)
                + " furnace=" + position(FURNACE)
                + " brewing=" + position(BREWING_STAND)
                + " redstone=" + position(REDSTONE_LAMP_TARGET)
                + "->" + position(REDSTONE_LEVER_TARGET)
                + " container=" + position(TRANSFER_BARREL)
                + " crops=" + positions(MATURE_CROPS)
                + " logs=" + positions(TREE_LOGS)
                + " tree_support=" + position(TREE_SUPPORT)
                + " bed=" + position(BED_FOOT) + "->" + position(BED_HEAD)
                + " survey_samples=" + positions(SURVEY_SAMPLES)
                + " selected_slot=" + mode.selectedSlot()));
    }

    static Map<BlockPos, BlockState> layout() {
        var result = emptyWorkspace();
        result.put(CROP_WATER, Blocks.WATER.defaultBlockState());
        result.put(CRAFTING_TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
        result.put(FURNACE, Blocks.FURNACE.defaultBlockState());
        result.put(BREWING_STAND, Blocks.BREWING_STAND.defaultBlockState());
        result.put(REDSTONE_LEVER_TARGET.below(), Blocks.GLASS.defaultBlockState());
        result.put(TRANSFER_BARREL, barrelState());
        for (BlockPos support : CROP_SUPPORTS) {
            result.put(support, hydratedFarmland());
            result.put(support.above(), matureWheat());
        }
        result.put(TREE_SUPPORT, Blocks.DIRT.defaultBlockState());
        for (BlockPos log : TREE_LOGS) {
            result.put(log, oakLog());
        }
        for (BlockPos clearance : TREE_GROWTH_CLEARANCE) {
            result.put(clearance, Blocks.AIR.defaultBlockState());
        }
        for (BlockPos fence : TREE_ENCLOSURE) {
            result.put(fence, Blocks.OAK_FENCE.defaultBlockState());
        }
        result.put(BED_FOOT, bedState(BedPart.FOOT));
        result.put(BED_HEAD, bedState(BedPart.HEAD));
        return Map.copyOf(result);
    }

    static Map<BlockPos, BlockState> combinedWheatLayout() {
        var result = emptyWorkspace();
        result.put(COMBINED_SUPPLY_CHEST, combinedSupplyChestState());
        result.put(COMBINED_FARM_WATER, Blocks.WATER.defaultBlockState());
        for (BlockPos support : COMBINED_FARM_SUPPORTS) {
            result.put(support, Blocks.DIRT.defaultBlockState());
            result.put(support.above(), Blocks.AIR.defaultBlockState());
        }
        for (BlockPos fence : COMBINED_FARM_FENCE) {
            result.put(fence, fence.equals(COMBINED_FARM_GATE)
                    ? closedCombinedFarmGate()
                    : Blocks.OAK_FENCE.defaultBlockState());
        }
        return Map.copyOf(result);
    }

    static Map<BlockPos, BlockState> generalizationLayout() {
        var result = emptyWorkspace();
        result.put(REDSTONE_LEVER_TARGET.below(), Blocks.GLASS.defaultBlockState());
        for (BlockPos position : GENERALIZATION_CONSTRUCTION_BLOCKS) {
            result.put(position, Blocks.GLASS.defaultBlockState());
        }
        result.put(GENERALIZATION_CHEST_WEST, generalizationChestState(ChestType.RIGHT));
        result.put(GENERALIZATION_CHEST_EAST, generalizationChestState(ChestType.LEFT));
        for (BlockPos position : GENERALIZATION_LADDER_BACKING) {
            result.put(position, Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for (BlockPos position : GENERALIZATION_LADDER_RUNGS) {
            result.put(position, generalizationLadderState());
        }
        for (BlockPos position : GENERALIZATION_LADDER_UPPER_PLATFORM) {
            result.put(position, Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for (BlockPos position : GENERALIZATION_SCAFFOLDING_COLUMN) {
            result.put(position, generalizationScaffoldingState());
        }
        for (BlockPos position : GENERALIZATION_SCAFFOLDING_UPPER_PLATFORM) {
            result.put(position, Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for (int x = GENERALIZATION_WIRE_LAMP_TARGET.getX() - 1;
                x <= GENERALIZATION_WIRE_LEVER_TARGET.getX() + 1; x++) {
            for (int z = GENERALIZATION_WIRE_LAMP_TARGET.getZ() - 1;
                    z <= GENERALIZATION_WIRE_LAMP_TARGET.getZ() + 1; z++) {
                result.put(new BlockPos(x, WORKSPACE_MIN.getY(), z),
                        Blocks.AIR.defaultBlockState());
            }
        }
        for (BlockPos position : GENERALIZATION_WIRE_SUPPORTS) {
            result.put(position, Blocks.GLASS.defaultBlockState());
        }
        result.put(GENERALIZATION_WIRE_OBSERVATION_PEDESTAL,
                Blocks.SMOOTH_STONE.defaultBlockState());
        return Map.copyOf(result);
    }

    static BlockState generalizationLadderState() {
        return Blocks.LADDER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    static BlockState generalizationScaffoldingState() {
        return Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, 0)
                .setValue(ScaffoldingBlock.BOTTOM, false)
                .setValue(ScaffoldingBlock.WATERLOGGED, false);
    }

    static BlockState generalizationChestState(ChestType type) {
        return Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH)
                .setValue(ChestBlock.TYPE, type)
                .setValue(ChestBlock.WATERLOGGED, false);
    }

    static BlockState combinedSupplyChestState() {
        return Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH)
                .setValue(ChestBlock.TYPE, ChestType.SINGLE)
                .setValue(ChestBlock.WATERLOGGED, false);
    }

    static BlockState closedCombinedFarmGate() {
        return Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.IN_WALL, false);
    }

    private static LinkedHashMap<BlockPos, BlockState> emptyWorkspace() {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = WORKSPACE_MIN.getX(); x <= WORKSPACE_MAX.getX(); x++) {
            for (int z = WORKSPACE_MIN.getZ(); z <= WORKSPACE_MAX.getZ(); z++) {
                result.put(new BlockPos(x, WORKSPACE_MIN.getY(), z),
                        Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = WORKSPACE_MIN.getY() + 1; y <= WORKSPACE_MAX.getY(); y++) {
                    result.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        return result;
    }

    static BlockState barrelState() {
        return Blocks.BARREL.defaultBlockState()
                .setValue(BarrelBlock.FACING, Direction.UP)
                .setValue(BarrelBlock.OPEN, false);
    }

    static BlockState hydratedFarmland() {
        return Blocks.FARMLAND.defaultBlockState()
                .setValue(BlockStateProperties.MOISTURE, 7);
    }

    static BlockState matureWheat() {
        return Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 7);
    }

    static BlockState oakLog() {
        return Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
    }

    static BlockState bedState(BedPart part) {
        return Blocks.BED.red().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.BED_PART, part)
                .setValue(BlockStateProperties.OCCUPIED, false);
    }

    static List<ContainerEntry> transferContents() {
        return TRANSFER_CONTENTS;
    }

    static List<ContainerEntry> generalizationChestWestContents() {
        return GENERALIZATION_CHEST_WEST_CONTENTS;
    }

    static List<ContainerEntry> generalizationChestEastContents() {
        return GENERALIZATION_CHEST_EAST_CONTENTS;
    }

    static ItemStack combinedSupplyHoe() {
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        hoe.setDamageValue(COMBINED_HOE_DAMAGE);
        return hoe;
    }

    static ItemStack combinedSupplySeeds() {
        return new ItemStack(Items.WHEAT_SEEDS, COMBINED_SEED_COUNT);
    }

    static int discardItemEntities(ServerLevel level, AABB bounds) {
        var staleItems = level.getEntities(EntityTypes.ITEM, bounds, Entity::isAlive);
        staleItems.forEach(Entity::discard);
        return staleItems.size();
    }

    static int resetCombinedWheatWorkspace(ServerLevel level) {
        applyCombinedWheatLayout(level);
        configureCombinedSupplyChest(level);
        // Replacing the previous crop supports can synchronously pop wheat/seeds after the
        // pre-layout cleanup. The production evaluation starts only after this final pass.
        return discardItemEntities(
                level, AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX));
    }

    static void verifyTreeGate(
            FixtureSecurity.Context context, Consumer<Component> output) {
        ServerLevel level = context.level();
        ServerPlayer player = context.player();
        long remainingLogs = TREE_LOGS.stream()
                .filter(position -> !level.getBlockState(position).isAir())
                .count();
        boolean enclosureIntact = TREE_ENCLOSURE.stream()
                .allMatch(position -> level.getBlockState(position).is(Blocks.OAK_FENCE));
        boolean supportIntact = level.getBlockState(TREE_SUPPORT).is(Blocks.DIRT);
        boolean playerInside = player.getX() > TREE_ENCLOSURE_MIN.getX()
                && player.getX() < TREE_ENCLOSURE_MAX.getX()
                && player.getZ() > TREE_ENCLOSURE_MIN.getZ()
                && player.getZ() < TREE_ENCLOSURE_MAX.getZ();
        ItemStack axe = player.getInventory().getItem(0);
        boolean expectedAxe = axe.is(Items.IRON_AXE) && axe.getDamageValue() == TREE_LOGS.size();
        if (remainingLogs != 0L || !enclosureIntact || !supportIntact
                || !playerInside || !expectedAxe) {
            throw new IllegalStateException("phase5.tree.gate=FAIL remaining_logs="
                    + remainingLogs + " enclosure=" + enclosureIntact
                    + " support=" + supportIntact + " player_inside=" + playerInside
                    + " axe_damage=" + axe.getDamageValue());
        }
        output.accept(Component.literal("phase5.tree.gate=PASS broken_logs="
                + TREE_LOGS.size() + " enclosure=true support=true player_inside=true axe_damage="
                + axe.getDamageValue()));
    }

    private static void applyLayout(ServerLevel level) {
        Map<BlockPos, BlockState> layout = layout();
        layout.forEach((position, state) -> {
            if (!position.equals(BED_FOOT) && !position.equals(BED_HEAD)) {
                FixtureArena.setBlock(level, position, state);
            }
        });
        FixtureArena.setPairedBlocks(
                level, BED_FOOT, bedState(BedPart.FOOT), BED_HEAD, bedState(BedPart.HEAD));
    }

    private static void applyCombinedWheatLayout(ServerLevel level) {
        combinedWheatLayout().forEach((position, state) ->
                FixtureArena.setBlock(level, position, state));
    }

    private static void applyGeneralizationLayout(ServerLevel level) {
        generalizationLayout().forEach((position, state) -> {
            if (!position.equals(GENERALIZATION_CHEST_WEST)
                    && !position.equals(GENERALIZATION_CHEST_EAST)
                    && !GENERALIZATION_SCAFFOLDING_COLUMN.contains(position)) {
                FixtureArena.setBlock(level, position, state);
            }
        });
        for (BlockPos position : GENERALIZATION_SCAFFOLDING_COLUMN) {
            FixtureArena.setBlock(level, position, generalizationScaffoldingState());
        }
        FixtureArena.setPairedBlocks(
                level,
                GENERALIZATION_CHEST_WEST,
                generalizationChestState(ChestType.RIGHT),
                GENERALIZATION_CHEST_EAST,
                generalizationChestState(ChestType.LEFT));
    }

    private static List<BlockPos> treeEnclosure() {
        var result = new LinkedHashSet<BlockPos>();
        for (int x = TREE_ENCLOSURE_MIN.getX(); x <= TREE_ENCLOSURE_MAX.getX(); x++) {
            result.add(new BlockPos(x, TREE_ENCLOSURE_MIN.getY(), TREE_ENCLOSURE_MIN.getZ()));
            result.add(new BlockPos(x, TREE_ENCLOSURE_MIN.getY(), TREE_ENCLOSURE_MAX.getZ()));
        }
        for (int z = TREE_ENCLOSURE_MIN.getZ(); z <= TREE_ENCLOSURE_MAX.getZ(); z++) {
            result.add(new BlockPos(TREE_ENCLOSURE_MIN.getX(), TREE_ENCLOSURE_MIN.getY(), z));
            result.add(new BlockPos(TREE_ENCLOSURE_MAX.getX(), TREE_ENCLOSURE_MIN.getY(), z));
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> combinedFarmSupports() {
        var result = new java.util.ArrayList<BlockPos>();
        for (int x = 198; x <= 200; x++) {
            for (int z = 200; z <= 202; z++) {
                result.add(new BlockPos(x, 199, z));
            }
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> combinedFarmFence() {
        var result = new LinkedHashSet<BlockPos>();
        for (int x = 197; x <= 201; x++) {
            result.add(new BlockPos(x, 200, 199));
            result.add(new BlockPos(x, 200, 203));
        }
        for (int z = 199; z <= 203; z++) {
            result.add(new BlockPos(197, 200, z));
            result.add(new BlockPos(201, 200, z));
        }
        result.remove(COMBINED_FARM_GATE);
        var ordered = new java.util.ArrayList<>(result);
        ordered.add(COMBINED_FARM_GATE);
        return List.copyOf(ordered);
    }

    private static void configureBarrel(ServerLevel level) {
        if (!(level.getBlockEntity(TRANSFER_BARREL) instanceof Container container)) {
            throw new IllegalStateException("Phase 5 transfer barrel has no vanilla container");
        }
        container.clearContent();
        for (ContainerEntry entry : TRANSFER_CONTENTS) {
            container.setItem(entry.slot(), new ItemStack(entry.item(), entry.count()));
        }
        container.setChanged();
    }

    private static void resetStatefulWorkstations(ServerLevel level) {
        // Replacing these blocks also resets furnace burn/cook data and brewing progress.
        // Clearing Container slots alone leaves those block-entity timers behind, so a
        // repeated fixture invocation would not actually produce an idle workstation.
        FixtureArena.setBlock(level, FURNACE, Blocks.AIR.defaultBlockState());
        FixtureArena.setBlock(level, BREWING_STAND, Blocks.AIR.defaultBlockState());
    }

    private static void configureFurnace(ServerLevel level) {
        if (!(level.getBlockEntity(FURNACE) instanceof Container container)) {
            throw new IllegalStateException("Phase 5 furnace has no vanilla container");
        }
        container.clearContent();
        container.setChanged();
    }

    private static void configureBrewingStand(ServerLevel level) {
        if (!(level.getBlockEntity(BREWING_STAND) instanceof Container container)) {
            throw new IllegalStateException("Phase 5 brewing stand has no vanilla container");
        }
        container.clearContent();
        container.setChanged();
    }

    private static void configureCombinedSupplyChest(ServerLevel level) {
        if (!(level.getBlockEntity(COMBINED_SUPPLY_CHEST) instanceof Container container)) {
            throw new IllegalStateException("Combined wheat supply chest has no vanilla container");
        }
        container.clearContent();
        container.setItem(0, combinedSupplyHoe());
        container.setItem(8, combinedSupplySeeds());
        container.setChanged();
    }

    private static void configureGeneralizationChest(ServerLevel level) {
        configureContainer(
                level,
                GENERALIZATION_CHEST_WEST,
                GENERALIZATION_CHEST_WEST_CONTENTS,
                "west double chest");
        configureContainer(
                level,
                GENERALIZATION_CHEST_EAST,
                GENERALIZATION_CHEST_EAST_CONTENTS,
                "east double chest");
    }

    private static void configureContainer(
            ServerLevel level,
            BlockPos position,
            List<ContainerEntry> contents,
            String label) {
        if (!(level.getBlockEntity(position) instanceof Container container)) {
            throw new IllegalStateException("Phase 5 " + label + " has no vanilla container");
        }
        container.clearContent();
        for (ContainerEntry entry : contents) {
            container.setItem(entry.slot(), new ItemStack(entry.item(), entry.count()));
        }
        container.setChanged();
    }

    private static void configureInventory(ServerPlayer player, FixturePhase5Mode mode) {
        player.getInventory().clearContent();
        switch (mode) {
            case RECIPES -> {
                player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
                player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
            }
            case CRAFT -> player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
            case SMELT -> {
                player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON));
                player.getInventory().setItem(1, new ItemStack(Items.COAL));
            }
            case BREW -> {
                var water = BuiltInRegistries.POTION.get(
                                Identifier.fromNamespaceAndPath("minecraft", "water"))
                        .orElseThrow(() -> new IllegalStateException("water potion is unavailable"));
                for (int slot = 0; slot < 3; slot++) {
                    player.getInventory().setItem(
                            slot, PotionContents.createItemStack(Items.POTION, water));
                }
                player.getInventory().setItem(3, new ItemStack(Items.NETHER_WART));
                player.getInventory().setItem(4, new ItemStack(Items.BLAZE_POWDER));
            }
            case REDSTONE -> {
                player.getInventory().setItem(0, new ItemStack(Items.REDSTONE_LAMP));
                player.getInventory().setItem(1, new ItemStack(Items.LEVER));
            }
            case TRANSFER -> {
                // Empty inventory is the deterministic destination for container-to-player tests.
            }
            case CROP -> {
                player.getInventory().setItem(0, new ItemStack(Items.IRON_HOE));
                player.getInventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 8));
                player.getInventory().setItem(4, new ItemStack(Items.BREAD, 4));
            }
            case COMBINED_WHEAT -> {
                // The production-prompt E2E must obtain both supplies through the chest UI.
            }
            case TREE -> {
                player.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
                player.getInventory().setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
                player.getInventory().setItem(4, new ItemStack(Items.BREAD, 4));
            }
            case SLEEP, SURVEY -> player.getInventory().setItem(
                    0, new ItemStack(Items.BREAD, 4));
            case GENERALIZATION -> {
                player.getInventory().setItem(0, new ItemStack(Items.REDSTONE_LAMP, 3));
                player.getInventory().setItem(1, new ItemStack(Items.LEVER, 2));
                player.getInventory().setItem(2, new ItemStack(Items.GLASS, 2));
                player.getInventory().setItem(3, new ItemStack(Items.REDSTONE));
                player.getInventory().setItem(4, new ItemStack(Items.SMOOTH_STONE));
            }
            case IRON_FARM, RESET ->
                    throw new IllegalArgumentException(mode.wireName() + " has separate inventory setup");
        }
        player.getInventory().setSelectedSlot(mode.selectedSlot());
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static void resetKnownRecipes(FixtureSecurity.Context context) {
        context.player().resetRecipes(context.server().getRecipeManager().getRecipes());
    }

    private static void prepareSafeNight(ServerLevel level) {
        var clock = level.dimensionType().defaultClock()
                .orElseThrow(() -> new IllegalStateException("Overworld fixture clock is unavailable"));
        level.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.NIGHT);
        var weather = level.getWeatherData();
        weather.setClearWeatherTime(6000);
        weather.setRainTime(0);
        weather.setRaining(false);
        weather.setThunderTime(0);
        weather.setThundering(false);
    }

    private static void teleport(FixtureSecurity.Context context, Pose pose) {
        if (!context.player().teleportTo(
                context.level(), pose.x(), pose.y(), pose.z(), Set.<Relative>of(),
                pose.yaw(), pose.pitch(), false)) {
            throw new IllegalStateException("Phase 5 fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static Pose pose(FixturePhase5Mode mode) {
        return switch (mode) {
            case RECIPES, CRAFT -> new Pose(195.5D, 200.0D, 196.5D, 180.0F, 25.0F);
            case SMELT -> new Pose(196.5D, 200.0D, 196.5D, 180.0F, 25.0F);
            case BREW -> new Pose(197.5D, 200.0D, 196.5D, 180.0F, 25.0F);
            case REDSTONE -> new Pose(201.5D, 200.0D, 193.5D, 0.0F, 25.0F);
            case TRANSFER -> new Pose(199.5D, 200.0D, 196.5D, 180.0F, 25.0F);
            case CROP -> new Pose(194.5D, 200.0D, 201.5D, 180.0F, 28.0F);
            case COMBINED_WHEAT -> new Pose(199.5D, 200.0D, 197.0D, 180.0F, 18.0F);
            case TREE -> new Pose(201.5D, 200.0D, 198.5D, -90.0F, 8.0F);
            case SLEEP -> new Pose(193.5D, 200.0D, 204.5D, -90.0F, 18.0F);
            case SURVEY -> new Pose(200.5D, 200.0D, 204.5D, -90.0F, 12.0F);
            case GENERALIZATION -> new Pose(205.5D, 200.0D, 200.5D, 90.0F, 0.0F);
            case IRON_FARM, RESET ->
                    throw new IllegalArgumentException(mode.wireName() + " has a separate pose");
        };
    }

    private static ResourceKey<Recipe<?>> recipeKey(String path) {
        return ResourceKey.create(Registries.RECIPE,
                Identifier.fromNamespaceAndPath("minecraft", path));
    }

    private static String positions(List<BlockPos> positions) {
        return positions.stream().map(FixturePhase5Scenario::position)
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    record ContainerEntry(int slot, Item item, int count) {
        ContainerEntry {
            if (slot < 0 || slot >= 27 || item == Items.AIR || count < 1 || count > 64) {
                throw new IllegalArgumentException("invalid Phase 5 fixture container entry");
            }
        }
    }

    private record Pose(double x, double y, double z, float yaw, float pitch) {
    }
}
