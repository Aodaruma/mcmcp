package dev.aod.mcmcp;

import com.mojang.logging.LogUtils;
import dev.aod.mcmcp.client.AutomationIndicatorController;
import dev.aod.mcmcp.client.AgentBlockBreakChannel;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentMovementInput;
import dev.aod.mcmcp.client.AgentUseInputChannel;
import dev.aod.mcmcp.client.InputIsolationController;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import dev.aod.mcmcp.runtime.McpServerController;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
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
    private final InputIsolationController inputIsolation;
    private final AgentInputState agentInput = AgentInputState.global();
    private final AgentBlockBreakChannel agentBlockBreak = new AgentBlockBreakChannel(agentInput);
    private final AgentUseInputChannel agentUse = new AgentUseInputChannel(agentInput);
    private final ScreenOwnershipSignals screenOwnership = ScreenOwnershipSignals.global();

    public McmcpMod(IEventBus modEventBus, ModContainer modContainer) {
        var modVersion = modContainer.getModInfo().getVersion().toString();
        runtime = new McmcpRuntime(modVersion, NEOFORGE_VERSION);
        mcpServer = new McpServerController(runtime, modVersion);
        automationIndicator = new AutomationIndicatorController(runtime);
        inputIsolation = new InputIsolationController(runtime, automationIndicator);
        screenOwnership.setFailureHandler(runtime::onScreenOwnershipFailure);

        modContainer.registerConfig(ModConfig.Type.CLIENT, McmcpClientConfig.SPEC);
        modEventBus.addListener(automationIndicator::onRegisterGuiLayers);
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
        eventBus.addListener(EventPriority.LOWEST, this::onLevelSoundAtPosition);
        eventBus.addListener(EventPriority.LOWEST, this::onMovementInputUpdate);
        eventBus.addListener(inputIsolation::onMouseButton);
        eventBus.addListener(inputIsolation::onMouseScroll);
        eventBus.addListener(inputIsolation::onScreenMouseDragged);
        eventBus.addListener(inputIsolation::onScreenMouseScrolled);
        eventBus.addListener(this::onScreenOpening);
        eventBus.addListener(this::onScreenClosing);
        eventBus.addListener(automationIndicator::onScreenInit);
        eventBus.addListener(this::onClientStopping);
        eventBus.addListener(this::onClientStopped);

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
        inputIsolation.reconcilePhysicalKeyMappings();
        runtime.onPreTick(minecraft);
        inputIsolation.reconcilePhysicalKeyMappings();
    }

    private void onPostTick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        agentBlockBreak.onClientPostTick(minecraft);
        runtime.onPostTick(minecraft);
        // Use is emitted only after the runtime's current-tick target/item/safety reproof.
        agentUse.onClientPostTick(minecraft);
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        AgentMovementInput.install(event.getPlayer(), Minecraft.getInstance().options);
        runtime.onLoggingIn(Minecraft.getInstance());
        automationIndicator.onWorldJoined(Minecraft.getInstance());
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        runtime.onLoggingOut(Minecraft.getInstance());
    }

    private void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        AgentMovementInput.install(event.getNewPlayer(), Minecraft.getInstance().options);
        runtime.onPlayerClone(Minecraft.getInstance());
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            runtime.onLevelUnload(Minecraft.getInstance());
        }
    }

    private void onPauseChanged(ClientPauseChangeEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        agentInput.setPaused(event.isPaused(), System.nanoTime());
        if (event.isPaused() && minecraft.player != null
                && minecraft.player.input instanceof AgentMovementInput movementInput) {
            movementInput.apply(agentInput.movementSnapshot());
        }
        runtime.onPauseChanged(event.isPaused());
    }

    private void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.client.player.LocalPlayer player)) {
            return;
        }
        var movementInput = AgentMovementInput.install(
                player, Minecraft.getInstance().options, event.getInput());
        movementInput.apply(agentInput.movementSnapshot(player));
    }

    private void onLevelSoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (event.isCanceled()
                || event.getSound() == null
                || !(event.getLevel() instanceof ClientLevel clientLevel)
                || clientLevel != Minecraft.getInstance().level) {
            return;
        }
        var position = event.getPosition();
        runtime.onPositionSoundEvent(
                event.getSound().value().location().toString(),
                event.getSource(),
                position.x(),
                position.y(),
                position.z());
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() == null) {
            return;
        }
        var before = screenOwnership.snapshot().phase();
        boolean allowed = screenOwnership.allowScreenOpening(event.getNewScreen());
        if (!allowed
                && before != ScreenOwnershipSignals.Phase.IDLE
                && before != ScreenOwnershipSignals.Phase.FAILED
                && screenOwnership.snapshot().phase() == ScreenOwnershipSignals.Phase.FAILED) {
            runtime.onScreenOwnershipFailure("unexpected_screen_opened");
        }
    }

    private void onScreenClosing(ScreenEvent.Closing event) {
        screenOwnership.onScreenClosing(event.getScreen())
                .ifPresent(runtime::onScreenOwnershipFailure);
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
