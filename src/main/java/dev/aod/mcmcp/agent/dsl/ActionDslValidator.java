package dev.aod.mcmcp.agent.dsl;

import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    public static final int MAX_ACTION_TICKS = 15_000;
    public static final int MAX_KILL_ZONE_TICKS = 36_000;
    public static final int MAX_COBBLESTONE_GENERATOR_TICKS = 36_000;
    public static final long MAX_BOUNDED_INPUT_TICKS = 1_728_000L;
    public static final int MAX_KILL_ZONE_OPERATION_TICKS =
            MAX_KILL_ZONE_TICKS - ActionDslCompiler.KILL_ZONE_EFFECT_RESERVE_TICKS;
    public static final int MAX_FISHING_SOUND_WAIT_TICKS = 900;
    public static final long MAX_ACTION_DURATION_MILLIS = MAX_ACTION_TICKS * 50L;
    public static final long MAX_KILL_ZONE_DURATION_MILLIS = MAX_KILL_ZONE_TICKS * 50L;
    public static final long MAX_COBBLESTONE_GENERATOR_DURATION_MILLIS =
            MAX_COBBLESTONE_GENERATOR_TICKS * 50L;
    public static final long MAX_BOUNDED_INPUT_DURATION_MILLIS =
            MAX_BOUNDED_INPUT_TICKS * 50L;
    public static final int MAX_ACTION_CAMERA_DEGREES = 720;
    public static final int MAX_BLOCKS_BROKEN = 8;
    public static final int MAX_COBBLESTONE_GENERATOR_BREAKS = 64;
    public static final int MAX_INTERACTIONS = 16;
    public static final int MAX_CONTAINER_STACKS = MAX_INTERACTIONS - 2;
    public static final int MAX_CONTAINER_TRANSFER_COUNT = 64 * MAX_CONTAINER_STACKS;
    public static final int MAX_KILL_ZONE_ATTACKS = 2_048;
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
    private static final Pattern PLACEMENT_STATE_REF =
            Pattern.compile("psr_[0-9a-f]{32}");
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
        boolean killZoneOnly = program.body().size() == 1
                && program.body().getFirst() instanceof ActionDsl.OperateKillZone;
        boolean cobblestoneGeneratorOnly = program.body().size() == 1
                && program.body().getFirst()
                        instanceof ActionDsl.OperateKnownCobblestoneGenerator;
        boolean boundedInputsOnly = program.body().size() == 1
                && program.body().getFirst() instanceof ActionDsl.HoldBoundedInputs;
        validateRequestBudget(
                request.budget(), killZoneOnly, cobblestoneGeneratorOnly, boundedInputsOnly);

        if (program.body().isEmpty() || program.body().size() > MAX_TOP_LEVEL_NODES) {
            throw invalid("program.body must contain 1.." + MAX_TOP_LEVEL_NODES + " nodes");
        }
        validateTerminalOwnedMenuPlacement(program.body());
        validateTerminalClearPlacement(program.body());
        validateExclusiveNode(program.body(), node -> node instanceof ActionDsl.RemoveVisibleFrameItem
                        || node instanceof ActionDsl.InsertVisibleFrameItem,
                "frame item operation must be the only top-level Action node");
        validateExclusiveNode(program.body(), node -> node instanceof ActionDsl.PillarUpKnown,
                "pillar_up_known must be the only top-level Action node");
        validateExclusiveNode(program.body(),
                node -> node instanceof ActionDsl.ApproachKnownPlacement,
                "approach_known_placement must be the only top-level Action node");
        validateExclusiveNode(program.body(),
                node -> node instanceof ActionDsl.CastKnownFishingRod,
                "cast_known_fishing_rod must be the only top-level Action node");
        validateExclusiveNode(program.body(),
                node -> node instanceof ActionDsl.OperateKillZone,
                "operate_kill_zone must be the only top-level Action node");
        validateExclusiveNode(program.body(),
                node -> node instanceof ActionDsl.OperateKnownCobblestoneGenerator,
                "operate_known_cobblestone_generator must be the only top-level Action node");
        validateExclusiveNode(program.body(),
                node -> node instanceof ActionDsl.HoldBoundedInputs,
                "hold_bounded_inputs must be the only top-level Action node");
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
        validateRequestBudget(budget, false, false, false);
    }

    private static void validateRequestBudget(
            ActionDsl.Budget budget,
            boolean killZoneOnly,
            boolean cobblestoneGeneratorOnly,
            boolean boundedInputsOnly) {
        Objects.requireNonNull(budget, "budget");
        boolean longRunningOnly = killZoneOnly || cobblestoneGeneratorOnly;
        requireRange(budget.maxDurationMillis(), 100,
                boundedInputsOnly ? MAX_BOUNDED_INPUT_DURATION_MILLIS
                        : longRunningOnly ? MAX_KILL_ZONE_DURATION_MILLIS
                        : MAX_ACTION_DURATION_MILLIS,
                "budget.max_duration_ms");
        requireRange(budget.maxTicks(), 2,
                boundedInputsOnly ? MAX_BOUNDED_INPUT_TICKS
                        : longRunningOnly ? MAX_KILL_ZONE_TICKS : MAX_ACTION_TICKS,
                "budget.max_ticks");
        requireFiniteRange(
                budget.maxDistanceBlocks(), 0, NavigationDistanceBudget.MAX_DISTANCE_BLOCKS,
                "budget.max_distance_blocks");
        requireFiniteRange(
                budget.maxCameraDegrees(), 0, MAX_ACTION_CAMERA_DEGREES,
                "budget.max_camera_degrees");
        requireRange(budget.maxInteractions(), 0,
                killZoneOnly ? MAX_KILL_ZONE_ATTACKS : MAX_INTERACTIONS,
                "budget.max_interactions");
        requireRange(budget.maxBlocksBroken(), 0,
                cobblestoneGeneratorOnly
                        ? MAX_COBBLESTONE_GENERATOR_BREAKS : MAX_BLOCKS_BROKEN,
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
        if (node instanceof ActionDsl.ApproachKnownPlacement approach) {
            validatePosition(approach.anchor(), path + ".anchor");
            requireSequenceSize(
                    approach.entries(), 1, MAX_BLOCK_PLAN_ENTRIES, path + ".entries");
            var entryIds = new HashSet<String>();
            var distinctTargets = new HashSet<ActionDsl.Position>();
            for (int index = 0; index < approach.entries().size(); index++) {
                ActionDsl.BlockPlanEntry entry = approach.entries().get(index);
                String entryPath = path + ".entries[" + index + "]";
                Objects.requireNonNull(entry, entryPath);
                requirePattern(entry.id(), NODE_ID, entryPath + ".id");
                if (!entryIds.add(entry.id())) {
                    throw invalid(path + ".entries must contain unique ids");
                }
                validateOffset(entry.offset(), entryPath + ".offset");
                ActionDsl.Position target = transformedTarget(
                        approach.anchor(), approach.transform(), entry.offset());
                validatePosition(target, entryPath + ".offset");
                if (!distinctTargets.add(target)) {
                    throw invalid(path + ".entries must produce distinct transformed targets");
                }
                if (entry.sourceState().isPresent() || entry.item().isPresent()
                        || entry.placementStateRef().isEmpty()) {
                    throw invalid(entryPath + " must use placement_state_ref");
                }
                requirePattern(entry.placementStateRef().orElseThrow(),
                        PLACEMENT_STATE_REF, entryPath + ".placement_state_ref");

                ActionDsl.PlacementSupport support = entry.support();
                validatePosition(support.position(), entryPath + ".support.position");
                if (!approach.anchor().dimension().equals(support.position().dimension())) {
                    throw invalid(entryPath + ".support.position must use the anchor dimension");
                }
                if (support.face() != ActionDsl.BlockFace.UP) {
                    throw invalid(entryPath + ".support.face must be up");
                }
                if (support.expectedState().isEmpty()
                        || support.dependencyEntryId().isPresent()) {
                    throw invalid(entryPath
                            + ".support must use expected_state and null dependency_entry_id");
                }
                validateBlockState(support.expectedState().orElseThrow(),
                        entryPath + ".support.expected_state");
                if (!relative(support.position(), support.face()).equals(target)) {
                    throw invalid(entryPath
                            + ".support face must point from support position to target");
                }
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.FaceKnownPosition face) {
            validatePosition(face.target(), path + ".target");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            return 1;
        }
        if (node instanceof ActionDsl.FaceKnownBlockFace face) {
            validatePosition(face.target(), path + ".target");
            requireResourceLocation(face.expectedBlock(), path + ".expected_block");
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
        if (node instanceof ActionDsl.BreakKnownBlock block) {
            validatePosition(block.target(), path + ".target");
            if (!walk.breakTargets.add(block.target())) {
                throw unprovable("A break target cannot occur more than once: "
                        + block.target());
            }
            validateBlockState(block.expectedState(), path + ".expected_state");
            if (!SafeBreakSourcePolicy.allowsKnownBlockCombination(
                    block.expectedState().block(), block.toolItem(), block.expectedDrop())) {
                throw invalid(path
                        + " block/tool/drop combination is outside the closed safe allowlist");
            }
            if (BREAKABLE_LOGS.contains(block.expectedState().block())) {
                if (!block.expectedState().properties().keySet().equals(Set.of("axis"))
                        || !Set.of("x", "y", "z").contains(
                                block.expectedState().properties().get("axis"))) {
                    throw invalid(path + ".expected_state must be one complete log state");
                }
            } else if (!block.expectedState().properties().isEmpty()) {
                throw invalid(path + ".expected_state must be one complete cobblestone state");
            }
            requireRange(block.minimumInventoryCount(), 1, 2_304,
                    path + ".minimum_inventory_count");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            validatePosition(operation.target(), path + ".target");
            validateBlockState(operation.expectedState(), path + ".expected_state");
            if (!"minecraft:cobblestone".equals(operation.expectedState().block())
                    || !operation.expectedState().properties().isEmpty()) {
                throw invalid(path + ".expected_state must be exact minecraft:cobblestone");
            }
            if (!"minecraft:iron_pickaxe".equals(operation.toolItem())) {
                throw invalid(path + ".tool_item must be minecraft:iron_pickaxe");
            }
            if (!"minecraft:cobblestone".equals(operation.expectedDrop())) {
                throw invalid(path + ".expected_drop must be minecraft:cobblestone");
            }
            requireRange(operation.minimumInventoryCount(), 1, 2_304,
                    path + ".minimum_inventory_count");
            requireRange(operation.maxBreaks(), 1, MAX_COBBLESTONE_GENERATOR_BREAKS,
                    path + ".max_breaks");
            requireRange(operation.regenerationWaitTicks(), 1, 200,
                    path + ".regeneration_wait_ticks");
            requireRange(operation.maxOperationDurationTicks(), 1,
                    MAX_COBBLESTONE_GENERATOR_TICKS,
                    path + ".max_operation_duration_ticks");
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            return 1;
        }
        if (node instanceof ActionDsl.HoldBoundedInputs hold) {
            requireSequenceSize(hold.inputs(), 1, ActionDsl.BoundedInput.values().length,
                    path + ".inputs");
            var distinctInputs = EnumSet.noneOf(ActionDsl.BoundedInput.class);
            for (int index = 0; index < hold.inputs().size(); index++) {
                ActionDsl.BoundedInput input = Objects.requireNonNull(
                        hold.inputs().get(index), path + ".inputs[" + index + "]");
                if (!distinctInputs.add(input)) {
                    throw invalid(path + ".inputs must not contain duplicates");
                }
            }
            requireRange(hold.durationTicks(), 1, MAX_BOUNDED_INPUT_TICKS,
                    path + ".duration_ticks");
            boolean attacks = distinctInputs.contains(ActionDsl.BoundedInput.ATTACK);
            boolean uses = distinctInputs.contains(ActionDsl.BoundedInput.USE);
            if (distinctInputs.contains(ActionDsl.BoundedInput.FORWARD)
                    && distinctInputs.contains(ActionDsl.BoundedInput.BACK)) {
                throw invalid(path + ".inputs cannot combine forward and back");
            }
            if (distinctInputs.contains(ActionDsl.BoundedInput.LEFT)
                    && distinctInputs.contains(ActionDsl.BoundedInput.RIGHT)) {
                throw invalid(path + ".inputs cannot combine left and right");
            }
            if (attacks && uses) {
                throw invalid(path + ".inputs cannot combine attack and use");
            }
            boolean changesAimOrPosition = distinctInputs.stream().anyMatch(input -> switch (input) {
                case FORWARD, BACK, LEFT, RIGHT, JUMP -> true;
                case SNEAK, ATTACK, USE -> false;
            });
            if ((attacks || uses) && changesAimOrPosition) {
                throw invalid(path
                        + ".inputs cannot combine attack/use with movement or jump; sneak is allowed");
            }
            boolean needsGuard = attacks || uses;
            if (hold.targetGuard().isPresent() != needsGuard
                    || hold.selectedItem().isPresent() != needsGuard) {
                throw invalid(path
                        + " attack/use requires target_guard and selected_item; movement-only holds forbid both");
            }
            if (needsGuard) {
                ActionDsl.ExactBlockTargetGuard guard = hold.targetGuard().orElseThrow();
                validatePosition(guard.target(), path + ".target_guard.target");
                validateBlockState(guard.expectedState(), path + ".target_guard.expected_state");
                requireResourceLocation(hold.selectedItem().orElseThrow(),
                        path + ".selected_item");
            }
            if (distinctInputs.stream().anyMatch(input -> switch (input) {
                case FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK -> true;
                case ATTACK, USE -> false;
            })) {
                walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            }
            if (attacks) walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_BREAK);
            if (uses) walk.requiredCapabilities.add(ActionDsl.Capability.ITEM_USE);
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
                entry.sourceState().ifPresent(state ->
                        validateBlockState(state, entryPath + ".source_state"));
                entry.item().ifPresent(item ->
                        requireResourceLocation(item, entryPath + ".item"));
                entry.placementStateRef().ifPresent(ref ->
                        requirePattern(ref, PLACEMENT_STATE_REF,
                                entryPath + ".placement_state_ref"));

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
        if (node instanceof ActionDsl.PillarUpKnown pillar) {
            validatePosition(pillar.support(), path + ".support");
            validateBlockState(pillar.expectedSupport(), path + ".expected_support");
            pillar.sourceState().ifPresent(state ->
                    validateBlockState(state, path + ".source_state"));
            pillar.item().ifPresent(item ->
                    requireResourceLocation(item, path + ".item"));
            pillar.placementStateRef().ifPresent(ref ->
                    requirePattern(ref, PLACEMENT_STATE_REF, path + ".placement_state_ref"));
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.BLOCK_PLACE);
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
            validateRoutingLabel(inspect.routingLabel(), path);
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
            requireRange(take.maxStacks(), 1, MAX_CONTAINER_STACKS, path + ".max_stacks");
            requireRange(take.maxTransferCount(), 1, MAX_CONTAINER_TRANSFER_COUNT,
                    path + ".max_transfer_count");
            validateRoutingLabel(take.routingLabel(), path);
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.INVENTORY_TRANSFER);
            return 1;
        }
        if (node instanceof ActionDsl.StoreKnownContainerStack store) {
            validatePosition(store.target(), path + ".target");
            if (!KNOWN_CONTAINERS.contains(store.expectedBlock())) {
                throw invalid(path + ".expected_block must be minecraft:chest or minecraft:barrel");
            }
            requirePattern(store.item(), RESOURCE_LOCATION, path + ".item");
            if (!STACK_POLICIES.contains(store.stackPolicy())) {
                throw invalid(path + ".stack_policy is unsupported");
            }
            requireRange(store.minimumContainerCount(), 1, 3_456,
                    path + ".minimum_container_count");
            requireRange(store.maxStacks(), 1, MAX_CONTAINER_STACKS, path + ".max_stacks");
            requireRange(store.maxTransferCount(), 1, MAX_CONTAINER_TRANSFER_COUNT,
                    path + ".max_transfer_count");
            validateRoutingLabel(store.routingLabel(), path);
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
            requireRange(smelt.maxSmelts(), 1, 64, path + ".max_smelts");
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
        if (node instanceof ActionDsl.RemoveVisibleFrameItem remove) {
            requirePattern(remove.entityRef(), OPAQUE_REFERENCE, path + ".entity_ref");
            requireResourceLocation(remove.expectedItem(), path + ".expected_item");
            if ("minecraft:air".equals(remove.expectedItem())) {
                throw invalid(path + ".expected_item must not be air");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.ENTITY_ATTACK);
            return 1;
        }
        if (node instanceof ActionDsl.InsertVisibleFrameItem insert) {
            requirePattern(insert.entityRef(), OPAQUE_REFERENCE, path + ".entity_ref");
            requireResourceLocation(insert.item(), path + ".item");
            if ("minecraft:air".equals(insert.item())) {
                throw invalid(path + ".item must not be air");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.ITEM_USE);
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
        if (node instanceof ActionDsl.CastKnownFishingRod cast) {
            validateFishingHandAndRod(cast.hand(), cast.rodItem(), path);
            validatePosition(cast.target(), path + ".target");
            validateBlockState(cast.expectedState(), path + ".expected_state");
            if (!"minecraft:water".equals(cast.expectedState().block())
                    || !"0".equals(cast.expectedState().properties().get("level"))) {
                throw invalid(path + ".expected_state must be exact source water");
            }
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            walk.requiredCapabilities.add(ActionDsl.Capability.ITEM_USE);
            return 1;
        }
        if (node instanceof ActionDsl.ReelKnownFishingSession reel) {
            validateFishingHandAndRod(reel.hand(), reel.rodItem(), path);
            requirePattern(reel.fishingSessionRef(), OPAQUE_REFERENCE,
                    path + ".fishing_session_ref");
            walk.requiredCapabilities.add(ActionDsl.Capability.ITEM_USE);
            return 1;
        }
        if (node instanceof ActionDsl.OperateKillZone operation) {
            validateWorldBounds(operation.targetKillZoneBounds(),
                    path + ".target_kill_zone_bounds");
            var bounds = operation.targetKillZoneBounds();
            if (bounds.max().x() - bounds.min().x() > 32.0D
                    || bounds.max().y() - bounds.min().y() > 16.0D
                    || bounds.max().z() - bounds.min().z() > 32.0D) {
                throw invalid(path + ".target_kill_zone_bounds is not local");
            }
            requireSequenceSize(operation.entityTypeAllowlist(), 1,
                    dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore.MAX_ENTITY_TYPES,
                    path + ".entity_type_allowlist");
            var distinctTypes = new HashSet<String>();
            for (int index = 0; index < operation.entityTypeAllowlist().size(); index++) {
                String type = operation.entityTypeAllowlist().get(index);
                requireResourceLocation(type, path + ".entity_type_allowlist[" + index + "]");
                if ("minecraft:player".equals(type) || !distinctTypes.add(type)) {
                    throw invalid(path + ".entity_type_allowlist contains a player or duplicate");
                }
            }
            requireResourceLocation(operation.mainHandItem(), path + ".main_hand_item");
            operation.consentRef().ifPresent(ref -> requirePattern(
                    ref, OPAQUE_REFERENCE, path + ".consent_ref"));
            requireRange(operation.maxAttacks(), 1, MAX_KILL_ZONE_ATTACKS,
                    path + ".max_attacks");
            requireRange(operation.minimumIntervalTicks(),
                    dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore.MIN_MINIMUM_INTERVAL_TICKS,
                    dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore.MAX_MINIMUM_INTERVAL_TICKS,
                    path + ".minimum_interval_ticks");
            requireRange(operation.maxOperationDurationTicks(), 1, MAX_KILL_ZONE_OPERATION_TICKS,
                    path + ".max_operation_duration_ticks");
            walk.requiredCapabilities.add(ActionDsl.Capability.ENTITY_ATTACK);
            return 1;
        }
        if (node instanceof ActionDsl.WaitTicks wait) {
            requireRange(wait.ticks(), 1, MAX_ACTION_TICKS, path + ".ticks");
            return 1;
        }
        if (node instanceof ActionDsl.WaitUntil wait) {
            if (wait.condition() instanceof ActionDsl.CropMatureCondition crop) {
                validatePosition(crop.target(), path + ".condition.target");
            } else {
                var sound = (ActionDsl.SoundClueCondition) wait.condition();
                if (!"minecraft:entity.fishing_bobber.splash".equals(sound.soundEvent())) {
                    throw invalid(path + ".condition.sound_event is unsupported");
                }
                requireRange(sound.sinceTick(), 0, Long.MAX_VALUE,
                        path + ".condition.since_tick");
                requireResourceLocation(sound.bounds().dimension(),
                        path + ".condition.bounds.dimension");
                validateWorldBounds(sound.bounds(), path + ".condition.bounds");
                requireRange(wait.maxTicks(), 1, MAX_FISHING_SOUND_WAIT_TICKS,
                        path + ".max_ticks");
            }
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
                    || node instanceof ActionDsl.BreakKnownBlock
                    || node instanceof ActionDsl.OperateKnownCobblestoneGenerator
                    || node instanceof ActionDsl.HoldBoundedInputs hold
                            && (hold.inputs().contains(ActionDsl.BoundedInput.ATTACK)
                                    || hold.inputs().contains(ActionDsl.BoundedInput.USE))
                    || node instanceof ActionDsl.TillKnownBlock
                    || node instanceof ActionDsl.TillKnownBatch
                    || node instanceof ActionDsl.PlantKnownWheat
                    || node instanceof ActionDsl.PlantKnownWheatBatch
                    || node instanceof ActionDsl.HarvestKnownWheat
                    || node instanceof ActionDsl.HarvestKnownWheatBatch
                    || node instanceof ActionDsl.ApplyKnownBlockPlan
                    || node instanceof ActionDsl.ClearKnownBlockPlan
                    || node instanceof ActionDsl.PillarUpKnown
                    || node instanceof ActionDsl.ApplyKnownRedstoneSpec
                    || node instanceof ActionDsl.OpenKnownFenceGate
                    || node instanceof ActionDsl.OpenKnownPassage
                    || node instanceof ActionDsl.InspectKnownContainer
                    || node instanceof ActionDsl.TakeKnownContainerStack
                    || node instanceof ActionDsl.StoreKnownContainerStack
                    || node instanceof ActionDsl.RemoveVisibleFrameItem
                    || node instanceof ActionDsl.InsertVisibleFrameItem
                    || node instanceof ActionDsl.CraftKnownRecipe
                    || node instanceof ActionDsl.SmeltKnownRecipe
                    || node instanceof ActionDsl.OperateKnownMenu
                    || node instanceof ActionDsl.BrewKnownPotionBatch
                    || node instanceof ActionDsl.CastKnownFishingRod
                    || node instanceof ActionDsl.ReelKnownFishingSession) return true;
            if (node instanceof ActionDsl.If conditional
                    && (containsWorldMutation(conditional.thenBranch())
                            || containsWorldMutation(conditional.elseBranch()))) return true;
            if (node instanceof ActionDsl.Repeat repeat
                    && containsWorldMutation(repeat.body())) return true;
        }
        return false;
    }

    private static void validateFishingHandAndRod(String hand, String rodItem, String path) {
        if (!Set.of("main_hand", "off_hand").contains(hand)) {
            throw invalid(path + ".hand is unsupported");
        }
        if (!"minecraft:fishing_rod".equals(rodItem)) {
            throw invalid(path + ".rod_item must be minecraft:fishing_rod");
        }
    }

    private static void validateWorldBounds(ActionDsl.WorldBounds bounds, String path) {
        ActionDsl.WorldPoint min = bounds.min();
        ActionDsl.WorldPoint max = bounds.max();
        requireFiniteRange(min.x(), -30_000_000, 30_000_000, path + ".min.x");
        requireFiniteRange(min.y(), -2_048, 2_048, path + ".min.y");
        requireFiniteRange(min.z(), -30_000_000, 30_000_000, path + ".min.z");
        requireFiniteRange(max.x(), -30_000_000, 30_000_000, path + ".max.x");
        requireFiniteRange(max.y(), -2_048, 2_048, path + ".max.y");
        requireFiniteRange(max.z(), -30_000_000, 30_000_000, path + ".max.z");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()
                || max.x() - min.x() > 64.0D
                || max.y() - min.y() > 64.0D
                || max.z() - min.z() > 64.0D) {
            throw invalid(path + " must be ordered and at most 64 blocks per axis");
        }
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

    private static void validateExclusiveNode(
            List<ActionDsl.Node> body, Predicate<ActionDsl.Node> matches, String diagnostic) {
        boolean contains = body.stream().anyMatch(node -> containsMatching(node, matches));
        if (contains && (body.size() != 1 || !matches.test(body.getFirst()))) {
            throw invalid(diagnostic);
        }
    }

    private static boolean containsMatching(
            ActionDsl.Node node, Predicate<ActionDsl.Node> matches) {
        if (matches.test(node)) return true;
        if (node instanceof ActionDsl.If conditional) {
            return conditional.thenBranch().stream()
                    .anyMatch(child -> containsMatching(child, matches))
                    || conditional.elseBranch().stream()
                            .anyMatch(child -> containsMatching(child, matches));
        }
        return node instanceof ActionDsl.Repeat repeat
                && repeat.body().stream().anyMatch(child -> containsMatching(child, matches));
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

    private static void validateRoutingLabel(
            Optional<ActionDsl.RoutingLabel> routingLabel, String path) {
        routingLabel.ifPresent(label -> {
            requirePattern(label.entityRef(), OPAQUE_REFERENCE,
                    path + ".routing_label.entity_ref");
            requireResourceLocation(label.item(), path + ".routing_label.item");
        });
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
