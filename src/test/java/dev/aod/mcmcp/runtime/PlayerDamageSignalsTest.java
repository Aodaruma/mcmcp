package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerDamageSignalsTest {
    @Test void healingBetweenPacketsDoesNotCancelDamageAndIdentityResetsEvidence() {
        var signals = new PlayerDamageSignals(); var player = new Object();
        signals.bind(player, 20, 2);
        signals.damage(player, true, 20, 2);
        signals.health(player, 20, 1);
        signals.health(player, 19, 1);
        signals.health(player, 20, 1);
        signals.health(player, 19, 1);
        var result = signals.bind(player, 19, 1);
        assertThat(result.loss()).isEqualTo(3);
        assertThat(result.falls()).isEqualTo(1);
        signals.damage(player, false, 19, 1);
        assertThat(signals.bind(player, 19, 1).other()).isEqualTo(1);
        assertThat(signals.bind(new Object(), 20, 0)).isEqualTo(new PlayerDamageSignals.Snapshot(0, 0, 0));
    }
}
