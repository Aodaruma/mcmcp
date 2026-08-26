package dev.aod.mcmcp.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.runtime.McmcpRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Local-only arming and emergency controls. There is intentionally no MCP unlock tool. */
public final class McmcpKeyBindings {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(McmcpMod.MOD_ID, "controls"));
    private final KeyMapping toggleArming = new KeyMapping(
            "key.mcmcp.toggle_lock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    private final KeyMapping emergencyStop = new KeyMapping(
            "key.mcmcp.emergency_stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY);

    public void register(RegisterKeyMappingsEvent event) {
        event.register(toggleArming);
        event.register(emergencyStop);
    }

    /**
     * Local safety controls have their own synchronous priority handlers. They must
     * not also enter the generic manual-input path before those handlers run, or an
     * F8 lock can be immediately toggled back to armed in the same client tick.
     */
    public boolean isLocalControlKey(KeyEvent event) {
        return toggleArming.matches(event) || emergencyStop.matches(event);
    }

    public void handleClientTick(Minecraft minecraft, McmcpRuntime runtime) {
        while (emergencyStop.consumeClick()) {
            runtime.emergencyStopFromLocalKey(minecraft);
        }
        while (toggleArming.consumeClick()) {
            runtime.toggleLocalArming(minecraft);
        }
    }
}
