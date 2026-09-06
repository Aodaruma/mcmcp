package dev.aod.mcmcp.fixture;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.runtime.TestHarnessWorldSessionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Autorun-only baseline. Lifecycle callbacks restore resources; no running-gameplay fixture writes. */
final class FixtureTunnelScenario {
    private static final Gson JSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FixtureTunnelResources RESOURCES = new FixtureTunnelResources(new FixtureRaysPerTickLease());
    // Scenario state is guarded by the class monitor; lock order is scenario, then resources.
    private static boolean setupAttempted;
    private static Prepared prepared;
    private static boolean restorePending;

    private FixtureTunnelScenario() { }

    static synchronized void prepareAutorun(FixtureSecurity.Context context, FixturePhase5Mode mode,
            Consumer<Component> output) {
        if (setupAttempted) throw new IllegalStateException("tunnel setup is one-shot; restart the disposable client");
        FixtureSecurity.Decision decision = FixtureSecurity.reauthorize(context);
        if (!decision.allowed()) throw new IllegalStateException("tunnel autorun security boundary changed");
        if (!mode.tunnel() || FixturePhase5AutorunConfig.fromSystemProperties()
                .map(FixturePhase5AutorunConfig::mode).filter(mode::equals).isEmpty())
            throw new IllegalStateException("tunnel setup requires the matching launch mode");
        if (FixturePhase3AutorunConfig.fromSystemProperties().isPresent())
            throw new IllegalStateException("tunnel setup cannot share another fixture autorun");
        var session = new FixtureTunnelSession(TestHarnessWorldSessionAccess.currentWorldSessionId());
        // Claim before the first world write: even a failed setup cannot be repeated in this JVM.
        setupAttempted = true;
        FixturePhase2Scenario.stop();
        FixtureCombinedWheatScenario.rollbackForReplacement(context);
        var plan = FixtureTunnelPlan.forMode(mode);
        var level = context.level();
        try {
            RESOURCES.begin(context.server(), level, new FixtureTunnelResources.ChunkAccess() {
                @Override public boolean forced(FixtureTunnelResources.Chunk chunk) {
                    return level.getForceLoadedChunks().contains(ChunkPos.pack(chunk.x(), chunk.z()));
                }
                @Override public void setForced(FixtureTunnelResources.Chunk chunk, boolean forced) {
                    level.setChunkForced(chunk.x(), chunk.z(), forced);
                }
            });
            level.getEntities((Entity) null, bounds(), entity -> entity != context.player())
                    .forEach(Entity::discard);
            for (var entry : plan.baseline().entrySet()) setBounded(level, entry.getKey(), state(entry.getValue()));
            configurePlayer(context.player());
            if (!context.player().teleportTo(level, 257.5D, 200.0D, 256.5D, Set.<Relative>of(),
                    -90.0F, 38.0F, false)) throw new IllegalStateException("tunnel start pose was not synchronized");
            context.player().setDeltaMovement(0, 0, 0);
            context.player().setYHeadRot(-90.0F);
            context.player().resetFallDistance();
            prepared = new Prepared(context, plan, UUID.randomUUID().toString(), session);
            if (!audit(prepared).baselineMatches() || entityCount(level, context.player()) != 0)
                throw new IllegalStateException("tunnel baseline did not match its fixed plan");
            sendStatus(context, output);
        } catch (RuntimeException failure) {
            try { restoreResources(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Post-terminal operator action; deliberately separate from the read-only oracle. */
    static synchronized void finish(FixtureSecurity.Context context, Consumer<Component> output) {
        requirePrepared(context);
        RESOURCES.requireOwner(context.server(), context.level());
        restoreResources();
        output.accept(Component.literal("{\"schema\":\"mcmcp_fixture_tunnel_v1\",\"kind\":\"finish\",\"resourcesRestored\":true}"));
    }

    static synchronized void restoreForReplacement(FixtureSecurity.Context context) {
        RESOURCES.requireOwner(context.server(), context.level());
        restoreResources();
    }

    static synchronized void onServerPreTick(ServerTickEvent.Pre event) {
        if (!RESOURCES.active() || event.getServer() != RESOURCES.server()) return;
        if (restorePending || prepared == null || !FixtureSecurity.reauthorize(prepared.context).allowed()
                || !currentSessionMatches() || !resourcesIntact())
            restoreAtLifecycle();
    }

    private static boolean resourcesIntact() {
        try { return resourceHealth().intact(); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private static FixtureTunnelResources.Health resourceHealth() {
        return RESOURCES.health(McmcpClientConfig.raysPerTick(), restorePending);
    }

    private static boolean currentSessionMatches() {
        try {
            prepared.session.requireCurrent(TestHarnessWorldSessionAccess.currentWorldSessionId());
            return true;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    static synchronized void onServerStopping(ServerStoppingEvent event) {
        if (event.getServer() == RESOURCES.server()) restoreAtLifecycle();
    }

    static synchronized void onServerStopped(ServerStoppedEvent event) {
        if (event.getServer() == RESOURCES.server()) restoreAtLifecycle();
    }

    static synchronized void restoreAfterAutorunFailure() {
        Object owner = RESOURCES.server();
        if (owner instanceof net.minecraft.server.MinecraftServer server) {
            if (server.isSameThread()) restoreAtLifecycle();
            else server.execute(FixtureTunnelScenario::restoreAtLifecycle);
        }
    }

    private static synchronized void restoreResources() {
        restorePending = true;
        RESOURCES.restore();
        prepared = null;
        restorePending = false;
    }

    private static synchronized void restoreAtLifecycle() {
        try { restoreResources(); }
        catch (RuntimeException exception) { LOGGER.error("Tunnel fixture resource restoration pending", exception); }
    }

    static synchronized void sendStatus(FixtureSecurity.Context context, Consumer<Component> output) {
        Prepared value = requirePrepared(context);
        var result = base(value, "status");
        boolean baseline = audit(value).baselineMatches();
        int entities = entityCount(context.level(), context.player());
        boolean inventory = inventoryMatches(context.player());
        var player = context.player();
        boolean pose = Math.hypot(player.getX() - 257.5D, player.getZ() - 256.5D) <= 0.01D
                && Math.abs(player.getY() - 200.0D) <= 0.01D;
        boolean body = player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && player.getHealth() == 20.0F && player.getFoodData().getFoodLevel() == 20
                && player.getAbsorptionAmount() == 0.0F && player.getActiveEffects().isEmpty();
        result.put("baselineMatches", baseline);
        result.put("entities", entities);
        result.put("inventoryMatches", inventory);
        result.put("startPoseMatches", pose);
        result.put("playerBaselineMatches", body);
        var resources = resourceHealth();
        result.put("resourcesActive", resources.active() && !resources.restorePending());
        result.put("raysPerTick", resources.raysPerTick());
        result.put("originalRaysPerTick", RESOURCES.originalRaysPerTick());
        result.put("forcedChunks", resources.forcedChunks());
        result.put("ready", baseline && entities == 0 && inventory && pose && body
                && resources.intact());
        result.put("fixtureTickMutation", "none");
        output.accept(Component.literal(JSON.toJson(result)));
    }

    static synchronized void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        Prepared value = requirePrepared(context);
        var resources = resourceHealth();
        if (!resources.intact())
            throw new IllegalStateException("tunnel oracle requires the active unchanged fixture resources");
        var audit = audit(value);
        var result = base(value, "oracle");
        result.put("resourcesActive", true);
        result.put("forcedChunks", resources.forcedChunks());
        result.put("raysPerTick", resources.raysPerTick());
        result.put("baselineMatches", audit.baselineMatches());
        result.put("outsideChanged", audit.outsideChanged());
        result.put("completedCells", audit.completedCells());
        result.put("prefixCells", audit.prefixCells());
        result.put("partialCells", audit.partialCells());
        result.put("invalidInsideStates", audit.invalidInsideStates());
        result.put("poseMatch", audit.poseMatch());
        result.put("hazardPrefix", audit.hazardPrefix());
        result.put("player", java.util.List.of(context.player().getX(), context.player().getY(), context.player().getZ()));
        result.put("health", context.player().getHealth());
        result.put("pass", audit.pass() && context.player().isAlive() && context.player().getHealth() >= 20.0F);
        result.put("scope", "world-only; join with public Action and evaluation lease terminal receipts");
        output.accept(Component.literal(JSON.toJson(result)));
    }

    private static Map<String, Object> base(Prepared value, String kind) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "mcmcp_fixture_tunnel_v1");
        result.put("kind", kind);
        result.put("setupId", value.setupId);
        result.put("worldSessionId", value.session.requireCurrent(
                TestHarnessWorldSessionAccess.currentWorldSessionId()).toString());
        result.put("mode", value.plan.mode().wireName());
        result.put("baselineBlocks", FixtureTunnelPlan.VOLUME_SIZE);
        result.put("measurement", "completedCells/prefixCells count excavated two-block columns, not visited route cells");
        result.put("expectedResult", Map.of("excavatedCells", value.plan.hazard() ? 4 : value.plan.feet().size(),
                "completedMoves", value.plan.hazard() ? 3 : value.plan.route().size(),
                "confirmedBreaks", value.plan.hazard() ? 8 : value.plan.excavation().size(),
                "finalFeet", FixtureTunnelPlan.START.offset(value.plan.hazard() ? 3 : value.plan.length(), 0, 0).coordinates()));
        result.put("auditBounds", Map.of("min", FixtureTunnelPlan.MIN.coordinates(), "max", FixtureTunnelPlan.MAX.coordinates()));
        result.put("scenario", Map.of("lengthBlocks", value.plan.length(),
                "pattern", value.plan.branches() ? "branches" : "straight",
                "branchLengthBlocks", value.plan.branches() ? 3 : 0,
                "branchSpacingBlocks", value.plan.branches() ? 4 : 0,
                "startFeet", FixtureTunnelPlan.START.coordinates(), "entrance", FixtureTunnelPlan.ENTRANCE.coordinates(),
                "face", "west", "excavationCells", value.plan.feet().size(), "routeMoves", value.plan.route().size()));
        return result;
    }

    private static Prepared requirePrepared(FixtureSecurity.Context context) {
        if (prepared == null || prepared.context.server() != context.server()
                || prepared.context.level() != context.level() || prepared.context.player() != context.player())
            throw new IllegalStateException("no tunnel baseline belongs to this world and player");
        return prepared;
    }

    private static FixtureTunnelPlan.Audit audit(Prepared value) {
        var player = value.context.player();
        return FixtureTunnelPlan.audit(value.plan, cell -> {
            BlockPos position = pos(cell);
            if (!value.context.level().isLoaded(position)) return FixtureTunnelPlan.Material.OTHER;
            var actual = value.context.level().getBlockState(position);
            for (var material : FixtureTunnelPlan.Material.values())
                if (material != FixtureTunnelPlan.Material.OTHER && actual.equals(state(material))) return material;
            return FixtureTunnelPlan.Material.OTHER;
        }, player.getX(), player.getY(), player.getZ());
    }

    static void setBounded(ServerLevel level, FixtureTunnelPlan.Cell cell, BlockState state) {
        if (!FixtureTunnelPlan.contains(cell)) throw new IllegalArgumentException("tunnel write outside dedicated volume");
        level.setBlock(pos(cell), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
    }

    static BlockState state(FixtureTunnelPlan.Material material) {
        return switch (material) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            case BEDROCK -> Blocks.BEDROCK.defaultBlockState();
            case SEA_LANTERN -> Blocks.SEA_LANTERN.defaultBlockState();
            case OTHER -> throw new IllegalArgumentException("unknown material is not a fixture write");
        };
    }

    static void configurePlayer(ServerPlayer player) {
        // Do not call Arena.resetPlayer: it teleports to and clears entities near the unrelated arena.
        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        player.setGameMode(GameType.SURVIVAL);
        player.setRemainingFireTicks(0);
        player.setAirSupply(player.getMaxAirSupply());
        player.removeAllEffects();
        player.setHealth(20.0F);
        player.setAbsorptionAmount(0.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.NETHERITE_PICKAXE));
        player.getInventory().setSelectedSlot(0);
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static boolean inventoryMatches(ServerPlayer player) {
        if (player.getInventory().getSelectedSlot() != 0) return false;
        var tool = player.getInventory().getItem(0);
        if (!tool.is(Items.NETHERITE_PICKAXE) || tool.getCount() != 1 || tool.getDamageValue() != 0 || tool.isEnchanted()) return false;
        for (int slot = 1; slot < player.getInventory().getContainerSize(); slot++)
            if (!player.getInventory().getItem(slot).isEmpty()) return false;
        return true;
    }

    private static int entityCount(ServerLevel level, ServerPlayer owner) {
        return level.getEntities((Entity) null, bounds(), entity -> entity != owner && !entity.isRemoved()).size();
    }

    private static BlockPos pos(FixtureTunnelPlan.Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
    private static AABB bounds() { return AABB.encapsulatingFullBlocks(pos(FixtureTunnelPlan.MIN), pos(FixtureTunnelPlan.MAX)); }
    private record Prepared(FixtureSecurity.Context context, FixtureTunnelPlan.Plan plan, String setupId,
            FixtureTunnelSession session) { }
}
