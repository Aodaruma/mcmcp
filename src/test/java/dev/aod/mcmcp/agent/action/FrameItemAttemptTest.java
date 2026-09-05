package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.FrameItemPort;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameItemAttemptTest {
    @Test
    void missingFogBeforeAndAfterDispatchWaitsForFreshVisibilityWithoutInventingContents() {
        var port = new FakePort(FrameItemPort.Frame.pendingObservation(0, 5));
        var attempt = attempt(port, FrameItemPort.Mode.INSERT);
        assertThat(attempt.tick(0).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.tick(1).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(port.dispatches).isZero();
        assertThat(attempt.drainEffectDeltas()).isEmpty();
        port.next = frame(null, 2, 10, 8, 20, 64, false);
        assertThat(attempt.tick(2).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.drainInteractionDelta()).isEqualTo(1);
        port.next = FrameItemPort.Frame.pendingObservation(0, 6);
        assertThat(attempt.tick(3).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.drainEffectDeltas()).isEmpty();
        port.next = frame("minecraft:stone", 2, 11, 11, 21, 63, true);
        assertThat(attempt.tick(4).status()).isEqualTo(FrameItemAttempt.Status.SUCCEEDED);
        assertThat(port.dispatches).isEqualTo(1);
        assertThat(attempt.drainInteractionDelta()).isZero();
        assertThat(attempt.drainEffectDeltas()).singleElement()
                .extracting(FrameItemAttempt.EffectDelta::verification)
                .isEqualTo(AgentActionStore.Verification.CONFIRMED);
    }

    @Test
    void missingFogDoesNotExtendPreparationDeadlineOrTheOriginalSixtyTickAckWindow() {
        var preparing = new FakePort(FrameItemPort.Frame.pendingObservation(0, 5));
        var preparation = attempt(preparing, FrameItemPort.Mode.REMOVE);
        assertThat(preparation.tick(0).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(preparation.tick(399).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        var deadline = preparation.tick(400);
        assertThat(deadline.status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(deadline.evidence()).isEqualTo("frame_deadline");
        assertThat(preparing.dispatches).isZero();
        assertThat(preparation.drainEffectDeltas()).isEmpty();

        var waiting = new FakePort(frame(null, 2, 10, 8, 20, 64, false));
        var dispatched = attempt(waiting, FrameItemPort.Mode.INSERT);
        dispatched.tick(0);
        waiting.next = FrameItemPort.Frame.pendingObservation(0, 6);
        assertThat(dispatched.tick(1).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(dispatched.tick(59).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        var timeout = dispatched.tick(60);
        assertThat(timeout.status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(timeout.evidence()).isEqualTo("frame_item_ack_timeout");
        assertThat(waiting.dispatches).isEqualTo(1);
        var effect = dispatched.drainEffectDeltas().getFirst();
        assertThat(effect.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(effect.observedBefore()).containsEntry("inventory_count", 64);
        assertThat(effect.observedAfter()).isEmpty();
        waiting.next = frame("minecraft:stone", 2, 11, 11, 21, 63, true);
        assertThat(dispatched.tick(61).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(dispatched.drainEffectDeltas()).isEmpty();
    }

    @Test
    void cancellationDuringMissingFogKeepsOnlyAnActuallyDispatchedUnknownEffect() {
        for (var mode : FrameItemPort.Mode.values()) {
            for (boolean dispatchFirst : new boolean[]{false, true}) {
                String initialItem = mode == FrameItemPort.Mode.REMOVE ? "minecraft:stone" : null;
                var port = new FakePort(dispatchFirst ? frame(initialItem, 2, 10, 8, 20, 64, false)
                        : FrameItemPort.Frame.pendingObservation(0, 5));
                var attempt = attempt(port, mode);
                attempt.tick(0);
                port.next = FrameItemPort.Frame.pendingObservation(0, 6);
                attempt.tick(1);
                attempt.close();
                assertThat(attempt.releaseStatus()).isEqualTo(FrameItemAttempt.ReleaseStatus.CONFIRMED);
                assertThat(port.dispatches).isEqualTo(dispatchFirst ? 1 : 0);
                assertThat(attempt.drainInteractionDelta()).isEqualTo(dispatchFirst ? 1 : 0);
                var effects = attempt.drainEffectDeltas();
                if (dispatchFirst) {
                    assertThat(effects).hasSize(1);
                    assertThat(effects.getFirst().verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
                    assertThat(effects.getFirst().observedAfter()).isEmpty();
                } else assertThat(effects).isEmpty();
            }
        }
    }

    @Test
    void freshFogWithAnActualVisibilityFailureStillStopsBeforeDispatch() {
        var port = new FakePort(FrameItemPort.Frame.pendingObservation(0, 5));
        var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
        assertThat(attempt.tick(0).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        port.next = new FrameItemPort.Frame(0, 5, false, "frame_front_not_visible", true,
                2, "minecraft:stone", 10, true, 8, "minecraft:stone", 20, 64, false);
        var result = attempt.tick(1);
        assertThat(result.status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("frame_front_not_visible");
        assertThat(port.dispatches).isZero();
        assertThat(attempt.drainEffectDeltas()).isEmpty();
    }

    @Test
    void removeRequiresFreshItemFieldAckAndNeverClaimsDropCollection() {
        var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
        assertThat(attempt.tick(0).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.drainInteractionDelta()).isEqualTo(1);
        // A changed live getter with an old item field is not server confirmation.
        port.next = frame(null, 2, 11, 8, 20, 64, false);
        assertThat(attempt.tick(1).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        port.next = frame(null, 2, 12, 12, 21, 65, false);
        assertThat(attempt.tick(2).status()).isEqualTo(FrameItemAttempt.Status.SUCCEEDED);
        assertThat(port.dispatches).isEqualTo(1);
        assertThat(attempt.drainInteractionDelta()).isZero();
        var effects = attempt.drainEffectDeltas();
        assertThat(effects).hasSize(1);
        assertThat(effects.getFirst().verification()).isEqualTo(AgentActionStore.Verification.CONFIRMED);
        assertThat(effects.getFirst().observedAfter()).containsEntry("displayed_item", "minecraft:air")
                .containsEntry("body_alive", true).containsEntry("rotation", 2)
                .doesNotContainKey("inventory_count");
        assertThat(attempt.releaseStatus()).isEqualTo(FrameItemAttempt.ReleaseStatus.CONFIRMED);
    }

    @Test
    void insertWaitsForBothDisplayAndExactServerInventoryConsumption() {
        var port = new FakePort(frame(null, 2, 10, 8, 20, 64, false));
        var attempt = attempt(port, FrameItemPort.Mode.INSERT);
        attempt.tick(0);
        port.next = frame("minecraft:stone", 2, 11, 11, 20, 63, true);
        assertThat(attempt.tick(1).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        port.next = frame("minecraft:stone", 2, 11, 11, 21, 63, false);
        assertThat(attempt.tick(2).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        port.next = frame("minecraft:stone", 2, 11, 11, 21, 62, true);
        assertThat(attempt.tick(3).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        port.next = frame("minecraft:stone", 2, 11, 11, 22, 63, true);
        assertThat(attempt.tick(4).status()).isEqualTo(FrameItemAttempt.Status.SUCCEEDED);
        assertThat(port.dispatches).isEqualTo(1);
        var effect = attempt.drainEffectDeltas().getFirst();
        assertThat(effect.observedBefore()).containsEntry("displayed_item", "minecraft:air")
                .containsEntry("inventory_count", 64);
        assertThat(effect.observedAfter()).containsEntry("displayed_item", "minecraft:stone")
                .containsEntry("inventory_count", 63);
    }

    @Test
    void timeoutOrDispatchExceptionRetainsUnknownAndNeverResends() {
        var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
        attempt.tick(0);
        assertThat(attempt.tick(60).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(attempt.tick(61).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(port.dispatches).isEqualTo(1);
        var unknown = attempt.drainEffectDeltas().getFirst();
        assertThat(unknown.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(unknown.observedAfter()).doesNotContainKey("displayed_item");

        var throwing = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        throwing.throwOnDispatch = true;
        var failed = attempt(throwing, FrameItemPort.Mode.REMOVE);
        assertThat(failed.tick(0).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        failed.close();
        assertThat(failed.drainInteractionDelta()).isEqualTo(1);
        assertThat(failed.drainInteractionDelta()).isZero();
        assertThat(failed.drainEffectDeltas()).singleElement()
                .extracting(FrameItemAttempt.EffectDelta::verification)
                .isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(throwing.dispatches).isEqualTo(1);
    }

    @Test
    void rotationOrBodyChangeCannotBecomeSuccessEvenWithMatchingItemAck() {
        for (boolean bodyAlive : new boolean[]{true, false}) {
            var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
            var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
            attempt.tick(0);
            var changed = frame(null, bodyAlive ? 3 : 2, 11, 11, 20, 64, false);
            port.next = new FrameItemPort.Frame(0, 5, true, null, bodyAlive,
                    changed.rotation(), changed.displayedItem(), 11, true, 11, null, 20, 64, false);
            assertThat(attempt.tick(1).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
            assertThat(attempt.drainEffectDeltas().getFirst().verification())
                    .isEqualTo(AgentActionStore.Verification.UNKNOWN);
            assertThat(port.dispatches).isEqualTo(1);
        }
    }

    @Test
    void terminalIntentWaitsForCleanupAndRemainsTheSameAcrossRetries() {
        var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        port.failedReleases = 2;
        var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
        attempt.tick(0);
        port.next = frame(null, 2, 11, 11, 20, 64, false);
        assertThat(attempt.tick(1).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.releaseStatus()).isEqualTo(FrameItemAttempt.ReleaseStatus.PROGRESSING);
        assertThat(attempt.tick(2).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        assertThat(attempt.tick(3).status()).isEqualTo(FrameItemAttempt.Status.SUCCEEDED);
        assertThat(attempt.drainEffectDeltas()).singleElement()
                .extracting(FrameItemAttempt.EffectDelta::verification)
                .isEqualTo(AgentActionStore.Verification.CONFIRMED);
        assertThat(port.dispatches).isEqualTo(1);
    }

    @Test
    void insufficientAckBudgetAndEmptyRemovalStopBeforeDispatch() {
        var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        var attempt = new FrameItemAttempt(port, request(FrameItemPort.Mode.REMOVE), 0, 59);
        assertThat(attempt.tick(0).status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(port.dispatches).isZero();
        assertThat(attempt.drainEffectDeltas()).isEmpty();
        var empty = new FakePort(frame(null, 2, 10, 8, 20, 64, false));
        assertThat(attempt(empty, FrameItemPort.Mode.REMOVE).tick(0).status())
                .isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(empty.dispatches).isZero();
        assertThatThrownBy(() -> new FrameItemAttempt(port, request(FrameItemPort.Mode.REMOVE), 0, 401))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invisibleFailureOmitsAfterValuesAndCleanupFaultDoesNotPublishTerminalEarly() {
        var port = new FakePort(frame("minecraft:stone", 2, 10, 8, 20, 64, false));
        port.failedReleases = 40;
        var attempt = attempt(port, FrameItemPort.Mode.REMOVE);
        attempt.tick(0);
        port.next = new FrameItemPort.Frame(0, 5, false, "frame_front_not_visible", true,
                7, "minecraft:diamond", 20, true, 20, "minecraft:diamond", 20, 64, false);
        for (int tick = 1; tick <= 40; tick++) {
            assertThat(attempt.tick(tick).status()).isEqualTo(FrameItemAttempt.Status.RUNNING);
        }
        assertThat(attempt.releaseStatus()).isEqualTo(FrameItemAttempt.ReleaseStatus.FAULT);
        var unknown = attempt.drainEffectDeltas().getFirst();
        assertThat(unknown.verification()).isEqualTo(AgentActionStore.Verification.UNKNOWN);
        assertThat(unknown.observedAfter()).isEmpty();
        var terminal = attempt.tick(41);
        assertThat(terminal.status()).isEqualTo(FrameItemAttempt.Status.FAILED);
        assertThat(terminal.evidence()).isEqualTo("frame_front_not_visible");
        assertThat(port.dispatches).isEqualTo(1);
    }

    private static FrameItemAttempt attempt(FakePort port, FrameItemPort.Mode mode) {
        return new FrameItemAttempt(port, request(mode), 0, 400);
    }

    private static FrameItemPort.Request request(FrameItemPort.Mode mode) {
        return new FrameItemPort.Request(mode, "abcdefghijklmnopqrstuvwx", "minecraft:stone", 2,
                new UUID(0, 1), "minecraft:overworld", new FrameItemPort.AimPoint(1, 65, 1), 8);
    }

    private static FrameItemPort.Frame frame(String item, int rotation, long packetRevision,
                                             long itemRevision, long inventoryRevision,
                                             int count, boolean exactConsumption) {
        return new FrameItemPort.Frame(0, 5, true, null, true, rotation, item,
                packetRevision, true, itemRevision, item, inventoryRevision, count, exactConsumption);
    }

    private static final class FakePort implements FrameItemPort {
        private Frame next;
        private int dispatches;
        private boolean throwOnDispatch;
        private int failedReleases;
        private int releases;

        private FakePort(Frame next) { this.next = next; }
        @Override public Frame prepare(Request request, long tick) { return atTick(tick); }
        @Override public Frame observe(Request request, long tick) { return atTick(tick); }
        @Override public void dispatch(Request request, long tick) {
            dispatches++;
            if (throwOnDispatch) throw new IllegalStateException("simulated post-send exception");
        }
        @Override public boolean release() { return ++releases > failedReleases; }
        private Frame atTick(long tick) {
            return new Frame(tick, next.worldRevision(), next.ready(), next.failure(), next.bodyAlive(),
                    next.rotation(), next.displayedItem(), next.packetRevision(), next.serverItemObserved(),
                    next.itemRevision(), next.serverItem(), next.inventoryRevision(), next.inventoryCount(),
                    next.inventoryExactChange(), next.observationPending());
        }
    }
}
