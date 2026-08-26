package dev.aod.mcmcp.observation;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.SkipAll;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * Owns one bounded server-authoritative Creative blueprint export at a time.
 *
 * <p>Cells are streamed as palette RLE to a fixed game-directory artifact; they are never retained
 * in heap or written to {@link WorldMemory}. The export contains no NBT, container contents, seed,
 * POI data, player entities, UUIDs, or health.</p>
 */
public final class CreativeRegionCapture {
    public static final String CAPABILITY = "creative_region_capture";
    public static final int MAX_VOLUME = 4_194_304;
    public static final int MAX_CHUNKS = 64;
    public static final int MAX_AXIS = 256;
    public static final int MAX_ENTITY_INSTANCES = 128;
    public static final long MAX_UNCOMPRESSED_BYTES = 64L * 1_024L * 1_024L;
    public static final Duration JOB_TIMEOUT = Duration.ofSeconds(120);

    private static final int CELLS_PER_SERVER_TICK = 4_096;
    private static final int HASH_CELLS_PER_SERVER_TICK = 16_384;
    private static final int MAX_SUMMARY_COUNTS = 512;
    private static final int MAX_PALETTE_ENTRIES = 65_536;
    private static final int RETAINED_JOBS = 16;
    private static final int TICKET_RADIUS = 0;
    private static final TicketType CAPTURE_TICKET =
            new TicketType(197L, TicketType.FLAG_LOADING);
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final Set<String> START_KEYS =
            Set.of("operation", "region", "include_entities", "idempotency_key");
    private static final Set<String> STATUS_KEYS = Set.of("operation", "job_id");
    private static final Set<String> REGION_KEYS = Set.of("dimension", "min", "max");
    private static final Set<String> POSITION_KEYS = Set.of("x", "y", "z");
    private static final Set<String> DYNAMIC_PROPERTIES = Set.of(
            BlockStateProperties.POWER.getName(),
            BlockStateProperties.POWERED.getName(),
            BlockStateProperties.TRIGGERED.getName(),
            BlockStateProperties.LIT.getName());

    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<IdempotencyIdentity, Job> idempotency = new LinkedHashMap<>();
    private Job activeJob;

