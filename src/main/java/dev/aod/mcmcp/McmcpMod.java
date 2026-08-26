package dev.aod.mcmcp;

import com.mojang.logging.LogUtils;
import dev.aod.mcmcp.client.AutomationIndicatorController;
import dev.aod.mcmcp.client.McmcpKeyBindings;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import dev.aod.mcmcp.runtime.McpServerController;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

@Mod(value = McmcpMod.MOD_ID, dist = Dist.CLIENT)
public final class McmcpMod {
    public static final String MOD_ID = "mcmcp";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String NEOFORGE_VERSION = "26.2.0.59";

    private final McmcpRuntime runtime;
    private final McpServerController mcpServer;
    private final AutomationIndicatorController automationIndicator;
    private final ScreenOwnershipSignals screenOwnership = ScreenOwnershipSignals.global();
    private final McmcpKeyBindings keys = new McmcpKeyBindings();

    public McmcpMod(IEventBus modEventBus, ModContainer modContainer) {
        var modVersion = modContainer.getModInfo().getVersion().toString();
        runtime = new McmcpRuntime(modVersion, NEOFORGE_VERSION);
        mcpServer = new McpServerController(runtime, modVersion);
        automationIndicator = new AutomationIndicatorController(runtime);
        screenOwnership.setFailureHandler(runtime::onManualInput);

        modEventBus.addListener(keys::register);
        var eventBus = NeoForge.EVENT_BUS;
        eventBus.addListener(this::onClientStarted);
        eventBus.addListener(this::onResourcesReady);
        eventBus.addListener(this::onPreTick);
        eventBus.addListener(this::onPostTick);
        eventBus.addListener(this::onLoggingIn);
        eventBus.addListener(this::onLoggingOut);
        eventBus.addListener(this::onPlayerClone);
        eventBus.addListener(this::onLevelUnload);
        eventBus.addListener(this::onPauseChanged);
        eventBus.addListener(this::onKeyInput);
        eventBus.addListener(this::onMouseButtonInput);
        eventBus.addListener(this::onMouseScrollInput);
        eventBus.addListener(this::onScreenOpening);
        eventBus.addListener(this::onScreenClosing);
        eventBus.addListener(automationIndicator::onScreenInit);
        eventBus.addListener(automationIndicator::onHudRender);
        eventBus.addListener(this::onClientStopping);
        eventBus.addListener(this::onClientStopped);
        eventBus.addListener(this::onServerPostTick);
        eventBus.addListener(this::onServerStopping);

        LOGGER.info("MCMCP bootstrap: modVersion={}, physicalSide=CLIENT", modVersion);
    }

    private void onClientStarted(ClientStartedEvent event) {
        mcpServer.start(event.getClient().gameDirectory.toPath());
    }

    private void onResourcesReady(ClientResourceLoadFinishedEvent event) {
        if (event.isInitial()) {
            runtime.onResourcesReady();
        }
    }

    private void onPreTick(ClientTickEvent.Pre event) {
        var minecraft = Minecraft.getInstance();
        screenOwnership.onClientTick();
        keys.handleClientTick(minecraft, runtime);
        runtime.onPreTick(minecraft);
    }

    private void onPostTick(ClientTickEvent.Post event) {
        runtime.onPostTick(Minecraft.getInstance());
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        runtime.onLoggingIn(Minecraft.getInstance());
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        runtime.onLoggingOut(Minecraft.getInstance());
    }

    private void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        runtime.onPlayerClone(Minecraft.getInstance());
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            runtime.onLevelUnload(Minecraft.getInstance());
        }
    }

    private void onPauseChanged(ClientPauseChangeEvent.Post event) {
        runtime.onPauseChanged(event.isPaused());
    }

    private void onKeyInput(InputEvent.Key event) {
        if (!keys.isLocalControlKey(event.getKeyEvent())) {
            screenOwnership.onManualInput("manual_keyboard_input");
            runtime.onManualInput("manual_keyboard_input");
        }
    }

    private void onMouseButtonInput(InputEvent.MouseButton.Pre event) {
        screenOwnership.onManualInput("manual_mouse_button_input");
        runtime.onManualInput("manual_mouse_button_input");
    }

    private void onMouseScrollInput(InputEvent.MouseScrollingEvent event) {
        screenOwnership.onManualInput("manual_mouse_scroll_input");
        runtime.onManualInput("manual_mouse_scroll_input");
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null
                && !screenOwnership.allowScreenOpening(event.getNewScreen())) {
            runtime.onManualInput("unexpected_screen_opened");
        }
    }

    private void onScreenClosing(ScreenEvent.Closing event) {
        screenOwnership.onScreenClosing(event.getScreen())
                .ifPresent(runtime::onManualInput);
    }

    private void onClientStopping(ClientStoppingEvent event) {
        runtime.shutdown(event.getClient());
        mcpServer.close();
    }

    private void onClientStopped(ClientStoppedEvent event) {
        mcpServer.close();
        mcpServer.awaitStopped(5, TimeUnit.SECONDS);
    }

    private void onServerPostTick(ServerTickEvent.Post event) {
        runtime.onServerPostTick(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        runtime.onServerStopping(event.getServer());
    }
}
