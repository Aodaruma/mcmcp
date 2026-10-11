package dev.aod.mcmcp.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Appends Agent-only controls to the native, scrollable chat options list. */
public final class AgentChatOptions {
    private AgentChatOptions() { }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ChatOptionsScreen)) return;
        for (var child : event.getScreen().children()) {
            if (!(child instanceof OptionsList list)) continue;
            var settings = McmcpClientConfig.agentChatSettings();
            list.addHeader(Component.translatable("gui.mcmcp.chat.title"));
            addRow(list, "prefix", settings.prefixEnabled(), settings.prefix(),
                    McmcpClientConfig::setAgentChatPrefixEnabled, McmcpClientConfig::setAgentChatPrefix);
            addRow(list, "suffix", settings.suffixEnabled(), settings.suffix(),
                    McmcpClientConfig::setAgentChatSuffixEnabled, McmcpClientConfig::setAgentChatSuffix);
            break;
        }
    }

    private static void addRow(OptionsList list, String name, boolean enabled, String value,
            Consumer<Boolean> setEnabled, Consumer<String> setValue) {
        var label = Component.translatable("gui.mcmcp.chat." + name);
        var tooltip = Tooltip.create(Component.translatable("gui.mcmcp.chat.hint"));
        var toggle = CycleButton.onOffBuilder(enabled).create(0, 0, 150, 20, label,
                (button, selected) -> setEnabled.accept(selected));
        toggle.setTooltip(tooltip);
        var field = new EditBox(Minecraft.getInstance().font, 150, 20, label);
        field.setMaxLength(256);
        field.setValue(value);
        field.setHint(label);
        field.setTooltip(tooltip);
        field.setResponder(text -> {
            boolean valid = AgentChatSettings.validFragment(text);
            field.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : 0xFFFF5555);
            if (valid) setValue.accept(text);
        });
        list.addSmall(toggle, field);
    }
}
