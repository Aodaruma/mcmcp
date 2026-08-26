package dev.aod.mcmcp.agent.dsl;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.CAPABILITY_DENIED;
import static dev.aod.mcmcp.agent.dsl.ActionDslException.Code.INVALID_ARGUMENT;
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

    private static final Pattern NODE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern PROGRAM_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern RESOURCE_LOCATION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

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
        if (program.capabilities().size() > 2) {
            throw invalid("program.capabilities must contain at most 2 values");
        }
        validateRequestBudget(request.budget());

        if (program.body().isEmpty() || program.body().size() > MAX_TOP_LEVEL_NODES) {
            throw invalid("program.body must contain 1.." + MAX_TOP_LEVEL_NODES + " nodes");
        }
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
        requireRange(budget.maxDurationMillis(), 100, 30_000, "budget.max_duration_ms");
        requireRange(budget.maxTicks(), 2, 600, "budget.max_ticks");
        requireFiniteRange(budget.maxDistanceBlocks(), 0, 32, "budget.max_distance_blocks");
        requireFiniteRange(budget.maxCameraDegrees(), 0, 360, "budget.max_camera_degrees");
        if (budget.maxInteractions() != 0
                || budget.maxBlocksBroken() != 0
                || budget.maxBlocksPlaced() != 0) {
            throw invalid("Phase 1 interaction, break, and place budgets must be 0");
        }
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
            throw invalid("Duplicate node id: " + node.id());
        }

        if (node instanceof ActionDsl.NavigateToKnown navigate) {
            validatePosition(navigate.target(), path + ".target");
            requireFiniteRange(navigate.tolerance(), 0.1, 1.5, path + ".tolerance");
            walk.requiredCapabilities.add(ActionDsl.Capability.MOVEMENT);
            return 1;
        }
        if (node instanceof ActionDsl.FaceKnownPosition face) {
            validatePosition(face.target(), path + ".target");
            walk.requiredCapabilities.add(ActionDsl.Capability.CAMERA);
            return 1;
        }
        if (node instanceof ActionDsl.WaitTicks wait) {
            requireRange(wait.ticks(), 1, 200, path + ".ticks");
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
        int bodyCount = walkSequence(repeat.body(), depth + 1, walk, path + ".body");
        return boundedAdd(1, boundedMultiply(bodyCount, repeat.count()));
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
        private final EnumSet<ActionDsl.Capability> requiredCapabilities =
                EnumSet.noneOf(ActionDsl.Capability.class);
        private int sourceNodes;
    }
}
