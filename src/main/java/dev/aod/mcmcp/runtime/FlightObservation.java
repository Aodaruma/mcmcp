package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.McmcpClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;

/** One tick's forward lookahead: loaded, visible, collision/fluid-free cells; never requests chunks. */
final class FlightObservation {
    private static final int MAX_CELLS = 2048;
    private final Minecraft minecraft;
    private final Vec3 eye;
    private final double radius;
    private final Map<BlockPos, Boolean> cells = new HashMap<>();
    private final Map<String, Integer> rejections = new java.util.LinkedHashMap<>();
    private Map<String, Object> firstRejection;
    FlightObservation(Minecraft minecraft) {
        this.minecraft = minecraft;
        eye = minecraft.player.getEyePosition();
        radius = Math.min(McmcpClientConfig.flightObservationBlocks(),
                Math.max(8, minecraft.options.getEffectiveRenderDistance() * 16 - 16));
    }
    boolean clear(AABB box) {
        if (box.getSize() > radius || box.getCenter().distanceTo(eye) + box.getSize() > radius)
            return reject("range", BlockPos.containing(box.getCenter()));
        if (!minecraft.level.getEntities(minecraft.player, box, entity -> entity.isAlive() && entity.isPickable()).isEmpty())
            return reject("entity", BlockPos.containing(box.getCenter()));
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            Boolean known = cells.get(pos);
            if (known == null) {
                if (cells.size() >= MAX_CELLS) return reject("cell_budget", pos);
                known = witness(pos);
                cells.put(pos.immutable(), known);
            }
            if (!known) return false;
        }
        return true;
    }
    boolean landing(AABB body, Vec3 motion) {
        if (motion.y > 0 || motion.y < -0.6 || motion.horizontalDistance() > 0.65) return false;
        var level = minecraft.level;
        var from = new Vec3(body.getCenter().x, body.minY + 1, body.getCenter().z);
        var hit = level.clip(new ClipContext(from, from.add(0, -1.4, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != net.minecraft.core.Direction.UP) return false;
        double ground = hit.getLocation().y;
        if (ground - body.minY > 0.4 || body.minY - ground > 0.3) return false;
        var standing = new AABB(body.minX - 0.1, ground + 0.01, body.minZ - 0.1,
                body.maxX + 0.1, ground + 1.8, body.maxZ + 0.1);
        if (!clear(standing)) return false;
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(standing.minX, ground - 0.01, standing.minZ),
                BlockPos.containing(standing.maxX, ground - 0.01, standing.maxZ))) {
            if (!level.isLoaded(pos)) return false;
            var state = level.getBlockState(pos);
            if (!state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP) || !state.getFluidState().isEmpty()
                    || dev.aod.mcmcp.routine.MinecraftSemanticActionPort.isNavigationHazard(state)) return false;
        }
        return true;
    }

    boolean supportedLanding(Vec3 feet) {
        // A remembered platform is not evidence that it still exists.
        if (feet == null || !minecraft.level.isLoaded(BlockPos.containing(feet))) return false;
        return landing(new AABB(feet.x - .3, feet.y, feet.z - .3,
                feet.x + .3, feet.y + .6, feet.z + .3), Vec3.ZERO);
    }

    Vec3 findLanding(Vec3 preferred, Vec3 threat) {
        var player = minecraft.player;
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (var probe : ElytraFlightPlanner.landingProbes(player.position(), player.getDeltaMovement(),
                player.getYRot(), preferred)) {
            var from = new Vec3(probe.x, player.getY() + 2, probe.z);
            var to = from.add(0, -Math.min(64, radius), 0);
            // The vertical search is only a candidate: require direct sight of its surface below.
            var hit = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != net.minecraft.core.Direction.UP) continue;
            var feet = hit.getLocation();
            if (eye.distanceTo(feet) > radius || !minecraft.level.isLoaded(hit.getBlockPos())) continue;
            var sight = minecraft.level.clip(new ClipContext(eye, feet.add(0, .02, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
            if (sight.getType() != HitResult.Type.MISS || !supportedLanding(feet)) continue;
            if (threat != null && feet.distanceToSqr(threat) < 36) continue;
            double score = ElytraFlightPlanner.landingScore(player.position(), player.getDeltaMovement(),
                    player.getYRot(), feet);
            if (score < bestScore) { best = feet; bestScore = score; }
        }
        return best;
    }
    private boolean witness(BlockPos pos) {
        var level = minecraft.level;
        if (!level.isLoaded(pos)) return reject("unloaded", pos);
        if (!level.getWorldBorder().isWithinBounds(pos)) return reject("world_border", pos);
        var end = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(end) > radius * radius) return reject("range", pos);
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, minecraft.player));
        if (hit.getType() != HitResult.Type.MISS) return reject("occluded", hit.getBlockPos());
        var state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) return reject("collision", pos);
        if (!state.getFluidState().isEmpty()) return reject("fluid", pos);
        if (dev.aod.mcmcp.routine.MinecraftSemanticActionPort.isNavigationHazard(state)
                || state.is(net.minecraft.world.level.block.Blocks.COBWEB)
                || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) return reject("hazard", pos);
        return true;
    }
    private boolean reject(String reason, BlockPos pos) {
        rejections.merge(reason, 1, Integer::sum);
        if (firstRejection == null) firstRejection = Map.of("reason", reason,
                "x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
        return false;
    }
    Map<String, Object> diagnostic() {
        return Map.of("rejections", Map.copyOf(rejections), "first_rejection",
                firstRejection == null ? Map.of() : firstRejection);
    }
    int sampledCells() { return cells.size(); }
    double radius() { return radius; }
}
