package dev.aod.mcmcp.client;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Local-only HUD and Screen controls; neither accepts MCP or network input. */
public final class AutomationIndicatorController {
    static final long AGENT_NOTICE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private static final Identifier HUD_LAYER = Identifier.fromNamespaceAndPath(
            McmcpMod.MOD_ID, "automation_status");
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 4;
    private static final int MIN_LEFT_MARGIN = 8;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_PADDING = 8;
    private static final int BACKGROUND = 0xD0101010;

    private static final int OFF_COLOR = 0xFFB8B8B8;
    private static final int READY_COLOR = 0xFFFFA928;
    private static final int AGENT_COLOR = 0xFF45A7FF;
    private static final int RECOVERING_COLOR = 0xFFC17AFF;
    private static final int FAULT_COLOR = 0xFFFF5555;

    private final McmcpRuntime runtime;
    private Screen indicatorScreen;
    private IndicatorButton indicatorButton;
    private AutomationUiSnapshot.State lastObservedState = AutomationUiSnapshot.State.OFF;
    private long agentNoticeDeadlineNanos;

    public AutomationIndicatorController(McmcpRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_LAYER, this::renderHud);
    }

    public void onScreenInit(ScreenEvent.Init.Post event) {
        var screen = event.getScreen();
        int width = menuButtonWidth(screen.width);
        var button = new IndicatorButton(
                this, screen.width, screen.height, width, screen instanceof ChatScreen);
        indicatorScreen = screen;
        indicatorButton = button;
        event.addListener(button);
    }

    /**
     * Dispatches only the physical primary click that is inside the current MCMCP button.
     * The caller still cancels the enclosing raw mouse event so no underlying Screen widget runs.
     */
    public boolean dispatchPrimaryClick(Minecraft minecraft, MouseButtonInfo buttonInfo) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(buttonInfo, "buttonInfo");
        var button = indicatorButton;
        if (buttonInfo.button() != 0
                || button == null
                || minecraft.gui.screen() != indicatorScreen
                || !button.visible
                || !button.active) {
            return false;
        }
        double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
        double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
        if (!button.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        return button.mouseClicked(
                new MouseButtonEvent(mouseX, mouseY, buttonInfo), false);
    }

    private void renderHud(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        var minecraft = Minecraft.getInstance();
        var snapshot = runtime.automationUiSnapshot();
        observeState(snapshot.state(), System.nanoTime());
        if (minecraft.level == null
                || minecraft.player == null) {
            return;
        }

        if (snapshot.state() == AutomationUiSnapshot.State.AGENT
                && System.nanoTime() < agentNoticeDeadlineNanos) {
            drawAgentNotice(graphics, minecraft);
        }
        if (minecraft.gui.screen() != null) {
            return;
        }

        int x = lowerRightCoordinate(
                graphics.guiWidth(), ICON_SIZE, McmcpClientConfig.hudOffsetX());
        int y = lowerRightCoordinate(
                graphics.guiHeight(), ICON_SIZE, McmcpClientConfig.hudOffsetY());
        drawIcon(graphics, x, y, snapshot.state());
    }

    private void press(IndicatorButton button) {
        var snapshot = runtime.automationUiSnapshot();
        switch (pressAction(snapshot.state(), canEnable(Minecraft.getInstance(), snapshot))) {
            case ENABLE -> runtime.enableAutomationFromUi();
            case DISABLE -> runtime.disableAutomationFromUi();
            case NONE -> { }
        }
        refresh(button, System.nanoTime());
    }

    private void refresh(IndicatorButton button, long nowNanos) {
        var snapshot = runtime.automationUiSnapshot();
        observeState(snapshot.state(), nowNanos);
        boolean canEnable = canEnable(Minecraft.getInstance(), snapshot);
        button.setMessage(label(snapshot, canEnable));
        button.setTooltip(Tooltip.create(tooltip(snapshot, canEnable)));
        button.active = buttonActive(snapshot.state(), canEnable);
    }

    private void observeState(AutomationUiSnapshot.State state, long nowNanos) {
        if (state == AutomationUiSnapshot.State.AGENT
                && lastObservedState != AutomationUiSnapshot.State.AGENT) {
            agentNoticeDeadlineNanos = nowNanos + AGENT_NOTICE_NANOS;
        }
        lastObservedState = state;
    }

    static PressAction pressAction(AutomationUiSnapshot.State state, boolean canEnable) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case OFF -> canEnable ? PressAction.ENABLE : PressAction.NONE;
            case READY, AGENT, RECOVERING -> PressAction.DISABLE;
            case FAULT -> PressAction.NONE;
        };
    }

    static boolean buttonActive(AutomationUiSnapshot.State state, boolean canEnable) {
        return pressAction(state, canEnable) != PressAction.NONE;
    }

    static boolean canEnable(
            boolean snapshotWorldReady,
            boolean levelPresent,
            boolean playerPresent,
            boolean playerAlive) {
        return snapshotWorldReady && levelPresent && playerPresent && playerAlive;
    }

    private static boolean canEnable(
            Minecraft minecraft, AutomationUiSnapshot snapshot) {
        return canEnable(
                snapshot.worldReady(),
                minecraft.level != null,
                minecraft.player != null,
                minecraft.player != null && minecraft.player.isAlive());
    }

    private static Component label(AutomationUiSnapshot snapshot, boolean canEnable) {
        return switch (snapshot.state()) {
            case OFF -> canEnable
                    ? Component.translatable("gui.mcmcp.automation.off")
                    : Component.translatable("gui.mcmcp.automation.off_unavailable");
            case READY -> Component.translatable("gui.mcmcp.automation.ready");
            case AGENT -> Component.translatable("gui.mcmcp.automation.agent");
            case RECOVERING -> Component.translatable("gui.mcmcp.automation.recovering");
            case FAULT -> Component.translatable(
                    "gui.mcmcp.automation.fault",
                    snapshot.detail() == null ? "internal_error" : snapshot.detail());
        };
    }

    private static Component tooltip(AutomationUiSnapshot snapshot, boolean canEnable) {
        return switch (snapshot.state()) {
            case OFF -> canEnable
                    ? Component.translatable("gui.mcmcp.automation.tooltip.enable")
                    : Component.translatable("gui.mcmcp.automation.tooltip.unavailable");
            case READY, AGENT, RECOVERING ->
                    Component.translatable("gui.mcmcp.automation.tooltip.disable");
            case FAULT -> Component.translatable("gui.mcmcp.automation.tooltip.fault");
        };
    }

    private static void drawAgentNotice(
            GuiGraphicsExtractor graphics, Minecraft minecraft) {
        var notice = Component.translatable("gui.mcmcp.automation.agent_notice");
        int textWidth = minecraft.font.width(notice);
        int x = Math.max(2, (graphics.guiWidth() - textWidth) / 2);
        int y = Math.max(2, graphics.guiHeight() - 58);
        graphics.fill(x - 4, y - 3, x + textWidth + 4, y + 12, 0xC0000000);
        graphics.text(minecraft.font, notice, x, y, 0xFFFFFFFF, true);
    }

    private static void drawIcon(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            AutomationUiSnapshot.State state) {
        graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, BACKGROUND);
        int color = color(state);
        switch (state) {
            case OFF -> drawRing(graphics, x, y, color);
            case READY -> {
                drawRing(graphics, x, y, color);
                graphics.fill(x + 7, y + 4, x + 9, y + 9, color);
                graphics.fill(x + 8, y + 8, x + 12, y + 10, color);
            }
            case AGENT -> {
                graphics.fill(x + 3, y + 7, x + 11, y + 10, color);
                graphics.fill(x + 9, y + 4, x + 12, y + 13, color);
                graphics.fill(x + 12, y + 6, x + 14, y + 11, color);
            }
            case RECOVERING -> {
                graphics.fill(x + 3, y + 3, x + 13, y + 5, color);
                graphics.fill(x + 3, y + 5, x + 5, y + 10, color);
                graphics.fill(x + 11, y + 5, x + 13, y + 10, color);
                graphics.fill(x + 5, y + 10, x + 11, y + 12, color);
                graphics.fill(x + 7, y + 12, x + 9, y + 14, color);
            }
            case FAULT -> {
                graphics.outline(x + 2, y + 2, 12, 12, color);
                graphics.fill(x + 7, y + 4, x + 9, y + 10, color);
                graphics.fill(x + 7, y + 12, x + 9, y + 14, color);
            }
        }
    }

    private static void drawRing(
            GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 6, y + 3, x + 10, y + 5, color);
        graphics.fill(x + 4, y + 5, x + 6, y + 11, color);
        graphics.fill(x + 10, y + 5, x + 12, y + 11, color);
        graphics.fill(x + 6, y + 11, x + 10, y + 13, color);
    }

    private static int color(AutomationUiSnapshot.State state) {
        return switch (state) {
            case OFF -> OFF_COLOR;
            case READY -> READY_COLOR;
            case AGENT -> AGENT_COLOR;
            case RECOVERING -> RECOVERING_COLOR;
            case FAULT -> FAULT_COLOR;
        };
    }

    private static int menuButtonWidth(int screenWidth) {
        var font = Minecraft.getInstance().font;
        return menuButtonWidth(
                screenWidth,
                McmcpClientConfig.hudOffsetX(),
                font.width(Component.translatable("gui.mcmcp.automation.off")),
                font.width(Component.translatable("gui.mcmcp.automation.off_unavailable")),
                font.width(Component.translatable("gui.mcmcp.automation.ready")),
                font.width(Component.translatable("gui.mcmcp.automation.agent")),
                font.width(Component.translatable("gui.mcmcp.automation.recovering")),
                font.width(Component.translatable(
                        "gui.mcmcp.automation.fault", "internal_error")));
    }

    static int menuButtonWidth(int screenWidth, int rightOffset, int... labelWidths) {
        int longest = 0;
        for (int labelWidth : labelWidths) {
            longest = Math.max(longest, labelWidth);
        }
        int available = Math.max(1, screenWidth - Math.max(0, rightOffset) - MIN_LEFT_MARGIN);
        int preferred = longest + ICON_SIZE + ICON_TEXT_GAP + 2 * BUTTON_PADDING;
        return Math.min(preferred, available);
    }

    static int lowerRightCoordinate(int screenExtent, int elementExtent, int margin) {
        return Math.max(0, screenExtent - elementExtent - margin);
    }

    static int screenButtonY(
            int screenHeight, int buttonHeight, int margin, boolean chatScreen) {
        return chatScreen
                ? Math.min(Math.max(0, margin), Math.max(0, screenHeight - buttonHeight))
                : lowerRightCoordinate(screenHeight, buttonHeight, margin);
    }

    enum PressAction {
        ENABLE,
        DISABLE,
        NONE
    }

    private static final class IndicatorButton extends Button.Plain {
        private final AutomationIndicatorController controller;
        private final boolean chatScreen;

        private IndicatorButton(
                AutomationIndicatorController controller,
                int screenWidth,
                int screenHeight,
                int width,
                boolean chatScreen) {
            super(
                    lowerRightCoordinate(
                            screenWidth, width, McmcpClientConfig.hudOffsetX()),
                    screenButtonY(
                            screenHeight,
                            BUTTON_HEIGHT,
                            McmcpClientConfig.hudOffsetY(),
                            chatScreen),
                    width,
                    BUTTON_HEIGHT,
                    Component.empty(),
                    ignored -> { },
                    DEFAULT_NARRATION);
            this.controller = controller;
            this.chatScreen = chatScreen;
            setTooltipDelay(Duration.ofMillis(250));
            controller.refresh(this, System.nanoTime());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            controller.press(this);
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics,
                int mouseX,
                int mouseY,
                float partialTick) {
            int width = menuButtonWidth(graphics.guiWidth());
            setWidth(width);
            setX(lowerRightCoordinate(
                    graphics.guiWidth(), width, McmcpClientConfig.hudOffsetX()));
            setY(screenButtonY(
                    graphics.guiHeight(),
                    getHeight(),
                    McmcpClientConfig.hudOffsetY(),
                    chatScreen));
            controller.refresh(this, System.nanoTime());
            extractDefaultSprite(graphics);
            drawIcon(
                    graphics,
                    getX() + BUTTON_PADDING,
                    getY() + (getHeight() - ICON_SIZE) / 2,
                    controller.runtime.automationUiSnapshot().state());
            graphics.textRendererForWidget(
                            this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                    .acceptScrollingWithDefaultCenter(
                            getMessage(),
                            getX() + BUTTON_PADDING + ICON_SIZE + ICON_TEXT_GAP,
                            getRight() - BUTTON_PADDING,
                            getY(),
                            getBottom());
        }
    }
}
