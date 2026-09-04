package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Closed, mutation-free-after-T0 Vanilla fishing fixture. */
final class FixtureFishingScenario {
    static final BlockPos WORKSPACE_MIN = new BlockPos(193, 199, 194);
    static final BlockPos WORKSPACE_MAX = new BlockPos(205, 207, 206);
    static final BlockPos WATER_MIN = new BlockPos(194, 200, 195);
    static final BlockPos WATER_MAX = new BlockPos(204, 202, 205);
    static final BlockPos OPEN_WATER_CENTER = new BlockPos(199, 202, 200);
    static final BlockPos PLAYER_FEET = new BlockPos(199, 203, 194);
    static final int EXPECTED_SOURCE_WATER_CELLS = 11 * 11 * 3;
    static final int EXPECTED_OPEN_AIR_CELLS = 11 * 11 * 2;

    private FixtureFishingScenario() {
    }

    static void prepare(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        FixturePhase4RouteBlocker.stop();
        FixtureArena.requireInitialized(context.level());

        ServerLevel level = context.level();
        if (context.player().fishing != null) {
            context.player().fishing.discard();
        }
        if (context.player().fishing != null) {
            throw new IllegalStateException("fishing fixture could not retire the prior owned bobber");
        }
        discardWorkspaceEntities(level, context.player());
        for (int x = WORKSPACE_MIN.getX(); x <= WORKSPACE_MAX.getX(); x++) {
            for (int y = WORKSPACE_MIN.getY(); y <= WORKSPACE_MAX.getY(); y++) {
                for (int z = WORKSPACE_MIN.getZ(); z <= WORKSPACE_MAX.getZ(); z++) {
                    FixtureArena.setBlock(level, new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (var entry : layout().entrySet()) {
            FixtureArena.setBlock(level, entry.getKey(), entry.getValue());
        }

        configurePlayer(context.player());
        teleport(context);
        if (!isReady(level)) {
            throw new IllegalStateException("fishing fixture did not reach its closed baseline");
        }
        output.accept(Component.literal("phase5.mode=fishing"
                + " water=" + position(WATER_MIN) + ".." + position(WATER_MAX)
                + " open_water_center=" + position(OPEN_WATER_CENTER)
                + " stand=" + position(PLAYER_FEET)
                + " inventory=minecraft:fishing_rod@damage0,unenchanted"
                + " source_water_cells=" + EXPECTED_SOURCE_WATER_CELLS
                + " open_air_cells=" + EXPECTED_OPEN_AIR_CELLS
                + " loot_goal=one_vanilla_fishing_loot"
                + " fixture_tick_mutation=none"));
    }

    /** Complete non-air baseline; every other workspace cell is required to be air. */
    static Map<BlockPos, BlockState> layout() {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = WORKSPACE_MIN.getX(); x <= WORKSPACE_MAX.getX(); x++) {
            for (int z = WORKSPACE_MIN.getZ(); z <= WORKSPACE_MAX.getZ(); z++) {
                result.put(new BlockPos(x, 199, z), Blocks.SEA_LANTERN.defaultBlockState());
                boolean boundary = x == WORKSPACE_MIN.getX() || x == WORKSPACE_MAX.getX()
                        || z == WORKSPACE_MIN.getZ() || z == WORKSPACE_MAX.getZ();
                if (boundary) {
                    for (int y = 200; y <= 202; y++) {
                        result.put(new BlockPos(x, y, z),
                                Blocks.SMOOTH_STONE.defaultBlockState());
                    }
                }
            }
        }
        for (int x = WATER_MIN.getX(); x <= WATER_MAX.getX(); x++) {
            for (int y = WATER_MIN.getY(); y <= WATER_MAX.getY(); y++) {
                for (int z = WATER_MIN.getZ(); z <= WATER_MAX.getZ(); z++) {
                    result.put(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
                }
            }
        }
        return Map.copyOf(result);
    }

    static boolean isReady(ServerLevel level) {
        int sourceWater = 0;
        int openAir = 0;
        for (int x = WATER_MIN.getX(); x <= WATER_MAX.getX(); x++) {
            for (int z = WATER_MIN.getZ(); z <= WATER_MAX.getZ(); z++) {
                for (int y = WATER_MIN.getY(); y <= WATER_MAX.getY(); y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (level.getBlockState(position).is(Blocks.WATER)
                            && level.getFluidState(position).isSource()) {
                        sourceWater++;
                    }
                }
                for (int y = WATER_MAX.getY() + 1; y <= WATER_MAX.getY() + 2; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        openAir++;
                    }
                }
            }
        }
        return sourceWater == EXPECTED_SOURCE_WATER_CELLS
                && openAir == EXPECTED_OPEN_AIR_CELLS
                && ownedBobberCount(level) == 0
                && looseItemCount(level) == 0;
    }

    static void sendStatus(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        output.accept(Component.literal("fishing.ready=" + isReady(context.level())
                + " bobbers=" + ownedBobberCount(context.level())
                + " loose_items=" + looseItemCount(context.level())));
    }

    static void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        ServerPlayer player = context.player();
        ItemStack rod = player.getInventory().getItem(0);
        output.accept(Component.literal("TEST-ONLY ORACLE: bobbers="
                + ownedBobberCount(context.level())
                + " loose_items=" + looseItemCount(context.level())
                + " rod=" + BuiltInRegistries.ITEM.getKey(rod.getItem())
                + " damage=" + rod.getDamageValue()
                + " enchanted=" + rod.isEnchanted()
                + " player=" + player.getX() + ',' + player.getY() + ',' + player.getZ()
                + " health=" + player.getHealth()));
    }

    private static void configurePlayer(ServerPlayer player) {
        FixtureArena.resetPlayer(player);
        player.getInventory().clearContent();
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        if (rod.isEnchanted() || rod.getDamageValue() != 0) {
            throw new IllegalStateException("fishing fixture rod is not pristine");
        }
        player.getInventory().setItem(0, rod);
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
                Set.<Relative>of(), 0.0F, 10.0F, false)) {
            throw new IllegalStateException("fishing fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static void discardWorkspaceEntities(ServerLevel level, ServerPlayer player) {
        AABB bounds = AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX);
        for (Entity entity : level.getAllEntities()) {
            if (entity != player && entity.isAlive() && bounds.contains(entity.position())) {
                entity.discard();
            }
        }
    }

    private static int ownedBobberCount(ServerLevel level) {
        return level.getEntities(
                EntityTypes.FISHING_BOBBER,
                AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX),
                entity -> entity.isAlive()).size();
    }

    private static int looseItemCount(ServerLevel level) {
        return level.getEntities(
                EntityTypes.ITEM,
                AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX),
                entity -> entity.isAlive()).size();
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
