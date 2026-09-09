package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionSelectionEvidenceTest {
    @Test
    void failedMovementPreparationCannotRepublishThePreviousPrimitiveSelectionOrPickup() throws Exception {
        var execution = new MovementExecution(8.0F, null, null, null);
        seed(execution, "agentSelectedSlot", 4);
        seed(execution, "pickupCell", new NavCell("minecraft:overworld", 1, 64, 2));

        // A world/precondition failure must not make the root's finally block consume old evidence.
        assertThatThrownBy(() -> execution.begin(null, null, null, null, 0L, null, null,
                false, Map.of(), null, -1)).isInstanceOf(NullPointerException.class);

        assertThat(execution.selectedSlot()).isEqualTo(-1);
        assertThat(execution.pickupCell()).isNull();
    }

    @Test
    void failedCobblestoneTickCannotOverwriteSelectionFromAnInterveningPrimitive() throws Exception {
        var execution = new CobblestoneExecution(new UUID(0, 1), new dev.aod.mcmcp.agent.action.AgentActionStore(), null);
        seed(execution, "agentSelectedSlot", 2);

        assertThatThrownBy(() -> execution.tick(null, null, null, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(execution.selectedSlot()).isEqualTo(-1);
    }

    private static void seed(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
