package dev.aod.mcmcp.client;

import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Local HUD/pause-menu indicator; it never accepts MCP or network input. */
public final class AutomationIndicatorController {
    static final long ENABLE_CONFIRMATION_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final int ROBOT_SIZE = 12;
    private static final int ROBOT_GAP = 4;
    private static final int HUD_SIZE = 20;
    private static final int HUD_MARGIN = 3;
    private static final int PAUSE_HEIGHT = 20;
    private static final int PAUSE_MARGIN = 6;
    private static final int PAUSE_PADDING = 8;

    private static final int BACKGROUND = 0xC0101010;
    private static final int ACTIVE_COLOR = 0xFF55FF55;
    private static final int IDLE_COLOR = 0xFFFFD75F;
    private static final int DISABLED_COLOR = 0xFFFF5555;

    private final McmcpRuntime runtime;
    private long enableConfirmationDeadlineNanos;

    public AutomationIndicatorController(McmcpRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)) {
            return;
        }
        enableConfirmationDeadlineNanos = 0L;
        int width = pauseButtonWidth(event.getScreen().width);
        event.addListener(new IndicatorButton(
                this, event.getScreen().width, event.getScreen().height, width));
    }

    public void onHudRender(RenderGuiEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof PauseScreen) {
            return;
        }
        var snapshot = runtime.automationUiSnapshot();
        if (!snapshot.worldReady()) {
            return;
        }

        var graphics = event.getGuiGraphics();
        int x = lowerRightCoordinate(graphics.guiWidth(), HUD_SIZE, HUD_MARGIN);
        int y = lowerRightCoordinate(graphics.guiHeight(), HUD_SIZE, HUD_MARGIN);
        int color = color(snapshot.state());
        graphics.fill(x, y, x + HUD_SIZE, y + HUD_SIZE, BACKGROUND);
        graphics.outline(x, y, HUD_SIZE, HUD_SIZE, color);
        drawRobot(graphics, x + 4, y + 4, color);
    }

    private void press(IndicatorButton button) {
        long nowNanos = System.nanoTime();
        var snapshot = runtime.automationUiSnapshot();
        var action = pressAction(
                snapshot.state(), nowNanos < enableConfirmationDeadlineNanos);
        switch (action) {
            case DISABLE -> {
                enableConfirmationDeadlineNanos = 0L;
                runtime.disableAutomationFromUi(Minecraft.getInstance());
            }
            case REQUEST_ENABLE_CONFIRMATION ->
                    enableConfirmationDeadlineNanos = nowNanos + ENABLE_CONFIRMATION_NANOS;
            case ENABLE -> {
                if (runtime.enableAutomationFromUi(Minecraft.getInstance())) {
                    enableConfirmationDeadlineNanos = 0L;
                }
            }
        }
        refresh(button, System.nanoTime());
    }

    private void refresh(IndicatorButton button, long nowNanos) {
        var snapshot = runtime.automationUiSnapshot();
        boolean confirming = snapshot.state() == AutomationUiSnapshot.State.DISABLED
                && nowNanos < enableConfirmationDeadlineNanos;
        if (snapshot.state() != AutomationUiSnapshot.State.DISABLED) {
            enableConfirmationDeadlineNanos = 0L;
        }
        button.setMessage(label(snapshot.state(), confirming));
        button.setTooltip(Tooltip.create(tooltip(snapshot.state(), confirming)));
        button.setIndicatorColor(confirming ? IDLE_COLOR : color(snapshot.state()));
    }

    static PressAction pressAction(
            AutomationUiSnapshot.State state,
            boolean enableConfirmationActive) {
        Objects.requireNonNull(state, "state");
        if (state != AutomationUiSnapshot.State.DISABLED) {
            return PressAction.DISABLE;
        }
        return enableConfirmationActive
                ? PressAction.ENABLE
                : PressAction.REQUEST_ENABLE_CONFIRMATION;
    }

    private static Component label(AutomationUiSnapshot.State state, boolean confirming) {
        if (confirming) {
            return Component.translatable("gui.mcmcp.automation.confirm_enable")
                    .withStyle(ChatFormatting.YELLOW);
        }
        return switch (state) {
            case ACTIVE -> Component.translatable("gui.mcmcp.automation.active")
                    .withStyle(ChatFormatting.GREEN);
            case IDLE -> Component.translatable("gui.mcmcp.automation.idle")
                    .withStyle(ChatFormatting.YELLOW);
            case DISABLED -> Component.translatable("gui.mcmcp.automation.disabled")
                    .withStyle(ChatFormatting.RED);
        };
    }

    private static Component tooltip(AutomationUiSnapshot.State state, boolean confirming) {
        if (confirming) {
            return Component.translatable("gui.mcmcp.automation.tooltip.confirm_enable");
        }
        return switch (state) {
            case ACTIVE, IDLE ->
                    Component.translatable("gui.mcmcp.automation.tooltip.disable");
            case DISABLED ->
                    Component.translatable("gui.mcmcp.automation.tooltip.enable");
        };
    }

    private static int color(AutomationUiSnapshot.State state) {
        return switch (state) {
            case ACTIVE -> ACTIVE_COLOR;
            case IDLE -> IDLE_COLOR;
            case DISABLED -> DISABLED_COLOR;
        };
    }

    private static int pauseButtonWidth(int screenWidth) {
        var font = Minecraft.getInstance().font;
        return pauseButtonWidth(
                screenWidth,
                font.width(label(AutomationUiSnapshot.State.ACTIVE, false)),
                font.width(label(AutomationUiSnapshot.State.IDLE, false)),
                font.width(label(AutomationUiSnapshot.State.DISABLED, false)),
                font.width(label(AutomationUiSnapshot.State.DISABLED, true)));
    }

    static int pauseButtonWidth(int screenWidth, int... labelWidths) {
        int longest = 0;
        for (int labelWidth : labelWidths) {
            longest = Math.max(longest, labelWidth);
        }
        int available = Math.max(1, screenWidth - 2 * PAUSE_MARGIN);
        int preferred = longest + ROBOT_SIZE + ROBOT_GAP + 2 * PAUSE_PADDING;
        return Math.min(preferred, available);
    }

    static int lowerRightCoordinate(int screenExtent, int elementExtent, int margin) {
        return Math.max(0, screenExtent - elementExtent - margin);
    }

    /** Twelve-pixel robot head: antenna, outlined face and two status-coloured eyes. */
    private static void drawRobot(GuiGraphicsExtractor graphics, int x, int y, int statusColor) {
        graphics.fill(x + 5, y, x + 7, y + 2, statusColor);
        graphics.fill(x + 2, y + 2, x + 10, y + 4, statusColor);
        graphics.fill(x, y + 4, x + 12, y + 12, statusColor);
        graphics.fill(x + 2, y + 6, x + 10, y + 10, 0xFF202020);
        graphics.fill(x + 3, y + 7, x + 5, y + 9, statusColor);
        graphics.fill(x + 7, y + 7, x + 9, y + 9, statusColor);
    }

    enum PressAction {
        DISABLE,
        REQUEST_ENABLE_CONFIRMATION,
        ENABLE
    }

    private static final class IndicatorButton extends Button.Plain {
        private final AutomationIndicatorController controller;
        private int indicatorColor = DISABLED_COLOR;

        private IndicatorButton(
                AutomationIndicatorController controller,
                int screenWidth,
                int screenHeight,
                int width) {
            super(
                    lowerRightCoordinate(screenWidth, width, PAUSE_MARGIN),
                    lowerRightCoordinate(screenHeight, PAUSE_HEIGHT, PAUSE_MARGIN),
                    width,
                    PAUSE_HEIGHT,
                    Component.empty(),
                    ignored -> { },
                    DEFAULT_NARRATION);
            this.controller = controller;
            setTooltipDelay(java.time.Duration.ofMillis(250));
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
            int width = pauseButtonWidth(graphics.guiWidth());
            setWidth(width);
            setX(lowerRightCoordinate(graphics.guiWidth(), width, PAUSE_MARGIN));
            setY(lowerRightCoordinate(graphics.guiHeight(), getHeight(), PAUSE_MARGIN));
            controller.refresh(this, System.nanoTime());
            extractDefaultSprite(graphics);
            graphics.textRendererForWidget(
                            this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                    .acceptScrollingWithDefaultCenter(
                            getMessage(),
                            getX() + PAUSE_PADDING + ROBOT_SIZE + ROBOT_GAP,
                            getRight() - PAUSE_PADDING,
                            getY(),
                            getBottom());
            drawRobot(
                    graphics,
                    getX() + PAUSE_PADDING,
                    getY() + (getHeight() - ROBOT_SIZE) / 2,
                    indicatorColor);
        }

        private void setIndicatorColor(int indicatorColor) {
            this.indicatorColor = indicatorColor;
        }
    }
}