    /** Admits a job from the Minecraft client thread and returns immediately. */
    public synchronized Map<String, Object> start(
            Minecraft minecraft,
            long clientTick,
            UUID worldSessionId,
            Map<String, Object> arguments,
            BooleanSupplier remainsArmed) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(remainsArmed, "remainsArmed");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Creative export admission requires the client thread");
        }
        if (!isConfirmedCreative(minecraft)) {
            throw new IllegalStateException("Creative export requires private local Creative with commands enabled");
        }
        requireExactKeys(arguments, "capture_creative_region start", START_KEYS);
        if (!"start".equals(string(arguments.get("operation"), "operation"))) {
            throw new IllegalArgumentException("operation must be start");
        }
        if (clientTick < 0) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }

        Region region = parseRegion(requiredMap(arguments.get("region"), "region"));
        String currentDimension = Objects.requireNonNull(minecraft.level, "level")
                .dimension().identifier().toString();
        if (!currentDimension.equals(region.dimension())) {
            throw new IllegalArgumentException("region.dimension must equal the current dimension");
        }
        boolean includeEntities = optionalBoolean(
                arguments.get("include_entities"), false, "include_entities");
        UUID idempotencyKey = uuid(arguments.get("idempotency_key"), "idempotency_key");
        var request = new RequestIdentity(region, includeEntities);
        var identity = new IdempotencyIdentity(worldSessionId, idempotencyKey);
        Job replay = idempotency.get(identity);
        if (replay != null) {
            if (!replay.request.equals(request)) {
                throw new CaptureIdempotencyConflictException();
            }
            return replay.toMap(true);
        }
        if (activeJob != null) {
            throw new CaptureBusyException();
        }

        MinecraftServer server = Objects.requireNonNull(
                minecraft.getSingleplayerServer(), "integratedServer");
        UUID jobId = UUID.randomUUID();
        String relativePath = "mcmcp/exports/creative-blueprints/" + jobId + ".json.gz";
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        Path finalPath = gameDirectory.resolve(relativePath).normalize();
        if (!finalPath.startsWith(gameDirectory)) {
            throw new IllegalStateException("Creative export path escaped the game directory");
        }
        Path temporaryPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        long startedAtNanos = System.nanoTime();
        var job = new Job(
                jobId,
                worldSessionId,
                idempotencyKey,
                request,
                clientTick,
                server,
                minecraft.player.getUUID(),
                relativePath,
                finalPath,
                temporaryPath,
                startedAtNanos,
                remainsArmed);
        pruneRetainedJobs();
        jobs.put(jobId, job);
        idempotency.put(identity, job);
        activeJob = job;
        return job.toMap(false);
    }

    /** Polls a retained job. Status remains available after a local lock in the same session. */
    public synchronized Map<String, Object> status(
            UUID worldSessionId,
            Map<String, Object> arguments) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(arguments, "arguments");
        requireExactKeys(arguments, "capture_creative_region status", STATUS_KEYS);
        if (!"status".equals(string(arguments.get("operation"), "operation"))) {
            throw new IllegalArgumentException("operation must be status");
        }
        UUID jobId = uuid(arguments.get("job_id"), "job_id");
        Job job = jobs.get(jobId);
        if (job == null || !job.worldSessionId.equals(worldSessionId)) {
            throw new CaptureNotFoundException();
        }
        if (!job.state.terminal() && timeoutElapsed(
                job.startedAtNanos, JOB_TIMEOUT, System.nanoTime())) {
            requestCancel(job, "timeout", "Creative export exceeded its 120 second deadline");
        }
        return job.toMap(false);
    }

    /** Runs one bounded step from {@code ServerTickEvent.Post}. */
    public synchronized void onServerPostTick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Creative export steps require the logical server thread");
        }
        Job job = activeJob;
        if (job == null || job.server != server) {
            return;
        }
        if (job.state.terminal()) {
            cleanupTerminal(job);
            return;
        }
        if (timeoutElapsed(job.startedAtNanos, JOB_TIMEOUT, System.nanoTime())) {
            fail(job, "timeout", "Creative export exceeded its 120 second deadline");
            return;
        }
        if (!job.remainsArmed.getAsBoolean()) {
            fail(job, "locked", "Local arming was revoked or the world session changed during Creative export");
            return;
        }
        try {
            if (job.state == State.QUEUED) {
                begin(job);
            }
            if (job.state == State.LOADING) {
                loadOneChunk(job);
            }
            else if (job.state == State.CAPTURING) {
                captureOneSlice(job);
            }
            else if (job.state == State.FINALIZING) {
                finalizeOneSlice(job);
            }
        }
        catch (ArtifactLimitException tooLarge) {
            fail(job, "artifact_too_large", "Creative export exceeded its bounded artifact resources");
        }
        catch (ChunkNotGeneratedException notGenerated) {
            fail(job, "chunk_not_generated", "Creative export will not generate an absent chunk");
        }
        catch (IOException ioFailure) {
            fail(job, "artifact_write_failed", "Creative export artifact could not be written");
        }
        catch (RuntimeException | LinkageError failure) {
            fail(job, "capture_failed", "Creative export failed while reading the bounded region");
        }
    }

    /** Cancels active work and schedules server-thread ticket/file cleanup. */
    public synchronized boolean cancelActive(String reason) {
        Job job = activeJob;
        if (job == null) {
            return true;
        }
        requestCancel(job, "cancelled", sanitize(reason));
        try {
            job.server.execute(() -> onServerPostTick(job.server));
            return true;
        }
        catch (RuntimeException | LinkageError rejected) {
            return false;
        }
    }

    public synchronized boolean hasActiveJob() {
        return activeJob != null;
    }

    /** Final logical-server fence used by {@code ServerStoppingEvent}. */
    public synchronized void onServerStopping(MinecraftServer server) {
        Job job = activeJob;
        if (job == null || job.server != server) {
            return;
        }
        requestCancel(job, "server_stopping", "Integrated server stopped during Creative export");
        cleanupTerminal(job);
    }

    /** Client-side lifecycle fence; server authority is independently checked on every step. */
    public synchronized void fenceClient(Minecraft minecraft, UUID worldSessionId) {
        Job job = activeJob;
        if (job == null) {
            return;
        }
        if (!job.worldSessionId.equals(worldSessionId)) {
            requestCancel(job, "world_session_changed", "World session changed during Creative export");
        }
        else if (!isConfirmedCreative(minecraft)) {
            requestCancel(job, "unsafe_state", "Client Creative or command permission was revoked");
        }
    }

    /** Client-side gate used by local arming and start admission. */
    public static boolean isConfirmedCreative(Minecraft minecraft) {
        if (minecraft == null || minecraft.gameMode == null || minecraft.player == null
                || minecraft.level == null) {
            return false;
        }
        var server = minecraft.getSingleplayerServer();
        var abilities = minecraft.player.getAbilities();
        return clientAccessAllowed(
                minecraft.hasSingleplayerServer() && minecraft.isLocalServer(),
                server != null && server.isSingleplayer() && !server.isPublished(),
                minecraft.gameMode.getPlayerMode(),
                abilities.instabuild,
                abilities.mayBuild,
                server != null && server.getWorldData().isAllowCommands(),
                minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    static boolean clientAccessAllowed(
            boolean localIntegrated,
            boolean privateSingleplayer,
            GameType gameType,
            boolean instabuild,
            boolean mayBuild,
            boolean allowCommands,
            boolean gamemasterPermission) {
        return localIntegrated && privateSingleplayer && gameType == GameType.CREATIVE
                && instabuild && mayBuild && allowCommands && gamemasterPermission;
    }

    static boolean serverAccessAllowed(
            boolean privateSingleplayer,
            GameType gameType,
            boolean instabuild,
            boolean mayBuild,
            boolean allowCommands,
            boolean gamemasterPermission) {
        return privateSingleplayer && gameType == GameType.CREATIVE
                && instabuild && mayBuild && allowCommands && gamemasterPermission;
    }

    static Region parseRegion(Map<?, ?> input) {
        requireExactKeys(input, "region", REGION_KEYS);
        String dimension = string(input.get("dimension"), "region.dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("region.dimension must be a registry id");
        }
        BlockPos min = parsePosition(requiredMap(input.get("min"), "region.min"), "region.min");
        BlockPos max = parsePosition(requiredMap(input.get("max"), "region.max"), "region.max");
        return new Region(dimension, min, max);
    }

    private void begin(Job job) throws IOException {
        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player == null) {
            throw new IllegalStateException("Integrated server player is unavailable");
        }
        var abilities = player.getAbilities();
        if (!job.server.isSingleplayerOwner(player.nameAndId()) || !serverAccessAllowed(
                job.server.isSingleplayer() && !job.server.isPublished(),
                player.gameMode(),
                abilities.instabuild,
                abilities.mayBuild,
                job.server.getWorldData().isAllowCommands(),
                player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))) {
            throw new IllegalStateException("Integrated server Creative authority is unavailable");
        }
        ServerLevel level = player.level();
        if (!job.request.region.dimension().equals(level.dimension().identifier().toString())) {
            throw new IllegalStateException("Integrated server player changed dimension");
        }
        Region region = job.request.region;
        if (region.min().getY() < level.getMinY() || region.max().getY() >= level.getMaxY()) {
            throw new IllegalArgumentException("region must be inside the current build height");
        }
        Files.createDirectories(job.finalPath.getParent());
        job.writer = new ArtifactWriter(job.temporaryPath, job.finalPath, job.relativePath, region);
        job.serverPlayer = player;
        job.level = level;
        job.startedServerTick = job.server.getTickCount();
        job.state = State.LOADING;
    }

    private void loadOneChunk(Job job) {
        verifyServerAuthority(job);
        if (job.currentChunkIndex >= job.chunks.size()) {
            job.state = State.FINALIZING;
            return;
        }

        ChunkPos chunk = job.chunks.get(job.currentChunkIndex);
        if (job.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
            acquireTicket(job, chunk);
            job.state = State.CAPTURING;
            return;
        }
        if (job.chunkScanFuture == null && job.chunkExists == null) {
            job.chunkExists = new AtomicBoolean();
            job.chunkScanFuture = job.level.getChunkSource().chunkScanner().scanChunk(
                    chunk,
                    new SkipAll() {
                        @Override
                        public StreamTagVisitor.ValueResult visitRootEntry(TagType<?> rootType) {
                            job.chunkExists.set(true);
                            return StreamTagVisitor.ValueResult.HALT;
                        }
                    });
            return;
        }
        if (job.chunkScanFuture != null) {
            if (!job.chunkScanFuture.isDone()) {
                return;
            }
            job.chunkScanFuture.join();
            job.chunkScanFuture = null;
            if (!job.chunkExists.get()) {
                throw new ChunkNotGeneratedException();
            }
            acquireTicket(job, chunk);
            return;
        }
        if (job.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
            return;
        }
        job.state = State.CAPTURING;
    }

    private void captureOneSlice(Job job) throws IOException {
        verifyServerAuthority(job);
        if (job.currentChunkIndex >= job.chunks.size()) {
            job.state = State.FINALIZING;
            return;
        }
        ChunkPos chunk = job.chunks.get(job.currentChunkIndex);
        if (job.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
            throw new IllegalStateException("A temporary Creative capture chunk was released early");
        }
        int chunkCellCount = job.request.region.cellCount(chunk);
        int limit = Math.min(chunkCellCount, job.cellIndexInChunk + CELLS_PER_SERVER_TICK);
        while (job.cellIndexInChunk < limit) {
            BlockPos position = job.request.region.positionInChunkAt(chunk, job.cellIndexInChunk);
            captureCell(job, position);
            job.cellIndexInChunk++;
            job.processedCells++;
        }
        if (job.cellIndexInChunk < chunkCellCount) {
            return;
        }
        if (job.request.includeEntities) {
            captureEntitiesInChunk(job, chunk);
        }
        releaseTicket(job, chunk);
        job.processedChunks.add(chunk);
        job.currentChunkIndex++;
        job.cellIndexInChunk = 0;
        job.chunkExists = null;
        job.state = job.currentChunkIndex < job.chunks.size() ? State.LOADING : State.FINALIZING;
    }

    private static void acquireTicket(Job job, ChunkPos chunk) {
        if (job.admittedTickets.add(chunk)) {
            job.level.getChunkSource().addTicketWithRadius(CAPTURE_TICKET, chunk, TICKET_RADIUS);
        }
    }

    private void finalizeOneSlice(Job job) throws IOException {
        verifyServerAuthority(job);
        int limit = Math.min(
                job.request.region.volume(),
                job.hashCellIndex + HASH_CELLS_PER_SERVER_TICK);
        while (job.hashCellIndex < limit) {
            BlockPos position = job.request.region.positionAt(job.hashCellIndex);
            int paletteId = job.paletteIdsByGlobalIndex[job.hashCellIndex];
            MutablePaletteEntry entry = job.paletteEntries.get(paletteId);
            updateBlueprintDigest(
                    job.blueprintDigest,
                    job.request.region.offset(position),
                    entry.descriptor.state());
            job.hashCellIndex++;
        }
        if (job.hashCellIndex == job.request.region.volume()) {
            job.blueprintHash = fingerprint(job.blueprintDigest.digest());
            finishCapture(job);
        }
    }

    private void finishCapture(Job job) throws IOException {
        EntityCapture entities = job.request.includeEntities
                ? finishEntities(job)
                : EntityCapture.omitted();
        job.completedServerTick = job.server.getTickCount();
        boolean materialsComplete = job.manualSetupCount == 0
                && (!entities.included() || entities.count() == 0);
        Map<String, Object> basis = basis(job);
        ArtifactResult artifact = job.writer.finish(
                basis,
                job.blueprintHash,
                job.paletteEntries,
                job.blockCounts,
                job.materialCounts,
                materialsComplete,
                entities);
        job.writer = null;
        job.artifact = artifact.toMap();
        job.summary = summary(job, artifact.blueprintHash(), materialsComplete, entities);
        releaseTickets(job);
        job.state = State.SUCCEEDED;
        activeJob = null;
        job.releaseWorkingState();
    }

    private static void captureEntitiesInChunk(Job job, ChunkPos chunk) {
        if (job.entitySamples.size() >= MAX_ENTITY_INSTANCES + 1) {
            return;
        }
        AABB bounds = job.request.region.boundsInChunk(chunk);
        int remaining = MAX_ENTITY_INSTANCES + 1 - job.entitySamples.size();
        var found = new ArrayList<Entity>(remaining);
        job.level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                entity -> !(entity instanceof Player)
                        && entity.isAlive() && bounds.contains(entity.position()),
                found,
                remaining);
        found.stream().map(EntitySample::from).forEach(job.entitySamples::add);
    }

    private static EntityCapture finishEntities(Job job) {
        job.entitySamples.sort(Comparator
                .comparing(EntitySample::type)
                .thenComparingDouble(EntitySample::y)
                .thenComparingDouble(EntitySample::z)
                .thenComparingDouble(EntitySample::x));
        boolean truncated = job.entitySamples.size() > MAX_ENTITY_INSTANCES;
        List<EntitySample> retained = job.entitySamples.subList(
                0, Math.min(job.entitySamples.size(), MAX_ENTITY_INSTANCES));
        var counts = new TreeMap<String, Integer>();
        var instances = new ArrayList<Map<String, Object>>(retained.size());
        for (EntitySample entity : retained) {
            counts.merge(entity.type(), 1, Math::addExact);
            instances.add(entity.toMap());
        }
        return new EntityCapture(true, retained.size(), counts, instances, truncated);
    }

    private static void verifyServerAuthority(Job job) {
        ServerPlayer player = job.server.getPlayerList().getPlayer(job.playerId);
        if (player == null || player != job.serverPlayer || player.level() != job.level) {
            throw new IllegalStateException("Integrated server player or level changed");
        }
        var abilities = player.getAbilities();
        if (!job.server.isSingleplayerOwner(player.nameAndId()) || !serverAccessAllowed(
                job.server.isSingleplayer() && !job.server.isPublished(),
                player.gameMode(), abilities.instabuild, abilities.mayBuild,
                job.server.getWorldData().isAllowCommands(),
                player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))) {
            throw new IllegalStateException("Integrated server Creative authority was revoked");
        }
        if (!job.request.region.dimension().equals(job.level.dimension().identifier().toString())) {
            throw new IllegalStateException("Integrated server dimension changed");
        }
    }

    private static void captureCell(Job job, BlockPos position) throws IOException {
        BlockState state = job.level.getBlockState(position);
        BlockStateView view = MinecraftObservationService.blockStateView(state);
        var manual = new ArrayList<ManualDescriptor>(4);

        var blockEntity = job.level.getBlockEntity(position);
        if (blockEntity != null) {
            manual.add(new ManualDescriptor(
                    "block_entity",
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString()));
        }
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            manual.add(new ManualDescriptor(
                    "fluid", BuiltInRegistries.FLUID.getKey(fluid.getType()).toString()));
        }
        if (isMultiCellState(state)) {
            manual.add(new ManualDescriptor("multi_cell", null));
        }
        if (isDynamicState(view)) {
            manual.add(new ManualDescriptor("dynamic_state", null));
        }

        MaterialUnit material = null;
        if (!state.isAir() && !isSecondaryMultiCellState(state)) {
            var clone = state.getCloneItemStack(job.level, position, false);
            if (clone.isEmpty()) {
                manual.add(new ManualDescriptor("material_unresolved", null));
            }
            else {
                String item = BuiltInRegistries.ITEM.getKey(clone.getItem()).toString();
                int count = Math.multiplyExact(clone.getCount(), materialUnits(state));
                material = new MaterialUnit(item, count);
                job.materialCounts.merge(item, count, Math::addExact);
            }
        }
        job.manualSetupCount = Math.addExact(job.manualSetupCount, manual.size());
        job.blockCounts.merge(view.block(), 1, Math::addExact);

        var descriptor = new CellDescriptor(view, manual, material);
        String key = descriptor.canonicalKey();
        MutablePaletteEntry entry = job.paletteByKey.get(key);
        if (entry == null) {
            if (job.paletteEntries.size() >= MAX_PALETTE_ENTRIES) {
                throw new ArtifactLimitException();
            }
            entry = new MutablePaletteEntry(job.paletteEntries.size(), descriptor);
            job.paletteByKey.put(key, entry);
            job.paletteEntries.add(entry);
        }
        entry.count = Math.addExact(entry.count, 1);
        job.paletteIdsByGlobalIndex[job.request.region.indexOf(position)] = entry.id;
        job.writer.writeCell(entry.id);
    }

    private void fail(Job job, String code, String message) {
        job.state = State.FAILED;
        job.error = error(code, message);
        cleanupTerminal(job);
    }

    private void requestCancel(Job job, String code, String message) {
        if (!job.state.terminal()) {
            job.state = State.CANCELLED;
            job.error = error(code, message);
        }
    }

    private void cleanupTerminal(Job job) {
        if (activeJob != job) {
            return;
        }
        releaseTickets(job);
        if (job.writer != null) {
            job.writer.abort();
            job.writer = null;
        }
        else {
            deleteQuietly(job.temporaryPath);
        }
        activeJob = null;
        job.releaseWorkingState();
    }

    private static void releaseTickets(Job job) {
        if (job.chunkScanFuture != null) {
            job.chunkScanFuture.cancel(false);
            job.chunkScanFuture = null;
        }
        if (job.level != null) {
            for (ChunkPos chunk : List.copyOf(job.admittedTickets)) {
                releaseTicket(job, chunk);
            }
        }
        job.admittedTickets.clear();
    }

    private static void releaseTicket(Job job, ChunkPos chunk) {
        if (!job.admittedTickets.remove(chunk) || job.level == null) {
            return;
        }
        try {
            job.level.getChunkSource().removeTicketWithRadius(
                    CAPTURE_TICKET, chunk, TICKET_RADIUS);
        }
        catch (RuntimeException | LinkageError ignored) {
            // The non-persistent 197-tick timeout remains a bounded leak backstop.
        }
    }

    private static Map<String, Object> basis(Job job) {
        var result = new LinkedHashMap<String, Object>();
        result.put("world_session_id", job.worldSessionId.toString());
        result.put("started_client_tick", job.startedClientTick);
        result.put("dimension", job.request.region.dimension());
        result.put("source", "integrated_server_chunk_sequence");
        result.put("game_mode", "creative");
        result.put("consistency", "server_thread_chunk_sequence");
        result.put("region", job.request.region.toMap());
        result.put("volume", job.request.region.volume());
        result.put("started_server_tick", job.startedServerTick);
        result.put("completed_server_tick", job.completedServerTick);
        return result;
    }

    private static Map<String, Object> summary(
            Job job,
            String blueprintHash,
            boolean materialsComplete,
            EntityCapture entities) {
        var result = new LinkedHashMap<String, Object>();
        result.put("basis", basis(job));
        result.put("blueprint_hash", blueprintHash);
        result.put("palette_size", job.paletteEntries.size());
        result.put("manual_setup_count", job.manualSetupCount);
        result.put("block_counts", boundedCounts(job.blockCounts, "block"));
        var materialSummary = new LinkedHashMap<>(boundedCounts(job.materialCounts, "item"));
        materialSummary.put("complete", materialsComplete);
        result.put("materials", Map.copyOf(materialSummary));
        result.put("entities", Map.of(
                "included", entities.included(),
                "count", entities.count(),
                "truncated", entities.truncated(),
                "complete", false));
        return Map.copyOf(result);
    }

    private void pruneRetainedJobs() {
        if (jobs.size() < RETAINED_JOBS) {
            return;
        }
        var iterator = jobs.entrySet().iterator();
        while (jobs.size() >= RETAINED_JOBS && iterator.hasNext()) {
            Job candidate = iterator.next().getValue();
            if (candidate != activeJob && candidate.state.terminal()) {
                iterator.remove();
                idempotency.remove(new IdempotencyIdentity(
                        candidate.worldSessionId, candidate.idempotencyKey));
            }
        }
        if (jobs.size() >= RETAINED_JOBS) {
            throw new CaptureBusyException();
        }
    }

    static String blueprintHash(List<Cell> cells) {
        MessageDigest digest = newBlueprintDigest();
        cells.stream()
                .sorted(Comparator
                        .comparingInt((Cell cell) -> cell.offset().y())
                        .thenComparingInt(cell -> cell.offset().z())
                        .thenComparingInt(cell -> cell.offset().x()))
                .forEach(cell -> updateBlueprintDigest(digest, cell.offset(), cell.state()));
        return fingerprint(digest.digest());
    }

    private static void updateBlueprintDigest(
            MessageDigest digest,
            BlockPlan.Offset offset,
            BlockStateView state) {
        String canonical = "\n" + offset.x() + "|" + offset.y() + "|" + offset.z()
                + "|" + canonicalState(state);
        digest.update(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalState(BlockStateView state) {
        var result = new StringBuilder(state.block());
        new TreeMap<>(state.properties()).forEach((name, value) ->
                result.append('|').append(name).append('=').append(value));
        return result.toString();
    }

    private static boolean isDynamicState(BlockStateView state) {
        return state.properties().keySet().stream().anyMatch(DYNAMIC_PROPERTIES::contains);
    }

    private static boolean isMultiCellState(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BedBlock.PART);
    }

    static boolean isSecondaryMultiCellState(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                || state.hasProperty(BedBlock.PART)
                        && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    static int materialUnits(BlockState state) {
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        if (state.hasProperty(BlockStateProperties.LAYERS)) {
            return state.getValue(BlockStateProperties.LAYERS);
        }
        if (state.hasProperty(BlockStateProperties.CANDLES)) {
            return state.getValue(BlockStateProperties.CANDLES);
        }
        if (state.hasProperty(BlockStateProperties.PICKLES)) {
            return state.getValue(BlockStateProperties.PICKLES);
        }
        if (state.hasProperty(BlockStateProperties.EGGS)) {
            return state.getValue(BlockStateProperties.EGGS);
        }
        if (state.hasProperty(BlockStateProperties.FLOWER_AMOUNT)) {
            return state.getValue(BlockStateProperties.FLOWER_AMOUNT);
        }
        return 1;
    }

    private static BlockPos parsePosition(Map<?, ?> input, String path) {
        requireExactKeys(input, path, POSITION_KEYS);
        return new BlockPos(
                integerInRange(input.get("x"), path + ".x", -30_000_000, 29_999_999),
                integerInRange(input.get("y"), path + ".y", -2_048, 2_047),
                integerInRange(input.get("z"), path + ".z", -30_000_000, 29_999_999));
    }

    private static List<Map<String, Object>> counts(Map<String, Integer> source, String keyName) {
        return source.entrySet().stream()
                .map(entry -> Map.<String, Object>of(keyName, entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private static Map<String, Object> boundedCounts(
            Map<String, Integer> source, String keyName) {
        List<Map<String, Object>> items = source.entrySet().stream()
                .limit(MAX_SUMMARY_COUNTS)
                .map(entry -> Map.<String, Object>of(
                        keyName, entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of(
                "items", items,
                "unique_count", source.size(),
                "truncated", source.size() > items.size());
    }

    private static Map<String, Object> stateMap(BlockStateView state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("block", state.block());
        result.put("properties", Collections.unmodifiableMap(new TreeMap<>(state.properties())));
        return result;
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", sanitize(message));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(256, normalized.length()));
    }

    private static Map<?, ?> requiredMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> result)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return result;
    }

    private static void requireExactKeys(Map<?, ?> input, String path, Set<String> expected) {
        for (Object key : input.keySet()) {
            if (!(key instanceof String name) || !expected.contains(name)) {
                throw new IllegalArgumentException(path + " contains an unknown property");
            }
        }
        if (input.size() != expected.size() || !input.keySet().containsAll(expected)) {
            throw new IllegalArgumentException(
                    path + " must contain exactly " + expected.stream().sorted().toList());
        }
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
        return result;
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
        double decimal = number.doubleValue();
        long exact = number.longValue();
        if (!Double.isFinite(decimal) || decimal != exact
                || exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be a 32-bit integer");
        }
        return (int) exact;
    }

    private static int integerInRange(Object value, String path, int minimum, int maximum) {
        int result = integer(value, path);
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(path + " must be in " + minimum + ".." + maximum);
        }
        return result;
    }

    private static boolean optionalBoolean(Object value, boolean fallback, String path) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return result;
    }

    static boolean timeoutElapsed(long startedAtNanos, Duration timeout, long nowNanos) {
        long elapsedNanos = nowNanos - startedAtNanos;
        return elapsedNanos < 0 || elapsedNanos >= timeout.toNanos();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static MessageDigest newBlueprintDigest() {
        MessageDigest digest = sha256();
        digest.update("mcmcp.blueprint/v1".getBytes(StandardCharsets.UTF_8));
        return digest;
    }

    private static String fingerprint(byte[] digest) {
        return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // The path is fixed and bounded; terminal status remains available for diagnosis.
        }
    }

    record Cell(String id, BlockPlan.Offset offset, BlockStateView state) {
    }

    record Region(
            String dimension,
            BlockPos min,
            BlockPos max,
            int sizeX,
            int sizeY,
            int sizeZ,
            int volume,
            List<ChunkPos> chunks) {
        Region(String dimension, BlockPos min, BlockPos max) {
            this(dimension, min, max, dimensions(min, max));
        }

        private Region(String dimension, BlockPos min, BlockPos max, int[] dimensions) {
            this(dimension, min, max, dimensions[0], dimensions[1], dimensions[2], dimensions[3],
                    chunkPositions(min, max));
        }

        Region {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            chunks = List.copyOf(chunks);
            if (chunks.size() > MAX_CHUNKS) {
                throw new IllegalArgumentException(
                        "region must intersect at most " + MAX_CHUNKS + " chunk columns");
            }
        }

        private static int[] dimensions(BlockPos min, BlockPos max) {
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException("region.min must not exceed region.max");
            }
            long x = (long) max.getX() - min.getX() + 1L;
            long y = (long) max.getY() - min.getY() + 1L;
            long z = (long) max.getZ() - min.getZ() + 1L;
            if (x > MAX_AXIS || y > MAX_AXIS || z > MAX_AXIS) {
                throw new IllegalArgumentException(
                        "each region axis must contain at most " + MAX_AXIS + " cells");
            }
            long volume = Math.multiplyExact(Math.multiplyExact(x, y), z);
            if (volume < 1 || volume > MAX_VOLUME) {
                throw new IllegalArgumentException("region volume must be in 1.." + MAX_VOLUME);
            }
            return new int[]{(int) x, (int) y, (int) z, (int) volume};
        }

        private static List<ChunkPos> chunkPositions(BlockPos min, BlockPos max) {
            var result = new ArrayList<ChunkPos>();
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
                for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
                    result.add(new ChunkPos(x, z));
                }
            }
            return List.copyOf(result);
        }

        BlockPos positionAt(int index) {
            if (index < 0 || index >= volume) {
                throw new IndexOutOfBoundsException(index);
            }
            int x = index % sizeX;
            int remaining = index / sizeX;
            int z = remaining % sizeZ;
            int y = remaining / sizeZ;
            return new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z);
        }

        int cellCount(ChunkPos chunk) {
            int minX = Math.max(min.getX(), chunk.x() << 4);
            int maxX = Math.min(max.getX(), (chunk.x() << 4) + 15);
            int minZ = Math.max(min.getZ(), chunk.z() << 4);
            int maxZ = Math.min(max.getZ(), (chunk.z() << 4) + 15);
            return Math.multiplyExact(
                    Math.multiplyExact(maxX - minX + 1, sizeY),
                    maxZ - minZ + 1);
        }

        BlockPos positionInChunkAt(ChunkPos chunk, int index) {
            int minX = Math.max(min.getX(), chunk.x() << 4);
            int maxX = Math.min(max.getX(), (chunk.x() << 4) + 15);
            int minZ = Math.max(min.getZ(), chunk.z() << 4);
            int maxZ = Math.min(max.getZ(), (chunk.z() << 4) + 15);
            int width = maxX - minX + 1;
            int depth = maxZ - minZ + 1;
            int count = Math.multiplyExact(Math.multiplyExact(width, sizeY), depth);
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(index);
            }
            int x = index % width;
            int remaining = index / width;
            int z = remaining % depth;
            int y = remaining / depth;
            return new BlockPos(minX + x, min.getY() + y, minZ + z);
        }

        int indexOf(BlockPos position) {
            int x = position.getX() - min.getX();
            int y = position.getY() - min.getY();
            int z = position.getZ() - min.getZ();
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                throw new IllegalArgumentException("position is outside the region");
            }
            return Math.addExact(x, Math.multiplyExact(sizeX, Math.addExact(
                    z, Math.multiplyExact(sizeZ, y))));
        }

        BlockPlan.Offset offset(BlockPos position) {
            return new BlockPlan.Offset(
                    position.getX() - min.getX(),
                    position.getY() - min.getY(),
                    position.getZ() - min.getZ());
        }

        AABB boundsInChunk(ChunkPos chunk) {
            int minX = Math.max(min.getX(), chunk.x() << 4);
            int maxX = Math.min(max.getX(), (chunk.x() << 4) + 15);
            int minZ = Math.max(min.getZ(), chunk.z() << 4);
            int maxZ = Math.min(max.getZ(), (chunk.z() << 4) + 15);
            return new AABB(
                    minX, min.getY(), minZ,
                    maxX + 1.0D, max.getY() + 1.0D, maxZ + 1.0D);
        }

        List<BlockPos> positions() {
            var result = new ArrayList<BlockPos>(volume);
            for (int index = 0; index < volume; index++) {
                result.add(positionAt(index));
            }
            return List.copyOf(result);
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "min", Map.of("x", min.getX(), "y", min.getY(), "z", min.getZ()),
                    "max", Map.of("x", max.getX(), "y", max.getY(), "z", max.getZ()));
        }
    }

    private record RequestIdentity(Region region, boolean includeEntities) {
    }

    private record IdempotencyIdentity(UUID worldSessionId, UUID idempotencyKey) {
    }

    private record ManualDescriptor(String code, String relatedRegistryId) {
        Map<String, Object> toMap() {
            return relatedRegistryId == null
                    ? Map.of("code", code)
                    : Map.of("code", code, "related_registry_id", relatedRegistryId);
        }
    }

    private record MaterialUnit(String item, int count) {
        Map<String, Object> toMap() {
            return Map.of("item", item, "count", count);
        }
    }

    private record CellDescriptor(
            BlockStateView state,
            List<ManualDescriptor> manualSetup,
            MaterialUnit material) {
        CellDescriptor {
            manualSetup = List.copyOf(manualSetup);
        }

        String canonicalKey() {
            var result = new StringBuilder(canonicalState(state));
            for (ManualDescriptor descriptor : manualSetup) {
                result.append("|manual:").append(descriptor.code()).append('=')
                        .append(descriptor.relatedRegistryId());
            }
            if (material != null) {
                result.append("|material:").append(material.item()).append('=').append(material.count());
            }
            return result.toString();
        }

        Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("state", stateMap(state));
            result.put("manual_setup", manualSetup.stream().map(ManualDescriptor::toMap).toList());
            if (material != null) {
                result.put("material", material.toMap());
            }
            return result;
        }
    }

    private static final class MutablePaletteEntry {
        private final int id;
        private final CellDescriptor descriptor;
        private int count;

        private MutablePaletteEntry(int id, CellDescriptor descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }
    }

    private record EntityCapture(
            boolean included,
            int count,
            Map<String, Integer> counts,
            List<Map<String, Object>> instances,
            boolean truncated) {
        EntityCapture {
            counts = Collections.unmodifiableMap(new TreeMap<>(counts));
            instances = List.copyOf(instances);
        }

        static EntityCapture omitted() {
            return new EntityCapture(false, 0, Map.of(), List.of(), false);
        }

        Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("included", included);
            result.put("coverage_basis", "server_tracked_non_player_position_inside_region_capped_128");
            result.put("server_complete", false);
            result.put("complete", false);
            result.put("count", count);
            result.put("by_type", CreativeRegionCapture.counts(counts, "type"));
            result.put("instances", instances);
            result.put("truncated", truncated);
            result.put("reconstruction", "manual_only");
            return result;
        }
    }

    private record EntitySample(
            String type,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            boolean onGround,
            boolean vehicle,
            boolean passenger) {
        static EntitySample from(Entity entity) {
            return new EntitySample(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYRot(), entity.getXRot(), entity.onGround(),
                    entity.isVehicle(), entity.isPassenger());
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "type", type,
                    "position", Map.of("x", x, "y", y, "z", z),
                    "rotation", Map.of("yaw", yaw, "pitch", pitch),
                    "on_ground", onGround,
                    "vehicle", vehicle,
                    "passenger", passenger);
        }
    }

    private enum State {
        QUEUED("queued", false),
        LOADING("loading_chunks", false),
        CAPTURING("capturing", false),
        FINALIZING("finalizing", false),
        SUCCEEDED("succeeded", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true);

        private final String wireName;
        private final boolean terminal;

        State(String wireName, boolean terminal) {
            this.wireName = wireName;
            this.terminal = terminal;
        }

        boolean terminal() {
            return terminal;
        }
    }

    private static final class Job {
        private final UUID jobId;
        private final UUID worldSessionId;
        private final UUID idempotencyKey;
        private final RequestIdentity request;
        private final long startedClientTick;
        private final MinecraftServer server;
        private final UUID playerId;
        private final String relativePath;
        private final Path finalPath;
        private final Path temporaryPath;
        private final long startedAtNanos;
        private final BooleanSupplier remainsArmed;
        private final List<ChunkPos> chunks;
        private final Set<ChunkPos> admittedTickets = new LinkedHashSet<>();
        private final Set<ChunkPos> processedChunks = new LinkedHashSet<>();
        private LinkedHashMap<String, MutablePaletteEntry> paletteByKey = new LinkedHashMap<>();
        private ArrayList<MutablePaletteEntry> paletteEntries = new ArrayList<>();
        private TreeMap<String, Integer> blockCounts = new TreeMap<>();
        private TreeMap<String, Integer> materialCounts = new TreeMap<>();
        private State state = State.QUEUED;
        private int currentChunkIndex;
        private int cellIndexInChunk;
        private int processedCells;
        private int manualSetupCount;
        private int startedServerTick = -1;
        private int completedServerTick = -1;
        private ServerPlayer serverPlayer;
        private ServerLevel level;
        private CompletableFuture<Void> chunkScanFuture;
        private AtomicBoolean chunkExists;
        private final ArrayList<EntitySample> entitySamples = new ArrayList<>(MAX_ENTITY_INSTANCES + 1);
        private int[] paletteIdsByGlobalIndex;
        private final MessageDigest blueprintDigest = newBlueprintDigest();
        private int hashCellIndex;
        private String blueprintHash;
        private ArtifactWriter writer;
        private Map<String, Object> artifact;
        private Map<String, Object> summary;
        private Map<String, Object> error;

        private Job(
                UUID jobId,
                UUID worldSessionId,
                UUID idempotencyKey,
                RequestIdentity request,
                long startedClientTick,
                MinecraftServer server,
                UUID playerId,
                String relativePath,
                Path finalPath,
                Path temporaryPath,
                long startedAtNanos,
                BooleanSupplier remainsArmed) {
            this.jobId = jobId;
            this.worldSessionId = worldSessionId;
            this.idempotencyKey = idempotencyKey;
            this.request = request;
            this.startedClientTick = startedClientTick;
            this.server = server;
            this.playerId = playerId;
            this.relativePath = relativePath;
            this.finalPath = finalPath;
            this.temporaryPath = temporaryPath;
            this.startedAtNanos = startedAtNanos;
            this.remainsArmed = remainsArmed;
            this.chunks = request.region.chunks();
            this.paletteIdsByGlobalIndex = new int[request.region.volume()];
        }

        private Map<String, Object> toMap(boolean idempotentReplay) {
            var progress = new LinkedHashMap<String, Object>();
            progress.put("processed_cells", processedCells);
            progress.put("total_cells", request.region.volume());
            progress.put("loaded_chunks", Math.min(
                    processedChunks.size()
                            + (state == State.CAPTURING && currentChunkIndex < chunks.size() ? 1 : 0),
                    chunks.size()));
            progress.put("processed_chunks", processedChunks.size());
            progress.put("total_chunks", chunks.size());
            if (startedServerTick >= 0) {
                progress.put("started_server_tick", startedServerTick);
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("job_id", jobId.toString());
            result.put("state", state.wireName);
            result.put("idempotent_replay", idempotentReplay);
            result.put("progress", progress);
            if (artifact != null) result.put("artifact", artifact);
            if (summary != null) result.put("summary", summary);
            if (error != null) result.put("error", error);
            return result;
        }

        private void releaseWorkingState() {
            paletteByKey.clear();
            paletteEntries.clear();
            blockCounts.clear();
            materialCounts.clear();
            entitySamples.clear();
            paletteIdsByGlobalIndex = null;
            chunkExists = null;
            serverPlayer = null;
            level = null;
        }
    }

    private static final class ArtifactWriter {
        private final Path temporaryPath;
        private final Path finalPath;
        private final String relativePath;
        private final MessageDigest artifactDigest = sha256();
        private final GZIPOutputStream gzip;
        private long uncompressedBytes;
        private int pendingPaletteId = -1;
        private int pendingRunCount;
        private boolean firstRun = true;
        private boolean closed;

        private ArtifactWriter(Path temporaryPath, Path finalPath, String relativePath, Region region)
                throws IOException {
            this.temporaryPath = temporaryPath;
            this.finalPath = finalPath;
            this.relativePath = relativePath;
            OutputStream file = Files.newOutputStream(
                    temporaryPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            gzip = new GZIPOutputStream(new DigestOutputStream(file, artifactDigest));
            writeRaw("{\"schema\":\"mcmcp.creative-blueprint-artifact/v1\",\"blueprint\":{"
                    + "\"schema\":\"mcmcp.blueprint-palette-rle/v1\",\"anchor\":");
            writeJson(Map.of(
                    "dimension", region.dimension(),
                    "x", region.min().getX(), "y", region.min().getY(), "z", region.min().getZ()));
            writeRaw(",\"transform\":{\"rotation\":0,\"mirror\":\"none\"},\"encoding\":");
            writeJson(Map.of(
                    "ordering", "chunk_z_x_then_y_z_x_within_clipped_chunk",
                    "hash_ordering", "y_z_x",
                    "size", Map.of("x", region.sizeX(), "y", region.sizeY(), "z", region.sizeZ())));
            writeRaw(",\"runs\":[");
        }

        private void writeCell(int paletteId) throws IOException {
            if (pendingPaletteId == paletteId) {
                pendingRunCount = Math.addExact(pendingRunCount, 1);
                return;
            }
            flushRun();
            pendingPaletteId = paletteId;
            pendingRunCount = 1;
        }

        private void flushRun() throws IOException {
            if (pendingPaletteId < 0) return;
            if (!firstRun) writeRaw(",");
            firstRun = false;
            writeJson(Map.of(
                    "palette_id", String.format(Locale.ROOT, "p%06d", pendingPaletteId),
                    "count", pendingRunCount));
        }

        private ArtifactResult finish(
                Map<String, Object> basis,
                String blueprintHash,
                List<MutablePaletteEntry> palette,
                Map<String, Integer> blockCounts,
                Map<String, Integer> materialCounts,
                boolean materialsComplete,
                EntityCapture entities) throws IOException {
            flushRun();
            writeRaw("],\"hash\":");
            writeJson(blueprintHash);
            writeRaw(",\"palette\":[");
            for (int index = 0; index < palette.size(); index++) {
                if (index > 0) writeRaw(",");
                MutablePaletteEntry entry = palette.get(index);
                var value = new LinkedHashMap<String, Object>();
                value.put("palette_id", String.format(Locale.ROOT, "p%06d", entry.id));
                value.putAll(entry.descriptor.toMap());
                value.put("count", entry.count);
                writeJson(value);
            }
            writeRaw("]},\"basis\":");
            writeJson(basis);
            writeRaw(",\"block_counts\":[");
            writeCounts(blockCounts, "block");
            writeRaw("],\"materials\":{\"basis\":"
                    + "\"clone_item_stack_without_block_entity_data\",\"scope\":"
                    + "\"placed_block_shell_only\",\"complete\":" + materialsComplete + ",\"items\":[");
            writeCounts(materialCounts, "item");
            writeRaw("]},\"entities\":");
            writeJson(entities.toMap());
            writeRaw("}");
            close();
            Files.move(temporaryPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            return new ArtifactResult(
                    relativePath, fingerprint(artifactDigest.digest()),
                    Files.size(finalPath), uncompressedBytes, blueprintHash);
        }

        private void writeCounts(Map<String, Integer> counts, String keyName) throws IOException {
            int index = 0;
            for (var entry : counts.entrySet()) {
                if (index++ > 0) writeRaw(",");
                writeJson(Map.of(keyName, entry.getKey(), "count", entry.getValue()));
            }
        }

        private void writeJson(Object value) throws IOException {
            write(JSON.writeValueAsBytes(value));
        }

        private void writeRaw(String value) throws IOException {
            write(value.getBytes(StandardCharsets.UTF_8));
        }

        private void write(byte[] bytes) throws IOException {
            if (bytes.length > MAX_UNCOMPRESSED_BYTES - uncompressedBytes) {
                throw new ArtifactLimitException();
            }
            gzip.write(bytes);
            uncompressedBytes += bytes.length;
        }

        private void close() throws IOException {
            if (!closed) {
                closed = true;
                gzip.close();
            }
        }

        private void abort() {
            try {
                close();
            }
            catch (IOException ignored) {
                // Continue to delete the fixed temporary path.
            }
            deleteQuietly(temporaryPath);
        }
    }

    private record ArtifactResult(
            String relativePath,
            String sha256,
            long compressedBytes,
            long uncompressedBytes,
            String blueprintHash) {
        Map<String, Object> toMap() {
            return Map.of(
                    "relative_path", relativePath,
                    "format", "json+gzip",
                    "sha256", sha256,
                    "compressed_bytes", compressedBytes,
                    "uncompressed_bytes", uncompressedBytes);
        }
    }

    private static final class ArtifactLimitException extends IOException {
    }

    private static final class ChunkNotGeneratedException extends RuntimeException {
    }

    public static final class CaptureIdempotencyConflictException extends IllegalStateException {
        public CaptureIdempotencyConflictException() {
            super("The Creative capture idempotency key has different arguments");
        }
    }

    public static final class CaptureBusyException extends IllegalStateException {
        public CaptureBusyException() {
            super("Another Creative capture is active or retained capacity is exhausted");
        }
    }

    public static final class CaptureNotFoundException extends IllegalStateException {
        public CaptureNotFoundException() {
            super("The Creative capture job is not retained in this world session");
        }
    }
}
