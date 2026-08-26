package dev.aodaruma.craftagent.observation;

import dev.aodaruma.craftagent.CraftAgentMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Typed, allowlisted Creative world edits with bounded in-memory undo/redo.
 *
 * <p>This is deliberately not a command-string bridge. Mutations run only on the integrated
 * server thread, never accept selectors or NBT, and reject block entities before and after an
 * edit. History is optimistic: undo/redo first verifies that the affected world state still
 * matches the transaction boundary.</p>
 */
public final class CreativeWorldEdit {
    public static final String CAPABILITY = "creative_world_edit";
    public static final int MAX_BLOCKS = 4_096;
    public static final int MAX_AXIS = 64;
    public static final int MAX_ENTITIES = 16;
    public static final Duration JOB_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration HISTORY_TTL = Duration.ofMinutes(30);
    public static final int HISTORY_LIMIT = 32;

    private static final int MUTATION_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final int RETAINED_JOBS = 32;
    private static final Set<String> SAFE_ENTITY_TYPES = Set.of(
            "minecraft:armor_stand",
            "minecraft:chicken",
            "minecraft:cow",
            "minecraft:iron_golem",
            "minecraft:pig",
            "minecraft:sheep",
            "minecraft:villager");
    private static final Set<String> DENIED_BLOCKS = Set.of(
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:tnt");
    private static final Set<String> STATUS_KEYS = Set.of("operation", "job_id");
    private static final Set<String> HISTORY_KEYS = Set.of("operation");
    private static final Set<String> SET_BLOCK_KEYS =
            Set.of("operation", "position", "state", "idempotency_key");
    private static final Set<String> FILL_KEYS =
            Set.of("operation", "region", "state", "idempotency_key");
    private static final Set<String> SUMMON_KEYS =
            Set.of("operation", "dimension", "entities", "idempotency_key");
    private static final Set<String> HISTORY_MUTATION_KEYS =
            Set.of("operation", "expected_transaction_id", "idempotency_key");
    private static final Set<String> DIMENSION_POSITION_KEYS = Set.of("dimension", "x", "y", "z");
    private static final Set<String> POSITION_KEYS = Set.of("x", "y", "z");
    private static final Set<String> REGION_KEYS = Set.of("dimension", "min", "max");
    private static final Set<String> STATE_KEYS = Set.of("block", "properties");
    private static final Set<String> ENTITY_KEYS = Set.of("type", "position", "yaw", "pitch");

    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<IdempotencyIdentity, Job> idempotency = new LinkedHashMap<>();
    private final Deque<Transaction> undo = new ArrayDeque<>();
    private final Deque<Transaction> redo = new ArrayDeque<>();
    private UUID historySessionId;
    private volatile Job activeJob;

    /** Admits one typed mutation from the Minecraft client thread and returns immediately. */
    public synchronized Map<String, Object> start(
            Minecraft minecraft,
            long clientTick,
            UUID worldSessionId,
            Map<String, Object> arguments,
            BooleanSupplier remainsArmed,
            Runnable lockOnRollbackFailure) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(remainsArmed, "remainsArmed");
        Objects.requireNonNull(lockOnRollbackFailure, "lockOnRollbackFailure");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Creative edit admission requires the client thread");
        }
        if (!CreativeRegionCapture.isConfirmedCreative(minecraft)) {
            throw new IllegalStateException(
                    "Creative edits require private local Creative with commands enabled");
        }
        if (clientTick < 0) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }
        ensureSession(worldSessionId);
        pruneHistory(System.nanoTime());

        Request request = parseRequest(arguments);
        String currentDimension = Objects.requireNonNull(minecraft.level, "level")
                .dimension().identifier().toString();
        if (request.dimension() != null && !currentDimension.equals(request.dimension())) {
            throw new IllegalArgumentException("edit dimension must equal the current dimension");
        }
        UUID idempotencyKey = uuid(arguments.get("idempotency_key"), "idempotency_key");
        var identity = new IdempotencyIdentity(worldSessionId, idempotencyKey);
        Job replay = idempotency.get(identity);
        if (replay != null) {
            if (!replay.request.equals(request)) {
                throw new EditIdempotencyConflictException();
            }
            return replay.toMap(true, historySnapshot());
        }
        if (activeJob != null) {
            throw new EditBusyException();
        }

        MinecraftServer server = Objects.requireNonNull(
                minecraft.getSingleplayerServer(), "integratedServer");
        long startedAtNanos = System.nanoTime();
        var job = new Job(
                UUID.randomUUID(), worldSessionId, idempotencyKey, request, clientTick, server,
                minecraft.player.getUUID(), startedAtNanos, remainsArmed, lockOnRollbackFailure);
        pruneRetainedJobs();
        jobs.put(job.jobId, job);
        idempotency.put(identity, job);
        activeJob = job;
        CraftAgentMod.LOGGER.info(
                "Creative edit admitted job={} operation={} requested_changes={} session={}",
                job.jobId, request.operation.wireName, request.requestedChanges(), worldSessionId);
        return job.toMap(false, historySnapshot());
    }

    /** Returns retained job state; status does not itself mutate the world. */
    public synchronized Map<String, Object> status(
            UUID worldSessionId, Map<String, Object> arguments) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(arguments, "arguments");
        requireExactKeys(arguments, "creative edit status", STATUS_KEYS);
        if (!"status".equals(string(arguments.get("operation"), "operation"))) {
            throw new IllegalArgumentException("operation must be status");
        }
        ensureSession(worldSessionId);
        pruneHistory(System.nanoTime());
        Job job = jobs.get(uuid(arguments.get("job_id"), "job_id"));
        if (job == null || !job.worldSessionId.equals(worldSessionId)) {
            throw new EditNotFoundException();
        }
        if (!job.state.terminal && timeoutElapsed(
                job.startedAtNanos, JOB_TIMEOUT, System.nanoTime())) {
            cancel(job, "timeout", "Creative edit exceeded its 10 second deadline");
        }
        return job.toMap(false, historySnapshot());
    }

    /** Returns only bounded transaction metadata, never world state or entity UUIDs. */
    public synchronized Map<String, Object> history(
            UUID worldSessionId, Map<String, Object> arguments) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(arguments, "arguments");
        requireExactKeys(arguments, "creative edit history", HISTORY_KEYS);
        if (!"history".equals(string(arguments.get("operation"), "operation"))) {
            throw new IllegalArgumentException("operation must be history");
        }
        ensureSession(worldSessionId);
        pruneHistory(System.nanoTime());
        return Map.of("response", "history", "history", historySnapshot());
    }

    /** Executes one whole bounded transaction from {@code ServerTickEvent.Post}. */
    public synchronized void onServerPostTick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Creative edits require the logical server thread");
        }
        Job job = activeJob;
        if (job == null) {
            return;
        }
        if (job.state.terminal) {
            activeJob = null;
            return;
        }
        if (job.server != server) {
            return;
        }
        try {
            job.checkpoint();
            ServerContext context = verifyServerAuthority(job);
            pruneHistory(System.nanoTime());
            apply(job, context.level);
            job.state = State.SUCCEEDED;
            CraftAgentMod.LOGGER.info(
                    "Creative edit succeeded job={} operation={} applied_changes={} transaction={}",
                    job.jobId, job.request.operation.wireName, job.appliedChanges, job.transactionId);
        }
        catch (EditCancelled cancelled) {
            cancel(job, cancelled.code, cancelled.getMessage());
        }
        catch (EditRejected rejected) {
            fail(job, rejected.code, rejected.getMessage());
        }
        catch (RuntimeException | LinkageError failure) {
            CraftAgentMod.LOGGER.error("Creative edit failed job={}", job.jobId, failure);
            fail(job, "edit_failed", "Creative edit failed on the integrated server");
        }
        finally {
            job.releaseExecutionReferences();
            activeJob = null;
        }
    }

    public boolean cancelActive(String reason) {
        Job job = activeJob;
        if (job == null) {
            return true;
        }
        job.requestCancel("cancelled", sanitize(reason));
        return true;
    }

    public boolean hasActiveJob() {
        return activeJob != null;
    }

    public synchronized void onServerStopping(MinecraftServer server) {
        if (activeJob != null && activeJob.server == server) {
            cancel(activeJob, "server_stopping", "Integrated server stopped before the Creative edit");
            activeJob = null;
        }
        clearRetainedSessionState();
        historySessionId = null;
    }

    /** Client lifecycle fence; server authority is independently checked before every transaction. */
    public synchronized void fenceClient(Minecraft minecraft, UUID worldSessionId) {
        if (historySessionId != null && !historySessionId.equals(worldSessionId)) {
            if (activeJob != null) {
                cancel(activeJob, "world_session_changed", "World session changed before the Creative edit");
                activeJob = null;
            }
            clearRetainedSessionState();
            historySessionId = worldSessionId;
        }
        else if (activeJob != null && !CreativeRegionCapture.isConfirmedCreative(minecraft)) {
            cancel(activeJob, "unsafe_state", "Creative authority was revoked before the edit");
            activeJob = null;
        }
    }

    static Request parseRequest(Map<String, Object> arguments) {
        String operation = string(arguments.get("operation"), "operation");
        return switch (operation) {
            case "set_block" -> parseSetBlock(arguments);
            case "fill" -> parseFill(arguments);
            case "summon_entities" -> parseSummon(arguments);
            case "undo" -> parseHistoryMutation(arguments, Operation.UNDO);
            case "redo" -> parseHistoryMutation(arguments, Operation.REDO);
            default -> throw new IllegalArgumentException(
                    "operation must be set_block, fill, summon_entities, undo, or redo");
        };
    }

    private static Request parseSetBlock(Map<String, Object> arguments) {
        requireExactKeys(arguments, "creative set_block", SET_BLOCK_KEYS);
        Map<?, ?> input = requiredMap(arguments.get("position"), "position");
        requireExactKeys(input, "position", DIMENSION_POSITION_KEYS);
        String dimension = registryId(input.get("dimension"), "position.dimension");
        BlockPos position = parsePosition(input, "position");
        return Request.blocks(Operation.SET_BLOCK, dimension, List.of(position),
                parseFullState(requiredMap(arguments.get("state"), "state"), "state"));
    }

    private static Request parseFill(Map<String, Object> arguments) {
        requireExactKeys(arguments, "creative fill", FILL_KEYS);
        Map<?, ?> input = requiredMap(arguments.get("region"), "region");
        requireExactKeys(input, "region", REGION_KEYS);
        String dimension = registryId(input.get("dimension"), "region.dimension");
        BlockPos min = parsePosition(requiredMap(input.get("min"), "region.min"), "region.min");
        BlockPos max = parsePosition(requiredMap(input.get("max"), "region.max"), "region.max");
        List<BlockPos> positions = regionPositions(min, max);
        return Request.blocks(Operation.FILL, dimension, positions,
                parseFullState(requiredMap(arguments.get("state"), "state"), "state"));
    }

    private static Request parseSummon(Map<String, Object> arguments) {
        requireExactKeys(arguments, "creative summon_entities", SUMMON_KEYS);
        String dimension = registryId(arguments.get("dimension"), "dimension");
        Object rawEntities = arguments.get("entities");
        if (!(rawEntities instanceof List<?> entities)
                || entities.isEmpty() || entities.size() > MAX_ENTITIES) {
            throw new IllegalArgumentException("entities must contain 1..16 entries");
        }
        var specs = new ArrayList<EntitySpec>(entities.size());
        for (int index = 0; index < entities.size(); index++) {
            String path = "entities[" + index + "]";
            Map<?, ?> entity = requiredMap(entities.get(index), path);
            requireExactKeys(entity, path, ENTITY_KEYS);
            String type = registryId(entity.get("type"), path + ".type");
            if (!SAFE_ENTITY_TYPES.contains(type)) {
                throw new IllegalArgumentException(path + ".type is not in the safe entity allowlist");
            }
            Map<?, ?> position = requiredMap(entity.get("position"), path + ".position");
            requireExactKeys(position, path + ".position", POSITION_KEYS);
            specs.add(new EntitySpec(
                    type,
                    decimal(position.get("x"), path + ".position.x", -30_000_000, 29_999_999),
                    decimal(position.get("y"), path + ".position.y", -2_048, 2_047),
                    decimal(position.get("z"), path + ".position.z", -30_000_000, 29_999_999),
                    (float) decimal(entity.get("yaw"), path + ".yaw", -180, 180),
                    (float) decimal(entity.get("pitch"), path + ".pitch", -90, 90)));
        }
        return Request.entities(dimension, specs);
    }

    private static Request parseHistoryMutation(
            Map<String, Object> arguments, Operation operation) {
        requireExactKeys(arguments, "creative " + operation.wireName, HISTORY_MUTATION_KEYS);
        return Request.history(operation, uuid(
                arguments.get("expected_transaction_id"), "expected_transaction_id"));
    }

    private void apply(Job job, ServerLevel level) {
        switch (job.request.operation) {
            case SET_BLOCK, FILL -> applyNewBlocks(job, level);
            case SUMMON_ENTITIES -> applyNewEntities(job, level);
            case UNDO -> applyUndo(job, level);
            case REDO -> applyRedo(job, level);
        }
    }

    private void applyNewBlocks(Job job, ServerLevel level) {
        validateDimensionAndHeight(level, job.request.dimension, job.request.positions);
        requireSafeState(job.request.state, "target");
        var changes = new ArrayList<BlockChange>();
        for (BlockPos position : job.request.positions) {
            job.checkpoint();
            requireLoaded(level, position);
            BlockState before = level.getBlockState(position);
            requireNoBlockEntity(level, position, before, "source");
            requireSafeState(before, "source");
            if (!before.equals(job.request.state)) {
                changes.add(new BlockChange(position.immutable(), before, job.request.state));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        applyBlockStates(job, level, changes, true);
        Transaction transaction = Transaction.blocks(
                UUID.randomUUID(), job.worldSessionId, job.request.dimension,
                System.nanoTime(), changes);
        pushNew(transaction);
        job.transactionId = transaction.id;
        job.appliedChanges = changes.size();
    }

    private void applyNewEntities(Job job, ServerLevel level) {
        if (!level.dimension().identifier().toString().equals(job.request.dimension)) {
            throw rejected("unsafe_state", "Integrated server dimension changed");
        }
        for (EntitySpec spec : job.request.entities) {
            job.checkpoint();
            spec.validate(level);
        }
        var spawned = spawnEntities(job, level, job.request.entities);
        Transaction transaction = Transaction.entities(
                UUID.randomUUID(), job.worldSessionId, job.request.dimension,
                System.nanoTime(), job.request.entities, spawned);
        pushNew(transaction);
        job.transactionId = transaction.id;
        job.appliedChanges = spawned.size();
    }

    private void applyUndo(Job job, ServerLevel level) {
        Transaction transaction = requireHead(undo, job.request.expectedTransactionId, "undo");
        transaction.undo(job, level);
        undo.removeFirst();
        redo.addFirst(transaction);
        job.transactionId = transaction.id;
        job.appliedChanges = transaction.changeCount();
    }

    private void applyRedo(Job job, ServerLevel level) {
        Transaction transaction = requireHead(redo, job.request.expectedTransactionId, "redo");
        transaction.redo(job, level);
        redo.removeFirst();
        undo.addFirst(transaction);
        job.transactionId = transaction.id;
        job.appliedChanges = transaction.changeCount();
    }

    private static Transaction requireHead(
            Deque<Transaction> stack, UUID expected, String operation) {
        Transaction transaction = stack.peekFirst();
        if (transaction == null) {
            throw rejected("history_empty", "No Creative transaction is available to " + operation);
        }
        if (!transaction.id.equals(expected)) {
            throw rejected("history_conflict", "The expected transaction is not the " + operation + " head");
        }
        return transaction;
    }

    private static void applyBlockStates(
            Job job, ServerLevel level, List<BlockChange> changes, boolean after) {
        for (BlockChange change : changes) {
            job.checkpoint();
            requireLoaded(level, change.position);
            BlockState expected = after ? change.before : change.after;
            requireSafeState(after ? change.after : change.before, "target");
            requireNoBlockEntity(level, change.position, expected, "history boundary");
            if (!level.getBlockState(change.position).equals(expected)) {
                throw rejected("divergence", "A block changed after the Creative transaction boundary");
            }
        }
        int applied = 0;
        try {
            for (BlockChange change : changes) {
                job.checkpoint();
                applied++;
                setExact(level, change.position, after ? change.after : change.before);
            }
            for (BlockChange change : changes) {
                job.checkpoint();
                BlockState expected = after ? change.after : change.before;
                requireNoBlockEntity(level, change.position, expected, "result");
                if (!level.getBlockState(change.position).equals(expected)) {
                    throw rejected(
                            "mutation_failed",
                            "Neighbor updates changed a requested complete BlockState");
                }
            }
        }
        catch (RuntimeException | LinkageError failure) {
            boolean rollbackFailed = false;
            for (int index = applied - 1; index >= 0; index--) {
                BlockChange change = changes.get(index);
                try {
                    setExact(level, change.position, after ? change.before : change.after);
                }
                catch (RuntimeException | LinkageError rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    rollbackFailed = true;
                }
            }
            if (rollbackFailed) {
                CraftAgentMod.LOGGER.error(
                        "Creative edit rollback failed job={}", job.jobId, failure);
                job.lockAfterRollbackFailure(failure);
                throw rejected(
                        "rollback_failed",
                        "Creative edit rollback did not restore every affected block");
            }
            throw failure;
        }
    }

    private static void setExact(ServerLevel level, BlockPos position, BlockState state) {
        boolean changed = level.setBlock(position, state, MUTATION_FLAGS);
        if (!changed && !level.getBlockState(position).equals(state)) {
            throw rejected("mutation_failed", "The integrated server rejected a block mutation");
        }
        requireNoBlockEntity(level, position, state, "result");
        if (!level.getBlockState(position).equals(state)) {
            throw rejected("mutation_failed", "The block did not retain the requested complete state");
        }
    }

    private static List<UUID> spawnEntities(Job job, ServerLevel level, List<EntitySpec> specs) {
        var spawned = new ArrayList<UUID>(specs.size());
        try {
            for (EntitySpec spec : specs) {
                job.checkpoint();
                Entity entity = spec.spawn(level);
                spawned.add(entity.getUUID());
            }
            return spawned;
        }
        catch (RuntimeException | LinkageError failure) {
            for (UUID id : spawned) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
            throw failure;
        }
    }

    private ServerContext verifyServerAuthority(Job job) {
        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player == null) {
            throw rejected("unsafe_state", "Integrated server player is unavailable");
        }
        var abilities = player.getAbilities();
        if (!job.server.isSingleplayerOwner(player.nameAndId())
                || !CreativeRegionCapture.serverAccessAllowed(
                        job.server.isSingleplayer() && !job.server.isPublished(),
                        player.gameMode(), abilities.instabuild, abilities.mayBuild,
                        job.server.getWorldData().isAllowCommands(),
                        player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))) {
            throw rejected("unsafe_state", "Integrated server Creative authority is unavailable");
        }
        return new ServerContext(player.level());
    }

    private static void validateDimensionAndHeight(
            ServerLevel level, String dimension, List<BlockPos> positions) {
        if (!level.dimension().identifier().toString().equals(dimension)) {
            throw rejected("unsafe_state", "Integrated server dimension changed");
        }
        for (BlockPos position : positions) {
            if (!level.isInsideBuildHeight(position.getY())) {
                throw rejected("invalid_height", "Creative edit is outside the current build height");
            }
            if (!level.getWorldBorder().isWithinBounds(position)) {
                throw rejected("outside_world_border", "Creative edit is outside the world border");
            }
        }
    }

    private static void requireLoaded(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position)) {
            throw rejected("chunk_not_loaded", "Safe Creative edits do not load or generate chunks");
        }
    }

    private static void requireNoBlockEntity(
            ServerLevel level, BlockPos position, BlockState state, String boundary) {
        if (state.getBlock() instanceof EntityBlock || level.getBlockEntity(position) != null) {
            throw rejected("block_entity_unsupported", boundary + " contains a block entity");
        }
    }

    private static void requireSafeState(BlockState state, String boundary) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getBlock() instanceof EntityBlock) {
            throw rejected("block_entity_unsupported", boundary + " contains a block entity");
        }
        if (DENIED_BLOCKS.contains(id)) {
            throw rejected("unsupported_block", boundary + " block is denied by the safe edit policy");
        }
        if (!state.getFluidState().isEmpty()) {
            throw rejected("unsupported_block", boundary + " fluid states are not undo-safe");
        }
        if (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof BedBlock
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)) {
            throw rejected("unsupported_block", boundary + " multi-cell blocks are not supported");
        }
    }

    private void pushNew(Transaction transaction) {
        undo.addFirst(transaction);
        redo.clear();
        while (undo.size() > HISTORY_LIMIT) {
            undo.removeLast();
        }
    }

    private void ensureSession(UUID worldSessionId) {
        if (historySessionId == null) {
            historySessionId = worldSessionId;
        }
        else if (!historySessionId.equals(worldSessionId)) {
            if (activeJob != null) {
                throw new EditBusyException();
            }
            clearRetainedSessionState();
            historySessionId = worldSessionId;
        }
    }

    private void clearRetainedSessionState() {
        jobs.values().forEach(Job::releaseExecutionReferences);
        jobs.clear();
        idempotency.clear();
        clearHistory();
    }

    private void clearHistory() {
        undo.clear();
        redo.clear();
    }

    private void pruneHistory(long nowNanos) {
        undo.removeIf(transaction -> timeoutElapsed(
                transaction.createdAtNanos, HISTORY_TTL, nowNanos));
        redo.removeIf(transaction -> timeoutElapsed(
                transaction.createdAtNanos, HISTORY_TTL, nowNanos));
    }

    private Map<String, Object> historySnapshot() {
        Transaction undoHead = undo.peekFirst();
        Transaction redoHead = redo.peekFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("can_undo", undoHead != null);
        result.put("can_redo", redoHead != null);
        result.put("undo_depth", undo.size());
        result.put("redo_depth", redo.size());
        result.put("undo_transaction_id", undoHead == null ? null : undoHead.id.toString());
        result.put("redo_transaction_id", redoHead == null ? null : redoHead.id.toString());
        result.put("history_ttl_seconds", HISTORY_TTL.toSeconds());
        return result;
    }

    private void fail(Job job, String code, String message) {
        job.state = State.FAILED;
        job.error = error(code, message);
        job.releaseExecutionReferences();
        CraftAgentMod.LOGGER.warn(
                "Creative edit failed job={} operation={} code={}",
                job.jobId, job.request.operation.wireName, code);
    }

    private static void cancel(Job job, String code, String message) {
        if (job.state.terminal) {
            return;
        }
        job.state = State.CANCELLED;
        job.error = error(code, message);
        job.releaseExecutionReferences();
        CraftAgentMod.LOGGER.info(
                "Creative edit cancelled job={} operation={} code={}",
                job.jobId, job.request.operation.wireName, code);
    }

    private void pruneRetainedJobs() {
        while (jobs.size() >= RETAINED_JOBS) {
            UUID removable = jobs.entrySet().stream()
                    .filter(entry -> entry.getValue().state.terminal)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (removable == null) {
                return;
            }
            Job removed = jobs.remove(removable);
            idempotency.remove(new IdempotencyIdentity(
                    removed.worldSessionId, removed.idempotencyKey));
        }
    }

    private static BlockState parseFullState(Map<?, ?> input, String path) {
        requireExactKeys(input, path, STATE_KEYS);
        String block = registryId(input.get("block"), path + ".block");
        Map<?, ?> rawProperties = requiredMap(input.get("properties"), path + ".properties");
        if (rawProperties.size() > 128) {
            throw new IllegalArgumentException(path + ".properties contains more than 128 entries");
        }
        var properties = new LinkedHashMap<String, String>();
        for (var entry : rawProperties.entrySet()) {
            String name = string(entry.getKey(), path + " property name");
            String value = string(entry.getValue(), path + ".properties." + name);
            if (!name.matches("[a-z0-9_]+") || value.isEmpty() || value.length() > 64) {
                throw new IllegalArgumentException(path + ".properties contains an invalid property");
            }
            properties.put(name, value);
        }
        var validated = BlockPlanStateTransformer.transformFull(
                new BlockStateView(block, properties), new BlockPlan.Transform(0, "none"), path);
        Identifier identifier = Identifier.parse(validated.block());
        Block registered = BuiltInRegistries.BLOCK.get(identifier).orElseThrow().value();
        BlockState state = registered.getStateDefinition().getPossibleStates().stream()
                .filter(candidate -> MinecraftObservationService.blockStateView(candidate).equals(validated))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("validated BlockState could not be resolved"));
        requireSafeState(state, "target");
        return state;
    }

    private static List<BlockPos> regionPositions(BlockPos min, BlockPos max) {
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("region.min must not exceed region.max");
        }
        long sizeX = (long) max.getX() - min.getX() + 1;
        long sizeY = (long) max.getY() - min.getY() + 1;
        long sizeZ = (long) max.getZ() - min.getZ() + 1;
        if (sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS) {
            throw new IllegalArgumentException("fill axes must not exceed 64 blocks");
        }
        long volume = sizeX * sizeY * sizeZ;
        if (volume > MAX_BLOCKS) {
            throw new IllegalArgumentException("fill volume must not exceed 4096 blocks");
        }
        var positions = new ArrayList<BlockPos>((int) volume);
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(positions);
    }

    private static BlockPos parsePosition(Map<?, ?> input, String path) {
        requireExactKeys(input, path, path.equals("position") ? DIMENSION_POSITION_KEYS : POSITION_KEYS);
        return new BlockPos(
                integer(input.get("x"), path + ".x"),
                integer(input.get("y"), path + ".y"),
                integer(input.get("z"), path + ".z"));
    }

    private static Map<?, ?> requiredMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> result)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return result;
    }

    private static void requireExactKeys(Map<?, ?> input, String path, Set<String> expected) {
        var actual = new LinkedHashSet<String>();
        for (Object key : input.keySet()) {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(path + " contains a non-string key");
            }
            actual.add(text);
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(path + " must contain exactly " + expected);
        }
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return text;
    }

    private static String registryId(Object value, String path) {
        String text = string(value, path);
        if (!text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(path + " must be a registry id");
        }
        return text;
    }

    private static UUID uuid(Object value, String path) {
        try {
            return UUID.fromString(string(value, path));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(path + " must be a UUID", invalid);
        }
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        long result = number.longValue();
        if (number.doubleValue() != result || result < -30_000_000L || result > 30_000_000L) {
            throw new IllegalArgumentException(path + " is outside the supported integer range");
        }
        return (int) result;
    }

    private static double decimal(Object value, String path, double minimum, double maximum) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new IllegalArgumentException(path + " is outside the supported numeric range");
        }
        return result;
    }

    static boolean timeoutElapsed(long startedAtNanos, Duration timeout, long nowNanos) {
        long elapsedNanos = nowNanos - startedAtNanos;
        return elapsedNanos < 0 || elapsedNanos >= timeout.toNanos();
    }

    private static String sanitize(String value) {
        String clean = Objects.requireNonNullElse(value, "cancelled")
                .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        return clean.isEmpty() ? "cancelled" : clean.substring(0, Math.min(clean.length(), 160));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", sanitize(message));
    }

    private static EditRejected rejected(String code, String message) {
        return new EditRejected(code, message);
    }

    enum Operation {
        SET_BLOCK("set_block"),
        FILL("fill"),
        SUMMON_ENTITIES("summon_entities"),
        UNDO("undo"),
        REDO("redo");

        private final String wireName;

        Operation(String wireName) {
            this.wireName = wireName;
        }
    }

    static final class Request {
        private final Operation operation;
        private final String dimension;
        private final List<BlockPos> positions;
        private final BlockState state;
        private final List<EntitySpec> entities;
        private final UUID expectedTransactionId;

        private Request(
                Operation operation,
                String dimension,
                List<BlockPos> positions,
                BlockState state,
                List<EntitySpec> entities,
                UUID expectedTransactionId) {
            this.operation = operation;
            this.dimension = dimension;
            this.positions = List.copyOf(positions);
            this.state = state;
            this.entities = List.copyOf(entities);
            this.expectedTransactionId = expectedTransactionId;
        }

        static Request blocks(
                Operation operation, String dimension, List<BlockPos> positions, BlockState state) {
            return new Request(operation, dimension, positions, state, List.of(), null);
        }

        static Request entities(String dimension, List<EntitySpec> entities) {
            return new Request(Operation.SUMMON_ENTITIES, dimension, List.of(), null, entities, null);
        }

        static Request history(Operation operation, UUID expectedTransactionId) {
            return new Request(operation, null, List.of(), null, List.of(), expectedTransactionId);
        }

        String dimension() {
            return dimension;
        }

        int requestedChanges() {
            return operation == Operation.SUMMON_ENTITIES ? entities.size()
                    : operation == Operation.SET_BLOCK || operation == Operation.FILL ? positions.size() : 1;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Request request)) {
                return false;
            }
            return operation == request.operation
                    && Objects.equals(dimension, request.dimension)
                    && positions.equals(request.positions)
                    && Objects.equals(state, request.state)
                    && entities.equals(request.entities)
                    && Objects.equals(expectedTransactionId, request.expectedTransactionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, dimension, positions, state, entities, expectedTransactionId);
        }
    }

    private record EntitySpec(
            String type, double x, double y, double z, float yaw, float pitch) {
        private void validate(ServerLevel level) {
            BlockPos position = BlockPos.containing(x, y, z);
            if (!level.isInsideBuildHeight(position.getY())) {
                throw rejected("invalid_height", "Entity position is outside the current build height");
            }
            if (!level.getWorldBorder().isWithinBounds(position)) {
                throw rejected("outside_world_border", "Entity position is outside the world border");
            }
            requireLoaded(level, position);
            if (!SAFE_ENTITY_TYPES.contains(type)) {
                throw rejected("unsupported_entity", "Entity type is not in the safe allowlist");
            }
        }

        private Entity spawn(ServerLevel level) {
            Identifier identifier = Identifier.parse(type);
            Optional<Holder.Reference<EntityType<?>>> registered =
                    BuiltInRegistries.ENTITY_TYPE.get(identifier);
            if (registered.isEmpty() || !SAFE_ENTITY_TYPES.contains(type)) {
                throw rejected("unsupported_entity", "Entity type is unavailable or not allowlisted");
            }
            Entity entity = registered.orElseThrow().value().spawn(
                    level, BlockPos.containing(x, y, z), EntitySpawnReason.COMMAND);
            if (entity == null) {
                throw rejected("spawn_failed", "The integrated server could not summon an entity");
            }
            entity.setPos(x, y, z);
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            return entity;
        }
    }

    private record BlockChange(BlockPos position, BlockState before, BlockState after) {
    }

    private static final class Transaction {
        private final UUID id;
        private final UUID worldSessionId;
        private final String dimension;
        private final long createdAtNanos;
        private final List<BlockChange> blocks;
        private final List<EntitySpec> entities;
        private List<UUID> entityIds;

        private Transaction(
                UUID id,
                UUID worldSessionId,
                String dimension,
                long createdNanos,
                List<BlockChange> blocks,
                List<EntitySpec> entities,
                List<UUID> entityIds) {
            this.id = id;
            this.worldSessionId = worldSessionId;
            this.dimension = dimension;
            createdAtNanos = createdNanos;
            this.blocks = List.copyOf(blocks);
            this.entities = List.copyOf(entities);
            this.entityIds = List.copyOf(entityIds);
        }

        static Transaction blocks(
                UUID id, UUID session, String dimension, long now, List<BlockChange> blocks) {
            return new Transaction(id, session, dimension, now, blocks, List.of(), List.of());
        }

        static Transaction entities(
                UUID id, UUID session, String dimension, long now,
                List<EntitySpec> entities, List<UUID> entityIds) {
            return new Transaction(id, session, dimension, now, List.of(), entities, entityIds);
        }

        int changeCount() {
            return blocks.isEmpty() ? entities.size() : blocks.size();
        }

        void undo(Job job, ServerLevel level) {
            requireDimension(level);
            if (!blocks.isEmpty()) {
                applyBlockStates(job, level, blocks, false);
                return;
            }
            var found = new ArrayList<Entity>(entityIds.size());
            for (int index = 0; index < entityIds.size(); index++) {
                job.checkpoint();
                Entity entity = level.getEntity(entityIds.get(index));
                if (entity == null
                        || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()
                                .equals(entities.get(index).type)) {
                    throw rejected("divergence", "A summoned entity changed after the transaction");
                }
                found.add(entity);
            }
            job.checkpoint();
            found.forEach(Entity::discard);
            entityIds = List.of();
        }

        void redo(Job job, ServerLevel level) {
            requireDimension(level);
            if (!blocks.isEmpty()) {
                applyBlockStates(job, level, blocks, true);
                return;
            }
            if (!entityIds.isEmpty()) {
                throw rejected("divergence", "Summoned entities are already present");
            }
            for (EntitySpec spec : entities) {
                job.checkpoint();
                spec.validate(level);
            }
            entityIds = List.copyOf(spawnEntities(job, level, entities));
        }

        private void requireDimension(ServerLevel level) {
            if (!level.dimension().identifier().toString().equals(dimension)) {
                throw rejected("unsafe_state", "Creative history belongs to another dimension");
            }
        }
    }

    private enum State {
        QUEUED("queued", false),
        SUCCEEDED("succeeded", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true);

        private final String wireName;
        private final boolean terminal;

        State(String wireName, boolean terminal) {
            this.wireName = wireName;
            this.terminal = terminal;
        }
    }

    private static final class Job {
        private final UUID jobId;
        private final UUID worldSessionId;
        private final UUID idempotencyKey;
        private final Request request;
        private final long clientTick;
        private volatile MinecraftServer server;
        private final UUID playerId;
        private final long startedAtNanos;
        private volatile BooleanSupplier remainsArmed;
        private volatile Runnable lockOnRollbackFailure;
        private volatile CancelSignal cancelSignal;
        private State state = State.QUEUED;
        private int appliedChanges;
        private UUID transactionId;
        private Map<String, Object> error;

        private Job(
                UUID jobId,
                UUID worldSessionId,
                UUID idempotencyKey,
                Request request,
                long clientTick,
                MinecraftServer server,
                UUID playerId,
                long startedAtNanos,
                BooleanSupplier remainsArmed,
                Runnable lockOnRollbackFailure) {
            this.jobId = jobId;
            this.worldSessionId = worldSessionId;
            this.idempotencyKey = idempotencyKey;
            this.request = request;
            this.clientTick = clientTick;
            this.server = server;
            this.playerId = playerId;
            this.startedAtNanos = startedAtNanos;
            this.remainsArmed = remainsArmed;
            this.lockOnRollbackFailure = lockOnRollbackFailure;
        }

        private void checkpoint() {
            CancelSignal cancellation = cancelSignal;
            if (cancellation != null) {
                throw new EditCancelled(cancellation.code, cancellation.message);
            }
            if (timeoutElapsed(startedAtNanos, JOB_TIMEOUT, System.nanoTime())) {
                throw rejected("timeout", "Creative edit exceeded its 10 second deadline");
            }
            BooleanSupplier armed = remainsArmed;
            if (armed == null || !armed.getAsBoolean()) {
                throw rejected("locked", "Local arming was revoked or the world session changed during the Creative edit");
            }
        }

        private void requestCancel(String code, String message) {
            cancelSignal = new CancelSignal(code, message);
        }

        private void lockAfterRollbackFailure(Throwable failure) {
            Runnable lock = lockOnRollbackFailure;
            if (lock == null) {
                return;
            }
            try {
                lock.run();
            }
            catch (RuntimeException | LinkageError lockFailure) {
                failure.addSuppressed(lockFailure);
                CraftAgentMod.LOGGER.error(
                        "Creative edit could not lock after rollback failure job={}", jobId, lockFailure);
            }
        }

        private void releaseExecutionReferences() {
            server = null;
            remainsArmed = null;
            lockOnRollbackFailure = null;
        }

        private Map<String, Object> toMap(
                boolean idempotentReplay, Map<String, Object> history) {
            var result = new LinkedHashMap<String, Object>();
            result.put("response", "job");
            result.put("job_id", jobId.toString());
            result.put("operation", request.operation.wireName);
            result.put("state", state.wireName);
            result.put("idempotent_replay", idempotentReplay);
            result.put("started_client_tick", clientTick);
            result.put("requested_changes", request.requestedChanges());
            result.put("applied_changes", appliedChanges);
            result.put("transaction_id", transactionId == null ? null : transactionId.toString());
            result.put("history", history);
            result.put("error", error);
            return result;
        }
    }

    private record IdempotencyIdentity(UUID worldSessionId, UUID idempotencyKey) {
    }

    private record ServerContext(ServerLevel level) {
    }

    private record CancelSignal(String code, String message) {
    }

    private static final class EditCancelled extends RuntimeException {
        private final String code;

        private EditCancelled(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class EditRejected extends IllegalArgumentException {
        private final String code;

        private EditRejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static final class EditBusyException extends IllegalStateException {
    }

    public static final class EditIdempotencyConflictException extends IllegalStateException {
    }

    public static final class EditNotFoundException extends IllegalStateException {
    }
}
