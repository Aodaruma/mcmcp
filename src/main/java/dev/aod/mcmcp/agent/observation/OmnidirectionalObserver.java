package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.agent.observation.ObservationRecord.EntityHazardClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.BlockStateView;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationRecord.ShapeClass;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundary;
import dev.aod.mcmcp.agent.observation.ObservationRecord.UnknownBoundaryReason;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleEntity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.Aabb;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.Vector;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import dev.aod.mcmcp.agent.observation.OmnidirectionalDirections.DirectionVector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds full-azimuth visual frames without changing or consulting player view state.
 *
 * <p>Call {@link #tick(ClientLevel, LocalPlayer, long, long, double)} once per client tick.
 * The supplied fog distance must come from the current render policy; this collector deliberately
 * does not reach through view, screen, focus, or option state to obtain it. Only a completed 2,048
 * direction frame is returned.</p>
 */
public final class OmnidirectionalObserver {
    public static final int DIRECTION_COUNT = OmnidirectionalDirections.DIRECTION_COUNT;
    public static final int MIN_RAYS_PER_TICK = 64;
    public static final int MAX_RAYS_PER_TICK = 512;
    public static final int DEFAULT_RAYS_PER_TICK = 256;
    public static final double MIN_RADIUS_BLOCKS = 1.0D;
    public static final double MAX_RADIUS_BLOCKS = 32.0D;

    private static final int MAX_VISITED_CELLS_PER_RAY = 128;
    public static final int MAX_NEARBY_ENTITIES = 128;
    public static final int MAX_VISIBLE_SURFACES = 8_192;
    public static final int MAX_UNKNOWN_BOUNDARIES = 4_096;
    private static final SecureRandom FRAME_ID_RANDOM = new SecureRandom();

    private static final Comparator<VisibleEntity> ENTITY_ORDER = Comparator
            .comparing((VisibleEntity value) -> value.entityType().value())
            .thenComparing(value -> value.displayedItem() == null
                    ? "" : value.displayedItem().value())
            .thenComparingDouble(value -> value.position().x())
            .thenComparingDouble(value -> value.position().y())
            .thenComparingDouble(value -> value.position().z())
            .thenComparingDouble(value -> value.aabb().minX())
            .thenComparingDouble(value -> value.aabb().minY())
            .thenComparingDouble(value -> value.aabb().minZ())
            .thenComparingDouble(value -> value.aabb().maxX())
            .thenComparingDouble(value -> value.aabb().maxY())
            .thenComparingDouble(value -> value.aabb().maxZ())
            .thenComparingDouble(value -> value.velocity().x())
            .thenComparingDouble(value -> value.velocity().y())
            .thenComparingDouble(value -> value.velocity().z())
            .thenComparing(value -> value.hazardClass().name());

    private final double configuredRadiusBlocks;
    private final int raysPerTick;
    private final FrameIdGenerator frameIds;
    private final LinkedHashMap<SurfaceKey, VisibleSurface> surfaces = new LinkedHashMap<>();
    private final LinkedHashMap<BoundaryKey, UnknownBoundary> boundaries = new LinkedHashMap<>();

    private ResourceId sessionDimension;
    private long lastAcceptedTick = -1L;
    private long lastVisualRevision = -1L;
    private int nextDirectionIndex;

    public OmnidirectionalObserver(double configuredRadiusBlocks, int raysPerTick) {
        this(configuredRadiusBlocks, raysPerTick, OmnidirectionalObserver::randomFrameId);
    }

    OmnidirectionalObserver(
            double configuredRadiusBlocks,
            int raysPerTick,
            FrameIdGenerator frameIds) {
        if (!Double.isFinite(configuredRadiusBlocks)
                || configuredRadiusBlocks < MIN_RADIUS_BLOCKS
                || configuredRadiusBlocks > MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("configuredRadiusBlocks must be between 1 and 32");
        }
        this.configuredRadiusBlocks = configuredRadiusBlocks;
        this.raysPerTick = OmnidirectionalDirections.requireRaysPerTick(raysPerTick);
        this.frameIds = Objects.requireNonNull(frameIds, "frameIds");
    }

    /**
     * Samples one deterministic batch from the player's actual eye position.
     *
     * @param worldRevision global mutation revision attached to records sampled on this tick
     * @param visualRevision epoch advanced only when a mutation invalidates partial visual rays
     * @param fogDistanceBlocks current finite fog boundary, or positive infinity when fog does
     *                          not reduce the configured radius
     */
    public Optional<ObservationFrame> tick(
            ClientLevel level,
            LocalPlayer player,
            long clientTick,
            long worldRevision,
            long visualRevision,
            double fogDistanceBlocks) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(player, "player");
        if (player.level() != level) {
            throw new IllegalArgumentException("player must belong to the sampled client level");
        }
        if ((!Double.isFinite(fogDistanceBlocks) && fogDistanceBlocks != Double.POSITIVE_INFINITY)
                || fogDistanceBlocks <= 0.0D) {
            throw new IllegalArgumentException("fogDistanceBlocks must be positive");
        }

        var dimension = new ResourceId(level.dimension().identifier().toString());
        Vec3 eye = player.getEyePosition();
        var eyeOrigin = worldPosition(dimension, eye);
        double effectiveRadius = Math.min(configuredRadiusBlocks, fogDistanceBlocks);
        UnknownBoundaryReason terminalReason = fogDistanceBlocks < configuredRadiusBlocks
                ? UnknownBoundaryReason.FOG_LIMIT
                : UnknownBoundaryReason.RADIUS_LIMIT;
        var sample = new TickSample(
                dimension,
                eyeOrigin,
                clientTick,
                worldRevision,
                visualRevision,
                effectiveRadius,
                terminalReason);

        return collectTick(
                sample,
                (directionIndex, direction, tickSample) -> traceMinecraftRay(
                        level, player, direction, tickSample, tickSample.effectiveRadiusBlocks()),
                () -> observeEntities(level, player, sample));
    }

    /** Discards a partial temporal frame, for example after disconnecting from a world. */
    public void reset() {
        clearAccumulation();
        sessionDimension = null;
        lastAcceptedTick = -1L;
        lastVisualRevision = -1L;
    }

    public double configuredRadiusBlocks() {
        return configuredRadiusBlocks;
    }

    public int raysPerTick() {
        return raysPerTick;
    }

    public int ticksToComplete() {
        return OmnidirectionalDirections.ticksToComplete(raysPerTick);
    }

    Optional<ObservationFrame> collectTick(
            TickSample sample,
            RaySampler raySampler,
            Supplier<EntityObservation> visibleEntities) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(raySampler, "raySampler");
        Objects.requireNonNull(visibleEntities, "visibleEntities");

        if (sessionDimension != null
                && sessionDimension.equals(sample.dimension())
                && sample.observedTick() == lastAcceptedTick) {
            return Optional.empty();
        }
        if (sessionDimension == null
                || !sessionDimension.equals(sample.dimension())
                || sample.observedTick() < lastAcceptedTick
                || lastVisualRevision >= 0L
                        && sample.visualRevision() != lastVisualRevision) {
            clearAccumulation();
        }
        sessionDimension = sample.dimension();
        lastAcceptedTick = sample.observedTick();
        lastVisualRevision = sample.visualRevision();

        int endExclusive = Math.min(DIRECTION_COUNT, nextDirectionIndex + raysPerTick);
        List<DirectionVector> directions = OmnidirectionalDirections.all();
        for (int index = nextDirectionIndex; index < endExclusive; index++) {
            RayTrace trace = Objects.requireNonNull(
                    raySampler.trace(index, directions.get(index), sample), "ray trace");
            requireTraceMetadata(trace, sample);
            VisibleSurface firstDropped = null;
            for (VisibleSurface surface : trace.surfaces()) {
                SurfaceKey key = new SurfaceKey(surface.position(), surface.face());
                if (surfaces.containsKey(key)) {
                    continue;
                }
                if (surfaces.size() < MAX_VISIBLE_SURFACES) {
                    surfaces.put(key, surface);
                } else if (firstDropped == null) {
                    firstDropped = surface;
                }
            }
            if (firstDropped != null && boundaries.size() < MAX_UNKNOWN_BOUNDARIES) {
                WorldPosition position = firstDropped.rayHit() == null
                        ? firstDropped.eyeOrigin() : firstDropped.rayHit();
                UnknownBoundary truncated = new UnknownBoundary(
                        position,
                        UnknownBoundaryReason.AMBIGUOUS_RENDER,
                        sample.eyeOrigin(),
                        sample.observedTick(),
                        sample.worldRevision());
                boundaries.putIfAbsent(
                        new BoundaryKey(truncated.position(), truncated.reason()), truncated);
            }
            UnknownBoundary boundary = trace.boundary();
            if (boundaries.size() < MAX_UNKNOWN_BOUNDARIES) {
                boundaries.putIfAbsent(
                        new BoundaryKey(boundary.position(), boundary.reason()),
                        boundary);
            }
        }
        nextDirectionIndex = endExclusive;
        if (nextDirectionIndex < DIRECTION_COUNT) {
            return Optional.empty();
        }

        EntityObservation entityObservation = Objects.requireNonNull(
                visibleEntities.get(), "visible entities");
        List<VisibleEntity> entities = new ArrayList<>(entityObservation.entities());
        for (VisibleEntity entity : entities) {
            requireEntityMetadata(Objects.requireNonNull(entity, "visible entity"), sample);
        }
        entities.sort(ENTITY_ORDER);

        var records = new ArrayList<ObservationRecord>(
                surfaces.size() + entities.size() + boundaries.size());
        records.addAll(surfaces.values());
        VisibleEntity previous = null;
        for (VisibleEntity entity : entities) {
            if (!entity.equals(previous)) {
                records.add(entity);
            }
            previous = entity;
        }
        records.addAll(boundaries.values());

        var completed = new ObservationFrame(
                frameIds.next(),
                sample.dimension(),
                sample.observedTick(),
                configuredRadiusBlocks,
                entityObservation.truncated(),
                false,
                records);
        clearAccumulation();
        return Optional.of(completed);
    }

    private RayTrace traceMinecraftRay(
            ClientLevel level,
            LocalPlayer player,
            DirectionVector direction,
            TickSample sample,
            double distance) {
        Vec3 from = vec3(sample.eyeOrigin());
        Vec3 to = from.add(direction.x() * distance, direction.y() * distance, direction.z() * distance);
        var visibleSurfaces = new ArrayList<VisibleSurface>();
        List<BlockPos> cells = cellsOnRay(from, to);
        if (cells.size() > MAX_VISITED_CELLS_PER_RAY) {
            return unknownTrace(
                    visibleSurfaces,
                    boundary(sample, to, UnknownBoundaryReason.AMBIGUOUS_RENDER));
        }

        CollisionContext collisionContext = CollisionContext.of(player);
        for (BlockPos cell : cells) {
            Vec3 cellEntry = cellEntry(from, to, cell);
            if (!level.isLoaded(cell)) {
                return unknownTrace(
                        visibleSurfaces,
                        boundary(sample, cellEntry, UnknownBoundaryReason.UNLOADED));
            }

            try {
                BlockState state = level.getBlockState(cell);
                FluidState fluid = state.getFluidState();
                if (state.isAir() && fluid.isEmpty()) {
                    continue;
                }
                // Fluid blocks have no block model; their independently rendered fluid shape
                // remains observable. Other non-model rendering is not safely classifiable.
                if (!state.isAir()
                        && state.getRenderShape() != RenderShape.MODEL
                        && !(state.getBlock() instanceof LiquidBlock)) {
                    return unknownTrace(
                            visibleSurfaces,
                            boundary(sample, cellEntry, UnknownBoundaryReason.AMBIGUOUS_RENDER));
                }

                VoxelShape outlineShape = state.getShape(level, cell, collisionContext);
                VoxelShape visualShape = state.getVisualShape(level, cell, collisionContext);
                VoxelShape fluidShape = fluid.isEmpty() ? null : fluid.getShape(level, cell);
                if (!state.isAir()
                        && outlineShape.isEmpty()
                        && visualShape.isEmpty()
                        && (fluidShape == null || fluidShape.isEmpty())) {
                    return unknownTrace(
                            visibleSurfaces,
                            boundary(sample, cellEntry, UnknownBoundaryReason.AMBIGUOUS_RENDER));
                }

                BlockHitResult outlineHit = outlineShape.clip(from, to, cell);
                BlockHitResult visualHit = visualShape.clip(from, to, cell);
                BlockHitResult blockHit = nearer(from, outlineHit, visualHit);
                BlockHitResult fluidHit = fluidShape == null ? null : fluidShape.clip(from, to, cell);
                var hits = orderedRenderableHits(from, blockHit, fluidHit);
                if (hits.isEmpty()) {
                    continue;
                }
                for (var renderable : hits) {
                    var hit = renderable.hit();
                    if (hit.isInside()) {
                        return unknownTrace(
                                visibleSurfaces,
                                boundary(sample, from, UnknownBoundaryReason.AMBIGUOUS_RENDER));
                    }

                    ShapeClass shapeClass = renderable.fluid()
                            ? ShapeClass.FLUID
                            : classify(state, outlineShape, visualShape);
                    visibleSurfaces.add(new VisibleSurface(
                            blockPosition(sample.dimension(), cell),
                            face(hit.getDirection()),
                            new ResourceId(
                                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                            policyVisibleBlockState(state),
                            safeDirectPlacementItem(state),
                            shapeClass,
                            state.getBlock() instanceof CropBlock crop
                                    ? crop.isMaxAge(state) : null,
                            worldPosition(sample.dimension(), hit.getLocation()),
                            sample.eyeOrigin(),
                            sample.observedTick(),
                            sample.worldRevision()));

                    UnknownBoundaryReason occlusion = occlusionReason(shapeClass);
                    if (occlusion != null) {
                        return new RayTrace(
                                occlusion == UnknownBoundaryReason.OPAQUE_OCCLUSION
                                        ? RayOutcome.HIT : RayOutcome.UNKNOWN,
                                visibleSurfaces,
                                boundary(sample, hit.getLocation(), occlusion));
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                return unknownTrace(
                        visibleSurfaces,
                        boundary(sample, cellEntry, UnknownBoundaryReason.AMBIGUOUS_RENDER));
            }
        }

        return new RayTrace(
                RayOutcome.MISS,
                visibleSurfaces,
                boundary(sample, to, sample.terminalReason()));
    }

    private EntityObservation observeEntities(
            ClientLevel level,
            LocalPlayer player,
            TickSample sample) {
        Vec3 eye = vec3(sample.eyeOrigin());
        double radius = sample.effectiveRadiusBlocks();
        AABB queryBounds = player.getBoundingBox().inflate(radius);
        var candidates = new ArrayList<Entity>();
        try {
            level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    queryBounds,
                    entity -> entity != player
                            && entity.isAlive()
                            && !entity.isRemoved()
                            && !entity.isSpectator()
                            && entity.getBoundingBox().distanceToSqr(eye) <= radius * radius,
                    candidates,
                    MAX_NEARBY_ENTITIES + 1);
        } catch (RuntimeException | LinkageError ignored) {
            return new EntityObservation(List.of(), true);
        }

        boolean truncated = candidates.size() > MAX_NEARBY_ENTITIES;
        var result = new ArrayList<VisibleEntity>(
                Math.min(candidates.size(), MAX_NEARBY_ENTITIES));
        for (Entity entity : candidates.subList(
                0, Math.min(candidates.size(), MAX_NEARBY_ENTITIES))) {
            try {
                if (entity.isInvisibleTo(player)
                        || !hasLineOfSight(level, player, entity.getBoundingBox(), sample)) {
                    continue;
                }
                Vec3 position = entity.position();
                Vec3 velocity = entity.getDeltaMovement();
                AABB box = entity.getBoundingBox();
                result.add(new VisibleEntity(
                        new ResourceId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()),
                        displayedItem(entity),
                        worldPosition(sample.dimension(), position),
                        new Vector(velocity.x, velocity.y, velocity.z),
                        new Aabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                        hazardClass(entity),
                        sample.eyeOrigin(),
                        sample.observedTick(),
                        sample.worldRevision()));
            } catch (RuntimeException | LinkageError ignored) {
                // A malformed or custom entity is omitted rather than exposing unvalidated state.
                truncated = true;
            }
        }
        return new EntityObservation(result, truncated);
    }

    private static ResourceId displayedItem(Entity entity) {
        return entity instanceof ItemEntity itemEntity
                ? displayedItem(itemEntity.getItem())
                : null;
    }

    /**
     * Canonical complete state only for audited construction, support, and owned-menu targets.
     * Returning null for every other visible block prevents non-rendered properties from crossing
     * the visual policy boundary.
     */
    static BlockStateView policyVisibleBlockState(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
            return blockStateView(state);
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return SafeConstructionBlocks.allowsVisibleState(blockId)
                && (!SafeConstructionBlocks.allows(blockId)
                        || SafeConstructionBlocks.allowsConstructionState(state))
                ? blockStateView(state) : null;
    }

    /** Canonical complete state encoder used only after the policy allowlist check. */
    static BlockStateView blockStateView(BlockState state) {
        Objects.requireNonNull(state, "state");
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateView(
                new ResourceId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                properties);
    }

    /**
     * Returns the audited vanilla one-cell BlockItem which directly owns this block, or null.
     * This is an item identity hint, not an authorization to place it; runtime prediction and
     * exact-state validation remain mandatory.
     */
    static ResourceId safeDirectPlacementItem(BlockState state) {
        Objects.requireNonNull(state, "state");
        Block block = state.getBlock();
        if (block == Blocks.WALL_TORCH
                && Items.TORCH instanceof StandingAndWallBlockItem) {
            return new ResourceId("minecraft:torch");
        }
        if (state.isAir()
                || state.hasBlockEntity()
                || block instanceof EntityBlock
                || block instanceof FallingBlock
                || block instanceof BedBlock
                || block == Blocks.TNT
                || !state.getFluidState().isEmpty()
                || SafeConstructionBlocks.allows(
                        BuiltInRegistries.BLOCK.getKey(block).toString())
                        && !SafeConstructionBlocks.allowsConstructionState(state)
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && !(block instanceof DoorBlock
                                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                        == DoubleBlockHalf.LOWER)
                || state.hasProperty(BlockStateProperties.BED_PART)
                || !(block.asItem() instanceof BlockItem item)
                || item.getBlock() != block) {
            return null;
        }
        var identifier = BuiltInRegistries.ITEM.getKey(item);
        var registered = BuiltInRegistries.ITEM.get(identifier);
        Class<?> implementation = item.getClass();
        if (!"minecraft".equals(identifier.getNamespace())
                || !SafeConstructionBlocks.allows(
                        BuiltInRegistries.BLOCK.getKey(block).toString())
                || registered.isEmpty()
                || registered.orElseThrow().value() != item
                || implementation != BlockItem.class
                        && !(block instanceof DoorBlock
                                && implementation == DoubleHighBlockItem.class)) {
            return null;
        }
        return new ResourceId(identifier.toString());
    }

    /** Extracts only the registry identity used to render an item entity, never stack metadata. */
    static ResourceId displayedItem(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Visible item entity has an empty display stack");
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null || "minecraft:air".equals(key.toString())) {
            throw new IllegalArgumentException("Visible item entity has no registered display item");
        }
        return new ResourceId(key.toString());
    }

    private boolean hasLineOfSight(
            ClientLevel level,
            LocalPlayer player,
            AABB target,
            TickSample sample) {
        Vec3 eye = vec3(sample.eyeOrigin());
        for (Vec3 point : aabbSamplePoints(target)) {
            Vec3 delta = point.subtract(eye);
            double distance = delta.length();
            if (distance == 0.0D) {
                return true;
            }
            var direction = new DirectionVector(
                    delta.x / distance,
                    delta.y / distance,
                    delta.z / distance);
            RayTrace trace = traceMinecraftRay(level, player, direction, sample, distance);
            if (trace.outcome() == RayOutcome.MISS) {
                return true;
            }
        }
        return false;
    }

    private static List<Vec3> aabbSamplePoints(AABB box) {
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double insetMinX = Math.fma(box.maxX - box.minX, 0.05D, box.minX);
        double insetMinY = Math.fma(box.maxY - box.minY, 0.05D, box.minY);
        double insetMinZ = Math.fma(box.maxZ - box.minZ, 0.05D, box.minZ);
        double insetMaxX = Math.fma(box.maxX - box.minX, 0.95D, box.minX);
        double insetMaxY = Math.fma(box.maxY - box.minY, 0.95D, box.minY);
        double insetMaxZ = Math.fma(box.maxZ - box.minZ, 0.95D, box.minZ);
        return List.of(
                new Vec3(centerX, centerY, centerZ),
                new Vec3(insetMinX, insetMinY, insetMinZ),
                new Vec3(insetMinX, insetMinY, insetMaxZ),
                new Vec3(insetMinX, insetMaxY, insetMinZ),
                new Vec3(insetMinX, insetMaxY, insetMaxZ),
                new Vec3(insetMaxX, insetMinY, insetMinZ),
                new Vec3(insetMaxX, insetMinY, insetMaxZ),
                new Vec3(insetMaxX, insetMaxY, insetMinZ),
                new Vec3(insetMaxX, insetMaxY, insetMaxZ));
    }

    private static ShapeClass classify(
            BlockState state,
            VoxelShape outlineShape,
            VoxelShape visualShape) {
        Block block = state.getBlock();
        if (block instanceof HalfTransparentBlock
                || block instanceof StainedGlassPaneBlock
                || state.is(Blocks.GLASS_PANE)) {
            return ShapeClass.TRANSPARENT;
        }
        if (block instanceof LeavesBlock || block instanceof BushBlock || block instanceof WebBlock) {
            return ShapeClass.CUTOUT;
        }
        if (state.isSolidRender() && Block.isShapeFullBlock(visualShape)) {
            return ShapeClass.OPAQUE;
        }
        if (!Block.isShapeFullBlock(outlineShape) || !Block.isShapeFullBlock(visualShape)) {
            return ShapeClass.PARTIAL;
        }
        return state.canOcclude() ? ShapeClass.PARTIAL : ShapeClass.CUTOUT;
    }

    static UnknownBoundaryReason occlusionReason(ShapeClass shapeClass) {
        return switch (shapeClass) {
            case OPAQUE, PARTIAL -> UnknownBoundaryReason.OPAQUE_OCCLUSION;
            case CUTOUT, UNKNOWN -> UnknownBoundaryReason.AMBIGUOUS_RENDER;
            case TRANSPARENT, FLUID -> null;
        };
    }

    private static EntityHazardClass hazardClass(Entity entity) {
        if (entity instanceof Player) {
            return EntityHazardClass.PLAYER;
        }
        if (entity instanceof Projectile) {
            return EntityHazardClass.PROJECTILE;
        }
        if (entity instanceof Enemy) {
            return EntityHazardClass.HOSTILE;
        }
        if (entity instanceof NeutralMob) {
            return EntityHazardClass.NEUTRAL;
        }
        if (entity instanceof Animal) {
            return EntityHazardClass.PASSIVE;
        }
        if (entity instanceof Mob) {
            return EntityHazardClass.NEUTRAL;
        }
        return EntityHazardClass.UNKNOWN;
    }

    private static List<BlockPos> cellsOnRay(Vec3 from, Vec3 to) {
        var cells = new ArrayList<BlockPos>();
        BlockGetter.<Void, List<BlockPos>>traverseBlocks(
                from,
                to,
                cells,
                (result, position) -> {
                    result.add(position.immutable());
                    return null;
                },
                ignored -> null);
        return cells;
    }

    private static Vec3 cellEntry(Vec3 from, Vec3 to, BlockPos cell) {
        return new AABB(cell).clip(from, to).orElse(from);
    }

    private static BlockHitResult nearer(
            Vec3 origin,
            BlockHitResult first,
            BlockHitResult second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return origin.distanceToSqr(first.getLocation())
                <= origin.distanceToSqr(second.getLocation()) ? first : second;
    }

    static BlockHitResult nearestRenderableHit(
            Vec3 origin, BlockHitResult blockHit, BlockHitResult fluidHit) {
        BlockHitResult nearest = nearer(origin, blockHit, fluidHit);
        return fluidHit != null && nearest == fluidHit && fluidHit.isInside() ? blockHit : nearest;
    }

    static List<RenderableHit> orderedRenderableHits(
            Vec3 origin, BlockHitResult blockHit, BlockHitResult fluidHit) {
        if (fluidHit != null && fluidHit.isInside()) {
            fluidHit = null;
        }
        if (blockHit == null) {
            return fluidHit == null ? List.of() : List.of(new RenderableHit(fluidHit, true));
        }
        if (fluidHit == null) {
            return List.of(new RenderableHit(blockHit, false));
        }
        double blockDistance = origin.distanceToSqr(blockHit.getLocation());
        double fluidDistance = origin.distanceToSqr(fluidHit.getLocation());
        if (Math.abs(blockDistance - fluidDistance) <= 1.0E-12D) {
            return List.of(new RenderableHit(blockHit, false));
        }
        return blockDistance < fluidDistance
                ? List.of(new RenderableHit(blockHit, false), new RenderableHit(fluidHit, true))
                : List.of(new RenderableHit(fluidHit, true), new RenderableHit(blockHit, false));
    }

    private static UnknownBoundary boundary(
            TickSample sample,
            Vec3 location,
            UnknownBoundaryReason reason) {
        return new UnknownBoundary(
                worldPosition(sample.dimension(), location),
                reason,
                sample.eyeOrigin(),
                sample.observedTick(),
                sample.worldRevision());
    }

    private static RayTrace unknownTrace(
            List<VisibleSurface> surfaces,
            UnknownBoundary boundary) {
        return new RayTrace(RayOutcome.UNKNOWN, surfaces, boundary);
    }

    private static void requireTraceMetadata(RayTrace trace, TickSample sample) {
        for (VisibleSurface surface : trace.surfaces()) {
            Objects.requireNonNull(surface, "visible surface");
            if (!surface.dimension().equals(sample.dimension())
                    || !surface.eyeOrigin().equals(sample.eyeOrigin())
                    || surface.observedTick() != sample.observedTick()
                    || surface.worldRevision() != sample.worldRevision()) {
                throw new IllegalArgumentException("ray surface metadata does not match its actual sample");
            }
        }
        UnknownBoundary boundary = trace.boundary();
        if (!boundary.dimension().equals(sample.dimension())
                || !boundary.eyeOrigin().equals(sample.eyeOrigin())
                || boundary.observedTick() != sample.observedTick()
                || boundary.worldRevision() != sample.worldRevision()) {
            throw new IllegalArgumentException("ray boundary metadata does not match its actual sample");
        }
    }

    private static void requireEntityMetadata(VisibleEntity entity, TickSample sample) {
        if (!entity.dimension().equals(sample.dimension())
                || !entity.eyeOrigin().equals(sample.eyeOrigin())
                || entity.observedTick() != sample.observedTick()
                || entity.worldRevision() != sample.worldRevision()) {
            throw new IllegalArgumentException("entity metadata does not match its actual sample");
        }
    }

    private void clearAccumulation() {
        nextDirectionIndex = 0;
        surfaces.clear();
        boundaries.clear();
    }

    private static BlockPosition blockPosition(ResourceId dimension, BlockPos position) {
        return new BlockPosition(
                dimension,
                position.getX(),
                position.getY(),
                position.getZ());
    }

    private static WorldPosition worldPosition(ResourceId dimension, Vec3 position) {
        return new WorldPosition(dimension, position.x, position.y, position.z);
    }

    private static Vec3 vec3(WorldPosition position) {
        return new Vec3(position.x(), position.y(), position.z());
    }

    private static Face face(Direction direction) {
        return switch (direction) {
            case DOWN -> Face.DOWN;
            case UP -> Face.UP;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case WEST -> Face.WEST;
            case EAST -> Face.EAST;
        };
    }

    private static String randomFrameId() {
        byte[] entropy = new byte[Long.BYTES];
        FRAME_ID_RANDOM.nextBytes(entropy);
        return "obs-" + HexFormat.of().formatHex(entropy);
    }

    enum RayOutcome {
        HIT,
        MISS,
        UNKNOWN
    }

    record RenderableHit(BlockHitResult hit, boolean fluid) {
        RenderableHit {
            Objects.requireNonNull(hit, "hit");
        }
    }

    record TickSample(
            ResourceId dimension,
            WorldPosition eyeOrigin,
            long observedTick,
            long worldRevision,
            long visualRevision,
            double effectiveRadiusBlocks,
            UnknownBoundaryReason terminalReason) {
        TickSample {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(eyeOrigin, "eyeOrigin");
            Objects.requireNonNull(terminalReason, "terminalReason");
            ObservationValues.requireSameDimension(dimension, eyeOrigin.dimension());
            ObservationValues.requireTick(observedTick, "observedTick");
            ObservationValues.requireTick(worldRevision, "worldRevision");
            ObservationValues.requireTick(visualRevision, "visualRevision");
            if (!Double.isFinite(effectiveRadiusBlocks)
                    || effectiveRadiusBlocks <= 0.0D
                    || effectiveRadiusBlocks > MAX_RADIUS_BLOCKS) {
                throw new IllegalArgumentException("effectiveRadiusBlocks must be in (0, 32]");
            }
            if (terminalReason != UnknownBoundaryReason.RADIUS_LIMIT
                    && terminalReason != UnknownBoundaryReason.FOG_LIMIT) {
                throw new IllegalArgumentException("terminalReason must describe a finite sampled limit");
            }
        }
    }

    record RayTrace(
            RayOutcome outcome,
            List<VisibleSurface> surfaces,
            UnknownBoundary boundary) {
        RayTrace {
            Objects.requireNonNull(outcome, "outcome");
            surfaces = List.copyOf(Objects.requireNonNull(surfaces, "surfaces"));
            Objects.requireNonNull(boundary, "boundary");
            boolean validBoundary = switch (outcome) {
                case HIT -> boundary.reason() == UnknownBoundaryReason.OPAQUE_OCCLUSION;
                case MISS -> boundary.reason() == UnknownBoundaryReason.RADIUS_LIMIT
                        || boundary.reason() == UnknownBoundaryReason.FOG_LIMIT;
                case UNKNOWN -> boundary.reason() == UnknownBoundaryReason.UNLOADED
                        || boundary.reason() == UnknownBoundaryReason.AMBIGUOUS_RENDER;
            };
            if (!validBoundary) {
                throw new IllegalArgumentException("ray outcome and unknown boundary disagree");
            }
            if (outcome == RayOutcome.HIT && surfaces.isEmpty()) {
                throw new IllegalArgumentException("HIT requires a visible surface");
            }
        }
    }

    record EntityObservation(List<VisibleEntity> entities, boolean truncated) {
        EntityObservation {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            if (entities.size() > MAX_NEARBY_ENTITIES) {
                throw new IllegalArgumentException("visible entity observation exceeds its cap");
            }
        }

        static EntityObservation empty() {
            return new EntityObservation(List.of(), false);
        }
    }

    @FunctionalInterface
    interface RaySampler {
        RayTrace trace(int directionIndex, DirectionVector direction, TickSample sample);
    }

    @FunctionalInterface
    interface FrameIdGenerator {
        String next();
    }

    private record SurfaceKey(BlockPosition position, Face face) {
    }

    private record BoundaryKey(WorldPosition position, UnknownBoundaryReason reason) {
    }
}
