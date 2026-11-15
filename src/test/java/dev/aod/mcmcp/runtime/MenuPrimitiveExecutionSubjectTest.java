package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MenuPrimitiveExecutionSubjectTest {
    @Test
    void storageEffectUsesValidSubjectWithoutLeakingSingleUseReference() {
        String reference = "W9pZlNliftWipjBgWn6ROlpC";
        String subject = MenuPrimitiveExecution.storageEffectSubject(
                reference, "minecraft:cobblestone");

        assertThat(subject).startsWith("storage:")
                .endsWith("/minecraft:cobblestone")
                .doesNotContain(reference);
        new AgentActionStore.Effect(1, "store", "storage_store", subject,
                Map.of(), Map.of(), AgentActionStore.Verification.CONFIRMED, 1, 1);
    }
}
