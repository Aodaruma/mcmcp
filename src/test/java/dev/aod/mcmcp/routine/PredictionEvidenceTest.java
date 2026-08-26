package dev.aod.mcmcp.routine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionEvidenceTest {
    private static final BlockStateFingerprint SOURCE =
            new BlockStateFingerprint("minecraft:cobblestone", Map.of());

    @Test
    void confirmsOnlyAnAuthoritativeAirTransition() {
        var evidence = new PredictionEvidence(
                8,
                true,
                true,
                Optional.of(new BlockStateFingerprint("minecraft:air", Map.of())),
                20,
                4);

        assertThat(evidence.confirmsBreakFrom(SOURCE)).isTrue();
    }

    @Test
    void replacementWithAnotherBlockIsNotAConfirmedBreak() {
        var evidence = new PredictionEvidence(
                8,
                true,
                true,
                Optional.of(new BlockStateFingerprint("minecraft:dirt", Map.of())),
                20,
                4);

        assertThat(evidence.confirmsBreakFrom(SOURCE)).isFalse();
    }
}
