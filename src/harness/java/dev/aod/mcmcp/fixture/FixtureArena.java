package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

/** A deterministic, absolute-coordinate Phase 1 observation arena. */
final class FixtureArena {
    static final BlockPos MIN = new BlockPos(192, 199, 192);
    static final BlockPos MAX = new BlockPos(207, 207, 207);

    static final BlockPos INITIALIZED_MARKER = new BlockPos(192, 200, 192);
    static final BlockPos WHEAT_YOUNG = new BlockPos(193, 200, 198);
    static final BlockPos WHEAT_MATURE = new BlockPos(195, 200, 198);
    static final BlockPos STAIRS = new BlockPos(197, 200, 198);
    static final BlockPos DOOR_LOWER = new BlockPos(201, 200, 198);
    static final BlockPos DOOR_UPPER = DOOR_LOWER.above();
    static final BlockPos LAMP = new BlockPos(205, 200, 198);
    static final BlockPos LIGHT_SAMPLE = new BlockPos(205, 200, 200);
    static final BlockPos VISIBLE_TARGET = new BlockPos(199, 200, 200);
    static final BlockPos WATER_SAMPLE = new BlockPos(194, 200, 200);
    static final BlockPos SLAB_SAMPLE = new BlockPos(196, 200, 200);
    static final BlockPos GLASS_WINDOW = new BlockPos(203, 201, 200);
    static final BlockPos GLASS_TARGET = new BlockPos(203, 201, 201);
    static final BlockPos HIDDEN_APERTURE = new BlockPos(199, 201, 203);
    static final BlockPos HIDDEN_TARGET = new BlockPos(199, 201, 204);
    static final BlockPos PHASE2_TARGET = new BlockPos(194, 203, 194);

    private static final int MUTATION_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final int PAIRED_BLOCK_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private FixtureArena() {
    }

