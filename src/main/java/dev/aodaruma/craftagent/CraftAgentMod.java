package dev.aodaruma.craftagent;

import com.mojang.logging.LogUtils;
import dev.aodaruma.craftagent.client.CraftAgentKeyBindings;
import dev.aodaruma.craftagent.runtime.CraftAgentRuntime;
import dev.aodaruma.craftagent.runtime.McpServerController;
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
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

@Mod(value = CraftAgentMod.MOD_ID, dist = Dist.CLIENT)
public final class CraftAgentMod {
    public static final String MOD_ID = "craftagent";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String NEOFORGE_VERSION = "26.2.0.59";

    private final CraftAgentRuntime runtime;
    private final McpServerController mcpServer;
    private final CraftAgentKeyBindings keys = new CraftAgentKeyBindings();

    public CraftAgentMod(IEventBus modEventBus, ModContainer modContainer) {
        var modVersion = modContainer.getModInfo().getVersion().toString();
        runtime = new CraftAgentRuntime(modVersion, NEOFORGE_VERSION);
        mcpServer = new McpServerController(runtime, modVersion);

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
        eventBus.addListener(this::onClientStopping);
        eventBus.addListener(this::onClientStopped);

        LOGGER.info("CraftAgent bootstrap: modVersion={}, physicalSide=CLIENT", modVersion);
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
            runtime.onManualInput("manual_keyboard_input");
        }
    }

    private void onMouseButtonInput(InputEvent.MouseButton.Pre event) {
        runtime.onManualInput("manual_mouse_button_input");
    }

    private void onMouseScrollInput(InputEvent.MouseScrollingEvent event) {
        runtime.onManualInput("manual_mouse_scroll_input");
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null) {
            runtime.onManualInput("unexpected_screen_opened");
        }
    }

    private void onClientStopping(ClientStoppingEvent event) {
        runtime.shutdown(event.getClient());
        mcpServer.close();
    }

    private void onClientStopped(ClientStoppedEvent event) {
        mcpServer.close();
        mcpServer.awaitStopped(5, TimeUnit.SECONDS);
    }
}
