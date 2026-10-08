package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2CraftPlanTest {
    static V2CraftArguments request(int count, boolean surplus, boolean inventory) {
        return new V2CraftArguments(false, inventory, 0, 64, 0, "a".repeat(24),
                "sha256:" + "0".repeat(64), count, 64, surplus, 1200, true, 64);
    }

    static ClientRecipeCatalog.RecipeView recipe(String kind, int output,
            List<ClientRecipeCatalog.IngredientView> ingredients, ClientRecipeCatalog.Shape shape) {
        return new ClientRecipeCatalog.RecipeView("a".repeat(24), "sha256:" + "0".repeat(64), kind,
                "inventory_2x2", true, null,
                new ClientRecipeCatalog.Result(true, List.of(new ClientRecipeCatalog.ResultAlternative(
                        "minecraft:oak_planks", output, "sha256:" + "1".repeat(64)))), ingredients, shape);
    }

    @Test void countsNewOutputsAndRequiresExplicitSurplus() {
        var recipe = recipe("shapeless", 4, List.of(new ClientRecipeCatalog.IngredientView(
                0, 1, List.of("minecraft:oak_log"))), null);
        var exact = V2CraftPlan.create(request(8, false, true), recipe, Map.of("minecraft:oak_log", 2, "minecraft:oak_planks", 40));
        assertThat(exact.failure()).isNull();
        assertThat(exact.crafts()).isEqualTo(2);
        assertThat(exact.produced()).isEqualTo(8);
        assertThat(exact.materials()).containsExactlyEntriesOf(Map.of("minecraft:oak_log", 2));
        assertThat(V2CraftPlan.create(request(5, false, true), recipe, Map.of("minecraft:oak_log", 2)).failure())
                .isEqualTo("craft_count_requires_surplus");
        assertThat(V2CraftPlan.create(request(5, true, true), recipe, Map.of("minecraft:oak_log", 2)).produced()).isEqualTo(8);
    }

    @Test void overlappingAlternativesDoNotStealTheOnlyUsableMaterial() {
        var ingredients = List.of(new ClientRecipeCatalog.IngredientView(0, 1, List.of("a", "b")),
                new ClientRecipeCatalog.IngredientView(1, 1, List.of("a")));
        assertThat(V2CraftPlan.allocate(ingredients, 2, Map.of("a", 2, "b", 2)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 2, "b", 2));
        assertThat(V2CraftPlan.allocate(ingredients, 2, Map.of("a", 1, "b", 20))).isNull();
    }

    @Test void batchAllocationKeepsLaterCraftsFeasible() {
        var ingredients = List.of(new ClientRecipeCatalog.IngredientView(0, 1, List.of("birch", "oak")),
                new ClientRecipeCatalog.IngredientView(1, 1, List.of("birch", "spruce")));
        var batches = V2CraftPlan.allocateBatches(ingredients, 2, Map.of("birch", 2, "oak", 2));
        assertThat(batches).containsExactly(Map.of("birch", 1, "oak", 1), Map.of("birch", 1, "oak", 1));
        assertThat(V2CraftPlan.allocateBatches(ingredients, 3, Map.of("birch", 2, "oak", 2))).isNull();
    }

    @Test void rejectsLargeShapeInHandButAcceptsWorkbench() {
        var recipe = recipe("shaped", 1, List.of(new ClientRecipeCatalog.IngredientView(
                0, 1, List.of("minecraft:oak_log"))), new ClientRecipeCatalog.Shape(3, 1));
        assertThat(V2CraftPlan.create(request(1, false, true), recipe, Map.of("minecraft:oak_log", 1)).failure())
                .isEqualTo("recipe_does_not_fit_station");
        assertThat(V2CraftPlan.create(request(1, false, false), recipe, Map.of("minecraft:oak_log", 1)).failure()).isNull();
    }

    @Test void validatesPublicBoundsAndStationKeys() {
        var args = new java.util.LinkedHashMap<String, Object>(Map.of("operation", "execute",
                "station", Map.of("kind", "inventory_2x2"), "recipe_ref", "a".repeat(24),
                "recipe_fingerprint", "sha256:" + "0".repeat(64), "count", 8));
        assertThat(V2CraftArguments.parse(args).inventory()).isTrue();
        args.put("station", Map.of("kind", "inventory_2x2", "x", 0));
        assertThatThrownBy(() -> V2CraftArguments.parse(args)).isInstanceOf(IllegalArgumentException.class);
        args.put("station", Map.of("kind", "crafting_table"));
        assertThatThrownBy(() -> V2CraftArguments.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void remainderAccountingPreservesExistingContainersAndComponentIdentity() {
        var before = Map.of("minecraft:milk_bucket#17", 3, "minecraft:bucket#23", 2, "minecraft:bucket#99", 1);
        var expected = V2CraftWork.expectedProduction(before, Map.of("minecraft:milk_bucket#17", 3),
                "minecraft:cake#31", 1, Map.of("minecraft:bucket#23", 3));
        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                "minecraft:bucket#23", 5, "minecraft:bucket#99", 1, "minecraft:cake#31", 1));
        assertThatThrownBy(() -> V2CraftWork.expectedProduction(before, Map.of("minecraft:milk_bucket#17", 4),
                "minecraft:cake#31", 1, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
