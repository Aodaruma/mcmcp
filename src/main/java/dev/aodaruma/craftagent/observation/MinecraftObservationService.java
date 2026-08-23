package dev.aodaruma.craftagent.observation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Captures bounded, same-tick client observations without exposing hidden chunk or entity state.
 *
 * <p>All public capture methods must be invoked on the Minecraft client thread. They synchronously
 * finish the requested sample for the supplied {@code clientTick}; callers must not combine calls
 * made on different ticks into one snapshot or block-plan comparison.</p>
 */
public final class MinecraftObservationService {
    public static final int MAX_EXPLICIT_POSITIONS = 512;
    public static final int MAX_VIEWPORT_RESULTS = 256;
    public static final int MAX_ENTITY_RESULTS = 256;
    public static final double MAX_LIVE_BLOCK_DISTANCE = 64.0;
    public static final double MAX_VIEWPORT_DISTANCE = 12.0;
    public static final double MAX_ENTITY_DISTANCE = 64.0;

    private static final double DEFAULT_VIEWPORT_DISTANCE = 8.0;
    private static final int DEFAULT_VIEWPORT_RESULTS = 128;
    private static final double DEFAULT_ENTITY_DISTANCE = 24.0;
    private static final int DEFAULT_ENTITY_RESULTS = 64;
    // A radius-12 viewport has at most 25^3 cells before the spherical distance filter.
    private static final int MAX_VIEWPORT_CANDIDATES = 15_625;
    private static final Set<String> SUPPORTED_SCOPES = Set.of(
            "player", "inventory", "target", "visible_blocks", "visible_entities", "world", "screen");

    private final WorldMemory memory;
    private final SampledVisibility visibility;

    public MinecraftObservationService(WorldMemory memory) {
        this(memory, new SampledVisibility());
    }

