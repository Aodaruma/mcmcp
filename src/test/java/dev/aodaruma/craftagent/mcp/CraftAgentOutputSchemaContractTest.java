package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Black-box contract checks between runtime reply envelopes and their internal wire schemas. */
class CraftAgentOutputSchemaContractTest {
    private static final String ROUTINE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String IDEMPOTENCY_KEY = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @ParameterizedTest(name = "{0} success matches internal output schema")
    @MethodSource("toolCases")
    void representativeSuccessResponseMatchesInternalOutputSchema(ToolCase toolCase) throws Exception {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(toolCase.successData())),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, toolCase.name(), toolCase.arguments());

        assertThat(result.isError()).as("%s must dispatch as a successful call", toolCase.name()).isFalse();
        assertThat(result.structuredContent()).isNull();
        assertMatchesInternalOutputSchema(toolCase.name(), textEnvelope(result));
    }

    @ParameterizedTest(name = "{0} failure matches internal output schema")
    @MethodSource("toolCases")
    void runtimeFailureResponseMatchesInternalOutputSchema(ToolCase toolCase) throws Exception {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.failure(
                        "fixture_failure", "A bounded fixture failure.", true, Map.of("source", "contract_test"))),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, toolCase.name(), toolCase.arguments());

        assertThat(result.isError()).as("%s must expose the runtime failure", toolCase.name()).isTrue();
        assertThat(result.structuredContent()).isNull();
        Map<?, ?> envelope = textEnvelope(result);
        assertThat(envelope.get("ok")).isEqualTo(false);
        assertThat(envelope.get("tool")).isEqualTo(toolCase.name());
        assertThat(envelope.get("error")).isInstanceOf(Map.class);
        Map<?, ?> error = (Map<?, ?>) envelope.get("error");
        assertThat(error.get("code")).isEqualTo("fixture_failure");
        assertMatchesInternalOutputSchema(toolCase.name(), envelope);
    }

    @ParameterizedTest(name = "start_routine accepts output kind {0}")
    @MethodSource("routineKinds")
    void everyAdvertisedRoutineKindMatchesTheStartOutputSchema(String kind) throws Exception {
        Object estimate = "apply_block_plan".equals(kind)
                ? McpTestFixtures.fields(
                        "items", List.of(Map.of(
                                "item", "minecraft:stone", "maximum_required_count", 2)),
                        "break_operations", 0,
                        "place_operations", 2)
                : null;
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(McpTestFixtures.fields(
                        "routine_id", ROUTINE_ID,
                        "kind", kind,
                        "state", "VALIDATING",
                        "idempotent_replay", false,
                        "resource_estimate", estimate))),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, "start_routine", startArguments());

        assertThat(result.isError()).isFalse();
        assertMatchesInternalOutputSchema("start_routine", textEnvelope(result));
    }

    @ParameterizedTest(name = "get_routine accepts projection {0}")
    @MethodSource("phaseThreeRoutineData")
    void phaseThreeRoutineProjectionsMatchTheRoutineOutputSchema(
            String ignored, Map<String, Object> routineData) throws Exception {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(routineData)),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(
                registry, "get_routine", Map.of("routine_id", ROUTINE_ID));

        assertThat(result.isError()).isFalse();
        assertMatchesInternalOutputSchema("get_routine", textEnvelope(result));
    }

    private static Stream<Arguments> toolCases() {
        return Stream.of(
                new ToolCase("get_status", Map.of(), McpTestFixtures.statusData()),
                new ToolCase(
                        "get_snapshot",
                        Map.of("scopes", List.of("player")),
                        snapshotData()),
                new ToolCase(
                        "capture_creative_region",
                        creativeRegionArguments(),
                        creativeRegionData()),
                new ToolCase(
                        "edit_creative_world",
                        creativeWorldEditArguments(),
                        creativeWorldEditData()),
                new ToolCase(
                        "compare_block_plan",
                        compareArguments(),
                        compareData()),
                new ToolCase("list_routines", Map.of(), listRoutinesData()),
                new ToolCase(
                        "get_routine",
                        Map.of("routine_id", ROUTINE_ID, "max_events", 32),
                        routineData()),
                new ToolCase("start_routine", startArguments(), startRoutineData()),
                new ToolCase(
                        "cancel_routine",
                        Map.of("routine_id", ROUTINE_ID, "reason", "contract test"),
                        cancelRoutineData()),
                new ToolCase(
                        "emergency_stop",
                        Map.of("reason", "contract test"),
                        emergencyStopData()),
                new ToolCase(
                        "get_recipes",
                        McpTestFixtures.fields(
                                "query", Map.of("kind", "result_item", "item", "minecraft:hopper"),
                                "max_results", 16),
                        recipesData()))
                .map(Arguments::of);
    }

    private static Map<String, Object> snapshotData() {
        Map<String, Object> player = McpTestFixtures.fields(
                "position", vector(10.5, 64.0, -3.5),
                "rotation", Map.of("yaw", 90.0, "pitch", 15.0),
                "velocity", vector(0.0, 0.0, 0.0),
                "health", 20.0,
                "max_health", 20.0,
                "absorption", 0.0,
                "hunger", 20,
                "saturation", 5.0,
                "on_ground", true,
                "alive", true,
                "using_item", false,
                "block_interaction_range", 4.5,
                "entity_interaction_range", 3.0,
                "selected_slot", 0,
                "effects", List.of());
        return McpTestFixtures.fields(
                "world_session_id", "session-test",
                "client_tick", 200L,
                "observation_revision", 12L,
                "requested_scopes", List.of("player"),
                "detail", "compact",
                "player", player);
    }

    private static Map<String, Object> creativeRegionArguments() {
        return McpTestFixtures.fields(
                "operation", "start",
                "region", McpTestFixtures.fields(
                        "dimension", "minecraft:overworld",
                        "min", Map.of("x", 10, "y", 64, "z", -3),
                        "max", Map.of("x", 10, "y", 64, "z", -3)),
                "include_entities", true,
                "idempotency_key", IDEMPOTENCY_KEY);
    }

    private static Map<String, Object> creativeRegionData() {
        Map<String, Object> basis = McpTestFixtures.fields(
                "world_session_id", ROUTINE_ID,
                "started_client_tick", 200L,
                "dimension", "minecraft:overworld",
                "source", "integrated_server_chunk_sequence",
                "game_mode", "creative",
                "consistency", "server_thread_chunk_sequence",
                "region", McpTestFixtures.fields(
                        "min", Map.of("x", 10, "y", 64, "z", -3),
                        "max", Map.of("x", 10, "y", 64, "z", -3)),
                "volume", 1,
                "started_server_tick", 100,
                "completed_server_tick", 102);
        return McpTestFixtures.fields(
                "job_id", ROUTINE_ID,
                "state", "succeeded",
                "idempotent_replay", false,
                "progress", McpTestFixtures.fields(
                        "processed_cells", 1,
                        "total_cells", 1,
                        "loaded_chunks", 1,
                        "processed_chunks", 1,
                        "total_chunks", 1,
                        "started_server_tick", 100),
                "artifact", McpTestFixtures.fields(
                        "relative_path", "craftagent/exports/creative-blueprints/" + ROUTINE_ID + ".json.gz",
                        "format", "json+gzip",
                        "sha256", "sha256:" + "a".repeat(64),
                        "compressed_bytes", 512,
                        "uncompressed_bytes", 1024),
                "summary", McpTestFixtures.fields(
                        "basis", basis,
                        "blueprint_hash", "sha256:" + "c".repeat(64),
                        "palette_size", 1,
                        "manual_setup_count", 0,
                        "block_counts", McpTestFixtures.fields(
                                "items", List.of(Map.of("block", "minecraft:stone", "count", 1)),
                                "unique_count", 1,
                                "truncated", false),
                        "materials", McpTestFixtures.fields(
                                "complete", true,
                                "items", List.of(Map.of("item", "minecraft:stone", "count", 1)),
                                "unique_count", 1,
                                "truncated", false),
                        "entities", McpTestFixtures.fields(
                                "included", true,
                                "count", 0,
                                "truncated", false,
                                "complete", false)));
    }

    private static Map<String, Object> creativeWorldEditArguments() {
        return McpTestFixtures.fields(
                "operation", "set_block",
                "position", Map.of(
                        "dimension", "minecraft:overworld", "x", 10, "y", 64, "z", -3),
                "state", Map.of("block", "minecraft:stone", "properties", Map.of()),
                "idempotency_key", IDEMPOTENCY_KEY);
    }

    private static Map<String, Object> creativeWorldEditData() {
        Map<String, Object> history = McpTestFixtures.fields(
                "can_undo", true,
                "can_redo", false,
                "undo_depth", 1,
                "redo_depth", 0,
                "undo_transaction_id", ROUTINE_ID,
                "redo_transaction_id", null,
                "history_ttl_seconds", 1_800L);
        return McpTestFixtures.fields(
                "response", "job",
                "job_id", ROUTINE_ID,
                "operation", "set_block",
                "state", "succeeded",
                "idempotent_replay", false,
                "started_client_tick", 200L,
                "requested_changes", 1,
                "applied_changes", 1,
                "transaction_id", ROUTINE_ID,
                "history", history,
                "error", null);
    }

    private static Map<String, Object> compareArguments() {
        return McpTestFixtures.fields(
                "anchor", dimensionPosition(10, 64, -3),
                "expected", List.of(McpTestFixtures.fields(
                        "id", "foundation",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "state", Map.of("block", "minecraft:cobblestone"))));
    }

    private static Map<String, Object> compareData() {
        return McpTestFixtures.fields(
                "plan_hash", "sha256:" + "0".repeat(64),
                "basis", McpTestFixtures.fields(
                        "world_session_id", "session-test",
                        "dimension", "minecraft:overworld",
                        "client_tick", 200L,
                        "observation_revision", 12L),
                "coverage", counts(
                        "requested", 1,
                        "current", 1,
                        "last_known", 0,
                        "unknown", 0),
                "summary", counts(
                        "match_current", 1,
                        "mismatch_current", 0,
                        "match_last_known", 0,
                        "mismatch_last_known", 0,
                        "unknown", 0),
                "required_verification", counts(
                        "total", 1,
                        "match_current", 1,
                        "mismatch_or_stale", 0,
                        "unknown", 0,
                        "complete", true),
                "differences", List.of());
    }

    private static Map<String, Object> recipesData() {
        return McpTestFixtures.fields(
                "basis", McpTestFixtures.fields(
                        "world_session_id", "session-test",
                        "client_tick", 200L,
                        "recipe_book_revision", 12L),
                "coverage", McpTestFixtures.fields(
                        "source", "client_known_recipe_displays",
                        "complete", false,
                        "known", 20,
                        "matched", 1,
                        "returned", 1,
                        "truncated", false),
                "recipes", List.of(McpTestFixtures.fields(
                        "recipe_ref", "AbCdEfGhIjKlMnOpQrStUvWx",
                        "fingerprint", "sha256:" + "a".repeat(64),
                        "display_kind", "shaped",
                        "required_screen", "crafting_table",
                        "supported", true,
                        "unsupported_reason", null,
                        "result", McpTestFixtures.fields(
                                "deterministic", true,
                                "alternatives", List.of(McpTestFixtures.fields(
                                        "item", "minecraft:hopper",
                                        "count", 1,
                                        "stack_fingerprint", "sha256:" + "b".repeat(64)))),
                        "ingredients", List.of(McpTestFixtures.fields(
                                "index", 0,
                                "count_per_craft", 5,
                                "alternatives", List.of(Map.of("item", "minecraft:iron_ingot")))),
                        "shape", Map.of("width", 3, "height", 3))));
    }

    private static Map<String, Object> listRoutinesData() {
        return Map.of(
                "catalog_version", "phase-6-compact-v1",
                "routines", List.of(
                        catalogEntry("stationary_break", 2)));
    }

    private static Map<String, Object> catalogEntry(String kind, int phase) {
        return McpTestFixtures.fields(
                "kind", kind,
                "phase", phase,
                "experimental", false,
                "capabilities", List.of("bounded server-confirmed work"));
    }

    private static Map<String, Object> routineData() {
        return McpTestFixtures.fields(
                "routine_id", ROUTINE_ID,
                "kind", "stationary_break",
                "state", "RUNNING",
                "phase", "breaking",
                "goal", Map.of("verified", false),
                "progress", Map.of("completed", 1, "total", 4, "unit", "items"),
                "current_step", McpTestFixtures.fields(
                        "kind", "break_block",
                        "target", dimensionPosition(10, 64, -3)),
                "checkpoint", Map.of("seq", 2L, "observation_revision", 12L),
                "verification", Map.of("confirmed", 1, "expected", 4, "unknown", 0),
                "resources", null,
                "effects", List.of(),
                "safety", Map.of("mode", "normal", "last_check_client_tick", 200L),
                "wait", null,
                "finalization", McpTestFixtures.fields(
                        "required", true,
                        "status", "pending",
                        "phase", null,
                        "failure", null),
                "events", List.of(McpTestFixtures.fields(
                        "seq", 1L,
                        "type", "phase_started",
                        "client_tick", 199L,
                        "observation_revision", 11L,
                        "details", Map.of("phase", "breaking"))),
                "failure", null,
                "next_poll_after_ms", 250,
                "events_truncated", false);
    }

    private static Stream<Arguments> routineKinds() {
        return Stream.of(
                "stationary_break", "navigate_to", "break_block", "place_block",
                "interact_block", "interact_entity", "apply_block_plan", "craft_items",
                "transfer_items", "tend_crop_area", "harvest_tree_area", "sleep_at_bed",
                "survey_area").map(Arguments::of);
    }

    private static Stream<Arguments> phaseThreeRoutineData() {
        return Stream.of(
                Arguments.of("navigate_to", routineData(
                        "navigate_to", "destinations", McpTestFixtures.fields(
                                "kind", "navigate_to",
                                "target", dimensionPosition(12, 64, -3),
                                "horizontal_tolerance_blocks", 0.5),
                        "movement_settle")),
                Arguments.of("navigate_to_route_reobservation", routineData(
                        "navigate_to", "destinations", McpTestFixtures.fields(
                                "kind", "navigate_to",
                                "target", dimensionPosition(12, 64, -3),
                                "horizontal_tolerance_blocks", 0.5),
                        "route_reobservation")),
                Arguments.of("navigate_to_route_occupancy", routineData(
                        "navigate_to", "destinations", McpTestFixtures.fields(
                                "kind", "navigate_to",
                                "target", dimensionPosition(12, 64, -3),
                                "horizontal_tolerance_blocks", 0.5),
                        "route_occupancy")),
                Arguments.of("break_block", routineData(
                        "break_block", "blocks", McpTestFixtures.fields(
                                "kind", "break_block",
                                "target", dimensionPosition(10, 64, -3),
                                "expected_after", Map.of("block", "minecraft:air")),
                        "server_sync")),
                Arguments.of("place_block", routineData(
                        "place_block", "blocks", McpTestFixtures.fields(
                                "kind", "place_block",
                                "target", dimensionPosition(10, 64, -3),
                                "expected_after", Map.of("block", "minecraft:cobblestone")),
                        "server_sync")),
                Arguments.of("interact_block", routineData(
                        "interact_block", "interactions", McpTestFixtures.fields(
                                "kind", "interact_block",
                                "target", dimensionPosition(10, 64, -3),
                                "expected_after", McpTestFixtures.fields(
                                        "block", "minecraft:lever",
                                        "properties", Map.of("powered", "true"))),
                        "server_sync")),
                Arguments.of("interact_entity", routineData(
                        "interact_entity", "interactions", McpTestFixtures.fields(
                                "kind", "interact_entity",
                                "entity_ref", "AbCdEfGhIjKlMnOpQrStUvWx",
                                "expected_type", "minecraft:cow"),
                        "server_sync")),
                Arguments.of("use_item_on_block", routineData(
                        "use_item_on_block", "interactions", McpTestFixtures.fields(
                                "kind", "use_item_on_block",
                                "target", dimensionPosition(10, 64, -3),
                                "expected_after", McpTestFixtures.fields(
                                        "block", "minecraft:farmland",
                                        "properties", Map.of("moisture", "0"))),
                        "server_sync")),
                Arguments.of("execute_plan", routineData(
                        "execute_plan", "steps", McpTestFixtures.fields(
                                "kind", "plan_step",
                                "plan_id", "wheat-stack",
                                "step_id", "till-0",
                                "op", "action",
                                "routine_kind", "use_item_on_block"),
                        "action_confirmation")),
                Arguments.of("apply_block_plan", applyBlockPlanRoutineData()));
    }

    private static Map<String, Object> routineData(
            String kind, String unit, Map<String, Object> currentStep, String waitReason) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(routineData());
        result.put("kind", kind);
        result.put("progress", Map.of("completed", 0, "total", 1, "unit", unit));
        result.put("current_step", currentStep);
        result.put("wait", McpTestFixtures.fields(
                "reason", waitReason,
                "deadline_client_tick", 240L,
                "wake_condition", "The fixed verifier observes the declared goal."));
        return result;
    }

    private static Map<String, Object> startArguments() {
        return McpTestFixtures.fields(
                "kind", "stationary_break",
                "parameters", McpTestFixtures.fields(
                        "target", dimensionPosition(10, 64, -3),
                        "allowed_blocks", List.of("minecraft:cobblestone"),
                        "goal", Map.of("item", "minecraft:cobblestone", "minimum_inventory_count", 4),
                        "regeneration_timeout_seconds", 3),
                "bounds", McpTestFixtures.fields(
                        "dimension", "minecraft:overworld",
                        "region", Map.of(
                                "min", position(10, 64, -3),
                                "max", position(10, 64, -3)),
                        "max_travel_blocks", 0,
                        "max_duration_seconds", 30,
                        "allow_break", true),
                "completion_intent", "finish_goal",
                "idempotency_key", IDEMPOTENCY_KEY);
    }

    private static Map<String, Object> startRoutineData() {
        return McpTestFixtures.fields(
                "routine_id", ROUTINE_ID,
                "kind", "stationary_break",
                "state", "VALIDATING",
                "idempotent_replay", false,
                "resource_estimate", null);
    }

    private static Map<String, Object> applyBlockPlanRoutineData() {
        var result = new java.util.LinkedHashMap<String, Object>(routineData());
        result.put("kind", "apply_block_plan");
        result.put("progress", Map.of("completed", 0, "total", 1, "unit", "cells"));
        result.put("current_step", McpTestFixtures.fields(
                "kind", "plan_cell",
                "step_index", 0,
                "phase_id", "foundation",
                "cell_id", "stone-0",
                "operation", "place",
                "target", dimensionPosition(10, 64, -3),
                "expected_after", Map.of(
                        "block", "minecraft:stone", "properties", Map.of()),
                "child_stage", "place",
                "item", "minecraft:stone"));
        result.put("resources", McpTestFixtures.fields(
                "planned", List.of(Map.of("item", "minecraft:stone", "count", 1)),
                "remaining", List.of(Map.of("item", "minecraft:stone", "count", 1)),
                "available", List.of(Map.of("item", "minecraft:stone", "count", 12)),
                "server_synchronized", true,
                "basis_observation_revision", 12L));
        result.put("wait", McpTestFixtures.fields(
                "reason", "bounded_preparation",
                "deadline_client_tick", 240L,
                "wake_condition", "The bounded preparation is ready."));
        return result;
    }

    private static Map<String, Object> cancelRoutineData() {
        return Map.of(
                "routine_id", ROUTINE_ID,
                "state", "CANCELLED",
                "released_inputs", true,
                "already_terminal", false);
    }

    private static Map<String, Object> emergencyStopData() {
        return Map.of(
                "stop_requested", true,
                "locked", true,
                "released_inputs", true,
                "discarded_pending_starts", 1);
    }

    private static Map<String, Object> position(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static Map<String, Object> dimensionPosition(int x, int y, int z) {
        return Map.of("dimension", "minecraft:overworld", "x", x, "y", y, "z", z);
    }

    private static Map<String, Object> vector(double x, double y, double z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static Map<String, Object> counts(Object... entries) {
        return McpTestFixtures.fields(entries);
    }

    private static McpSchema.CallToolResult invoke(
            CraftAgentToolRegistry registry, String name, Map<String, Object> arguments) {
        McpStatelessServerFeatures.SyncToolSpecification specification = specification(registry, name);
        return specification.callHandler().apply(
                McpTransportContext.EMPTY, new McpSchema.CallToolRequest(name, arguments));
    }

    private static void assertMatchesInternalOutputSchema(String name, Object envelope) {
        Map<String, Object> schema = switch (name) {
            case "get_status" -> McpToolSchemas.statusOutput();
            case "get_snapshot" -> McpToolSchemas.snapshotOutput();
            case "capture_creative_region" -> McpToolSchemas.creativeRegionOutput();
            case "edit_creative_world" -> McpToolSchemas.creativeWorldEditOutput();
            case "compare_block_plan" -> McpToolSchemas.compareOutput();
            case "list_routines" -> McpToolSchemas.listRoutinesOutput();
            case "get_routine" -> McpToolSchemas.getRoutineOutput();
            case "start_routine" -> McpToolSchemas.startRoutineOutput();
            case "cancel_routine" -> McpToolSchemas.cancelRoutineOutput();
            case "emergency_stop" -> McpToolSchemas.emergencyStopOutput();
            case "get_recipes" -> McpToolSchemas.getRecipesOutput();
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
        var validation = McpJsonDefaults.getSchemaValidator().validate(schema, envelope);
        assertThat(validation.valid())
                .as("%s output must match its internal schema: %s", name, validation)
                .isTrue();
    }

    private static Map<String, Object> textEnvelope(McpSchema.CallToolResult result) throws Exception {
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        return McpJsonDefaults.getMapper().readValue(text, new TypeRef<>() { });
    }

    private static McpStatelessServerFeatures.SyncToolSpecification specification(
            CraftAgentToolRegistry registry, String name) {
        return registry.specifications().stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private record ToolCase(String name, Map<String, Object> arguments, Map<String, Object> successData) {
        @Override
        public String toString() {
            return name;
        }
    }
}
