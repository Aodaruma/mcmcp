package dev.aod.mcmcp.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Deterministic, bounded Phase 3 action fixtures inside the existing private arena. */
final class FixturePhase3Scenario {
    static final BlockPos ORIGIN = new BlockPos(198, 200, 194);
    static final BlockPos NAVIGATION_TARGET = ORIGIN.offset(7, 0, 0);
    static final BlockPos BREAK_TARGET = ORIGIN.offset(5, 1, 0);
    static final BlockPos PLACEMENT_DESTINATION = ORIGIN.offset(4, 0, 0);
    static final BlockPos PLACEMENT_SUPPORT = ORIGIN.offset(5, 0, 0);
    static final BlockPos LEVER = ORIGIN.offset(5, 0, 0);
    static final BlockPos COW = ORIGIN.offset(5, 0, 0);
    static final float LEVER_PITCH_DEGREES = 18.0F;
    static final float COW_PITCH_DEGREES = 6.0F;

    private static final String COW_TAG = "mcmcp_phase3_fixture";
    private static final int LANE_LENGTH = 9;

    private FixturePhase3Scenario() {
    }

    static void prepare(FixtureSecurity.Context context, Mode mode, Consumer<Component> output) {
        FixturePhase2Scenario.stop();
        FixturePhase4RouteBlocker.stop();
        FixtureArena.requireInitialized(context.level());
        discardFixtureCows(context.level());
        applyLayout(context.level(), mode);
        FixtureArena.resetPlayer(context.player());

        switch (mode) {
            case NAVIGATE -> teleport(context, ORIGIN.getX() + 0.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D,
                    -90.0F, 40.0F);
            case BREAK -> teleport(context, ORIGIN.getX() + 1.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D,
                    -90.0F, 0.0F);
            case PLACE -> {
                context.player().getInventory().setSelectedSlot(1);
                synchronizeInventory(context.player());
                teleport(context, ORIGIN.getX() + 1.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D,
                        -90.0F, 24.0F);
            }
            case LEVER -> {
                context.player().getInventory().setSelectedSlot(6);
                synchronizeInventory(context.player());
                teleport(context, ORIGIN.getX() + 1.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D,
                        -90.0F, LEVER_PITCH_DEGREES);
            }
            case COW -> {
                context.player().getInventory().setItem(0, new ItemStack(Items.BUCKET));
                context.player().getInventory().setSelectedSlot(0);
                synchronizeInventory(context.player());
                spawnCow(context.level());
                teleport(context, ORIGIN.getX() + 2.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D,
                        -90.0F, COW_PITCH_DEGREES);
            }
            case RESET -> {
                // resetPlayer already restored the Phase 1 deterministic position and inventory.
            }
        }

        output.accept(Component.literal("phase3.mode=" + mode.wireName
                + " lane=" + position(ORIGIN) + ".." + position(ORIGIN.offset(LANE_LENGTH - 1, 2, 0))
                + " target=" + position(targetFor(mode))));
    }

    static void stop(FixtureSecurity.Context context) {
        if (context != null) {
            discardFixtureCows(context.level());
        }
    }

    static Map<BlockPos, BlockState> layout(Mode mode) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x = 0; x < LANE_LENGTH; x++) {
            for (int z = -1; z <= 1; z++) {
                result.put(new BlockPos(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) {
                    result.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        switch (mode) {
            case BREAK -> result.put(relative(BREAK_TARGET), Blocks.STONE.defaultBlockState());
            case PLACE -> result.put(relative(PLACEMENT_SUPPORT), Blocks.SMOOTH_STONE.defaultBlockState());
            case LEVER -> result.put(relative(LEVER), leverOffState());
            case NAVIGATE, COW, RESET -> {
            }
        }
        return Map.copyOf(result);
    }

    static BlockState leverOffState() {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, false);
    }

    static void configureCow(Cow cow) {
        cow.setNoAi(true);
        cow.setPersistenceRequired();
        cow.addTag(COW_TAG);
    }

    private static void applyLayout(ServerLevel level, Mode mode) {
        layout(mode).forEach((offset, state) -> FixtureArena.setBlock(level, ORIGIN.offset(offset), state));
    }

    private static void spawnCow(ServerLevel level) {
        Cow cow = EntityTypes.COW.spawn(level, COW, EntitySpawnReason.COMMAND);
        if (cow == null) {
            throw new IllegalStateException("Phase 3 fixture could not spawn its cow");
        }
        configureCow(cow);
        cow.setPos(COW.getX() + 0.5D, COW.getY(), COW.getZ() + 0.5D);
        cow.setYRot(90.0F);
        cow.setYHeadRot(90.0F);
    }

    private static void discardFixtureCows(ServerLevel level) {
        AABB arena = AABB.encapsulatingFullBlocks(FixtureArena.MIN, FixtureArena.MAX);
        level.getEntities(EntityTypes.COW, arena, cow -> cow.entityTags().contains(COW_TAG))
                .forEach(Cow::discard);
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
            throw new IllegalStateException("Phase 3 fixture could not synchronize player position and rotation");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static void synchronizeInventory(ServerPlayer player) {
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static BlockPos targetFor(Mode mode) {
        return switch (mode) {
            case NAVIGATE -> NAVIGATION_TARGET;
            case BREAK -> BREAK_TARGET;
            case PLACE -> PLACEMENT_DESTINATION;
            case LEVER -> LEVER;
            case COW -> COW;
            case RESET -> ORIGIN;
        };
    }

    private static BlockPos relative(BlockPos absolute) {
        return absolute.subtract(ORIGIN);
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    enum Mode {
        NAVIGATE("navigate"),
        BREAK("break"),
        PLACE("place"),
        LEVER("lever"),
        COW("cow"),
        RESET("reset");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }
    }
}
