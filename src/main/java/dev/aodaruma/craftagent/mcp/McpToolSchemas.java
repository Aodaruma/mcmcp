package dev.aodaruma.craftagent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON Schema 2020-12 documents advertised by the phase-gated MCP surface. */
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
    static final List<String> SNAPSHOT_SCOPES = List.of(
            "player", "inventory", "target", "visible_blocks", "visible_entities", "world", "screen");

    private McpToolSchemas() {
    }

    static Map<String, Object> statusInput() {
        return closedObject(fields());
    }

    static Map<String, Object> snapshotInput() {
        Map<String, Object> blockQuery = schema("oneOf", List.of(
                closedObject(fields(
                        "kind", constant("viewport"),
                        "max_distance", number(1, 12),
                        "max_results", integer(1, 256)), "kind"),
                closedObject(fields(
                        "kind", constant("positions"),
                        "positions", array(queryBlockPosition(), 1, 512)), "kind", "positions")));
        Map<String, Object> blockOptions = closedObject(fields(
                "source", enumString("live", "memory", "live_and_memory"),
                "query", blockQuery));
        Map<String, Object> entityOptions = closedObject(fields(
                "source", enumString("live", "memory", "live_and_memory"),
                "max_distance", number(1, 64),
                "max_results", integer(1, 256),
                "types", array(registryOrTagId(), 0, 128),
                "threat_relation", enumString("any", "currently_hostile_to_player")));
        Map<String, Object> options = closedObject(fields(
                "visible_blocks", blockOptions,
                "visible_entities", entityOptions));
        return closedObject(fields(
                "scopes", array(enumString(SNAPSHOT_SCOPES.toArray(String[]::new)), 1, SNAPSHOT_SCOPES.size(), true),
                "options", options,
                "detail", enumString("compact", "full")), "scopes");
    }

    static Map<String, Object> creativeRegionInput() {
        Map<String, Object> region = closedObject(fields(
                "dimension", registryId(),
                "min", blockPosition(),
                "max", blockPosition()), "dimension", "min", "max");
        Map<String, Object> result = closedObject(fields(
                "operation", enumString("start", "status"),
                "region", region,
                "include_entities", schema("type", "boolean"),
                "idempotency_key", uuid(),
                "job_id", uuid()));
        result.put("oneOf", List.of(
                schema(
                        "properties", fields("operation", constant("start")),
                        "required", List.of(
                                "operation", "region", "include_entities", "idempotency_key"),
                        "not", schema("required", List.of("job_id"))),
                schema(
                        "properties", fields("operation", constant("status")),
                        "required", List.of("operation", "job_id"),
                        "not", schema("anyOf", List.of(
                                schema("required", List.of("region")),
                                schema("required", List.of("include_entities")),
                                schema("required", List.of("idempotency_key")))))));
        return result;
    }

    static Map<String, Object> creativeWorldEditInput() {
        Map<String, Object> region = closedObject(fields(
                "dimension", registryId(),
                "min", blockPosition(),
                "max", blockPosition()), "dimension", "min", "max");
        Map<String, Object> entityPosition = closedObject(fields(
                "x", number(-30_000_000, 29_999_999),
                "y", number(-2_048, 2_047),
                "z", number(-30_000_000, 29_999_999)), "x", "y", "z");
        Map<String, Object> entity = closedObject(fields(
                "type", enumString(
                        "minecraft:armor_stand", "minecraft:chicken", "minecraft:cow",
                        "minecraft:iron_golem", "minecraft:pig", "minecraft:sheep",
                        "minecraft:villager"),
                "position", entityPosition,
                "yaw", number(-180, 180),
                "pitch", number(-90, 90)), "type", "position", "yaw", "pitch");

        Map<String, Object> setBlock = closedObject(fields(
                "operation", constant("set_block"),
                "position", dimensionBlockPosition(),
                "state", fullBlockState(),
                "idempotency_key", uuid()),
                "operation", "position", "state", "idempotency_key");
        Map<String, Object> fill = closedObject(fields(
                "operation", constant("fill"),
                "region", region,
                "state", fullBlockState(),
                "idempotency_key", uuid()),
                "operation", "region", "state", "idempotency_key");
        Map<String, Object> summon = closedObject(fields(
                "operation", constant("summon_entities"),
                "dimension", registryId(),
                "entities", array(entity, 1, 16),
                "idempotency_key", uuid()),
                "operation", "dimension", "entities", "idempotency_key");
        Map<String, Object> undo = closedObject(fields(
                "operation", constant("undo"),
                "expected_transaction_id", uuid(),
                "idempotency_key", uuid()),
                "operation", "expected_transaction_id", "idempotency_key");
        Map<String, Object> redo = closedObject(fields(
                "operation", constant("redo"),
                "expected_transaction_id", uuid(),
                "idempotency_key", uuid()),
                "operation", "expected_transaction_id", "idempotency_key");
        Map<String, Object> status = closedObject(fields(
                "operation", constant("status"),
                "job_id", uuid()), "operation", "job_id");
        Map<String, Object> history = closedObject(fields(
                "operation", constant("history")), "operation");

        Map<String, Object> result = closedObject(fields(
                "operation", enumString(
                        "set_block", "fill", "summon_entities", "undo", "redo", "status", "history"),
                "position", dimensionBlockPosition(),
                "region", region,
                "state", fullBlockState(),
                "dimension", registryId(),
                "entities", array(entity, 1, 16),
                "expected_transaction_id", uuid(),
                "idempotency_key", uuid(),
                "job_id", uuid()), "operation");
        result.put("oneOf", List.of(setBlock, fill, summon, undo, redo, status, history));
        return result;
    }

    static Map<String, Object> compareInput() {
        Map<String, Object> anchor = closedObject(fields(
                "dimension", registryId(),
                "x", integer(-30_000_000, 29_999_999),
                "y", integer(-2_048, 2_047),
                "z", integer(-30_000_000, 29_999_999)), "dimension", "x", "y", "z");
        Map<String, Object> transform = closedObject(fields(
                "rotation", enumInteger(0, 90, 180, 270),
                "mirror", enumString("none", "x", "z")));
        Map<String, Object> expectedBlock = closedObject(fields(
                "id", string(1, 96, "^[A-Za-z0-9][A-Za-z0-9_.:-]*$"),
                "offset", relativeBlockPosition(),
                "state", expectedBlockState(),
                "required", schema("type", "boolean", "default", true)), "id", "offset", "state");
        return closedObject(fields(
                "anchor", anchor,
                "transform", transform,
                "expected", array(expectedBlock, 1, 512),
                "include_matches", schema("type", "boolean", "default", false)),
                "anchor", "expected");
    }

    static Map<String, Object> getRecipesInput() {
        Map<String, Object> query = schema("oneOf", List.of(
                closedObject(fields(
                        "kind", constant("result_item"),
                        "item", registryId()), "kind", "item"),
                closedObject(fields(
                        "kind", constant("result_tag"),
                        "tag", registryId()), "kind", "tag")));
        return closedObject(fields(
                "query", query,
                "max_results", integer(1, 64)), "query", "max_results");
    }

    static Map<String, Object> emergencyStopInput() {
        return closedObject(fields("reason", string(1, 160, null)), "reason");
    }

    static Map<String, Object> listRoutinesInput() {
        return closedObject(fields(
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new))));
    }

    static Map<String, Object> getRoutineInput() {
        return closedObject(fields(
                "routine_id", uuid(),
                "after_event_seq", integer(0, Long.MAX_VALUE),
                "max_events", integer(1, 128)), "routine_id");
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

    /** Full server-side validation contract; deliberately not advertised in tools/list. */
    static Map<String, Object> startRoutineValidationInput() {
        Map<String, Object> result = closedObject(fields(
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new)),
                "parameters", schema(),
                "bounds", schema(),
                "completion_intent", completionIntent(),
                "idempotency_key", uuid()),
                "kind", "parameters", "bounds", "idempotency_key");
        result.put("oneOf", List.of(
                stationaryBreakStartInput(),
                navigateToStartInput(),
                breakBlockStartInput(),
                placeBlockStartInput(),
                interactBlockStartInput(),
                interactEntityStartInput(),
                useItemOnBlockStartInput(),
                applyBlockPlanStartInput(),
                craftItemsStartInput(),
                transferItemsStartInput(),
                tendCropAreaStartInput(),
                harvestTreeAreaStartInput(),
                sleepAtBedStartInput(),
                surveyAreaStartInput(),
                executePlanStartInput()));
        return result;
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
                "stack_policy", constant("default_components_only")), "item", "stack_policy");
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

    static Map<String, Object> cancelRoutineInput() {
        return closedObject(fields(
                "routine_id", uuid(),
                "reason", string(1, 160, null)), "routine_id", "reason");
    }

    static Map<String, Object> statusOutput() {
        Map<String, Object> versions = closedObject(fields(
                "mcp", string(1, 64, null),
                "mod", string(1, 64, null),
                "minecraft", string(1, 64, null),
                "neoforge", string(1, 64, null),
                "adapter", nullableString(128)), "mcp", "mod", "minecraft", "neoforge", "adapter");
        Map<String, Object> world = closedObject(fields(
                "connected", schema("type", "boolean"),
                "dimension", nullableRegistryId(),
                "world_session_id", nullableString(96)), "connected", "dimension", "world_session_id");
        Map<String, Object> lock = closedObject(fields(
                "locked", schema("type", "boolean"),
                "unlock_expires_at_client_tick", nullableInteger(0, Long.MAX_VALUE),
                "reason", nullableString(160)), "locked", "unlock_expires_at_client_tick", "reason");
        Map<String, Object> voiceChat = closedObject(fields(
                "status", enumString("unavailable", "ready", "muted", "error"),
                "adapter_version", nullableString(64),
                "connected", nullable(schema("type", "boolean")),
                "muted", nullable(schema("type", "boolean")),
                "failure", nullableString(96),
                "recovery_required", schema("type", "boolean")),
                "status", "adapter_version", "connected", "muted", "failure", "recovery_required");
        Map<String, Object> policies = closedObject(fields(
                "survival", string(1, 64, "^[a-z][a-z0-9_]*$"),
                "completion", string(1, 64, "^[a-z][a-z0-9_]*$")), "survival", "completion");
        Map<String, Object> routine = nullable(closedObject(fields(
                "routine_id", string(1, 96, null),
                "kind", string(1, 96, "^[a-z][a-z0-9_]*$"),
                "state", string(1, 64, "^[A-Z][A-Z_]*$")), "routine_id", "kind", "state"));
        Map<String, Object> memory = closedObject(fields(
                "count", integer(0, Integer.MAX_VALUE),
                "retention_policy", string(1, 96, null),
                "evicted_count", integer(0, Long.MAX_VALUE),
                "oldest_retained_tick", nullableInteger(0, Long.MAX_VALUE),
                "warning", nullableString(256)),
                "count", "retention_policy", "evicted_count", "oldest_retained_tick", "warning");
        Map<String, Object> data = closedObject(fields(
                "versions", versions,
                "world", world,
                "lock", lock,
                "capability_profile", array(string(1, 96, "^[a-z][a-z0-9_]*$"), 0, 256, true),
                "voice_chat", voiceChat,
                "policies", policies,
                "active_routine", routine,
                "memory", memory),
                "versions", "world", "lock", "capability_profile", "voice_chat", "policies",
                "active_routine", "memory");
        return envelope("get_status", data);
    }

    static Map<String, Object> snapshotOutput() {
        Map<String, Object> effect = closedObject(fields(
                "effect", registryId(),
                "amplifier", integer(0, 255),
                "duration_ticks", integer(-1, Integer.MAX_VALUE),
                "ambient", schema("type", "boolean"),
                "visible", schema("type", "boolean")),
                "effect", "amplifier", "duration_ticks", "ambient", "visible");
        Map<String, Object> player = closedObject(fields(
                "position", vector3(),
                "rotation", rotation(),
                "velocity", vector3(),
                "health", number(0, 2_048),
                "max_health", number(0, 2_048),
                "absorption", number(0, 2_048),
                "hunger", integer(0, 20),
                "saturation", number(0, 20),
                "on_ground", schema("type", "boolean"),
                "alive", schema("type", "boolean"),
                "using_item", schema("type", "boolean"),
                "block_interaction_range", number(0, 64),
                "entity_interaction_range", number(0, 64),
                "selected_slot", integer(0, 8),
                "effects", array(effect, 0, 128)),
                "position", "rotation", "velocity", "health", "max_health", "absorption", "hunger",
                "saturation", "on_ground", "alive", "using_item", "block_interaction_range",
                "entity_interaction_range", "selected_slot", "effects");
        Map<String, Object> enchantment = closedObject(fields(
                "enchantment", registryId(), "level", integer(1, 255)), "enchantment", "level");
        Map<String, Object> slot = closedObject(fields(
                "slot", integer(0, 255),
                "slot_role", enumString(
                        "hotbar", "main", "equipment_mainhand", "equipment_offhand", "equipment_feet",
                        "equipment_legs", "equipment_chest", "equipment_head", "equipment_body",
                        "equipment_saddle"),
                "selected", schema("type", "boolean"),
                "empty", schema("type", "boolean"),
                "item", registryId(),
                "count", integer(0, Integer.MAX_VALUE),
                "max_stack_size", integer(1, Integer.MAX_VALUE),
                "damage", integer(0, Integer.MAX_VALUE),
                "max_damage", integer(0, Integer.MAX_VALUE),
                "durability_remaining", integer(0, Integer.MAX_VALUE),
                "places_block", registryId(),
                "tags", array(registryId(), 0, 256, true),
                "enchantments", array(enchantment, 0, 128)),
                "slot", "slot_role", "selected", "empty");
        Map<String, Object> inventory = closedObject(fields(
                "selected_slot", integer(0, 8),
                "size", integer(0, 256),
                "slot_encoding", enumString("non_empty_only", "all_slots"),
                "slots", array(slot, 0, 256)), "selected_slot", "size", "slots");
        Map<String, Object> target = closedObject(fields(
                "kind", enumString("miss", "block", "entity"),
                "face", enumString("down", "up", "north", "south", "west", "east"),
                "hit_position", vector3(),
                "distance", number(0, 64),
                "within_reach", schema("type", "boolean"),
                "inside", schema("type", "boolean"),
                "observation", schema("oneOf", List.of(observedBlock(), entityObservation(false)))), "kind");
        Map<String, Object> blockCoverage = closedObject(fields(
                "requested_or_considered", integer(0, 15_625),
                "current", integer(0, 512),
                "last_known", integer(0, 512),
                "not_currently_observable", integer(0, 512),
                "unknown", integer(0, 512)),
                "requested_or_considered", "current", "last_known", "not_currently_observable", "unknown");
        Map<String, Object> visibleBlocks = closedObject(fields(
                "source", enumString("live", "memory", "live_and_memory"),
                "query_kind", enumString("positions", "viewport"),
                "results", array(schema("oneOf", List.of(
                        blockObservationResult(), compactBlockObservationResult())), 0, 512),
                "coverage", blockCoverage,
                "truncated", schema("type", "boolean"),
                "max_distance", number(1, 12),
                "max_results", integer(1, 256)),
                "source", "query_kind", "results", "coverage", "truncated");
        Map<String, Object> entityCoverage = countObject("current", "last_known");
        Map<String, Object> visibleEntities = closedObject(fields(
                "source", enumString("live", "memory", "live_and_memory"),
                "max_distance", number(1, 64),
                "max_results", integer(1, 256),
                "relative_to", enumString("camera", "player_eye"),
                "results", array(schema("oneOf", List.of(
                        entityObservation(true), compactEntityObservation())), 0, 256),
                "coverage", entityCoverage,
                "threat_relation_basis", enumString("client_synced_direct_target_only", "not_filtered"),
                "truncated", schema("type", "boolean")),
                "source", "max_distance", "max_results", "relative_to", "results", "coverage",
                "threat_relation_basis", "truncated");
        Map<String, Object> camera = schema("oneOf", List.of(
                closedObject(fields("available", constant(false)), "available"),
                closedObject(fields(
                        "position", vector3(),
                        "yaw", schema("type", "number"),
                        "pitch", schema("type", "number"),
                        "fov", number(0, 180),
                        "detached", schema("type", "boolean")),
                        "position", "yaw", "pitch", "fov", "detached")));
        Map<String, Object> world = closedObject(fields(
                "dimension", registryId(),
                "world_session_id", string(1, 96, null),
                "overworld_clock_time", integer(0, Long.MAX_VALUE),
                "default_clock_time", integer(0, Long.MAX_VALUE),
                "raining", schema("type", "boolean"),
                "thundering", schema("type", "boolean"),
                "rain_level", number(0, 1),
                "thunder_level", number(0, 1),
                "biome", registryId(),
                "block_light", integer(0, 15),
                "sky_light", integer(0, 15),
                "fluid", nullableRegistryId(),
                "camera", camera),
                "dimension", "world_session_id", "overworld_clock_time", "default_clock_time", "raining",
                "thundering", "rain_level", "thunder_level", "biome", "block_light", "sky_light", "fluid",
                "camera");
        Map<String, Object> container = closedObject(fields(
                "container_id", integer(0, Integer.MAX_VALUE),
                "revision", integer(0, Integer.MAX_VALUE),
                "slot_count", integer(0, 1024),
                "menu_type", registryId()), "container_id", "revision", "slot_count");
        Map<String, Object> screen = closedObject(fields(
                "present", schema("type", "boolean"),
                "screen_type", string(1, 512, null),
                "screen_kind", enumString("none", "chat", "container", "generic"),
                "automation_owned", schema("type", "boolean"),
                "chat_content_included", schema("type", "boolean"),
                "pause_screen", schema("type", "boolean"),
                "in_game_ui", schema("type", "boolean"),
                "container", container),
                "present", "screen_type", "screen_kind", "automation_owned", "chat_content_included");
        Map<String, Object> data = closedObject(fields(
                "world_session_id", string(1, 96, null),
                "client_tick", integer(0, Long.MAX_VALUE),
                "observation_revision", integer(0, Long.MAX_VALUE),
                "requested_scopes", array(enumString(SNAPSHOT_SCOPES.toArray(String[]::new)),
                        1, SNAPSHOT_SCOPES.size(), true),
                "detail", enumString("compact", "full"),
                "player", player,
                "inventory", inventory,
                "target", target,
                "visible_blocks", visibleBlocks,
                "visible_entities", visibleEntities,
                "world", world,
                "screen", screen),
                "world_session_id", "client_tick", "observation_revision", "requested_scopes");
        return envelope("get_snapshot", data);
    }

    static Map<String, Object> creativeRegionOutput() {
        Map<String, Object> region = closedObject(fields(
                "min", blockPosition(),
                "max", blockPosition()), "min", "max");
        Map<String, Object> basis = closedObject(fields(
                "world_session_id", uuid(),
                "started_client_tick", integer(0, Long.MAX_VALUE),
                "dimension", registryId(),
                "source", constant("integrated_server_chunk_sequence"),
                "game_mode", constant("creative"),
                "consistency", constant("server_thread_chunk_sequence"),
                "region", region,
                "volume", integer(1, 4_194_304),
                "started_server_tick", integer(0, Integer.MAX_VALUE),
                "completed_server_tick", integer(0, Integer.MAX_VALUE)),
                "world_session_id", "started_client_tick", "dimension", "source", "game_mode",
                "consistency", "region", "volume", "started_server_tick", "completed_server_tick");
        Map<String, Object> itemCount = closedObject(fields(
                "item", registryId(),
                "count", integer(1, Integer.MAX_VALUE)), "item", "count");
        Map<String, Object> blockCount = closedObject(fields(
                "block", registryId(),
                "count", integer(1, 4_194_304)), "block", "count");
        Map<String, Object> blockCounts = closedObject(fields(
                "items", array(blockCount, 0, 512),
                "unique_count", integer(0, 65_536),
                "truncated", schema("type", "boolean")),
                "items", "unique_count", "truncated");
        Map<String, Object> materials = closedObject(fields(
                "complete", schema("type", "boolean"),
                "items", array(itemCount, 0, 512),
                "unique_count", integer(0, 65_536),
                "truncated", schema("type", "boolean")),
                "complete", "items", "unique_count", "truncated");
        Map<String, Object> entitySummary = closedObject(fields(
                "included", schema("type", "boolean"),
                "count", integer(0, 128),
                "truncated", schema("type", "boolean"),
                "complete", schema("type", "boolean")),
                "included", "count", "truncated", "complete");
        Map<String, Object> summary = closedObject(fields(
                "basis", basis,
                "blueprint_hash", sha256Fingerprint(),
                "palette_size", integer(1, 65_536),
                "manual_setup_count", integer(0, Integer.MAX_VALUE),
                "block_counts", blockCounts,
                "materials", materials,
                "entities", entitySummary),
                "basis", "blueprint_hash", "palette_size", "manual_setup_count",
                "block_counts", "materials", "entities");
        Map<String, Object> artifact = closedObject(fields(
                "relative_path", string(80, 160,
                        "^craftagent/exports/creative-blueprints/[0-9a-f-]{36}\\.json\\.gz$"),
                "format", constant("json+gzip"),
                "sha256", sha256Fingerprint(),
                "compressed_bytes", integer(1, Long.MAX_VALUE),
                "uncompressed_bytes", integer(1, 67_108_864)),
                "relative_path", "format", "sha256", "compressed_bytes", "uncompressed_bytes");
        Map<String, Object> progress = closedObject(fields(
                "processed_cells", integer(0, 4_194_304),
                "total_cells", integer(1, 4_194_304),
                "loaded_chunks", integer(0, 64),
                "processed_chunks", integer(0, 64),
                "total_chunks", integer(1, 64),
                "started_server_tick", integer(0, Integer.MAX_VALUE)),
                "processed_cells", "total_cells", "loaded_chunks", "processed_chunks", "total_chunks");
        Map<String, Object> captureError = closedObject(fields(
                "code", enumString(
                        "timeout", "locked", "cancelled", "server_stopping", "world_session_changed",
                        "unsafe_state", "artifact_too_large", "chunk_not_generated",
                        "artifact_write_failed", "capture_failed"),
                "message", string(1, 256, null)), "code", "message");
        Map<String, Object> data = closedObject(fields(
                "job_id", uuid(),
                "state", enumString(
                        "queued", "loading_chunks", "capturing", "finalizing",
                        "succeeded", "failed", "cancelled"),
                "idempotent_replay", schema("type", "boolean"),
                "progress", progress,
                "artifact", artifact,
                "summary", summary,
                "error", captureError),
                "job_id", "state", "idempotent_replay", "progress");
        return envelope("capture_creative_region", data);
    }

    static Map<String, Object> creativeWorldEditOutput() {
        Map<String, Object> history = closedObject(fields(
                "can_undo", schema("type", "boolean"),
                "can_redo", schema("type", "boolean"),
                "undo_depth", integer(0, 32),
                "redo_depth", integer(0, 32),
                "undo_transaction_id", nullable(uuid()),
                "redo_transaction_id", nullable(uuid()),
                "history_ttl_seconds", constant(1_800L)),
                "can_undo", "can_redo", "undo_depth", "redo_depth",
                "undo_transaction_id", "redo_transaction_id", "history_ttl_seconds");
        Map<String, Object> editError = nullable(closedObject(fields(
                "code", string(1, 64, "^[a-z][a-z0-9_]{0,63}$"),
                "message", string(1, 160, null)), "code", "message"));
        Map<String, Object> job = closedObject(fields(
                "response", constant("job"),
                "job_id", uuid(),
                "operation", enumString("set_block", "fill", "summon_entities", "undo", "redo"),
                "state", enumString("queued", "succeeded", "failed", "cancelled"),
                "idempotent_replay", schema("type", "boolean"),
                "started_client_tick", integer(0, Long.MAX_VALUE),
                "requested_changes", integer(1, 4_096),
                "applied_changes", integer(0, 4_096),
                "transaction_id", nullable(uuid()),
                "history", history,
                "error", editError),
                "response", "job_id", "operation", "state", "idempotent_replay",
                "started_client_tick", "requested_changes", "applied_changes",
                "transaction_id", "history", "error");
        Map<String, Object> historyResponse = closedObject(fields(
                "response", constant("history"),
                "history", history), "response", "history");
        return envelope("edit_creative_world", schema("oneOf", List.of(job, historyResponse)));
    }

    static Map<String, Object> compareOutput() {
        Map<String, Object> basis = closedObject(fields(
                "world_session_id", string(1, 96, null),
                "dimension", registryId(),
                "client_tick", integer(0, Long.MAX_VALUE),
                "observation_revision", integer(0, Long.MAX_VALUE)),
                "world_session_id", "dimension", "client_tick", "observation_revision");
        Map<String, Object> coverage = countObject("requested", "current", "last_known", "unknown");
        Map<String, Object> summary = countObject(
                "match_current", "mismatch_current", "match_last_known", "mismatch_last_known", "unknown");
        Map<String, Object> difference = closedObject(fields(
                "id", string(1, 96, null),
                "required", schema("type", "boolean"),
                "result", enumString(
                        "match_current", "mismatch_current", "match_last_known", "mismatch_last_known", "unknown"),
                "world_position", dimensionBlockPosition(),
                "expected", expectedBlockState(),
                "actual", observedBlock(),
                "reason", blockObservationReason()),
                "id", "required", "result", "world_position", "expected");
        Map<String, Object> requiredVerification = closedObject(fields(
                "total", integer(0, 512),
                "match_current", integer(0, 512),
                "mismatch_or_stale", integer(0, 512),
                "unknown", integer(0, 512),
                "complete", schema("type", "boolean")),
                "total", "match_current", "mismatch_or_stale", "unknown", "complete");
        Map<String, Object> data = closedObject(fields(
                "plan_hash", string(8, 96, "^sha256:[0-9a-f]{64}$"),
                "basis", basis,
                "coverage", coverage,
                "summary", summary,
                "required_verification", requiredVerification,
                "differences", array(difference, 0, 512)),
                "plan_hash", "basis", "coverage", "summary", "required_verification", "differences");
        return envelope("compare_block_plan", data);
    }

    static Map<String, Object> getRecipesOutput() {
        Map<String, Object> basis = closedObject(fields(
                "world_session_id", string(1, 96, null),
                "client_tick", integer(0, Long.MAX_VALUE),
                "recipe_book_revision", integer(0, Long.MAX_VALUE)),
                "world_session_id", "client_tick", "recipe_book_revision");
        Map<String, Object> coverage = closedObject(fields(
                "source", constant("client_known_recipe_displays"),
                "complete", constant(false),
                "known", integer(0, Integer.MAX_VALUE),
                "matched", integer(0, Integer.MAX_VALUE),
                "returned", integer(0, 64),
                "truncated", schema("type", "boolean")),
                "source", "complete", "known", "matched", "returned", "truncated");
        Map<String, Object> resultAlternative = closedObject(fields(
                "item", registryId(),
                "count", integer(1, Integer.MAX_VALUE),
                "stack_fingerprint", sha256Fingerprint()),
                "item", "count", "stack_fingerprint");
        Map<String, Object> recipeResult = closedObject(fields(
                "deterministic", schema("type", "boolean"),
                "alternatives", array(resultAlternative, 0, 16)),
                "deterministic", "alternatives");
        Map<String, Object> ingredientAlternative = closedObject(fields(
                "item", registryId()), "item");
        Map<String, Object> ingredient = closedObject(fields(
                "index", integer(0, 8),
                "count_per_craft", integer(1, Integer.MAX_VALUE),
                "alternatives", array(ingredientAlternative, 1, 128)),
                "index", "count_per_craft", "alternatives");
        Map<String, Object> shape = nullable(closedObject(fields(
                "width", integer(1, 3),
                "height", integer(1, 3)), "width", "height"));
        Map<String, Object> recipe = closedObject(fields(
                "recipe_ref", opaqueReference(),
                "fingerprint", sha256Fingerprint(),
                "display_kind", enumString("shaped", "shapeless", "other"),
                "required_screen", enumString("inventory_2x2", "crafting_table", "unsupported"),
                "supported", schema("type", "boolean"),
                "unsupported_reason", nullableString(160),
                "result", recipeResult,
                "ingredients", array(ingredient, 0, 9),
                "shape", shape),
                "recipe_ref", "fingerprint", "display_kind", "required_screen", "supported",
                "unsupported_reason", "result", "ingredients", "shape");
        Map<String, Object> data = closedObject(fields(
                "basis", basis,
                "coverage", coverage,
                "recipes", array(recipe, 0, 64)), "basis", "coverage", "recipes");
        return envelope("get_recipes", data);
    }

    static Map<String, Object> emergencyStopOutput() {
        Map<String, Object> data = closedObject(fields(
                "stop_requested", constant(true),
                "locked", constant(true),
                "released_inputs", schema("type", "boolean"),
                "discarded_pending_starts", integer(0, Integer.MAX_VALUE)),
                "stop_requested", "locked", "released_inputs", "discarded_pending_starts");
        return envelope("emergency_stop", data);
    }

    static Map<String, Object> listRoutinesOutput() {
        Map<String, Object> catalogEntry = closedObject(fields(
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new)),
                "phase", enumInteger(2, 3, 4, 5, 6),
                "experimental", constant(false),
                "capabilities", array(string(1, 160, null), 1, 8, true),
                "input_schema", schema(
                        "type", "object",
                        "additionalProperties", true,
                        "maxProperties", 64),
                "postconditions", array(string(1, 160, null), 1, 16, true)),
                "kind", "phase", "experimental", "capabilities");
        Map<String, Object> data = closedObject(fields(
                "catalog_version", constant("phase-6-compact-v1"),
                "routines", array(catalogEntry, 1, ROUTINE_KINDS.size(), true)),
                "catalog_version", "routines");
        return envelope("list_routines", data);
    }

    static Map<String, Object> getRoutineOutput() {
        return envelope("get_routine", routineData());
    }

    static Map<String, Object> startRoutineOutput() {
        Map<String, Object> resourceCount = closedObject(fields(
                "item", registryId(),
                "maximum_required_count", integer(1, 64)),
                "item", "maximum_required_count");
        Map<String, Object> resourceEstimate = closedObject(fields(
                "items", array(resourceCount, 0, 64, true),
                "break_operations", integer(0, 64),
                "place_operations", integer(0, 64)),
                "items", "break_operations", "place_operations");
        Map<String, Object> data = closedObject(fields(
                "routine_id", uuid(),
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new)),
                "state", routineState(),
                "idempotent_replay", schema("type", "boolean"),
                "resource_estimate", nullable(resourceEstimate)),
                "routine_id", "kind", "state", "idempotent_replay", "resource_estimate");
        return envelope("start_routine", data);
    }

    static Map<String, Object> cancelRoutineOutput() {
        Map<String, Object> data = closedObject(fields(
                "routine_id", uuid(),
                "state", routineState(),
                "released_inputs", schema("type", "boolean"),
                "already_terminal", schema("type", "boolean")),
                "routine_id", "state", "released_inputs", "already_terminal");
        return envelope("cancel_routine", data);
    }

    private static Map<String, Object> routineData() {
        Map<String, Object> goal = closedObject(fields(
                "verified", schema("type", "boolean")), "verified");
        Map<String, Object> progress = closedObject(fields(
                "completed", integer(0, 12_000),
                "total", integer(0, 12_000),
                "unit", enumString(
                        "items", "blocks", "cells", "interactions", "destinations", "steps")),
                "completed", "total", "unit");
        Map<String, Object> stationaryBreakStep = closedObject(fields(
                "kind", constant("break_block"),
                "target", dimensionBlockPosition()), "kind", "target");
        Map<String, Object> navigateStep = closedObject(fields(
                "kind", constant("navigate_to"),
                "target", dimensionBlockPosition(),
                "horizontal_tolerance_blocks", number(0.25, 2.0)),
                "kind", "target", "horizontal_tolerance_blocks");
        Map<String, Object> breakStep = closedObject(fields(
                "kind", constant("break_block"),
                "target", dimensionBlockPosition(),
                "expected_after", expectedBlockState()),
                "kind", "target", "expected_after");
        Map<String, Object> placeStep = closedObject(fields(
                "kind", constant("place_block"),
                "target", dimensionBlockPosition(),
                "expected_after", expectedBlockState()),
                "kind", "target", "expected_after");
        Map<String, Object> interactBlockStep = closedObject(fields(
                "kind", constant("interact_block"),
                "target", dimensionBlockPosition(),
                "expected_after", expectedBlockState()),
                "kind", "target", "expected_after");
        Map<String, Object> useItemOnBlockStep = closedObject(fields(
                "kind", constant("use_item_on_block"),
                "target", dimensionBlockPosition(),
                "expected_after", expectedBlockState()),
                "kind", "target", "expected_after");
        Map<String, Object> interactEntityStep = closedObject(fields(
                "kind", constant("interact_entity"),
                "entity_ref", string(24, 24, "^[A-Za-z0-9_-]{24}$"),
                "expected_type", registryId()),
                "kind", "entity_ref", "expected_type");
        Map<String, Object> planCellStep = closedObject(fields(
                "kind", constant("plan_cell"),
                "step_index", integer(0, 63),
                "phase_id", string(1, 64, "^[a-z][a-z0-9_.-]{0,63}$"),
                "cell_id", string(1, 64, "^[a-z][a-z0-9_.-]{0,63}$"),
                "operation", enumString("verify_only", "break_to_air", "place", "replace"),
                "target", dimensionBlockPosition(),
                "expected_after", fullBlockState(),
                "child_stage", enumString("break", "place"),
                "item", registryId()),
                "kind", "step_index", "phase_id", "cell_id", "operation", "target", "expected_after");
        Map<String, Object> phaseFiveStep = closedObject(fields(
                "kind", enumString(
                        "craft_items", "transfer_items", "tend_crop_area",
                        "harvest_tree_area", "sleep_at_bed", "survey_area")), "kind");
        Map<String, Object> executePlanStep = closedObject(fields(
                "kind", constant("execute_plan"),
                "plan_id", localIdentifier()), "kind", "plan_id");
        Map<String, Object> planStep = closedObject(fields(
                "kind", constant("plan_step"),
                "plan_id", localIdentifier(),
                "step_id", localIdentifier(),
                "op", enumString("action", "assert", "wait_until", "repeat_until"),
                "routine_kind", enumString(FINITE_PLAN_ACTION_KINDS.toArray(String[]::new)),
                "max_iterations", integer(1, 128),
                "completed_iterations", integer(0, 128)),
                "kind", "plan_id", "step_id", "op");
        Map<String, Object> currentStep = nullable(schema("oneOf", List.of(
                stationaryBreakStep, navigateStep, breakStep, placeStep, interactBlockStep,
                useItemOnBlockStep, interactEntityStep, planCellStep, phaseFiveStep,
                executePlanStep, planStep)));
        Map<String, Object> checkpoint = closedObject(fields(
                "seq", integer(0, Long.MAX_VALUE),
                "observation_revision", integer(0, Long.MAX_VALUE)), "seq", "observation_revision");
        Map<String, Object> verification = closedObject(fields(
                "confirmed", integer(0, 12_000),
                "expected", integer(0, 12_000),
                "unknown", integer(0, 12_000)), "confirmed", "expected", "unknown");
        Map<String, Object> effect = closedObject(fields(
                "type", string(1, 96, "^[a-z][a-z0-9_]*$"),
                "observed_before", boundedDiagnosticDetails(),
                "observed_after", boundedDiagnosticDetails(),
                "verification", enumString("confirmed", "inferred", "unknown")),
                "type", "observed_before", "observed_after", "verification");
        Map<String, Object> safety = closedObject(fields(
                "mode", enumString("normal", "stopping", "paused"),
                "last_check_client_tick", integer(0, Long.MAX_VALUE)),
                "mode", "last_check_client_tick");
        Map<String, Object> wait = nullable(closedObject(fields(
                "reason", enumString(
                        "target_regeneration", "server_sync", "movement_settle",
                        "route_reobservation", "route_occupancy",
                        "bounded_preparation", "server_world_diff", "fresh_plan_observation",
                        "plan_progress", "server_terminal_evidence", "action_confirmation",
                        "condition_wait", "repeat_condition_unknown"),
                "deadline_client_tick", integer(0, Long.MAX_VALUE),
                "wake_condition", string(1, 160, null)),
                "reason", "deadline_client_tick", "wake_condition"));
        Map<String, Object> failure = routineFailure();
        Map<String, Object> finalization = closedObject(fields(
                "required", schema("type", "boolean"),
                "status", enumString("pending", "running", "succeeded", "failed", "not_required"),
                "phase", nullableString(128),
                "failure", nullable(failure)),
                "required", "status", "phase", "failure");
        Map<String, Object> event = closedObject(fields(
                "seq", integer(1, Long.MAX_VALUE),
                "type", enumString(
                        "phase_started", "step_verified", "retrying", "checkpoint", "needs_replan",
                        "finalization_started", "goal_verified", "succeeded", "failed", "cancelled"),
                "client_tick", integer(0, Long.MAX_VALUE),
                "observation_revision", integer(0, Long.MAX_VALUE),
                "details", boundedDetails()),
                "seq", "type", "client_tick", "observation_revision", "details");
        Map<String, Object> resourceSnapshot = closedObject(fields(
                "item", registryId(),
                "count", integer(0, Integer.MAX_VALUE)), "item", "count");
        Map<String, Object> resources = nullable(closedObject(fields(
                "planned", array(resourceSnapshot, 0, 64, true),
                "remaining", array(resourceSnapshot, 0, 64, true),
                "available", array(resourceSnapshot, 0, 64, true),
                "server_synchronized", schema("type", "boolean"),
                "basis_observation_revision", integer(0, Long.MAX_VALUE)),
                "planned", "remaining", "available", "server_synchronized",
                "basis_observation_revision"));
        return closedObject(fields(
                "routine_id", uuid(),
                "kind", enumString(ROUTINE_KINDS.toArray(String[]::new)),
                "state", routineState(),
                "phase", string(1, 128, "^[a-z][a-z0-9_.]*$"),
                "goal", goal,
                "progress", progress,
                "current_step", currentStep,
                "checkpoint", checkpoint,
                "verification", verification,
                "resources", resources,
                "effects", array(effect, 0, 128),
                "safety", safety,
                "wait", wait,
                "finalization", finalization,
                "events", array(event, 0, 128),
                "failure", nullable(failure),
                "next_poll_after_ms", integer(50, 10_000),
                "events_truncated", schema("type", "boolean")),
                "routine_id", "kind", "state", "phase", "goal", "progress", "current_step",
                "checkpoint", "verification", "resources", "effects", "safety", "wait", "finalization", "events",
                "failure", "next_poll_after_ms", "events_truncated");
    }

    private static Map<String, Object> routineFailure() {
        return closedObject(fields(
                "category", enumString("transient", "precondition", "divergence", "safety", "external"),
                "code", string(1, 64, "^[A-Z][A-Z0-9_]*$"),
                "retryable", schema("type", "boolean"),
                "recovery", enumString("retry", "replan", "user", "none"),
                "scope", enumString("step", "routine", "finalization"),
                // A 60-second Phase 2 deadline is at most 1,200 client ticks.
                "attempts", integer(0, 1_200),
                "expected", boundedDiagnosticDetails(),
                "observed", boundedDiagnosticDetails(),
                "evidence", boundedDiagnosticDetails(),
                "suggested_snapshot_scopes", array(
                        enumString(SNAPSHOT_SCOPES.toArray(String[]::new)), 0, SNAPSHOT_SCOPES.size(), true),
                "requires_user", schema("type", "boolean")),
                "category", "code", "retryable", "recovery", "scope", "attempts", "expected",
                "observed", "evidence", "suggested_snapshot_scopes", "requires_user");
    }

    private static Map<String, Object> routineState() {
        return enumString("QUEUED", "VALIDATING", "RUNNING", "WAITING", "FINALIZING",
                "SUCCEEDED", "FAILED", "CANCELLED");
    }

    private static Map<String, Object> envelope(String toolName, Map<String, Object> dataSchema) {
        Map<String, Object> error = closedObject(fields(
                "code", string(1, 64, "^[a-z][a-z0-9_]*$"),
                "message", string(1, 512, null),
                "retryable", schema("type", "boolean"),
                "details", boundedDetails()), "code", "message", "retryable", "details");
        Map<String, Object> result = closedObject(fields(
                "ok", schema("type", "boolean"),
                "tool", constant(toolName),
                "request_id", string(1, 96, null),
                "data", dataSchema,
                "error", error), "ok", "tool", "request_id");
        result.put("oneOf", List.of(
                schema("properties", fields("ok", constant(true)), "required", List.of("data"),
                        "not", schema("required", List.of("error"))),
                schema("properties", fields("ok", constant(false)), "required", List.of("error"),
                        "not", schema("required", List.of("data")))));
        return result;
    }

    private static Map<String, Object> boundedDetails() {
        return schema(
                "type", "object",
                "propertyNames", schema("pattern", "^[A-Za-z][A-Za-z0-9_.-]{0,63}$"),
                "additionalProperties", scalarOrScalarArray(),
                "maxProperties", 32);
    }

    private static Map<String, Object> boundedDiagnosticDetails() {
        Map<String, Object> scalarMap = schema(
                "type", "object",
                "propertyNames", schema("pattern", "^[A-Za-z][A-Za-z0-9_.-]{0,63}$"),
                "additionalProperties", scalarJson(),
                "maxProperties", 128);
        return schema(
                "type", "object",
                "propertyNames", schema("pattern", "^[A-Za-z][A-Za-z0-9_.-]{0,63}$"),
                "additionalProperties", schema("oneOf", List.of(
                        scalarJson(), array(scalarJson(), 0, 128), scalarMap)),
                "maxProperties", 32);
    }

    private static Map<String, Object> blockObservationResult() {
        return closedObject(fields(
                "outcome", enumString("current", "last_known", "not_currently_observable", "unknown"),
                "position", dimensionBlockPosition(),
                "actual", observedBlock(),
                "reason", blockObservationReason()),
                "outcome", "position");
    }

    private static Map<String, Object> compactBlockObservationResult() {
        return closedObject(fields(
                "outcome", enumString("current", "last_known", "not_currently_observable", "unknown"),
                "position", dimensionBlockPosition(),
                "state", blockState(),
                "visible_faces", array(
                        enumString("down", "up", "north", "south", "west", "east"), 0, 6, true),
                "within_reach", schema("type", "boolean"),
                "provenance", enumString(
                        "line_of_sight_observation", "crosshair_observation", "interaction_confirmation"),
                "age_ticks", integer(0, Long.MAX_VALUE),
                "reason", blockObservationReason()),
                "outcome", "position");
    }

    private static Map<String, Object> observedBlock() {
        Map<String, Object> context = closedObject(fields(
                "block_light_at_observation", integer(0, 15),
                "sky_light_at_observation", integer(0, 15),
                "fluid_at_observation", nullableRegistryId(),
                "fluid_source_at_observation", nullableBoolean(),
                "fluid_amount_at_observation", nullableInteger(1, 8),
                "replaceable_at_observation", schema("type", "boolean"),
                "collision_empty_at_observation", schema("type", "boolean"),
                "sturdy_faces_at_observation", array(
                        enumString("down", "up", "north", "south", "west", "east"), 0, 6, true)),
                "block_light_at_observation", "sky_light_at_observation", "fluid_at_observation",
                "fluid_source_at_observation", "fluid_amount_at_observation",
                "replaceable_at_observation", "collision_empty_at_observation", "sturdy_faces_at_observation");
        Map<String, Object> knowledge = closedObject(fields(
                "currentness", enumString("current", "last_known"),
                "provenance", enumString(
                        "line_of_sight_observation", "crosshair_observation", "interaction_confirmation"),
                "observed_at_client_tick", integer(0, Long.MAX_VALUE),
                "age_ticks", integer(0, Long.MAX_VALUE),
                "visible_now", schema("type", "boolean")),
                "currentness", "provenance", "observed_at_client_tick", "age_ticks", "visible_now");
        Map<String, Object> liveContext = closedObject(fields(
                "visible_faces", array(enumString("down", "up", "north", "south", "west", "east"), 0, 6, true),
                "within_reach", schema("type", "boolean")), "visible_faces", "within_reach");
        return closedObject(fields(
                "position", dimensionBlockPosition(),
                "state", blockState(),
                "observed_context", context,
                "knowledge", knowledge,
                "live_context", liveContext,
                "world_session_id", string(1, 96, null)),
                "position", "state", "observed_context", "knowledge", "world_session_id");
    }

    private static Map<String, Object> entityObservation(boolean requireThreatRelation) {
        Map<String, Object> knowledge = closedObject(fields(
                "currentness", enumString("current", "last_known"),
                "observed_at_client_tick", integer(0, Long.MAX_VALUE),
                "age_ticks", integer(0, Long.MAX_VALUE),
                "visible_now", schema("type", "boolean")),
                "currentness", "observed_at_client_tick", "age_ticks", "visible_now");
        Map<String, Object> result = closedObject(fields(
                "type", registryId(),
                "dimension", registryId(),
                "position", vector3(),
                "motion", vector3(),
                "vehicle", schema("type", "boolean"),
                "passenger", schema("type", "boolean"),
                "entity_ref", string(1, 128, null),
                "relative_position", vector3(),
                "distance", nonNegativeNumber(),
                "knowledge", knowledge,
                "world_session_id", string(1, 96, null),
                "threat_relation", enumString("currently_hostile_to_player", "unknown")),
                "type", "dimension", "position", "motion", "vehicle", "passenger", "knowledge",
                "world_session_id");
        if (requireThreatRelation) {
            @SuppressWarnings("unchecked")
            List<String> required = new java.util.ArrayList<>((List<String>) result.get("required"));
            required.add("threat_relation");
            required.add("relative_position");
            required.add("distance");
            result.put("required", List.copyOf(required));
        }
        return result;
    }

    private static Map<String, Object> compactEntityObservation() {
        return closedObject(fields(
                "type", registryId(),
                "position", vector3(),
                "relative_position", vector3(),
                "distance", nonNegativeNumber(),
                "entity_ref", string(1, 128, null),
                "currentness", enumString("current", "last_known"),
                "age_ticks", integer(0, Long.MAX_VALUE),
                "threat_relation", enumString("currently_hostile_to_player", "unknown")),
                "type", "position", "relative_position", "distance", "currentness", "age_ticks",
                "threat_relation");
    }

    private static Map<String, Object> blockObservationReason() {
        return enumString(
                "never_observed", "evicted", "unavailable", "camera_unavailable", "occluded",
                "out_of_range", "outside_fov", "unloaded");
    }

    private static Map<String, Object> blockState() {
        return closedLikeRegistryObject(fields(
                "block", registryId(),
                "properties", blockProperties()), "block", "properties");
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

    private static Map<String, Object> queryBlockPosition() {
        return closedObject(fields(
                "dimension", registryId(),
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

    private static Map<String, Object> vector3() {
        return closedObject(fields(
                "x", schema("type", "number"),
                "y", schema("type", "number"),
                "z", schema("type", "number")), "x", "y", "z");
    }

    private static Map<String, Object> rotation() {
        return closedObject(fields(
                "yaw", schema("type", "number"),
                "pitch", number(-90, 90)), "yaw", "pitch");
    }

    private static Map<String, Object> countObject(String... names) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : names) {
            properties.put(name, integer(0, 512));
        }
        return closedObject(properties, names);
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

    private static Map<String, Object> nullableRegistryId() {
        return nullable(registryId());
    }

    private static Map<String, Object> registryOrTagId() {
        return string(3, 257, "^#?[a-z0-9_.-]+:[a-z0-9_./-]+$");
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

    private static Map<String, Object> nullableString(int maxLength) {
        return nullable(string(1, maxLength, null));
    }

    private static Map<String, Object> integer(long min, long max) {
        return schema("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> nullableInteger(long min, long max) {
        return nullable(integer(min, max));
    }

    private static Map<String, Object> number(double min, double max) {
        return schema("type", "number", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> nonNegativeNumber() {
        return schema("type", "number", "minimum", 0);
    }

    private static Map<String, Object> nullableBoolean() {
        return schema("type", List.of("boolean", "null"));
    }

    private static Map<String, Object> scalarJson() {
        return schema("type", List.of("string", "number", "integer", "boolean", "null"));
    }

    private static Map<String, Object> scalarOrScalarArray() {
        return schema("oneOf", List.of(scalarJson(), array(scalarJson(), 0, 128)));
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return McpToolSchemas.schema("oneOf", List.of(schema, McpToolSchemas.schema("type", "null")));
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
