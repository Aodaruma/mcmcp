package dev.aod.mcmcp.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        "hud_offset_x",
                        "hud_offset_y");
        assertThat(values).doesNotContainKey("ready_timeout_seconds");
        assertThat(values).doesNotContainKey("multiplayer_default");
        assertThat(((ModConfigSpec.BooleanValue) values.get("endpoint_enabled")).getDefault())
                .isEqualTo(true);
        assertThat(((ModConfigSpec.IntValue) values.get("port")).getDefault())
                .isEqualTo(8_765);
        assertThat(((ModConfigSpec.IntValue) values.get("max_request_bytes")).getDefault())
                .isEqualTo(65_536);
        assertThat(((ModConfigSpec.IntValue) values.get("hud_offset_x")).getDefault())
                .isEqualTo(8);
        assertThat(((ModConfigSpec.IntValue) values.get("hud_offset_y")).getDefault())
                .isEqualTo(8);
    }

    @Test
    void fixtureObservationOverrideDoesNotChangeTheProductionDefault() {
        Integer savedOverride = McmcpClientConfig.rawTestHarnessRaysPerTickOverride();
        try {
            McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(512);

            assertThat(McmcpClientConfig.raysPerTick()).isEqualTo(512);
            var values = McmcpClientConfig.SPEC.getValues().valueMap();
            assertThat(((ModConfigSpec.IntValue) values.get(
                    "omnidirectional_rays_per_tick")).getDefault()).isEqualTo(256);
        } finally {
            McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(savedOverride);
        }
    }

    @Test
    void acceptsOnlyBoundedHarnessBridgeValues() {
        Integer savedOverride = McmcpClientConfig.rawTestHarnessRaysPerTickOverride();
        try {
            assertThatThrownBy(() -> McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(63))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(513))
                    .isInstanceOf(IllegalArgumentException.class);
            McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(64);
            assertThat(McmcpClientConfig.rawTestHarnessRaysPerTickOverride()).isEqualTo(64);
            McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(null);
            assertThat(McmcpClientConfig.rawTestHarnessRaysPerTickOverride()).isNull();
        } finally {
            McmcpClientConfig.setRawTestHarnessRaysPerTickOverride(savedOverride);
        }
    }

    @Test
    void publicHarnessOverrideRequiresBothTheFlagAndFixtureMod() {
        assertThat(McmcpClientConfig.testHarnessRuntimeAvailable(false, false)).isFalse();
        assertThat(McmcpClientConfig.testHarnessRuntimeAvailable(true, false)).isFalse();
        assertThat(McmcpClientConfig.testHarnessRuntimeAvailable(false, true)).isFalse();
        assertThat(McmcpClientConfig.testHarnessRuntimeAvailable(true, true)).isTrue();
    }
}
