package dev.aod.mcmcp.adminbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Revalidated fail-closed boundary for every externally requested world mutation. */
final class AdminBridgeSecurity {
    private AdminBridgeSecurity() {
    }

    static Decision authorize(IntegratedServer server) {
        if (!Boolean.getBoolean(McmcpFixtureAdminMod.ENABLE_PROPERTY)) {
            return Decision.reject("bridge_disabled");
        }
        return authorizePrivateWorld(server);
    }

    /** Allows only crash-journal recovery; it does not enable the HTTP mutation endpoint. */
    static Decision authorizeRecovery(IntegratedServer server) {
        return authorizePrivateWorld(server);
    }

    private static Decision authorizePrivateWorld(IntegratedServer server) {
        if (server == null || Minecraft.getInstance().getSingleplayerServer() != server) {
            return Decision.reject("integrated_server_unavailable");
        }
        if (!server.isSameThread()) {
            return Decision.reject("server_thread_required");
        }
        if (!server.isSingleplayer() || server.isDedicatedServer() || server.isPublished()) {
            return Decision.reject("private_singleplayer_required");
        }
        if (server.getPlayerList().getPlayerCount() != 1) {
            return Decision.reject("single_owner_required");
        }
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            return Decision.reject("singleplayer_owner_required");
        }
        ServerLevel level = player.level();
        if (!Level.OVERWORLD.equals(level.dimension()) || server.overworld() != level) {
            return Decision.reject("overworld_owner_required");
        }
        return Decision.allow(new Context(server, level, player));
    }

    record Context(IntegratedServer server, ServerLevel level, ServerPlayer player) {
    }

    record Decision(Context context, String code) {
        static Decision allow(Context context) { return new Decision(context, null); }
        static Decision reject(String code) { return new Decision(null, code); }
        boolean allowed() { return context != null; }
    }
}
