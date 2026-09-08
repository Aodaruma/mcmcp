package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import dev.aod.mcmcp.agent.dsl.PredicateEvaluator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** DSLが参照するpredicateの依存関係とimmutable admission snapshot。 */
final class ActionPredicates {
    private ActionPredicates() {}

    record PredicateRequirements(
            Set<ActionDsl.NumericField> numericFields,
            Set<ActionDsl.BooleanField> booleanFields,
            Set<String> inventoryItems,
            Set<String> statusEffects) {
        PredicateRequirements {
            numericFields = Set.copyOf(Objects.requireNonNull(numericFields, "numericFields"));
            booleanFields = Set.copyOf(Objects.requireNonNull(booleanFields, "booleanFields"));
            inventoryItems = Set.copyOf(Objects.requireNonNull(inventoryItems, "inventoryItems"));
            statusEffects = Set.copyOf(Objects.requireNonNull(statusEffects, "statusEffects"));
        }
    }

    record AdmissionPolicySnapshot(
            Map<ActionDsl.NumericField, Double> numericValues,
            Map<ActionDsl.BooleanField, Boolean> booleanValues,
            Map<String, Integer> inventoryCounts,
            Map<String, Boolean> statusEffectValues) implements PolicySnapshot {
        AdmissionPolicySnapshot {
            numericValues = Map.copyOf(Objects.requireNonNull(numericValues, "numericValues"));
            booleanValues = Map.copyOf(Objects.requireNonNull(booleanValues, "booleanValues"));
            inventoryCounts = Map.copyOf(
                    Objects.requireNonNull(inventoryCounts, "inventoryCounts"));
            statusEffectValues = Map.copyOf(
                    Objects.requireNonNull(statusEffectValues, "statusEffectValues"));
        }

        static AdmissionPolicySnapshot capture(
                PolicySnapshot source, PredicateRequirements requirements) {
            var numeric = new EnumMap<ActionDsl.NumericField, Double>(ActionDsl.NumericField.class);
            for (var field : requirements.numericFields()) {
                var value = source.numeric(field);
                if (value.isPresent()) numeric.put(field, value.getAsDouble());
            }
            var bools = new EnumMap<ActionDsl.BooleanField, Boolean>(ActionDsl.BooleanField.class);
            for (var field : requirements.booleanFields()) {
                source.bool(field).ifPresent(value -> bools.put(field, value));
            }
            var items = new LinkedHashMap<String, Integer>();
            for (var item : requirements.inventoryItems()) {
                var value = source.inventoryCount(item);
                if (value.isPresent()) items.put(item, value.getAsInt());
            }
            var effects = new LinkedHashMap<String, Boolean>();
            for (var effect : requirements.statusEffects()) {
                source.hasStatusEffect(effect).ifPresent(value -> effects.put(effect, value));
            }
            return new AdmissionPolicySnapshot(numeric, bools, items, effects);
        }

        @Override
        public OptionalDouble numeric(ActionDsl.NumericField field) {
            Double value = numericValues.get(field);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }

        @Override
        public Optional<Boolean> bool(ActionDsl.BooleanField field) {
            return Optional.ofNullable(booleanValues.get(field));
        }

        @Override
        public OptionalInt inventoryCount(String item) {
            Integer value = inventoryCounts.get(item);
            return value == null ? OptionalInt.empty() : OptionalInt.of(value);
        }

        @Override
        public Optional<Boolean> hasStatusEffect(String effect) {
            return Optional.ofNullable(statusEffectValues.get(effect));
        }
    }

    static PredicateRequirements predicateRequirements(ActionDsl.Program program) {
        Objects.requireNonNull(program, "program");
        var numeric = EnumSet.noneOf(ActionDsl.NumericField.class);
        var bools = EnumSet.noneOf(ActionDsl.BooleanField.class);
        var items = new LinkedHashSet<String>();
        var effects = new LinkedHashSet<String>();
        collectPredicateRequirements(program.body(), numeric, bools, items, effects);
        return new PredicateRequirements(numeric, bools, items, effects);
    }

    static void collectPredicateRequirements(
            List<ActionDsl.Node> nodes,
            Set<ActionDsl.NumericField> numeric,
            Set<ActionDsl.BooleanField> bools,
            Set<String> items,
            Set<String> effects) {
        for (var node : nodes) {
            if (node instanceof ActionDsl.If conditional) {
                for (var atomic : predicateOperands(conditional.condition())) {
                    switch (atomic) {
                        case ActionDsl.NumericPredicate value -> numeric.add(value.field());
                        case ActionDsl.BooleanPredicate value -> bools.add(value.field());
                        case ActionDsl.InventoryPredicate value -> items.add(value.item());
                        case ActionDsl.StatusPredicate value -> effects.add(value.effect());
                    }
                }
                collectPredicateRequirements(
                        conditional.thenBranch(), numeric, bools, items, effects);
                collectPredicateRequirements(
                        conditional.elseBranch(), numeric, bools, items, effects);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                collectPredicateRequirements(repeat.body(), numeric, bools, items, effects);
            }
        }
    }

    static List<ActionDsl.AtomicPredicate> predicateOperands(
            ActionDsl.Predicate predicate) {
        return predicate instanceof ActionDsl.AtomicPredicate atomic
                ? List.of(atomic)
                : ((ActionDsl.LogicalPredicate) predicate).operands();
    }

    static void validatePredicateAvailability(
            ActionDsl.Program program, PolicySnapshot snapshot) {
        for (var node : program.body()) {
            validatePredicateAvailability(node, snapshot);
        }
    }

    static void validatePredicateAvailability(
            ActionDsl.Node node, PolicySnapshot snapshot) {
        if (node instanceof ActionDsl.If conditional) {
            PredicateEvaluator.evaluate(conditional.condition(), snapshot);
            conditional.thenBranch().forEach(child ->
                    validatePredicateAvailability(child, snapshot));
            conditional.elseBranch().forEach(child ->
                    validatePredicateAvailability(child, snapshot));
        } else if (node instanceof ActionDsl.Repeat repeat) {
            repeat.body().forEach(child -> validatePredicateAvailability(child, snapshot));
        }
    }

    static PolicySnapshot policySnapshot(Minecraft minecraft) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        return new PolicySnapshot() {
            @Override
            public OptionalDouble numeric(ActionDsl.NumericField field) {
                return OptionalDouble.of(switch (field) {
                    case HEALTH -> player.getHealth();
                    case HUNGER -> player.getFoodData().getFoodLevel();
                    case AIR -> player.getAirSupply();
                });
            }

            @Override
            public Optional<Boolean> bool(ActionDsl.BooleanField field) {
                return Optional.of(switch (field) {
                    case ON_FIRE -> player.isOnFire();
                    case SUBMERGED -> player.isUnderWater();
                });
            }

            @Override
            public OptionalInt inventoryCount(String item) {
                int count = 0;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    var stack = inventory.getItem(slot);
                    if (!stack.isEmpty()
                            && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) {
                        count = Math.addExact(count, stack.getCount());
                    }
                }
                return OptionalInt.of(count);
            }

            @Override
            public Optional<Boolean> hasStatusEffect(String effect) {
                return Optional.of(player.getActiveEffects().stream()
                        .anyMatch(instance -> instance.getEffect().getRegisteredName().equals(effect)));
            }
        };
    }
}
