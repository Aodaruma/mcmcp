package dev.aod.mcmcp.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceTransmissionGuardTest {
    @Test
    void isUnblockedByDefaultAndCountsSuppressedFramesOnlyWhenRecorded() {
        var guard = new VoiceTransmissionGuard();

        assertThat(guard.blocksTransmission()).isFalse();
        guard.block();
        guard.recordSuppressedFrame();
        guard.recordSuppressedFrame();
        guard.unblock();

        assertThat(guard.blocksTransmission()).isFalse();
        assertThat(guard.suppressedFrames()).isEqualTo(2);
    }
}
