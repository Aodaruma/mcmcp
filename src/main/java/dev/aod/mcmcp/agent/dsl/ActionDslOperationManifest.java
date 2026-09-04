package dev.aod.mcmcp.agent.dsl;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Machine-readable inventory of every public Action DSL v1 opcode and opaque reference field. */
public final class ActionDslOperationManifest {
    private static final List<OperationDescriptor> OPERATIONS = List.of(
            fixed(ActionDsl.NavigateToKnown.class, "navigate_to_known", caps("movement")),
            fixed(ActionDsl.ApproachKnownSurface.class, "approach_known_surface", caps("movement")),
            fixed(
                    ActionDsl.ApproachKnownPlacement.class,
                    "approach_known_placement",
                    caps("movement"),
                    ref("/entries/*/placement_state_ref", "placement_state_ref", "required")),
            fixed(ActionDsl.FaceKnownPosition.class, "face_known_position", caps("camera")),
            fixed(ActionDsl.FaceKnownBlockFace.class, "face_known_block_face", caps("camera")),
            fixed(ActionDsl.BreakKnownFace.class, "break_known_face", caps("block_break", "camera")),
            fixed(ActionDsl.BreakKnownBlock.class, "break_known_block", caps("block_break", "camera")),
            fixed(
                    ActionDsl.OperateKnownCobblestoneGenerator.class,
                    "operate_known_cobblestone_generator",
                    caps("block_break")),
            fixed(ActionDsl.TillKnownBlock.class, "till_known_block", caps("block_interact", "camera")),
            fixed(ActionDsl.TillKnownBatch.class, "till_known_batch", caps("block_interact", "camera")),
            fixed(ActionDsl.PlantKnownWheat.class, "plant_known_wheat", caps("block_place", "camera")),
            fixed(ActionDsl.PlantKnownWheatBatch.class, "plant_known_wheat_batch", caps("block_place", "camera")),
            fixed(ActionDsl.HarvestKnownWheat.class, "harvest_known_wheat", caps("block_break", "camera")),
            fixed(ActionDsl.HarvestKnownWheatBatch.class, "harvest_known_wheat_batch", caps("block_break", "camera")),
            fixed(
                    ActionDsl.ApplyKnownBlockPlan.class,
                    "apply_known_block_plan",
                    caps("block_place", "camera"),
                    ref("/entries/*/placement_state_ref", "placement_state_ref", "one_of_inline_source")),
            fixed(ActionDsl.ClearKnownBlockPlan.class, "clear_known_block_plan", caps("block_break", "camera")),
            fixed(
                    ActionDsl.PillarUpKnown.class,
                    "pillar_up_known",
                    caps("block_place", "camera", "movement"),
                    ref("/placement_state_ref", "placement_state_ref", "one_of_inline_source")),
            fixed(
                    ActionDsl.ApplyKnownRedstoneSpec.class,
                    "apply_known_redstone_spec",
                    caps("block_interact", "block_place", "camera")),
            fixed(ActionDsl.OpenKnownFenceGate.class, "open_known_fence_gate", caps("block_interact", "camera")),
            fixed(ActionDsl.OpenKnownPassage.class, "open_known_passage", caps("block_interact", "camera")),
            fixed(ActionDsl.InspectKnownContainer.class, "inspect_known_container", caps("camera", "inventory_transfer")),
            fixed(ActionDsl.TakeKnownContainerStack.class, "take_known_container_stack", caps("camera", "inventory_transfer")),
            fixed(ActionDsl.StoreKnownContainerStack.class, "store_known_container_stack", caps("camera", "inventory_transfer")),
            fixed(
                    ActionDsl.CraftKnownRecipe.class,
                    "craft_known_recipe",
                    caps("camera", "inventory_transfer"),
                    ref("/recipe_ref", "recipe_ref", "required_with_fingerprint")),
            fixed(
                    ActionDsl.SmeltKnownRecipe.class,
                    "smelt_known_recipe",
                    caps("camera", "inventory_transfer"),
                    ref("/recipe_ref", "recipe_ref", "required_with_fingerprint")),
            fixed(
                    ActionDsl.OperateKnownMenu.class,
                    "operate_known_menu",
                    caps("inventory_transfer"),
                    ref("/operation_ref", "operation_ref", "required")),
            fixed(ActionDsl.BrewKnownPotionBatch.class, "brew_known_potion_batch", caps("camera", "inventory_transfer")),
            fixed(ActionDsl.CollectVisibleItem.class, "collect_visible_item", caps("movement")),
            fixed(ActionDsl.CollectVisibleItemBatch.class, "collect_visible_item_batch", caps("movement")),
            fixed(ActionDsl.CastKnownFishingRod.class, "cast_known_fishing_rod", caps("camera", "item_use")),
            fixed(
                    ActionDsl.ReelKnownFishingSession.class,
                    "reel_known_fishing_session",
                    caps("item_use"),
                    ref("/fishing_session_ref", "fishing_session_ref", "required")),
            fixed(
                    ActionDsl.OperateKillZone.class,
                    "operate_kill_zone",
                    caps("entity_attack"),
                    ref("/consent_ref", "kill_zone_consent_ref", "required_after_physical_grant")),
            fixed(ActionDsl.WaitTicks.class, "wait_ticks", caps()),
            fixed(ActionDsl.WaitUntil.class, "wait_until", caps()),
            inherited(ActionDsl.If.class, "if"),
            inherited(ActionDsl.Repeat.class, "repeat"));

