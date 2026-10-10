package dev.aod.mcmcp.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WaystoneMenuSupportTest {
    @Test void nativeRuleScalarResultsDoNotBecomeUnsupportedCosts() throws ReflectiveOperationException {
        var costs = WaystoneMenuSupport.readCosts(List.of(new JsonPrimitive(0.01), new JsonPrimitive("300s"),
                new JsonPrimitive(true), 27f, 0d, 3, 4L, "setting", true));
        assertThat(costs.supported()).isTrue();
        assertThat(costs.levels()).isZero();
        assertThat(costs.points()).isZero();
        assertThat(costs.messages()).isEmpty();
    }

    @Test void unrecognizedStructuredRequirementsStillPreventSelection() throws ReflectiveOperationException {
        var costs = WaystoneMenuSupport.readCosts(List.of(true, new JsonObject(), new Object()));
        assertThat(costs.supported()).isFalse();
        assertThat(costs.messages()).containsExactly("unsupported_requirement:JsonObject", "unsupported_requirement:Object");
    }
}
