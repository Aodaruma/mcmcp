package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Closed, stationary kill-zone consent fixture with no gameplay mutation after T0. */
final class FixtureKillZoneScenario {
    static final BlockPos WORKSPACE_MIN = new BlockPos(196, 199, 194);
    static final BlockPos WORKSPACE_MAX = new BlockPos(202, 203, 202);
    static final BlockPos PLAYER_FEET = new BlockPos(199, 200, 197);
    static final BlockPos TARGET_FEET = new BlockPos(199, 200, 199);
    static final BlockPos FRONT_LOWER = PLAYER_FEET.south();
    static final BlockPos FRONT_UPPER = FRONT_LOWER.above();
    static final String TARGET_TAG = "mcmcp_fixture_kill_zone";
    static final float EXPECTED_TARGET_HEALTH = 20.0F;

    private FixtureKillZoneScenario() {
    }

    static void prepare(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        FixturePhase4RouteBlocker.stop();
        FixtureArena.requireInitialized(context.level());

        ServerLevel level = context.level();
        clearWorkspace(level);
        layout().forEach((position, state) -> FixtureArena.setBlock(level, position, state));
        configurePlayer(context.player());
        teleport(context);
        // This is the exact runtime hard-hazard volume at T0. Clear every non-player entity,
        // including projectiles outside the smaller block workspace, before adding the sole target.
        discardSafetyVolumeEntities(level, context.player());
        ArmorStand stand = spawnTarget(level);

        if (!isReady(context, stand)) {
            stand.discard();
            throw new IllegalStateException("kill-zone fixture did not reach its closed baseline");
        }
        output.accept(Component.literal("phase5.mode=kill_zone"
                + " player_cell=" + position(PLAYER_FEET)
                + " front_lower=" + position(FRONT_LOWER) + ":minecraft:smooth_stone"
                + " front_upper=" + position(FRONT_UPPER)
                + ":minecraft:smooth_stone_slab[type=top]"
                + " target=minecraft:armor_stand@" + position(TARGET_FEET)
                + " target_bounds=minecraft:overworld/199,200,199..200,202,200"
                + " inventory=minecraft:stone_sword@damage0,unenchanted"
                + " selected_slot=0 crosshair=fixed fixture_tick_mutation=none"));
    }

