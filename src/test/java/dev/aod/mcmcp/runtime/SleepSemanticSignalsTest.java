package dev.aod.mcmcp.runtime;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SleepSemanticSignalsTest {
    @Test
    void recognizesOnlyExactRootAllowlistedTranslationKeys() {
        assertThat(SleepSemanticSignals.allowlistedKey(
                Component.translatable("block.minecraft.set_spawn")))
                .contains(SleepSemanticSignals.Key.SET_SPAWN);
        assertThat(SleepSemanticSignals.allowlistedKey(
                Component.translatable("block.minecraft.bed.not_safe")))
                .contains(SleepSemanticSignals.Key.BED_NOT_SAFE);

        assertThat(SleepSemanticSignals.allowlistedKey(Component.literal("Respawn point set")))
                .isEmpty();
        assertThat(SleepSemanticSignals.allowlistedKey(
                Component.translatable("mcmcp.unknown")))
                .isEmpty();
        assertThat(SleepSemanticSignals.allowlistedKey(
                Component.literal("ignored").append(
                        Component.translatable("block.minecraft.set_spawn"))))
                .isEmpty();
    }

    @Test
    void ledgerMatchesExactKeysOnlyAfterTheActionBaseline() {
        var ledger = new SleepSemanticSignals.SessionLedger();
        var session = UUID.randomUUID();
        ledger.bind(session);
        ledger.record(SleepSemanticSignals.Key.BED_OCCUPIED);
        var baseline = ledger.bind(session);

        assertThat(ledger.latestAfter(
                baseline, SleepSemanticSignals.Key.BED_OCCUPIED)).isEmpty();

        ledger.record(SleepSemanticSignals.Key.SET_SPAWN);

        assertThat(ledger.latestAfter(baseline, SleepSemanticSignals.Key.SET_SPAWN))
                .contains(new SleepSemanticSignals.Signal(
                        SleepSemanticSignals.Key.SET_SPAWN, 2));
        assertThat(ledger.latestAfter(
                baseline, SleepSemanticSignals.Key.BED_NOT_SAFE)).isEmpty();
    }

    @Test
    void rebindingDropsPriorSessionEvidenceAndRestartsItsRevision() {
        var ledger = new SleepSemanticSignals.SessionLedger();
        var first = ledger.bind(UUID.randomUUID());
        ledger.record(SleepSemanticSignals.Key.SET_SPAWN);

        var second = ledger.bind(UUID.randomUUID());

        assertThat(second.revision()).isZero();
        assertThat(ledger.latestAfter(first, SleepSemanticSignals.Key.SET_SPAWN)).isEmpty();
        assertThat(ledger.latestAfter(second, SleepSemanticSignals.Key.SET_SPAWN)).isEmpty();
    }
}