    private static final Map<Class<? extends ActionDsl.Node>, OperationDescriptor> BY_TYPE;

    static {
        var byType = new LinkedHashMap<Class<? extends ActionDsl.Node>, OperationDescriptor>();
        var names = new LinkedHashSet<String>();
        for (OperationDescriptor descriptor : OPERATIONS) {
            if (byType.put(descriptor.nodeType(), descriptor) != null
                    || !names.add(descriptor.op())) {
                throw new ExceptionInInitializerError("Duplicate Action DSL operation descriptor");
            }
        }
        Set<Class<?>> permitted = Set.copyOf(
                Arrays.asList(ActionDsl.Node.class.getPermittedSubclasses()));
        if (!permitted.equals(byType.keySet())) {
            throw new ExceptionInInitializerError(
                    "Action DSL operation manifest does not match sealed Node types");
        }
        BY_TYPE = Map.copyOf(byType);
    }

    private ActionDslOperationManifest() {
    }

    public static List<OperationDescriptor> operations() {
        return OPERATIONS;
    }

    public static Set<Class<? extends ActionDsl.Node>> nodeTypes() {
        return BY_TYPE.keySet();
    }

    public static OperationDescriptor forNode(ActionDsl.Node node) {
        Objects.requireNonNull(node, "node");
        OperationDescriptor descriptor = BY_TYPE.get(node.getClass());
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown Action DSL node type");
        }
        return descriptor;
    }

    public static List<Map<String, Object>> operationPayload(Set<String> grantedCapabilities) {
        Objects.requireNonNull(grantedCapabilities, "grantedCapabilities");
        return OPERATIONS.stream()
                .map(operation -> operation.toMap(grantedCapabilities))
                .toList();
    }

    public static List<Map<String, Object>> referenceDescriptorPayload() {
        return List.of(
                referenceDescriptor(
                        "operation_ref",
                        "agent_get_state",
                        "/known_menu/operations/*/operation_ref",
                        List.of("operate_known_menu"),
                        "single_use_exact_menu_context_until_valid_through_client_tick"),
                referenceDescriptor(
                        "placement_state_ref",
                        "agent_get_observation",
                        "/records/*/placement_state_ref",
                        List.of("approach_known_placement", "apply_known_block_plan", "pillar_up_known"),
                        "world_session_bounded_identity_ref_subject_to_512_entry_eviction"),
                referenceDescriptor(
                        "recipe_ref",
                        "agent_get_state",
                        "/recipe_query/recipes/*/{recipe_ref,fingerprint}",
                        List.of("craft_known_recipe", "smelt_known_recipe"),
                        "world_session_and_recipe_book_revision_with_exact_fingerprint"),
                referenceDescriptor(
                        "fishing_session_ref",
                        "agent_get_action",
                        "/effects/*/observed_after/fishing_session_ref",
                        List.of("reel_known_fishing_session"),
                        "single_use_owned_bobber_world_session_and_1200_tick_ttl"),
                referenceDescriptor(
                        "kill_zone_consent_ref",
                        "agent_get_state",
                        "/entity_attack_consent/consent_ref",
                        List.of("operate_kill_zone"),
                        "single_consume_exact_policy_and_world_session_with_3600_tick_start_ttl"));
    }

    public static Map<String, Object> missingCapabilityGuidance() {
        var result = new LinkedHashMap<String, Object>();
        result.put("code", "MISSING_CAPABILITY");
        result.put("declare_in", "/program/capabilities");
        result.put(
                "required_capabilities_from",
                "/policy/action_dsl/available_operations/*/required_capabilities");
        result.put("current_grants_from", "/control/granted_capabilities");
        result.put(
                "suggested_next_step",
                "Declare every required capability; if locally_missing_capabilities remains non-empty, obtain a matching local authorization before starting the Action.");
        return Map.copyOf(result);
    }

    private static OperationDescriptor fixed(
            Class<? extends ActionDsl.Node> nodeType,
            String op,
            Set<String> capabilities,
            ReferenceField... references) {
        return new OperationDescriptor(
                nodeType, op, 1, capabilities, "fixed", List.of(references));
    }

    private static OperationDescriptor inherited(
            Class<? extends ActionDsl.Node> nodeType, String op) {
        return new OperationDescriptor(
                nodeType, op, 1, Set.of(), "union_of_descendants", List.of());
    }

    private static Set<String> caps(String... capabilities) {
        return Set.copyOf(List.of(capabilities));
    }

    private static ReferenceField ref(String path, String kind, String requirement) {
        return new ReferenceField(path, kind, requirement);
    }

    private static Map<String, Object> referenceDescriptor(
            String kind,
            String issuedBy,
            String sourcePath,
            List<String> consumerOperations,
            String validity) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", kind);
        result.put("issued_by", issuedBy);
        result.put("source_path", sourcePath);
        result.put("consumer_operations", consumerOperations);
        result.put("validity", validity);
        result.put("replay_policy", "always_refresh_before_clone");
        return Map.copyOf(result);
    }

    public record OperationDescriptor(
            Class<? extends ActionDsl.Node> nodeType,
            String op,
            int version,
            Set<String> requiredCapabilities,
            String capabilityRule,
            List<ReferenceField> referenceFields) {
        public OperationDescriptor {
            Objects.requireNonNull(nodeType, "nodeType");
            Objects.requireNonNull(op, "op");
            requiredCapabilities = Set.copyOf(
                    Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
            Objects.requireNonNull(capabilityRule, "capabilityRule");
            referenceFields = List.copyOf(
                    Objects.requireNonNull(referenceFields, "referenceFields"));
        }

        public Map<String, Object> toMap(Set<String> grantedCapabilities) {
            List<String> required = requiredCapabilities.stream().sorted().toList();
            List<String> missing = required.stream()
                    .filter(capability -> !grantedCapabilities.contains(capability))
                    .toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("op", op);
            result.put("version", version);
            result.put("required_capabilities", required);
            result.put("capability_rule", capabilityRule);
            result.put("locally_granted", missing.isEmpty());
            result.put("locally_missing_capabilities", missing);
            result.put("reference_fields", referenceFields.stream()
                    .map(ReferenceField::toMap).toList());
            return Map.copyOf(result);
        }
    }

    public record ReferenceField(String path, String kind, String requirement) {
        public ReferenceField {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(requirement, "requirement");
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "path", path,
                    "kind", kind,
                    "requirement", requirement,
                    "replay_policy", "refresh_required");
        }
    }
}
