package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.agent.input.InputSequenceJobExecution;
import dev.aod.mcmcp.agent.input.InputSequenceLeaseDriver;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.BoundedInputLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeV2InputRoutingTest {
    private final McmcpRuntime runtime = new McmcpRuntime("test", "26.2.0.59");

    @AfterEach
    void closeVoiceListener() throws Exception {
        ((AutoCloseable) field("voiceChat").get(runtime)).close();
    }

    @Test
    void deliveryStatusAndCancelRouteToTheSameV2Job() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.INPUT_SEQUENCE, session, 1, Long.MAX_VALUE);
        installExecution(store, id, session);

        var confirmed = invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id);
        assertThat(confirmed).isEqualTo(Map.of("action_id", id.toString(), "confirmed", true));
        var queued = (Map<?, ?>) invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString()));
        assertThat(queued.get("action_id")).isEqualTo(id.toString());
        assertThat(queued.get("state")).isEqualTo("queued");
        assertThat(queued.get("kind")).isEqualTo("input_sequence");

        var cancelled = (Map<?, ?>) invoke("cancelAgentAction",
                new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class},
                null, Map.of("action_id", id.toString()));
        assertThat(cancelled.get("cancel_requested")).isEqualTo(true);
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(field("v2InputExecution").get(runtime)).isNull();
        assertThat(invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString())))
                .asString().contains("cancelled");
    }

    @Test
    void abandonedResponseCannotStartInput() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.INPUT_SEQUENCE, session, 1, Long.MAX_VALUE);
        installExecution(store, id, session);

        var abandoned = invoke("abandonAgentActionDelivery", new Class<?>[]{UUID.class}, id);
        assertThat(abandoned).isEqualTo(Map.of("action_id", id.toString(), "abandoned", true));
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(field("v2InputExecution").get(runtime)).isNull();
        var lateConfirm = invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id);
        assertThat(lateConfirm).isEqualTo(Map.of("action_id", id.toString(), "confirmed", false));
    }

    private void installExecution(AgentJobStore store, UUID id, UUID session) throws Exception {
        var sequence = new FiniteInputSequence(List.of(new FiniteInputSequence.Step(
                Set.of(BoundedInputLease.Input.USE), 1, 0, 1)));
        var driver = new InputSequenceLeaseDriver(sequence, AgentInputState.global());
        field("v2InputExecution").set(runtime, new InputSequenceJobExecution(
                store, id, session, driver, () -> true));
    }

    private java.lang.reflect.Field field(String name) throws Exception {
        var field = McmcpRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = McmcpRuntime.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(runtime, arguments);
    }
}
