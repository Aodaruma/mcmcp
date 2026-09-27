package dev.aod.mcmcp.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 既存の内部routine catalogが参照する入力schema。公開5 Toolの正本はMcpToolCatalog。 */
public final class McpToolSchemas {
    private static final List<String> ROUTINE_KINDS = List.of(
            "stationary_break", "navigate_to", "break_block", "place_block",
            "interact_block", "interact_entity", "use_item_on_block", "apply_block_plan",
            "craft_items", "transfer_items", "tend_crop_area", "harvest_tree_area",
            "sleep_at_bed", "survey_area", "execute_plan");
    private static final List<String> FINITE_PLAN_ACTION_KINDS = List.of(
            "navigate_to", "break_block", "place_block", "interact_block",
            "use_item_on_block", "craft_items", "transfer_items",
            "tend_crop_area", "harvest_tree_area", "sleep_at_bed", "survey_area");

    private McpToolSchemas() {
    }

    public static Map<String, Object> startRoutineInput() {
        Map<String, Object> openObject = schema(
                "type", "object",
                "additionalProperties", true,
                "maxProperties", 64);
        return closedObject(fields(
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new)),
                "parameters", openObject,
                "bounds", openObject,
                "completion_intent", completionIntent(),
                "idempotency_key", uuid()),
                "kind", "parameters", "bounds", "idempotency_key");
    }

    public static Map<String, Object> stationaryBreakStartInput() {
        Map<String, Object> goal = closedObject(fields(
                "item", registryId(),
                "minimum_inventory_count", integer(1, 2_304)),
                "item", "minimum_inventory_count");
        Map<String, Object> parameters = closedObject(fields(
                "target", dimensionBlockPosition(),
                "allowed_blocks", array(registryId(), 1, 16, true),
                "goal", goal,
                "regeneration_timeout_seconds", integer(1, 10)),
                "target", "allowed_blocks", "goal", "regeneration_timeout_seconds");
        Map<String, Object> region = closedObject(fields(
                "min", blockPosition(),
                "max", blockPosition()), "min", "max");
        Map<String, Object> bounds = closedObject(fields(
                "dimension", registryId(),
                "region", region,
                "max_travel_blocks", constant(0),
                "max_duration_seconds", integer(1, 60),
                "allow_break", constant(true)),
                "dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break");
        return closedObject(fields(
                "kind", constant("stationary_break"),
                "parameters", parameters,
                "bounds", bounds,
                "completion_intent", completionIntent(),
                "idempotency_key", uuid()),
                "kind", "parameters", "bounds", "idempotency_key");
    }

    public static Map<String, Object> navigateToStartInput() {
        Map<String, Object> parameters = closedObject(fields(
                "target", dimensionBlockPosition(),
                "horizontal_tolerance_blocks", number(0.25, 2.0)),
                "target", "horizontal_tolerance_blocks");
        return routineStartBranch(
                "navigate_to", parameters, phaseThreeBounds(integer(1, 128), integer(1, 120), false));
    }

    public static Map<String, Object> breakBlockStartInput() {
        Map<String, Object> air = closedLikeRegistryObject(fields(
                "block", constant("minecraft:air"),
                "properties", closedObject(Map.of())), "block");
        Map<String, Object> parameters = closedObject(fields(
                "target", dimensionBlockPosition(),
                "expected_before", expectedBlockState(),
                "expected_after", air),
                "target", "expected_before", "expected_after");
        return routineStartBranch(
                "break_block", parameters,
                phaseThreeBounds(constant(0), integer(1, 30), true));
    }

    public static Map<String, Object> placeBlockStartInput() {
        Map<String, Object> parameters = closedObject(fields(
                "target", dimensionBlockPosition(),
                "expected_before", expectedBlockState(),
                "item", registryId(),
                "expected_after", expectedBlockState()),
                "target", "expected_before", "item", "expected_after");
        return routineStartBranch(
                "place_block", parameters, phaseThreeBounds(constant(0), integer(1, 30), false));
    }

    public static Map<String, Object> interactBlockStartInput() {
        return routineStartBranch(
                "interact_block", blockTransitionParameters(),
                phaseThreeBounds(constant(0), integer(1, 30), false));
    }

    public static Map<String, Object> interactEntityStartInput() {
        Map<String, Object> goal = closedObject(fields(
                "item", constant("minecraft:milk_bucket"),
                "minimum_inventory_count", integer(1, 2_304)),
                "item", "minimum_inventory_count");
        Map<String, Object> parameters = closedObject(fields(
                "entity_ref", string(24, 24, "^[A-Za-z0-9_-]{24}$"),
                "expected_type", constant("minecraft:cow"),
                "hand", constant("main_hand"),
                "held_item", constant("minecraft:bucket"),
                "goal", goal),
                "entity_ref", "expected_type", "hand", "held_item", "goal");
        return routineStartBranch(
                "interact_entity", parameters, phaseThreeBounds(constant(0), integer(1, 30), false));
    }

    /** Closed Phase 3 contract for one exact normal-use state transition. */
    public static Map<String, Object> useItemOnBlockStartInput() {
        Map<String, Object> before = closedLikeRegistryObject(fields(
                "block", enumString(
                        "minecraft:dirt", "minecraft:dirt_path", "minecraft:grass_block"),
                "properties", blockProperties()), "block");
        Map<String, Object> after = closedLikeRegistryObject(fields(
                "block", constant("minecraft:farmland"),
                "properties", closedObject(fields(
                        "moisture", constant("0")), "moisture")),
                "block", "properties");
        Map<String, Object> parameters = closedObject(fields(
                "target", dimensionBlockPosition(),
                "expected_before", before,
                "item", enumString(
                        "minecraft:wooden_hoe", "minecraft:stone_hoe",
                        "minecraft:iron_hoe", "minecraft:golden_hoe",
                        "minecraft:diamond_hoe", "minecraft:netherite_hoe"),
                "expected_after", after),
                "target", "expected_before", "item", "expected_after");
        return routineStartBranch(
                "use_item_on_block", parameters,
                phaseThreeBounds(constant(0), integer(1, 30), false));
    }

    /** Closed Phase 4 contract for one bounded, externally phase-split block plan. */
    public static Map<String, Object> applyBlockPlanStartInput() {
        Map<String, Object> anchor = dimensionBlockPosition();
        Map<String, Object> transform = closedObject(fields(
                "rotation", enumInteger(0, 90, 180, 270),
                "mirror", enumString("none", "x", "z")), "rotation", "mirror");
        Map<String, Object> phase = closedObject(fields(
                "id", string(1, 64, "^[a-z][a-z0-9_.-]{0,63}$"),
                "index", integer(1, 64),
                "total", integer(1, 64)), "id", "index", "total");

        Map<String, Object> common = fields(
                "id", string(1, 64, "^[a-z][a-z0-9_.-]{0,63}$"),
                "offset", relativeBlockPosition(),
                "expected_before", fullBlockState(),
                "expected_after", fullBlockState());
        Map<String, Object> verify = new LinkedHashMap<>(common);
        verify.put("operation", constant("verify_only"));
        Map<String, Object> breakToAir = new LinkedHashMap<>(common);
        breakToAir.put("operation", constant("break_to_air"));
        breakToAir.put("expected_after", airFullBlockState());
        Map<String, Object> place = new LinkedHashMap<>(common);
        place.put("operation", constant("place"));
        place.put("item", registryId());
        Map<String, Object> replace = new LinkedHashMap<>(common);
        replace.put("operation", constant("replace"));
        replace.put("item", registryId());
        String[] commonRequired = {
                "id", "offset", "operation", "expected_before", "expected_after"};
        Map<String, Object> entry = schema("oneOf", List.of(
                closedObject(verify, commonRequired),
                closedObject(breakToAir, commonRequired),
                closedObject(place,
                        "id", "offset", "operation", "expected_before", "expected_after", "item"),
                closedObject(replace,
                        "id", "offset", "operation", "expected_before", "expected_after", "item")));
        Map<String, Object> parameters = closedObject(fields(
                "anchor", anchor,
                "transform", transform,
                "phase", phase,
                "entries", array(entry, 1, 64)),
                "anchor", "transform", "phase", "entries");
        Map<String, Object> region = closedObject(fields(
                "min", blockPosition(),
                "max", blockPosition()), "min", "max");
        Map<String, Object> bounds = closedObject(fields(
                "dimension", registryId(),
                "region", region,
                "max_travel_blocks", constant(0),
                "max_duration_seconds", integer(1, 120),
                "allow_break", schema("type", "boolean")),
                "dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break");
        return routineStartBranch("apply_block_plan", parameters, bounds);
    }

    /** Closed Phase 5 contract for client-known recipe display execution. */
    public static Map<String, Object> craftItemsStartInput() {
        Map<String, Object> goal = closedObject(fields(
                "item", registryId(),
                "stack_policy", constant("default_components_only"),
                "minimum_inventory_count", integer(1, 2_304)),
                "item", "stack_policy", "minimum_inventory_count");
        Map<String, Object> station = closedObject(fields(
                "kind", constant("crafting_table"),
                "target", dimensionBlockPosition(),
                "expected_state", fullBlockState()),
                "kind", "target", "expected_state");
        Map<String, Object> parameters = closedObject(fields(
                "recipe_ref", opaqueReference(),
                "recipe_fingerprint", sha256Fingerprint(),
                "goal", goal,
                "station", station,
                "max_crafts", integer(1, 64)),
                "recipe_ref", "recipe_fingerprint", "goal", "station", "max_crafts");
        return routineStartBranch(
                "craft_items", parameters, phaseFiveBounds(32, 120, false));
    }

    /** Closed Phase 5 contract for one automation-owned vanilla container transfer. */
    public static Map<String, Object> transferItemsStartInput() {
        Map<String, Object> container = closedObject(fields(
                "target", dimensionBlockPosition(),
                "expected_state", fullBlockState()), "target", "expected_state");
        Map<String, Object> stack = closedObject(fields(
                "item", registryId(),
                "stack_policy", enumString(
                        "default_components_only", "item_id_any_components")),
                "item", "stack_policy");
        Map<String, Object> goal = closedObject(fields(
                "minimum_destination_count", integer(0, 2_304)), "minimum_destination_count");
        Map<String, Object> parameters = closedObject(fields(
                "container", container,
                "direction", enumString("player_to_container", "container_to_player"),
                "stack", stack,
                "goal", goal,
                "max_transfer_count", integer(1, 2_304)),
                "container", "direction", "stack", "goal", "max_transfer_count");
        return routineStartBranch(
                "transfer_items", parameters, phaseFiveBounds(32, 120, false));
    }

    /** Closed Phase 5 contract for explicitly declared vanilla crop cells. */
    public static Map<String, Object> tendCropAreaStartInput() {
        Map<String, Object> plot = closedObject(fields(
                "id", localIdentifier(),
                "crop_position", dimensionBlockPosition(),
                "support_position", dimensionBlockPosition(),
                "expected_support_state", fullBlockState()),
                "id", "crop_position", "support_position", "expected_support_state");
        Map<String, Object> goal = closedObject(fields(
                "minimum_harvested_plots", integer(0, 64),
                "replant", constant(true),
                "collect_drops", constant(true)),
                "minimum_harvested_plots", "replant", "collect_drops");
        Map<String, Object> parameters = closedObject(fields(
                "crop_adapter", enumString("wheat", "carrots", "potatoes", "beetroots"),
                "plots", array(plot, 1, 64),
                "goal", goal,
                "wait_policy", enumString("no_wait", "until_minimum")),
                "crop_adapter", "plots", "goal", "wait_policy");
        return routineStartBranch(
                "tend_crop_area", parameters, phaseFiveBounds(128, 7_200, true));
    }

    /** Closed Phase 5 contract for only the current tree cells declared by the caller. */
    public static Map<String, Object> harvestTreeAreaStartInput() {
        Map<String, Object> expectedCell = closedObject(fields(
                "position", dimensionBlockPosition(),
                "expected_state", fullBlockState()), "position", "expected_state");
        Map<String, Object> sapling = closedObject(fields(
                "item", registryId(),
                "expected_after_state", fullBlockState()), "item", "expected_after_state");
        Map<String, Object> tree = closedObject(fields(
                "id", localIdentifier(),
                "logs", array(expectedCell, 1, 64),
                "support", expectedCell,
                "sapling", sapling,
                "growth_clearance", array(expectedCell, 1, 64)),
                "id", "logs", "support", "sapling", "growth_clearance");
        Map<String, Object> parameters = closedObject(fields(
                "trees", array(tree, 1, 8),
                "collect_drops", constant(true)), "trees", "collect_drops");
        return routineStartBranch(
                "harvest_tree_area", parameters, phaseFiveBounds(128, 600, true));
    }

    /** Closed Phase 5 contract for one explicitly identified bed and fixed return policy. */
    public static Map<String, Object> sleepAtBedStartInput() {
        Map<String, Object> bed = closedObject(fields(
                "foot_position", dimensionBlockPosition(),
                "expected_foot_state", fullBlockState(),
                "head_position", dimensionBlockPosition(),
                "expected_head_state", fullBlockState()),
                "foot_position", "expected_foot_state", "head_position", "expected_head_state");
        Map<String, Object> parameters = closedObject(fields(
                "bed", bed,
                "return_policy", constant("start_checkpoint")), "bed", "return_policy");
        return routineStartBranch(
                "sleep_at_bed", parameters, phaseFiveBounds(128, 600, false));
    }

    /** Closed Phase 5 contract for predicted assessment from declared client-visible samples. */
    public static Map<String, Object> surveyAreaStartInput() {
        Map<String, Object> waypoint = closedObject(fields(
                "id", localIdentifier(),
                "target", dimensionBlockPosition(),
                "look_at", dimensionBlockPosition()), "id", "target", "look_at");
        Map<String, Object> sample = closedObject(fields(
                "id", localIdentifier(),
                "position", dimensionBlockPosition()), "id", "position");
        Map<String, Object> goal = closedObject(fields(
                "minimum_observed_samples", integer(1, 256)), "minimum_observed_samples");
        Map<String, Object> parameters = closedObject(fields(
                "waypoints", array(waypoint, 1, 32),
                "samples", array(sample, 1, 256),
                "goal", goal,
                "assessment", enumString("coverage_only", "spawn_surface_prediction")),
                "waypoints", "samples", "goal", "assessment");
        return routineStartBranch(
                "survey_area", parameters, phaseFiveBounds(128, 600, false));
    }

    /** Closed outer envelope for the bounded Phase 6 finite-plan IR. */
    public static Map<String, Object> executePlanStartInput() {
        Map<String, Object> parameters = closedObject(fields(
                "plan_id", localIdentifier(),
                "max_ticks", integer(1, 144_000),
                "steps", array(finitePlanStep(7), 1, 256)),
                "plan_id", "max_ticks", "steps");
        return routineStartBranch("execute_plan", parameters, closedObject(fields()));
    }

    private static Map<String, Object> finitePlanStep(int remainingDepth) {
        Map<String, Object> openPayload = schema(
                "type", "object",
                "additionalProperties", true,
                "maxProperties", 64);
        Map<String, Object> actionArguments = closedObject(fields(
                "parameters", openPayload,
                "bounds", openPayload), "parameters", "bounds");
        Map<String, Object> action = closedObject(fields(
                "id", localIdentifier(),
                "op", constant("action"),
                "kind", enumString(FINITE_PLAN_ACTION_KINDS.toArray(String[]::new)),
                "arguments", actionArguments), "id", "op", "kind", "arguments");
        Map<String, Object> condition = finitePlanCondition();
        Map<String, Object> assertion = closedObject(fields(
                "id", localIdentifier(),
                "op", constant("assert"),
                "condition", condition), "id", "op", "condition");
        Map<String, Object> wait = closedObject(fields(
                "id", localIdentifier(),
                "op", constant("wait_until"),
                "condition", condition,
                "max_ticks", integer(1, 144_000)),
                "id", "op", "condition", "max_ticks");
        var variants = new java.util.ArrayList<Map<String, Object>>(
                List.of(action, assertion, wait));
        if (remainingDepth > 0) {
            variants.add(closedObject(fields(
                    "id", localIdentifier(),
                    "op", constant("repeat_until"),
                    "until", condition,
                    "max_iterations", integer(1, 128),
                    "max_ticks", integer(1, 144_000),
                    "steps", array(finitePlanStep(remainingDepth - 1), 1, 256)),
                    "id", "op", "until", "max_iterations", "max_ticks", "steps"));
        }
        return schema("oneOf", List.copyOf(variants));
    }

    private static Map<String, Object> finitePlanCondition() {
        Map<String, Object> inventory = closedObject(fields(
                "kind", constant("inventory_at_least"),
                "item", registryId(),
                "minimum_count", integer(1, 2_304)),
                "kind", "item", "minimum_count");
        Map<String, Object> block = closedObject(fields(
                "kind", constant("block_matches"),
                "target", dimensionBlockPosition(),
                "expected_state", fullBlockState()),
                "kind", "target", "expected_state");
        return schema("oneOf", List.of(inventory, block));
    }

    private static Map<String, Object> blockTransitionParameters() {
        return closedObject(fields(
                "target", dimensionBlockPosition(),
                "expected_before", expectedBlockState(),
                "expected_after", expectedBlockState()),
                "target", "expected_before", "expected_after");
    }

    private static Map<String, Object> phaseThreeBounds(
            Map<String, Object> maxTravel,
            Map<String, Object> maxDuration,
            boolean allowBreak) {
        Map<String, Object> region = closedObject(fields(
                "min", blockPosition(),
                "max", blockPosition()), "min", "max");
        return closedObject(fields(
                "dimension", registryId(),
                "region", region,
                "max_travel_blocks", maxTravel,
                "max_duration_seconds", maxDuration,
                "allow_break", constant(allowBreak)),
                "dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break");
    }

    private static Map<String, Object> phaseFiveBounds(
            int maximumTravelBlocks,
            int maximumDurationSeconds,
            boolean allowBreak) {
        Map<String, Object> region = closedObject(fields(
                "min", blockPosition(),
                "max", blockPosition()), "min", "max");
        return closedObject(fields(
                "dimension", registryId(),
                "region", region,
                "max_travel_blocks", integer(0, maximumTravelBlocks),
                "max_duration_seconds", integer(1, maximumDurationSeconds),
                "allow_break", constant(allowBreak)),
                "dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break");
    }

    private static Map<String, Object> routineStartBranch(
            String kind,
            Map<String, Object> parameters,
            Map<String, Object> bounds) {
        return closedObject(fields(
                "kind", constant(kind),
                "parameters", parameters,
                "bounds", bounds,
                "completion_intent", completionIntent(),
                "idempotency_key", uuid()),
                "kind", "parameters", "bounds", "idempotency_key");
    }

    private static Map<String, Object> completionIntent() {
        Map<String, Object> result = enumString("finish_goal", "continue_goal");
        result.put("default", "finish_goal");
        return result;
    }

    private static Map<String, Object> expectedBlockState() {
        return closedLikeRegistryObject(fields(
                "block", registryId(),
                "properties", blockProperties()), "block");
    }

    /** Phase 4 plans require an explicit complete property map, including an empty map. */
    private static Map<String, Object> fullBlockState() {
        return closedLikeRegistryObject(fields(
                "block", registryId(),
                "properties", blockProperties()), "block", "properties");
    }

    private static Map<String, Object> airFullBlockState() {
        return closedLikeRegistryObject(fields(
                "block", constant("minecraft:air"),
                "properties", closedObject(Map.of())), "block", "properties");
    }

    private static Map<String, Object> blockProperties() {
        return schema(
                "type", "object",
                "propertyNames", schema("pattern", "^[a-z0-9_]+$"),
                "additionalProperties", string(1, 64, null),
                "maxProperties", 128);
    }

    private static Map<String, Object> blockPosition() {
        return closedObject(fields(
                "x", integer(-30_000_000, 29_999_999),
                "y", integer(-2_048, 2_047),
                "z", integer(-30_000_000, 29_999_999)), "x", "y", "z");
    }

    private static Map<String, Object> relativeBlockPosition() {
        return closedObject(fields(
                "x", integer(-4_096, 4_096),
                "y", integer(-4_096, 4_096),
                "z", integer(-4_096, 4_096)), "x", "y", "z");
    }

    private static Map<String, Object> dimensionBlockPosition() {
        Map<String, Object> result = new LinkedHashMap<>(blockPosition());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) result.get("properties"));
        properties.put("dimension", registryId());
        result.put("properties", properties);
        result.put("required", List.of("dimension", "x", "y", "z"));
        return result;
    }

    private static Map<String, Object> registryId() {
        return string(3, 256, "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    }

    private static Map<String, Object> opaqueReference() {
        return string(24, 24, "^[A-Za-z0-9_-]{24}$");
    }

    private static Map<String, Object> sha256Fingerprint() {
        return string(71, 71, "^sha256:[0-9a-f]{64}$");
    }

    private static Map<String, Object> localIdentifier() {
        return string(1, 64, "^[a-z][a-z0-9_.-]{0,63}$");
    }

    private static Map<String, Object> uuid() {
        return string(36, 36,
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    }

    private static Map<String, Object> enumString(String... values) {
        return schema("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> enumInteger(Integer... values) {
        return schema("type", "integer", "enum", List.of(values));
    }

    private static Map<String, Object> string(int min, int max, String pattern) {
        Map<String, Object> result = schema("type", "string", "minLength", min, "maxLength", max);
        if (pattern != null) {
            result.put("pattern", pattern);
        }
        return result;
    }

    private static Map<String, Object> integer(long min, long max) {
        return schema("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> number(double min, double max) {
        return schema("type", "number", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> constant(Object value) {
        return schema("const", value);
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return array(items, min, max, false);
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max, boolean unique) {
        Map<String, Object> result = schema(
                "type", "array", "items", items, "minItems", min, "maxItems", max);
        if (unique) {
            result.put("uniqueItems", true);
        }
        return result;
    }

    private static Map<String, Object> closedObject(Map<String, Object> properties, String... required) {
        Map<String, Object> result = schema(
                "type", "object", "properties", properties, "additionalProperties", false);
        if (required.length > 0) {
            result.put("required", List.of(required));
        }
        return result;
    }

    private static Map<String, Object> closedLikeRegistryObject(
            Map<String, Object> properties, String... required) {
        return closedObject(properties, required);
    }

    private static Map<String, Object> fields(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("fields require key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static Map<String, Object> schema(Object... entries) {
        return fields(entries);
    }
}
