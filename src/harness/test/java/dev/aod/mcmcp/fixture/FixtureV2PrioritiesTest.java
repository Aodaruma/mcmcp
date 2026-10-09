package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FixtureV2PrioritiesTest {
    @Test void onlyFixedCasesWithAnExplicitNewRequestNumberAreAccepted() {
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:water")).isTrue();
        for (String kind : java.util.List.of("ladder_down", "ladder_long", "ladder_up", "ladder_long_up", "ladder_gap", "ladder_enclosed"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("2:" + kind)).isTrue();
        for(String value:java.util.List.of("water", "1:function x", "1:water\n2:boat", "1:../water", "123456789:boat"))
            assertThat(FixtureV2PrioritiesConfig.validRequest(value)).isFalse();
    }
}
