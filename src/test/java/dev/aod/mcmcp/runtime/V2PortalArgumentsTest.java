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
        for(var change:Map.of("block","minecraft:air","max_ticks",1201,"x",30_000_001,"y",2049,"force",true).entrySet()) {
            var invalid=args(); invalid.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->V2PortalArguments.parse(invalid,"minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void netherPortalRequiresItsMatchingDimensionAndLongerDefaultWait() {
        var request=args(); request.put("block","minecraft:nether_portal"); request.put("expected_dimension","minecraft:the_nether");
        var parsed=V2PortalArguments.parse(request,"minecraft:overworld");
        assertThat(parsed.maxTicks()).isEqualTo(600);
        assertThat(parsed.geometry().nether()).isTrue();
        request.put("expected_dimension","minecraft:the_end");
        assertThatThrownBy(()->V2PortalArguments.parse(request,"minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void approachKeepsEyeLevelEvenWhenTheObservedPortalIsBelowTheFeet() {
        var request=V2PortalArguments.parse(args(),"minecraft:overworld");
        var face=MinecraftV2PortalPort.approachFacingTarget(request,java.util.UUID.randomUUID(),0,66.62);
        assertThat(face.aimY()).isEqualTo(66.62);
        assertThat(face.target().y()).isEqualTo(66);
        assertThat(face.aimX()).isEqualTo(0.5);
    }
}
