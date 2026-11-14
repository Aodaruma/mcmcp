package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fresh visible bodies affect this planning pass only, never remembered terrain or attack selection. */
final class ObservedEntityObstacles {
    private ObservedEntityObstacles() { }

    static KnownTraversabilitySnapshot filter(KnownTraversabilitySnapshot map, Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null || minecraft.level == null || player.isSpectator()) return map;
        var bodies = minecraft.level.getEntities(player, player.getBoundingBox().inflate(8), entity ->
                (entity instanceof Mob || entity instanceof Player) && entity.isAlive() && !entity.isSpectator()
                        && !entity.isPassengerOfSameVehicle(player) && player.hasLineOfSight(entity)).stream()
                .sorted(java.util.Comparator.comparingDouble(player::distanceToSqr)).limit(64)
                .map(net.minecraft.world.entity.Entity::getBoundingBox).toList();
        if (bodies.isEmpty()) return map;
        double width = player.getBbWidth() + 0.2, height = player.getBoundingBox().getYsize();
        return map.excludingEdges(edge -> {
            var a = edge.key().from(); var b = edge.key().to();
            var from = new Vec3(a.x() + 0.5, a.y(), a.z() + 0.5);
            var to = new Vec3(b.x() + 0.5, b.y(), b.z() + 0.5);
            for (var body : bodies) {
                if (blocks(body, from, to, width, height + (edge.locomotion().ballistic() ? 1.5 : 0))) return true;
            }
            return false;
        });
    }

    static boolean blocks(AABB obstacle, Vec3 from, Vec3 to, double width, double height) {
        double radius = width / 2;
        if (Math.min(from.x, to.x) - radius >= obstacle.maxX || Math.max(from.x, to.x) + radius <= obstacle.minX
                || Math.min(from.z, to.z) - radius >= obstacle.maxZ || Math.max(from.z, to.z) + radius <= obstacle.minZ
                || Math.min(from.y, to.y) >= obstacle.maxY || Math.max(from.y, to.y) + height <= obstacle.minY) return false;
        var start = new AABB(from.x - radius, from.y, from.z - radius, from.x + radius, from.y + height, from.z + radius);
        var end = start.move(to.subtract(from));
        // If already pushed into a body, allow a clear step away instead of deleting every exit.
        if (start.intersects(obstacle) && !end.intersects(obstacle)
                && to.subtract(from).dot(from.subtract(obstacle.getCenter())) >= 0
                && to.distanceToSqr(obstacle.getCenter()) > from.distanceToSqr(obstacle.getCenter())) return false;
        return true;
    }
}
