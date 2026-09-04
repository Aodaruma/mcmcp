package dev.aod.mcmcp.agent.action;

import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionStoreTest {
    @Test
    void reservedActionCannotRunUntilDeliveryIsConfirmedAndOtherwiseExpires() {
        var store = new AgentActionStore();
        var reserved = store.reserve(program(), source(), Instant.EPOCH, 100L);

        assertThat(store.get(reserved.actionId()).state())
                .isEqualTo(AgentActionStore.State.UNCONFIRMED);
        assertThatThrownBy(() -> store.markRunning(reserved.actionId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.expireUnconfirmed(99L)).isFalse();
        assertThat(store.confirm(reserved.actionId(), 99L))
                .isEqualTo(AgentActionStore.Confirmation.CONFIRMED);
        assertThat(store.get(reserved.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);

        store.cancel(reserved.actionId());
        var abandoned = store.reserve(program(), source(), Instant.EPOCH, 200L);
        assertThat(store.abandonUnconfirmed(abandoned.actionId(), "socket_closed")).isTrue();
        assertThat(store.get(abandoned.actionId()).failure().code())
                .isEqualTo(AgentActionStore.FailureCode.DELIVERY_UNCONFIRMED);

        var expired = store.reserve(program(), source(), Instant.EPOCH, 300L);
        assertThat(store.expireUnconfirmed(300L)).isTrue();
        assertThat(store.get(expired.actionId()).state()).isEqualTo(AgentActionStore.State.FAILED);
    }

    @Test
    void enforcesOneActiveActionAndRetainsOnlyThePreviousTerminalAction() {
        var store = new AgentActionStore();
        var first = store.start(program(), source(), Instant.EPOCH);
        assertThatThrownBy(() -> store.start(program(), source(), Instant.EPOCH))
                .isInstanceOf(AgentActionStore.BusyException.class);

        store.markRunning(first.actionId());
        store.beginNode(first.actionId(), "hold");
        store.recordTick(first.actionId());
        store.completeNode(first.actionId());
        store.succeed(first.actionId());
        var second = store.start(program(), source(), Instant.EPOCH.plusSeconds(1));

        assertThat(store.get(first.actionId()).state()).isEqualTo(AgentActionStore.State.SUCCEEDED);
        assertThat(store.get(second.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);
        assertThat(store.cancel(second.actionId()).cancelRequested()).isTrue();
        assertThat(store.cancel(second.actionId()).cancelRequested()).isFalse();

        var third = store.start(program(), source(), Instant.EPOCH.plusSeconds(2));
        assertThatThrownBy(() -> store.get(first.actionId()))
                .isInstanceOf(AgentActionStore.NotFoundException.class);
        assertThat(store.get(second.actionId()).state()).isEqualTo(AgentActionStore.State.CANCELLED);
        assertThat(store.get(third.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);
    }

    @Test
    void permitsTerminalTraceAtTheRecordedTickLimitButNeverExceedsIt() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        store.markRunning(accepted.actionId());
        for (int tick = 0; tick < AgentActionStore.MAX_RECORDED_TICKS; tick++) {
            store.recordTick(accepted.actionId());
        }

        assertThatThrownBy(() -> store.recordTick(accepted.actionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Action tick limit exceeded");
        assertThat(store.get(accepted.actionId()).progress().ticks())
                .isEqualTo(AgentActionStore.MAX_RECORDED_TICKS);
        assertThatCode(() -> store.terminateActive(new AgentActionStore.Failure(
                AgentActionStore.FailureCode.RECOVERY_EXHAUSTED,
                false,
                java.util.List.of("recovery_budget_exhausted"))))
                .doesNotThrowAnyException();
        assertThat(store.get(accepted.actionId()).trace().getLast().tick())
                .isEqualTo(AgentActionStore.MAX_RECORDED_TICKS);
    }

    @Test
    void saturatesMotionFromExternalCorrectionsAtThePublicSchemaBoundary() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        store.markRunning(accepted.actionId());

        store.recordMotion(accepted.actionId(), Double.MAX_VALUE, Double.MAX_VALUE);
        store.succeed(accepted.actionId());

        var progress = store.get(accepted.actionId()).progress();
        assertThat(progress.distanceTravelled()).isEqualTo(AgentActionStore.MAX_RECORDED_DISTANCE);
        assertThat(progress.cameraDegrees())
                .isEqualTo(AgentActionStore.MAX_RECORDED_CAMERA_DEGREES);
        assertThat(progress.motionOverflowed()).isTrue();
    }

    @Test
    void passiveFarmlandSettlingIsAuditedWithoutInflatingInputDistance() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        store.markRunning(accepted.actionId());

        store.recordMotion(accepted.actionId(), 0.0D, 0.0D);
        store.recordPassiveMotion(
                accepted.actionId(), 1.0D / 16.0D, "farmland_settling");

        var snapshot = store.get(accepted.actionId());
        assertThat(snapshot.progress().distanceTravelled()).isZero();
        assertThat(snapshot.trace()).anySatisfy(trace -> {
            assertThat(trace.event()).isEqualTo("PASSIVE_MOTION");
            assertThat(trace.detail()).isEqualTo("farmland_settling=0.062500");
        });
        assertThatThrownBy(() -> store.recordPassiveMotion(
                accepted.actionId(), 0.125D, "farmland_settling"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsOnlyExplicitlyRecordedServerConfirmedBreaks() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        store.markRunning(accepted.actionId());

        store.recordBlockBreak(accepted.actionId());

        assertThat(store.get(accepted.actionId()).progress().blocksBroken()).isOne();
    }

    @Test
    void boundedTerminalWaitWakesOnCancellationAndTimesOutWithCurrentShape() throws Exception {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        Thread canceller = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
            }
            store.cancel(accepted.actionId());
        });

        var terminal = store.awaitTerminal(accepted.actionId(), 1_000);
        canceller.join();
        assertThat(terminal.state()).isEqualTo(AgentActionStore.State.CANCELLED);

        var next = store.start(program(), source(), Instant.EPOCH.plusSeconds(1));
        var timedOut = store.awaitTerminal(next.actionId(), 10);
        assertThat(timedOut.state()).isEqualTo(AgentActionStore.State.QUEUED);
        assertThat(timedOut.actionId()).isEqualTo(next.actionId());
    }

    @Test
    void terminalWaitBoundsAndUnknownHandlesFailClosed() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);

        assertThatThrownBy(() -> store.awaitTerminal(
                accepted.actionId(), AgentActionStore.MAX_TERMINAL_WAIT_MILLIS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.awaitTerminal(java.util.UUID.randomUUID(), 0))
                .isInstanceOf(AgentActionStore.NotFoundException.class);
    }

    @Test
    void clearingStoreReleasesTerminalWaitAsNotFound() throws Exception {
        var store = new AgentActionStore();
        var accepted = store.start(program(), source(), Instant.EPOCH);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                store.awaitTerminal(
                        accepted.actionId(), AgentActionStore.MAX_TERMINAL_WAIT_MILLIS);
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });

        Thread.sleep(50L);
        store.clear();
        waiter.join(1_000L);
        if (waiter.isAlive()) {
            waiter.interrupt();
        }

        assertThat(waiter.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(AgentActionStore.NotFoundException.class);
    }

    private static ActionDslCompiler.CompiledProgram program() {
        var request = ActionDslParser.parse(requestJson());
        return ActionDslCompiler.compile(request, ignored -> java.util.Optional.empty(), Set.of());
    }

    private static ActionDslSource source() {
        return ActionDslSource.capture(requestJson());
    }

    private static com.google.gson.JsonObject requestJson() {
        return JsonParser.parseString("""
                {
                  "schema_version": 1,
                  "program": {
                    "dsl_version": 1,
                    "capabilities": [],
                    "body": [{"id":"hold","op":"wait_ticks","ticks":2}]
                  },
                  "budget": {
                    "max_duration_ms": 100,
                    "max_ticks": 2,
                    "max_distance_blocks": 0,
                    "max_camera_degrees": 0,
                    "max_interactions": 0,
                    "max_blocks_broken": 0,
                    "max_blocks_placed": 0
                  }
                }
                """).getAsJsonObject();
    }
}
