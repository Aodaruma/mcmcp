package dev.aod.mcmcp.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpClientConfigTest {
    @Test
    void exposesRequiredPhaseOneClientSettingsWithSafeDefaults() {
        assertThat(McmcpClientConfig.DEFAULT_HUD_OFFSET).isEqualTo(8);
        var values = McmcpClientConfig.SPEC.getValues().valueMap();
        assertThat(values)
                .containsKeys(
                        "endpoint_enabled",
                        "port",
                        "max_request_bytes",
                        "ready_timeout_seconds",
                        "hud_offset_x",
                        "hud_offset_y");
        assertThat(((ModConfigSpec.BooleanValue) values.get("endpoint_enabled")).getDefault())
                .isEqualTo(true);
        assertThat(((ModConfigSpec.IntValue) values.get("port")).getDefault())
                .isEqualTo(8_765);
        assertThat(((ModConfigSpec.IntValue) values.get("max_request_bytes")).getDefault())
                .isEqualTo(65_536);
        assertThat(((ModConfigSpec.IntValue) values.get("ready_timeout_seconds")).getDefault())
                .isEqualTo(30);
        assertThat(((ModConfigSpec.IntValue) values.get("hud_offset_x")).getDefault())
                .isEqualTo(8);
        assertThat(((ModConfigSpec.IntValue) values.get("hud_offset_y")).getDefault())
                .isEqualTo(8);
    }
}
