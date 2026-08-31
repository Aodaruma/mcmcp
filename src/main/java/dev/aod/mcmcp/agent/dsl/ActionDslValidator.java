package dev.aod.mcmcp.agent.dsl;

import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.redstone.RedstoneSpec;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.CAPABILITY_DENIED;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.INVALID_ARGUMENT;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.PROGRAM_TOO_COMPLEX;

/** Structural and semantic validation shared by parsed and programmatically built DSL trees. */
public final class ActionDslValidator {
    public static final int MAX_AST_DEPTH = 4;
    public static final int MAX_SOURCE_NODES = 64;
    public static final int MAX_EXECUTED_NODES = 256;
    public static final int MAX_REPEAT_COUNT = 16;
    public static final int MAX_TOP_LEVEL_NODES = 32;
    public static final int MAX_BRANCH_NODES = 16;
    public static final int MAX_PREDICATE_OPERANDS = 4;
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    public static final int MAX_ACTION_TICKS = 12_000;
    public static final long MAX_ACTION_DURATION_MILLIS = MAX_ACTION_TICKS * 50L;
    public static final int MAX_ACTION_CAMERA_DEGREES = 720;
    public static final int MAX_BLOCKS_BROKEN = 8;
    public static final int MAX_INTERACTIONS = 16;
    public static final int MAX_BLOCKS_PLACED = 8;
    public static final int MAX_MUTATION_BATCH_TARGETS = 8;
    public static final int MAX_BLOCK_PLAN_ENTRIES = 8;
    public static final int MAX_BLOCK_STATE_PROPERTIES = 32;
    public static final int MAX_BLOCK_PLAN_OFFSET = 8;
    public static final int MIN_COLLECT_BATCH_TARGETS = 2;

    private static final Pattern NODE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern PROGRAM_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern RESOURCE_LOCATION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{24}");
    private static final Pattern SHA256_FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern BLOCK_PROPERTY_NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern BLOCK_PROPERTY_VALUE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Set<String> BREAKABLE_LOGS = Set.of(
            "minecraft:oak_log",
            "minecraft:birch_log");
    private static final Set<String> VANILLA_AXES = Set.of(
            "minecraft:wooden_axe",
            "minecraft:stone_axe",
            "minecraft:copper_axe",
            "minecraft:iron_axe",
            "minecraft:golden_axe",
            "minecraft:diamond_axe",
            "minecraft:netherite_axe");
    private static final Set<String> TILLABLE_BLOCKS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path");
    private static final Set<String> VANILLA_HOES = Set.of(
            "minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:iron_hoe",
            "minecraft:golden_hoe", "minecraft:diamond_hoe", "minecraft:netherite_hoe");
    private static final Set<String> KNOWN_CONTAINERS = Set.of(
            "minecraft:chest", "minecraft:barrel");
    private static final Set<String> FURNACE_STATIONS = Set.of(
            "furnace", "blast_furnace", "smoker");
    private static final Set<String> STACK_POLICIES = Set.of(
            "default_components_only", "item_id_any_components");
    private static final Set<String> WOODEN_OPENABLES = Set.of(
            "minecraft:oak_door", "minecraft:spruce_door", "minecraft:birch_door",
            "minecraft:jungle_door", "minecraft:acacia_door", "minecraft:cherry_door",
            "minecraft:dark_oak_door", "minecraft:pale_oak_door", "minecraft:mangrove_door",
            "minecraft:bamboo_door", "minecraft:crimson_door", "minecraft:warped_door",
            "minecraft:oak_trapdoor", "minecraft:spruce_trapdoor", "minecraft:birch_trapdoor",
            "minecraft:jungle_trapdoor", "minecraft:acacia_trapdoor", "minecraft:cherry_trapdoor",
            "minecraft:dark_oak_trapdoor", "minecraft:pale_oak_trapdoor",
            "minecraft:mangrove_trapdoor", "minecraft:bamboo_trapdoor",
            "minecraft:crimson_trapdoor", "minecraft:warped_trapdoor",
            "minecraft:oak_fence_gate", "minecraft:spruce_fence_gate",
            "minecraft:birch_fence_gate", "minecraft:jungle_fence_gate",
            "minecraft:acacia_fence_gate", "minecraft:cherry_fence_gate",
            "minecraft:dark_oak_fence_gate", "minecraft:pale_oak_fence_gate",
            "minecraft:mangrove_fence_gate", "minecraft:bamboo_fence_gate",
            "minecraft:crimson_fence_gate", "minecraft:warped_fence_gate");

    private ActionDslValidator() {
    }

