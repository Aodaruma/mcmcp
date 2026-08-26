package dev.aodaruma.craftagent.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import org.slf4j.Logger;

import java.util.UUID;

/** One-shot Phase 5 setup for the disposable integrated-server fixture. */
final class FixturePhase5Autorun {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TOGGLE_ARMING_KEY = "key.craftagent.toggle_lock";
    private static final int ARM_DELAY_CLIENT_TICKS = 20;

    private final FixturePhase5AutorunConfig config;
    private volatile Stage stage = Stage.WAITING_FOR_WORLD;
    private int clientTicksAfterPrepare;
    private boolean clientSelectedSlotApplied;
    private boolean pauseOptionChanged;
    private boolean originalPauseOnLostFocus;

    private FixturePhase5Autorun(FixturePhase5AutorunConfig config) {
        this.config = config;
    }

    static void installIfRequested(IEventBus eventBus) {
        final FixturePhase5AutorunConfig config;
        try {
            var requested = FixturePhase5AutorunConfig.fromSystemProperties();
            if (requested.isEmpty()) {
                return;
            }
            config = requested.orElseThrow();
        } catch (IllegalArgumentException exception) {
            LOGGER.error("CraftAgent Phase 5 fixture autorun configuration rejected: {}",
                    exception.getMessage());
            return;
        }

        var autorun = new FixturePhase5Autorun(config);
        eventBus.addListener(autorun::onClientStarted);
        eventBus.addListener(autorun::onClientTick);
        eventBus.addListener(autorun::onClientStopping);
        LOGGER.warn("CraftAgent Phase 5 fixture autorun enabled: mode={}, autoArm={}",
                config.mode().wireName(), config.autoArm());
    }

    private void onClientStarted(ClientStartedEvent event) {
        originalPauseOnLostFocus = event.getClient().options.pauseOnLostFocus;
        event.getClient().options.pauseOnLostFocus = false;
        pauseOptionChanged = true;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (stage == Stage.WAITING_FOR_WORLD) {
            schedulePreparationIfReady(Minecraft.getInstance());
            return;
        }
        if (stage != Stage.PREPARED || !applyClientSelectedSlot(Minecraft.getInstance())) {
            return;
        }
        if (!config.autoArm()) {
            stage = Stage.COMPLETE;
            LOGGER.info("CraftAgent Phase 5 fixture prepared without auto-arming");
            return;
        }
        if (++clientTicksAfterPrepare < ARM_DELAY_CLIENT_TICKS) {
            return;
        }

        stage = Stage.ARMING;
        try {
            KeyMapping toggleArming = KeyMapping.get(TOGGLE_ARMING_KEY);
            if (toggleArming == null) {
                fail("arming key mapping is unavailable", null);
                return;
            }
            KeyMapping.click(toggleArming.getKey());
            stage = Stage.COMPLETE;
            LOGGER.info("CraftAgent Phase 5 fixture requested one local-arm toggle after {} client ticks",
                    ARM_DELAY_CLIENT_TICKS);
        } catch (RuntimeException exception) {
            fail("could not click the local-arm key mapping", exception);
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
        LOGGER.info("CraftAgent Phase 5 fixture applied client selected slot {} for mode={}",
                selectedSlot, config.mode().wireName());
        return true;
    }

    private void schedulePreparationIfReady(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        stage = Stage.PREPARING;
        try {
            server.execute(() -> prepareOnServer(server, playerId));
        } catch (RuntimeException exception) {
            fail("integrated-server preparation could not be scheduled", exception);
        }
    }

    private void prepareOnServer(IntegratedServer server, UUID playerId) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                fail("singleplayer owner was unavailable on the integrated server", null);
                return;
            }

            FixtureSecurity.Decision decision =
                    FixtureSecurity.authorize(player.createCommandSourceStack());
            if (!decision.allowed()) {
                fail("security boundary rejected autorun: " + decision.rejection(), null);
                return;
            }
            FixtureArena.load(decision.context());
            FixtureSecurity.Decision revalidated = FixtureSecurity.reauthorize(decision.context());
            if (!revalidated.allowed()) {
                fail("security boundary changed during arena setup: "
                        + revalidated.rejection(), null);
                return;
            }
            FixturePhase5Scenario.prepare(revalidated.context(), config.mode(),
                    component -> LOGGER.info("CraftAgent Phase 5 fixture: {}", component.getString()));
            stage = Stage.PREPARED;
            LOGGER.info("CraftAgent Phase 5 fixture server setup complete: mode={}",
                    config.mode().wireName());
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
        stage = Stage.FAILED;
        if (exception == null) {
            LOGGER.error("CraftAgent Phase 5 fixture autorun failed: {}. Local arming was not requested",
                    reason);
        } else {
            LOGGER.error("CraftAgent Phase 5 fixture autorun failed: {}. Local arming was not requested",
                    reason, exception);
        }
    }

    private enum Stage {
        WAITING_FOR_WORLD,
        PREPARING,
        PREPARED,
        ARMING,
        COMPLETE,
        FAILED
    }
}
