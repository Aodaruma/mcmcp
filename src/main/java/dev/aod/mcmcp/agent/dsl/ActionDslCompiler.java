package dev.aod.mcmcp.agent.dsl;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.navigation.NavigationDistanceBudget;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.CAPABILITY_DENIED;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.PROGRAM_BUDGET_UNPROVABLE;

/** Component-wise worst-case compiler for one already closed Action DSL v1 tree. */
public final class ActionDslCompiler {
    public static final ActionDsl.Budget PHASE_ONE_HARD_LIMIT = new ActionDsl.Budget(
            ActionDslValidator.MAX_ACTION_DURATION_MILLIS,
            ActionDslValidator.MAX_ACTION_TICKS, NavigationDistanceBudget.MAX_DISTANCE_BLOCKS,
            ActionDslValidator.MAX_ACTION_CAMERA_DEGREES,
            ActionDslValidator.MAX_INTERACTIONS,
            ActionDslValidator.MAX_BLOCKS_BROKEN,
            ActionDslValidator.MAX_BLOCKS_PLACED);
    private static final long NOMINAL_TICK_MILLIS = 50;
    private static final long BLOCK_PLAN_TICKS_PER_ENTRY = 300;
    private static final double BLOCK_PLAN_CAMERA_DEGREES_PER_ENTRY = 80;
    public static final long PILLAR_UP_TICKS = 300L;
    public static final long PILLAR_UP_DURATION_MILLIS =
            PILLAR_UP_TICKS * NOMINAL_TICK_MILLIS;
    public static final long KNOWN_BREWING_TICKS = 1_400L;
    public static final long KNOWN_BREWING_DURATION_MILLIS = 70_000L;
    public static final long KNOWN_BREWING_INTERACTIONS = 16L;
    private static final long KNOWN_SMELTING_BASE_TICKS = 2_200L;
    private static final long KNOWN_SMELTING_TICKS_PER_ITEM = 200L;
    public static final long KNOWN_SMELTING_INTERACTIONS = 7L;
    public static final long KNOWN_MENU_OPERATION_TICKS = 600L;
    public static final long KNOWN_MENU_OPERATION_DURATION_MILLIS =
            KNOWN_MENU_OPERATION_TICKS * NOMINAL_TICK_MILLIS;
    public static final long KNOWN_MENU_OPERATION_INTERACTIONS = 1L;
    public static final long KNOWN_CRAFTING_TICKS =
            AgentPrimitivePlanner.CONTAINER_TICK_UPPER_BOUND;
    public static final long KNOWN_CRAFTING_DURATION_MILLIS =
            KNOWN_CRAFTING_TICKS * NOMINAL_TICK_MILLIS;

    private ActionDslCompiler() {
    }

    public static CompiledProgram compile(
            ActionDsl.Request request,
            PrimitiveCostModel primitiveCosts,
            Set<ActionDsl.Capability> locallyAllowedCapabilities) {
        return compile(request, primitiveCosts, locallyAllowedCapabilities, PHASE_ONE_HARD_LIMIT);
    }

    public static CompiledProgram compile(
            ActionDsl.Request request,
            PrimitiveCostModel primitiveCosts,
            Set<ActionDsl.Capability> locallyAllowedCapabilities,
            ActionDsl.Budget localHardLimit) {
        Objects.requireNonNull(primitiveCosts, "primitiveCosts");
        Objects.requireNonNull(locallyAllowedCapabilities, "locallyAllowedCapabilities");
        ActionDslValidator.Validation validation = ActionDslValidator.validate(request);
        ActionDslValidator.validateHardLimit(localHardLimit);

        if (!locallyAllowedCapabilities.containsAll(request.program().capabilities())) {
            throw new ActionDslException(
                    CAPABILITY_DENIED,
                    "A declared program capability is not locally allowed");
        }

        var primitiveCostBounds = new LinkedHashMap<String, Cost>();
        Cost cost = compileSequence(
                request.program().body(), primitiveCosts, primitiveCostBounds);
        ActionDsl.Budget effectiveBudget = minimum(request.budget(), localHardLimit);
        requireWithinBudget(cost, effectiveBudget);
        return new CompiledProgram(
                request,
                validation.sourceNodes(),
                validation.executedNodesUpperBound(),
                validation.requiredCapabilities(),
                cost,
                effectiveBudget,
                primitiveCostBounds);
    }

