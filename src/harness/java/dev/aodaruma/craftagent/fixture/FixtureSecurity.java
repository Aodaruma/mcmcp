package dev.aodaruma.craftagent.fixture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The hard safety boundary around all fixture mutations.
 *
 * <p>This class intentionally lives in the harness source set and references the client-only
 * integrated server. Production code must never depend on it.</p>
 */
final class FixtureSecurity {
    private FixtureSecurity() {
    }

    static Decision authorize(CommandSourceStack source) {
        if (!Boolean.getBoolean(CraftAgentTestFixtureMod.ENABLE_PROPERTY)) {
            return Decision.reject("-D" + CraftAgentTestFixtureMod.ENABLE_PROPERTY + "=true is required");
        }

        if (!(source.getServer() instanceof IntegratedServer server)) {
            return Decision.reject("only a client-hosted integrated server is allowed");
        }
        if (Minecraft.getInstance().getSingleplayerServer() != server) {
            return Decision.reject("the command is not running on this client's integrated server");
        }
        if (!server.isSameThread()) {
            return Decision.reject("world mutation must run on the integrated-server thread");
        }
        if (!server.isSingleplayer() || server.isDedicatedServer()) {
            return Decision.reject("dedicated and remote multiplayer servers are forbidden");
        }
        if (server.isPublished()) {
            return Decision.reject("the world is open to LAN; close it before using the fixture");
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return Decision.reject("the command must be issued by the singleplayer owner");
        }
        if (server.getPlayerList().getPlayerCount() != 1) {
            return Decision.reject("exactly one connected player is required");
        }
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            return Decision.reject("only the singleplayer owner may use the fixture");
        }

        ServerLevel level = player.level();
        if (source.getLevel() != level || !Level.OVERWORLD.equals(level.dimension())) {
            return Decision.reject("run the fixture as the owner in the Overworld");
        }

        return Decision.allow(new Context(server, level, player));
    }

    record Context(IntegratedServer server, ServerLevel level, ServerPlayer player) {
    }

    record Decision(Context context, String rejection) {
        static Decision allow(Context context) {
            return new Decision(context, null);
        }

        static Decision reject(String rejection) {
            return new Decision(null, rejection);
        }

        boolean allowed() {
            return context != null;
        }
    }
}
