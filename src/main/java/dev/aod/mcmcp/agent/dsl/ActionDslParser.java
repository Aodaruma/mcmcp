package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.redstone.RedstoneSpec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.INVALID_ARGUMENT;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.PROGRAM_TOO_COMPLEX;

/** Strict JSON parser for the closed {@code agent_start_action} Action DSL v1 envelope. */
public final class ActionDslParser {
    private ActionDslParser() {
    }

    public static ActionDsl.Request parse(String source) {
        if (source == null) {
            throw invalid("Action DSL JSON is required");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > ActionDslValidator.MAX_REQUEST_BYTES) {
            throw new ActionDslException(
                    PROGRAM_TOO_COMPLEX,
                    "Action DSL request exceeds " + ActionDslValidator.MAX_REQUEST_BYTES + " UTF-8 bytes");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (JsonParseException | IllegalStateException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, "Action DSL JSON is malformed", failure);
        }
        return parseElement(parsed);
    }

    /** Integration-friendly form; the HTTP layer must additionally enforce the original byte size. */
    public static ActionDsl.Request parse(JsonObject source) {
        if (source == null) {
            throw invalid("Action DSL JSON is required");
        }
        return parse(source.toString());
    }

    private static ActionDsl.Request parseElement(JsonElement source) {
        JsonObject root = object(source, "$", Set.of("schema_version", "program", "budget"),
                Set.of("schema_version", "program", "budget"));
        ActionDsl.Request request = new ActionDsl.Request(
                integer(root.get("schema_version"), "schema_version"),
                program(root.get("program")),
                budget(root.get("budget")));
        ActionDslValidator.validate(request);
        return request;
    }

    private static ActionDsl.Program program(JsonElement value) {
        JsonObject object = object(value, "program",
                Set.of("dsl_version", "name", "capabilities", "body"),
                Set.of("dsl_version", "capabilities", "body"));
        Optional<String> name = object.has("name")
                ? Optional.of(string(object.get("name"), "program.name"))
                : Optional.empty();
        JsonArray capabilityValues = array(object.get("capabilities"), "program.capabilities");
        if (capabilityValues.size() > ActionDsl.Capability.values().length) {
            throw invalid("program.capabilities contains too many values");
        }
        var capabilities = EnumSet.noneOf(ActionDsl.Capability.class);
        var seen = new HashSet<String>();
        for (int index = 0; index < capabilityValues.size(); index++) {
            String raw = string(capabilityValues.get(index), "program.capabilities[" + index + "]");
            if (!seen.add(raw)) {
                throw invalid("program.capabilities must contain unique values");
            }
            capabilities.add(capability(raw, "program.capabilities[" + index + "]"));
        }
        return new ActionDsl.Program(
                integer(object.get("dsl_version"), "program.dsl_version"),
                name,
                capabilities,
                nodes(object.get("body"), "program.body"));
    }

    private static ActionDsl.Budget budget(JsonElement value) {
        Set<String> fields = Set.of(
                "max_duration_ms", "max_ticks", "max_distance_blocks", "max_camera_degrees",
                "max_interactions", "max_blocks_broken", "max_blocks_placed");
        JsonObject object = object(value, "budget", fields, fields);
        return new ActionDsl.Budget(
                longInteger(object.get("max_duration_ms"), "budget.max_duration_ms"),
                longInteger(object.get("max_ticks"), "budget.max_ticks"),
                number(object.get("max_distance_blocks"), "budget.max_distance_blocks"),
                number(object.get("max_camera_degrees"), "budget.max_camera_degrees"),
                longInteger(object.get("max_interactions"), "budget.max_interactions"),
                longInteger(object.get("max_blocks_broken"), "budget.max_blocks_broken"),
                longInteger(object.get("max_blocks_placed"), "budget.max_blocks_placed"));
    }

    private static List<ActionDsl.Node> nodes(JsonElement value, String path) {
        JsonArray array = array(value, path);
        var nodes = new ArrayList<ActionDsl.Node>(array.size());
        for (int index = 0; index < array.size(); index++) {
            nodes.add(node(array.get(index), path + "[" + index + "]"));
        }
        return List.copyOf(nodes);
    }

    private static ActionDsl.Node node(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        String operation = string(required(object, "op", path), path + ".op");
        return switch (operation) {
            case "navigate_to_known" -> navigate(object, path);
            case "approach_known_surface" -> approachKnownSurface(object, path);
            case "approach_known_placement" -> approachKnownPlacement(object, path);
            case "face_known_position" -> face(object, path);
            case "face_known_block_face" -> faceKnownBlockFace(object, path);
            case "break_known_face" -> breakKnownFace(object, path);
            case "break_known_block" -> breakKnownBlock(object, path);
            case "operate_known_cobblestone_generator" ->
                    operateKnownCobblestoneGenerator(object, path);
            case "till_known_block" -> tillKnownBlock(object, path);
            case "till_known_batch" -> tillKnownBatch(object, path);
            case "plant_known_wheat" -> plantKnownWheat(object, path);
            case "plant_known_wheat_batch" -> plantKnownWheatBatch(object, path);
            case "harvest_known_wheat" -> harvestKnownWheat(object, path);
            case "harvest_known_wheat_batch" -> harvestKnownWheatBatch(object, path);
            case "apply_known_block_plan" -> applyKnownBlockPlan(object, path);
            case "clear_known_block_plan" -> clearKnownBlockPlan(object, path);
            case "pillar_up_known" -> pillarUpKnown(object, path);
            case "apply_known_redstone_spec" -> applyKnownRedstoneSpec(object, path);
            case "open_known_fence_gate" -> openKnownFenceGate(object, path);
            case "open_known_passage" -> openKnownPassage(object, path);
            case "inspect_known_container" -> inspectKnownContainer(object, path);
            case "take_known_container_stack" -> takeKnownContainerStack(object, path);
            case "store_known_container_stack" -> storeKnownContainerStack(object, path);
            case "craft_known_recipe" -> craftKnownRecipe(object, path);
            case "smelt_known_recipe" -> smeltKnownRecipe(object, path);
            case "operate_known_menu" -> operateKnownMenu(object, path);
            case "brew_known_potion_batch" -> brewKnownPotionBatch(object, path);
            case "collect_visible_item" -> collectVisibleItem(object, path);
            case "collect_visible_item_batch" -> collectVisibleItemBatch(object, path);
            case "cast_known_fishing_rod" -> castKnownFishingRod(object, path);
            case "reel_known_fishing_session" -> reelKnownFishingSession(object, path);
            case "operate_kill_zone" -> operateKillZone(object, path);
            case "wait_ticks" -> waitTicks(object, path);
            case "wait_until" -> waitUntil(object, path);
            case "if" -> conditional(object, path);
            case "repeat" -> repeat(object, path);
            default -> throw invalid("Unknown Action DSL opcode at " + path + ": " + operation);
        };
    }

