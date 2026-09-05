package dev.aod.mcmcp.client;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScreenPolicyTest {
    @Test
    void permitsChatWithoutConstructingOrReadingItsContents() {
        assertThat(AgentScreenPolicy.allowsWorldInput(null)).isTrue();
        assertThat(AgentScreenPolicy.allowsWorldInput(ChatScreen.class, false))
                .isTrue();
    }

    @Test
    void rejectsOtherScreensEvenIfTheyDoNotPause() {
        for (var type : java.util.List.of(Screen.class, InventoryScreen.class, PauseScreen.class)) {
            assertThat(AgentScreenPolicy.allowsWorldInput(type, false)).isFalse();
            assertThat(AgentScreenPolicy.allowsWorldInput(type, true)).isFalse();
        }
        assertThat(AgentScreenPolicy.allowsWorldInput(ChatScreen.class, true)).isFalse();
    }
}
