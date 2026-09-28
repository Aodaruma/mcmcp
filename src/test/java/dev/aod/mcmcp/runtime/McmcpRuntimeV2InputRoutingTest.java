package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.agent.input.InputSequenceJobExecution;
import dev.aod.mcmcp.agent.input.InputSequenceLeaseDriver;
import dev.aod.mcmcp.agent.navigation.CoordinateMoveJobExecution;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
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

    @Test
    void moveUsesSharedDeliveryStatusAndCancelRouting() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.MOVE, session, 10, Long.MAX_VALUE);
        field("v2MoveExecution").set(runtime, new CoordinateMoveJobExecution(
                store, id, session, new NavCell("overworld", 1, 64, 0),
                0.25D, 16.0D, new CoordinateMoveJobExecution.MovementDriver() {
                    @Override public void begin(RoutePlan route, double tolerance) { }
                    @Override public MinecraftActionPrimitiveExecutor.TickResult tick(
                            KnownTraversabilitySnapshot map, double remainingDistance,
                            long clientTick, java.util.function.BooleanSupplier outputAllowed) {
                        throw new AssertionError("a cancelled move must not tick");
                    }
                    @Override public boolean active() { return false; }
                    @Override public void close() { }
                }, () -> true));

        assertThat(invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id))
                .isEqualTo(Map.of("action_id", id.toString(), "confirmed", true));
        var queued = (Map<?, ?>) invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString()));
        assertThat(queued.get("kind")).isEqualTo("move");
        assertThat(queued.get("state")).isEqualTo("queued");
        var cancelled = (Map<?, ?>) invoke("cancelAgentAction",
                new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class},
                null, Map.of("action_id", id.toString()));
        assertThat(cancelled.get("cancel_requested")).isEqualTo(true);
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(field("v2MoveExecution").get(runtime)).isNull();
    }

    @Test
    void breakUsesSharedDeliveryStatusAndCancelRouting() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.BREAK_BLOCK, session, 10, Long.MAX_VALUE);
        var request = V2BreakArguments.parse(Map.of(
                "x", 1, "y", 64, "z", 0), "overworld");
        field("v2BreakExecution").set(runtime, new V2BlockJobExecution<>(
                store, id, session, AgentJobStore.Kind.BREAK_BLOCK, request,
                new V2BlockJobExecution.Driver<V2BreakArguments>() {
                    @Override public String dimension() { return "overworld"; }
                    @Override public V2BlockJobExecution.BeginResult begin(NavCell target,
                            V2BreakArguments args,
                            java.util.function.BooleanSupplier outputAllowed) {
                        throw new AssertionError("a cancelled break must not start");
                    }
                    @Override public V2BlockJobExecution.StepResult tick(long clientTick,
                            java.util.function.BooleanSupplier outputAllowed) {
                        throw new AssertionError("a cancelled break must not tick");
                    }
                    @Override public void close() { }
                }, () -> true));
        assertThat(invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id))
                .isEqualTo(Map.of("action_id", id.toString(), "confirmed", true));
        var queued = (Map<?, ?>) invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString()));
        assertThat(queued.get("kind")).isEqualTo("break_block");
        assertThat(queued.get("state")).isEqualTo("queued");
        var progress = (Map<?, ?>) queued.get("progress");
        assertThat(progress.get("scanned_cells")).isEqualTo(0);
        assertThat(progress.get("broken_blocks")).isEqualTo(0);
        var cancelled = (Map<?, ?>) invoke("cancelAgentAction",
                new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class},
                null, Map.of("action_id", id.toString()));
        assertThat(cancelled.get("cancel_requested")).isEqualTo(true);
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(field("v2BreakExecution").get(runtime)).isNull();
    }

    @Test
    void clickUsesTheSameDeliveryStatusAndCancelOwner() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.CLICK, session, 1, Long.MAX_VALUE);
        var sequence = V2ClickArguments.parse(Map.of("button", "middle"));
        field("v2InputExecution").set(runtime, new InputSequenceJobExecution(
                store, id, session, AgentJobStore.Kind.CLICK,
                new InputSequenceLeaseDriver(sequence, AgentInputState.global()),
                () -> true, ignored -> { }));

        assertThat(invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id))
                .isEqualTo(Map.of("action_id", id.toString(), "confirmed", true));
        var queued = (Map<?, ?>) invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString()));
        assertThat(queued.get("kind")).isEqualTo("click");
        var cancelled = (Map<?, ?>) invoke("cancelAgentAction",
                new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class},
                null, Map.of("action_id", id.toString()));
        assertThat(cancelled.get("cancel_requested")).isEqualTo(true);
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(field("v2InputExecution").get(runtime)).isNull();
    }

    @Test
    void placeProgressUsesTheSharedActionStatusWithoutBreakLabels() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.PLACE_BLOCK, session, 10, Long.MAX_VALUE);
        store.confirm(id, 1);
        store.start(id, session);
        store.recordBlockProgress(id, 2, 1);
        var payload = (Map<?, ?>) invoke("getAgentAction", new Class<?>[]{Map.class},
                Map.of("action_id", id.toString()));
        assertThat(payload.get("kind")).isEqualTo("place_block");
        var progress = (Map<?, ?>) payload.get("progress");
        assertThat(progress.get("scanned_cells")).isEqualTo(2);
        assertThat(progress.get("placed_blocks")).isEqualTo(1);
        assertThat(progress.containsKey("broken_blocks")).isFalse();
    }

    @Test
    void placeDeliveryAndCancelRouteToItsOwner() throws Exception {
        var session = UUID.randomUUID();
        var store = (AgentJobStore) field("v2Jobs").get(runtime);
        var id = store.reserve(AgentJobStore.Kind.PLACE_BLOCK, session, 10, Long.MAX_VALUE);
        var request = V2PlaceArguments.parse(Map.of(
                "x", 1, "y", 64, "z", 0, "block", "minecraft:stone"),
                "overworld");
        field("v2PlaceExecution").set(runtime, new V2BlockJobExecution<>(
                store, id, session, AgentJobStore.Kind.PLACE_BLOCK, request,
                new V2BlockJobExecution.Driver<V2PlaceArguments>() {
                    @Override public String dimension() { return "overworld"; }
                    @Override public V2BlockJobExecution.BeginResult begin(NavCell target,
                            V2PlaceArguments args,
                            java.util.function.BooleanSupplier outputAllowed) {
                        throw new AssertionError("a cancelled place must not start");
                    }
                    @Override public V2BlockJobExecution.StepResult tick(long clientTick,
                            java.util.function.BooleanSupplier outputAllowed) {
                        throw new AssertionError("a cancelled place must not tick");
                    }
                    @Override public void close() { }
                }, () -> true));
        assertThat(invoke("confirmAgentActionDelivery", new Class<?>[]{UUID.class}, id))
                .isEqualTo(Map.of("action_id", id.toString(), "confirmed", true));
        var cancelled = (Map<?, ?>) invoke("cancelAgentAction",
                new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class},
                null, Map.of("action_id", id.toString()));
        assertThat(cancelled.get("cancel_requested")).isEqualTo(true);
        assertThat(store.get(id).state()).isEqualTo(AgentJobStore.State.CANCELLED);
        assertThat(field("v2PlaceExecution").get(runtime)).isNull();
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
