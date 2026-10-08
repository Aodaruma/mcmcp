package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure quantity and overlapping-ingredient allocation, with no world access or inputs. */
record V2CraftPlan(String item, int outputPerCraft, int crafts, int produced,
        Map<String, Integer> materials, String failure) {
    V2CraftPlan { materials = Map.copyOf(materials); }

    static V2CraftPlan create(V2CraftArguments request, ClientRecipeCatalog.RecipeView recipe,
            Map<String, Integer> available) {
        if (!recipe.v2CraftSupported()) {
            return unavailable("unsupported_recipe");
        }
        int side = request.inventory() ? 2 : 3;
        if (recipe.shape() != null && (recipe.shape().width() > side || recipe.shape().height() > side)
                || recipe.ingredients().stream().mapToInt(ClientRecipeCatalog.IngredientView::countPerCraft).sum() > side * side) {
            return unavailable("recipe_does_not_fit_station");
        }
        var output = recipe.result().alternatives().getFirst();
        int crafts = Math.toIntExact(((long) request.count() + output.count() - 1) / output.count());
        int produced = Math.multiplyExact(crafts, output.count());
        if (produced != request.count() && !request.allowSurplus()) return unavailable("craft_count_requires_surplus");
        if (crafts > request.maxCrafts()) return unavailable("craft_limit");
        var allocated = allocate(recipe.ingredients(), crafts, available);
        return new V2CraftPlan(output.item(), output.count(), crafts, produced,
                allocated == null ? Map.of() : allocated, allocated == null ? "insufficient_materials" : null);
    }

    // Bipartite capacity flow prevents an early flexible ingredient from taking the only
    // material usable by a later ingredient. Each augmentation exhausts an edge capacity.
    static Map<String, Integer> allocate(List<ClientRecipeCatalog.IngredientView> ingredients,
            int crafts, Map<String, Integer> available) {
        var batches = allocateBatches(ingredients, crafts, available);
        if (batches == null) return null;
        var total = new LinkedHashMap<String, Integer>();
        batches.forEach(batch -> batch.forEach((item, count) -> total.merge(item, count, Math::addExact)));
        return Map.copyOf(total);
    }

    static List<Map<String, Integer>> allocateBatches(List<ClientRecipeCatalog.IngredientView> ingredients,
            int crafts, Map<String, Integer> available) {
        var items = available.keySet().stream().filter(item -> available.get(item) > 0).sorted().toList();
        int sink = 1 + items.size() + ingredients.size();
        int[][] residual = new int[sink + 1][sink + 1];
        for (int i = 0; i < items.size(); i++) residual[0][i + 1] = available.get(items.get(i));
        int required = 0;
        for (int g = 0; g < ingredients.size(); g++) {
            var ingredient = ingredients.get(g);
            int need = Math.multiplyExact(ingredient.countPerCraft(), crafts);
            required = Math.addExact(required, need);
            int node = 1 + items.size() + g;
            residual[node][sink] = need;
            for (int i = 0; i < items.size(); i++) {
                if (ingredient.alternatives().contains(items.get(i))) residual[i + 1][node] = need;
            }
        }
        int total = 0;
        while (total < required) {
            int[] parent = new int[sink + 1];
            java.util.Arrays.fill(parent, -1);
            parent[0] = 0;
            var queue = new ArrayList<Integer>();
            queue.add(0);
            for (int q = 0; q < queue.size() && parent[sink] < 0; q++) {
                int from = queue.get(q);
                for (int to = 1; to <= sink; to++) {
                    if (parent[to] < 0 && residual[from][to] > 0) { parent[to] = from; queue.add(to); }
                }
            }
            if (parent[sink] < 0) return null;
            int amount = required - total;
            for (int at = sink; at != 0; at = parent[at]) amount = Math.min(amount, residual[parent[at]][at]);
            for (int at = sink; at != 0; at = parent[at]) {
                residual[parent[at]][at] -= amount;
                residual[at][parent[at]] += amount;
            }
            total += amount;
        }
        var result = new ArrayList<Map<String, Integer>>(crafts);
        for (int craft = 0; craft < crafts; craft++) result.add(new LinkedHashMap<>());
        for (int g = 0; g < ingredients.size(); g++) {
            int perCraft = ingredients.get(g).countPerCraft();
            int assigned = 0;
            for (int i = 0; i < items.size(); i++) {
                int used = residual[1 + items.size() + g][i + 1];
                while (used > 0) {
                    int take = Math.min(used, perCraft - assigned % perCraft);
                    result.get(assigned / perCraft).merge(items.get(i), take, Math::addExact);
                    assigned += take;
                    used -= take;
                }
            }
        }
        return result.stream().map(Map::copyOf).toList();
    }

    Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("executable", failure == null);
        result.put("crafts", crafts);
        result.put("produced_count", produced);
        result.put("materials", materials);
        if (item != null) result.put("item", item);
        if (failure != null) result.put("failure", failure);
        return result;
    }

    private static V2CraftPlan unavailable(String reason) {
        return new V2CraftPlan(null, 0, 0, 0, Map.of(), reason);
    }
}