    public static Validation validate(ActionDsl.Request request) {
        Objects.requireNonNull(request, "request");
        if (request.schemaVersion() != 1) {
            throw invalid("schema_version must be 1");
        }
        ActionDsl.Program program = request.program();
        if (program.dslVersion() != 1) {
            throw invalid("dsl_version must be 1");
        }
        program.name().ifPresent(name -> requirePattern(name, PROGRAM_NAME, "program.name"));
        if (program.capabilities().size() > ActionDsl.Capability.values().length) {
            throw invalid("program.capabilities contains too many values");
        }
        validateRequestBudget(request.budget());

        if (program.body().isEmpty() || program.body().size() > MAX_TOP_LEVEL_NODES) {
            throw invalid("program.body must contain 1.." + MAX_TOP_LEVEL_NODES + " nodes");
        }
        validateTerminalOwnedMenuPlacement(program.body());
        validateTerminalClearPlacement(program.body());
        var walk = new Walk();
        int executed = walkSequence(program.body(), 1, walk, "program.body");
        if (walk.sourceNodes > MAX_SOURCE_NODES) {
            throw tooComplex("source node count exceeds " + MAX_SOURCE_NODES);
        }
        if (executed > MAX_EXECUTED_NODES) {
            throw tooComplex("expanded execution node count exceeds " + MAX_EXECUTED_NODES);
        }
        if (!program.capabilities().containsAll(walk.requiredCapabilities)) {
            var missing = EnumSet.copyOf(walk.requiredCapabilities);
            missing.removeAll(program.capabilities());
            throw new ActionDslException(
                    CAPABILITY_DENIED,
                    "Program does not declare required capabilities: " + missing);
        }
        return new Validation(
                walk.sourceNodes,
                executed,
                Set.copyOf(walk.requiredCapabilities));
    }

    static void validateRequestBudget(ActionDsl.Budget budget) {
        Objects.requireNonNull(budget, "budget");
        requireRange(budget.maxDurationMillis(), 100, MAX_ACTION_DURATION_MILLIS,
                "budget.max_duration_ms");
        requireRange(budget.maxTicks(), 2, MAX_ACTION_TICKS, "budget.max_ticks");
        requireFiniteRange(budget.maxDistanceBlocks(), 0, 32, "budget.max_distance_blocks");
        requireFiniteRange(
                budget.maxCameraDegrees(), 0, MAX_ACTION_CAMERA_DEGREES,
                "budget.max_camera_degrees");
        requireRange(budget.maxInteractions(), 0, MAX_INTERACTIONS, "budget.max_interactions");
        requireRange(budget.maxBlocksBroken(), 0, MAX_BLOCKS_BROKEN,
                "budget.max_blocks_broken");
        requireRange(budget.maxBlocksPlaced(), 0, MAX_BLOCKS_PLACED,
                "budget.max_blocks_placed");
    }

    static void validateHardLimit(ActionDsl.Budget budget) {
        Objects.requireNonNull(budget, "localHardLimit");
        if (budget.maxDurationMillis() < 0 || budget.maxTicks() < 0
                || budget.maxInteractions() < 0 || budget.maxBlocksBroken() < 0
                || budget.maxBlocksPlaced() < 0) {
            throw invalid("Local hard-limit values must be non-negative");
        }
        requireFiniteNonNegative(budget.maxDistanceBlocks(), "local max distance");
        requireFiniteNonNegative(budget.maxCameraDegrees(), "local max camera");
    }

    private static int walkSequence(
            List<ActionDsl.Node> nodes,
            int depth,
            Walk walk,
            String path) {
        int total = 0;
        for (int index = 0; index < nodes.size(); index++) {
            total = boundedAdd(total, walkNode(nodes.get(index), depth, walk,
                    path + "[" + index + "]"));
        }
        return total;
    }

