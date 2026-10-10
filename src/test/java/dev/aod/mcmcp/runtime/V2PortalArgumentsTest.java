package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2PortalArgumentsTest {
    private Map<String,Object> args() { return new HashMap<>(Map.of("target","portal","block","minecraft:end_portal",
            "x",0,"y",64,"z",0,"expected_dimension","minecraft:the_end")); }
    @Test void requiresNaturalPortalAndDifferentExpectedVanillaDimension() {
        assertThat(V2PortalArguments.parse(args(),"minecraft:overworld").maxTicks()).isEqualTo(200);
        assertThatThrownBy(()->V2PortalArguments.parse(args(),"minecraft:the_end")).isInstanceOf(IllegalArgumentException.class);
        var back=args(); back.put("expected_dimension","minecraft:overworld");
        assertThat(V2PortalArguments.parse(back,"minecraft:the_end").expectedDimension()).isEqualTo("minecraft:overworld");
        for(var change:Map.of("block","minecraft:air","max_ticks",201,"x",30_000_001,"y",2049,"force",true).entrySet()) {
            var invalid=args(); invalid.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->V2PortalArguments.parse(invalid,"minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
