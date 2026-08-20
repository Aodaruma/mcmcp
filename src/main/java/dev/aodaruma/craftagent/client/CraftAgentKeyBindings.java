package dev.aodaruma.craftagent.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aodaruma.craftagent.CraftAgentMod;
import dev.aodaruma.craftagent.runtime.CraftAgentRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Local-only arming and emergency controls. There is intentionally no MCP unlock tool. */
public final class CraftAgentKeyBindings {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(CraftAgentMod.MOD_ID, "controls"));
    private final KeyMapping toggleArming = new KeyMapping(
            "key.craftagent.toggle_lock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    private final KeyMapping emergencyStop = new KeyMapping(
            "key.craftagent.emergency_stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY);

    public void register(RegisterKeyMappingsEvent event) {
        event.register(toggleArming);
        event.register(emergencyStop);
    }

    public void handleClientTick(Minecraft minecraft, CraftAgentRuntime runtime) {
        while (emergencyStop.consumeClick()) {
            runtime.emergencyStopFromLocalKey(minecraft);
        }
        while (toggleArming.consumeClick()) {
            runtime.toggleLocalArming(minecraft);
        }
    }
}
