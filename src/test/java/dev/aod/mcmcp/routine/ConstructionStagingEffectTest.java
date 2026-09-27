package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConstructionStagingEffectTest {
    @Test
    void actualPortReceiptsPassPublicLedgerValidationWithoutExposingSlots() {
        var source = new StackFingerprint("minecraft:torch", 16, 1);
        for (var destination : new StackFingerprint[]{StackFingerprint.EMPTY,
                new StackFingerprint("minecraft:snow_block", 64, 2)}) {
            var before = MinecraftApplyBlockPlanPort.stagingObservation(source, destination);
            var after = MinecraftApplyBlockPlanPort.stagingObservation(destination, source);
            var confirmed = new AgentActionStore.Effect(1, "light", "inventory_swap",
                    "inventory:construction_hotbar", before, after, AgentActionStore.Verification.CONFIRMED, 3, 8);
            var unknown = new AgentActionStore.Effect(1, "light", "inventory_swap",
                    "inventory:construction_hotbar", before, Map.of(), AgentActionStore.Verification.UNKNOWN, 2, 7);
            assertThat(confirmed.observedAfter()).containsEntry("destination_item", "minecraft:torch")
                    .containsEntry("destination_count", 16);
            assertThat(unknown.observedAfter()).isEmpty();
            assertThat(before.keySet()).noneMatch(key -> key.contains("slot"));
        }
    }
}
