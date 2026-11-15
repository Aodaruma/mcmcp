package dev.aod.mcmcp.observation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PriorityQueue;

/** Only consumes the documented NeoForge client event; never requests server data. */
public final class ClientSyncedRecipes {
    static final int MAX_RECIPES = 4096;
    static final int MAX_DISPLAYS = 16384;
    private Object connection;
    private long generation;
    private Snapshot snapshot = Snapshot.unavailable();

    public synchronized void receive(Object currentConnection, RecipesReceivedEvent event) {
        receive(currentConnection, event.getRecipeMap().values(), event.getRecipeTypes().stream()
                .map(type -> BuiltInRegistries.RECIPE_TYPE.getKey(type).toString()).sorted().toList());
    }

    synchronized void receive(Object currentConnection, Collection<RecipeHolder<?>> recipes, List<String> types) {
        if (currentConnection == null) {
            clear();
            return;
        }
        var entries = new ArrayList<RecipeDisplayEntry>();
        int omitted = 0;
        boolean limited = recipes.size() > MAX_RECIPES;
        boolean failed = false;
        // Choose the same bounded subset regardless of the source map's iteration order.
        // Internal negative ids never authorize recipe-book placement packets.
        Comparator<RecipeHolder<?>> byId = Comparator.comparing(value -> value.id().identifier().toString());
        var selected = new PriorityQueue<RecipeHolder<?>>(MAX_RECIPES + 1, byId.reversed());
        for (var holder : recipes) {
            if (selected.size() < MAX_RECIPES) selected.add(holder);
            else if (byId.compare(holder, selected.peek()) < 0) {
                selected.remove();
                selected.add(holder);
            }
        }
        for (var holder : selected.stream().sorted(byId).toList()) {
            try {
                var recipe = holder.value();
                var displays = recipe.display();
                if (displays.isEmpty()) omitted++;
                for (var display : displays) {
                    if (entries.size() == MAX_DISPLAYS) {
                        limited = true;
                        break;
                    }
                    entries.add(new RecipeDisplayEntry(new RecipeDisplayId(-1 - entries.size()), display,
                            OptionalInt.empty(), recipe.recipeBookCategory(), Optional.empty()));
                }
            } catch (RuntimeException | LinkageError failure) {
                // No exception message, mod text, raw recipe or components enter diagnostics.
                failed = true;
                omitted++;
            }
        }
        connection = currentConnection;
        snapshot = new Snapshot(++generation, true, entries, types.stream().limit(128).toList(),
                omitted, limited || types.size() > 128, failed);
    }

    public synchronized Snapshot forConnection(Object currentConnection) {
        return currentConnection != null && currentConnection == connection
                ? snapshot : Snapshot.unavailable();
    }

    public synchronized void clear() {
        connection = null;
        snapshot = Snapshot.unavailable();
    }

    public record Snapshot(long generation, boolean available, List<RecipeDisplayEntry> entries,
                           List<String> recipeTypes, int omittedWithoutDisplay, boolean limited, boolean failed) {
        public Snapshot {
            entries = List.copyOf(entries);
            recipeTypes = List.copyOf(recipeTypes);
        }

        static Snapshot unavailable() {
            return new Snapshot(0, false, List.of(), List.of(), 0, false, false);
        }
    }
}
