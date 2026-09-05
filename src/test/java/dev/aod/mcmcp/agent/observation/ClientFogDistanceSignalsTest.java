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

        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 10))
                .hasValue(3.5D);
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 11)).isEmpty();
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 9)).isEmpty();
        assertThat(ClientFogDistanceSignals.currentIdentity(level, new Object(), 10)).isEmpty();
        assertThat(ClientFogDistanceSignals.currentIdentity(new Object(), camera, 10)).isEmpty();
    }

    @Test
    void missingSamplesStayAbsentUntilANewRendererSampleArrives() {
        var level = new Object();
        var camera = new Object();
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 10)).isEmpty();
        ClientFogDistanceSignals.recordIdentity(level, camera, 10, 32.0D);
        for (int tick = 11; tick < 100; tick++) {
            assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, tick)).isEmpty();
        }
        ClientFogDistanceSignals.recordIdentity(level, camera, 100, 8.0D);
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 100)).hasValue(8.0D);
    }

    @Test
    void realShortFogIsPreservedAndCannotBeConfusedWithMissingData() {
        var level = new Object();
        var camera = new Object();
        ClientFogDistanceSignals.recordIdentity(level, camera, 10, 1.0D);
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 10)).hasValue(1.0D);
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 11)).isEmpty();
        ClientFogDistanceSignals.recordIdentity(level, camera, 11, 0.25D);
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 11)).hasValue(0.25D);
    }
}