    private static int walkNode(
            ActionDsl.Node node,
            int depth,
            Walk walk,
            String path) {
        Objects.requireNonNull(node, path);
        if (depth > MAX_AST_DEPTH) {
            throw tooComplex("AST depth exceeds " + MAX_AST_DEPTH + " at " + path);
        }
        walk.sourceNodes++;
        if (walk.sourceNodes > MAX_SOURCE_NODES) {
            throw tooComplex("source node count exceeds " + MAX_SOURCE_NODES);
        }
        requirePattern(node.id(), NODE_ID, path + ".id");
        if (!walk.ids.add(node.id())) {
            throw invalid("Program contains duplicate node ids");
        }

        if (node instanceof ActionDsl.NavigateToKnown navigate) {
            validatePosition(navigate.target(), path + ".target");
            requireFiniteRange(navigate.tolerance(), 0.1, 1.5, path + ".tolerance");
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.ApproachKnownSurface approach) {
            validatePosition(approach.target(), path + ".target");
            requireResourceLocation(approach.expectedBlock(), path + ".expected_block");
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.FaceKnownPosition face) {
            validatePosition(face.target(), path + ".target");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            return 1;
        }
        if (node instanceof ActionDsl.BreakKnownFace breakKnownFace) {
            validatePosition(breakKnownFace.target(), path + ".target");
            if (!walk.breakTargets.add(breakKnownFace.target())) {
                throw unprovable("A break target cannot occur more than once: "
                        + breakKnownFace.target());
            }
            if (!BREAKABLE_LOGS.contains(breakKnownFace.expectedBlock())) {
                throw invalid(path + ".expected_block must be minecraft:oak_log or minecraft:birch_log");
            }
            if (!VANILLA_AXES.contains(breakKnownFace.toolItem())) {
                throw invalid(path + ".tool_item must be an exact vanilla axe item id");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.TillKnownBlock till) {
            validatePosition(till.target(), path + ".target");
            if (!TILLABLE_BLOCKS.contains(till.expectedBlock())) {
                throw invalid(path + ".expected_block must be dirt, grass_block, or dirt_path");
            }
            if (!VANILLA_HOES.contains(till.hoeItem())) {
                throw invalid(path + ".hoe_item must be an exact vanilla hoe item id");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_INTERACT);
            return 1;
        }
        if (node instanceof ActionDsl.TillKnownBatch batch) {
            requireSequenceSize(
                    batch.targets(), 1, MAX_MUTATION_BATCH_TARGETS, path + ".targets");
            validateDistinctPositions(batch.targets(), path + ".targets");
            for (int index = 0; index < batch.targets().size(); index++) {
                validatePosition(batch.targets().get(index), path + ".targets[" + index + "]");
            }
            if (!TILLABLE_BLOCKS.contains(batch.expectedBlock())) {
                throw invalid(path + ".expected_block must be dirt, grass_block, or dirt_path");
            }
            if (!VANILLA_HOES.contains(batch.hoeItem())) {
                throw invalid(path + ".hoe_item must be an exact vanilla hoe item id");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_INTERACT);
            return 1;
        }
        if (node instanceof ActionDsl.PlantKnownWheat plant) {
            validatePosition(plant.target(), path + ".target");
            validatePosition(plant.support(), path + ".support");
            if (!plant.target().dimension().equals(plant.support().dimension())
                    || plant.target().x() != plant.support().x()
                    || plant.target().y() != plant.support().y() + 1
                    || plant.target().z() != plant.support().z()) {
                throw invalid(path + ".support must be the block directly below target");
            }
            if (!"minecraft:wheat_seeds".equals(plant.seedItem())) {
                throw invalid(path + ".seed_item must be minecraft:wheat_seeds");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_PLACE);
            return 1;
        }
        if (node instanceof ActionDsl.PlantKnownWheatBatch batch) {
            requireSequenceSize(
                    batch.targets(), 1, MAX_MUTATION_BATCH_TARGETS, path + ".targets");
            var cropTargets = new HashSet<ActionDsl.Position>();
            var supports = new HashSet<ActionDsl.Position>();
            for (int index = 0; index < batch.targets().size(); index++) {
                ActionDsl.PlantPlot plot = batch.targets().get(index);
                String targetPath = path + ".targets[" + index + "]";
                validatePosition(plot.target(), targetPath + ".target");
                validatePosition(plot.support(), targetPath + ".support");
                if (!plot.target().dimension().equals(plot.support().dimension())
                        || plot.target().x() != plot.support().x()
                        || plot.target().y() != plot.support().y() + 1
                        || plot.target().z() != plot.support().z()) {
                    throw invalid(targetPath + ".support must be the block directly below target");
                }
                if (!cropTargets.add(plot.target()) || !supports.add(plot.support())) {
                    throw invalid(path + ".targets must contain distinct target/support positions");
                }
            }
            if (cropTargets.stream().anyMatch(supports::contains)) {
                throw invalid(path + ".targets crop and support positions must not overlap");
            }
            if (!"minecraft:wheat_seeds".equals(batch.seedItem())) {
                throw invalid(path + ".seed_item must be minecraft:wheat_seeds");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_PLACE);
            return 1;
        }
        if (node instanceof ActionDsl.HarvestKnownWheat harvest) {
            validatePosition(harvest.target(), path + ".target");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.HarvestKnownWheatBatch batch) {
            requireSequenceSize(
                    batch.targets(), 1, MAX_MUTATION_BATCH_TARGETS, path + ".targets");
            validateDistinctPositions(batch.targets(), path + ".targets");
            for (int index = 0; index < batch.targets().size(); index++) {
                validatePosition(batch.targets().get(index), path + ".targets[" + index + "]");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            validatePosition(plan.anchor(), path + ".anchor");
            requireSequenceSize(plan.entries(), 1, MAX_BLOCK_PLAN_ENTRIES, path + ".entries");
            var entryIds = new HashSet<String>();
            var entryTargets = new java.util.LinkedHashMap<String, ActionDsl.Position>();
            var distinctTargets = new HashSet<ActionDsl.Position>();
            for (int index = 0; index < plan.entries().size(); index++) {
                ActionDsl.BlockPlanEntry entry = plan.entries().get(index);
                String entryPath = path + ".entries[" + index + "]";
                Objects.requireNonNull(entry, entryPath);
                requirePattern(entry.id(), NODE_ID, entryPath + ".id");
                if (!entryIds.add(entry.id())) {
                    throw invalid(path + ".entries must contain unique ids");
                }
                validateOffset(entry.offset(), entryPath + ".offset");
                ActionDsl.Position target = transformedTarget(
                        plan.anchor(), plan.transform(), entry.offset());
                validatePosition(target, entryPath + ".offset");
                if (!distinctTargets.add(target)) {
                    throw invalid(path + ".entries must produce distinct transformed targets");
                }
                validateBlockState(entry.sourceState(), entryPath + ".source_state");
                requireResourceLocation(entry.item(), entryPath + ".item");

                ActionDsl.PlacementSupport support = entry.support();
                validatePosition(support.position(), entryPath + ".support.position");
                if (!plan.anchor().dimension().equals(support.position().dimension())) {
                    throw invalid(entryPath + ".support.position must use the anchor dimension");
                }
                boolean hasExpectedState = support.expectedState().isPresent();
                boolean hasDependency = support.dependencyEntryId().isPresent();
                if (hasExpectedState == hasDependency) {
                    throw invalid(entryPath
                            + ".support must select exactly one support witness");
                }
                if (hasExpectedState) {
                    validateBlockState(support.expectedState().orElseThrow(),
                            entryPath + ".support.expected_state");
                } else {
                    String dependencyId = support.dependencyEntryId().orElseThrow();
                    requirePattern(dependencyId, NODE_ID,
                            entryPath + ".support.dependency_entry_id");
                    ActionDsl.Position dependencyTarget = entryTargets.get(dependencyId);
                    if (dependencyTarget == null) {
                        throw invalid(entryPath
                                + ".support dependency must reference an earlier entry");
                    }
                    if (!dependencyTarget.equals(support.position())) {
                        throw invalid(entryPath
                                + ".support dependency position must match its earlier target");
                    }
                }
                if (!relative(support.position(), support.face()).equals(target)) {
                    throw invalid(entryPath
                            + ".support face must point from support position to target");
                }
                entryTargets.put(entry.id(), target);
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_PLACE);
            return 1;
        }
        if (node instanceof ActionDsl.ClearKnownBlockPlan plan) {
            validatePosition(plan.anchor(), path + ".anchor");
            requireSequenceSize(plan.entries(), 1, MAX_BLOCK_PLAN_ENTRIES, path + ".entries");
            var entryIds = new HashSet<String>();
            var targets = new HashSet<ActionDsl.Position>();
            for (int index = 0; index < plan.entries().size(); index++) {
                ActionDsl.ClearBlockPlanEntry entry = plan.entries().get(index);
                String entryPath = path + ".entries[" + index + "]";
                Objects.requireNonNull(entry, entryPath);
                requirePattern(entry.id(), NODE_ID, entryPath + ".id");
                if (!entryIds.add(entry.id())) {
                    throw invalid(path + ".entries must contain unique ids");
                }
                validateOffset(entry.offset(), entryPath + ".offset");
                ActionDsl.Position target = transformedTarget(
                        plan.anchor(), plan.transform(), entry.offset());
                validatePosition(target, entryPath + ".offset");
                if (!targets.add(target)) {
                    throw invalid(path + ".entries must produce distinct transformed targets");
                }
                validateBlockState(entry.expectedBefore(), entryPath + ".expected_before");
                if ("minecraft:air".equals(entry.expectedBefore().block())) {
                    throw invalid(entryPath + ".expected_before must be non-air");
                }
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.ApplyKnownRedstoneSpec redstone) {
            validatePosition(redstone.anchor(), path + ".anchor");
            try {
                new RedstoneSpec(
                        redstone.components(),
                        redstone.truthTable(),
                        redstone.footprint(),
                        redstone.rotation(),
                        new RedstoneSpec.ExecutionBounds(
                                true, redstone.timing().settleTicks()));
            } catch (IllegalArgumentException failure) {
                throw new ActionDslException(
                        INVALID_ARGUMENT,
                        "apply_known_redstone_spec is outside the fixed identity slices",
                        failure);
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_INTERACT);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_PLACE);
            return 1;
        }
        if (node instanceof ActionDsl.OpenKnownFenceGate gate) {
            validatePosition(gate.target(), path + ".target");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_INTERACT);
            return 1;
        }
        if (node instanceof ActionDsl.OpenKnownPassage passage) {
            validatePosition(passage.target(), path + ".target");
            if (!WOODEN_OPENABLES.contains(passage.expectedBlock())) {
                throw invalid(path + ".expected_block must be an allowlisted wooden openable");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_INTERACT);
            return 1;
        }
        if (node instanceof ActionDsl.InspectKnownContainer inspect) {
            validatePosition(inspect.target(), path + ".target");
            if (!KNOWN_CONTAINERS.contains(inspect.expectedBlock())) {
                throw invalid(path + ".expected_block must be minecraft:chest or minecraft:barrel");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.TakeKnownContainerStack take) {
            validatePosition(take.target(), path + ".target");
            if (!KNOWN_CONTAINERS.contains(take.expectedBlock())) {
                throw invalid(path + ".expected_block must be minecraft:chest or minecraft:barrel");
            }
            requirePattern(take.item(), RESOURCE_LOCATION, path + ".item");
            if (!STACK_POLICIES.contains(take.stackPolicy())) {
                throw invalid(path + ".stack_policy is unsupported");
            }
            requireRange(take.minimumInventoryCount(), 1, 2_304,
                    path + ".minimum_inventory_count");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.CraftKnownRecipe craft) {
            requirePattern(craft.recipeRef(), OPAQUE_REFERENCE, path + ".recipe_ref");
            requirePattern(craft.recipeFingerprint(), SHA256_FINGERPRINT,
                    path + ".recipe_fingerprint");
            requirePattern(craft.goalItem(), RESOURCE_LOCATION, path + ".goal.item");
            if (!"default_components_only".equals(craft.stackPolicy())) {
                throw invalid(path + ".goal.stack_policy must be default_components_only");
            }
            requireRange(craft.minimumInventoryCount(), 1, 2_304,
                    path + ".goal.minimum_inventory_count");
            if (!"crafting_table".equals(craft.stationKind())) {
                throw invalid(path + ".station.kind must be crafting_table");
            }
            validatePosition(craft.target(), path + ".station.target");
            validateBlockState(craft.expectedState(), path + ".station.expected_state");
            if (!"minecraft:crafting_table".equals(craft.expectedState().block())
                    || !craft.expectedState().properties().isEmpty()) {
                throw invalid(path + ".station.expected_state must be an exact crafting table state");
            }
            requireRange(craft.maxCrafts(), 1, 3, path + ".max_crafts");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.SmeltKnownRecipe smelt) {
            requirePattern(smelt.recipeRef(), OPAQUE_REFERENCE, path + ".recipe_ref");
            requirePattern(smelt.recipeFingerprint(), SHA256_FINGERPRINT,
                    path + ".recipe_fingerprint");
            requirePattern(smelt.goalItem(), RESOURCE_LOCATION, path + ".goal.item");
            if (!"default_components_only".equals(smelt.stackPolicy())) {
                throw invalid(path + ".goal.stack_policy must be default_components_only");
            }
            requireRange(smelt.minimumInventoryCount(), 1, 2_304,
                    path + ".goal.minimum_inventory_count");
            if (!FURNACE_STATIONS.contains(smelt.stationKind())) {
                throw invalid(path + ".station.kind is unsupported");
            }
            validatePosition(smelt.target(), path + ".station.target");
            validateBlockState(smelt.expectedState(), path + ".station.expected_state");
            if (!("minecraft:" + smelt.stationKind()).equals(smelt.expectedState().block())) {
                throw invalid(path + ".station.expected_state must match station.kind");
            }
            requirePattern(smelt.fuelItem(), RESOURCE_LOCATION, path + ".fuel.item");
            if (!"default_components_only".equals(smelt.fuelStackPolicy())) {
                throw invalid(path + ".fuel.stack_policy must be default_components_only");
            }
            if (smelt.maxSmelts() != 1) {
                throw invalid(path + ".max_smelts must be 1");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.OperateKnownMenu menu) {
            requirePattern(menu.operationRef(), OPAQUE_REFERENCE, path + ".operation_ref");
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.BrewKnownPotionBatch brew) {
            validatePosition(brew.target(), path + ".target");
            if (!StandardPotionPolicy.BREWING_STAND.equals(brew.expectedBlock())) {
                throw invalid(path + ".expected_block must be minecraft:brewing_stand");
            }
            if (!StandardPotionPolicy.FUEL_ITEM.equals(brew.fuelItem())) {
                throw invalid(path + ".fuel_item must be minecraft:blaze_powder");
            }
            if (!StandardPotionPolicy.isKnownOneStepRecipe(
                    brew.input(), brew.ingredientItem(), brew.expectedOutput())) {
                throw invalid(path + " must declare one catalog-fixed standard potion recipe");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.CollectVisibleItem collect) {
            validateWorldPosition(collect.target(), path + ".target");
            requireResourceLocation(collect.displayedItem(), path + ".displayed_item");
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.CollectVisibleItemBatch batch) {
            requireSequenceSize(
                    batch.targets(), MIN_COLLECT_BATCH_TARGETS,
                    MAX_MUTATION_BATCH_TARGETS, path + ".targets");
            var distinct = new HashSet<ActionDsl.CollectTarget>();
            for (int index = 0; index < batch.targets().size(); index++) {
                ActionDsl.CollectTarget target = batch.targets().get(index);
                String targetPath = path + ".targets[" + index + "]";
                validateWorldPosition(target.target(), targetPath + ".target");
                requireResourceLocation(target.displayedItem(), targetPath + ".displayed_item");
                if (!distinct.add(target)) {
                    throw invalid(path + ".targets must contain distinct item witnesses");
                }
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.WaitTicks wait) {
            requireRange(wait.ticks(), 1, 200, path + ".ticks");
            return 1;
        }
        if (node instanceof ActionDsl.WaitUntil wait) {
            validatePosition(wait.condition().target(), path + ".condition.target");
            requireRange(wait.maxTicks(), 1, MAX_ACTION_TICKS, path + ".max_ticks");
            return 1;
        }
        if (node instanceof ActionDsl.If conditional) {
            validatePredicate(conditional.condition(), path + ".condition");
            requireSequenceSize(conditional.thenBranch(), 0, MAX_BRANCH_NODES, path + ".then");
            requireSequenceSize(conditional.elseBranch(), 0, MAX_BRANCH_NODES, path + ".else");
            int thenCount = walkSequence(conditional.thenBranch(), depth + 1, walk, path + ".then");
            int elseCount = walkSequence(conditional.elseBranch(), depth + 1, walk, path + ".else");
            return boundedAdd(1, Math.max(thenCount, elseCount));
        }
        var repeat = (ActionDsl.Repeat) node;
        requireRange(repeat.count(), 1, MAX_REPEAT_COUNT, path + ".count");
        requireSequenceSize(repeat.body(), 1, MAX_BRANCH_NODES, path + ".body");
        if (containsWorldMutation(repeat.body())) {
            throw unprovable("world mutation primitives cannot occur inside repeat");
        }
        int bodyCount = walkSequence(repeat.body(), depth + 1, walk, path + ".body");
        return boundedAdd(1, boundedMultiply(bodyCount, repeat.count()));
    }

    private static boolean containsWorldMutation(List<ActionDsl.Node> nodes) {
        for (var node : nodes) {
            if (node instanceof ActionDsl.BreakKnownFace
                    || node instanceof ActionDsl.TillKnownBlock
                    || node instanceof ActionDsl.TillKnownBatch
                    || node instanceof ActionDsl.PlantKnownWheat
                    || node instanceof ActionDsl.PlantKnownWheatBatch
                    || node instanceof ActionDsl.HarvestKnownWheat
                    || node instanceof ActionDsl.HarvestKnownWheatBatch
                    || node instanceof ActionDsl.ApplyKnownBlockPlan
                    || node instanceof ActionDsl.ClearKnownBlockPlan
                    || node instanceof ActionDsl.ApplyKnownRedstoneSpec
                    || node instanceof ActionDsl.OpenKnownFenceGate
                    || node instanceof ActionDsl.OpenKnownPassage
                    || node instanceof ActionDsl.InspectKnownContainer
                    || node instanceof ActionDsl.TakeKnownContainerStack
                    || node instanceof ActionDsl.CraftKnownRecipe
                    || node instanceof ActionDsl.SmeltKnownRecipe
                    || node instanceof ActionDsl.OperateKnownMenu
                    || node instanceof ActionDsl.BrewKnownPotionBatch) return true;
            if (node instanceof ActionDsl.If conditional
                    && (containsWorldMutation(conditional.thenBranch())
                            || containsWorldMutation(conditional.elseBranch()))) return true;
            if (node instanceof ActionDsl.Repeat repeat
                    && containsWorldMutation(repeat.body())) return true;
        }
        return false;
    }

    private static void validateTerminalOwnedMenuPlacement(List<ActionDsl.Node> body) {
        for (int index = 0; index < body.size(); index++) {
            ActionDsl.Node node = body.get(index);
            if (node instanceof ActionDsl.BrewKnownPotionBatch
                    || node instanceof ActionDsl.SmeltKnownRecipe
                    || node instanceof ActionDsl.OperateKnownMenu) {
                if (index != body.size() - 1) {
                    throw invalid("long-running owned-menu operation must be the final Action node");
                }
            } else if (containsTerminalOwnedMenu(node)) {
                throw invalid("long-running owned-menu operation must be a top-level Action node");
            }
        }
    }

    private static boolean containsTerminalOwnedMenu(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BrewKnownPotionBatch
                || node instanceof ActionDsl.SmeltKnownRecipe
                || node instanceof ActionDsl.OperateKnownMenu) return true;
        if (node instanceof ActionDsl.If conditional) {
            return conditional.thenBranch().stream()
                    .anyMatch(ActionDslValidator::containsTerminalOwnedMenu)
                    || conditional.elseBranch().stream()
                            .anyMatch(ActionDslValidator::containsTerminalOwnedMenu);
        }
        return node instanceof ActionDsl.Repeat repeat
                && repeat.body().stream()
                        .anyMatch(ActionDslValidator::containsTerminalOwnedMenu);
    }

    private static void validateTerminalClearPlacement(List<ActionDsl.Node> body) {
        for (int index = 0; index < body.size(); index++) {
            ActionDsl.Node node = body.get(index);
            if (node instanceof ActionDsl.ClearKnownBlockPlan) {
                if (index != body.size() - 1) {
                    throw invalid("clear_known_block_plan must be the final Action node");
                }
            } else if (containsConstructionClear(node)) {
                throw invalid("clear_known_block_plan must be a top-level Action node");
            }
        }
    }

    private static boolean containsConstructionClear(ActionDsl.Node node) {
        if (node instanceof ActionDsl.ClearKnownBlockPlan) return true;
        if (node instanceof ActionDsl.If conditional) {
            return conditional.thenBranch().stream()
                    .anyMatch(ActionDslValidator::containsConstructionClear)
                    || conditional.elseBranch().stream()
                            .anyMatch(ActionDslValidator::containsConstructionClear);
        }
        return node instanceof ActionDsl.Repeat repeat
                && repeat.body().stream().anyMatch(ActionDslValidator::containsConstructionClear);
    }

    private static void validatePredicate(ActionDsl.Predicate predicate, String path) {
        Objects.requireNonNull(predicate, path);
        if (predicate instanceof ActionDsl.LogicalPredicate logical) {
            requireSequenceSize(
                    logical.operands(), 1, MAX_PREDICATE_OPERANDS, path + "." + logical.operator().wireName());
            for (int index = 0; index < logical.operands().size(); index++) {
                validateAtomic(logical.operands().get(index), path + "[" + index + "]");
            }
            return;
        }
        validateAtomic((ActionDsl.AtomicPredicate) predicate, path);
    }

    private static void validateAtomic(ActionDsl.AtomicPredicate predicate, String path) {
        Objects.requireNonNull(predicate, path);
        if (predicate instanceof ActionDsl.NumericPredicate numeric) {
            requireFiniteRange(numeric.value(), -2048, 2048, path + ".value");
            return;
        }
        if (predicate instanceof ActionDsl.BooleanPredicate bool) {
            if (bool.comparison() != ActionDsl.Comparison.EQ) {
                throw invalid(path + ".comparison must be eq");
            }
            return;
        }
        if (predicate instanceof ActionDsl.InventoryPredicate inventory) {
            requireResourceLocation(inventory.item(), path + ".item");
            requireRange(inventory.value(), 0, Integer.MAX_VALUE, path + ".value");
            return;
        }
        var status = (ActionDsl.StatusPredicate) predicate;
        requireResourceLocation(status.effect(), path + ".effect");
        if (status.comparison() != ActionDsl.Comparison.EQ) {
            throw invalid(path + ".comparison must be eq");
        }
    }

    private static void validatePosition(ActionDsl.Position position, String path) {
        Objects.requireNonNull(position, path);
        requireResourceLocation(position.dimension(), path + ".dimension");
        requireRange(position.x(), -30_000_000, 30_000_000, path + ".x");
        requireRange(position.y(), -2048, 2048, path + ".y");
        requireRange(position.z(), -30_000_000, 30_000_000, path + ".z");
    }

    private static void validateOffset(ActionDsl.Offset offset, String path) {
        Objects.requireNonNull(offset, path);
        requireRange(offset.x(), -MAX_BLOCK_PLAN_OFFSET, MAX_BLOCK_PLAN_OFFSET, path + ".x");
        requireRange(offset.y(), -MAX_BLOCK_PLAN_OFFSET, MAX_BLOCK_PLAN_OFFSET, path + ".y");
        requireRange(offset.z(), -MAX_BLOCK_PLAN_OFFSET, MAX_BLOCK_PLAN_OFFSET, path + ".z");
    }

    private static void validateBlockState(ActionDsl.BlockStateSpec state, String path) {
        Objects.requireNonNull(state, path);
        requireResourceLocation(state.block(), path + ".block");
        if (state.properties().size() > MAX_BLOCK_STATE_PROPERTIES) {
            throw invalid(path + ".properties contains too many values");
        }
        for (var property : state.properties().entrySet()) {
            if (!BLOCK_PROPERTY_NAME.matcher(property.getKey()).matches()) {
                throw invalid(path + ".properties contains an invalid property name");
            }
            if (!BLOCK_PROPERTY_VALUE.matcher(property.getValue()).matches()) {
                throw invalid(path + ".properties contains an invalid property value");
            }
        }
    }

    private static ActionDsl.Position transformedTarget(
            ActionDsl.Position anchor,
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.Offset rawOffset) {
        ActionDsl.Offset offset = transform.apply(rawOffset);
        return new ActionDsl.Position(
                anchor.dimension(),
                Math.toIntExact((long) anchor.x() + offset.x()),
                Math.toIntExact((long) anchor.y() + offset.y()),
                Math.toIntExact((long) anchor.z() + offset.z()));
    }

    private static ActionDsl.Position relative(
            ActionDsl.Position position, ActionDsl.BlockFace face) {
        int dx = face == ActionDsl.BlockFace.EAST ? 1
                : face == ActionDsl.BlockFace.WEST ? -1 : 0;
        int dy = face == ActionDsl.BlockFace.UP ? 1
                : face == ActionDsl.BlockFace.DOWN ? -1 : 0;
        int dz = face == ActionDsl.BlockFace.SOUTH ? 1
                : face == ActionDsl.BlockFace.NORTH ? -1 : 0;
        return new ActionDsl.Position(
                position.dimension(), position.x() + dx, position.y() + dy, position.z() + dz);
    }

    private static void validateDistinctPositions(
            List<ActionDsl.Position> positions, String path) {
        if (new HashSet<>(positions).size() != positions.size()) {
            throw invalid(path + " must contain distinct positions");
        }
    }

    private static void validateWorldPosition(ActionDsl.WorldPosition position, String path) {
        Objects.requireNonNull(position, path);
        requireResourceLocation(position.dimension(), path + ".dimension");
        requireFiniteRange(position.x(), -30_000_000, 30_000_000, path + ".x");
        requireFiniteRange(position.y(), -2_048, 2_048, path + ".y");
        requireFiniteRange(position.z(), -30_000_000, 30_000_000, path + ".z");
    }

    private static void requireResourceLocation(String value, String path) {
        requirePattern(value, RESOURCE_LOCATION, path);
        if (value.length() > 128) {
            throw invalid(path + " must contain at most 128 characters");
        }
    }

    private static void requirePattern(String value, Pattern pattern, String path) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(path + " has invalid syntax");
        }
    }

    private static void requireSequenceSize(List<?> values, int minimum, int maximum, String path) {
        Objects.requireNonNull(values, path);
        if (values.size() < minimum || values.size() > maximum) {
            throw invalid(path + " must contain " + minimum + ".." + maximum + " values");
        }
    }

    private static void requireRange(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) {
            throw invalid(path + " must be in " + minimum + ".." + maximum);
        }
    }

    private static void requireFiniteRange(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw invalid(path + " must be finite and in " + minimum + ".." + maximum);
        }
    }