    private static ActionDsl.NavigateToKnown navigate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target", "tolerance"),
                Set.of("id", "op", "target", "tolerance"));
        return new ActionDsl.NavigateToKnown(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                number(source.get("tolerance"), path + ".tolerance"));
    }

    private static ActionDsl.ApproachKnownSurface approachKnownSurface(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target", "expected_block"),
                Set.of("id", "op", "target", "expected_block"));
        return new ActionDsl.ApproachKnownSurface(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"));
    }

    private static ActionDsl.ApproachKnownPlacement approachKnownPlacement(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "anchor", "transform", "entries"),
                Set.of("id", "op", "anchor", "transform", "entries"));
        JsonArray values = array(source.get("entries"), path + ".entries");
        var entries = new ArrayList<ActionDsl.BlockPlanEntry>(values.size());
        for (int index = 0; index < values.size(); index++) {
            entries.add(blockPlanEntry(values.get(index), path + ".entries[" + index + "]"));
        }
        return new ActionDsl.ApproachKnownPlacement(
                string(source.get("id"), path + ".id"),
                position(source.get("anchor"), path + ".anchor"),
                blockPlanTransform(source.get("transform"), path + ".transform"),
                entries);
    }

    private static ActionDsl.FaceKnownPosition face(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target"), Set.of("id", "op", "target"));
        return new ActionDsl.FaceKnownPosition(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"));
    }

    private static ActionDsl.FaceKnownBlockFace faceKnownBlockFace(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "face", "expected_block"),
                Set.of("id", "op", "target", "face", "expected_block"));
        return new ActionDsl.FaceKnownBlockFace(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                string(source.get("expected_block"), path + ".expected_block"));
    }

    private static ActionDsl.BreakKnownFace breakKnownFace(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "face", "expected_block", "tool_item"),
                Set.of("id", "op", "target", "face", "expected_block", "tool_item"));
        return new ActionDsl.BreakKnownFace(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("tool_item"), path + ".tool_item"));
    }

    private static ActionDsl.BreakKnownBlock breakKnownBlock(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "face", "expected_state", "tool_item",
                        "expected_drop", "minimum_inventory_count"),
                Set.of("id", "op", "target", "face", "expected_state", "tool_item",
                        "expected_drop", "minimum_inventory_count"));
        return new ActionDsl.BreakKnownBlock(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                blockStateSpec(source.get("expected_state"), path + ".expected_state"),
                string(source.get("tool_item"), path + ".tool_item"),
                string(source.get("expected_drop"), path + ".expected_drop"),
                integer(source.get("minimum_inventory_count"),
                        path + ".minimum_inventory_count"));
    }

    private static ActionDsl.OperateKnownCobblestoneGenerator
            operateKnownCobblestoneGenerator(JsonObject source, String path) {
        Set<String> fields = Set.of(
                "id", "op", "target", "face", "expected_state", "tool_item",
                "expected_drop", "minimum_inventory_count", "max_breaks",
                "regeneration_wait_ticks", "max_operation_duration_ticks");
        exactKeys(source, path, fields, fields);
        return new ActionDsl.OperateKnownCobblestoneGenerator(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                blockStateSpec(source.get("expected_state"), path + ".expected_state"),
                string(source.get("tool_item"), path + ".tool_item"),
                string(source.get("expected_drop"), path + ".expected_drop"),
                integer(source.get("minimum_inventory_count"),
                        path + ".minimum_inventory_count"),
                integer(source.get("max_breaks"), path + ".max_breaks"),
                integer(source.get("regeneration_wait_ticks"),
                        path + ".regeneration_wait_ticks"),
                longInteger(source.get("max_operation_duration_ticks"),
                        path + ".max_operation_duration_ticks"));
    }

    private static ActionDsl.TillKnownBlock tillKnownBlock(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "expected_block", "hoe_item"),
                Set.of("id", "op", "target", "expected_block", "hoe_item"));
        return new ActionDsl.TillKnownBlock(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("hoe_item"), path + ".hoe_item"));
    }

    private static ActionDsl.TillKnownBatch tillKnownBatch(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "targets", "expected_block", "hoe_item"),
                Set.of("id", "op", "targets", "expected_block", "hoe_item"));
        return new ActionDsl.TillKnownBatch(
                string(source.get("id"), path + ".id"),
                positions(source.get("targets"), path + ".targets"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("hoe_item"), path + ".hoe_item"));
    }

    private static ActionDsl.PlantKnownWheat plantKnownWheat(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "support", "seed_item"),
                Set.of("id", "op", "target", "support", "seed_item"));
        return new ActionDsl.PlantKnownWheat(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                position(source.get("support"), path + ".support"),
                string(source.get("seed_item"), path + ".seed_item"));
    }

    private static ActionDsl.PlantKnownWheatBatch plantKnownWheatBatch(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "targets", "seed_item"),
                Set.of("id", "op", "targets", "seed_item"));
        JsonArray values = array(source.get("targets"), path + ".targets");
        var targets = new ArrayList<ActionDsl.PlantPlot>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String targetPath = path + ".targets[" + index + "]";
            JsonObject target = object(values.get(index), targetPath,
                    Set.of("target", "support"), Set.of("target", "support"));
            targets.add(new ActionDsl.PlantPlot(
                    position(target.get("target"), targetPath + ".target"),
                    position(target.get("support"), targetPath + ".support")));
        }
        return new ActionDsl.PlantKnownWheatBatch(
                string(source.get("id"), path + ".id"),
                targets,
                string(source.get("seed_item"), path + ".seed_item"));
    }

    private static ActionDsl.HarvestKnownWheat harvestKnownWheat(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target"), Set.of("id", "op", "target"));
        return new ActionDsl.HarvestKnownWheat(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"));
    }

    private static ActionDsl.HarvestKnownWheatBatch harvestKnownWheatBatch(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "targets"),
                Set.of("id", "op", "targets"));
        return new ActionDsl.HarvestKnownWheatBatch(
                string(source.get("id"), path + ".id"),
                positions(source.get("targets"), path + ".targets"));
    }

    private static ActionDsl.ApplyKnownBlockPlan applyKnownBlockPlan(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "anchor", "transform", "entries"),
                Set.of("id", "op", "anchor", "transform", "entries"));
        JsonArray values = array(source.get("entries"), path + ".entries");
        var entries = new ArrayList<ActionDsl.BlockPlanEntry>(values.size());
        for (int index = 0; index < values.size(); index++) {
            entries.add(blockPlanEntry(values.get(index), path + ".entries[" + index + "]"));
        }
        return new ActionDsl.ApplyKnownBlockPlan(
                string(source.get("id"), path + ".id"),
                position(source.get("anchor"), path + ".anchor"),
                blockPlanTransform(source.get("transform"), path + ".transform"),
                entries);
    }

    private static ActionDsl.ClearKnownBlockPlan clearKnownBlockPlan(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "anchor", "transform", "entries"),
                Set.of("id", "op", "anchor", "transform", "entries"));
        JsonArray values = array(source.get("entries"), path + ".entries");
        var entries = new ArrayList<ActionDsl.ClearBlockPlanEntry>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String entryPath = path + ".entries[" + index + "]";
            JsonObject entry = object(values.get(index), entryPath,
                    Set.of("id", "offset", "expected_before"),
                    Set.of("id", "offset", "expected_before"));
            entries.add(new ActionDsl.ClearBlockPlanEntry(
                    string(entry.get("id"), entryPath + ".id"),
                    offset(entry.get("offset"), entryPath + ".offset"),
                    blockStateSpec(entry.get("expected_before"),
                            entryPath + ".expected_before")));
        }
        return new ActionDsl.ClearKnownBlockPlan(
                string(source.get("id"), path + ".id"),
                position(source.get("anchor"), path + ".anchor"),
                blockPlanTransform(source.get("transform"), path + ".transform"),
                entries);
    }

    private static ActionDsl.PillarUpKnown pillarUpKnown(JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "support", "expected_support",
                        "source_state", "item", "placement_state_ref"),
                Set.of("id", "op", "support", "expected_support"));
        boolean hasSourceState = source.has("source_state");
        boolean hasItem = source.has("item");
        boolean hasPlacementStateRef = source.has("placement_state_ref");
        if (hasSourceState != hasItem || hasSourceState == hasPlacementStateRef) {
            throw invalid(path
                    + " must select source_state plus item or placement_state_ref");
        }
        return new ActionDsl.PillarUpKnown(
                string(source.get("id"), path + ".id"),
                position(source.get("support"), path + ".support"),
                blockStateSpec(source.get("expected_support"), path + ".expected_support"),
                hasSourceState
                        ? Optional.of(blockStateSpec(
                                source.get("source_state"), path + ".source_state"))
                        : Optional.empty(),
                hasItem
                        ? Optional.of(string(source.get("item"), path + ".item"))
                        : Optional.empty(),
                hasPlacementStateRef
                        ? Optional.of(string(
                                source.get("placement_state_ref"),
                                path + ".placement_state_ref"))
                        : Optional.empty());
    }

    private static ActionDsl.BlockPlanTransform blockPlanTransform(
            JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("rotation", "mirror"),
                Set.of("rotation", "mirror"));
        return new ActionDsl.BlockPlanTransform(
                blockPlanRotation(integer(object.get("rotation"), path + ".rotation"),
                        path + ".rotation"),
                blockPlanMirror(string(object.get("mirror"), path + ".mirror"),
                        path + ".mirror"));
    }

    private static ActionDsl.BlockPlanEntry blockPlanEntry(JsonElement value, String path) {
        JsonObject object = object(value, path,
                Set.of("id", "offset", "source_state", "item", "placement_state_ref", "support"),
                Set.of("id", "offset", "support"));
        boolean hasSourceState = object.has("source_state");
        boolean hasItem = object.has("item");
        boolean hasPlacementStateRef = object.has("placement_state_ref");
        if (hasSourceState != hasItem || hasSourceState == hasPlacementStateRef) {
            throw invalid(path
                    + " must select source_state plus item or placement_state_ref");
        }
        return new ActionDsl.BlockPlanEntry(
                string(object.get("id"), path + ".id"),
                offset(object.get("offset"), path + ".offset"),
                hasSourceState
                        ? Optional.of(blockStateSpec(
                                object.get("source_state"), path + ".source_state"))
                        : Optional.empty(),
                hasItem
                        ? Optional.of(string(object.get("item"), path + ".item"))
                        : Optional.empty(),
                hasPlacementStateRef
                        ? Optional.of(string(
                                object.get("placement_state_ref"),
                                path + ".placement_state_ref"))
                        : Optional.empty(),
                placementSupport(object.get("support"), path + ".support"));
    }

    private static ActionDsl.Offset offset(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("x", "y", "z"),
                Set.of("x", "y", "z"));
        return new ActionDsl.Offset(
                integer(object.get("x"), path + ".x"),
                integer(object.get("y"), path + ".y"),
                integer(object.get("z"), path + ".z"));
    }

    private static ActionDsl.BlockStateSpec blockStateSpec(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("block", "properties"),
                Set.of("block", "properties"));
        JsonObject properties = rawObject(object.get("properties"), path + ".properties");
        var parsed = new LinkedHashMap<String, String>();
        for (var property : properties.entrySet()) {
            JsonElement propertyValue = property.getValue();
            if (propertyValue == null || !propertyValue.isJsonPrimitive()
                    || !propertyValue.getAsJsonPrimitive().isString()) {
                throw invalid(path + ".properties must contain only string values");
            }
            parsed.put(property.getKey(), propertyValue.getAsString());
        }
        return new ActionDsl.BlockStateSpec(
                string(object.get("block"), path + ".block"), parsed);
    }

    private static ActionDsl.PlacementSupport placementSupport(
            JsonElement value, String path) {
        JsonObject object = object(value, path,
                Set.of("position", "face", "expected_state", "dependency_entry_id"),
                Set.of("position", "face", "expected_state", "dependency_entry_id"));
        return new ActionDsl.PlacementSupport(
                position(object.get("position"), path + ".position"),
                blockFace(string(object.get("face"), path + ".face"), path + ".face"),
                nullableBlockStateSpec(object.get("expected_state"), path + ".expected_state"),
                nullableString(object.get("dependency_entry_id"),
                        path + ".dependency_entry_id"));
    }

    private static Optional<ActionDsl.BlockStateSpec> nullableBlockStateSpec(
            JsonElement value, String path) {
        if (value == null || value.isJsonNull()) return Optional.empty();
        return Optional.of(blockStateSpec(value, path));
    }

    private static Optional<String> nullableString(JsonElement value, String path) {
        if (value == null || value.isJsonNull()) return Optional.empty();
        return Optional.of(string(value, path));
    }

    private static ActionDsl.ApplyKnownRedstoneSpec applyKnownRedstoneSpec(
            JsonObject source, String path) {
        Set<String> fields = Set.of(
                "id", "op", "anchor", "rotation", "components",
                "truth_table", "footprint", "timing");
        exactKeys(source, path, fields, fields);
        JsonArray componentValues = array(source.get("components"), path + ".components");
        var components = new ArrayList<RedstoneSpec.Component>(componentValues.size());
        for (int index = 0; index < componentValues.size(); index++) {
            components.add(redstoneComponent(
                    componentValues.get(index), path + ".components[" + index + "]"));
        }
        JsonArray rowValues = array(source.get("truth_table"), path + ".truth_table");
        var truthTable = new ArrayList<RedstoneSpec.TruthRow>(rowValues.size());
        for (int index = 0; index < rowValues.size(); index++) {
            truthTable.add(redstoneTruthRow(
                    rowValues.get(index), path + ".truth_table[" + index + "]"));
        }
        return new ActionDsl.ApplyKnownRedstoneSpec(
                string(source.get("id"), path + ".id"),
                position(source.get("anchor"), path + ".anchor"),
                integer(source.get("rotation"), path + ".rotation"),
                components,
                truthTable,
                redstoneFootprint(source.get("footprint"), path + ".footprint"),
                redstoneTiming(source.get("timing"), path + ".timing"));
    }

    private static RedstoneSpec.Component redstoneComponent(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("id", "role", "block"),
                Set.of("id", "role", "block"));
        String role = string(object.get("role"), path + ".role");
        RedstoneSpec.Role parsedRole = switch (role) {
            case "input" -> RedstoneSpec.Role.INPUT;
            case "output" -> RedstoneSpec.Role.OUTPUT;
            case "wire" -> RedstoneSpec.Role.WIRE;
            default -> throw invalid(path + ".role must be input, output, or wire");
        };
        return new RedstoneSpec.Component(
                string(object.get("id"), path + ".id"),
                parsedRole,
                string(object.get("block"), path + ".block"));
    }

    private static RedstoneSpec.TruthRow redstoneTruthRow(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("inputs", "outputs"),
                Set.of("inputs", "outputs"));
        JsonObject inputs = object(object.get("inputs"), path + ".inputs",
                Set.of("input"), Set.of("input"));
        JsonObject outputs = object(object.get("outputs"), path + ".outputs",
                Set.of("output", "output_2"), Set.of("output"));
        var parsedOutputs = new LinkedHashMap<String, Boolean>();
        parsedOutputs.put("output", bool(outputs.get("output"), path + ".outputs.output"));
        if (outputs.has("output_2")) {
            parsedOutputs.put(
                    "output_2", bool(outputs.get("output_2"), path + ".outputs.output_2"));
        }
        return new RedstoneSpec.TruthRow(
                java.util.Map.of("input", bool(inputs.get("input"), path + ".inputs.input")),
                parsedOutputs);
    }

    private static RedstoneSpec.Footprint redstoneFootprint(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("x", "y", "z"),
                Set.of("x", "y", "z"));
        return new RedstoneSpec.Footprint(
                integer(object.get("x"), path + ".x"),
                integer(object.get("y"), path + ".y"),
                integer(object.get("z"), path + ".z"));
    }

    private static ActionDsl.RedstoneTiming redstoneTiming(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("settle_ticks"), Set.of("settle_ticks"));
        return new ActionDsl.RedstoneTiming(
                integer(object.get("settle_ticks"), path + ".settle_ticks"));
    }

    private static ActionDsl.OpenKnownFenceGate openKnownFenceGate(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target"), Set.of("id", "op", "target"));
        return new ActionDsl.OpenKnownFenceGate(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"));
    }

    private static ActionDsl.OpenKnownPassage openKnownPassage(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "target", "expected_block"),
                Set.of("id", "op", "target", "expected_block"));
        return new ActionDsl.OpenKnownPassage(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"));
    }

    private static ActionDsl.InspectKnownContainer inspectKnownContainer(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "expected_block", "routing_label"),
                Set.of("id", "op", "target", "expected_block"));
        return new ActionDsl.InspectKnownContainer(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"),
                routingLabel(source, path));
    }

    private static ActionDsl.TakeKnownContainerStack takeKnownContainerStack(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "expected_block", "item",
                        "stack_policy", "minimum_inventory_count", "routing_label"),
                Set.of("id", "op", "target", "expected_block", "item",
                        "stack_policy", "minimum_inventory_count"));
        return new ActionDsl.TakeKnownContainerStack(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("item"), path + ".item"),
                string(source.get("stack_policy"), path + ".stack_policy"),
                integer(source.get("minimum_inventory_count"),
                        path + ".minimum_inventory_count"),
                routingLabel(source, path));
    }

    private static ActionDsl.StoreKnownContainerStack storeKnownContainerStack(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "expected_block", "item",
                        "stack_policy", "minimum_container_count", "routing_label"),
                Set.of("id", "op", "target", "expected_block", "item",
                        "stack_policy", "minimum_container_count"));
        return new ActionDsl.StoreKnownContainerStack(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"),
                string(source.get("item"), path + ".item"),
                string(source.get("stack_policy"), path + ".stack_policy"),
                integer(source.get("minimum_container_count"),
                        path + ".minimum_container_count"),
                routingLabel(source, path));
    }

    private static Optional<ActionDsl.RoutingLabel> routingLabel(
            JsonObject source, String path) {
        if (!source.has("routing_label")) return Optional.empty();
        JsonObject label = object(
                source.get("routing_label"), path + ".routing_label",
                Set.of("entity_ref", "item"), Set.of("entity_ref", "item"));
        return Optional.of(new ActionDsl.RoutingLabel(
                string(label.get("entity_ref"), path + ".routing_label.entity_ref"),
                string(label.get("item"), path + ".routing_label.item")));
    }

    private static ActionDsl.CraftKnownRecipe craftKnownRecipe(
            JsonObject source, String path) {
        Set<String> fields = Set.of(
                "id", "op", "recipe_ref", "recipe_fingerprint",
                "goal", "station", "max_crafts");
        exactKeys(source, path, fields, fields);
        JsonObject goal = object(source.get("goal"), path + ".goal",
                Set.of("item", "stack_policy", "minimum_inventory_count"),
                Set.of("item", "stack_policy", "minimum_inventory_count"));
        JsonObject station = object(source.get("station"), path + ".station",
                Set.of("kind", "target", "expected_state"),
                Set.of("kind", "target", "expected_state"));
        return new ActionDsl.CraftKnownRecipe(
                string(source.get("id"), path + ".id"),
                string(source.get("recipe_ref"), path + ".recipe_ref"),
                string(source.get("recipe_fingerprint"), path + ".recipe_fingerprint"),
                string(goal.get("item"), path + ".goal.item"),
                string(goal.get("stack_policy"), path + ".goal.stack_policy"),
                integer(goal.get("minimum_inventory_count"),
                        path + ".goal.minimum_inventory_count"),
                string(station.get("kind"), path + ".station.kind"),
                position(station.get("target"), path + ".station.target"),
                blockStateSpec(station.get("expected_state"),
                        path + ".station.expected_state"),
                integer(source.get("max_crafts"), path + ".max_crafts"));
    }

    private static ActionDsl.SmeltKnownRecipe smeltKnownRecipe(
            JsonObject source, String path) {
        Set<String> fields = Set.of(
                "id", "op", "recipe_ref", "recipe_fingerprint",
                "goal", "station", "fuel", "max_smelts");
        exactKeys(source, path, fields, fields);
        JsonObject goal = object(source.get("goal"), path + ".goal",
                Set.of("item", "stack_policy", "minimum_inventory_count"),
                Set.of("item", "stack_policy", "minimum_inventory_count"));
        JsonObject station = object(source.get("station"), path + ".station",
                Set.of("kind", "target", "expected_state"),
                Set.of("kind", "target", "expected_state"));
        JsonObject fuel = object(source.get("fuel"), path + ".fuel",
                Set.of("item", "stack_policy"), Set.of("item", "stack_policy"));
        return new ActionDsl.SmeltKnownRecipe(
                string(source.get("id"), path + ".id"),
                string(source.get("recipe_ref"), path + ".recipe_ref"),
                string(source.get("recipe_fingerprint"), path + ".recipe_fingerprint"),
                string(goal.get("item"), path + ".goal.item"),
                string(goal.get("stack_policy"), path + ".goal.stack_policy"),
                integer(goal.get("minimum_inventory_count"),
                        path + ".goal.minimum_inventory_count"),
                string(station.get("kind"), path + ".station.kind"),
                position(station.get("target"), path + ".station.target"),
                blockStateSpec(station.get("expected_state"),
                        path + ".station.expected_state"),
                string(fuel.get("item"), path + ".fuel.item"),
                string(fuel.get("stack_policy"), path + ".fuel.stack_policy"),
                integer(source.get("max_smelts"), path + ".max_smelts"));
    }

    private static ActionDsl.OperateKnownMenu operateKnownMenu(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "operation_ref"),
                Set.of("id", "op", "operation_ref"));
        return new ActionDsl.OperateKnownMenu(
                string(source.get("id"), path + ".id"),
                string(source.get("operation_ref"), path + ".operation_ref"));
    }

    private static ActionDsl.BrewKnownPotionBatch brewKnownPotionBatch(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "target", "expected_block", "input",
                        "ingredient_item", "fuel_item", "expected_output"),
                Set.of("id", "op", "target", "expected_block", "input",
                        "ingredient_item", "fuel_item", "expected_output"));
        return new ActionDsl.BrewKnownPotionBatch(
                string(source.get("id"), path + ".id"),
                position(source.get("target"), path + ".target"),
                string(source.get("expected_block"), path + ".expected_block"),
                standardPotionStack(source.get("input"), path + ".input"),
                string(source.get("ingredient_item"), path + ".ingredient_item"),
                string(source.get("fuel_item"), path + ".fuel_item"),
                standardPotionStack(
                        source.get("expected_output"), path + ".expected_output"));
    }

    private static StandardPotionStackSpec standardPotionStack(
            JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("item", "potion", "count"),
                Set.of("item", "potion", "count"));
        try {
            return new StandardPotionStackSpec(
                    string(object.get("item"), path + ".item"),
                    string(object.get("potion"), path + ".potion"),
                    integer(object.get("count"), path + ".count"));
        } catch (IllegalArgumentException failure) {
            throw new ActionDslException(
                    INVALID_ARGUMENT, path + " is not a standard potion batch", failure);
        }
    }

    private static ActionDsl.CollectVisibleItem collectVisibleItem(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "displayed_item", "target"),
                Set.of("id", "op", "displayed_item", "target"));
        return new ActionDsl.CollectVisibleItem(
                string(source.get("id"), path + ".id"),
                string(source.get("displayed_item"), path + ".displayed_item"),
                worldPosition(source.get("target"), path + ".target"));
    }

    private static ActionDsl.CollectVisibleItemBatch collectVisibleItemBatch(
            JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "targets"),
                Set.of("id", "op", "targets"));
        String batchId = string(source.get("id"), path + ".id");
        JsonArray values = array(source.get("targets"), path + ".targets");
        var targets = new ArrayList<ActionDsl.CollectTarget>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String targetPath = path + ".targets[" + index + "]";
            JsonObject target = object(values.get(index), targetPath,
                    Set.of("displayed_item", "target"),
                    Set.of("displayed_item", "target"));
            targets.add(new ActionDsl.CollectTarget(
                    string(target.get("displayed_item"), targetPath + ".displayed_item"),
                    worldPosition(target.get("target"), targetPath + ".target")));
        }
        return new ActionDsl.CollectVisibleItemBatch(batchId, targets);
    }

    private static ActionDsl.WaitTicks waitTicks(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "ticks"), Set.of("id", "op", "ticks"));
        return new ActionDsl.WaitTicks(
                string(source.get("id"), path + ".id"),
                integer(source.get("ticks"), path + ".ticks"));
    }

    private static ActionDsl.CastKnownFishingRod castKnownFishingRod(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "hand", "rod_item", "target", "face", "expected_state"),
                Set.of("id", "op", "hand", "rod_item", "target", "face", "expected_state"));
        return new ActionDsl.CastKnownFishingRod(
                string(source.get("id"), path + ".id"),
                string(source.get("hand"), path + ".hand"),
                string(source.get("rod_item"), path + ".rod_item"),
                position(source.get("target"), path + ".target"),
                blockFace(string(source.get("face"), path + ".face"), path + ".face"),
                blockStateSpec(source.get("expected_state"), path + ".expected_state"));
    }

    private static ActionDsl.ReelKnownFishingSession reelKnownFishingSession(
            JsonObject source, String path) {
        exactKeys(source, path,
                Set.of("id", "op", "fishing_session_ref", "hand", "rod_item"),
                Set.of("id", "op", "fishing_session_ref", "hand", "rod_item"));
        return new ActionDsl.ReelKnownFishingSession(
                string(source.get("id"), path + ".id"),
                string(source.get("fishing_session_ref"), path + ".fishing_session_ref"),
                string(source.get("hand"), path + ".hand"),
                string(source.get("rod_item"), path + ".rod_item"));
    }

    private static ActionDsl.OperateKillZone operateKillZone(
            JsonObject source, String path) {
        Set<String> fields = Set.of(
                "id", "op", "target_kill_zone_bounds", "entity_type_allowlist",
                "main_hand_item", "consent_ref", "max_attacks",
                "minimum_interval_ticks", "max_operation_duration_ticks");
        exactKeys(source, path, fields, fields);
        JsonArray rawTypes = array(
                source.get("entity_type_allowlist"), path + ".entity_type_allowlist");
        var types = new ArrayList<String>(rawTypes.size());
        for (int index = 0; index < rawTypes.size(); index++) {
            types.add(string(rawTypes.get(index),
                    path + ".entity_type_allowlist[" + index + "]"));
        }
        JsonElement rawConsent = source.get("consent_ref");
        Optional<String> consent = rawConsent == null || rawConsent.isJsonNull()
                ? Optional.empty()
                : Optional.of(string(rawConsent, path + ".consent_ref"));
        return new ActionDsl.OperateKillZone(
                string(source.get("id"), path + ".id"),
                worldBounds(source.get("target_kill_zone_bounds"),
                        path + ".target_kill_zone_bounds"),
                types,
                string(source.get("main_hand_item"), path + ".main_hand_item"),
                consent,
                integer(source.get("max_attacks"), path + ".max_attacks"),
                longInteger(source.get("minimum_interval_ticks"),
                        path + ".minimum_interval_ticks"),
                longInteger(source.get("max_operation_duration_ticks"),
                        path + ".max_operation_duration_ticks"));
    }

    private static ActionDsl.WaitUntil waitUntil(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "condition", "max_ticks"),
                Set.of("id", "op", "condition", "max_ticks"));
        return new ActionDsl.WaitUntil(
                string(source.get("id"), path + ".id"),
                waitCondition(source.get("condition"), path + ".condition"),
                integer(source.get("max_ticks"), path + ".max_ticks"));
    }

    private static ActionDsl.WaitCondition waitCondition(
            JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        String type = string(required(object, "type", path), path + ".type");
        if ("crop_mature".equals(type)) {
            exactKeys(object, path, Set.of("type", "target"), Set.of("type", "target"));
            return new ActionDsl.CropMatureCondition(
                    position(object.get("target"), path + ".target"));
        }
        if ("sound_clue".equals(type)) {
            exactKeys(object, path,
                    Set.of("type", "sound_event", "since_tick", "bounds"),
                    Set.of("type", "sound_event", "since_tick", "bounds"));
            if (!"minecraft:entity.fishing_bobber.splash".equals(
                    string(object.get("sound_event"), path + ".sound_event"))) {
                throw invalid(path + ".sound_event is unsupported");
            }
            return new ActionDsl.SoundClueCondition(
                    "minecraft:entity.fishing_bobber.splash",
                    longInteger(object.get("since_tick"), path + ".since_tick"),
                    worldBounds(object.get("bounds"), path + ".bounds"));
        }
        throw invalid("Unsupported wait condition type at " + path + ": " + type);
    }

    private static ActionDsl.WorldBounds worldBounds(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("dimension", "min", "max"),
                Set.of("dimension", "min", "max"));
        return new ActionDsl.WorldBounds(
                string(object.get("dimension"), path + ".dimension"),
                worldPoint(object.get("min"), path + ".min"),
                worldPoint(object.get("max"), path + ".max"));
    }

    private static ActionDsl.WorldPoint worldPoint(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("x", "y", "z"), Set.of("x", "y", "z"));
        return new ActionDsl.WorldPoint(
                number(object.get("x"), path + ".x"),
                number(object.get("y"), path + ".y"),
                number(object.get("z"), path + ".z"));
    }

    private static ActionDsl.CropMatureCondition cropMatureCondition(
            JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("type", "target"),
                Set.of("type", "target"));
        String type = string(object.get("type"), path + ".type");
        if (!"crop_mature".equals(type)) {
            throw invalid("Unsupported wait condition type at " + path + ": " + type);
        }
        return new ActionDsl.CropMatureCondition(
                position(object.get("target"), path + ".target"));
    }

    private static ActionDsl.If conditional(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "condition", "then", "else"),
                Set.of("id", "op", "condition", "then", "else"));
        return new ActionDsl.If(
                string(source.get("id"), path + ".id"),
                predicate(source.get("condition"), path + ".condition"),
                nodes(source.get("then"), path + ".then"),
                nodes(source.get("else"), path + ".else"));
    }

    private static ActionDsl.Repeat repeat(JsonObject source, String path) {
        exactKeys(source, path, Set.of("id", "op", "count", "body"),
                Set.of("id", "op", "count", "body"));
        return new ActionDsl.Repeat(
                string(source.get("id"), path + ".id"),
                integer(source.get("count"), path + ".count"),
                nodes(source.get("body"), path + ".body"));
    }

    private static ActionDsl.Position position(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("dimension", "x", "y", "z"),
                Set.of("dimension", "x", "y", "z"));
        return new ActionDsl.Position(
                string(object.get("dimension"), path + ".dimension"),
                integer(object.get("x"), path + ".x"),
                integer(object.get("y"), path + ".y"),
                integer(object.get("z"), path + ".z"));
    }

    private static List<ActionDsl.Position> positions(JsonElement value, String path) {
        JsonArray values = array(value, path);
        var positions = new ArrayList<ActionDsl.Position>(values.size());
        for (int index = 0; index < values.size(); index++) {
            positions.add(position(values.get(index), path + "[" + index + "]"));
        }
        return List.copyOf(positions);
    }

    private static ActionDsl.WorldPosition worldPosition(JsonElement value, String path) {
        JsonObject object = object(value, path, Set.of("dimension", "x", "y", "z"),
                Set.of("dimension", "x", "y", "z"));
        return new ActionDsl.WorldPosition(
                string(object.get("dimension"), path + ".dimension"),
                number(object.get("x"), path + ".x"),
                number(object.get("y"), path + ".y"),
                number(object.get("z"), path + ".z"));
    }

    private static ActionDsl.Predicate predicate(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        boolean hasAll = object.has("all");
        boolean hasAny = object.has("any");
        if (hasAll || hasAny) {
            if (hasAll == hasAny) {
                throw invalid(path + " must contain exactly one of all or any");
            }
            String key = hasAll ? "all" : "any";
            exactKeys(object, path, Set.of(key), Set.of(key));
            JsonArray values = array(object.get(key), path + "." + key);
            var operands = new ArrayList<ActionDsl.AtomicPredicate>(values.size());
            for (int index = 0; index < values.size(); index++) {
                operands.add(atomicPredicate(values.get(index), path + "." + key + "[" + index + "]"));
            }
            return new ActionDsl.LogicalPredicate(
                    hasAll ? ActionDsl.LogicalOperator.ALL : ActionDsl.LogicalOperator.ANY,
                    operands);
        }
        return atomicPredicate(object, path);
    }

    private static ActionDsl.AtomicPredicate atomicPredicate(JsonElement value, String path) {
        JsonObject object = rawObject(value, path);
        String field = string(required(object, "field", path), path + ".field");
        return switch (field) {
            case "health", "hunger", "air" -> numericPredicate(object, field, path);
            case "on_fire", "submerged" -> booleanPredicate(object, field, path);
            case "inventory_count" -> inventoryPredicate(object, path);
            case "has_status_effect" -> statusPredicate(object, path);
            default -> throw invalid("Unsupported snapshot predicate field at " + path + ": " + field);
        };
    }

    private static ActionDsl.NumericPredicate numericPredicate(
            JsonObject source, String field, String path) {
        exactKeys(source, path, Set.of("field", "comparison", "value"),
                Set.of("field", "comparison", "value"));
        return new ActionDsl.NumericPredicate(
                numericField(field),
                comparison(string(source.get("comparison"), path + ".comparison"), path),
                number(source.get("value"), path + ".value"));
    }

    private static ActionDsl.BooleanPredicate booleanPredicate(
            JsonObject source, String field, String path) {
        exactKeys(source, path, Set.of("field", "comparison", "value"),
                Set.of("field", "comparison", "value"));
        ActionDsl.Comparison comparison = comparison(
                string(source.get("comparison"), path + ".comparison"), path);
        if (comparison != ActionDsl.Comparison.EQ) {
            throw invalid(path + ".comparison must be eq");
        }
        return new ActionDsl.BooleanPredicate(
                booleanField(field), comparison, bool(source.get("value"), path + ".value"));
    }

    private static ActionDsl.InventoryPredicate inventoryPredicate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("field", "item", "comparison", "value"),
                Set.of("field", "item", "comparison", "value"));
        return new ActionDsl.InventoryPredicate(
                string(source.get("item"), path + ".item"),
                comparison(string(source.get("comparison"), path + ".comparison"), path),
                integer(source.get("value"), path + ".value"));
    }

    private static ActionDsl.StatusPredicate statusPredicate(JsonObject source, String path) {
        exactKeys(source, path, Set.of("field", "effect", "comparison", "value"),
                Set.of("field", "effect", "comparison", "value"));
        ActionDsl.Comparison comparison = comparison(
                string(source.get("comparison"), path + ".comparison"), path);
        if (comparison != ActionDsl.Comparison.EQ) {
            throw invalid(path + ".comparison must be eq");
        }
        return new ActionDsl.StatusPredicate(
                string(source.get("effect"), path + ".effect"),
                comparison,
                bool(source.get("value"), path + ".value"));
    }

    private static ActionDsl.Capability capability(String value, String path) {
        return switch (value) {
            case "movement" -> ActionDsl.Capability.MOVEMENT;
            case "camera" -> ActionDsl.Capability.CAMERA;
            case "block_break" -> ActionDsl.Capability.BLOCK_BREAK;
            case "block_interact" -> ActionDsl.Capability.BLOCK_INTERACT;
            case "block_place" -> ActionDsl.Capability.BLOCK_PLACE;
            case "inventory_transfer" -> ActionDsl.Capability.INVENTORY_TRANSFER;
            case "item_use" -> ActionDsl.Capability.ITEM_USE;
            case "entity_attack" -> ActionDsl.Capability.ENTITY_ATTACK;
            default -> throw invalid("Unsupported capability at " + path + ": " + value);
        };
    }

    private static ActionDsl.BlockFace blockFace(String value, String path) {
        return switch (value) {
            case "down" -> ActionDsl.BlockFace.DOWN;
            case "up" -> ActionDsl.BlockFace.UP;
            case "north" -> ActionDsl.BlockFace.NORTH;
            case "south" -> ActionDsl.BlockFace.SOUTH;
            case "west" -> ActionDsl.BlockFace.WEST;
            case "east" -> ActionDsl.BlockFace.EAST;
            default -> throw invalid("Unsupported block face at " + path + ": " + value);
        };
    }

    private static ActionDsl.BlockPlanRotation blockPlanRotation(int value, String path) {
        return switch (value) {
            case 0 -> ActionDsl.BlockPlanRotation.DEGREES_0;
            case 90 -> ActionDsl.BlockPlanRotation.DEGREES_90;
            case 180 -> ActionDsl.BlockPlanRotation.DEGREES_180;
            case 270 -> ActionDsl.BlockPlanRotation.DEGREES_270;
            default -> throw invalid(path + " must be 0, 90, 180, or 270");
        };
    }

    private static ActionDsl.BlockPlanMirror blockPlanMirror(String value, String path) {
        return switch (value) {
            case "none" -> ActionDsl.BlockPlanMirror.NONE;
            case "x" -> ActionDsl.BlockPlanMirror.X;
            case "z" -> ActionDsl.BlockPlanMirror.Z;
            default -> throw invalid(path + " must be none, x, or z");
        };
    }

    private static ActionDsl.NumericField numericField(String value) {
        return switch (value) {
            case "health" -> ActionDsl.NumericField.HEALTH;
            case "hunger" -> ActionDsl.NumericField.HUNGER;
            case "air" -> ActionDsl.NumericField.AIR;
            default -> throw new AssertionError(value);
        };
    }

    private static ActionDsl.BooleanField booleanField(String value) {
        return switch (value) {
            case "on_fire" -> ActionDsl.BooleanField.ON_FIRE;
            case "submerged" -> ActionDsl.BooleanField.SUBMERGED;
            default -> throw new AssertionError(value);
        };
    }

    private static ActionDsl.Comparison comparison(String value, String path) {
        return switch (value) {
            case "lt" -> ActionDsl.Comparison.LT;
            case "lte" -> ActionDsl.Comparison.LTE;
            case "eq" -> ActionDsl.Comparison.EQ;
            case "gte" -> ActionDsl.Comparison.GTE;
            case "gt" -> ActionDsl.Comparison.GT;
            default -> throw invalid("Unsupported comparison at " + path + ": " + value);
        };
    }

    private static JsonObject object(
            JsonElement value,
            String path,
            Set<String> allowed,
            Set<String> required) {
        JsonObject result = rawObject(value, path);
        exactKeys(result, path, allowed, required);
        return result;
    }

    private static JsonObject rawObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw invalid(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonElement required(JsonObject object, String key, String path) {
        if (!object.has(key)) {
            throw invalid(path + " is missing required field " + key);
        }
        return object.get(key);
    }

    private static void exactKeys(
            JsonObject object,
            String path,
            Set<String> allowed,
            Set<String> required) {
        var unexpected = new HashSet<>(object.keySet());
        unexpected.removeAll(allowed);
        var missing = new HashSet<>(required);
        missing.removeAll(object.keySet());
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            throw invalid(path + " has unexpected keys " + unexpected + " and missing keys " + missing);
        }
    }

    private static String string(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isString()) {
            throw invalid(path + " must be a string");
        }
        return primitive.getAsString();
    }

    private static boolean bool(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isBoolean()) {
            throw invalid(path + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int integer(JsonElement value, String path) {
        long parsed = longInteger(value, path);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw invalid(path + " is outside the integer range");
        }
        return (int) parsed;
    }

    private static long longInteger(JsonElement value, String path) {
        BigDecimal parsed = decimal(value, path);
        try {
            return parsed.longValueExact();
        } catch (ArithmeticException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, path + " must be an exact integer", failure);
        }
    }

    private static double number(JsonElement value, String path) {
        double parsed = decimal(value, path).doubleValue();
        if (!Double.isFinite(parsed)) {
            throw invalid(path + " must be finite");
        }
        return parsed;
    }

    private static BigDecimal decimal(JsonElement value, String path) {
        JsonPrimitive primitive = primitive(value, path);
        if (!primitive.isNumber()) {
            throw invalid(path + " must be a number");
        }
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException failure) {
            throw new ActionDslException(INVALID_ARGUMENT, path + " must be a finite JSON number", failure);
        }
    }

    private static JsonPrimitive primitive(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive()) {
            throw invalid(path + " must be a scalar value");
        }
        return value.getAsJsonPrimitive();
    }

    private static ActionDslException invalid(String message) {
        return new ActionDslException(INVALID_ARGUMENT, message);
    }
}