    private static Cost compileSequence(
            List<ActionDsl.Node> nodes,
            PrimitiveCostModel primitiveCosts,
            Map<String, Cost> primitiveCostBounds) {
        Cost result = Cost.ZERO;
        for (ActionDsl.Node node : nodes) {
            result = add(result, compileNode(node, primitiveCosts, primitiveCostBounds));
        }
        return result;
    }

    private static Cost compileNode(
            ActionDsl.Node node,
            PrimitiveCostModel primitiveCosts,
            Map<String, Cost> primitiveCostBounds) {
        if (node instanceof ActionDsl.WaitTicks wait) {
            return compileWait(node, wait.ticks(), primitiveCostBounds);
        }
        if (node instanceof ActionDsl.WaitUntil wait) {
            return compileWait(node, wait.maxTicks(), primitiveCostBounds);
        }
        if (node instanceof ActionDsl.ApplyKnownBlockPlan plan) {
            Cost cost = Objects.requireNonNull(
                    primitiveCosts.worstCase(node), "primitive cost result")
                    .orElseGet(() -> intrinsicKnownBlockPlanCost(
                            plan.entries().size(), knownBlockPlanPlacements(plan)));
            requireKnownBlockPlanCost(cost, plan.entries().size());
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.ClearKnownBlockPlan plan) {
            Cost cost = intrinsicKnownBlockClearCost(plan.entries().size());
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.PillarUpKnown) {
            Cost cost = Objects.requireNonNull(
                    primitiveCosts.worstCase(node), "primitive cost result")
                    .orElseGet(ActionDslCompiler::intrinsicPillarUpCost);
            if (!cost.equals(intrinsicPillarUpCost())) {
                throw unprovable("pillar_up_known has an invalid primitive bound");
            }
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.ApplyKnownRedstoneSpec redstone) {
            int outputCount = (int) redstone.components().stream()
                    .filter(component -> component.role()
                            == dev.aod.mcmcp.redstone.RedstoneSpec.Role.OUTPUT)
                    .count();
            int wireCount = (int) redstone.components().stream()
                    .filter(component -> component.role()
                            == dev.aod.mcmcp.redstone.RedstoneSpec.Role.WIRE)
                    .count();
            Cost cost = intrinsicKnownRedstoneCost(
                    redstone.timing().settleTicks(), outputCount, wireCount);
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.NavigateToKnown
                || node instanceof ActionDsl.ApproachKnownSurface
                || node instanceof ActionDsl.FaceKnownPosition
                || node instanceof ActionDsl.BreakKnownFace
                || node instanceof ActionDsl.TillKnownBlock
                || node instanceof ActionDsl.TillKnownBatch
                || node instanceof ActionDsl.PlantKnownWheat
                || node instanceof ActionDsl.PlantKnownWheatBatch
                || node instanceof ActionDsl.HarvestKnownWheat
                || node instanceof ActionDsl.HarvestKnownWheatBatch
                || node instanceof ActionDsl.OpenKnownFenceGate
                || node instanceof ActionDsl.OpenKnownPassage
                || node instanceof ActionDsl.InspectKnownContainer
                || node instanceof ActionDsl.TakeKnownContainerStack
                || node instanceof ActionDsl.CraftKnownRecipe
                || node instanceof ActionDsl.SmeltKnownRecipe
                || node instanceof ActionDsl.OperateKnownMenu
                || node instanceof ActionDsl.BrewKnownPotionBatch
                || node instanceof ActionDsl.CollectVisibleItem
                || node instanceof ActionDsl.CollectVisibleItemBatch) {
            Optional<Cost> resolved = Objects.requireNonNull(
                    primitiveCosts.worstCase(node), "primitive cost result");
            if (resolved.isEmpty()) {
                throw unprovable("A primitive worst-case cost is unavailable");
            }
            Cost cost = resolved.get();
            if (node instanceof ActionDsl.BreakKnownFace) {
                if (cost.distanceBlocks() != 0
                        || cost.interactions() != 0
                        || cost.blocksBroken() != 1
                        || cost.blocksPlaced() != 0) {
                    throw unprovable(
                            "break_known_face must consume exactly one break and no movement, interaction, or place");
                }
            } else if (node instanceof ActionDsl.TillKnownBlock) {
                requireMutationCost(cost, 1, 0, 0, "till_known_block");
            } else if (node instanceof ActionDsl.TillKnownBatch batch) {
                requireMutationCost(cost, batch.targets().size(), 0, 0, "till_known_batch");
            } else if (node instanceof ActionDsl.PlantKnownWheat) {
                requireMutationCost(cost, 0, 0, 1, "plant_known_wheat");
            } else if (node instanceof ActionDsl.PlantKnownWheatBatch batch) {
                requireMutationCost(cost, 0, 0, batch.targets().size(),
                        "plant_known_wheat_batch");
            } else if (node instanceof ActionDsl.HarvestKnownWheat) {
                requireMutationCost(cost, 0, 1, 0, "harvest_known_wheat");
            } else if (node instanceof ActionDsl.HarvestKnownWheatBatch batch) {
                requireMutationCost(cost, 0, batch.targets().size(), 0,
                        "harvest_known_wheat_batch");
            } else if (node instanceof ActionDsl.OpenKnownFenceGate) {
                requireMutationCost(cost, 1, 0, 0, "open_known_fence_gate");
            } else if (node instanceof ActionDsl.OpenKnownPassage) {
                requireMutationCost(cost, 1, 0, 0, "open_known_passage");
            } else if (node instanceof ActionDsl.InspectKnownContainer) {
                requireMutationCost(cost, 1, 0, 0, "inspect_known_container");
            } else if (node instanceof ActionDsl.TakeKnownContainerStack) {
                requireMutationCost(cost, 3, 0, 0, "take_known_container_stack");
            } else if (node instanceof ActionDsl.CraftKnownRecipe craft) {
                requireMutationCost(
                        cost, knownCraftInteractions(craft.maxCrafts()), 0, 0,
                        "craft_known_recipe");
                if (cost.durationMillis() != KNOWN_CRAFTING_DURATION_MILLIS
                        || cost.ticks() != KNOWN_CRAFTING_TICKS) {
                    throw unprovable("craft_known_recipe has an invalid primitive time bound");
                }
            } else if (node instanceof ActionDsl.SmeltKnownRecipe smelt) {
                requireMutationCost(
                        cost, KNOWN_SMELTING_INTERACTIONS, 0, 0,
                        "smelt_known_recipe");
                if (cost.durationMillis() != knownSmeltingDurationMillis(smelt.maxSmelts())
                        || cost.ticks() != knownSmeltingTicks(smelt.maxSmelts())) {
                    throw unprovable("smelt_known_recipe has an invalid primitive time bound");
                }
            } else if (node instanceof ActionDsl.OperateKnownMenu) {
                requireMutationCost(
                        cost, KNOWN_MENU_OPERATION_INTERACTIONS, 0, 0,
                        "operate_known_menu");
                if (cost.durationMillis() != KNOWN_MENU_OPERATION_DURATION_MILLIS
                        || cost.ticks() != KNOWN_MENU_OPERATION_TICKS
                        || cost.cameraDegrees() != 0) {
                    throw unprovable("operate_known_menu has an invalid primitive bound");
                }
            } else if (node instanceof ActionDsl.BrewKnownPotionBatch) {
                requireMutationCost(
                        cost, KNOWN_BREWING_INTERACTIONS, 0, 0,
                        "brew_known_potion_batch");
                if (cost.durationMillis() != KNOWN_BREWING_DURATION_MILLIS
                        || cost.ticks() != KNOWN_BREWING_TICKS) {
                    throw unprovable(
                            "brew_known_potion_batch has an invalid primitive time bound");
                }
            } else if (cost.interactions() != 0
                    || cost.blocksBroken() != 0
                    || cost.blocksPlaced() != 0) {
                throw unprovable("Non-breaking primitive cost contains an interaction, break, or place");
            }
            if (node instanceof ActionDsl.FaceKnownPosition && cost.distanceBlocks() != 0) {
                throw unprovable("face_known_position cannot consume movement distance");
            }
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.If conditional) {
            Cost thenCost = compileSequence(
                    conditional.thenBranch(), primitiveCosts, primitiveCostBounds);
            Cost elseCost = compileSequence(
                    conditional.elseBranch(), primitiveCosts, primitiveCostBounds);
            return componentMaximum(thenCost, elseCost);
        }
        var repeat = (ActionDsl.Repeat) node;
        return multiply(
                compileSequence(repeat.body(), primitiveCosts, primitiveCostBounds),
                repeat.count());
    }

