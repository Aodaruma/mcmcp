package dev.aodaruma.craftagent.observation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Conservative, renderer-independent visibility checks for structured observations.
 *
 * <p>This class deliberately has no access to {@link WorldMemory}. A hidden block or entity can
 * therefore never be remembered merely because the client received it in a chunk or entity
 * update. Callers may persist a result only after {@link Result#visible()} is true.</p>
 */
final class SampledVisibility {
    private static final double SURFACE_EPSILON = 1.0e-4;
    private static final double HIT_EPSILON_SQUARED = 1.0e-5;
    private static final int MAX_SHAPE_BOXES = 16;
    private static final int MAX_TRANSPARENT_CROSSINGS = 16;

    Result block(Minecraft minecraft, BlockPos pos, BlockState state, double maxDistance) {
        var setup = setup(minecraft);
        if (setup == null) {
            return Result.hidden("camera_unavailable");
        }
        if (!setup.level().isLoaded(pos)) {
            return Result.hidden("unloaded");
        }

        List<AABB> boxes = shapeBoxes(setup, pos, state);
        if (!withinDistance(setup.eye(), boxes, maxDistance)) {
            return Result.hidden("out_of_range");
        }
        if (!intersectsFrustum(setup.frustum(), boxes)) {
            return Result.hidden("outside_fov");
        }

        var visibleFaces = EnumSet.noneOf(Direction.class);
        for (Direction face : Direction.values()) {
            for (AABB box : boxes) {
                Vec3 sample = faceCenter(box, face);
                if (pointVisible(setup, pos, sample)) {
                    visibleFaces.add(face);
                    break;
                }
            }
        }

        if (!visibleFaces.isEmpty()) {
            return Result.visible(visibleFaces.stream().map(Direction::getSerializedName).toList());
        }

        // Empty blocks/fluids and narrow model shapes may have no useful face sample. A visible
        // center still proves that the queried cell itself is in the unobstructed viewport.
        for (AABB box : boxes) {
            if (pointVisible(setup, pos, box.getCenter())) {
                return Result.visible(List.of());
            }
        }
        return Result.hidden("occluded");
    }

    Result entity(Minecraft minecraft, Entity entity, double maxDistance) {
        var setup = setup(minecraft);
        if (setup == null) {
            return Result.hidden("camera_unavailable");
        }
        AABB bounds = entity.getBoundingBox();
        if (bounds.distanceToSqr(setup.eye()) > maxDistance * maxDistance) {
            return Result.hidden("out_of_range");
        }
        if (!setup.frustum().isVisible(bounds)) {
            return Result.hidden("outside_fov");
        }

        var samples = new ArrayList<Vec3>(8);
        samples.add(bounds.getCenter());
        samples.add(entity.getEyePosition());
        for (Direction face : Direction.values()) {
            samples.add(faceCenter(bounds, face));
        }
        for (Vec3 sample : samples) {
            if (setup.frustum().pointInFrustum(sample.x, sample.y, sample.z)
                    && clearToPoint(setup, sample, null)) {
                return Result.visible(List.of());
            }
        }
        return Result.hidden("occluded");
    }

    boolean positionInViewport(Minecraft minecraft, BlockPos pos, double maxDistance) {
        var setup = setup(minecraft);
        if (setup == null || !setup.level().isLoaded(pos)) {
            return false;
        }
        var bounds = new AABB(pos);
        return bounds.distanceToSqr(setup.eye()) <= maxDistance * maxDistance
                && setup.frustum().isVisible(bounds);
    }

    private static Setup setup(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (level == null || camera == null || !camera.isInitialized() || camera.entity() == null) {
            return null;
        }
        Frustum frustum = camera.getCullFrustum();
        if (frustum == null) {
            return null;
        }
        return new Setup(level, camera, frustum, camera.position());
    }

    private static List<AABB> shapeBoxes(Setup setup, BlockPos pos, BlockState state) {
        VoxelShape shape;
        try {
            shape = state.getShape(setup.level(), pos, CollisionContext.of(setup.camera().entity()));
        } catch (RuntimeException ignored) {
            shape = null;
        }
        if (shape == null || shape.isEmpty()) {
            return List.of(new AABB(pos));
        }

        List<AABB> local = shape.toAabbs();
        if (local.isEmpty()) {
            return List.of(new AABB(pos));
        }
        var world = new ArrayList<AABB>(Math.min(local.size(), MAX_SHAPE_BOXES));
        for (int index = 0; index < local.size() && index < MAX_SHAPE_BOXES; index++) {
            world.add(local.get(index).move(pos));
        }
        return List.copyOf(world);
    }

    private static boolean withinDistance(Vec3 eye, List<AABB> boxes, double maxDistance) {
        double maxDistanceSquared = maxDistance * maxDistance;
        for (AABB box : boxes) {
            if (box.distanceToSqr(eye) <= maxDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsFrustum(Frustum frustum, List<AABB> boxes) {
        for (AABB box : boxes) {
            if (frustum.isVisible(box)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointVisible(Setup setup, BlockPos target, Vec3 sample) {
        if (!setup.frustum().pointInFrustum(sample.x, sample.y, sample.z)) {
            return false;
        }
        return clearToPoint(setup, sample, target);
    }

    private static boolean clearToPoint(Setup setup, Vec3 sample, BlockPos target) {
        Vec3 towardEye = setup.eye().subtract(sample);
        if (towardEye.lengthSqr() < SURFACE_EPSILON * SURFACE_EPSILON) {
            return true;
        }
        Vec3 end = sample.add(towardEye.normalize().scale(SURFACE_EPSILON));
        return traceClearPath(
                setup.eye(),
                end,
                target,
                (from, to) -> setup.level().clip(new ClipContext(
                        from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, setup.camera().entity())),
                setup.level()::getBlockState);
    }

    /**
     * Continues a visual ray only through block types whose material is explicitly transparent.
     * Shape-wide predicates such as {@code !state.canOcclude()} are intentionally avoided: they
     * also match slabs, stairs, doors, and other solid geometry and would create X-ray visibility.
     */
    static boolean traceClearPath(
            Vec3 start,
            Vec3 end,
            BlockPos target,
            RayClipper clipper,
            Function<BlockPos, BlockState> blockStateAt) {
        Vec3 displacement = end.subtract(start);
        if (displacement.lengthSqr() < SURFACE_EPSILON * SURFACE_EPSILON) {
            return true;
        }
        Vec3 direction = displacement.normalize();
        Vec3 cursor = start;

        for (int transparentCrossings = 0; transparentCrossings <= MAX_TRANSPARENT_CROSSINGS;
                transparentCrossings++) {
            BlockHitResult hit = clipper.clip(cursor, end);
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            if (target != null
                    && target.equals(hit.getBlockPos())
                    && hit.getLocation().distanceToSqr(end) <= HIT_EPSILON_SQUARED) {
                return true;
            }
            if (!isVisuallyTransparent(blockStateAt.apply(hit.getBlockPos()))
                    || transparentCrossings == MAX_TRANSPARENT_CROSSINGS) {
                return false;
            }

            Vec3 next = advancePastBlockCell(hit.getLocation(), hit.getBlockPos(), direction);
            if (next.distanceToSqr(cursor) <= SURFACE_EPSILON * SURFACE_EPSILON) {
                return false;
            }
            if (next.subtract(end).dot(direction) >= 0.0D) {
                return true;
            }
            cursor = next;
        }
        return false;
    }

    static boolean isVisuallyTransparent(BlockState state) {
        return state.getBlock() instanceof HalfTransparentBlock
                || state.is(Blocks.GLASS_PANE)
                || state.getBlock() instanceof StainedGlassPaneBlock;
    }

    private static Vec3 advancePastBlockCell(Vec3 hit, BlockPos pos, Vec3 direction) {
        double distance = Math.min(
                exitDistance(hit.x, pos.getX(), direction.x),
                Math.min(
                        exitDistance(hit.y, pos.getY(), direction.y),
                        exitDistance(hit.z, pos.getZ(), direction.z)));
        if (!Double.isFinite(distance)) {
            return hit;
        }
        return hit.add(direction.scale(Math.max(0.0D, distance) + SURFACE_EPSILON));
    }

    private static double exitDistance(double coordinate, int cellCoordinate, double direction) {
        if (direction > 0.0D) {
            return (cellCoordinate + 1.0D - coordinate) / direction;
        }
        if (direction < 0.0D) {
            return (cellCoordinate - coordinate) / direction;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static Vec3 faceCenter(AABB box, Direction face) {
        double x = (box.minX + box.maxX) * 0.5;
        double y = (box.minY + box.maxY) * 0.5;
        double z = (box.minZ + box.maxZ) * 0.5;
        return switch (face) {
            case DOWN -> new Vec3(x, box.minY, z);
            case UP -> new Vec3(x, box.maxY, z);
            case NORTH -> new Vec3(x, y, box.minZ);
            case SOUTH -> new Vec3(x, y, box.maxZ);
            case WEST -> new Vec3(box.minX, y, z);
            case EAST -> new Vec3(box.maxX, y, z);
        };
    }

    record Result(boolean visible, List<String> visibleFaces, String reason) {
        Result {
            visibleFaces = List.copyOf(visibleFaces);
            if (visible == (reason != null)) {
                throw new IllegalArgumentException("visible results have no reason; hidden results require one");
            }
        }

        static Result visible(List<String> visibleFaces) {
            return new Result(true, visibleFaces, null);
        }

        static Result hidden(String reason) {
            return new Result(false, List.of(), reason);
        }
    }

    @FunctionalInterface
    interface RayClipper {
        BlockHitResult clip(Vec3 from, Vec3 to);
    }

    private record Setup(ClientLevel level, Camera camera, Frustum frustum, Vec3 eye) {}
}
