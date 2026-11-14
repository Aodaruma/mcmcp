package dev.aod.mcmcp.safety;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BowUseCancellationTest {
    @Test void uncertainFireStopsLocalUseAndTransfersOnlyCancellationToTheNextOwner() {
        for (boolean linkageFailure : new boolean[] { false, true }) {
            var cancellation = new BowUseCancellation();
            var player = new Object();
            var sent = new ArrayList<Integer>();
            int[] fires = { 0 };
            boolean[] using = { true };
            Throwable failure = linkageFailure ? new LinkageError("fire hook")
                    : new IllegalStateException("fire send");
            assertThatThrownBy(() -> cancellation.fire(player, 2, slot -> {
                sent.add(slot);
                throw new IllegalStateException("cancel send");
            }, () -> {
                fires[0]++;
                if (failure instanceof LinkageError error) throw error;
                throw (RuntimeException) failure;
            }, () -> using[0] = false)).isSameAs(failure);
            assertThat(using[0]).isFalse();
            assertThat(cancellation.pendingFor(player)).isTrue();
            assertThat(sent).containsExactly(3, 2);
            sent.clear();
            // Global cleanup sees the pending cancellation after the combat lease is already gone.
            assertThat(cancellation.cancel(player, 2, sent::add, () -> using[0] = false)).isTrue();
            assertThat(sent).containsExactly(3, 2);
            assertThat(fires[0]).isEqualTo(1);
            assertThat(cancellation.pendingFor(player)).isFalse();
        }
    }

    @Test void eitherPacketFailureStillRestoresSlotStopsUseAndRetainsUnconfirmedCleanup() {
        for (int failingSlot : new int[] { 0, 8 }) {
            var cancellation = new BowUseCancellation();
            var player = new Object();
            var sent = new ArrayList<Integer>();
            boolean[] using = { true };
            assertThat(cancellation.cancel(player, 8, slot -> {
                sent.add(slot);
                if (slot == failingSlot) throw new IllegalStateException("disconnected");
            }, () -> using[0] = false)).isFalse();
            assertThat(sent).containsExactly(0, 8);
            assertThat(using[0]).isFalse();
            assertThat(cancellation.pendingFor(player)).isTrue();
            sent.clear();
            assertThat(cancellation.cancel(player, 8, sent::add, () -> using[0] = false)).isTrue();
            assertThat(sent).containsExactly(0, 8);
            assertThat(cancellation.pendingFor(player)).isFalse();
        }
    }

    @Test void linkageFailureAndLocalStopFailureRemainPendingUntilThePlayerLeaves() {
        var cancellation = new BowUseCancellation();
        var player = new Object();
        int[] stops = { 0 };
        assertThat(cancellation.cancel(player, 1, slot -> { throw new LinkageError("send hook"); },
                () -> stops[0]++)).isFalse();
        assertThat(stops[0]).isEqualTo(1);
        assertThat(cancellation.cancel(player, 1, slot -> { },
                () -> { throw new IllegalStateException("stop hook"); })).isFalse();
        assertThat(cancellation.pendingFor(player)).isTrue();
        assertThat(cancellation.pendingFor(new Object())).isFalse();
        assertThat(cancellation.pendingFor(player)).isFalse();
    }
}
