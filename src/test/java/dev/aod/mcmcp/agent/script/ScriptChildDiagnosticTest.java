package dev.aod.mcmcp.agent.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptChildDiagnosticTest {
    @Test
    void oversizedInventoryCannotDisplaceIdentityOrQuantityAndMarksTruncation() {
        var result = new LinkedHashMap<String, Object>();
        result.put("items", java.util.Collections.nCopies(10000, Map.of("item", "minecraft:stone", "count", 64)));
        result.put("effects", List.of(Map.of("verification", "unknown")));
        result.put("confirmed_count", 0); result.put("unconfirmed", true); result.put("release_pending", true);
        var diagnostic = ScriptChildDiagnostic.capture("inventory", ScriptJobExecution.Outcome.FAILURE,
                Map.of("action_id", "stopped-child", "state", "failed", "result", result));
        assertThat(diagnostic.get("truncated")).isEqualTo(true);
        var action = (Map<?, ?>) diagnostic.get("action");
        assertThat(action.get("action_id")).isEqualTo("stopped-child");
        var retained = (Map<?, ?>) action.get("result");
        assertThat(retained.get("confirmed_count")).isEqualTo(0);
        assertThat(retained.get("unconfirmed")).isEqualTo(true);
        assertThat(retained.get("release_pending")).isEqualTo(true);
        assertThat(retained.get("effects")).isEqualTo(List.of(Map.of("verification", "unknown")));
        assertThat(jsonBytes(diagnostic)).isLessThanOrEqualTo(8192);
    }

    @Test
    void multibyteEscapedStringsAndCyclesRemainBounded() {
        var cyclic = new ArrayList<Object>(); cyclic.add(cyclic);
        for (Object value : List.of(cyclic, "<漢字\"\\\n>".repeat(2000),
                java.util.Collections.nCopies(300, "<漢字\"\\\n>".repeat(30)))) {
            var diagnostic = ScriptChildDiagnostic.capture("inventory", ScriptJobExecution.Outcome.FAILURE,
                    Map.of("action_id", "child", "result", Map.of("data", value)));
            assertThat(diagnostic.get("truncated")).isEqualTo(true);
            assertThat(jsonBytes(diagnostic)).isLessThanOrEqualTo(8192);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticIsDeeplyImmutableAndRetainsNullFailure() {
        var child = new LinkedHashMap<String, Object>();
        var list = new ArrayList<Object>(List.of(1)); child.put("failure", null); child.put("result", list);
        var diagnostic = ScriptChildDiagnostic.capture("inventory", ScriptJobExecution.Outcome.SUCCESS, child);
        list.add(2); child.clear();
        var retained = (Map<String, Object>) diagnostic.get("action");
        assertThat(retained).containsEntry("failure", null).containsEntry("result", List.of(1));
        assertThatThrownBy(() -> retained.put("state", "changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) retained.get("result")).add(3)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static int jsonBytes(Object value) {
        return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
