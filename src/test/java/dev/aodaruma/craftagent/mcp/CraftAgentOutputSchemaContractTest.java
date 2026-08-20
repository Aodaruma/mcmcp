package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
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

/** Black-box contract checks between the public tool catalog and runtime reply envelopes. */
class CraftAgentOutputSchemaContractTest {
    private static final String ROUTINE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String IDEMPOTENCY_KEY = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @ParameterizedTest(name = "{0} success matches advertised outputSchema")
    @MethodSource("toolCases")
    void representativeSuccessResponseMatchesAdvertisedOutputSchema(ToolCase toolCase) {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(toolCase.successData())),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, toolCase.name(), toolCase.arguments());

        assertThat(result.isError()).as("%s must dispatch as a successful call", toolCase.name()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        assertMatchesAdvertisedOutputSchema(registry, toolCase.name(), result.structuredContent());
    }

    @ParameterizedTest(name = "{0} failure matches advertised outputSchema")
    @MethodSource("toolCases")
    void runtimeFailureResponseMatchesAdvertisedOutputSchema(ToolCase toolCase) {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.failure(
                        "fixture_failure", "A bounded fixture failure.", true, Map.of("source", "contract_test"))),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, toolCase.name(), toolCase.arguments());

        assertThat(result.isError()).as("%s must expose the runtime failure", toolCase.name()).isTrue();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        Map<?, ?> envelope = (Map<?, ?>) result.structuredContent();
        assertThat(envelope.get("ok")).isEqualTo(false);
        assertThat(envelope.get("tool")).isEqualTo(toolCase.name());
        assertThat(envelope.get("error")).isInstanceOf(Map.class);
        Map<?, ?> error = (Map<?, ?>) envelope.get("error");
        assertThat(error.get("code")).isEqualTo("fixture_failure");
        assertMatchesAdvertisedOutputSchema(registry, toolCase.name(), envelope);
    }

    @ParameterizedTest(name = "start_routine accepts output kind {0}")
    @MethodSource("routineKinds")
    void everyAdvertisedRoutineKindMatchesTheStartOutputSchema(String kind) {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(McpRuntimePort.RuntimeReply.success(Map.of(
                        "routine_id", ROUTINE_ID,
                        "kind", kind,
                        "state", "VALIDATING",
                        "idempotent_replay", false))),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(registry, "start_routine", startArguments());

        assertThat(result.isError()).isFalse();
        assertMatchesAdvertisedOutputSchema(registry, "start_routine", result.structuredContent());
    }

    @ParameterizedTest(name = "get_routine accepts projection {0}")
    @MethodSource("phaseThreeRoutineData")
    void phaseThreeRoutineProjectionsMatchTheRoutineOutputSchema(String ignored, Map<String, Object> routineData) {
        CraftAgentToolRegistry registry = new CraftAgentToolRegistry(
                (command, context) -> CompletableFuture.completedFuture(
                        McpRuntimePort.RuntimeReply.success(routineData)),
                Duration.ofSeconds(1));

        McpSchema.CallToolResult result = invoke(
                registry, "get_routine", Map.of("routine_id", ROUTINE_ID));

        assertThat(result.isError()).isFalse();
        assertMatchesAdvertisedOutputSchema(registry, "get_routine", result.structuredContent());
    }

    private static Stream<Arguments> toolCases() {
        return Stream.of(
                new ToolCase("get_status", Map.of(), McpTestFixtures.statusData()),
                new ToolCase(
                        "get_snapshot",
                        Map.of("scopes", List.of("player")),
                        snapshotData()),
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
                        emergencyStopData()))
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
                "player", player);
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

    private static Map<String, Object> listRoutinesData() {
        return Map.of(
                "catalog_version", "phase-3",
                "routines", List.of(
                        catalogEntry("stationary_break", 2, McpToolSchemas.stationaryBreakStartInput()),
                        catalogEntry("navigate_to", 3, McpToolSchemas.navigateToStartInput()),
                        catalogEntry("break_block", 3, McpToolSchemas.breakBlockStartInput()),
                        catalogEntry("place_block", 3, McpToolSchemas.placeBlockStartInput()),
                        catalogEntry("interact_block", 3, McpToolSchemas.interactBlockStartInput()),
                        catalogEntry("interact_entity", 3, McpToolSchemas.interactEntityStartInput())));
    }

    private static Map<String, Object> catalogEntry(
            String kind, int phase, Map<String, Object> inputSchema) {
        return McpTestFixtures.fields(
                "kind", kind,
                "phase", phase,
                "experimental", false,
                "input_schema", inputSchema,
                "postconditions", List.of("The kind-specific goal is server-confirmed."));
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
                "interact_block", "interact_entity").map(Arguments::of);
    }

    private static Stream<Arguments> phaseThreeRoutineData() {
        return Stream.of(
                Arguments.of("navigate_to", routineData(
                        "navigate_to", "destinations", McpTestFixtures.fields(
                                "kind", "navigate_to",
                                "target", dimensionPosition(12, 64, -3),
                                "horizontal_tolerance_blocks", 0.5),
                        "movement_settle")),
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
                        "server_sync")));
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
        return Map.of(
                "routine_id", ROUTINE_ID,
                "kind", "stationary_break",
                "state", "VALIDATING",
                "idempotent_replay", false);
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

    private static void assertMatchesAdvertisedOutputSchema(
            CraftAgentToolRegistry registry, String name, Object structuredContent) {
        McpSchema.Tool tool = specification(registry, name).tool();
        var validation = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(), structuredContent);
        assertThat(validation.valid())
                .as("%s output must match its advertised schema: %s", name, validation)
                .isTrue();
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
