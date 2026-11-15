package dev.aod.mcmcp.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentChatSettingsTest {
    @Test void appliesOnlyEnabledAffixesAndKeepsLiteralSpacing() {
        assertThat(new AgentChatSettings(false, "", true, AgentChatSettings.DEFAULT_SUFFIX).format("こんにちは"))
                .isEqualTo("こんにちは (by Agent)");
        assertThat(new AgentChatSettings(true, "[Bot] ", true, "! ").format("ready"))
                .isEqualTo("[Bot] ready! ");
        assertThat(new AgentChatSettings(false, "ignored", false, "ignored").format("ready"))
                .isEqualTo("ready");
    }

    @Test void validatesTheOriginalAndCompleteMessageBeforeSubmission() {
        var defaults = new AgentChatSettings(false, "", true, AgentChatSettings.DEFAULT_SUFFIX);
        assertThat(defaults.format("x".repeat(245))).hasSize(256);
        assertThatThrownBy(() -> defaults.format("x".repeat(246))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentChatSettings(true, " /", false, "").format("help"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentChatSettings(true, "label ", false, "").format("/help"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentChatSettings.validFragment("日本語 🦅 ")).isTrue();
        assertThat(AgentChatSettings.validFragment("")).isTrue();
        for (String bad : new String[]{"\n", "\u00a7", "\ud800", "\u2028", "x".repeat(257)})
            assertThat(AgentChatSettings.validFragment(bad)).isFalse();
    }
}
