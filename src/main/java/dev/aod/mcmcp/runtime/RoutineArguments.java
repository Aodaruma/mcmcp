package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.ApplyBlockPlanOperation;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.ApplyBlockPlanStep;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.FinitePlanRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** 既存routineコマンドの要求構築と、block/item境界の検証。 */
final class RoutineArguments {
    private RoutineArguments() {}

    record ParsedApplyBlockPlan(
            ApplyBlockPlanRequest request,
            String requestIdentity,
            Map<String, Object> resourceEstimate) {
        ParsedApplyBlockPlan {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
            Objects.requireNonNull(resourceEstimate, "resourceEstimate");
        }
    }

    record ParsedPhaseFive(
            PhaseFiveRequest request,
            String requestIdentity,
            List<BlockTarget> targets) {
        ParsedPhaseFive {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    record ParsedFinitePlan(FinitePlanRequest request, String requestIdentity) {
        ParsedFinitePlan {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(requestIdentity, "requestIdentity");
        }
    }

    record FullStatePair(
            BlockStateFingerprint source,
            BlockStateFingerprint transformed) {
        FullStatePair {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(transformed, "transformed");
        }
    }

    static SemanticActionRequest semanticActionArgument(
            Map<String, Object> arguments,
            WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        return semanticActionArgument(arguments, session.dimension());
    }

    static SemanticActionRequest semanticActionArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        RuntimeArguments.completionIntentArgument(arguments);
        var kind = RuntimeArguments.stringArgument(arguments, "kind");
        var parameters = RuntimeArguments.objectArgument(arguments, "parameters");
        var bounds = actionBoundsArgument(arguments, currentDimension);
        return switch (kind) {
            case NavigateToRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "navigate_to parameters", Set.of(
                        "target", "horizontal_tolerance_blocks"));
                yield new NavigateToRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        RuntimeArguments.doubleArgument(parameters, "horizontal_tolerance_blocks"),
                        bounds);
            }
            case BreakBlockRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "break_block parameters", Set.of(
                        "target", "expected_before", "expected_after"));
                var expectedBefore = blockStateArgument(parameters, "expected_before");
                SafeBreakSourcePolicy.requireRegisteredBlockId(expectedBefore.blockId());
                yield new BreakBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        expectedBefore,
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case PlaceBlockRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "place_block parameters", Set.of(
                        "target", "expected_before", "item", "expected_after"));
                yield new PlaceBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        RuntimeArguments.stringArgument(parameters, "item"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case UseItemOnBlockRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "use_item_on_block parameters", Set.of(
                        "target", "expected_before", "item", "expected_after"));
                yield new UseItemOnBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        RuntimeArguments.stringArgument(parameters, "item"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case InteractBlockRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "interact_block parameters", Set.of(
                        "target", "expected_before", "expected_after"));
                yield new InteractBlockRequest(
                        dimensionBlockTargetArgument(parameters, "target"),
                        blockStateArgument(parameters, "expected_before"),
                        blockStateArgument(parameters, "expected_after"),
                        bounds);
            }
            case InteractEntityRequest.KIND -> {
                RuntimeArguments.requireExactKeys(parameters, "interact_entity parameters", Set.of(
                        "entity_ref", "expected_type", "hand", "held_item", "goal"));
                var goal = RuntimeArguments.objectArgument(parameters, "goal");
                RuntimeArguments.requireExactKeys(goal, "goal", Set.of("item", "minimum_inventory_count"));
                yield new InteractEntityRequest(
                        RuntimeArguments.stringArgument(parameters, "entity_ref"),
                        RuntimeArguments.stringArgument(parameters, "expected_type"),
                        RuntimeArguments.stringArgument(parameters, "hand"),
                        RuntimeArguments.stringArgument(parameters, "held_item"),
                        new StationaryBreakGoal(
                                RuntimeArguments.stringArgument(goal, "item"),
                                RuntimeArguments.intArgument(goal, "minimum_inventory_count")),
                        bounds);
            }
            default -> throw new IllegalArgumentException("kind is not a Phase 3 semantic action");
        };
    }

    static ParsedFinitePlan finitePlanRequestArgument(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        if (!"execute_plan".equals(RuntimeArguments.stringArgument(arguments, "kind"))) {
            throw new IllegalArgumentException("kind must be execute_plan");
        }
        String completionIntent = RuntimeArguments.completionIntentArgument(arguments);
        var outerBounds = RuntimeArguments.objectArgument(arguments, "bounds");
        RuntimeArguments.requireExactKeys(outerBounds, "execute_plan bounds", Set.of());
        var parameters = RuntimeArguments.objectArgument(arguments, "parameters");
        var request = FinitePlanRequest.parse(parameters);
        var canonical = new StringBuilder();
        RoutineIdentity.appendIdentity(canonical, "finite-plan/v1");
        RoutineIdentity.appendCanonicalValue(canonical, parameters);
        RoutineIdentity.appendCanonicalValue(canonical, completionIntent);
        return new ParsedFinitePlan(request, RoutineIdentity.sha256Identity(canonical));
    }

    static ParsedApplyBlockPlan applyBlockPlanArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        if (!ApplyBlockPlanRequest.KIND.equals(RuntimeArguments.stringArgument(arguments, "kind"))) {
            throw new IllegalArgumentException("kind must be apply_block_plan");
        }
        String completionIntent = RuntimeArguments.completionIntentArgument(arguments);

        var parameters = RuntimeArguments.objectArgument(arguments, "parameters");
        RuntimeArguments.requireExactKeys(parameters, "apply_block_plan parameters", Set.of(
                "anchor", "transform", "phase", "entries"));
        var bounds = actionBoundsArgument(arguments, currentDimension);
        var anchor = dimensionBlockTargetArgument(parameters, "anchor");
        if (!anchor.dimension().equals(bounds.dimension())) {
            throw new IllegalArgumentException("anchor dimension must equal bounds.dimension");
        }

        var transformInput = RuntimeArguments.objectArgument(parameters, "transform");
        RuntimeArguments.requireExactKeys(transformInput, "transform", Set.of("rotation", "mirror"));
        int rotation = RuntimeArguments.intArgument(transformInput, "rotation");
        var transform = new BlockPlan.Transform(rotation, RuntimeArguments.stringArgument(transformInput, "mirror"));

        var phase = RuntimeArguments.objectArgument(parameters, "phase");
        RuntimeArguments.requireExactKeys(phase, "phase", Set.of("id", "index", "total"));
        String phaseId = RuntimeArguments.stringArgument(phase, "id");
        int phaseIndex = RuntimeArguments.intArgument(phase, "index");
        int phaseTotal = RuntimeArguments.intArgument(phase, "total");

        Object rawEntries = parameters.get("entries");
        if (!(rawEntries instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > ApplyBlockPlanRequest.MAX_STEPS) {
            throw new IllegalArgumentException("entries must contain 1..64 items");
        }

        var canonical = new StringBuilder();
        RoutineIdentity.appendIdentity(canonical, "apply_block_plan/v1");
        RoutineIdentity.appendIdentity(canonical, ApplyBlockPlanRequest.KIND);
        RoutineIdentity.appendTargetIdentity(canonical, anchor);
        RoutineIdentity.appendIdentity(canonical, Integer.toString(transform.rotation()));
        RoutineIdentity.appendIdentity(canonical, transform.mirror());
        RoutineIdentity.appendIdentity(canonical, phaseId);
        RoutineIdentity.appendIdentity(canonical, Integer.toString(phaseIndex));
        RoutineIdentity.appendIdentity(canonical, Integer.toString(phaseTotal));
        RoutineIdentity.appendIdentity(canonical, Integer.toString(entries.size()));

        var steps = new ArrayList<ApplyBlockPlanStep>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            String path = "entries[" + index + "]";
            if (!(entries.get(index) instanceof Map<?, ?> rawEntry)) {
                throw new IllegalArgumentException(path + " must be an object");
            }
            @SuppressWarnings("unchecked")
            var entry = (Map<String, Object>) rawEntry;
            String operationName = RuntimeArguments.stringArgument(entry, "operation");
            ApplyBlockPlanOperation operation = switch (operationName) {
                case "verify_only" -> ApplyBlockPlanOperation.VERIFY_ONLY;
                case "break_to_air" -> ApplyBlockPlanOperation.BREAK_TO_AIR;
                case "place" -> ApplyBlockPlanOperation.PLACE;
                case "replace" -> ApplyBlockPlanOperation.REPLACE;
                default -> throw new IllegalArgumentException(path + ".operation is unsupported");
            };
            boolean itemRequired = operation == ApplyBlockPlanOperation.PLACE
                    || operation == ApplyBlockPlanOperation.REPLACE;
            var exactKeys = itemRequired
                    ? Set.of("id", "offset", "operation", "expected_before", "expected_after", "item")
                    : Set.of("id", "offset", "operation", "expected_before", "expected_after");
            RuntimeArguments.requireExactKeys(entry, path, exactKeys);

            String id = RuntimeArguments.stringArgument(entry, "id");
            var offset = RuntimeArguments.objectArgument(entry, "offset");
            RuntimeArguments.requireExactKeys(offset, path + ".offset", Set.of("x", "y", "z"));
            int rawX = RuntimeArguments.relativeCoordinate(offset, "x", path);
            int rawY = RuntimeArguments.relativeCoordinate(offset, "y", path);
            int rawZ = RuntimeArguments.relativeCoordinate(offset, "z", path);
            var transformedOffset = transform.apply(new BlockPlan.Offset(rawX, rawY, rawZ));
            final BlockTarget target;
            try {
                target = checkedBlockTarget(
                        anchor.dimension(),
                        Math.addExact(anchor.x(), transformedOffset.x()),
                        Math.addExact(anchor.y(), transformedOffset.y()),
                        Math.addExact(anchor.z(), transformedOffset.z()));
            }
            catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(path + ".offset transforms outside supported bounds", overflow);
            }

            var before = fullBlockStateArgument(entry, "expected_before", transform, path);
            var after = fullBlockStateArgument(entry, "expected_after", transform, path);
            Optional<String> item = itemRequired
                    ? Optional.of(RuntimeArguments.stringArgument(entry, "item"))
                    : Optional.empty();
            if (operation == ApplyBlockPlanOperation.REPLACE
                    && before.transformed().equals(after.transformed())) {
                throw new IllegalArgumentException(
                        path + " replace requires different exact before and after states");
            }
            steps.add(new ApplyBlockPlanStep(
                    id, operation, target, before.transformed(), after.transformed(), item));

            RoutineIdentity.appendIdentity(canonical, id);
            RoutineIdentity.appendIdentity(canonical, Integer.toString(rawX));
            RoutineIdentity.appendIdentity(canonical, Integer.toString(rawY));
            RoutineIdentity.appendIdentity(canonical, Integer.toString(rawZ));
            RoutineIdentity.appendIdentity(canonical, operation.wireName());
            RoutineIdentity.appendBlockStateIdentity(canonical, before.source());
            RoutineIdentity.appendBlockStateIdentity(canonical, after.source());
            RoutineIdentity.appendIdentity(canonical, item.orElse(""));
        }

        var request = new ApplyBlockPlanRequest(
                phaseId, phaseIndex, phaseTotal, steps, bounds);
        request.requiredResources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    RoutineIdentity.appendIdentity(canonical, entry.getKey());
                    RoutineIdentity.appendIdentity(canonical, Integer.toString(entry.getValue()));
                });
        RoutineIdentity.appendIdentity(canonical, Integer.toString(request.requiredResources().size()));
        RoutineIdentity.appendBoundsIdentity(canonical, bounds);
        RoutineIdentity.appendIdentity(canonical, completionIntent);
        return new ParsedApplyBlockPlan(
                request,
                RoutineIdentity.sha256Identity(canonical),
                resourceEstimate(request));
    }

    static ParsedPhaseFive phaseFiveRequestArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        Objects.requireNonNull(arguments, "arguments");
        requireStartRoutineKeys(arguments);
        String completionIntent = RuntimeArguments.completionIntentArgument(arguments);
        String kind = RuntimeArguments.stringArgument(arguments, "kind");
        if (!PhaseFiveRequest.KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind is not a Phase 5 routine");
        }
        var parameters = RuntimeArguments.objectArgument(arguments, "parameters");
        var bounds = phaseFiveBoundsArgument(arguments, currentDimension);
        var targets = new ArrayList<BlockTarget>();
        int expectedUnits;
        String progressUnit;

        switch (kind) {
            case "craft_items" -> {
                RuntimeArguments.requireExactKeys(parameters, "craft_items parameters", Set.of(
                        "recipe_ref", "recipe_fingerprint", "goal", "station", "max_crafts"));
                RuntimeArguments.requireOpaqueReference(parameters, "recipe_ref");
                RuntimeArguments.requireSha256Fingerprint(parameters, "recipe_fingerprint");
                var goal = RuntimeArguments.objectArgument(parameters, "goal");
                RuntimeArguments.requireExactKeys(goal, "craft_items goal", Set.of(
                        "item", "stack_policy", "minimum_inventory_count"));
                RuntimeArguments.requireRegisteredItemId(RuntimeArguments.stringArgument(goal, "item"));
                RuntimeArguments.requireLiteral(goal, "stack_policy", "default_components_only");
                expectedUnits = RuntimeArguments.requireRange(
                        RuntimeArguments.intArgument(goal, "minimum_inventory_count"), 1, 2_304,
                        "minimum_inventory_count");
                RuntimeArguments.requireRange(RuntimeArguments.intArgument(parameters, "max_crafts"), 1, 64, "max_crafts");

                var station = RuntimeArguments.objectArgument(parameters, "station");
                RuntimeArguments.requireExactKeys(station, "craft_items station", Set.of(
                        "kind", "target", "expected_state"));
                RuntimeArguments.requireLiteral(station, "kind", "crafting_table");
                var target = boundedDimensionTarget(station, "target", bounds);
                targets.add(target);
                var expected = exactFullState(station, "expected_state", "station");
                if (!"minecraft:crafting_table".equals(expected.blockId())) {
                    throw new IllegalArgumentException(
                            "craft_items station must be minecraft:crafting_table");
                }
                progressUnit = "items";
            }
            case "transfer_items" -> {
                RuntimeArguments.requireExactKeys(parameters, "transfer_items parameters", Set.of(
                        "container", "direction", "stack", "goal", "max_transfer_count"));
                String direction = RuntimeArguments.stringArgument(parameters, "direction");
                if (!direction.equals("player_to_container")
                        && !direction.equals("container_to_player")) {
                    throw new IllegalArgumentException("transfer direction is unsupported");
                }
                var container = RuntimeArguments.objectArgument(parameters, "container");
                RuntimeArguments.requireExactKeys(container, "transfer_items container", Set.of(
                        "target", "expected_state"));
                targets.add(boundedDimensionTarget(container, "target", bounds));
                var expected = exactFullState(container, "expected_state", "container");
                if (!expected.blockId().equals("minecraft:barrel")
                        && !expected.blockId().equals("minecraft:chest")) {
                    throw new IllegalArgumentException(
                            "transfer container must be a canonical chest or barrel");
                }
                if (expected.blockId().equals("minecraft:chest")
                        && !"single".equals(expected.properties().get("type"))) {
                    throw new IllegalArgumentException("transfer chest must be single");
                }
                var stack = RuntimeArguments.objectArgument(parameters, "stack");
                RuntimeArguments.requireExactKeys(stack, "transfer_items stack", Set.of("item", "stack_policy"));
                RuntimeArguments.requireRegisteredItemId(RuntimeArguments.stringArgument(stack, "item"));
                String stackPolicy = RuntimeArguments.stringArgument(stack, "stack_policy");
                if (!Set.of("default_components_only", "item_id_any_components")
                        .contains(stackPolicy)) {
                    throw new IllegalArgumentException("transfer stack policy is unsupported");
                }
                var goal = RuntimeArguments.objectArgument(parameters, "goal");
                RuntimeArguments.requireExactKeys(goal, "transfer_items goal", Set.of("minimum_destination_count"));
                expectedUnits = RuntimeArguments.requireRange(
                        RuntimeArguments.intArgument(goal, "minimum_destination_count"), 0,
                        direction.equals("player_to_container") ? 3_456 : 2_304,
                        "minimum_destination_count");
                RuntimeArguments.requireRange(RuntimeArguments.intArgument(parameters, "max_transfer_count"), 1, 2_304,
                        "max_transfer_count");
                progressUnit = "items";
            }
            case "tend_crop_area" -> {
                RuntimeArguments.requireExactKeys(parameters, "tend_crop_area parameters", Set.of(
                        "crop_adapter", "plots", "goal", "wait_policy"));
                String adapter = RuntimeArguments.stringArgument(parameters, "crop_adapter");
                if (!Set.of("wheat", "carrots", "potatoes", "beetroots").contains(adapter)) {
                    throw new IllegalArgumentException("crop_adapter is unsupported");
                }
                var plots = RuntimeArguments.objectListArgument(parameters, "plots", 1, 64);
                var ids = new java.util.HashSet<String>();
                var cropTargets = new java.util.HashSet<BlockTarget>();
                for (int index = 0; index < plots.size(); index++) {
                    var plot = plots.get(index);
                    String path = "plots[" + index + "]";
                    RuntimeArguments.requireExactKeys(plot, path, Set.of(
                            "id", "crop_position", "support_position", "expected_support_state"));
                    RuntimeArguments.requireUniqueLocalId(ids, RuntimeArguments.stringArgument(plot, "id"), path + ".id");
                    var crop = boundedDimensionTarget(plot, "crop_position", bounds);
                    if (!cropTargets.add(crop)) {
                        throw new IllegalArgumentException("crop positions must be unique");
                    }
                    targets.add(crop);
                    targets.add(boundedDimensionTarget(plot, "support_position", bounds));
                    var support = exactFullState(plot, "expected_support_state", path);
                    if (!"minecraft:farmland".equals(support.blockId())) {
                        throw new IllegalArgumentException("crop support must be minecraft:farmland");
                    }
                }
                var goal = RuntimeArguments.objectArgument(parameters, "goal");
                RuntimeArguments.requireExactKeys(goal, "tend_crop_area goal", Set.of(
                        "minimum_harvested_plots", "replant", "collect_drops"));
                expectedUnits = RuntimeArguments.requireRange(
                        RuntimeArguments.intArgument(goal, "minimum_harvested_plots"), 0, plots.size(),
                        "minimum_harvested_plots");
                RuntimeArguments.requireTrue(goal, "replant");
                RuntimeArguments.requireTrue(goal, "collect_drops");
                String waitPolicy = RuntimeArguments.stringArgument(parameters, "wait_policy");
                if (!waitPolicy.equals("no_wait") && !waitPolicy.equals("until_minimum")) {
                    throw new IllegalArgumentException("wait_policy is unsupported");
                }
                progressUnit = "cells";
            }
            case "harvest_tree_area" -> {
                RuntimeArguments.requireExactKeys(parameters, "harvest_tree_area parameters", Set.of(
                        "trees", "collect_drops"));
                RuntimeArguments.requireTrue(parameters, "collect_drops");
                var trees = RuntimeArguments.objectListArgument(parameters, "trees", 1, 8);
                var ids = new java.util.HashSet<String>();
                var logTargets = new java.util.HashSet<BlockTarget>();
                int totalLogs = 0;
                for (int treeIndex = 0; treeIndex < trees.size(); treeIndex++) {
                    var tree = trees.get(treeIndex);
                    String path = "trees[" + treeIndex + "]";
                    RuntimeArguments.requireExactKeys(tree, path, Set.of(
                            "id", "logs", "support", "sapling", "growth_clearance"));
                    RuntimeArguments.requireUniqueLocalId(ids, RuntimeArguments.stringArgument(tree, "id"), path + ".id");
                    var logs = RuntimeArguments.objectListArgument(tree, "logs", 1, 64);
                    totalLogs = Math.addExact(totalLogs, logs.size());
                    if (totalLogs > 64) {
                        throw new IllegalArgumentException("all tree logs together must not exceed 64");
                    }
                    for (int logIndex = 0; logIndex < logs.size(); logIndex++) {
                        var log = logs.get(logIndex);
                        String logPath = path + ".logs[" + logIndex + "]";
                        validateExpectedCell(log, logPath, bounds, targets, logTargets);
                    }
                    validateExpectedCell(
                            RuntimeArguments.objectArgument(tree, "support"), path + ".support",
                            bounds, targets, null);
                    var sapling = RuntimeArguments.objectArgument(tree, "sapling");
                    RuntimeArguments.requireExactKeys(sapling, path + ".sapling", Set.of(
                            "item", "expected_after_state"));
                    RuntimeArguments.requireRegisteredItemId(RuntimeArguments.stringArgument(sapling, "item"));
                    exactFullState(sapling, "expected_after_state", path + ".sapling");
                    var clearance = RuntimeArguments.objectListArgument(tree, "growth_clearance", 1, 64);
                    for (int clearanceIndex = 0; clearanceIndex < clearance.size(); clearanceIndex++) {
                        validateExpectedCell(
                                clearance.get(clearanceIndex),
                                path + ".growth_clearance[" + clearanceIndex + "]",
                                bounds, targets, null);
                    }
                }
                expectedUnits = totalLogs;
                progressUnit = "blocks";
            }
            case "sleep_at_bed" -> {
                RuntimeArguments.requireExactKeys(parameters, "sleep_at_bed parameters", Set.of(
                        "bed", "return_policy"));
                RuntimeArguments.requireLiteral(parameters, "return_policy", "start_checkpoint");
                var bed = RuntimeArguments.objectArgument(parameters, "bed");
                RuntimeArguments.requireExactKeys(bed, "sleep_at_bed bed", Set.of(
                        "foot_position", "expected_foot_state",
                        "head_position", "expected_head_state"));
                var foot = boundedDimensionTarget(bed, "foot_position", bounds);
                var head = boundedDimensionTarget(bed, "head_position", bounds);
                if (foot.equals(head)) {
                    throw new IllegalArgumentException("bed halves must use distinct positions");
                }
                targets.add(foot);
                targets.add(head);
                var footState = exactFullState(bed, "expected_foot_state", "bed");
                var headState = exactFullState(bed, "expected_head_state", "bed");
                if (!footState.blockId().equals(headState.blockId())
                        || !footState.blockId().endsWith("_bed")) {
                    throw new IllegalArgumentException("bed halves must use the same bed block");
                }
                expectedUnits = 1;
                progressUnit = "interactions";
            }
            case "survey_area" -> {
                RuntimeArguments.requireExactKeys(parameters, "survey_area parameters", Set.of(
                        "waypoints", "samples", "goal", "assessment"));
                var waypoints = RuntimeArguments.objectListArgument(parameters, "waypoints", 1, 32);
                var waypointIds = new java.util.HashSet<String>();
                for (int index = 0; index < waypoints.size(); index++) {
                    var waypoint = waypoints.get(index);
                    String path = "waypoints[" + index + "]";
                    RuntimeArguments.requireExactKeys(waypoint, path, Set.of("id", "target", "look_at"));
                    RuntimeArguments.requireUniqueLocalId(
                            waypointIds, RuntimeArguments.stringArgument(waypoint, "id"), path + ".id");
                    targets.add(boundedDimensionTarget(waypoint, "target", bounds));
                    targets.add(boundedDimensionTarget(waypoint, "look_at", bounds));
                }
                var samples = RuntimeArguments.objectListArgument(parameters, "samples", 1, 256);
                var sampleIds = new java.util.HashSet<String>();
                for (int index = 0; index < samples.size(); index++) {
                    var sample = samples.get(index);
                    String path = "samples[" + index + "]";
                    RuntimeArguments.requireExactKeys(sample, path, Set.of("id", "position"));
                    RuntimeArguments.requireUniqueLocalId(sampleIds, RuntimeArguments.stringArgument(sample, "id"), path + ".id");
                    targets.add(boundedDimensionTarget(sample, "position", bounds));
                }
                var goal = RuntimeArguments.objectArgument(parameters, "goal");
                RuntimeArguments.requireExactKeys(goal, "survey_area goal", Set.of("minimum_observed_samples"));
                expectedUnits = RuntimeArguments.requireRange(
                        RuntimeArguments.intArgument(goal, "minimum_observed_samples"), 1, samples.size(),
                        "minimum_observed_samples");
                String assessment = RuntimeArguments.stringArgument(parameters, "assessment");
                if (!assessment.equals("coverage_only")
                        && !assessment.equals("spawn_surface_prediction")) {
                    throw new IllegalArgumentException("survey assessment is unsupported");
                }
                progressUnit = "cells";
            }
            default -> throw new AssertionError("unreachable Phase 5 kind");
        }

        var request = new PhaseFiveRequest(
                kind, parameters, bounds, expectedUnits, progressUnit);
        var canonical = new StringBuilder();
        RoutineIdentity.appendIdentity(canonical, "phase-five/v1");
        RoutineIdentity.appendCanonicalValue(canonical, kind);
        RoutineIdentity.appendCanonicalValue(canonical, parameters);
        RoutineIdentity.appendCanonicalValue(canonical, Map.of(
                "dimension", bounds.dimension(),
                "minimum", Map.of(
                        "x", bounds.minimum().x(), "y", bounds.minimum().y(), "z", bounds.minimum().z()),
                "maximum", Map.of(
                        "x", bounds.maximum().x(), "y", bounds.maximum().y(), "z", bounds.maximum().z()),
                "max_travel_blocks", bounds.maxTravelBlocks(),
                "max_duration_seconds", bounds.maxDurationSeconds(),
                "allow_break", bounds.allowBreak()));
        RoutineIdentity.appendCanonicalValue(canonical, completionIntent);
        return new ParsedPhaseFive(
                request, RoutineIdentity.sha256Identity(canonical), targets.stream().distinct().toList());
    }

    static PhaseFiveBounds phaseFiveBoundsArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        var bounds = RuntimeArguments.objectArgument(arguments, "bounds");
        RuntimeArguments.requireExactKeys(bounds, "bounds", Set.of(
                "dimension", "region", "max_travel_blocks",
                "max_duration_seconds", "allow_break"));
        String dimension = RuntimeArguments.stringArgument(bounds, "dimension");
        if (!dimension.equals(Objects.requireNonNull(currentDimension, "currentDimension"))) {
            throw new IllegalArgumentException("bounds.dimension must equal the current dimension");
        }
        var region = RuntimeArguments.objectArgument(bounds, "region");
        RuntimeArguments.requireExactKeys(region, "bounds.region", Set.of("min", "max"));
        return new PhaseFiveBounds(
                dimension,
                positionTargetArgument(region, "min", dimension),
                positionTargetArgument(region, "max", dimension),
                RuntimeArguments.intArgument(bounds, "max_travel_blocks"),
                RuntimeArguments.intArgument(bounds, "max_duration_seconds"),
                RuntimeArguments.booleanArgument(bounds, "allow_break"));
    }

    static BlockTarget boundedDimensionTarget(
            Map<String, Object> source,
            String name,
            PhaseFiveBounds bounds) {
        var target = dimensionBlockTargetArgument(source, name);
        if (!bounds.contains(target)) {
            throw new IllegalArgumentException(name + " must be inside bounds.region");
        }
        return target;
    }

    static BlockStateFingerprint exactFullState(
            Map<String, Object> source,
            String name,
            String path) {
        return fullBlockStateArgument(
                source, name, new BlockPlan.Transform(0, "none"), path).transformed();
    }

    static void validateExpectedCell(
            Map<String, Object> cell,
            String path,
            PhaseFiveBounds bounds,
            List<BlockTarget> targets,
            Set<BlockTarget> uniqueTargets) {
        RuntimeArguments.requireExactKeys(cell, path, Set.of("position", "expected_state"));
        var target = boundedDimensionTarget(cell, "position", bounds);
        if (uniqueTargets != null && !uniqueTargets.add(target)) {
            throw new IllegalArgumentException("declared log positions must be unique");
        }
        targets.add(target);
        exactFullState(cell, "expected_state", path);
    }

    static FullStatePair fullBlockStateArgument(
            Map<String, Object> source,
            String name,
            BlockPlan.Transform transform,
            String entryPath) {
        var state = RuntimeArguments.objectArgument(source, name);
        RuntimeArguments.requireExactKeys(state, entryPath + "." + name, Set.of("block", "properties"));
        var fingerprint = blockStateArgument(source, name);
        var sourceView = new BlockStateView(fingerprint.blockId(), fingerprint.properties());
        var transformed = BlockPlanStateTransformer.transformFull(
                sourceView, transform, entryPath + "." + name);
        return new FullStatePair(
                fingerprint,
                new BlockStateFingerprint(transformed.block(), transformed.properties()));
    }

    static Map<String, Object> resourceEstimate(ApplyBlockPlanRequest request) {
        var items = request.requiredResources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(),
                        "maximum_required_count", entry.getValue()))
                .toList();
        int breaks = 0;
        int placements = 0;
        for (var step : request.steps()) {
            if (step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                breaks++;
            }
            if (step.operation() == ApplyBlockPlanOperation.PLACE
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                placements++;
            }
        }
        return Map.of(
                "items", items,
                "break_operations", breaks,
                "place_operations", placements);
    }

    static ActionBounds actionBoundsArgument(
            Map<String, Object> arguments,
            WorldSessionTracker.Snapshot session) {
        Objects.requireNonNull(session, "session");
        return actionBoundsArgument(arguments, session.dimension());
    }

    static ActionBounds actionBoundsArgument(
            Map<String, Object> arguments,
            String currentDimension) {
        var bounds = RuntimeArguments.objectArgument(arguments, "bounds");
        RuntimeArguments.requireExactKeys(bounds, "bounds", Set.of(
                "dimension", "region", "max_travel_blocks",
                "max_duration_seconds", "allow_break"));
        var dimension = RuntimeArguments.stringArgument(bounds, "dimension");
        if (!dimension.equals(Objects.requireNonNull(currentDimension, "currentDimension"))) {
            throw new IllegalArgumentException("bounds.dimension must equal the current dimension");
        }
        var region = RuntimeArguments.objectArgument(bounds, "region");
        RuntimeArguments.requireExactKeys(region, "bounds.region", Set.of("min", "max"));
        var minimum = positionTargetArgument(region, "min", dimension);
        var maximum = positionTargetArgument(region, "max", dimension);
        return new ActionBounds(
                dimension,
                minimum,
                maximum,
                RuntimeArguments.intArgument(bounds, "max_travel_blocks"),
                RuntimeArguments.intArgument(bounds, "max_duration_seconds"),
                RuntimeArguments.booleanArgument(bounds, "allow_break"));
    }

    static BlockTarget dimensionBlockTargetArgument(
            Map<String, Object> source,
            String name) {
        var target = RuntimeArguments.objectArgument(source, name);
        RuntimeArguments.requireExactKeys(target, name, Set.of("dimension", "x", "y", "z"));
        return checkedBlockTarget(
                RuntimeArguments.stringArgument(target, "dimension"),
                RuntimeArguments.intArgument(target, "x"),
                RuntimeArguments.intArgument(target, "y"),
                RuntimeArguments.intArgument(target, "z"));
    }

    static BlockTarget positionTargetArgument(
            Map<String, Object> source,
            String name,
            String dimension) {
        var target = RuntimeArguments.objectArgument(source, name);
        RuntimeArguments.requireExactKeys(target, name, Set.of("x", "y", "z"));
        return checkedBlockTarget(
                dimension,
                RuntimeArguments.intArgument(target, "x"),
                RuntimeArguments.intArgument(target, "y"),
                RuntimeArguments.intArgument(target, "z"));
    }

    static BlockTarget checkedBlockTarget(
            String dimension,
            int x,
            int y,
            int z) {
        if (x < -30_000_000 || x > 29_999_999
                || z < -30_000_000 || z > 29_999_999
                || y < -2_048 || y > 2_047) {
            throw new IllegalArgumentException("block position is outside supported bounds");
        }
        return new BlockTarget(dimension, x, y, z);
    }

    static BlockStateFingerprint blockStateArgument(
            Map<String, Object> source,
            String name) {
        var state = RuntimeArguments.objectArgument(source, name);
        RuntimeArguments.requireAllowedKeys(state, name, Set.of("block", "properties"));
        var block = RuntimeArguments.stringArgument(state, "block");
        var properties = new LinkedHashMap<String, String>();
        if (state.containsKey("properties")) {
            var rawProperties = state.get("properties");
            if (!(rawProperties instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException(name + ".properties must be an object");
            }
            if (values.size() > 128) {
                throw new IllegalArgumentException(name + ".properties must contain at most 128 entries");
            }
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !key.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException(name + ".properties has an invalid key");
                }
                if (!(entry.getValue() instanceof String value)
                        || value.isBlank()
                        || value.length() > 64) {
                    throw new IllegalArgumentException(name + ".properties has an invalid value");
                }
                properties.put(key, value);
            }
        }
        return new BlockStateFingerprint(block, properties);
    }

    static Optional<BlockTarget> semanticTarget(SemanticActionRequest request) {
        return switch (request) {
            case NavigateToRequest navigation -> Optional.of(navigation.target());
            case BreakBlockRequest block -> Optional.of(block.target());
            case PlaceBlockRequest place -> Optional.of(place.target());
            case UseItemOnBlockRequest use -> Optional.of(use.target());
            case InteractBlockRequest block -> Optional.of(block.target());
            case InteractEntityRequest ignored -> Optional.empty();
        };
    }

    static void validateLiveBounds(
            Minecraft minecraft,
            ActionBounds bounds,
            BlockTarget target) {
        var level = minecraft.level;
        if (level == null) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
        if (!level.isInsideBuildHeight(bounds.minimum().y())
                || !level.isInsideBuildHeight(bounds.maximum().y())) {
            throw new IllegalArgumentException("bounds.region is outside the current build height");
        }
        if (target != null
                && !level.getWorldBorder().isWithinBounds(
                        new net.minecraft.core.BlockPos(target.x(), target.y(), target.z()))) {
            throw new IllegalArgumentException("target is outside the current world border");
        }
    }

    static void validateLiveBounds(
            Minecraft minecraft,
            PhaseFiveBounds bounds,
            List<BlockTarget> targets) {
        var level = minecraft.level;
        if (level == null) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
        if (!level.isInsideBuildHeight(bounds.minimum().y())
                || !level.isInsideBuildHeight(bounds.maximum().y())) {
            throw new IllegalArgumentException("bounds.region is outside the current build height");
        }
        for (var target : targets) {
            if (!bounds.contains(target)) {
                throw new IllegalArgumentException("Phase 5 target is outside bounds.region");
            }
            if (!level.getWorldBorder().isWithinBounds(
                    new net.minecraft.core.BlockPos(target.x(), target.y(), target.z()))) {
                throw new IllegalArgumentException("Phase 5 target is outside the current world border");
            }
        }
    }

    static void validateApplyBlockPlanItems(ApplyBlockPlanRequest request) {
        for (var step : request.steps()) {
            boolean supportedDoorPlace = step.operation() == ApplyBlockPlanOperation.PLACE
                    && "minecraft:air".equals(step.expectedBefore().blockId())
                    && step.expectedBefore().properties().isEmpty()
                    && step.requiredItemId().filter("minecraft:oak_door"::equals).isPresent()
                    && MinecraftApplyBlockPlanPort.supportedDoorPlacement(step.expectedAfter())
                    && request.bounds().contains(new BlockTarget(
                            step.target().dimension(), step.target().x(),
                            Math.addExact(step.target().y(), 1), step.target().z()));
            if (step.operation().mutating()
                    && !supportedDoorPlace
                    && (unsupportedMultiCellBlock(step.expectedBefore().blockId())
                            || unsupportedMultiCellBlock(step.expectedAfter().blockId()))) {
                throw new IllegalArgumentException(
                        "multi-cell mutation is not supported; verify each cell with verify_only");
            }
            if ((step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE)
                    && !SafeBreakSourcePolicy.allowsRegisteredBlockId(
                            step.expectedBefore().blockId())) {
                throw new IllegalArgumentException(SafeBreakSourcePolicy.REJECTION_MESSAGE);
            }
            if (step.requiredItemId().isEmpty()) {
                continue;
            }
            String itemId = step.requiredItemId().orElseThrow();
            Identifier identifier = Identifier.tryParse(itemId);
            var registered = identifier == null
                    ? Optional.<Holder.Reference<net.minecraft.world.item.Item>>empty()
                    : BuiltInRegistries.ITEM.get(identifier);
            if (registered.isEmpty()
                    || !(registered.orElseThrow().value() instanceof BlockItem blockItem)) {
                throw new IllegalArgumentException("plan item must be a registered BlockItem");
            }
            if (blockItem instanceof SolidBucketItem) {
                throw new IllegalArgumentException(
                        "plan item must not replace itself with a different container item");
            }
            if (!MinecraftApplyBlockPlanPort.supportsPlacementItem(blockItem)) {
                throw new IllegalArgumentException(
                        "plan item uses an unsupported placement implementation");
            }
            var placedBlock = blockItem.getBlock();
            String placedBlockId = BuiltInRegistries.BLOCK.getKey(placedBlock).toString();
            if (!placedBlockId.equals(step.expectedAfter().blockId())) {
                throw new IllegalArgumentException("plan item must place the expected_after block");
            }
            if (blockItem instanceof BedItem
                    || blockItem instanceof DoubleHighBlockItem && !supportedDoorPlace
                    || placedBlock instanceof DoorBlock && !supportedDoorPlace
                    || placedBlock instanceof BedBlock
                    || placedBlock.defaultBlockState().hasProperty(
                            BlockStateProperties.DOUBLE_BLOCK_HALF) && !supportedDoorPlace
                    || placedBlock.defaultBlockState().hasProperty(
                            BlockStateProperties.BED_PART)) {
                throw new IllegalArgumentException(
                        "multi-cell mutation is not supported; verify each cell with verify_only");
            }
        }
    }

    static void validateStationaryBreakAllowedBlocks(Set<String> allowedBlocks) {
        Objects.requireNonNull(allowedBlocks, "allowedBlocks");
        allowedBlocks.forEach(SafeBreakSourcePolicy::requireRegisteredBlockId);
    }

    static boolean unsupportedMultiCellBlock(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        var registered = identifier == null
                ? Optional.<Holder.Reference<net.minecraft.world.level.block.Block>>empty()
                : BuiltInRegistries.BLOCK.get(identifier);
        if (registered.isEmpty()) {
            return false;
        }
        var block = registered.orElseThrow().value();
        var state = block.defaultBlockState();
        return block instanceof DoorBlock
                || block instanceof BedBlock
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART);
    }

    static void requireStartRoutineKeys(Map<String, Object> arguments) {
        Set<String> allowed = Set.of(
                "kind", "parameters", "bounds", "completion_intent", "idempotency_key");
        RuntimeArguments.requireAllowedKeys(arguments, "start_routine", allowed);
        Set<String> required = Set.of("kind", "parameters", "bounds", "idempotency_key");
        if (!arguments.keySet().containsAll(required)
                || arguments.size() < required.size()
                || arguments.size() > allowed.size()) {
            throw new IllegalArgumentException(
                    "start_routine must contain kind, parameters, bounds, and idempotency_key; "
                            + "completion_intent is optional");
        }
    }
}
