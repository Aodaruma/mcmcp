package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FixtureV2PrioritiesTest {
    @Test void onlyFixedCasesWithAnExplicitNewRequestNumberAreAccepted() {
        assertThat(FixtureV2PrioritiesConfig.validRequest("1:water")).isTrue();
        for (String kind : java.util.List.of("ladder_down", "ladder_long", "ladder_up", "ladder_long_up", "ladder_gap", "ladder_enclosed", "approach_chest", "approach_roof", "approach_wall", "approach_far", "storage_boundary", "storage_plain", "storage_upgraded", "storage_tab", "storage_food", "storage_drift", "storage_cursor_drift", "waystones"))
            assertThat(FixtureV2PrioritiesConfig.validRequest("2:" + kind)).isTrue();
        for(String value:java.util.List.of("water", "1:function x", "1:water\n2:boat", "1:../water", "123456789:boat"))
            assertThat(FixtureV2PrioritiesConfig.validRequest(value)).isFalse();
    }
}
