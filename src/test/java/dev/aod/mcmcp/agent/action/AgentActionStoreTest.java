package dev.aod.mcmcp.agent.action;

import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
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
        var reserved = store.reserve(program(), Instant.EPOCH, 100L);

        assertThat(store.get(reserved.actionId()).state())
                .isEqualTo(AgentActionStore.State.UNCONFIRMED);
        assertThatThrownBy(() -> store.markRunning(reserved.actionId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.expireUnconfirmed(99L)).isFalse();
        assertThat(store.confirm(reserved.actionId(), 99L))
                .isEqualTo(AgentActionStore.Confirmation.CONFIRMED);
        assertThat(store.get(reserved.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);

        store.cancel(reserved.actionId());
        var abandoned = store.reserve(program(), Instant.EPOCH, 200L);
        assertThat(store.abandonUnconfirmed(abandoned.actionId(), "socket_closed")).isTrue();
        assertThat(store.get(abandoned.actionId()).failure().code())
                .isEqualTo(AgentActionStore.FailureCode.DELIVERY_UNCONFIRMED);

        var expired = store.reserve(program(), Instant.EPOCH, 300L);
        assertThat(store.expireUnconfirmed(300L)).isTrue();
        assertThat(store.get(expired.actionId()).state()).isEqualTo(AgentActionStore.State.FAILED);
    }

    @Test
    void enforcesOneActiveActionAndRetainsOnlyThePreviousTerminalAction() {
        var store = new AgentActionStore();
        var first = store.start(program(), Instant.EPOCH);
        assertThatThrownBy(() -> store.start(program(), Instant.EPOCH))
                .isInstanceOf(AgentActionStore.BusyException.class);

        store.markRunning(first.actionId());
        store.beginNode(first.actionId(), "hold");
        store.recordTick(first.actionId());
        store.completeNode(first.actionId());
        store.succeed(first.actionId());
        var second = store.start(program(), Instant.EPOCH.plusSeconds(1));

        assertThat(store.get(first.actionId()).state()).isEqualTo(AgentActionStore.State.SUCCEEDED);
        assertThat(store.get(second.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);
        assertThat(store.cancel(second.actionId()).cancelRequested()).isTrue();
        assertThat(store.cancel(second.actionId()).cancelRequested()).isFalse();

        var third = store.start(program(), Instant.EPOCH.plusSeconds(2));
        assertThatThrownBy(() -> store.get(first.actionId()))
                .isInstanceOf(AgentActionStore.NotFoundException.class);
        assertThat(store.get(second.actionId()).state()).isEqualTo(AgentActionStore.State.CANCELLED);
        assertThat(store.get(third.actionId()).state()).isEqualTo(AgentActionStore.State.QUEUED);
    }

    @Test
    void permitsTerminalTraceAtEightHundredTicksButNeverMutatesToEightHundredOne() {
        var store = new AgentActionStore();
        var accepted = store.start(program(), Instant.EPOCH);
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
        var accepted = store.start(program(), Instant.EPOCH);
        store.markRunning(accepted.actionId());

        store.recordMotion(accepted.actionId(), Double.MAX_VALUE, Double.MAX_VALUE);
        store.succeed(accepted.actionId());

        var progress = store.get(accepted.actionId()).progress();
        assertThat(progress.distanceTravelled()).isEqualTo(AgentActionStore.MAX_RECORDED_DISTANCE);
        assertThat(progress.cameraDegrees())
                .isEqualTo(AgentActionStore.MAX_RECORDED_CAMERA_DEGREES);
        assertThat(progress.motionOverflowed()).isTrue();
    }

    private static ActionDslCompiler.CompiledProgram program() {
        var request = ActionDslParser.parse(JsonParser.parseString("""
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
                """).getAsJsonObject());
        return ActionDslCompiler.compile(request, ignored -> java.util.Optional.empty(), Set.of());
    }
}
