package dev.aodaruma.craftagent.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot, property-gated Phase 3 live-test setup for the disposable harness world.
 *
 * <p>All world changes are scheduled onto the integrated-server thread and pass through the same
 * {@link FixtureSecurity} boundary as fixture commands. The production source set cannot reference
 * this class.</p>
 */
final class FixturePhase3Autorun {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TOGGLE_ARMING_KEY = "key.craftagent.toggle_lock";
    private static final int ARM_DELAY_CLIENT_TICKS = 20;

    private final FixturePhase3AutorunConfig config;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.WAITING_FOR_WORLD);

    private int clientTicksAfterPrepare;
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
            LOGGER.error("CraftAgent Phase 3 autorun configuration rejected: {}", exception.getMessage());
            return;
        }

        FixturePhase3Autorun autorun = new FixturePhase3Autorun(config);
        eventBus.addListener(autorun::onClientStarted);
        eventBus.addListener(autorun::onClientTick);
        eventBus.addListener(autorun::onClientStopping);
        LOGGER.warn("CraftAgent Phase 3 fixture autorun enabled: mode={}, autoArm={}",
                config.mode().name().toLowerCase(Locale.ROOT), config.autoArm());
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
        if (current != Stage.PREPARED) {
            return;
        }
        if (!applyClientSelectedSlot(Minecraft.getInstance())) {
            return;
        }
        if (!config.autoArm()) {
            if (stage.compareAndSet(Stage.PREPARED, Stage.COMPLETE)) {
                LOGGER.info("CraftAgent Phase 3 fixture prepared without auto-arming");
            }
            return;
        }

        clientTicksAfterPrepare++;
        if (clientTicksAfterPrepare < ARM_DELAY_CLIENT_TICKS
                || !stage.compareAndSet(Stage.PREPARED, Stage.ARMING)) {
            return;
        }

        try {
            KeyMapping toggleArming = KeyMapping.get(TOGGLE_ARMING_KEY);
            if (toggleArming == null) {
                fail("arming key mapping is unavailable", null);
                return;
            }
            KeyMapping.click(toggleArming.getKey());
            stage.set(Stage.COMPLETE);
            LOGGER.info("CraftAgent Phase 3 fixture requested one local-arm toggle after {} client ticks",
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
        LOGGER.info("CraftAgent Phase 3 fixture applied client selected slot {} for mode={}",
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
            FixturePhase3Scenario.prepare(revalidated.context(), scenarioMode(config.mode()),
                    component -> LOGGER.info("CraftAgent Phase 3 fixture: {}", component.getString()));
            stage.set(Stage.PREPARED);
            LOGGER.info("CraftAgent Phase 3 fixture server setup complete: mode={}",
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
            LOGGER.error("CraftAgent Phase 3 fixture autorun failed: {}. Local arming was not requested", reason);
        } else {
            LOGGER.error("CraftAgent Phase 3 fixture autorun failed: {}. Local arming was not requested",
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
        };
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
