package dev.aod.mcmcp.client;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.time.Duration;
import java.nio.file.Path;
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
    private static final int EXECUTION_BORDER_COLOR = 0xFFFFFF00;
    private static final int EVALUATION_BORDER_COLOR = 0xFF42D9F5;
    private static final int CONSENT_BORDER_COLOR = 0xFF55E88A;
    private static final int EXECUTION_BORDER_WIDTH = 2;

    private static final int OFF_COLOR = 0xFFB8B8B8;
    private static final int READY_COLOR = 0xFFFFA928;
    private static final int EVALUATING_COLOR = 0xFF42D9F5;
    private static final int CONSENT_PENDING_COLOR = 0xFF55E88A;
    private static final int AGENT_COLOR = 0xFF45A7FF;
    private static final int RECOVERING_COLOR = 0xFFC17AFF;
    private static final int FAULT_COLOR = 0xFFFF5555;

    private final McmcpRuntime runtime;
    private Screen indicatorScreen;
    private IndicatorButton indicatorButton;
    private AutomationUiSnapshot.State lastObservedState = AutomationUiSnapshot.State.OFF;
    private long agentNoticeDeadlineNanos;
    private boolean setupNoticeShown;

    public AutomationIndicatorController(McmcpRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        runtime.installEntityAttackConsentUi(this);
    }

    public void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_LAYER, this::renderHud);
    }

    public void onScreenInit(ScreenEvent.Init.Post event) {
        var screen = event.getScreen();
        var minecraft = Minecraft.getInstance();
        if (!statusButtonVisible(minecraft.level != null, minecraft.player != null)) {
            indicatorScreen = null;
            indicatorButton = null;
            return;
        }
        int width = menuButtonWidth(screen.width);
        var button = new IndicatorButton(
                this, screen.width, screen.height, width, screen instanceof ChatScreen);
        indicatorScreen = screen;
        indicatorButton = button;
        event.addListener(button);
        if (screen instanceof PauseScreen) {
            int setupWidth = Math.min(120, Math.max(1, button.getX() - 2 * MIN_LEFT_MARGIN));
            int setupX = button.getX() - setupWidth - 4;
            if (setupX >= MIN_LEFT_MARGIN) {
                var setup = Button.builder(
                                Component.translatable("gui.mcmcp.setup.open"),
                                ignored -> openSetupChooser(screen))
                        .bounds(setupX, button.getY(), setupWidth, BUTTON_HEIGHT)
                        .build();
                setup.active = runtime.automationUiSnapshot().state()
                        == AutomationUiSnapshot.State.OFF;
                event.addListener(setup);
            }
        }
    }

    static boolean statusButtonVisible(boolean worldLoaded, boolean playerPresent) {
        return worldLoaded && playerPresent;
    }

    public void onWorldJoined(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (setupNoticeShown || minecraft.player == null) return;
        setupNoticeShown = true;
        Path userHome = Path.of(System.getProperty("user.home", "."));
        if (!McpClientAutoConfigurator.anyClientConfigured(
                userHome, McmcpClientConfig.port())) {
            minecraft.player.sendOverlayMessage(
                    Component.translatable("gui.mcmcp.setup.first_notice"));
        }
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

    /** Dispatches only a physical primary click into the dedicated consent prompt. */
    public boolean dispatchConsentPromptPrimaryClick(
            Minecraft minecraft, MouseButtonInfo buttonInfo) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(buttonInfo, "buttonInfo");
        if (buttonInfo.button() != 0
                || !(minecraft.gui.screen() instanceof EntityAttackConsentScreen prompt)) {
            return false;
        }
        return prompt.dispatchPhysicalPrimaryClick(
                minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
                minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
    }

    /** Physical Esc path while the dedicated consent screen owns input. */
    public boolean cancelConsentPromptFromPhysicalEscape(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (!(minecraft.gui.screen() instanceof EntityAttackConsentScreen prompt)) {
            return false;
        }
        prompt.cancelAndClose();
        return true;
    }

    /** Runtime-only presentation hook; the sink itself remains held by this controller. */
    public void openEntityAttackConsentPrompt(
            ScopedEntityAttackConsentStore.Scope scope,
            EntityAttackConsentPromptSink sink) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sink, "sink");
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.gui.screen() != null) {
            sink.cancel();
            throw new IllegalStateException("entity attack consent screen is not available");
        }
        minecraft.setScreenAndShow(new EntityAttackConsentScreen(scope, sink));
    }

    private void renderHud(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        var minecraft = Minecraft.getInstance();
        var snapshot = runtime.automationUiSnapshot();
        observeState(snapshot.state(), System.nanoTime());
        if (minecraft.level == null
                || minecraft.player == null) {
            return;
        }

        if (executionBorderVisible(snapshot.state())) {
            drawExecutionBorder(graphics);
        } else if (consentBorderVisible(snapshot.state())) {
            drawConsentBorder(graphics);
        } else if (evaluationBorderVisible(snapshot.state())) {
            drawEvaluationBorder(graphics);
        }

        if ((snapshot.state() == AutomationUiSnapshot.State.AGENT
                || snapshot.state() == AutomationUiSnapshot.State.EVALUATING)
                && System.nanoTime() < agentNoticeDeadlineNanos) {
            drawActiveNotice(graphics, minecraft, snapshot.state());
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
            case ENABLE -> enableOrRequestMultiplayerConsent();
            case DISABLE -> runtime.disableAutomationFromUi();
            case NONE -> { }
        }
        refresh(button, System.nanoTime());
    }

    private void enableOrRequestMultiplayerConsent() {
        var minecraft = Minecraft.getInstance();
        var server = minecraft.getCurrentServer();
        Path allowlist = multiplayerAllowlist(minecraft);
        if (!minecraft.isMultiplayerServer()
                || server != null && MultiplayerAllowlist.allows(allowlist, server.ip)) {
            runtime.enableAutomationFromUi(minecraft);
            return;
        }
        if (server == null) return;
        Screen parent = minecraft.gui.screen();
        String address = server.ip;
        minecraft.setScreenAndShow(new MultiplayerConsentScreen(
                address,
                (accepted, remember) -> {
                    var current = minecraft.getCurrentServer();
                    if (!accepted) {
                        minecraft.setScreenAndShow(parent);
                    } else if (!minecraft.isMultiplayerServer()
                            || current == null
                            || !MultiplayerAllowlist.sameAddress(address, current.ip)) {
                        minecraft.setScreenAndShow(parent);
                        overlay(minecraft, Component.translatable(
                                "gui.mcmcp.multiplayer.server_changed"));
                    } else if (remember
                            && !MultiplayerAllowlist.remember(allowlist, address)) {
                        minecraft.setScreenAndShow(parent);
                        overlay(minecraft, Component.translatable(
                                "gui.mcmcp.multiplayer.write_failed"));
                    } else {
                        if (remember) {
                            runtime.enableAutomationFromUi(minecraft);
                        } else {
                            runtime.enableAutomationForCurrentMultiplayerSessionFromUi(
                                    minecraft, address);
                        }
                        minecraft.setScreenAndShow(parent);
                    }
                }));
    }

    static Path multiplayerAllowlist(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().toAbsolutePath().normalize()
                .resolve("config").resolve("mcmcp").resolve("allowed-servers.json");
    }

    static String wrapMultiplayerAddress(String address) {
        if (address == null || address.isBlank()) return "?";
        var wrapped = new StringBuilder(address.length() + address.length() / 28);
        int column = 0;
        for (int index = 0; index < address.length(); ) {
            int codePoint = address.codePointAt(index);
            if (column == MultiplayerConsentScreen.ADDRESS_LINE_LENGTH) {
                wrapped.append('\n');
                column = 0;
            }
            wrapped.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            column++;
        }
        return wrapped.toString();
    }

    private void openSetupChooser(Screen parent) {
        var minecraft = Minecraft.getInstance();
        if (runtime.automationUiSnapshot().state() != AutomationUiSnapshot.State.OFF) {
            overlay(minecraft, Component.translatable("gui.mcmcp.setup.disable_first"));
            return;
        }
        minecraft.setScreenAndShow(new ConfirmScreen(
                codex -> openSetupConfirmation(
                        codex ? McpClientAutoConfigurator.Target.CODEX
                                : McpClientAutoConfigurator.Target.CLAUDE_CODE,
                        parent),
                Component.translatable("gui.mcmcp.setup.title"),
                Component.translatable("gui.mcmcp.setup.choose"),
                Component.translatable("gui.mcmcp.setup.codex"),
                Component.translatable("gui.mcmcp.setup.claude_code")) {
            @Override
            protected void addButtons(LinearLayout buttons) {
                var options = LinearLayout.vertical().spacing(4);
                options.defaultCellSetting().alignHorizontallyCenter();
                int availableWidth = Math.max(80, width - 50);
                if (availableWidth >= 304) {
                    int buttonWidth = Math.min(200, (availableWidth - 4) / 2);
                    var clients = LinearLayout.horizontal().spacing(4);
                    yesButton = clients.addChild(Button.builder(
                                    yesButtonComponent,
                                    ignored -> callback.accept(true))
                            .width(buttonWidth)
                            .build());
                    noButton = clients.addChild(Button.builder(
                                    noButtonComponent,
                                    ignored -> callback.accept(false))
                            .width(buttonWidth)
                            .build());
                    options.addChild(clients);
                } else {
                    int buttonWidth = Math.min(200, availableWidth);
                    yesButton = options.addChild(Button.builder(
                                    yesButtonComponent,
                                    ignored -> callback.accept(true))
                            .width(buttonWidth)
                            .build());
                    noButton = options.addChild(Button.builder(
                                    noButtonComponent,
                                    ignored -> callback.accept(false))
                            .width(buttonWidth)
                            .build());
                }
                options.addChild(Button.builder(
                                Component.translatable("gui.mcmcp.setup.cancel"),
                                ignored -> onClose())
                        .width(Math.min(200, availableWidth))
                        .build());
                buttons.addChild(options);
            }

            @Override
            public boolean keyPressed(KeyEvent event) {
                if (event.isEscape()) {
                    onClose();
                    return true;
                }
                return super.keyPressed(event);
            }

            @Override
            public void onClose() {
                minecraft.setScreenAndShow(parent);
            }
        });
    }

    private void openSetupConfirmation(
            McpClientAutoConfigurator.Target target, Screen parent) {
        var minecraft = Minecraft.getInstance();
        String targetName = target == McpClientAutoConfigurator.Target.CODEX
                ? "Codex" : "Claude Code";
        String configPath = target == McpClientAutoConfigurator.Target.CODEX
                ? ".codex/config.toml" : ".claude.json";
        minecraft.setScreenAndShow(new ConfirmScreen(
                accepted -> {
                    if (!accepted) {
                        openSetupChooser(parent);
                        return;
                    }
                    var result = McpClientAutoConfigurator.configure(
                            target,
                            minecraft.gameDirectory.toPath(),
                            Path.of(System.getProperty("user.home", ".")),
                            McmcpClientConfig.port());
                    minecraft.setScreenAndShow(parent);
                    overlay(minecraft, Component.translatable(
                            result.success()
                                    ? "gui.mcmcp.setup.success"
                                    : "gui.mcmcp.setup.failure",
                            targetName,
                            result.success()
                                    ? Component.translatable("gui.mcmcp.setup.restart_required")
                                    : result.code()));
                },
                Component.translatable("gui.mcmcp.setup.confirm_title", targetName),
                Component.translatable(
                        "gui.mcmcp.setup.confirm_message", configPath),
                Component.translatable("gui.mcmcp.setup.confirm"),
                Component.translatable("gui.mcmcp.setup.back")) {
            @Override
            public void onClose() {
                openSetupChooser(parent);
            }
        });
    }

    private static void overlay(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
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
        if ((state == AutomationUiSnapshot.State.AGENT
                || state == AutomationUiSnapshot.State.EVALUATING)
                && lastObservedState != state) {
            agentNoticeDeadlineNanos = nowNanos + AGENT_NOTICE_NANOS;
        }
        lastObservedState = state;
    }

    static PressAction pressAction(AutomationUiSnapshot.State state, boolean canEnable) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case OFF -> canEnable ? PressAction.ENABLE : PressAction.NONE;
            case READY, EVALUATING, AGENT, RECOVERING -> PressAction.DISABLE;
            case CONSENT_PENDING -> PressAction.NONE;
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
            case EVALUATING -> Component.translatable("gui.mcmcp.automation.evaluating");
            case CONSENT_PENDING ->
                    Component.translatable("gui.mcmcp.automation.consent_pending");
            case AGENT -> Component.translatable("gui.mcmcp.automation.agent");
            case RECOVERING -> Component.translatable("gui.mcmcp.automation.recovering");
            case FAULT -> Component.translatable("gui.mcmcp.automation.fault");
        };
    }

    private static Component tooltip(AutomationUiSnapshot snapshot, boolean canEnable) {
        return switch (snapshot.state()) {
            case OFF -> canEnable
                    ? Component.translatable("gui.mcmcp.automation.tooltip.enable")
                    : Component.translatable("gui.mcmcp.automation.tooltip.unavailable");
            case READY, AGENT, RECOVERING ->
                    Component.translatable("gui.mcmcp.automation.tooltip.disable");
            case EVALUATING ->
                    Component.translatable("gui.mcmcp.automation.tooltip.evaluating");
            case CONSENT_PENDING -> Component.translatable(
                    "gui.mcmcp.automation.tooltip.consent_pending",
                    snapshot.detail() == null ? "entity" : snapshot.detail());
            case FAULT -> Component.translatable(
                    "gui.mcmcp.automation.tooltip.fault",
                    snapshot.detail() == null ? "internal_error" : snapshot.detail());
        };
    }

    static boolean executionBorderVisible(AutomationUiSnapshot.State state) {
        return state == AutomationUiSnapshot.State.AGENT
                || state == AutomationUiSnapshot.State.RECOVERING;
    }

    static boolean evaluationBorderVisible(AutomationUiSnapshot.State state) {
        return state == AutomationUiSnapshot.State.EVALUATING;
    }

    static boolean consentBorderVisible(AutomationUiSnapshot.State state) {
        return state == AutomationUiSnapshot.State.CONSENT_PENDING;
    }

    private static void drawExecutionBorder(GuiGraphicsExtractor graphics) {
        drawBorder(graphics, EXECUTION_BORDER_COLOR);
    }

    private static void drawEvaluationBorder(GuiGraphicsExtractor graphics) {
        drawBorder(graphics, EVALUATION_BORDER_COLOR);
    }

    private static void drawConsentBorder(GuiGraphicsExtractor graphics) {
        drawBorder(graphics, CONSENT_BORDER_COLOR);
    }

    private static void drawBorder(GuiGraphicsExtractor graphics, int color) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.fill(0, 0, width, EXECUTION_BORDER_WIDTH, color);
        graphics.fill(
                0, height - EXECUTION_BORDER_WIDTH,
                width, height,
                color);
        graphics.fill(
                0, EXECUTION_BORDER_WIDTH,
                EXECUTION_BORDER_WIDTH, height - EXECUTION_BORDER_WIDTH,
                color);
        graphics.fill(
                width - EXECUTION_BORDER_WIDTH, EXECUTION_BORDER_WIDTH,
                width, height - EXECUTION_BORDER_WIDTH,
                color);
    }

    private static void drawActiveNotice(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            AutomationUiSnapshot.State state) {
        var notice = Component.translatable(state == AutomationUiSnapshot.State.EVALUATING
                ? "gui.mcmcp.automation.evaluating_notice"
                : "gui.mcmcp.automation.agent_notice");
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
        // Pixel robot: antenna, rounded head, ears and a little neck.
        graphics.fill(x + 6, y + 1, x + 10, y + 2, color);
        graphics.fill(x + 7, y + 2, x + 9, y + 4, color);
        graphics.fill(x + 3, y + 4, x + 13, y + 14, color);
        graphics.fill(x + 2, y + 5, x + 14, y + 13, color);
        graphics.fill(x + 1, y + 7, x + 15, y + 11, color);
        graphics.fill(x + 6, y + 14, x + 10, y + 15, color);
        graphics.fill(x + 3, y + 5, x + 13, y + 13, 0xFF20262D);

        // The face display identifies every state without relying on color.
        switch (state) {
            case OFF, READY -> {
                int eyeTop = state == AutomationUiSnapshot.State.OFF ? 8 : 6;
                graphics.fill(x + 4, y + eyeTop, x + 7, y + 9, color);
                graphics.fill(x + 9, y + eyeTop, x + 12, y + 9, color);
                graphics.fill(x + 5, y + 10, x + 6, y + 11, color);
                graphics.fill(x + 6, y + 11, x + 10, y + 12, color);
                graphics.fill(x + 10, y + 10, x + 11, y + 11, color);
            }
            case EVALUATING -> {
                graphics.fill(x + 4, y + 8, x + 6, y + 10, color);
                graphics.fill(x + 7, y + 8, x + 9, y + 10, color);
                graphics.fill(x + 10, y + 8, x + 12, y + 10, color);
            }
            case AGENT -> {
                graphics.fill(x + 6, y + 6, x + 7, y + 12, color);
                graphics.fill(x + 7, y + 7, x + 9, y + 11, color);
                graphics.fill(x + 9, y + 8, x + 11, y + 10, color);
            }
            case RECOVERING -> {
                graphics.fill(x + 5, y + 6, x + 11, y + 7, color);
                graphics.fill(x + 4, y + 6, x + 5, y + 9, color);
                graphics.fill(x + 11, y + 6, x + 12, y + 9, color);
                graphics.fill(x + 5, y + 9, x + 6, y + 10, color);
                graphics.fill(x + 10, y + 9, x + 11, y + 10, color);
                graphics.fill(x + 6, y + 10, x + 10, y + 11, color);
                graphics.fill(x + 7, y + 11, x + 9, y + 12, color);
            }
            case CONSENT_PENDING -> {
                graphics.fill(x + 6, y + 6, x + 10, y + 7, color);
                graphics.fill(x + 5, y + 7, x + 6, y + 8, color);
                graphics.fill(x + 10, y + 7, x + 11, y + 8, color);
                graphics.fill(x + 8, y + 8, x + 10, y + 9, color);
                graphics.fill(x + 7, y + 9, x + 9, y + 10, color);
                graphics.fill(x + 7, y + 11, x + 9, y + 12, color);
            }
            case FAULT -> {
                graphics.fill(x + 7, y + 6, x + 9, y + 10, color);
                graphics.fill(x + 7, y + 11, x + 9, y + 12, color);
            }
        }
    }

    private static int color(AutomationUiSnapshot.State state) {
        return switch (state) {
            case OFF -> OFF_COLOR;
            case READY -> READY_COLOR;
            case EVALUATING -> EVALUATING_COLOR;
            case CONSENT_PENDING -> CONSENT_PENDING_COLOR;
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
                font.width(Component.translatable("gui.mcmcp.automation.evaluating")),
                font.width(Component.translatable("gui.mcmcp.automation.consent_pending")),
                font.width(Component.translatable("gui.mcmcp.automation.agent")),
                font.width(Component.translatable("gui.mcmcp.automation.recovering")),
                font.width(Component.translatable("gui.mcmcp.automation.fault")));
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

    @FunctionalInterface
    private interface MultiplayerConsentCallback {
        void accept(boolean accepted, boolean rememberServer);
    }

    private static final class MultiplayerConsentScreen extends ConfirmScreen {
        private static final int MAX_CONTENT_WIDTH = 400;
        private static final int ADDRESS_LINE_LENGTH = 28;

        private final MultiplayerConsentCallback result;
        private Checkbox rememberCheckbox;
        private boolean terminal;

        private MultiplayerConsentScreen(
                String address,
                MultiplayerConsentCallback result) {
            super(
                    ignored -> { },
                    Component.translatable("gui.mcmcp.multiplayer.title")
                            .withStyle(ChatFormatting.BOLD, ChatFormatting.RED),
                    Component.translatable(
                            "gui.mcmcp.multiplayer.message",
                            wrapMultiplayerAddress(address),
                            Component.translatable("gui.mcmcp.multiplayer.disclaimer")
                                    .withStyle(ChatFormatting.UNDERLINE)),
                    Component.translatable("gui.mcmcp.multiplayer.enable"),
                    Component.translatable("gui.mcmcp.multiplayer.cancel"));
            this.result = Objects.requireNonNull(result, "result");
        }

        @Override
        protected LayoutElement addMessage() {
            return new MultiLineTextWidget(message, font)
                    .setMaxWidth(Math.min(MAX_CONTENT_WIDTH, Math.max(80, width - 50)))
                    .setMaxRows(24)
                    .setCentered(true);
        }

        @Override
        protected void addAdditionalText() {
            rememberCheckbox = layout.addChild(Checkbox.builder(
                            Component.translatable("gui.mcmcp.multiplayer.remember"),
                            font)
                    .maxWidth(Math.min(MAX_CONTENT_WIDTH, Math.max(80, width - 50)))
                    .build());
        }

        @Override
        protected void addButtons(LinearLayout buttons) {
            int availableWidth = Math.max(80, width - 50);
            if (availableWidth >= 304) {
                int buttonWidth = Math.min(150, (availableWidth - 4) / 2);
                yesButton = buttons.addChild(Button.builder(
                                yesButtonComponent,
                                ignored -> complete(true))
                        .width(buttonWidth)
                        .build());
                noButton = buttons.addChild(Button.builder(
                                noButtonComponent,
                                ignored -> complete(false))
                        .width(buttonWidth)
                        .build());
                return;
            }
            var stacked = LinearLayout.vertical().spacing(4);
            stacked.defaultCellSetting().alignHorizontallyCenter();
            int buttonWidth = Math.min(200, availableWidth);
            yesButton = stacked.addChild(Button.builder(
                            yesButtonComponent,
                            ignored -> complete(true))
                    .width(buttonWidth)
                    .build());
            noButton = stacked.addChild(Button.builder(
                            noButtonComponent,
                            ignored -> complete(false))
                    .width(buttonWidth)
                    .build());
            buttons.addChild(stacked);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isEscape()) {
                complete(false);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void onClose() {
            complete(false);
        }

        private void complete(boolean accepted) {
            if (terminal) return;
            terminal = true;
            result.accept(
                    accepted,
                    accepted && rememberCheckbox != null && rememberCheckbox.selected());
        }

    }

    /** Capability instance is created by the runtime and retained only by the local prompt. */
    public interface EntityAttackConsentPromptSink {
        boolean grantFromPhysicalPrimaryClick();

        void cancel();

        boolean pending();
    }

    private static final class EntityAttackConsentScreen extends ConfirmScreen {
        private final EntityAttackConsentPromptSink sink;
        private boolean terminal;

        private EntityAttackConsentScreen(
                ScopedEntityAttackConsentStore.Scope scope,
                EntityAttackConsentPromptSink sink) {
            super(
                    ignored -> { },
                    Component.translatable("gui.mcmcp.entity_attack_consent.title"),
                    Component.translatable(
                            "gui.mcmcp.entity_attack_consent.message",
                            scope.dimension(),
                            boundsSummary(scope.playerStationBounds()),
                            boundsSummary(scope.targetKillZoneBounds()),
                            String.join(", ", scope.entityTypeAllowlist()),
                            scope.mainHandItem(),
                            scope.attackSideEffectProfile()
                                    .name().toLowerCase(java.util.Locale.ROOT),
                            scope.maxAttacks(),
                            scope.minimumIntervalTicks(),
                            ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS,
                            scope.maxOperationDurationTicks()),
                    Component.translatable("gui.mcmcp.entity_attack_consent.grant"),
                    Component.translatable("gui.mcmcp.entity_attack_consent.cancel"));
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        private static String boundsSummary(ScopedEntityAttackConsentStore.Bounds bounds) {
            return String.format(
                    java.util.Locale.ROOT,
                    "[%.1f, %.1f, %.1f]-[%.1f, %.1f, %.1f]",
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());
        }

        private boolean dispatchPhysicalPrimaryClick(double mouseX, double mouseY) {
            if (terminal) {
                return false;
            }
            if (yesButton != null && yesButton.visible && yesButton.active
                    && yesButton.isMouseOver(mouseX, mouseY)) {
                if (sink.grantFromPhysicalPrimaryClick()) {
                    terminal = true;
                    super.onClose();
                } else {
                    cancelAndClose();
                }
                return true;
            }
            if (noButton != null && noButton.visible && noButton.active
                    && noButton.isMouseOver(mouseX, mouseY)) {
                cancelAndClose();
                return true;
            }
            return false;
        }

        private void cancelAndClose() {
            if (!terminal) {
                terminal = true;
                sink.cancel();
            }
            super.onClose();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            if (event.isEscape()) {
                cancelAndClose();
            }
            // Selection keys must never activate the Grant button.
            return true;
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void tick() {
            super.tick();
            if (!terminal && !sink.pending()) {
                terminal = true;
                super.onClose();
            }
        }

        @Override
        public void onClose() {
            cancelAndClose();
        }

        @Override
        public void removed() {
            if (!terminal) {
                terminal = true;
                sink.cancel();
            }
            super.removed();
        }
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
            controller.refresh(this, System.nanoTime());
            int width = menuButtonWidth(
                    graphics.guiWidth(),
                    McmcpClientConfig.hudOffsetX(),
                    Minecraft.getInstance().font.width(getMessage()));
            setWidth(width);
            setX(lowerRightCoordinate(
                    graphics.guiWidth(), width, McmcpClientConfig.hudOffsetX()));
            setY(screenButtonY(
                    graphics.guiHeight(),
                    getHeight(),
                    McmcpClientConfig.hudOffsetY(),
                    chatScreen));
            extractDefaultSprite(graphics);
            var state = controller.runtime.automationUiSnapshot().state();
            drawIcon(
                    graphics,
                    getX() + BUTTON_PADDING,
                    getY() + (getHeight() - ICON_SIZE) / 2,
                    state);
            graphics.textRendererForWidget(
                            this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                    .acceptScrollingWithDefaultCenter(
                            getMessage(),
                            getX() + BUTTON_PADDING + ICON_SIZE + ICON_TEXT_GAP,
                            getRight() - BUTTON_PADDING,
                            getY(),
                            getBottom());
            if (executionBorderVisible(state)) {
                drawExecutionBorder(graphics);
            } else if (consentBorderVisible(state)) {
                drawConsentBorder(graphics);
            } else if (evaluationBorderVisible(state)) {
                drawEvaluationBorder(graphics);
            }
        }
    }
}
