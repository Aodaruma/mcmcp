package dev.aod.mcmcp.agent.dsl;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Evaluates a DSL predicate once against one immutable policy-filtered snapshot. */
public final class PredicateEvaluator {
    private PredicateEvaluator() {
    }

    public static boolean evaluate(ActionDsl.Predicate predicate, PolicySnapshot snapshot) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(snapshot, "snapshot");
        if (predicate instanceof ActionDsl.AtomicPredicate atomic) {
            return evaluateAtomic(atomic, snapshot);
        }
        var logical = (ActionDsl.LogicalPredicate) predicate;
        boolean result = logical.operator() == ActionDsl.LogicalOperator.ALL;
        // Intentionally evaluate every operand. A missing field is unavailable even when ordinary
        // boolean short-circuiting could otherwise hide it.
        for (ActionDsl.AtomicPredicate operand : logical.operands()) {
            boolean value = evaluateAtomic(operand, snapshot);
            result = logical.operator() == ActionDsl.LogicalOperator.ALL
                    ? result && value
                    : result || value;
        }
        return result;
    }

    private static boolean evaluateAtomic(
            ActionDsl.AtomicPredicate predicate, PolicySnapshot snapshot) {
        if (predicate instanceof ActionDsl.NumericPredicate numeric) {
            OptionalDouble actual = Objects.requireNonNull(
                    snapshot.numeric(numeric.field()), "snapshot numeric result");
            if (actual.isEmpty()) {
                throw unavailable(numeric.field().wireName());
            }
            return compare(actual.getAsDouble(), numeric.comparison(), numeric.value());
        }
        if (predicate instanceof ActionDsl.BooleanPredicate bool) {
            Optional<Boolean> actual = Objects.requireNonNull(
                    snapshot.bool(bool.field()), "snapshot boolean result");
            if (actual.isEmpty()) {
                throw unavailable(bool.field().wireName());
            }
            return actual.get() == bool.value();
        }
        if (predicate instanceof ActionDsl.InventoryPredicate inventory) {
            OptionalInt actual = Objects.requireNonNull(
                    snapshot.inventoryCount(inventory.item()), "snapshot inventory result");
            if (actual.isEmpty()) {
                throw unavailable("inventory_count:" + inventory.item());
            }
            return compare(actual.getAsInt(), inventory.comparison(), inventory.value());
        }
        var status = (ActionDsl.StatusPredicate) predicate;
        Optional<Boolean> actual = Objects.requireNonNull(
                snapshot.hasStatusEffect(status.effect()), "snapshot status result");
        if (actual.isEmpty()) {
            throw unavailable("has_status_effect:" + status.effect());
        }
        return actual.get() == status.value();
    }

    private static boolean compare(double actual, ActionDsl.Comparison comparison, double expected) {
        return switch (comparison) {
            case LT -> actual < expected;
            case LTE -> actual <= expected;
            case EQ -> Double.compare(actual, expected) == 0;
            case GTE -> actual >= expected;
            case GT -> actual > expected;
        };
    }

    private static ActionDslException unavailable(String field) {
        return new ActionDslException(
                ActionDslException.Code.PREDICATE_UNAVAILABLE,
                "Snapshot field is unavailable: " + field);
    }
}
