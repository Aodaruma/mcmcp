package dev.aod.mcmcp.client;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/** Chat is a passive UI for world actions; container ownership remains a separate gate. */
public final class AgentScreenPolicy {
    private AgentScreenPolicy() { }

    public static boolean allowsWorldInput(Screen screen) {
        return screen == null || allowsWorldInput(screen.getClass(), screen.isPauseScreen());
    }

    static boolean allowsWorldInput(Class<? extends Screen> screenType, boolean pauseScreen) {
        return ChatScreen.class.isAssignableFrom(screenType) && !pauseScreen;
    }
}
