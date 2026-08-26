package dev.aod.mcmcp.agent.observation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientFogDistanceSignalsTest {
    @Test
    void usesTheNearerPositiveFogBoundary() {
        assertThat(ClientFogDistanceSignals.effectiveEnd(3.5F, 64.0F)).isEqualTo(3.5D);
        assertThat(ClientFogDistanceSignals.effectiveEnd(Float.MAX_VALUE, 48.0F)).isEqualTo(48.0D);
        assertThat(ClientFogDistanceSignals.effectiveEnd(0.0F, 0.0F)).isEqualTo(1.0D / 16.0D);
    }

    @Test
    void acceptsOnlyTheExactLevelCameraAndEntityTick() {
        var level = new Object();
        var camera = new Object();
        ClientFogDistanceSignals.recordIdentity(level, camera, 10, 3.5D);

        assertThat(ClientFogDistanceSignals.currentOrIdentity(level, camera, 10, 1.0D))
                .isEqualTo(3.5D);
        assertThat(ClientFogDistanceSignals.currentOrIdentity(level, camera, 11, 1.0D))
                .isEqualTo(1.0D);
        assertThat(ClientFogDistanceSignals.currentOrIdentity(level, new Object(), 10, 1.0D))
                .isEqualTo(1.0D);
        assertThat(ClientFogDistanceSignals.currentOrIdentity(
                new Object(), camera, 10, 1.0D)).isEqualTo(1.0D);
    }
}
