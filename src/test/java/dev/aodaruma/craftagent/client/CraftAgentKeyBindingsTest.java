package dev.aodaruma.craftagent.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.assertj.core.api.Assertions.assertThat;

class CraftAgentKeyBindingsTest {
    @Test
    void localControlsBypassGenericManualInputBeforeTheirSameTickHandlersRun() {
        var bindings = new CraftAgentKeyBindings();

        assertThat(bindings.isLocalControlKey(key(GLFW.GLFW_KEY_F8))).isTrue();
        assertThat(bindings.isLocalControlKey(key(GLFW.GLFW_KEY_F9))).isTrue();
        assertThat(bindings.isLocalControlKey(key(GLFW.GLFW_KEY_W))).isFalse();
    }

    @Test
    void localControlExclusionFollowsAReassignedRegisteredMapping() {
        var bindings = new CraftAgentKeyBindings();
        var toggleArming = KeyMapping.get("key.craftagent.toggle_lock");
        var originalKey = toggleArming.getKey();

        try {
            toggleArming.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F10));

            assertThat(bindings.isLocalControlKey(key(GLFW.GLFW_KEY_F8))).isFalse();
            assertThat(bindings.isLocalControlKey(key(GLFW.GLFW_KEY_F10))).isTrue();
        } finally {
            toggleArming.setKey(originalKey);
        }
    }

    private static KeyEvent key(int key) {
        return new KeyEvent(key, 0, 0);
    }
}
