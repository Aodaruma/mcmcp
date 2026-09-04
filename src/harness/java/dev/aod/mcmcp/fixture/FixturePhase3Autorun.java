package dev.aod.mcmcp.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot, property-gated Phase 3/4 live-test setup for the disposable harness world.
 *
 * <p>All world changes are scheduled onto the integrated-server thread and pass through the same
 * {@link FixtureSecurity} boundary as fixture commands. The production source set cannot reference
 * this class.</p>
 */
final class FixturePhase3Autorun {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final FixturePhase3AutorunConfig config;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.WAITING_FOR_WORLD);
    private final FixturePhase4DivergenceTrigger divergenceTrigger =
            new FixturePhase4DivergenceTrigger();

    private boolean clientSelectedSlotApplied;
    private boolean pauseOptionChanged;
    private boolean originalPauseOnLostFocus;

    private FixturePhase3Autorun(FixturePhase3AutorunConfig config) {
        this.config = config;
    }

    static void installIfRequested(IEventBus eventBus) {
        final FixturePhase3AutorunConfig config;
        try {
            var requested = FixturePhase3AutorunConfig.fromSystemProperties();
            if (requested.isEmpty()) {
                return;
            }
            config = requested.orElseThrow();
        } catch (IllegalArgumentException exception) {
            LOGGER.error("MCMCP fixture autorun configuration rejected: {}", exception.getMessage());
            return;
        }

        FixturePhase3Autorun autorun = new FixturePhase3Autorun(config);
        eventBus.addListener(autorun::onClientStarted);
        eventBus.addListener(autorun::onClientTick);
        eventBus.addListener(autorun::onClientStopping);
        LOGGER.warn("MCMCP fixture autorun enabled: mode={}, setupOnly=true",
                config.mode().name().toLowerCase(Locale.ROOT));
    }

    private void onClientStarted(ClientStartedEvent event) {
        originalPauseOnLostFocus = event.getClient().options.pauseOnLostFocus;
        event.getClient().options.pauseOnLostFocus = false;
        pauseOptionChanged = true;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Stage current = stage.get();
        if (current == Stage.WAITING_FOR_WORLD) {
            schedulePreparationIfReady(Minecraft.getInstance());
            return;
        }
        if (current == Stage.COMPLETE) {
            scheduleDivergenceIfOwnedBreak(Minecraft.getInstance());
            return;
        }
        if (current != Stage.PREPARED) {
            return;
        }
        if (!applyClientSelectedSlot(Minecraft.getInstance())) {
            return;
        }
        if (stage.compareAndSet(Stage.PREPARED, Stage.COMPLETE)) {
            LOGGER.info("MCMCP fixture prepared; press the Screen status button once to authorize");
        }
    }

    private void scheduleDivergenceIfOwnedBreak(Minecraft minecraft) {
        if (config.mode() != FixturePhase3AutorunConfig.Mode.DIVERGENCE
                || !divergenceTrigger.observe(ownedDivergenceBreakActive(minecraft))) {
            return;
        }
        var server = minecraft.getSingleplayerServer();
        var player = minecraft.player;
        if (server == null || player == null) {
            fail("divergence injection lost the integrated-server owner", null);
            return;
        }

        UUID playerId = player.getUUID();
        try {
            server.execute(() -> injectDivergenceOnServer(server, playerId));
            LOGGER.info("MCMCP fixture scheduled Phase 4 guard divergence after {} "
                            + "consecutive owned-break ticks",
                    FixturePhase4DivergenceTrigger.REQUIRED_CONSECUTIVE_OWNED_BREAK_TICKS);
        } catch (RuntimeException exception) {
            fail("divergence injection could not be scheduled", exception);
        }
    }

    private static boolean ownedDivergenceBreakActive(Minecraft minecraft) {
        var player = minecraft.player;
        var level = minecraft.level;
        var gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null
                || !minecraft.options.keyAttack.isDown()
                || !gameMode.isDestroying()
                || !player.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(FixturePhase4Scenario.TARGET_A)) {
            return false;
        }
        return level.getBlockState(FixturePhase4Scenario.TARGET_A).is(Blocks.OBSIDIAN)
                && level.getBlockState(FixturePhase4Scenario.TARGET_B).is(Blocks.DIRT);
    }

    private void injectDivergenceOnServer(
            net.minecraft.client.server.IntegratedServer server, UUID playerId) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                fail("divergence injection owner was unavailable on the integrated server", null);
                return;
            }
            FixtureSecurity.Decision decision =
                    FixtureSecurity.authorize(player.createCommandSourceStack());
            if (!decision.allowed()) {
                fail("security boundary rejected divergence injection: "
                        + decision.rejection(), null);
                return;
            }
            FixtureSecurity.Decision revalidated =
                    FixtureSecurity.reauthorize(decision.context());
            if (!revalidated.allowed()) {
                fail("security boundary changed before divergence injection: "
                        + revalidated.rejection(), null);
                return;
            }
            var context = revalidated.context();
            if (!context.level().getBlockState(FixturePhase4Scenario.TARGET_A)
                    .is(Blocks.OBSIDIAN)
                    || !context.level().getBlockState(FixturePhase4Scenario.TARGET_B)
                            .is(Blocks.DIRT)) {
                fail("divergence injection source or guard changed before server mutation", null);
                return;
            }
            FixturePhase4Scenario.introduceDivergence(context);
            if (!context.level().getBlockState(FixturePhase4Scenario.TARGET_B)
                    .is(Blocks.GOLD_BLOCK)) {
                fail("divergence injection did not produce the gold guard state", null);
                return;
            }
            LOGGER.warn("MCMCP fixture injected Phase 4 guard divergence during the "
                    + "owned obsidian break");
        } catch (RuntimeException exception) {
            fail("server-side divergence injection failed", exception);
        }
    }

    private boolean applyClientSelectedSlot(Minecraft minecraft) {
        if (clientSelectedSlotApplied) {
            return true;
        }
        var player = minecraft.player;
        if (player == null) {
            return false;
        }
        int selectedSlot = config.mode().selectedSlot();
        player.getInventory().setSelectedSlot(selectedSlot);
        if (player.getInventory().getSelectedSlot() != selectedSlot) {
            fail("client selected slot did not match fixture mode", null);
            return false;
        }
        clientSelectedSlotApplied = true;
        LOGGER.info("MCMCP fixture applied client selected slot {} for mode={}",
                selectedSlot, config.mode().name().toLowerCase(Locale.ROOT));
        return true;
    }

    private void schedulePreparationIfReady(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        if (!stage.compareAndSet(Stage.WAITING_FOR_WORLD, Stage.PREPARING)) {
            return;
        }

        try {
            server.execute(() -> prepareOnServer(server, playerId));
        } catch (RuntimeException exception) {
            fail("integrated-server preparation could not be scheduled", exception);
        }
    }

    private void prepareOnServer(net.minecraft.client.server.IntegratedServer server, UUID playerId) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                fail("singleplayer owner was unavailable on the integrated server", null);
                return;
            }

            FixtureSecurity.Decision decision = FixtureSecurity.authorize(player.createCommandSourceStack());
            if (!decision.allowed()) {
                fail("security boundary rejected autorun: " + decision.rejection(), null);
                return;
            }

            // A live autorun always rebuilds the bounded arena first, so a new disposable world and
            // a previously used harness world begin from identical server-authoritative state.
            FixtureArena.load(decision.context());
            FixtureSecurity.Decision revalidated = FixtureSecurity.reauthorize(decision.context());
            if (!revalidated.allowed()) {
                fail("security boundary changed during arena setup: " + revalidated.rejection(), null);
                return;
            }
            if (config.mode().creativeCapture()) {
                FixtureCreativeCaptureScenario.prepare(
                        revalidated.context(),
                        component -> LOGGER.info(
                                "MCMCP creative capture fixture: {}", component.getString()));
            } else if (config.mode().phase4()) {
                FixturePhase4Scenario.prepare(
                        revalidated.context(), ScenarioRouting.phase4(config.mode()),
                        component -> LOGGER.info("MCMCP Phase 4 fixture: {}", component.getString()));
            } else {
                FixturePhase3Scenario.prepare(revalidated.context(), scenarioMode(config.mode()),
                        component -> LOGGER.info("MCMCP Phase 3 fixture: {}", component.getString()));
            }
            stage.set(Stage.PREPARED);
            LOGGER.info("MCMCP fixture server setup complete: mode={}",
                    config.mode().name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            fail("server-side fixture preparation failed", exception);
        }
    }

    private void onClientStopping(ClientStoppingEvent event) {
        if (pauseOptionChanged) {
            event.getClient().options.pauseOnLostFocus = originalPauseOnLostFocus;
            pauseOptionChanged = false;
        }
    }

    private void fail(String reason, RuntimeException exception) {
        stage.set(Stage.FAILED);
        if (exception == null) {
            LOGGER.error("MCMCP fixture autorun failed: {}. Local authorization remains unchanged", reason);
        } else {
            LOGGER.error("MCMCP fixture autorun failed: {}. Local authorization remains unchanged",
                    reason, exception);
        }
    }

    private static FixturePhase3Scenario.Mode scenarioMode(FixturePhase3AutorunConfig.Mode mode) {
        return switch (mode) {
            case NAVIGATE -> FixturePhase3Scenario.Mode.NAVIGATE;
            case BREAK -> FixturePhase3Scenario.Mode.BREAK;
            case PLACE -> FixturePhase3Scenario.Mode.PLACE;
            case LEVER -> FixturePhase3Scenario.Mode.LEVER;
            case COW -> FixturePhase3Scenario.Mode.COW;
            case RESET -> FixturePhase3Scenario.Mode.RESET;
            case ALL_SATISFIED, MUTATIONS, WATERLOGGED, DIRECTIONAL_STAIRS,
                    DIRECTIONAL_STAIRS_MATRIX, HOPPER, SHORTAGE, DIVERGENCE, HIDDEN,
                    BUILD_RUNNER, CREATIVE_CAPTURE ->
                    throw new IllegalArgumentException("Phase 4 mode cannot use the Phase 3 scenario");
        };
    }

    static final class ScenarioRouting {
        private ScenarioRouting() {}

        static FixturePhase4Scenario.Mode phase4(FixturePhase3AutorunConfig.Mode mode) {
            return switch (mode) {
                case ALL_SATISFIED -> FixturePhase4Scenario.Mode.ALL_SATISFIED;
                case MUTATIONS -> FixturePhase4Scenario.Mode.MUTATIONS;
                case WATERLOGGED -> FixturePhase4Scenario.Mode.WATERLOGGED;
                case DIRECTIONAL_STAIRS -> FixturePhase4Scenario.Mode.DIRECTIONAL_STAIRS;
                case DIRECTIONAL_STAIRS_MATRIX ->
                        FixturePhase4Scenario.Mode.DIRECTIONAL_STAIRS_MATRIX;
                case HOPPER -> FixturePhase4Scenario.Mode.HOPPER;
                case SHORTAGE -> FixturePhase4Scenario.Mode.SHORTAGE;
                case DIVERGENCE -> FixturePhase4Scenario.Mode.DIVERGENCE;
                case HIDDEN -> FixturePhase4Scenario.Mode.HIDDEN;
                case BUILD_RUNNER -> FixturePhase4Scenario.Mode.BUILD_RUNNER;
                case NAVIGATE, BREAK, PLACE, LEVER, COW, RESET, CREATIVE_CAPTURE ->
                        throw new IllegalArgumentException(
                                "Phase 3 mode cannot use the Phase 4 scenario");
            };
        }
    }

    private enum Stage {
        WAITING_FOR_WORLD,
        PREPARING,
        PREPARED,
        COMPLETE,
        FAILED
    }
}
