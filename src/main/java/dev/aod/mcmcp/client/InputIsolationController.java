package dev.aod.mcmcp.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.BitSet;
import java.util.Objects;

/**
 * Keeps physical input out of vanilla while AGENT/RECOVERING owns control.
 * Mouse movement itself remains available so the Screen status button is reachable.
 */
public final class InputIsolationController {
    private static volatile InputIsolationController installed;

    private final McmcpRuntime runtime;
    private final AutomationIndicatorController indicator;
    private final BitSet swallowedMouseButtons = new BitSet();

    public InputIsolationController(
            McmcpRuntime runtime,
            AutomationIndicatorController indicator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.indicator = Objects.requireNonNull(indicator, "indicator");
        installed = this;
    }

    /** Clears stale physical mappings; agent movement is applied later to final ClientInput. */
    public void onClientPreTick() {
        if (runtime.inputIsolationActive()) {
            KeyMapping.releaseAll();
        }
    }

    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        int button = event.getButton();
        if (event.getAction() == InputConstants.RELEASE && blocked(button)) {
            swallowedMouseButtons.clear(button);
            event.setCanceled(true);
            return;
        }
        if (!runtime.inputIsolationActive()) {
            return;
        }

        if (event.getAction() == InputConstants.PRESS && button >= 0) {
            swallowedMouseButtons.set(button);
            if (button == 0) {
                indicator.dispatchPrimaryClick(
                        Minecraft.getInstance(), event.getMouseButtonInfo());
            }
        }
        event.setCanceled(true);
    }

    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (runtime.inputIsolationActive()) {
            event.setCanceled(true);
        }
    }

    public void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (runtime.inputIsolationActive() || blocked(event.getMouseButton())) {
            event.setCanceled(true);
        }
    }

    public void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (runtime.inputIsolationActive()) {
            event.setCanceled(true);
        }
    }

    /** Called only by the narrow KeyboardHandler mixin. True means cancel vanilla. */
    public static boolean interceptKeyPress(
            Minecraft minecraft, int action, KeyEvent event) {
        var current = installed;
        if (current == null) {
            return false;
        }
        var decision = keyDecision(
                current.runtime.automationUiSnapshot().state(),
                current.runtime.inputIsolationActive(),
                event.key(),
                action);
        return switch (decision) {
            case PASS, BLOCK -> decision.cancelsVanilla();
            case EMERGENCY_STOP -> {
                current.runtime.emergencyStopFromLocalKey(minecraft);
                yield decision.cancelsVanilla();
            }
        };
    }

    /** Called by the CharacterEvent and IME pre-edit mixin entry points. */
    public static boolean interceptTextInput() {
        var current = installed;
        return current != null && current.runtime.inputIsolationActive();
    }

    /** Called by MouseHandler#turnPlayer; raw mouse movement is otherwise untouched. */
    public static boolean interceptMouseTurn() {
        var current = installed;
        return current != null && current.runtime.inputIsolationActive();
    }

    static KeyDecision keyDecision(
            AutomationUiSnapshot.State state,
            boolean isolationActive,
            int key,
            int action) {
        Objects.requireNonNull(state, "state");
        if (key == InputConstants.KEY_ESCAPE && action == InputConstants.PRESS) {
            return switch (state) {
                case READY, AGENT, RECOVERING -> KeyDecision.EMERGENCY_STOP;
                case OFF, FAULT -> KeyDecision.PASS;
            };
        }
        return isolationActive ? KeyDecision.BLOCK : KeyDecision.PASS;
    }

    private boolean blocked(int button) {
        return button >= 0 && swallowedMouseButtons.get(button);
    }

    enum KeyDecision {
        PASS,
        BLOCK,
        EMERGENCY_STOP;

        boolean cancelsVanilla() {
            return this == BLOCK;
        }
    }
}
