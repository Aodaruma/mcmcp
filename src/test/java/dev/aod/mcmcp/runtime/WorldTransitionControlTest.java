package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.safety.LocalArmingState;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WorldTransitionControlTest {
    private final Object connection = new Object();
    private final LocalArmingState arming = new LocalArmingState();
    private final WorldTransitionControl handoff = new WorldTransitionControl();

    private void suspend(boolean active) {
        var session = UUID.randomUUID();
        arming.arm(session, Set.of("movement"));
        if (active) arming.beginAction(session);
        var candidate = handoff.capture(connection, "overworld", arming.peek(), 5, "server.example", true);
        arming.lock("level_or_dimension_change");
        handoff.suspend(candidate, receipt(6, true), arming.peek(), 6, true);
    }

    private static ClientCommandInbox.StopReceipt receipt(long epoch, boolean released) {
        return new ClientCommandInbox.StopReceipt("level_or_dimension_change", epoch, 2, released, released, true, 0);
    }

    @Test void readyAndActiveIntentTransferOnceAfterCleanupWithoutTransferringAnAction() {
        for (boolean active : new boolean[]{false, true}) {
            suspend(active);
            var candidate = handoff.take(connection, "the_end", arming.peek(), 6, true);
            assertThat(candidate.capabilities()).containsExactly("movement");
            assertThat(candidate.multiplayerConsent()).isEqualTo("server.example");
            assertThat(arming.peek().locked()).isTrue();
            assertThat(handoff.take(connection, "the_end", arming.peek(), 6, true)).isNull();
        }
    }

    @Test void unloadThenPlayerCloneCanKeepTheSameIntent() {
        suspend(false);
        var candidate = handoff.capture(connection, null, arming.peek(), 6, null, true);
        arming.lock("player_respawn");
        handoff.suspend(candidate, new ClientCommandInbox.StopReceipt("player_respawn", 7, 3, true, true, true, 0),
                arming.peek(), 7, true);
        assertThat(handoff.take(connection, "the_nether", arming.peek(), 7, true)).isNotNull();
    }

    @Test void offStopDisconnectDeathAndSameDimensionRespawnCannotReenable() {
        for (String reason : new String[]{"local_ui_disabled", "disconnect", "player_unavailable", "endpoint_fault"}) {
            suspend(false);
            arming.lock(reason);
            assertThat(handoff.take(connection, "the_end", arming.peek(), 6, true)).isNull();
        }
        suspend(false);
        assertThat(handoff.take(connection, "the_end", arming.peek(), 7, true)).isNull();
        suspend(false);
        assertThat(handoff.take(new Object(), "the_end", arming.peek(), 6, true)).isNull();
        suspend(false);
        assertThat(handoff.take(connection, "overworld", arming.peek(), 6, true)).isNull();
        suspend(false);
        assertThat(handoff.capture(connection, null, arming.peek(), 6, null, false)).isNull();
        assertThat(handoff.take(connection, "the_end", arming.peek(), 6, true)).isNull();
    }

    @Test void incompleteCleanupConcurrentStopAndIneligibleLeaseAreNotCarried() {
        for (int failure = 0; failure < 4; failure++) {
            arming.arm(UUID.randomUUID(), Set.of("movement"));
            var candidate = handoff.capture(connection, "overworld", arming.peek(), 5, null, failure != 3);
            arming.lock("level_or_dimension_change");
            handoff.suspend(candidate, receipt(failure == 1 ? 7 : 6, failure != 0), arming.peek(),
                    failure == 1 ? 7 : 6, failure != 2);
            assertThat(handoff.take(connection, "the_end", arming.peek(), failure == 1 ? 7 : 6, true)).isNull();
        }
    }
}
