package dev.aod.mcmcp.runtime;

import com.google.gson.JsonParser;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.ActionDslParser;
import dev.aod.mcmcp.agent.dsl.ActionDslSource;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;

class McmcpRuntimeIdleInputTest {
    private final McmcpRuntime runtime = new McmcpRuntime("0.1.0-SNAPSHOT", "26.2.0.59");

    @AfterEach
    void detachRuntimeVoiceListener() throws Exception {
        var field = McmcpRuntime.class.getDeclaredField("voiceChat");
        field.setAccessible(true);
        ((AutoCloseable) field.get(runtime)).close();
    }

    @Test
    void idleTicksNeverTouchMinecraftOrReleaseManualUse() throws Exception {
        assertIdleDoesNotTouchClient(runtime);
    }

    @ParameterizedTest
    @EnumSource(value = AgentActionStore.State.class, names = {"SUCCEEDED", "CANCELLED", "FAILED"})
    void ticksAfterTerminalActionDoNotCancelManualUse(AgentActionStore.State terminal) throws Exception {
        var field = McmcpRuntime.class.getDeclaredField("agentActions");
        field.setAccessible(true);
        var store = (AgentActionStore) field.get(runtime);
        var request = JsonParser.parseString("""
                {"schema_version":1,
                 "program":{"dsl_version":1,"capabilities":[],
                   "body":[{"id":"wait","op":"wait_ticks","ticks":2}]},
                 "budget":{"max_duration_ms":100,"max_ticks":2,"max_distance_blocks":0,
                   "max_camera_degrees":0,"max_interactions":0,"max_blocks_broken":0,
                   "max_blocks_placed":0}}
                """).getAsJsonObject();
        var program = ActionDslCompiler.compile(ActionDslParser.parse(request),
                ignored -> Optional.empty(), Set.of());
        var id = store.start(program, ActionDslSource.capture(request), Instant.EPOCH).actionId();
        store.markRunning(id);
        switch (terminal) {
            case SUCCEEDED -> store.succeed(id);
            case CANCELLED -> store.cancel(id);
            case FAILED -> store.fail(id, new AgentActionStore.Failure(
                    AgentActionStore.FailureCode.SAFETY_INTERRUPTED, false, List.of("test")));
            default -> throw new AssertionError(terminal);
        }
        assertIdleDoesNotTouchClient(runtime);
    }

    private static void assertIdleDoesNotTouchClient(McmcpRuntime runtime) throws Exception {
        var tick = McmcpRuntime.class.getDeclaredMethod("tickAgentAction", Minecraft.class);
        tick.setAccessible(true);
        // A null client makes ANY cleanup/input dispatch fail. Idle must be a pure no-op,
        // including across the many ticks needed to draw a bow or drink a potion.
        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) tick.invoke(runtime, new Object[] { null });
        }).doesNotThrowAnyException();
    }
}
