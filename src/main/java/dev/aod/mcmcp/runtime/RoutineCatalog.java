package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.mcp.McpToolSchemas;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 既存内部routineの能力・入力スキーマ一覧。公開5 Toolとは別の互換経路。 */
final class RoutineCatalog {
    private RoutineCatalog() {}

    static Map<String, Object> routineCatalog() {
        var summaries = detailedRoutineCatalog().stream()
                .map(RoutineCatalog::routineCatalogSummary)
                .toList();
        return Map.of(
                "catalog_version", "phase-6-compact-v2",
                "routines", summaries);
    }

    static Map<String, Object> routineCatalog(String kind) {
        Objects.requireNonNull(kind, "kind");
        var entry = detailedRoutineCatalog().stream()
                .filter(candidate -> kind.equals(candidate.get("kind")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("kind is not an available routine"));
        var detailed = new LinkedHashMap<>(entry);
        detailed.put("capabilities", routineCapabilities(kind));
        return Map.of(
                "catalog_version", "phase-6-compact-v2",
                "routines", List.of(Map.copyOf(detailed)));
    }

    static List<Map<String, Object>> detailedRoutineCatalog() {
        return List.of(
                        routineCatalogEntry(
                                "stationary_break",
                                2,
                                McpToolSchemas.stationaryBreakStartInput(),
                                List.of(
                                        "each counted break has a covering vanilla prediction ACK",
                                        "each counted break has a server-verified target state transition",
                                        "the synchronized inventory reaches the requested minimum item count",
                                        "all routine-owned attack input is released before terminal state")),
                        routineCatalogEntry(
                                NavigateToRequest.KIND,
                                3,
                                McpToolSchemas.navigateToStartInput(),
                                List.of(
                                        "the destination is reached within the requested horizontal tolerance",
                                        "the settled position is server-reconciled without a position correction",
                                        "all routine-owned movement input is released before verification")),
                        routineCatalogEntry(
                                BreakBlockRequest.KIND,
                                3,
                                McpToolSchemas.breakBlockStartInput(),
                                List.of(
                                        "the requested block transition has a covering vanilla prediction ACK",
                                        "the server-verified target state matches the Phase 3 minecraft:air expected_after",
                                        "all routine-owned attack input is released before verification")),
                        routineCatalogEntry(
                                PlaceBlockRequest.KIND,
                                3,
                                McpToolSchemas.placeBlockStartInput(),
                                List.of(
                                        "the exact item is selected from hotbar or staged once from player inventory before dispatch",
                                        "wheat, carrot, potato, and beetroot items may initially plant an exact age-0 crop above farmland",
                                        "exactly one bounded main-hand placement is dispatched",
                                        "the placement has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
                        routineCatalogEntry(
                                InteractBlockRequest.KIND,
                                3,
                                McpToolSchemas.interactBlockStartInput(),
                                List.of(
                                        "exactly one allowlisted block interaction is dispatched",
                                        "expected_after is the exact full same-block toggle state",
                                        "the interaction has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
                        routineCatalogEntry(
                                InteractEntityRequest.KIND,
                                3,
                                McpToolSchemas.interactEntityStartInput(),
                                List.of(
                                        "the opaque entity reference is re-resolved as a visible, reachable, targeted adult cow",
                                        "exactly one main-hand interaction is dispatched with minecraft:bucket and without automatic retry",
                                        "a fresh inbound inventory sync reaches the absolute minecraft:milk_bucket count goal")),
                        routineCatalogEntry(
                                UseItemOnBlockRequest.KIND,
                                3,
                                McpToolSchemas.useItemOnBlockStartInput(),
                                List.of(
                                        "the exact item is selected from hotbar or staged once from player inventory before dispatch",
                                        "the closed transition tills dirt, grass block, or dirt path to moisture-0 farmland with a vanilla hoe",
                                        "exactly one allowlisted normal-use item action is dispatched",
                                        "the action has a covering vanilla prediction ACK",
                                        "the server-verified target state matches expected_after")),
                        routineCatalogEntry(
                                ApplyBlockPlanRequest.KIND,
                                4,
                                McpToolSchemas.applyBlockPlanStartInput(),
                                List.of(
                                        "already-satisfied cells are skipped only after a current exact full-state observation",
                                        "all mutations have a covering vanilla prediction ACK and exact server state",
                                        "all required cells match current exact full states with unknown equal to zero",
                                        "the current client inventory is accepted as the eligible-hotbar baseline; every placement requires a fresh inbound selected-slot inventory sync")),
                        routineCatalogEntry(
                                "craft_items",
                                5,
                                McpToolSchemas.craftItemsStartInput(),
                                List.of(
                                        "the opaque client-known recipe reference and fingerprint are revalidated before dispatch",
                                        "ambiguous container clicks are never retried blindly",
                                        "success requires a fresh full-content readback with the absolute inventory goal and an empty cursor")),
                        routineCatalogEntry(
                                "transfer_items",
                                5,
                                McpToolSchemas.transferItemsStartInput(),
                                List.of(
                                        "only an automation-opened canonical vanilla chest or barrel is used",
                                        "minimum_destination_count zero performs a no-mutation bounded content readback",
                                        "default-only or exact item-ID whole stacks, including damaged tools, can be transferred",
                                        "a missing requested item reports a bounded list of observed source item IDs for replanning",
                                        "one bounded quick-move segment is never retried blindly",
                                        "success requires a fresh reopened full-content snapshot for both endpoints and an empty cursor")),
                        routineCatalogEntry(
                                "tend_crop_area",
                                5,
                                McpToolSchemas.tendCropAreaStartInput(),
                                List.of(
                                        "only existing declared crop blocks are harvested or replanted; air cells require place_block",
                                        "only declared current-visible cells using the closed vanilla crop adapters are mutated",
                                        "every harvest and replant transition has server-positive block evidence",
                                        "drop collection uncertainty remains explicit")),
                        routineCatalogEntry(
                                "harvest_tree_area",
                                5,
                                McpToolSchemas.harvestTreeAreaStartInput(),
                                List.of(
                                        "only declared current-visible vanilla log cells are claimed and mutated",
                                        "hidden logs and complete natural-tree coverage are never inferred",
                                        "drop collection uncertainty remains explicit")),
                        routineCatalogEntry(
                                "sleep_at_bed",
                                5,
                                McpToolSchemas.sleepAtBedStartInput(),
                                List.of(
                                        "both exact bed halves and the dimension sleep rule are revalidated before normal use",
                                        "sleep and wake require server-synchronized player state",
                                        "respawn change is confirmed only by the action-scoped vanilla semantic signal")),
                        routineCatalogEntry(
                                "survey_area",
                                5,
                                McpToolSchemas.surveyAreaStartInput(),
                                List.of(
                                        "only declared waypoints and samples are inspected through normal movement and view control",
                                        "current, last-known, and unknown coverage remain distinct",
                                        "spawn-surface assessment is explicitly predicted rather than server-confirmed")),
                        routineCatalogEntry(
                                "execute_plan",
                                6,
                                McpToolSchemas.executePlanStartInput(),
                                List.of(
                                        "every child action remains private to one parent routine",
                                        "all loops, waits, total ticks, nesting, and expanded executions are bounded",
                                        "conditions authorize progress only from positive current evidence",
                                        "the active child action is released before any terminal parent state")));
    }

    static Map<String, Object> routineCatalogSummary(Map<String, Object> entry) {
        String kind = (String) entry.get("kind");
        return Map.of(
                "kind", kind,
                "phase", entry.get("phase"),
                "experimental", entry.get("experimental"),
                "capabilities", routineCapabilities(kind));
    }

    static List<String> routineCapabilities(String kind) {
        return switch (kind) {
            case "stationary_break" -> List.of("break one regenerating target", "collect to inventory goal");
            case NavigateToRequest.KIND -> List.of("bounded ground navigation");
            case BreakBlockRequest.KIND -> List.of("break one exact block to air");
            case PlaceBlockRequest.KIND -> List.of(
                    "place one exact block or initially plant one crop",
                    "auto-stage the exact item from player inventory");
            case InteractBlockRequest.KIND -> List.of("toggle one allowlisted block");
            case InteractEntityRequest.KIND -> List.of("interact with one visible referenced entity");
            case UseItemOnBlockRequest.KIND -> List.of(
                    "till one exact dirt, grass, or path block with a vanilla hoe",
                    "auto-stage the exact item from player inventory");
            case ApplyBlockPlanRequest.KIND -> List.of("verify, break, place, or replace up to 64 declared cells");
            case "craft_items" -> List.of("craft a client-known recipe to an inventory goal");
            case "transfer_items" -> List.of(
                    "open, transfer one item type to or from one container, verify, and close",
                    "inspect bounded source item choices without mutation by setting the destination goal to zero",
                    "report bounded source item choices when the requested item is absent");
            case "tend_crop_area" -> List.of(
                    "harvest and replant existing declared crop plots",
                    "use place_block for initial planting into air");
            case "harvest_tree_area" -> List.of("harvest and replant declared visible tree cells");
            case "sleep_at_bed" -> List.of("sleep at one declared bed and return");
            case "survey_area" -> List.of("visit declared waypoints and observe declared samples");
            case "execute_plan" -> List.of(
                    "execute a bounded typed sequence with finite loops and checks",
                    "compose transfer_items, use_item_on_block, and place_block for farming");
            default -> throw new IllegalArgumentException("kind is not an available routine");
        };
    }

    static Map<String, Object> routineCatalogEntry(
            String kind,
            int phase,
            Map<String, Object> inputSchema,
            List<String> postconditions) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("kind", kind);
        entry.put("phase", phase);
        entry.put("experimental", false);
        entry.put("input_schema", inputSchema);
        entry.put("postconditions", postconditions);
        return Map.copyOf(entry);
    }
}
