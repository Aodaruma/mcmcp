package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bounded real-fluid cobblestone-generator fixture.
 *
 * <p>The harness writes the baseline only during T0 preparation. Once prepared there is no
 * listener, lease, or fixture tick mutation: breaking {@link #GENERATION_CELL} lets ordinary
 * Vanilla water/lava updates create its replacement.</p>
 */
final class FixtureCobblestoneGeneratorScenario {
    static final BlockPos WORKSPACE_MIN = new BlockPos(196, 199, 197);
    static final BlockPos WORKSPACE_MAX = new BlockPos(201, 202, 201);

    static final BlockPos WATER_SOURCE = new BlockPos(197, 201, 200);
    static final BlockPos WATER_DROP = new BlockPos(198, 200, 200);
    static final BlockPos WATER_CHANNEL = WATER_DROP.above();
    static final BlockPos GENERATION_CELL = new BlockPos(199, 201, 200);
    static final BlockPos LAVA_SOURCE = new BlockPos(200, 201, 200);
    // Stand directly beside the generated block so every Vanilla drop spawn offset intersects
    // the player's pickup-expanded bounding box instead of remaining on top of the replacement.
    static final BlockPos PLAYER_FEET = new BlockPos(199, 201, 199);

    static final List<BlockPos> SOURCE_CELLS = List.of(WATER_SOURCE, LAVA_SOURCE);
    static final List<BlockPos> PROTECTED_CELLS = protectedCells();

    private FixtureCobblestoneGeneratorScenario() {
    }

    static void prepare(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        FixturePhase4RouteBlocker.stop();
        FixtureArena.requireInitialized(context.level());

        ServerLevel level = context.level();
        discardItemEntities(level);
        clearWorkspace(level);
        for (var entry : layout().entrySet()) {
            FixtureArena.setBlock(level, entry.getKey(), entry.getValue());
        }
        configurePlayer(context.player());
        teleport(context);

        output.accept(Component.literal("phase5.mode=cobblestone_generator"
                + " water_source=" + position(WATER_SOURCE)
                + " water_drop=" + position(WATER_DROP)
                + " generation_cell=" + position(GENERATION_CELL)
                + " lava_source=" + position(LAVA_SOURCE)
                + " stand=" + position(PLAYER_FEET)
                + " inventory=minecraft:iron_pickaxe@damage0"
                + " cobblestone_goal=8 selected_slot=0 fixture_tick_mutation=none"));
    }

    static Map<BlockPos, BlockState> layout() {
        var result = new LinkedHashMap<BlockPos, BlockState>();

        // Bedrock makes the two source supports, collection cell, and downward water pocket
        // non-mineable and keeps every possible fluid route inside this test-only volume.
        for (int x = 196; x <= 201; x++) {
            for (int z = 197; z <= 201; z++) {
                result.put(new BlockPos(x, 199, z), Blocks.BEDROCK.defaultBlockState());
            }
        }
        for (int x = 196; x <= 201; x++) {
            for (int z = 197; z <= 201; z++) {
                result.put(new BlockPos(x, 200, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        result.put(WATER_DROP, Blocks.AIR.defaultBlockState());

        // Glass blocks every horizontal fluid exit. The north face of only the generated block
        // remains open so a stationary Survival player can mine it normally.
        for (int x = 197; x <= 200; x++) {
            if (x != GENERATION_CELL.getX()) {
                result.put(new BlockPos(x, 201, 199), Blocks.GLASS.defaultBlockState());
            }
            result.put(new BlockPos(x, 201, 201), Blocks.GLASS.defaultBlockState());
        }
        result.put(new BlockPos(196, 201, 200), Blocks.GLASS.defaultBlockState());
        result.put(new BlockPos(201, 201, 200), Blocks.GLASS.defaultBlockState());
        result.put(WATER_CHANNEL, Blocks.AIR.defaultBlockState());
        result.put(GENERATION_CELL, Blocks.COBBLESTONE.defaultBlockState());
        result.put(WATER_SOURCE, Blocks.WATER.defaultBlockState());
        result.put(LAVA_SOURCE, Blocks.LAVA.defaultBlockState());
        return Map.copyOf(result);
    }

    static void sendStatus(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        output.accept(Component.literal("cobblestone_generator.ready=" + isReady(context.level())
                + " generation=" + block(context.level(), GENERATION_CELL)
                + " water_source=" + source(context.level(), WATER_SOURCE)
                + " lava_source=" + source(context.level(), LAVA_SOURCE)
                + " loose_items=" + looseItemCount(context.level())));
    }

    static void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        ServerPlayer player = context.player();
        ItemStack pickaxe = player.getInventory().getItem(0);
        output.accept(Component.literal("TEST-ONLY ORACLE: generation="
                + block(context.level(), GENERATION_CELL)
                + " water_source=" + source(context.level(), WATER_SOURCE)
                + " lava_source=" + source(context.level(), LAVA_SOURCE)
                + " cobblestone=" + inventoryCount(player, Items.COBBLESTONE)
                + " pickaxe=" + BuiltInRegistries.ITEM.getKey(pickaxe.getItem())
                + " damage=" + pickaxe.getDamageValue()
                + " enchanted=" + pickaxe.isEnchanted()
                + " loose_items=" + looseItemCount(context.level())
                + " player=" + player.getX() + ',' + player.getY() + ',' + player.getZ()
                + " health=" + player.getHealth()));
    }

    static boolean isReady(ServerLevel level) {
        return level.getBlockState(GENERATION_CELL).is(Blocks.COBBLESTONE)
                && level.getFluidState(WATER_SOURCE).isSource()
                && level.getBlockState(WATER_SOURCE).is(Blocks.WATER)
                && level.getFluidState(LAVA_SOURCE).isSource()
                && level.getBlockState(LAVA_SOURCE).is(Blocks.LAVA)
                && !level.getFluidState(WATER_CHANNEL).isEmpty();
    }

    private static void clearWorkspace(ServerLevel level) {
        for (int x = WORKSPACE_MIN.getX(); x <= WORKSPACE_MAX.getX(); x++) {
            for (int y = WORKSPACE_MIN.getY(); y <= WORKSPACE_MAX.getY(); y++) {
                for (int z = WORKSPACE_MIN.getZ(); z <= WORKSPACE_MAX.getZ(); z++) {
                    FixtureArena.setBlock(level, new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    static void configurePlayer(ServerPlayer player) {
        FixtureArena.resetPlayer(player);
        player.getInventory().clearContent();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        if (pickaxe.isEnchanted() || pickaxe.getDamageValue() != 0) {
            throw new IllegalStateException("cobblestone fixture pickaxe is not pristine");
        }
        player.getInventory().setItem(0, pickaxe);
        player.getInventory().setSelectedSlot(0);
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static void teleport(FixtureSecurity.Context context) {
        if (!context.player().teleportTo(
                context.level(),
                PLAYER_FEET.getX() + 0.5D,
                PLAYER_FEET.getY(),
                PLAYER_FEET.getZ() + 0.5D,
                Set.<Relative>of(), 0.0F, 8.0F, false)) {
            throw new IllegalStateException(
                    "cobblestone fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static int discardItemEntities(ServerLevel level) {
        var bounds = AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX);
        var items = level.getEntities(EntityTypes.ITEM, bounds, entity -> entity.isAlive());
        items.forEach(entity -> entity.discard());
        return items.size();
    }

    private static int looseItemCount(ServerLevel level) {
        return level.getEntities(
                EntityTypes.ITEM,
                AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX),
                entity -> entity.isAlive()).size();
    }

    private static int inventoryCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory()) {
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static List<BlockPos> protectedCells() {
        return layout().keySet().stream()
                .filter(position -> !position.equals(GENERATION_CELL)
                        && !position.equals(WATER_CHANNEL))
                .sorted(java.util.Comparator.comparingInt((BlockPos position) -> position.getY())
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
    }

    private static String block(ServerLevel level, BlockPos position) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString();
    }

    private static String source(ServerLevel level, BlockPos position) {
        return block(level, position) + "@source=" + level.getFluidState(position).isSource();
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