    private static void requireFiniteNonNegative(double value, String path) {
        if (!Double.isFinite(value) || value < 0) {
            throw invalid(path + " must be finite and non-negative");
        }
    }

    private static int boundedAdd(int left, int right) {
        if (left > MAX_EXECUTED_NODES || right > MAX_EXECUTED_NODES
                || left > MAX_EXECUTED_NODES - right) {
            throw tooComplex("expanded execution node count exceeds " + MAX_EXECUTED_NODES);
        }
        return left + right;
    }

    private static int boundedMultiply(int value, int multiplier) {
        if (value != 0 && multiplier > MAX_EXECUTED_NODES / value) {
            throw tooComplex("expanded execution node count exceeds " + MAX_EXECUTED_NODES);
        }
        return value * multiplier;
    }

    private static ActionDslException invalid(String message) {
        return new ActionDslException(INVALID_ARGUMENT, message);
    }

    private static ActionDslException tooComplex(String message) {
        return new ActionDslException(PROGRAM_TOO_COMPLEX, message);
    }

    private static ActionDslException unprovable(String message) {
        return new ActionDslException(PROGRAM_BUDGET_UNPROVABLE, message);
    }

    public record Validation(
            int sourceNodes,
            int executedNodesUpperBound,
            Set<ActionDsl.Capability> requiredCapabilities) {
        public Validation {
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }
    }

    private static final class Walk {
        private final Set<String> ids = new HashSet<>();
        private final Set<ActionDsl.Position> breakTargets = new HashSet<>();
        private final EnumSet<ActionDsl.Capability> requiredCapabilities =
                EnumSet.noneOf(ActionDsl.Capability.class);
        private int sourceNodes;
    }
}
