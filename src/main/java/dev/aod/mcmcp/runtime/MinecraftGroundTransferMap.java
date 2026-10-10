package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.*;
import dev.aod.mcmcp.agent.safety.GroundTraversal;
import dev.aod.mcmcp.agent.safety.Locomotion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import java.util.*;

/** Bounded local collision queries, kept out of the shared observation map. */
final class MinecraftGroundTransferMap {
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    static KnownTraversabilitySnapshot augment(KnownTraversabilitySnapshot map, LocalPlayer player, double allowance) {
        if (!player.onGround() || player.isInWater() || player.isPassenger() || player.isCrouching()
                || Locomotion.aerialMode(player).aerial()) return map;
        var current = new NavCell(map.dimension(), Mth.floor(player.getX()), Mth.floor(player.getY() + 1.0E-6), Mth.floor(player.getZ()));
        var sources = new TreeSet<NavCell>(Comparator.comparingDouble((NavCell cell) -> cell.distanceTo(current)).thenComparing(cell -> cell));
        sources.add(current);
        for (var edge : map.edges().values()) {
            if (edge.locomotion() == Locomotion.GROUND && edge.traversable()) {
                if (edge.key().from().distanceTo(current) <= 4) sources.add(edge.key().from());
                if (edge.key().to().distanceTo(current) <= 4) sources.add(edge.key().to());
            }
        }
        var additions = new ArrayList<TraversabilityEdge>();
        var damage = new HashMap<TraversabilityEdge.Key, Double>();
        int count = 0;
        for (var source : sources) {
            if (++count > 32) break;
            var body = player.getBoundingBox();
            var start = body.move(source.x() + 0.5 - player.getX(), source.y() - body.minY, source.z() + 0.5 - player.getZ());
            for (var direction : DIRECTIONS) for (var mode : List.of(Locomotion.GAP_JUMP, Locomotion.DROP)) {
                int length = mode == Locomotion.GAP_JUMP ? 2 : 1;
                int x = source.x() + direction[0] * length, z = source.z() + direction[1] * length;
                var plan = GroundTraversal.discover(player, start, x, z, mode, allowance).orElse(null);
                if (plan == null) continue;
                var target = new NavCell(map.dimension(), x, Mth.floor(plan.landing().minY + 1.0E-6), z);
                var key = new TraversabilityEdge.Key(source, target);
                // Keep ordinary safe steps when their existing proof is already sufficient.
                if (map.edge(key).map(TraversabilityEdge::traversable).orElse(false)) continue;
                var edge = new TraversabilityEdge(map.worldSessionId(), key, TraversabilityEdge.Status.CONFIRMED,
                        TraversabilityEdge.TargetSupport.CONFIRMED, TraversabilityEdge.Clearance.CONFIRMED,
                        TraversabilityEdge.Transition.CONFIRMED, TraversabilityEdge.Fluid.NONE, TraversabilityEdge.Hazard.NONE,
                        TraversabilityEdge.Provenance.LOCAL_VOLUME, current, player.tickCount, map.worldRevision(), mode);
                additions.add(edge); damage.put(key, plan.predictedDamage(player));
            }
        }
        return map.withGroundTransfers(additions, damage, allowance);
    }
}