    MinecraftObservationService(WorldMemory memory, SampledVisibility visibility) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    /** Shared fairness boundary for safety checks that must not become a hidden-entity oracle. */
    public boolean isEntityCurrentlyVisible(
            Minecraft minecraft,
            net.minecraft.world.entity.Entity entity,
            double maxDistance) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(entity, "entity");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("entity visibility must be sampled on the client thread");
        }
        return visibility.entity(minecraft, entity, maxDistance).visible();
    }

    /**
     * Resolves an opaque reference back to a currently loaded, alive, visible non-player Entity.
     * UUIDs remain internal and stale/mismatched references simply do not resolve.
     */
    public Optional<Entity> resolveCurrentlyVisibleEntity(
            Minecraft minecraft,
            long clientTick,
            UUID worldSessionId,
            String dimension,
            String entityRef,
            double maxDistance) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(entityRef, "entityRef");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("entity reference resolution must run on the client thread");
        }
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D
                || maxDistance > MAX_ENTITY_DISTANCE) {
            throw new IllegalArgumentException("maxDistance is outside the observable entity range");
        }
        var level = minecraft.level;
        if (level == null || minecraft.player == null
                || !dimension.equals(level.dimension().identifier().toString())) {
            return Optional.empty();
        }
        return memory.resolveEntityRef(entityRef, clientTick, worldSessionId, dimension)
                .flatMap(resolved -> Optional.ofNullable(level.getEntity(resolved.internalUuid()))
                        .filter(entity -> !(entity instanceof Player))
                        .filter(entity -> entity.isAlive() && !entity.isRemoved())
                        .filter(entity -> resolved.type().equals(
                                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))
                        .filter(entity -> visibility.entity(minecraft, entity, maxDistance).visible()));
    }

    /** Captures all requested snapshot scopes in the supplied client tick. */
    public Map<String, Object> getSnapshot(
            Minecraft minecraft, long clientTick, Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        ClientState state = requireClientState(minecraft, clientTick);
        List<String> scopes = parseScopes(arguments.get("scopes"));
        Map<?, ?> options = optionalMap(arguments.get("options"), "options");
        var capture = new Capture();
        var payload = new LinkedHashMap<String, Object>();

        // A stable order keeps audit output deterministic and lets target observations populate
        // the shared same-tick cache before a visible-block/entity scope reuses them.
        if (scopes.contains("player")) {
            payload.put("player", player(state));
        }
        if (scopes.contains("inventory")) {
            payload.put("inventory", inventory(state));
        }
        if (scopes.contains("world")) {
            payload.put("world", world(state));
        }
        if (scopes.contains("screen")) {
            payload.put("screen", screen(state));
        }
        if (scopes.contains("target")) {
            payload.put("target", target(state, capture));
        }
        if (scopes.contains("visible_blocks")) {
            payload.put("visible_blocks", visibleBlocks(
                    state, capture, optionalMap(options.get("visible_blocks"), "options.visible_blocks")));
        }
        if (scopes.contains("visible_entities")) {
            payload.put("visible_entities", visibleEntities(
                    state, capture, optionalMap(options.get("visible_entities"), "options.visible_entities")));
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("world_session_id", state.sessionId().toString());
        result.put("client_tick", clientTick);
        result.put("observation_revision", memory.revision());
        result.put("requested_scopes", List.copyOf(scopes));
        result.putAll(payload);
        return result;
    }

    /**
     * Single-tick primitive for {@code compare_block_plan}. The order and number of results exactly
     * match the input positions, including unknown and not-observable outcomes.
     */
    public List<BlockSample> observeBlocks(
            Minecraft minecraft, long clientTick, List<BlockPos> positions, BlockSource source) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(source, "source");
        if (positions.size() > MAX_EXPLICIT_POSITIONS) {
            throw new IllegalArgumentException("positions exceeds " + MAX_EXPLICIT_POSITIONS);
        }
        ClientState state = requireClientState(minecraft, clientTick);
        return observeBlocks(state, positions, source);
    }

    /**
     * Dimension-qualified form used by plan comparison. A qualified position from another
     * dimension is rejected rather than silently being sampled in the current client level.
     */
    public List<BlockSample> observeBlocks(
            Minecraft minecraft,
            long clientTick,
            Collection<BlockPosition> positions,
            BlockSource source) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(source, "source");
        if (positions.size() > MAX_EXPLICIT_POSITIONS) {
            throw new IllegalArgumentException("positions exceeds " + MAX_EXPLICIT_POSITIONS);
        }
        ClientState state = requireClientState(minecraft, clientTick);
        var minecraftPositions = new ArrayList<BlockPos>(positions.size());
        var capture = new Capture();
        for (BlockPosition position : positions) {
            Objects.requireNonNull(position, "position");
            if (!state.dimension().equals(position.dimension())) {
                throw new IllegalArgumentException("block observations are limited to the current dimension");
            }
            minecraftPositions.add(new BlockPos(position.x(), position.y(), position.z()));
        }
        return observeBlocks(state, minecraftPositions, source, capture);
    }

    /** Convenience form of {@link #observeBlocks} for one expected plan position. */
    public BlockSample observeBlock(
            Minecraft minecraft, long clientTick, BlockPosition position, BlockSource source) {
        return observeBlocks(minecraft, clientTick, List.<BlockPosition>of(position), source).getFirst();
    }

    /** Convenience form for a position already known to be in the current client dimension. */
    public BlockSample observeBlock(
            Minecraft minecraft, long clientTick, BlockPos position, BlockSource source) {
        return observeBlocks(minecraft, clientTick, List.<BlockPos>of(position), source).getFirst();
    }

    private List<BlockSample> observeBlocks(
            ClientState state, List<BlockPos> positions, BlockSource source) {
        return observeBlocks(state, positions, source, new Capture());
    }

    private List<BlockSample> observeBlocks(
            ClientState state, List<BlockPos> positions, BlockSource source, Capture capture) {
        var results = new ArrayList<BlockSample>(positions.size());
        for (BlockPos position : positions) {
            results.add(sampleBlock(state, capture, Objects.requireNonNull(position, "position"), source));
        }
        return List.copyOf(results);
    }

    private Map<String, Object> visibleBlocks(ClientState state, Capture capture, Map<?, ?> options) {
        BlockSource source = BlockSource.parse(optionalString(options.get("source"), "live_and_memory"));
        Map<?, ?> query = optionalMap(options.get("query"), "options.visible_blocks.query");
        String kind = optionalString(query.get("kind"), "viewport");
        return switch (kind) {
            case "positions" -> explicitBlocks(state, capture, source, query);
            case "viewport" -> viewportBlocks(state, capture, source, query);
            default -> throw new IllegalArgumentException("unsupported visible_blocks query kind: " + kind);
        };
    }

    private Map<String, Object> explicitBlocks(
            ClientState state, Capture capture, BlockSource source, Map<?, ?> query) {
        Object rawPositions = query.get("positions");
        if (!(rawPositions instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("positions query requires an array");
        }
        if (collection.size() > MAX_EXPLICIT_POSITIONS) {
            throw new IllegalArgumentException("positions exceeds " + MAX_EXPLICIT_POSITIONS);
        }

        var unique = new LinkedHashSet<BlockPos>();
        for (Object raw : collection) {
            Map<?, ?> input = requiredMap(raw, "position");
            Object dimension = input.get("dimension");
            if (dimension != null && !state.dimension().equals(requiredString(dimension, "position.dimension"))) {
                throw new IllegalArgumentException("positions are limited to the current dimension");
            }
            var position = new BlockPos(
                    requiredInt(input.get("x"), "position.x"),
                    requiredInt(input.get("y"), "position.y"),
                    requiredInt(input.get("z"), "position.z"));
            if (!unique.add(position)) {
                throw new IllegalArgumentException("duplicate position: " + position);
            }
        }

        var samples = new ArrayList<BlockSample>(unique.size());
        for (BlockPos position : unique) {
            samples.add(sampleBlock(state, capture, position, source));
        }
        return blockQueryMap("positions", source, samples, unique.size(), false);
    }

    private Map<String, Object> viewportBlocks(
            ClientState state, Capture capture, BlockSource source, Map<?, ?> query) {
        double maxDistance = boundedDouble(
                query.get("max_distance"), DEFAULT_VIEWPORT_DISTANCE, 1.0, MAX_VIEWPORT_DISTANCE,
                "visible_blocks.query.max_distance");
        int maxResults = boundedInt(
                query.get("max_results"), DEFAULT_VIEWPORT_RESULTS, 1, MAX_VIEWPORT_RESULTS,
                "visible_blocks.query.max_results");
        var samples = new ArrayList<BlockSample>(maxResults);
        var included = new HashSet<BlockPosition>();
        int considered = 0;
        boolean truncated = false;

        if (source.includesLive()) {
            List<BlockPos> candidates = viewportCandidates(state, maxDistance);
            considered += candidates.size();
            for (BlockPos position : candidates) {
                if (samples.size() >= maxResults) {
                    truncated = true;
                    break;
                }
                // Air cells are meaningful for explicit plan checks but would dominate a viewport.
                // They are still re-observed below when a prior memory entry exists at the cell.
                if (state.level().getBlockState(position).isAir()) {
                    continue;
                }
                BlockSample sample = sampleBlock(state, capture, position, BlockSource.LIVE);
                if (sample.outcome() == BlockOutcome.CURRENT && included.add(sample.position())) {
                    samples.add(sample);
                }
            }
        }

        if (samples.size() < maxResults && source.includesMemory()) {
            for (ObservedBlock remembered : retainedBlocksInCurrentDimension(state)) {
                if (samples.size() >= maxResults) {
                    truncated = true;
                    break;
                }
                if (included.contains(remembered.position())) {
                    continue;
                }
                var pos = new BlockPos(
                        remembered.position().x(), remembered.position().y(), remembered.position().z());
                if (!visibility.positionInViewport(state.minecraft(), pos, maxDistance)) {
                    continue;
                }
                BlockSample sample = sampleBlock(state, capture, pos, source);
                if ((sample.outcome() == BlockOutcome.CURRENT
                                || sample.outcome() == BlockOutcome.LAST_KNOWN)
                        && included.add(sample.position())) {
                    samples.add(sample);
                }
            }
        }

        var result = new LinkedHashMap<String, Object>(
                blockQueryMap("viewport", source, samples, considered, truncated));
        result.put("max_distance", maxDistance);
        result.put("max_results", maxResults);
        return result;
    }

    private List<BlockPos> viewportCandidates(ClientState state, double maxDistance) {
        var camera = state.minecraft().gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized()) {
            return List.of();
        }
        Vec3 eye = camera.position();
        BlockPos origin = BlockPos.containing(eye);
        int radius = (int) Math.ceil(maxDistance);
        var candidates = new ArrayList<BlockPos>();
        for (int y = -radius; y <= radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    var position = origin.offset(x, y, z);
                    if (new AABB(position).distanceToSqr(eye) <= maxDistance * maxDistance) {
                        candidates.add(position);
                        if (candidates.size() > MAX_VIEWPORT_CANDIDATES) {
                            throw new IllegalStateException("bounded viewport candidate limit exceeded");
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(position -> new AABB(position).distanceToSqr(eye)));
        return List.copyOf(candidates);
    }

    private BlockSample sampleBlock(
            ClientState state, Capture capture, BlockPos position, BlockSource source) {
        var qualified = new BlockPosition(
                state.dimension(), position.getX(), position.getY(), position.getZ());
        Optional<ObservedBlock> remembered = memory.findBlock(qualified);

        if (source == BlockSource.MEMORY) {
            return remembered
                    .map(value -> BlockSample.lastKnown(value, state.clientTick()))
                    .orElseGet(() -> BlockSample.unknown(qualified, unknownReason(memory.stats())));
        }

        LiveBlock live = capture.blocks().computeIfAbsent(position,
                ignored -> observeLiveBlock(state, position));
        if (live.observation() != null) {
            return BlockSample.current(
                    live.observation(), live.visibleFaces(), live.withinReach(), state.clientTick());
        }
        if (source == BlockSource.LIVE) {
            return BlockSample.notCurrentlyObservable(qualified, live.hiddenReason());
        }
        return remembered
                .map(value -> BlockSample.lastKnown(value, state.clientTick()))
                .orElseGet(() -> BlockSample.unknown(qualified, unknownReason(memory.stats())));
    }

    private LiveBlock observeLiveBlock(ClientState state, BlockPos position) {
        BlockState blockState = state.level().getBlockState(position);
        List<String> visibleFaces;
        ObservationProvenance provenance;
        String hiddenReason = null;

        if (state.minecraft().hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && position.equals(hit.getBlockPos())) {
            SampledVisibility.Result sampled = visibility.block(
                    state.minecraft(), position, blockState, MAX_LIVE_BLOCK_DISTANCE);
            visibleFaces = mergeCrosshairVisibleFaces(
                    hit.getDirection().getSerializedName(),
                    sampled.visible() ? sampled.visibleFaces() : List.of());
            provenance = ObservationProvenance.CROSSHAIR_OBSERVATION;
        } else {
            SampledVisibility.Result result = visibility.block(
                    state.minecraft(), position, blockState, MAX_LIVE_BLOCK_DISTANCE);
            if (!result.visible()) {
                return LiveBlock.hidden(result.reason());
            }
            visibleFaces = result.visibleFaces();
            provenance = ObservationProvenance.LINE_OF_SIGHT_OBSERVATION;
        }

        boolean withinReach = state.player().isWithinBlockInteractionRange(position, 0.0);
        ObservedBlock observation = new ObservedBlock(
                new BlockPosition(state.dimension(), position.getX(), position.getY(), position.getZ()),
                blockStateView(blockState),
                observedContext(state.level(), position, blockState),
                provenance,
                state.clientTick(),
                state.sessionId());
        memory.rememberBlock(observation);
        return new LiveBlock(observation, visibleFaces, withinReach, hiddenReason);
    }

    static List<String> mergeCrosshairVisibleFaces(
            String hitFace, Collection<String> sampledFaces) {
        var merged = new LinkedHashSet<String>();
        merged.add(Objects.requireNonNull(hitFace, "hitFace"));
        merged.addAll(Objects.requireNonNull(sampledFaces, "sampledFaces"));
        return List.copyOf(merged);
    }

    private Map<String, Object> visibleEntities(ClientState state, Capture capture, Map<?, ?> options) {
        BlockSource source = BlockSource.parse(optionalString(options.get("source"), "live_and_memory"));
        double maxDistance = boundedDouble(
                options.get("max_distance"), DEFAULT_ENTITY_DISTANCE, 1.0, MAX_ENTITY_DISTANCE,
                "visible_entities.max_distance");
        int maxResults = boundedInt(
                options.get("max_results"), DEFAULT_ENTITY_RESULTS, 1, MAX_ENTITY_RESULTS,
                "visible_entities.max_results");
        EntityFilter typeFilter = EntityFilter.parse(options.get("types"));
        String threatFilter = optionalString(options.get("threat_relation"), "any");
        if (!Set.of("any", "currently_hostile_to_player").contains(threatFilter)) {
            throw new IllegalArgumentException("unsupported threat_relation: " + threatFilter);
        }

        var results = new ArrayList<Map<String, Object>>(maxResults);
        var current = new HashSet<UUID>();
        boolean truncated = false;
        ObservationOrigin origin = observationOrigin(state);

        if (source.includesLive()) {
            Vec3 center = origin.position();
            AABB search = AABB.ofSize(center, maxDistance * 2.0, maxDistance * 2.0, maxDistance * 2.0);
            List<Entity> entities = state.level().getEntities(
                    state.player(), search, entity -> entity.isAlive() && typeFilter.test(entity.getType()));
            entities.sort(Comparator.comparingDouble(entity -> entity.getBoundingBox().distanceToSqr(center)));
            for (Entity entity : entities) {
                if (entity.position().distanceToSqr(center) > maxDistance * maxDistance) {
                    continue;
                }
                boolean activelyTargetingPlayer = entity instanceof Mob mob && mob.getTarget() == state.player();
                if ("currently_hostile_to_player".equals(threatFilter) && !activelyTargetingPlayer) {
                    continue;
                }
                var observed = observeLiveEntity(state, capture, entity, maxDistance, false);
                if (observed == null || current.contains(entity.getUUID())) {
                    continue;
                }
                if (results.size() >= maxResults) {
                    truncated = true;
                    break;
                }
                current.add(entity.getUUID());
                var mapped = entityMap(observed, state.clientTick(), true, origin.position());
                mapped.put("threat_relation", activelyTargetingPlayer
                        ? "currently_hostile_to_player"
                        : "unknown");
                results.add(mapped);
            }
        }

        if (source.includesMemory()
                && !"currently_hostile_to_player".equals(threatFilter)) {
            for (WorldMemory.EntityObservation remembered : retainedEntitiesInCurrentDimension(state)) {
                if (current.contains(remembered.internalUuid()) || !typeFilter.test(remembered.type())) {
                    continue;
                }
                if (entityDistanceSquared(remembered, origin.position()) > maxDistance * maxDistance) {
                    continue;
                }
                if (results.size() >= maxResults) {
                    truncated = true;
                    break;
                }
                var mapped = entityMap(remembered, state.clientTick(), false, origin.position());
                mapped.put("threat_relation", "unknown");
                results.add(mapped);
            }
        }

        long currentCount = results.stream()
                .filter(result -> knowledgeCurrentness(result).equals("current"))
                .count();
        var response = new LinkedHashMap<String, Object>();
        response.put("source", source.wireName());
        response.put("max_distance", maxDistance);
        response.put("max_results", maxResults);
        response.put("relative_to", origin.basis());
        response.put("results", List.copyOf(results));
        response.put("coverage", Map.of(
                "current", currentCount,
                "last_known", results.size() - currentCount));
        response.put("threat_relation_basis", "currently_hostile_to_player".equals(threatFilter)
                ? "client_synced_direct_target_only"
                : "not_filtered");
        response.put("truncated", truncated);
        return response;
    }

    private WorldMemory.EntityObservation observeLiveEntity(
            ClientState state,
            Capture capture,
            Entity entity,
            double maxDistance,
            boolean crosshairConfirmed) {
        if (capture.entities().containsKey(entity.getUUID())) {
            return capture.entities().get(entity.getUUID());
        }
        if (!crosshairConfirmed && !visibility.entity(state.minecraft(), entity, maxDistance).visible()) {
            return null;
        }
        Vec3 motion = entity.getDeltaMovement();
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        WorldMemory.EntityObservation observed = memory.rememberEntity(
                entity.getUUID(),
                type,
                entity.getX(), entity.getY(), entity.getZ(),
                motion.x, motion.y, motion.z,
                entity instanceof Player,
                entity.isVehicle(),
                entity.isPassenger(),
                state.dimension(),
                state.clientTick());
        capture.entities().put(entity.getUUID(), observed);
        return observed;
    }

    private Map<String, Object> target(ClientState state, Capture capture) {
        HitResult hit = state.minecraft().hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return Map.of("kind", "miss");
        }
        Vec3 eye = state.player().getEyePosition();
        if (hit instanceof BlockHitResult blockHit) {
            BlockSample sample = sampleBlock(state, capture, blockHit.getBlockPos(), BlockSource.LIVE);
            var result = new LinkedHashMap<String, Object>();
            result.put("kind", "block");
            result.put("face", blockHit.getDirection().getSerializedName());
            result.put("hit_position", vectorMap(blockHit.getLocation()));
            result.put("distance", eye.distanceTo(blockHit.getLocation()));
            result.put("inside", blockHit.isInside());
            result.put("within_reach", state.player().isWithinBlockInteractionRange(blockHit.getBlockPos(), 0.0));
            result.put("observation", sample.observation().toMap(
                    state.clientTick(), true, sample.visibleFaces(), sample.withinReach()));
            return result;
        }
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            WorldMemory.EntityObservation observed = observeLiveEntity(
                    state, capture, entity, MAX_ENTITY_DISTANCE, true);
            var result = new LinkedHashMap<String, Object>();
            result.put("kind", "entity");
            result.put("hit_position", vectorMap(entityHit.getLocation()));
            result.put("distance", eye.distanceTo(entityHit.getLocation()));
            result.put("within_reach", state.player().isWithinEntityInteractionRange(entity, 0.0));
            result.put("observation", observed.toMap(state.clientTick(), true));
            return result;
        }
        return Map.of("kind", "miss");
    }

    private Map<String, Object> player(ClientState state) {
        LocalPlayer player = state.player();
        Vec3 motion = player.getDeltaMovement();
        var effects = player.getActiveEffects().stream()
                .map(effect -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("effect", effect.getEffect().getRegisteredName());
                    value.put("amplifier", effect.getAmplifier());
                    value.put("duration_ticks", effect.getDuration());
                    value.put("ambient", effect.isAmbient());
                    value.put("visible", effect.isVisible());
                    return Map.<String, Object>copyOf(value);
                })
                .sorted(Comparator.comparing(effect -> (String) effect.get("effect")))
                .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("position", vectorMap(player.position()));
        result.put("rotation", Map.of("yaw", player.getYRot(), "pitch", player.getXRot()));
        result.put("velocity", vectorMap(motion));
        result.put("health", player.getHealth());
        result.put("max_health", player.getMaxHealth());
        result.put("absorption", player.getAbsorptionAmount());
        result.put("hunger", player.getFoodData().getFoodLevel());
        result.put("saturation", player.getFoodData().getSaturationLevel());
        result.put("on_ground", player.onGround());
        result.put("alive", player.isAlive());
        result.put("using_item", player.isUsingItem());
        result.put("block_interaction_range", player.blockInteractionRange());
        result.put("entity_interaction_range", player.entityInteractionRange());
        result.put("selected_slot", player.getInventory().getSelectedSlot());
        result.put("effects", effects);
        return result;
    }

    private Map<String, Object> inventory(ClientState state) {
        Inventory inventory = state.player().getInventory();
        int selected = inventory.getSelectedSlot();
        var slots = new ArrayList<Map<String, Object>>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            var value = new LinkedHashMap<String, Object>();
            value.put("slot", slot);
            value.put("slot_role", slotRole(slot));
            value.put("selected", slot == selected);
            value.put("empty", stack.isEmpty());
            if (!stack.isEmpty()) {
                value.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                value.put("count", stack.getCount());
                value.put("max_stack_size", stack.getMaxStackSize());
                if (stack.isDamageableItem()) {
                    value.put("damage", stack.getDamageValue());
                    value.put("max_damage", stack.getMaxDamage());
                    value.put("durability_remaining", Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
                }
                Block block = Block.byItem(stack.getItem());
                if (block != Blocks.AIR) {
                    value.put("places_block", BuiltInRegistries.BLOCK.getKey(block).toString());
                }
                value.put("tags", stack.getItem().builtInRegistryHolder().tags()
                        .map(tag -> tag.location().toString())
                        .sorted()
                        .toList());
                value.put("enchantments", stack.getEnchantments().entrySet().stream()
                        .map(entry -> Map.<String, Object>of(
                                "enchantment", entry.getKey().getRegisteredName(),
                                "level", entry.getIntValue()))
                        .sorted(Comparator.comparing(entry -> (String) entry.get("enchantment")))
                        .toList());
            }
            slots.add(Map.copyOf(value));
        }
        return Map.of(
                "selected_slot", selected,
                "size", inventory.getContainerSize(),
                "slots", List.copyOf(slots));
    }

    private Map<String, Object> world(ClientState state) {
        BlockPos position = state.player().blockPosition();
        FluidState fluid = state.level().getFluidState(position);
        var camera = state.minecraft().gameRenderer.mainCamera();
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", state.dimension());
        result.put("world_session_id", state.sessionId().toString());
        result.put("overworld_clock_time", state.level().getOverworldClockTime());
        result.put("default_clock_time", state.level().getDefaultClockTime());
        result.put("raining", state.level().isRaining());
        result.put("thundering", state.level().isThundering());
        result.put("rain_level", state.level().getRainLevel(1.0F));
        result.put("thunder_level", state.level().getThunderLevel(1.0F));
        result.put("biome", state.level().getBiome(position).getRegisteredName());
        result.put("block_light", state.level().getBrightness(LightLayer.BLOCK, position));
        result.put("sky_light", state.level().getBrightness(LightLayer.SKY, position));
        result.put("fluid", fluid.isEmpty()
                ? null
                : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString());
        result.put("camera", camera != null && camera.isInitialized()
                ? Map.of(
                        "position", vectorMap(camera.position()),
                        "yaw", camera.yRot(),
                        "pitch", camera.xRot(),
                        "fov", camera.getFov(),
                        "detached", camera.isDetached())
                : Map.of("available", false));
        return result;
    }

    private Map<String, Object> screen(ClientState state) {
        Screen screen = state.minecraft().gui.screen();
        var result = new LinkedHashMap<String, Object>();
        result.put("present", screen != null);
        result.put("screen_type", screen == null ? "none" : screen.getClass().getName());
        result.put("screen_kind", screen == null
                ? "none"
                : screen instanceof ChatScreen
                        ? "chat"
                        : screen instanceof AbstractContainerScreen<?>
                                ? "container"
                                : "generic");
        result.put("automation_owned", false);
        result.put("chat_content_included", false);
        if (screen != null) {
            result.put("pause_screen", screen.isPauseScreen());
            result.put("in_game_ui", screen.isInGameUi());
        }
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            var menu = containerScreen.getMenu();
            var container = new LinkedHashMap<String, Object>();
            container.put("container_id", menu.containerId);
            container.put("revision", menu.getStateId());
            container.put("slot_count", menu.slots.size());
            if (menu.getType() != null) {
                container.put("menu_type", BuiltInRegistries.MENU.getKey(menu.getType()).toString());
            }
            result.put("container", container);
        }
        return result;
    }

    static BlockStateView blockStateView(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateView(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    @SuppressWarnings("deprecation") // The no-context flag is the only API for observation-time replaceability.
    private static ObservedContext observedContext(
            ClientLevel level, BlockPos position, BlockState state) {
        FluidState fluid = state.getFluidState();
        var sturdyFaces = new ArrayList<String>();
        for (Direction face : Direction.values()) {
            if (state.isFaceSturdy(level, position, face)) {
                sturdyFaces.add(face.getSerializedName());
            }
        }
        return new ObservedContext(
                level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position),
                fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
                fluid.isEmpty() ? null : fluid.isSource(),
                fluid.isEmpty() ? null : fluid.getAmount(),
                state.canBeReplaced(),
                state.getCollisionShape(level, position).isEmpty(),
                sturdyFaces);
    }

    private ClientState requireClientState(Minecraft minecraft, long clientTick) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Minecraft observations require the client thread");
        }
        if (clientTick < 0) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || minecraft.gameMode == null || minecraft.getConnection() == null) {
            throw new ObservationUnavailableException("world_not_ready", "Client world is not ready");
        }
        UUID sessionId = memory.sessionId();
        if (sessionId == null) {
            throw new ObservationUnavailableException("world_session_unavailable", "World session is not active");
        }
        String dimension = level.dimension().identifier().toString();
        return new ClientState(minecraft, level, player, sessionId, dimension, clientTick);
    }

    private List<ObservedBlock> retainedBlocksInCurrentDimension(ClientState state) {
        int retained = memory.stats().retainedBlocks();
        return memory.retainedBlocks(retained).stream()
                .filter(block -> block.worldSessionId().equals(state.sessionId()))
                .filter(block -> block.position().dimension().equals(state.dimension()))
                .toList();
    }

    private List<WorldMemory.EntityObservation> retainedEntitiesInCurrentDimension(ClientState state) {
        int retained = memory.stats().retainedEntities();
        return memory.retainedEntities(retained).stream()
                .filter(entity -> entity.worldSessionId().equals(state.sessionId()))
                .filter(entity -> entity.dimension().equals(state.dimension()))
                .toList();
    }

    private static Map<String, Object> blockQueryMap(
            String kind,
            BlockSource source,
            List<BlockSample> samples,
            int requestedOrConsidered,
            boolean truncated) {
        var counts = new LinkedHashMap<String, Integer>();
        for (BlockOutcome outcome : BlockOutcome.values()) {
            counts.put(outcome.wireName(), 0);
        }
        for (BlockSample sample : samples) {
            counts.compute(sample.outcome().wireName(), (ignored, count) -> count + 1);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("source", source.wireName());
        result.put("query_kind", kind);
        result.put("results", samples.stream().map(sample -> sample.toMap(sample.currentTick())).toList());
        result.put("coverage", Map.of(
                "requested_or_considered", requestedOrConsidered,
                "current", counts.get("current"),
                "last_known", counts.get("last_known"),
                "not_currently_observable", counts.get("not_currently_observable"),
                "unknown", counts.get("unknown")));
        result.put("truncated", truncated);
        return result;
    }

    private static String knowledgeCurrentness(Map<String, Object> entity) {
        Object raw = entity.get("knowledge");
        if (!(raw instanceof Map<?, ?> knowledge)) {
            return "unknown";
        }
        return Objects.toString(knowledge.get("currentness"), "unknown");
    }

    private static String slotRole(int slot) {
        if (slot >= 0 && slot < Inventory.getSelectionSize()) {
            return "hotbar";
        }
        var equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
        if (equipment != null) {
            return "equipment_" + equipment.getSerializedName();
        }
        return "main";
    }

    private static Map<String, Object> vectorMap(Vec3 vector) {
        return Map.of("x", vector.x, "y", vector.y, "z", vector.z);
    }

    private static ObservationOrigin observationOrigin(ClientState state) {
        var camera = state.minecraft().gameRenderer.mainCamera();
        return camera != null && camera.isInitialized()
                ? new ObservationOrigin(camera.position(), "camera")
                : new ObservationOrigin(state.player().getEyePosition(), "player_eye");
    }

    private static LinkedHashMap<String, Object> entityMap(
            WorldMemory.EntityObservation observation,
            long currentTick,
            boolean current,
            Vec3 origin) {
        var mapped = new LinkedHashMap<String, Object>(observation.toMap(currentTick, current));
        var relative = new Vec3(
                observation.x() - origin.x,
                observation.y() - origin.y,
                observation.z() - origin.z);
        mapped.put("relative_position", vectorMap(relative));
        mapped.put("distance", relative.length());
        return mapped;
    }

    private static double entityDistanceSquared(
            WorldMemory.EntityObservation observation, Vec3 origin) {
        double x = observation.x() - origin.x;
        double y = observation.y() - origin.y;
        double z = observation.z() - origin.z;
        return x * x + y * y + z * z;
    }

    private static String unknownReason(WorldMemory.Stats stats) {
        return stats.evictedBlocks() == 0 ? "never_observed" : "unavailable";
    }

    private static List<String> parseScopes(Object raw) {
        if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("scopes must be a non-empty array");
        }
        var scopes = new LinkedHashSet<String>();
        for (Object value : collection) {
            String scope = requiredString(value, "scope");
            if (!SUPPORTED_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("unsupported snapshot scope: " + scope);
            }
            if (!scopes.add(scope)) {
                throw new IllegalArgumentException("duplicate snapshot scope: " + scope);
            }
        }
        return List.copyOf(scopes);
    }

    private static Map<?, ?> optionalMap(Object raw, String name) {
        return raw == null ? Map.of() : requiredMap(raw, name);
    }

    private static Map<?, ?> requiredMap(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return map;
    }

    private static String optionalString(Object raw, String defaultValue) {
        return raw == null ? defaultValue : requiredString(raw, "value");
    }

    private static String requiredString(Object raw, String name) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return value;
    }

    private static int requiredInt(Object raw, String name) {
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double decimal = number.doubleValue();
        long value = number.longValue();
        if (!Double.isFinite(decimal) || decimal != value || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer");
        }
        return (int) value;
    }

    private static int boundedInt(Object raw, int defaultValue, int min, int max, String name) {
        int value = raw == null ? defaultValue : requiredInt(raw, name);
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        }
        return value;
    }

    private static double boundedDouble(Object raw, double defaultValue, double min, double max, String name) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        }
        return value;
    }

    public enum BlockSource {
        LIVE("live"),
        MEMORY("memory"),
        LIVE_AND_MEMORY("live_and_memory");

        private final String wireName;

        BlockSource(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        boolean includesLive() {
            return this != MEMORY;
        }

        boolean includesMemory() {
            return this != LIVE;
        }

        public static BlockSource parse(String raw) {
            for (BlockSource source : values()) {
                if (source.wireName.equals(raw)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("unsupported observation source: " + raw);
        }
    }

    public enum BlockOutcome {
        CURRENT("current"),
        LAST_KNOWN("last_known"),
        NOT_CURRENTLY_OBSERVABLE("not_currently_observable"),
        UNKNOWN("unknown");

        private final String wireName;

        BlockOutcome(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record BlockSample(
            BlockOutcome outcome,
            BlockPosition position,
            ObservedBlock observation,
            List<String> visibleFaces,
            boolean withinReach,
            String reason,
            long currentTick) {
        public BlockSample {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(position, "position");
            visibleFaces = List.copyOf(visibleFaces);
            if ((outcome == BlockOutcome.CURRENT || outcome == BlockOutcome.LAST_KNOWN) != (observation != null)) {
                throw new IllegalArgumentException("known outcomes require exactly one observation");
            }
            if ((outcome == BlockOutcome.NOT_CURRENTLY_OBSERVABLE || outcome == BlockOutcome.UNKNOWN)
                    != (reason != null)) {
                throw new IllegalArgumentException("non-value outcomes require exactly one reason");
            }
        }

        static BlockSample current(
                ObservedBlock observation, List<String> visibleFaces, boolean withinReach, long currentTick) {
            return new BlockSample(
                    BlockOutcome.CURRENT,
                    observation.position(),
                    observation,
                    visibleFaces,
                    withinReach,
                    null,
                    currentTick);
        }

        static BlockSample lastKnown(ObservedBlock observation, long currentTick) {
            return new BlockSample(
                    BlockOutcome.LAST_KNOWN,
                    observation.position(),
                    observation,
                    List.of(),
                    false,
                    null,
                    currentTick);
        }

        static BlockSample notCurrentlyObservable(BlockPosition position, String reason) {
            return new BlockSample(
                    BlockOutcome.NOT_CURRENTLY_OBSERVABLE,
                    position,
                    null,
                    List.of(),
                    false,
                    reason,
                    0);
        }

        static BlockSample unknown(BlockPosition position, String reason) {
            return new BlockSample(
                    BlockOutcome.UNKNOWN,
                    position,
                    null,
                    List.of(),
                    false,
                    reason,
                    0);
        }

        public Map<String, Object> toMap(long clientTick) {
            var result = new LinkedHashMap<String, Object>();
            result.put("outcome", outcome.wireName());
            result.put("position", position.toMap());
            if (observation != null) {
                result.put("actual", observation.toMap(
                        clientTick,
                        outcome == BlockOutcome.CURRENT,
                        visibleFaces,
                        withinReach));
            } else {
                result.put("reason", reason);
            }
            return result;
        }
    }

    public static final class ObservationUnavailableException extends IllegalStateException {
        private final String code;

        public ObservationUnavailableException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private static final class EntityFilter {
        private final Set<String> ids;
        private final List<TagKey<EntityType<?>>> tags;

        private EntityFilter(Set<String> ids, List<TagKey<EntityType<?>>> tags) {
            this.ids = Set.copyOf(ids);
            this.tags = List.copyOf(tags);
        }

        static EntityFilter parse(Object raw) {
            if (raw == null) {
                return new EntityFilter(Set.of(), List.of());
            }
            if (!(raw instanceof Collection<?> collection)) {
                throw new IllegalArgumentException("visible_entities.types must be an array");
            }
            var ids = new LinkedHashSet<String>();
            var tags = new ArrayList<TagKey<EntityType<?>>>();
            for (Object item : collection) {
                String value = requiredString(item, "visible_entities type");
                boolean tag = value.startsWith("#");
                String identifierText = tag ? value.substring(1) : value;
                Identifier identifier = Identifier.tryParse(identifierText);
                if (identifier == null) {
                    throw new IllegalArgumentException("invalid entity identifier: " + value);
                }
                if (tag) {
                    var key = TagKey.create(Registries.ENTITY_TYPE, identifier);
                    boolean known = BuiltInRegistries.ENTITY_TYPE.getTags()
                            .anyMatch(named -> named.key().equals(key));
                    if (!known) {
                        throw new IllegalArgumentException("unknown entity tag: " + value);
                    }
                    tags.add(key);
                } else {
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) {
                        throw new IllegalArgumentException("unknown entity type: " + value);
                    }
                    ids.add(identifier.toString());
                }
            }
            return new EntityFilter(ids, tags);
        }

        boolean test(EntityType<?> type) {
            if (ids.isEmpty() && tags.isEmpty()) {
                return true;
            }
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            return ids.contains(id) || tags.stream().anyMatch(type.builtInRegistryHolder()::is);
        }

        boolean test(String type) {
            // A last-known entity has no current holder against which to evaluate a possibly
            // reloaded tag, so an ID filter remains valid while tag-only membership is unknown.
            return ids.isEmpty() && tags.isEmpty() || ids.contains(type);
        }
    }

    private record ClientState(
            Minecraft minecraft,
            ClientLevel level,
            LocalPlayer player,
            UUID sessionId,
            String dimension,
            long clientTick) {}

    private record ObservationOrigin(Vec3 position, String basis) {}

    private record LiveBlock(
            ObservedBlock observation,
            List<String> visibleFaces,
            boolean withinReach,
            String hiddenReason) {
        LiveBlock {
            visibleFaces = List.copyOf(visibleFaces);
            if ((observation == null) != (hiddenReason != null)) {
                throw new IllegalArgumentException("a live block is either observed or hidden");
            }
        }

        static LiveBlock hidden(String reason) {
            return new LiveBlock(null, List.of(), false, reason);
        }
    }

    private record Capture(
            Map<BlockPos, LiveBlock> blocks,
            Map<UUID, WorldMemory.EntityObservation> entities) {
        Capture() {
            this(new HashMap<>(), new HashMap<>());
        }
    }
}