    static void load(FixtureSecurity.Context context) {
        FixtureTunnelScenario.restoreForReplacement(context);
        FixtureCombinedWheatScenario.rollbackForArenaReset(context);
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        FixturePhase4RouteBlocker.stop();
        ServerLevel level = context.level();

        // Every block write, including clearing, passes through the bounds-checked helper.
        for (int x = MIN.getX(); x <= MAX.getX(); x++) {
            for (int y = MIN.getY(); y <= MAX.getY(); y++) {
                for (int z = MIN.getZ(); z <= MAX.getZ(); z++) {
                    setBlock(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = MIN.getX(); x <= MAX.getX(); x++) {
            for (int z = MIN.getZ(); z <= MAX.getZ(); z++) {
                setBlock(level, new BlockPos(x, MIN.getY(), z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }

        setBlock(level, new BlockPos(MAX.getX(), 200, MIN.getZ()), Blocks.CONCRETE.yellow().defaultBlockState());
        setBlock(level, new BlockPos(MIN.getX(), 200, MAX.getZ()), Blocks.CONCRETE.yellow().defaultBlockState());
        setBlock(level, new BlockPos(MAX.getX(), 200, MAX.getZ()), Blocks.CONCRETE.yellow().defaultBlockState());

        // The water is embedded in the floor and keeps both farmland samples deterministic.
        setBlock(level, new BlockPos(192, 199, 198), Blocks.WATER.defaultBlockState());
        placeWheat(level, WHEAT_YOUNG, 0);
        placeWheat(level, WHEAT_MATURE, 7);

        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        setBlock(level, STAIRS, stairs);

        BlockState doorBase = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.POWERED, false);
        setBlock(level, DOOR_LOWER,
                doorBase.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                PAIRED_BLOCK_FLAGS);
        setBlock(level, DOOR_UPPER,
                doorBase.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                PAIRED_BLOCK_FLAGS);

        setBlock(level, LAMP.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        setBlock(level, LAMP, Blocks.REDSTONE_LAMP.defaultBlockState()
                .setValue(BlockStateProperties.LIT, true));

        // A one-cell glass basin keeps a level-0 water source stable while leaving its top
        // surface observable from the deterministic player position.
        setBlock(level, WATER_SAMPLE.relative(Direction.NORTH), Blocks.GLASS.defaultBlockState());
        setBlock(level, WATER_SAMPLE.relative(Direction.SOUTH), Blocks.GLASS.defaultBlockState());
        setBlock(level, WATER_SAMPLE.relative(Direction.WEST), Blocks.GLASS.defaultBlockState());
        setBlock(level, WATER_SAMPLE.relative(Direction.EAST), Blocks.GLASS.defaultBlockState());
        setBlock(level, WATER_SAMPLE, Blocks.WATER.defaultBlockState());
        setBlock(level, SLAB_SAMPLE, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, false));

        setBlock(level, VISIBLE_TARGET, Blocks.EMERALD_BLOCK.defaultBlockState());
        setBlock(level, GLASS_WINDOW, Blocks.GLASS.defaultBlockState());
        setBlock(level, GLASS_TARGET, Blocks.LAPIS_BLOCK.defaultBlockState());

        buildOcclusionBox(level, Blocks.GOLD_BLOCK.defaultBlockState(), true);
        resetPlayer(context.player());
        // Commit marker is deliberately last so interrupted setup is never treated as initialized.
        setBlock(level, INITIALIZED_MARKER, Blocks.CONCRETE.yellow().defaultBlockState());
    }

    static void exposeHidden(FixtureSecurity.Context context) {
        requireInitialized(context.level());
        setBlock(context.level(), HIDDEN_APERTURE, Blocks.AIR.defaultBlockState());
    }

    static void concealHidden(FixtureSecurity.Context context) {
        requireInitialized(context.level());
        setBlock(context.level(), HIDDEN_APERTURE, Blocks.STONE.defaultBlockState());
    }

    static void mutateHidden(FixtureSecurity.Context context) {
        requireInitialized(context.level());
        // Seal first, then mutate. An observer must retain last-known data until it sees the cell again.
        setBlock(context.level(), HIDDEN_APERTURE, Blocks.STONE.defaultBlockState());
        setBlock(context.level(), HIDDEN_TARGET, Blocks.DIAMOND_BLOCK.defaultBlockState());
    }

    static void resetInventoryAndStatus(FixtureSecurity.Context context) {
        FixtureTunnelScenario.restoreForReplacement(context);
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        requireInitialized(context.level());
        resetPlayer(context.player());
    }

    static void preparePhase2(FixtureSecurity.Context context, boolean slowTarget) {
        FixtureTunnelScenario.restoreForReplacement(context);
        requireInitialized(context.level());
        resetPlayer(context.player());
        if (!context.player().teleportTo(
                context.level(), 194.5D, 200.0D, 194.5D, Set.<Relative>of(), 0.0F, -90.0F, false)) {
            throw new IllegalStateException("Phase 2 fixture could not synchronize player position and rotation");
        }

        for (BlockPos light : new BlockPos[] {
                new BlockPos(193, 199, 193), new BlockPos(195, 199, 193),
                new BlockPos(193, 199, 195), new BlockPos(195, 199, 195)}) {
            setBlock(context.level(), light, Blocks.SEA_LANTERN.defaultBlockState());
        }
        setBlock(context.level(), PHASE2_TARGET,
                slowTarget ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.STONE.defaultBlockState());
    }

    static boolean restorePhase2Stone(FixtureSecurity.Context context) {
        requireInitialized(context.level());
        if (!context.level().getBlockState(PHASE2_TARGET).isAir()) {
            return false;
        }
        setBlock(context.level(), PHASE2_TARGET, Blocks.STONE.defaultBlockState());
        return true;
    }

    static void sendStatus(FixtureSecurity.Context context, Consumer<Component> output) {
        ServerLevel level = context.level();
        ServerPlayer player = context.player();
        output.accept(Component.literal("fixture.initialized=" + isInitialized(level)
                + " bounds=" + pos(MIN) + ".." + pos(MAX)));
        output.accept(Component.literal(blockLine(level, "young_wheat", WHEAT_YOUNG)));
        output.accept(Component.literal(blockLine(level, "mature_wheat", WHEAT_MATURE)));
        output.accept(Component.literal(blockLine(level, "stairs", STAIRS)));
        output.accept(Component.literal(blockLine(level, "door_lower", DOOR_LOWER)));
        output.accept(Component.literal(blockLine(level, "door_upper", DOOR_UPPER)));
        output.accept(Component.literal(blockLine(level, "lamp", LAMP)
                + " block_light@" + pos(LIGHT_SAMPLE) + "="
                + level.getBrightness(LightLayer.BLOCK, LIGHT_SAMPLE)));
        var waterFluid = level.getFluidState(WATER_SAMPLE);
        output.accept(Component.literal(blockLine(level, "water_sample", WATER_SAMPLE)
                + " fluid_source=" + waterFluid.isSource()
                + " fluid_amount=" + waterFluid.getAmount()));
        output.accept(Component.literal(blockLine(level, "slab_sample", SLAB_SAMPLE)));
        output.accept(Component.literal(blockLine(level, "visible_target", VISIBLE_TARGET)));
        output.accept(Component.literal(blockLine(level, "glass_window", GLASS_WINDOW)));
        output.accept(Component.literal(blockLine(level, "phase2_target", PHASE2_TARGET)));
        output.accept(Component.literal("hidden_target=" + pos(HIDDEN_TARGET)
                + " aperture=" + pos(HIDDEN_APERTURE)
                + " aperture_open=" + level.getBlockState(HIDDEN_APERTURE).isAir()
                + " (ground truth intentionally omitted; use oracle only for manual verification)"));
        output.accept(Component.literal("player pos=" + player.getX() + ',' + player.getY() + ',' + player.getZ()
                + " yaw=" + player.getYRot() + " pitch=" + player.getXRot()
                + " health=" + player.getHealth()
                + " food=" + player.getFoodData().getFoodLevel()
                + " saturation=" + player.getFoodData().getSaturationLevel()
                + " xp_level=" + player.experienceLevel
                + " xp_total=" + player.totalExperience
                + " selected_slot=" + player.getInventory().getSelectedSlot()));
        output.accept(Component.literal("inventory " + inventoryLine(player)));
    }

    static void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        requireInitialized(context.level());
        output.accept(Component.literal("TEST-ONLY ORACLE: "
                + blockLine(context.level(), "hidden_target", HIDDEN_TARGET)));
    }

    private static void placeWheat(ServerLevel level, BlockPos cropPos, int age) {
        setBlock(level, cropPos.below(), Blocks.FARMLAND.defaultBlockState()
                .setValue(BlockStateProperties.MOISTURE, 7));
        setBlock(level, cropPos, Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, age));
    }

    private static void buildOcclusionBox(ServerLevel level, BlockState target, boolean sealed) {
        for (int x = 198; x <= 200; x++) {
            for (int y = 200; y <= 202; y++) {
                for (int z = 203; z <= 205; z++) {
                    boolean shell = x == 198 || x == 200 || y == 200 || y == 202 || z == 203 || z == 205;
                    if (shell) {
                        setBlock(level, new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        setBlock(level, HIDDEN_TARGET, target);
        if (!sealed) {
            setBlock(level, HIDDEN_APERTURE, Blocks.AIR.defaultBlockState());
        }
    }

    static void resetPlayer(ServerPlayer player) {
        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(199.5D, 200.0D, 194.5D);
        clearNearbyHostiles(player);
        player.setYRot(0.0F);
        player.setXRot(5.0F);
        player.setYHeadRot(0.0F);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.resetFallDistance();
        player.setRemainingFireTicks(0);
        player.setAirSupply(player.getMaxAirSupply());
        player.removeAllEffects();
        player.setHealth(16.0F);
        player.setAbsorptionAmount(0.0F);

        FoodData food = player.getFoodData();
        food.setFoodLevel(17);
        food.setSaturation(3.5F);
        player.setExperienceLevels(6);
        player.setExperiencePoints(3);
        player.totalExperience = 75;

        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 32));
        player.getInventory().setItem(2, new ItemStack(Items.WHEAT_SEEDS, 7));
        player.getInventory().setItem(3, new ItemStack(Items.TORCH, 16));
        player.getInventory().setItem(4, new ItemStack(Items.BREAD, 5));
        player.getInventory().setItem(5, new ItemStack(Items.OAK_SAPLING, 4));
        player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                new ItemStack(Items.SHIELD));
        player.getInventory().setSelectedSlot(0);
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static void clearNearbyHostiles(ServerPlayer player) {
        var nearbyHostiles = new ArrayList<Entity>();
        for (Entity entity : player.level().getAllEntities()) {
            if (entity instanceof Enemy && entity.distanceToSqr(player) <= 64.0D * 64.0D) {
                nearbyHostiles.add(entity);
            }
        }
        nearbyHostiles.forEach(Entity::discard);
    }

    private static String inventoryLine(ServerPlayer player) {
        StringBuilder result = new StringBuilder();
        for (int slot = 0; slot <= 5; slot++) {
            if (slot > 0) {
                result.append(' ');
            }
            ItemStack stack = player.getInventory().getItem(slot);
            result.append(slot).append('=')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append('x').append(stack.getCount());
        }
        ItemStack offhand = player.getInventory().getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND);
        result.append(" offhand=")
                .append(BuiltInRegistries.ITEM.getKey(offhand.getItem()))
                .append('x').append(offhand.getCount());
        return result.toString();
    }

    private static boolean isInitialized(ServerLevel level) {
        return level.getBlockState(INITIALIZED_MARKER).is(Blocks.CONCRETE.yellow());
    }

    static void requireInitialized(ServerLevel level) {
        if (!isInitialized(level)) {
            throw new IllegalStateException("run /mcmcp_fixture load first");
        }
    }

    private static String blockLine(ServerLevel level, String label, BlockPos blockPos) {
        BlockState state = level.getBlockState(blockPos);
        return label + '@' + pos(blockPos) + '=' + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + state.getValues();
    }

    private static String pos(BlockPos blockPos) {
        return blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }

    static void setBlock(ServerLevel level, BlockPos blockPos, BlockState state) {
        setBlock(level, blockPos, state, MUTATION_FLAGS);
    }

    /** Writes replacement-fixture T0 without letting removed fluids refill later cells mid-pass. */
    static void setBlockWithoutNeighborUpdates(
            ServerLevel level, BlockPos blockPos, BlockState state) {
        setBlock(level, blockPos, state, PAIRED_BLOCK_FLAGS);
    }

    static void setPairedBlocks(
            ServerLevel level,
            BlockPos firstPosition,
            BlockState firstState,
            BlockPos secondPosition,
            BlockState secondState) {
        setBlock(level, firstPosition, firstState, PAIRED_BLOCK_FLAGS);
        setBlock(level, secondPosition, secondState, PAIRED_BLOCK_FLAGS);
    }

    private static void setBlock(ServerLevel level, BlockPos blockPos, BlockState state, int flags) {
        if (blockPos.getX() < MIN.getX() || blockPos.getX() > MAX.getX()
                || blockPos.getY() < MIN.getY() || blockPos.getY() > MAX.getY()
                || blockPos.getZ() < MIN.getZ() || blockPos.getZ() > MAX.getZ()) {
            throw new IllegalArgumentException("fixture attempted an out-of-bounds write at " + blockPos);
        }
        // Level#setBlock legitimately returns false for an idempotent write. Treat it as an
        // error only when the requested state is still absent after the call.
        if (!level.setBlock(blockPos, state, flags) && !level.getBlockState(blockPos).equals(state)) {
            throw new IllegalStateException("fixture could not set " + blockPos + " to " + state);
        }
    }
}
