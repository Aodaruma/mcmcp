package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.player.LocalPlayer;
import java.util.UUID;

/** 所有menu操作の開始時点に固定するplayer/session/位置/healthの基準。 */
record PlayerBaseline(
        UUID worldSessionId,
        String dimension,
        double x,
        double y,
        double z,
        float health) {
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    static PlayerBaseline capture(
            LocalPlayer player, WorldSessionTracker.Snapshot session) {
        return new PlayerBaseline(
                session.worldSessionId(), session.dimension(),
                player.getX(), player.getY(), player.getZ(), player.getHealth());
    }

    boolean sameSession(WorldSessionTracker.Snapshot session) {
        return worldSessionId.equals(session.worldSessionId())
                && dimension.equals(session.dimension());
    }

    boolean matches(LocalPlayer player) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz <= MAX_POSITION_DRIFT_SQUARED;
    }
}
