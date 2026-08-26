package dev.aodaruma.craftagent.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;

import java.util.Set;
import java.util.function.Consumer;

/** Creative-only pose for capturing the existing deterministic gallery. */
final class FixtureCreativeCaptureScenario {
    static final BlockPos CAPTURE_MIN = new BlockPos(192, 199, 192);
    static final BlockPos CAPTURE_MAX = new BlockPos(207, 202, 207);
    static final BlockPos PLAYER_POSE = new BlockPos(151, 200, 199);

    private FixtureCreativeCaptureScenario() {
    }

    static void prepare(FixtureSecurity.Context context, Consumer<Component> output) {
        FixtureArena.requireInitialized(context.level());
        context.server().setWorldAllowCommands(true);
        context.server().getPlayerList().sendPlayerPermissionLevel(context.player());
        context.player().setGameMode(GameType.CREATIVE);
        if (!context.server().getWorldData().isAllowCommands()
                || !context.player().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            throw new IllegalStateException("Creative capture fixture could not enable the local cheats gate");
        }
        if (!context.player().teleportTo(
                context.level(),
                PLAYER_POSE.getX() + 0.5D,
                PLAYER_POSE.getY(),
                PLAYER_POSE.getZ() + 0.5D,
                Set.<Relative>of(), 0.0F, 10.0F, false)) {
            throw new IllegalStateException("Creative capture fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
        output.accept(Component.literal("creative_capture cheats=true player=" + position(PLAYER_POSE)
                + " region=" + position(CAPTURE_MIN) + ".." + position(CAPTURE_MAX)));
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
