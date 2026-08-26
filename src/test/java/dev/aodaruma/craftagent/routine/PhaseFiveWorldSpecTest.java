package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhaseFiveWorldSpecTest {
    @Test
    void cropUsesOnlyTheClosedAdapterAndDirectFarmlandSupport() {
        var spec = (PhaseFiveWorldSpec.CropSpec) PhaseFiveWorldSpec.parse(request(
                "tend_crop_area",
                Map.of(
                        "crop_adapter", "wheat",
                        "plots", List.of(Map.of(
                                "id", "plot-1",
                                "crop_position", position(2, 65, 2),
                                "support_position", position(2, 64, 2),
                                "expected_support_state", state("minecraft:farmland"))),
                        "goal", Map.of(
                                "minimum_harvested_plots", 1,
                                "replant", true,
                                "collect_drops", true),
                        "wait_policy", "no_wait"),
                true, 1));

        assertThat(spec.adapter().blockId()).isEqualTo("minecraft:wheat");
        assertThat(spec.adapter().plantingItem()).isEqualTo("minecraft:wheat_seeds");
        assertThat(spec.plots()).hasSize(1);
    }

    @Test
    void cropRejectsADeclaredSupportThatIsNotDirectlyBelow() {
        var request = request("tend_crop_area", Map.of(
                "crop_adapter", "wheat",
                "plots", List.of(Map.of(
                        "id", "plot-1",
                        "crop_position", position(2, 65, 2),
                        "support_position", position(3, 64, 2),
                        "expected_support_state", state("minecraft:farmland"))),
                "goal", Map.of(
                        "minimum_harvested_plots", 1,
                        "replant", true,
                        "collect_drops", true),
                "wait_policy", "no_wait"), true, 1);

        assertThatThrownBy(() -> PhaseFiveWorldSpec.parse(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support");
    }

    @Test
    void treeRejectsLogsOutsideTheClosedVanillaSpeciesMap() {
        var request = treeRequest("example:magic_log", "minecraft:oak_sapling");

        assertThatThrownBy(() -> PhaseFiveWorldSpec.parse(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void treeDerivesOnlyTheDeclaredSupportAdjacentSaplingCell() {
        var spec = (PhaseFiveWorldSpec.TreeSpec) PhaseFiveWorldSpec.parse(
                treeRequest("minecraft:oak_log", "minecraft:oak_sapling"));

        assertThat(spec.totalLogs()).isEqualTo(1);
        assertThat(spec.trees().getFirst().saplingPosition())
                .isEqualTo(new BlockTarget(DIMENSION, 4, 65, 4));
    }

    @Test
    void surveyKeepsTheRequestedSampleDenominatorExplicit() {
        var spec = (PhaseFiveWorldSpec.SurveySpec) PhaseFiveWorldSpec.parse(request(
                "survey_area",
                Map.of(
                        "waypoints", List.of(Map.of(
                                "id", "waypoint-1",
                                "target", position(0, 64, 0),
                                "look_at", position(2, 64, 0))),
                        "samples", List.of(
                                Map.of("id", "sample-1", "position", position(2, 63, 0)),
                                Map.of("id", "sample-2", "position", position(3, 63, 0))),
                        "goal", Map.of("minimum_observed_samples", 1),
                        "assessment", "spawn_surface_prediction"),
                false, 1));

        assertThat(spec.samples()).hasSize(2);
        assertThat(spec.minimumObserved()).isEqualTo(1);
        assertThat(spec.assessment()).isEqualTo("spawn_surface_prediction");
    }

    private static final String DIMENSION = "minecraft:overworld";

    private static PhaseFiveRequest treeRequest(String log, String sapling) {
        return request("harvest_tree_area", Map.of(
                "trees", List.of(Map.of(
                        "id", "tree-1",
                        "logs", List.of(Map.of(
                                "position", position(4, 65, 4),
                                "expected_state", state(log))),
                        "support", Map.of(
                                "position", position(4, 64, 4),
                                "expected_state", state("minecraft:dirt")),
                        "sapling", Map.of(
                                "item", sapling,
                                "expected_after_state", state(sapling)),
                        "growth_clearance", List.of(Map.of(
                                "position", position(4, 66, 4),
                                "expected_state", state("minecraft:air"))))),
                "collect_drops", true), true, 1);
    }

    private static PhaseFiveRequest request(
            String kind, Map<String, Object> parameters, boolean allowBreak, int expectedUnits) {
        var minimum = new BlockTarget(DIMENSION, -16, 60, -16);
        var maximum = new BlockTarget(DIMENSION, 16, 80, 16);
        return new PhaseFiveRequest(kind, parameters,
                new PhaseFiveBounds(DIMENSION, minimum, maximum, 32, 120, allowBreak),
                expectedUnits, "cells");
    }

    private static Map<String, Object> position(int x, int y, int z) {
        return Map.of("dimension", DIMENSION, "x", x, "y", y, "z", z);
    }

    private static Map<String, Object> state(String block) {
        return Map.of("block", block, "properties", Map.of());
    }
}
