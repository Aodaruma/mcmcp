package dev.aod.mcmcp.agent.dsl;

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
            30_000, 600, 32, 360, 0, ActionDslValidator.MAX_BLOCKS_BROKEN, 0);
    private static final long NOMINAL_TICK_MILLIS = 50;

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
        requireWithin(cost, effectiveBudget);
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
            Cost cost = new Cost(
                    multiplyExact(wait.ticks(), NOMINAL_TICK_MILLIS),
                    wait.ticks(), 0, 0, 0, 0, 0);
            primitiveCostBounds.put(node.id(), cost);
            return cost;
        }
        if (node instanceof ActionDsl.NavigateToKnown
                || node instanceof ActionDsl.FaceKnownPosition
                || node instanceof ActionDsl.BreakKnownFace) {
            Optional<Cost> resolved = Objects.requireNonNull(
                    primitiveCosts.worstCase(node), "primitive cost result");
            if (resolved.isEmpty()) {
                throw unprovable("No worst-case cost is available for node " + node.id());
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

    private static void requireWithin(Cost cost, ActionDsl.Budget budget) {
        if (cost.durationMillis() > budget.maxDurationMillis()
                || cost.ticks() > budget.maxTicks()
                || cost.distanceBlocks() > budget.maxDistanceBlocks()
                || cost.cameraDegrees() > budget.maxCameraDegrees()
                || cost.interactions() > budget.maxInteractions()
                || cost.blocksBroken() > budget.maxBlocksBroken()
                || cost.blocksPlaced() > budget.maxBlocksPlaced()) {
            throw unprovable("Worst-case cost exceeds the effective request/local budget");
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
