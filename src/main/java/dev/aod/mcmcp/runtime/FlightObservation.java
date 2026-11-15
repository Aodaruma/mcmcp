package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
    private final double landingFog;
    private final Map<BlockPos, Boolean> cells = new HashMap<>();
    private final Map<LandingSupport, Boolean> landingSupports = new HashMap<>();
    private final Map<String, Integer> rejections = new java.util.LinkedHashMap<>();
    private Map<String, Object> firstRejection;
    FlightObservation(Minecraft minecraft) {
        this.minecraft = minecraft;
        eye = minecraft.player.getEyePosition();
        radius = Math.min(McmcpClientConfig.flightObservationBlocks(),
                Math.max(8, minecraft.options.getEffectiveRenderDistance() * 16 - 16));
        landingFog = ClientFogDistanceSignals.current(minecraft.level, minecraft.player, minecraft.player.tickCount).orElse(0);
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
                if (sampledCells() >= MAX_CELLS) return reject("cell_budget", pos);
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

    boolean stoppedLanding(AABB body, Vec3 motion, double expectedGround) {
        if (motion.y > 0 || motion.y < -.6 || motion.horizontalDistance() > .65) return false;
        var feet = body.getCenter();
        var hit = minecraft.level.clip(new ClipContext(new Vec3(feet.x, body.minY + 1, feet.z),
                new Vec3(feet.x, body.minY - .4, feet.z), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != net.minecraft.core.Direction.UP) return false;
        double ground = hit.getLocation().y;
        if (!sameLandingHeight(expectedGround, ground) || ground - body.minY > .4 || body.minY - ground > .3) return false;
        var at = new Vec3(feet.x, ground, feet.z);
        var drift = new Vec3(motion.x, 0, motion.z);
        for (int tick = 0; tick < 32; tick++) {
            var next = at.add(drift);
            var standing = new AABB(Math.min(at.x, next.x) - .4, ground + .01, Math.min(at.z, next.z) - .4,
                    Math.max(at.x, next.x) + .4, ground + 1.8, Math.max(at.z, next.z) + .4);
            if (!clear(standing)) return false;
            for (var pos : BlockPos.betweenClosed(BlockPos.containing(standing.minX, ground - .01, standing.minZ),
                    BlockPos.containing(standing.maxX, ground - .01, standing.maxZ))) {
                var key = new LandingSupport(pos.immutable(), ground);
                Boolean known = landingSupports.get(key);
                if (known == null) {
                    if (sampledCells() >= MAX_CELLS) return reject("cell_budget", pos);
                    known = dryLandingSupport(pos, ground);
                    landingSupports.put(key, known);
                }
                if (!known) return false;
            }
            at = next;
            // Three unreduced steps cover touchdown reconciliation before ordinary ground friction.
            if (tick >= 2) drift = drift.scale(.546);
            if (drift.horizontalDistance() < .005) return true;
        }
        return false;
    }

    private boolean dryLandingSupport(BlockPos pos, double ground) {
        var level = minecraft.level;
        if (!visibleLandingSupport(eye, pos, ground, radius, landingFog,
                cell -> level.isLoaded(cell) && level.getWorldBorder().isWithinBounds(cell),
                (mode, end) -> level.clip(new ClipContext(eye, end, mode, ClipContext.Fluid.ANY, minecraft.player))))
            return reject("landing_support_unseen", pos);
        var state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)
                && state.getFluidState().isEmpty()
                && !dev.aod.mcmcp.routine.MinecraftSemanticActionPort.isNavigationHazard(state)
                && Math.abs(state.getFriction(level, pos, minecraft.player) - .6F) <= .0001F;
    }

    static boolean sameLandingHeight(double expected, double actual) {
        return Double.isFinite(expected) && Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-6;
    }

    /** Air above a floor does not prove that the floor's top, fluid or friction can be seen. */
    static boolean visibleLandingSupport(Vec3 eye, BlockPos support, double ground, double radius, double fog,
            java.util.function.Predicate<BlockPos> loaded,
            java.util.function.BiFunction<ClipContext.Block, Vec3, BlockHitResult> ray) {
        var surface = new Vec3(support.getX() + .5, ground, support.getZ() + .5);
        if (!Double.isFinite(ground) || !Double.isFinite(fog) || fog <= 0
                || eye.distanceToSqr(surface) > Math.min(radius, fog) * Math.min(radius, fog)) return false;
        var end = surface.add(0, -1e-5, 0);
        boolean allLoaded = BlockGetter.<Boolean, java.util.function.Predicate<BlockPos>>traverseBlocks(
                eye, end, loaded, (known, pos) -> known.test(pos) ? null : Boolean.FALSE, known -> Boolean.TRUE);
        if (!allLoaded) return false;
        for (var mode : new ClipContext.Block[] {ClipContext.Block.VISUAL, ClipContext.Block.COLLIDER}) {
            var hit = ray.apply(mode, end);
            if (hit.getType() != HitResult.Type.BLOCK || !support.equals(hit.getBlockPos())
                    || hit.getDirection() != net.minecraft.core.Direction.UP || hit.isInside() || hit.isWorldBorderHit()
                    || !sameLandingHeight(ground, hit.getLocation().y)) return false;
        }
        return true;
    }

    private record LandingSupport(BlockPos position, double height) { }

    Vec3 findLanding(Vec3 preferred, Vec3 threat, boolean descending) {
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
            if (descending && !ElytraLandingCourse.descendingGoal(player.position(), feet)) continue;
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
    int sampledCells() { return cells.size() + landingSupports.size(); }
    double radius() { return radius; }
}
