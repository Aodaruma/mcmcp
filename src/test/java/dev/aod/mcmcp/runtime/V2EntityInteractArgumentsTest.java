package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2EntityInteractArgumentsTest {
    private static final String REF = "abcdefghijklmnopqrstuvwx";

    @Test
    void currentHandAndModTargetsDoNotNeedSpecialHandlers() {
        var current = V2EntityInteractArguments.parse(Map.of("target", "entity", "entity_ref", REF));
        assertThat(current.item()).isNull();
        assertThat(current.type()).isNull();
        assertThat(current.maxTicks()).isEqualTo(200);
        var mod = V2EntityInteractArguments.parse(Map.of("target", "entity", "entity_ref", REF,
                "entity_type", "example:animal", "item", "example:flask", "result_item", "example:milk"));
        assertThat(mod.type()).isEqualTo("example:animal");
        assertThat(mod.resultItem()).isEqualTo("example:milk");
        assertThat(V2EntityInteractArguments.parse(Map.of("target", "entity", "entity_ref", REF,
                "item", "minecraft:air")).item()).isEqualTo("minecraft:air");
    }

    @Test
    void rejectsForgedReferencesAmbiguousTargetsAndUnboundedJobs() {
        for (var args : List.of(
                Map.<String, Object>of("target", "entity", "entity_ref", "bad"),
                Map.<String, Object>of("target", "entity", "entity_ref", REF, "entity_type", "unqualified"),
                Map.<String, Object>of("target", "entity", "entity_ref", REF, "max_ticks", 0),
                Map.<String, Object>of("target", "entity", "entity_ref", REF, "max_ticks", 1201),
                Map.<String, Object>of("target", "entity", "entity_ref", REF, "x", 3),
                Map.<String, Object>of("target", "item", "entity_ref", REF))) {
            assertThatThrownBy(() -> V2EntityInteractArguments.parse(args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
