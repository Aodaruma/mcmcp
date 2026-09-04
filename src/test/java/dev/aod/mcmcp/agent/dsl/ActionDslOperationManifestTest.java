package dev.aod.mcmcp.agent.dsl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActionDslOperationManifestTest {
    @Test
    void manifestMatchesEverySealedNodeExactlyOnce() {
        assertThat((Set<?>) ActionDslOperationManifest.nodeTypes())
                .isEqualTo(Set.copyOf(Arrays.asList(
                        ActionDsl.Node.class.getPermittedSubclasses())));
        assertThat(ActionDslOperationManifest.operations())
                .extracting(ActionDslOperationManifest.OperationDescriptor::op)
                .containsExactly(
                        "navigate_to_known",
                        "approach_known_surface",
                        "approach_known_placement",
                        "face_known_position",
                        "face_known_block_face",
                        "break_known_face",
                        "break_known_block",
                        "operate_known_cobblestone_generator",
                        "till_known_block",
                        "till_known_batch",
                        "plant_known_wheat",
                        "plant_known_wheat_batch",
                        "harvest_known_wheat",
                        "harvest_known_wheat_batch",
                        "apply_known_block_plan",
                        "clear_known_block_plan",
                        "pillar_up_known",
                        "apply_known_redstone_spec",
                        "open_known_fence_gate",
                        "open_known_passage",
                        "inspect_known_container",
                        "take_known_container_stack",
                        "store_known_container_stack",
                        "craft_known_recipe",
                        "smelt_known_recipe",
                        "operate_known_menu",
                        "brew_known_potion_batch",
                        "collect_visible_item",
                        "collect_visible_item_batch",
                        "cast_known_fishing_rod",
                        "reel_known_fishing_session",
                        "operate_kill_zone",
                        "wait_ticks",
                        "wait_until",
                        "if",
                        "repeat");
    }

    @Test
    void payloadExplainsCapabilitiesReferencesAndLocalGaps() {
        var payload = ActionDslOperationManifest.operationPayload(Set.of("camera"));
        var store = payload.stream()
                .filter(operation -> operation.get("op").equals("store_known_container_stack"))
                .findFirst().orElseThrow();
        assertThat(store.get("required_capabilities"))
                .isEqualTo(java.util.List.of("camera", "inventory_transfer"));
        assertThat(store).containsEntry("locally_granted", false);
        assertThat(store.get("locally_missing_capabilities"))
                .isEqualTo(java.util.List.of("inventory_transfer"));

        var craft = payload.stream()
                .filter(operation -> operation.get("op").equals("craft_known_recipe"))
                .findFirst().orElseThrow();
        assertThat((java.util.List<?>) craft.get("reference_fields")).singleElement()
                .satisfies(reference -> {
                    var fields = (java.util.Map<?, ?>) reference;
                    assertThat(fields.get("kind")).isEqualTo("recipe_ref");
                    assertThat(fields.get("replay_policy")).isEqualTo("refresh_required");
                });
        assertThat(ActionDslOperationManifest.missingCapabilityGuidance())
                .containsEntry("code", "MISSING_CAPABILITY");
        assertThat(ActionDslOperationManifest.referenceDescriptorPayload())
                .extracting(descriptor -> descriptor.get("kind"))
                .containsExactly(
                        "operation_ref", "placement_state_ref", "recipe_ref",
                        "fishing_session_ref", "kill_zone_consent_ref");
    }
}
