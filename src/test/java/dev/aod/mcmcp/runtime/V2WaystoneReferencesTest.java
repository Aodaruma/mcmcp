package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class V2WaystoneReferencesTest {
    private final V2WaystoneReferences refs = new V2WaystoneReferences();
    private final V2WaystoneReferences.Scope scope = new V2WaystoneReferences.Scope(UUID.randomUUID(),
            "minecraft:overworld", 1, 64, 2,
            new ContainerSyncSignals.StackFingerprint(V2WaystoneArguments.ITEM, 1, 123));
    private final V2WaystoneReferences.Destination destination = new V2WaystoneReferences.Destination(UUID.randomUUID(),
            "End base", "minecraft:the_end", 8, 70, 9, 3, 0, true, List.of());

    @Test void referencesAreBoundedExpiringAndSingleUse() {
        var ref = refs.register(scope, 100, destination);
        assertThat(ref).matches("[A-Za-z0-9_-]{24}");
        assertThatThrownBy(() -> refs.resolve(ref, scope, 2501)).isInstanceOf(IllegalArgumentException.class);
        assertThat(refs.consume(ref, scope, 2500, destination, 3, 0)).isEqualTo(destination);
        assertThatThrownBy(() -> refs.consume(ref, scope, 2500, destination, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        var oldest = refs.register(scope, 0, destination);
        for (int i = 0; i < V2WaystoneReferences.MAX_REFERENCES; i++) refs.register(scope, 0, destination);
        assertThatThrownBy(() -> refs.resolve(oldest, scope, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void changedItemOriginSessionDestinationCostOrEligibilityCannotBeSelected() {
        var ref = refs.register(scope, 0, destination);
        for (var changed : List.of(
                new V2WaystoneReferences.Scope(UUID.randomUUID(), scope.dimension(), 1, 64, 2, scope.held()),
                new V2WaystoneReferences.Scope(scope.session(), "minecraft:the_end", 1, 64, 2, scope.held()),
                new V2WaystoneReferences.Scope(scope.session(), scope.dimension(), 2, 64, 2, scope.held()),
                new V2WaystoneReferences.Scope(scope.session(), scope.dimension(), 1, 64, 2,
                        new ContainerSyncSignals.StackFingerprint(V2WaystoneArguments.ITEM, 1, 124)))) {
            assertThatThrownBy(() -> refs.resolve(ref, changed, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        for (var changed : List.of(
                new V2WaystoneReferences.Destination(UUID.randomUUID(), destination.name(), destination.dimension(), 8, 70, 9, 3, 0, true, List.of()),
                new V2WaystoneReferences.Destination(destination.id(), destination.name(), destination.dimension(), 9, 70, 9, 3, 0, true, List.of()),
                new V2WaystoneReferences.Destination(destination.id(), destination.name(), destination.dimension(), 8, 70, 9, 4, 0, true, List.of()),
                new V2WaystoneReferences.Destination(destination.id(), destination.name(), destination.dimension(), 8, 70, 9, 3, 0, false, List.of()))) {
            assertThatThrownBy(() -> refs.consume(ref, scope, 1, changed, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> refs.consume(ref, scope, 1, destination, 2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(refs.consume(ref, scope, 1, destination, 3, 0)).isEqualTo(destination);
    }

    @Test void argumentsRequireInspectedReferencesAndExplicitExperienceBudget() {
        var inspect = Map.<String, Object>of("target", "item", "operation", "inspect_destinations", "item", V2WaystoneArguments.ITEM);
        assertThat(V2WaystoneArguments.parse(inspect).inspect()).isTrue();
        var select = new java.util.LinkedHashMap<>(inspect);
        select.put("operation", "select_destination");
        assertThatThrownBy(() -> V2WaystoneArguments.parse(select)).isInstanceOf(IllegalArgumentException.class);
        select.put("destination_ref", "a".repeat(24));
        assertThat(V2WaystoneArguments.parse(select).maxLevels()).isZero();
        select.put("max_xp_levels", -1);
        assertThatThrownBy(() -> V2WaystoneArguments.parse(select)).isInstanceOf(IllegalArgumentException.class);
        select.remove("max_xp_levels"); select.put("packet", "arbitrary");
        assertThatThrownBy(() -> V2WaystoneArguments.parse(select)).isInstanceOf(IllegalArgumentException.class);
    }
}
