package dev.aod.mcmcp.agent.script;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Restricted synchronous language used by the public script job. */
final class ActionScript {
    static final int MAX_SOURCE = 32_768;
    static final int MAX_NODES = 4_096;
    static final int MAX_DEPTH = 64;
    static final int MAX_CALL_DEPTH = 16;
    static final Budget DEFAULT_BUDGET = new Budget(100_000, 10_000, 1_000);

    record Budget(int work, int iterations, int calls) {
        Budget {
            if (work < 0 || work > 100_000 || iterations < 0 || iterations > 10_000
                    || calls < 0 || calls > 1_000) throw new IllegalArgumentException("Invalid budget");
        }
    }

    enum Status { SUCCESS, INVALID, LIMIT, CANCELLED, FAILED }
    enum Outcome { SUCCESS, FAILURE, CANCELLED }
    record Command(String name, Map<String, Object> arguments) {
        Command { arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments)); }
    }
    /** Must return only on completion; must cooperate with cancellation while waiting. No retries. */
    interface Sink { Outcome execute(Command command, BooleanSupplier cancelled); }
    record Result(Status status, int work, int iterations, int calls, int completed, String detail) {}

    static Result run(String source, Set<String> commands, Sink sink, BooleanSupplier cancelled, Budget budget) {
        Objects.requireNonNull(sink);
        var execution = new Execution(budget, cancelled, sink);
        try {
            execution.checkCancelled();
            var parser = new ActionScriptParser(source, Set.copyOf(commands), cancelled);
            var program = parser.parse();
            execution.functions = parser.functions();
            execution.eval(program, new Scope(null));
            execution.checkCancelled();
            return execution.result(Status.SUCCESS, "");
        } catch (Stop stop) {
            return execution.result(stop.status, stop.getMessage());
        }
    }

    static final class Stop extends RuntimeException {
        final Status status;
        Stop(Status status, String detail) { super(detail); this.status = status; }
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, Object> values = new LinkedHashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        Scope owner(String name) {
            if (values.containsKey(name)) return this;
            if (parent != null) return parent.owner(name);
            throw new Stop(Status.INVALID, "Unknown variable: " + name);
        }
    }

    private static final class Execution {
        final Budget budget;
        final BooleanSupplier cancelled;
        final Sink sink;
        int work, iterations, calls, completed;
        int callDepth;
        Map<String, ActionScriptParser.Node> functions = Map.of();

        Execution(Budget budget, BooleanSupplier cancelled, Sink sink) {
            this.budget = Objects.requireNonNull(budget);
            this.cancelled = Objects.requireNonNull(cancelled);
            this.sink = sink;
        }
        Result result(Status status, String detail) {
            return new Result(status, work, iterations, calls, completed, detail);
        }
        void checkCancelled() {
            if (isCancelled())
                throw new Stop(Status.CANCELLED, "Cancelled");
        }
        boolean isCancelled() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
        void step() {
            checkCancelled();
            if (work == budget.work()) throw new Stop(Status.LIMIT, "Work limit");
            work++;
        }
        void iteration() {
            checkCancelled();
            if (iterations == budget.iterations()) throw new Stop(Status.LIMIT, "Iteration limit");
            iterations++;
        }
        Object eval(ActionScriptParser.Node node, Scope scope) {
            step();
            var children = node.children();
            return switch (node.kind()) {
                case "function" -> null;
                case "literal" -> node.value();
                case "variable" -> scope.owner(node.text()).values.get(node.text());
                case "array" -> {
                    var values = new ArrayList<Object>();
                    for (var child : children) values.add(eval(child, scope));
                    yield Collections.unmodifiableList(values);
                }
                case "object" -> {
                    var values = new LinkedHashMap<String, Object>();
                    for (var child : children) values.put(child.text(), eval(child.children().getFirst(), scope));
                    yield Collections.unmodifiableMap(values);
                }
                case "block" -> {
                    var local = new Scope(scope);
                    for (var child : children) eval(child, local);
                    yield null;
                }
                case "let", "assign" -> {
                    Object value = eval(children.getFirst(), scope);
                    Scope target = node.kind().equals("let") ? scope : scope.owner(node.text());
                    if (node.kind().equals("let") && target.values.containsKey(node.text()))
                        throw new Stop(Status.INVALID, "Duplicate variable: " + node.text());
                    target.values.put(node.text(), value);
                    yield null;
                }
                case "if" -> {
                    if (bool(eval(children.getFirst(), scope))) eval(children.get(1), scope);
                    else if (children.size() == 3) eval(children.get(2), scope);
                    yield null;
                }
                case "repeat" -> {
                    double count = number(eval(children.getFirst(), scope));
                    if (count < 0 || count != Math.rint(count))
                        throw new Stop(Status.INVALID, "Repeat count must be a nonnegative integer");
                    if (count > budget.iterations() - iterations) throw new Stop(Status.LIMIT, "Iteration limit");
                    for (int i = 0; i < (int) count; i++) {
                        iteration();
                        eval(children.get(1), scope);
                    }
                    yield null;
                }
                case "for" -> {
                    var local = new Scope(scope);
                    eval(children.getFirst(), local);
                    while (bool(eval(children.get(1), local))) {
                        iteration();
                        eval(children.get(3), local);
                        eval(children.get(2), local);
                    }
                    yield null;
                }
                case "call" -> {
                    var args = new LinkedHashMap<String, Object>();
                    for (var arg : children) args.put(arg.text(), eval(arg.children().getFirst(), scope));
                    checkCancelled();
                    var function = functions.get(node.text());
                    if (function != null) {
                        if (callDepth == MAX_CALL_DEPTH) throw new Stop(Status.LIMIT, "Function call depth limit");
                        var local = new Scope(null); // No caller/global capture or closures.
                        local.values.putAll(args);
                        callDepth++;
                        try {
                            eval(function.children().getLast(), local);
                        } finally {
                            callDepth--;
                        }
                        yield null;
                    }
                    if (calls == budget.calls()) throw new Stop(Status.LIMIT, "Call limit");
                    calls++;
                    Outcome outcome;
                    try {
                        outcome = sink.execute(new Command(node.text(), args), this::isCancelled);
                    } catch (RuntimeException failure) {
                        checkCancelled();
                        throw new Stop(Status.FAILED, "Command sink threw");
                    }
                    if (outcome == Outcome.SUCCESS) completed++;
                    checkCancelled();
                    if (outcome == Outcome.CANCELLED) throw new Stop(Status.CANCELLED, "Command cancelled");
                    if (outcome != Outcome.SUCCESS) throw new Stop(Status.FAILED, "Command failed");
                    yield null;
                }
                case "unary" -> {
                    Object value = eval(children.getFirst(), scope);
                    yield switch (node.text()) {
                        case "!" -> !bool(value);
                        case "-" -> finite(-number(value));
                        default -> number(value);
                    };
                }
                case "binary" -> {
                    Object left = eval(children.getFirst(), scope);
                    if (node.text().equals("&&")) yield bool(left) && bool(eval(children.get(1), scope));
                    if (node.text().equals("||")) yield bool(left) || bool(eval(children.get(1), scope));
                    Object right = eval(children.get(1), scope);
                    yield switch (node.text()) {
                        case "==" -> equal(left, right);
                        case "!=" -> !equal(left, right);
                        case "<" -> number(left) < number(right);
                        case "<=" -> number(left) <= number(right);
                        case ">" -> number(left) > number(right);
                        case ">=" -> number(left) >= number(right);
                        case "+" -> finite(number(left) + number(right));
                        case "-" -> finite(number(left) - number(right));
                        case "*" -> finite(number(left) * number(right));
                        case "/" -> finite(number(left) / number(right));
                        case "%" -> finite(number(left) % number(right));
                        default -> throw new AssertionError(node.text());
                    };
                }
                default -> throw new AssertionError(node.kind());
            };
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        throw new Stop(Status.INVALID, "Boolean required");
    }
    private static boolean equal(Object left, Object right) {
        // Do not recursively traverse shared collection graphs (which can expand exponentially).
        if (left instanceof java.util.List<?> || left instanceof Map<?, ?>
                || right instanceof java.util.List<?> || right instanceof Map<?, ?>)
            throw new Stop(Status.INVALID, "Equality requires scalar values");
        if (left instanceof Double a && right instanceof Double b) return a.doubleValue() == b.doubleValue();
        return Objects.equals(left, right);
    }
    private static double number(Object value) {
        if (value instanceof Double d) return d;
        throw new Stop(Status.INVALID, "Number required");
    }
    static double finite(double value) {
        if (!Double.isFinite(value)) throw new Stop(Status.INVALID, "Nonfinite number");
        return value;
    }
}