    /** Complete non-air baseline; every unlisted workspace cell is required to be air. */
    static Map<BlockPos, BlockState> layout() {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = WORKSPACE_MIN.getX(); x <= WORKSPACE_MAX.getX(); x++) {
            for (int z = WORKSPACE_MIN.getZ(); z <= WORKSPACE_MAX.getZ(); z++) {
                result.put(new BlockPos(x, WORKSPACE_MIN.getY(), z),
                        Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }

        // The player occupies exactly one cardinally sealed cell. South is the attack face:
        // a full lower block plus a top slab leaves only the lower 0.5 of the upper cell open.
        result.put(PLAYER_FEET.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        result.put(PLAYER_FEET.above(2), Blocks.SMOOTH_STONE.defaultBlockState());
        result.put(FRONT_LOWER, Blocks.SMOOTH_STONE.defaultBlockState());
        result.put(FRONT_UPPER, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, false));
        for (BlockPos neighbor : new BlockPos[] {
                PLAYER_FEET.north(), PLAYER_FEET.west(), PLAYER_FEET.east()}) {
            result.put(neighbor, Blocks.SMOOTH_STONE.defaultBlockState());
            result.put(neighbor.above(), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        result.put(TARGET_FEET.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        return Map.copyOf(result);
    }

    static void sendStatus(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        output.accept(Component.literal("kill_zone.ready=" + isReady(context, onlyTarget(context.level()))
                + " targets=" + targetCount(context.level())
                + " other_safety_volume_entities="
                + otherSafetyVolumeEntityCount(context.level(), context.player())
                + " front_lower=" + block(context.level(), FRONT_LOWER)
                + " front_upper=" + block(context.level(), FRONT_UPPER)));
    }

    static void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        ArmorStand target = onlyTarget(context.level());
        ItemStack sword = context.player().getInventory().getItem(0);
        output.accept(Component.literal("TEST-ONLY ORACLE: targets=" + targetCount(context.level())
                + " target_health=" + (target == null ? "absent" : target.getHealth())
                + " target_alive=" + (target != null && target.isAlive())
                + " target_last_hit=" + (target == null ? "absent" : target.lastHit)
                + " sword=" + BuiltInRegistries.ITEM.getKey(sword.getItem())
                + " damage=" + sword.getDamageValue()
                + " enchanted=" + sword.isEnchanted()
                + " player=" + context.player().getX() + ',' + context.player().getY() + ','
                + context.player().getZ() + " health=" + context.player().getHealth()));
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

    private static void configurePlayer(ServerPlayer player) {
        FixtureArena.resetPlayer(player);
        player.getInventory().clearContent();
        ItemStack sword = new ItemStack(Items.STONE_SWORD);
        if (sword.isEnchanted() || sword.getDamageValue() != 0) {
            throw new IllegalStateException("kill-zone fixture sword is not pristine");
        }
        player.getInventory().setItem(0, sword);
        player.getInventory().setSelectedSlot(0);
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static ArmorStand spawnTarget(ServerLevel level) {
        ArmorStand stand = EntityTypes.ARMOR_STAND.spawn(
                level, TARGET_FEET, EntitySpawnReason.COMMAND);
        if (stand == null) {
            throw new IllegalStateException("kill-zone fixture could not spawn its armor stand");
        }
        stand.addTag(TARGET_TAG);
        stand.setPos(TARGET_FEET.getX() + 0.5D, TARGET_FEET.getY(),
                TARGET_FEET.getZ() + 0.5D);
        stand.setYRot(180.0F);
        stand.setYHeadRot(180.0F);
        return stand;
    }

    private static void teleport(FixtureSecurity.Context context) {
        if (!context.player().teleportTo(
                context.level(),
                PLAYER_FEET.getX() + 0.5D,
                PLAYER_FEET.getY(),
                PLAYER_FEET.getZ() + 0.5D,
                Set.<Relative>of(), 0.0F, 18.0F, false)) {
            throw new IllegalStateException("kill-zone fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static boolean isReady(FixtureSecurity.Context context, ArmorStand stand) {
        ServerPlayer player = context.player();
        ItemStack sword = player.getInventory().getItem(0);
        BlockState upper = context.level().getBlockState(FRONT_UPPER);
        return targetCount(context.level()) == 1
                && stand != null && stand.isAlive()
                && Math.abs(stand.getHealth() - EXPECTED_TARGET_HEALTH) < 0.0001F
                && otherSafetyVolumeEntityCount(context.level(), player) == 0
                && context.level().getBlockState(FRONT_LOWER).is(Blocks.SMOOTH_STONE)
                && upper.is(Blocks.SMOOTH_STONE_SLAB)
                && upper.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP
                && !upper.getValue(BlockStateProperties.WATERLOGGED)
                && sword.is(Items.STONE_SWORD) && sword.getCount() == 1
                && sword.getDamageValue() == 0 && !sword.isEnchanted()
                && player.getInventory().getSelectedSlot() == 0
                && inventoryCount(player) == 1
                && Math.abs(player.getX() - (PLAYER_FEET.getX() + 0.5D)) < 0.0001D
                && Math.abs(player.getY() - PLAYER_FEET.getY()) < 0.0001D
                && Math.abs(player.getZ() - (PLAYER_FEET.getZ() + 0.5D)) < 0.0001D
                && Math.abs(player.getYRot()) < 0.0001F
                && Math.abs(player.getXRot() - 18.0F) < 0.0001F;
    }

    private static int inventoryCount(ServerPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory()) {
            count += stack.getCount();
        }
        return count;
    }

    private static void discardSafetyVolumeEntities(ServerLevel level, ServerPlayer player) {
        level.getEntities(player, player.getBoundingBox().inflate(8.0D), Entity::isAlive)
                .forEach(Entity::discard);
    }

    private static int otherSafetyVolumeEntityCount(ServerLevel level, ServerPlayer player) {
        ArmorStand target = onlyTarget(level);
        return level.getEntities(
                player,
                player.getBoundingBox().inflate(8.0D),
                entity -> entity.isAlive() && entity != target)
                .size();
    }

    private static ArmorStand onlyTarget(ServerLevel level) {
        var targets = level.getEntities(
                EntityTypes.ARMOR_STAND,
                AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX),
                stand -> stand.isAlive() && stand.entityTags().contains(TARGET_TAG));
        return targets.size() == 1 ? targets.getFirst() : null;
    }

    private static int targetCount(ServerLevel level) {
        return level.getEntities(
                EntityTypes.ARMOR_STAND,
                AABB.encapsulatingFullBlocks(WORKSPACE_MIN, WORKSPACE_MAX),
                stand -> stand.isAlive() && stand.entityTags().contains(TARGET_TAG)).size();
    }

    private static String block(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + state.getValues().toString();
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