    private static Cost compileWait(
            ActionDsl.Node node, int ticks, Map<String, Cost> primitiveCostBounds) {
        Cost cost = intrinsicWaitCost(ticks);
        primitiveCostBounds.put(node.id(), cost);
        return cost;
    }

    /** Exact structural cost shared by compile-time and JIT wait admission. */
    public static Cost intrinsicWaitCost(int ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("wait ticks must be positive");
        }
        return new Cost(
                multiplyExact(ticks, NOMINAL_TICK_MILLIS),
                ticks, 0, 0, 0, 0, 0);
    }

    /** Structural bound for the stationary place-only block-plan adapter. */
    public static Cost intrinsicKnownBlockPlanCost(int entries) {
        return intrinsicKnownBlockPlanCost(entries, entries);
    }

    public static Cost intrinsicKnownBlockPlanCost(int entries, long placements) {
        if (entries < 1 || entries > ActionDslValidator.MAX_BLOCK_PLAN_ENTRIES) {
            throw new IllegalArgumentException("block plan entry count is outside the closed bound");
        }
        if (placements < entries || placements > entries * 2L) {
            throw new IllegalArgumentException("block plan footprint is outside the closed bound");
        }
        long ticks = Math.multiplyExact(BLOCK_PLAN_TICKS_PER_ENTRY, entries);
        return new Cost(
                multiplyExact(ticks, NOMINAL_TICK_MILLIS),
                ticks,
                0,
                BLOCK_PLAN_CAMERA_DEGREES_PER_ENTRY * entries,
                0,
                0,
                placements);
    }

    public static long knownBlockPlanPlacements(ActionDsl.ApplyKnownBlockPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return plan.entries().stream().mapToLong(entry ->
                entry.sourceState()
                        .map(state -> (long) SafeConstructionBlocks
                                .placementCellCount(state.block()))
                        // An unresolved opaque identity may be the admitted two-cell door.
                        // Runtime admission supplies the exact remembered footprint when known.
                        .orElse(2L)).sum();
    }

    private static void requireKnownBlockPlanCost(Cost cost, int entries) {
        long placements = cost.blocksPlaced();
        if (placements < entries || placements > entries * 2L
                || !cost.equals(intrinsicKnownBlockPlanCost(entries, placements))) {
            throw unprovable("apply_known_block_plan has an invalid primitive bound");
        }
    }

    /** Structural bound for the stationary break-only construction adapter. */
    public static Cost intrinsicKnownBlockClearCost(int entries) {
        Cost place = intrinsicKnownBlockPlanCost(entries);
        return new Cost(
                place.durationMillis(), place.ticks(), place.distanceBlocks(),
                place.cameraDegrees(), place.interactions(), entries, 0);
    }

    /** Fixed bound for one jump, one placement, and restoring the admitted camera pose. */
    public static Cost intrinsicPillarUpCost() {
        return new Cost(
                PILLAR_UP_DURATION_MILLIS,
                PILLAR_UP_TICKS,
                2.0D,
                360.0D,
                0,
                0,
                1);
    }

    /** Structural bound for the original one-output identity circuit. */
    public static Cost intrinsicKnownRedstoneCost(int settleTicks) {
        return intrinsicKnownRedstoneCost(settleTicks, 1);
    }

    /** Structural bound for one or two lamps, one lever, two toggles, and three observations. */
    public static Cost intrinsicKnownRedstoneCost(int settleTicks, int outputCount) {
        return intrinsicKnownRedstoneCost(settleTicks, outputCount, 0);
    }

    /** Structural bound for the closed direct, fan-out, and one-dust identity slices. */
    public static Cost intrinsicKnownRedstoneCost(
            int settleTicks, int outputCount, int wireCount) {
        if (settleTicks < 1
                || settleTicks > dev.aod.mcmcp.redstone.RedstoneSpec.MAX_SETTLE_TICKS) {
            throw new IllegalArgumentException("redstone settle ticks are outside the closed bound");
        }
        if (outputCount < 1 || outputCount > 2
                || wireCount < 0 || wireCount > 1
                || outputCount == 2 && wireCount != 0) {
            throw new IllegalArgumentException("redstone layout is outside the closed bound");
        }
        long placements = outputCount + 1L + wireCount;
        long ticks = Math.addExact(
                Math.multiplyExact(
                        placements + 2L, AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND),
                Math.multiplyExact(3L, settleTicks));
        return new Cost(
                multiplyExact(ticks, NOMINAL_TICK_MILLIS),
                ticks,
                0,
                ActionDslValidator.MAX_ACTION_CAMERA_DEGREES,
                2,
                0,
                placements);
    }

    /** Initial open plus three operations per craft, with one conservative safety slot. */
    public static long knownCraftInteractions(int maxCrafts) {
        if (maxCrafts < 1 || maxCrafts > 3) {
            throw new IllegalArgumentException("max crafts is outside the closed Action bound");
        }
        return 1L + Math.multiplyExact(4L, maxCrafts);
    }

    /** GUI/release margin plus the Vanilla maximum 200-tick cook time per item. */
    public static long knownSmeltingTicks(int maxSmelts) {
        if (maxSmelts < 1 || maxSmelts > 64) {
            throw new IllegalArgumentException("max smelts is outside the closed Action bound");
        }
        return Math.addExact(
                KNOWN_SMELTING_BASE_TICKS,
                Math.multiplyExact(KNOWN_SMELTING_TICKS_PER_ITEM, maxSmelts));
    }

    public static long knownSmeltingDurationMillis(int maxSmelts) {
        return multiplyExact(knownSmeltingTicks(maxSmelts), NOMINAL_TICK_MILLIS);
    }

    private static void requireMutationCost(
            Cost cost, long interactions, long breaks, long placements, String operation) {
        if (cost.distanceBlocks() != 0
                || cost.interactions() != interactions
                || cost.blocksBroken() != breaks
                || cost.blocksPlaced() != placements) {
            throw unprovable(operation + " has an invalid primitive cost");
        }
    }

    private static ActionDsl.Budget minimum(ActionDsl.Budget request, ActionDsl.Budget local) {
        return new ActionDsl.Budget(
                Math.min(request.maxDurationMillis(), local.maxDurationMillis()),
                Math.min(request.maxTicks(), local.maxTicks()),
                Math.min(request.maxDistanceBlocks(), local.maxDistanceBlocks()),
                Math.min(request.maxCameraDegrees(), local.maxCameraDegrees()),
                Math.min(request.maxInteractions(), local.maxInteractions()),
                Math.min(request.maxBlocksBroken(), local.maxBlocksBroken()),
                Math.min(request.maxBlocksPlaced(), local.maxBlocksPlaced()));
    }

    /**
     * Applies the effective request/local ceiling and reports only trusted catalog component
     * names. Submitted values, node identifiers, and coordinates are deliberately omitted.
     */
    public static void requireWithinBudget(Cost cost, ActionDsl.Budget budget) {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(budget, "budget");
        var exceeded = new ArrayList<String>(7);
        if (cost.durationMillis() > budget.maxDurationMillis()) {
            exceeded.add("budget.max_duration_ms");
        }
        if (cost.ticks() > budget.maxTicks()) {
            exceeded.add("budget.max_ticks");
        }
        if (cost.distanceBlocks() > budget.maxDistanceBlocks()) {
            exceeded.add("budget.max_distance_blocks");
        }
        if (cost.cameraDegrees() > budget.maxCameraDegrees()) {
            exceeded.add("budget.max_camera_degrees");
        }
        if (cost.interactions() > budget.maxInteractions()) {
            exceeded.add("budget.max_interactions");
        }
        if (cost.blocksBroken() > budget.maxBlocksBroken()) {
            exceeded.add("budget.max_blocks_broken");
        }
        if (cost.blocksPlaced() > budget.maxBlocksPlaced()) {
            exceeded.add("budget.max_blocks_placed");
        }
        if (!exceeded.isEmpty()) {
            throw unprovable("Worst-case cost exceeds effective budget components: "
                    + String.join(", ", exceeded));
        }
    }

    private static Cost add(Cost left, Cost right) {
        try {
            return new Cost(
                    Math.addExact(left.durationMillis(), right.durationMillis()),
                    Math.addExact(left.ticks(), right.ticks()),
                    finiteAdd(left.distanceBlocks(), right.distanceBlocks()),
                    finiteAdd(left.cameraDegrees(), right.cameraDegrees()),
                    Math.addExact(left.interactions(), right.interactions()),
                    Math.addExact(left.blocksBroken(), right.blocksBroken()),
                    Math.addExact(left.blocksPlaced(), right.blocksPlaced()));
        } catch (ArithmeticException failure) {
            throw new ActionDslException(
                    PROGRAM_BUDGET_UNPROVABLE,
                    "Worst-case cost overflowed while adding a sequence",
                    failure);
        }
    }

    private static Cost componentMaximum(Cost left, Cost right) {
        return new Cost(
                Math.max(left.durationMillis(), right.durationMillis()),
                Math.max(left.ticks(), right.ticks()),
                Math.max(left.distanceBlocks(), right.distanceBlocks()),
                Math.max(left.cameraDegrees(), right.cameraDegrees()),
                Math.max(left.interactions(), right.interactions()),
                Math.max(left.blocksBroken(), right.blocksBroken()),
                Math.max(left.blocksPlaced(), right.blocksPlaced()));
    }

    private static Cost multiply(Cost value, int multiplier) {
        try {
            return new Cost(
                    Math.multiplyExact(value.durationMillis(), multiplier),
                    Math.multiplyExact(value.ticks(), multiplier),
                    finiteMultiply(value.distanceBlocks(), multiplier),
                    finiteMultiply(value.cameraDegrees(), multiplier),
                    Math.multiplyExact(value.interactions(), multiplier),
                    Math.multiplyExact(value.blocksBroken(), multiplier),
                    Math.multiplyExact(value.blocksPlaced(), multiplier));
        } catch (ArithmeticException failure) {
            throw new ActionDslException(
                    PROGRAM_BUDGET_UNPROVABLE,
                    "Worst-case cost overflowed while expanding repeat",
                    failure);
        }
    }

    private static long multiplyExact(long value, long multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException failure) {
            throw new ActionDslException(
                    PROGRAM_BUDGET_UNPROVABLE,
                    "Worst-case duration overflowed",
                    failure);
        }
    }

    private static double finiteAdd(double left, double right) {
        double result = left + right;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("non-finite cost sum");
        }
        return result;
    }

    private static double finiteMultiply(double value, int multiplier) {
        double result = value * multiplier;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("non-finite cost product");
        }
        return result;
    }

    private static ActionDslException unprovable(String message) {
        return new ActionDslException(PROGRAM_BUDGET_UNPROVABLE, message);
    }

    /** Resolves map/snapshot-dependent primitive bounds without exposing Minecraft types here. */
    @FunctionalInterface
    public interface PrimitiveCostModel {
        Optional<Cost> worstCase(ActionDsl.Node primitive);
    }

    public record Cost(
            long durationMillis,
            long ticks,
            double distanceBlocks,
            double cameraDegrees,
            long interactions,
            long blocksBroken,
            long blocksPlaced) {
        private static final Cost ZERO = new Cost(0, 0, 0, 0, 0, 0, 0);

        public Cost {
            if (durationMillis < 0 || ticks < 0 || interactions < 0
                    || blocksBroken < 0 || blocksPlaced < 0
                    || !Double.isFinite(distanceBlocks) || distanceBlocks < 0
                    || !Double.isFinite(cameraDegrees) || cameraDegrees < 0) {
                throw new IllegalArgumentException("Cost components must be finite and non-negative");
            }
        }
    }

    public record CompiledProgram(
            ActionDsl.Request request,
            int sourceNodes,
            int executedNodesUpperBound,
            Set<ActionDsl.Capability> requiredCapabilities,
            Cost worstCaseCost,
            ActionDsl.Budget effectiveBudget,
            Map<String, Cost> primitiveCostBounds) {
        public CompiledProgram {
            Objects.requireNonNull(request, "request");
            requiredCapabilities = Set.copyOf(requiredCapabilities);
            Objects.requireNonNull(worstCaseCost, "worstCaseCost");
            Objects.requireNonNull(effectiveBudget, "effectiveBudget");
            primitiveCostBounds = Map.copyOf(
                    Objects.requireNonNull(primitiveCostBounds, "primitiveCostBounds"));
        }
    }
}
