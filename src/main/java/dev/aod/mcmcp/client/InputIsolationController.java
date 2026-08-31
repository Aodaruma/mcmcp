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
 * Keeps physical input out of vanilla while EVALUATING/AGENT/RECOVERING owns control.
 * Mouse movement itself remains available so the Screen status button is reachable.
 */
public final class InputIsolationController {
    private static volatile InputIsolationController installed;

    private final McmcpRuntime runtime;
    private final AutomationIndicatorController indicator;
    private final BitSet swallowedMouseButtons = new BitSet();
    private boolean previousIsolationActive;

    public InputIsolationController(
            McmcpRuntime runtime,
            AutomationIndicatorController indicator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.indicator = Objects.requireNonNull(indicator, "indicator");
        installed = this;
    }

    /**
     * Reconciles vanilla key mappings with the current isolation edge.
     *
     * <p>This is called immediately before and after the runtime pre-tick. While isolation is
     * active, stale physical mappings are cleared; on the falling edge, vanilla re-reads the
     * currently held keyboard keys exactly once. The second call closes an acquire/release that
     * happens inside the same runtime tick instead of leaving mappings released until another
     * focus or mouse-grab transition.</p>
     */
    public void reconcilePhysicalKeyMappings() {
        boolean isolationActive = isolatesPhysicalInput(
                runtime.automationUiSnapshot().state());
        var action = physicalKeyMappingAction(previousIsolationActive, isolationActive);
        switch (action) {
            case RELEASE -> KeyMapping.releaseAll();
            case RESTORE -> KeyMapping.setAll();
            case NONE -> { }
        }
        previousIsolationActive = isolationActive;
    }

    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        int button = event.getButton();
        if (event.getAction() == InputConstants.RELEASE && blocked(button)) {
            swallowedMouseButtons.clear(button);
            event.setCanceled(true);
            return;
        }
        if (!isolatesPhysicalInput(runtime.automationUiSnapshot().state())) {
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
        if (isolatesPhysicalInput(runtime.automationUiSnapshot().state())) {
            event.setCanceled(true);
        }
    }

    public void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (isolatesPhysicalInput(runtime.automationUiSnapshot().state())
                || blocked(event.getMouseButton())) {
            event.setCanceled(true);
        }
    }

    public void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (isolatesPhysicalInput(runtime.automationUiSnapshot().state())) {
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
                current.runtime.automationUiSnapshot().state(), event.key(), action);
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
        return current != null && isolatesPhysicalInput(
                current.runtime.automationUiSnapshot().state());
    }

    /** Called by MouseHandler#turnPlayer; raw mouse movement is otherwise untouched. */
    public static boolean interceptMouseTurn() {
        var current = installed;
        return current != null && isolatesPhysicalInput(
                current.runtime.automationUiSnapshot().state());
    }

    static KeyDecision keyDecision(
            AutomationUiSnapshot.State state,
            int key,
            int action) {
        Objects.requireNonNull(state, "state");
        if (key == InputConstants.KEY_ESCAPE && action == InputConstants.PRESS) {
            return switch (state) {
                case EVALUATING, AGENT, RECOVERING -> KeyDecision.EMERGENCY_STOP;
                case OFF, READY, FAULT -> KeyDecision.PASS;
            };
        }
        return isolatesPhysicalInput(state)
                ? KeyDecision.BLOCK
                : KeyDecision.PASS;
    }

    static boolean isolatesPhysicalInput(AutomationUiSnapshot.State state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case EVALUATING, AGENT, RECOVERING -> true;
            case OFF, READY, FAULT -> false;
        };
    }

    private boolean blocked(int button) {
        return button >= 0 && swallowedMouseButtons.get(button);
    }

    static PhysicalKeyMappingAction physicalKeyMappingAction(
            boolean previousIsolationActive,
            boolean isolationActive) {
        if (isolationActive) {
            return PhysicalKeyMappingAction.RELEASE;
        }
        return previousIsolationActive
                ? PhysicalKeyMappingAction.RESTORE
                : PhysicalKeyMappingAction.NONE;
    }

    enum PhysicalKeyMappingAction {
        NONE,
        RELEASE,
        RESTORE
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
