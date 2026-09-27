package dev.aod.mcmcp.observation;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSyncedRecipesTest {
    @Test
    void publicEventExposesSpecialDisplaysOnlyForTheirConnectionWithoutAssembling() {
        var source = new ClientSyncedRecipes();
        Object connection = new Object();
        source.receive(connection, new RecipesReceivedEvent(Set.of(RecipeType.CRAFTING),
                RecipeMap.create(List.of(holder("visible", special(false, false)),
                        holder("no_display", special(true, false)), holder("broken", special(false, true))))));
        var snapshot = source.forConnection(connection);
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.entries()).hasSize(1);
        assertThat(snapshot.recipeTypes()).containsExactly("minecraft:crafting");
        assertThat(snapshot.omittedWithoutDisplay()).isEqualTo(2);
        assertThat(snapshot.failed()).isTrue();
        assertThat(source.forConnection(new Object()).available()).isFalse();
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        catalog.refresh(session, 0, List.of(), RecipeScope.ALL_CRAFTABLE, snapshot);
        var view = catalog.query(session, new ClientRecipeCatalog.Query(
                ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:stick"), 1).recipes().getFirst();
        assertThat(view.supported()).isFalse();
        assertThat(view.result().deterministic()).isFalse();
        assertThat(catalog.resolve(session, view.recipeRef(), view.fingerprint())).isEmpty();
        source.clear();
        assertThat(source.forConnection(connection).available()).isFalse();
    }

    @Test
    void boundedSourceAndEmptyReloadReplaceEarlierData() {
        var source = new ClientSyncedRecipes();
        Object connection = new Object();
        source.receive(connection, java.util.Collections.nCopies(ClientSyncedRecipes.MAX_RECIPES + 1,
                holder("many", special(false, false))), List.of("minecraft:crafting"));
        var old = source.forConnection(connection);
        assertThat(old.entries()).hasSize(ClientSyncedRecipes.MAX_RECIPES);
        assertThat(old.limited()).isTrue();
        source.receive(connection, List.of(), List.of());
        assertThat(source.forConnection(connection).generation()).isGreaterThan(old.generation());
        assertThat(source.forConnection(connection).entries()).isEmpty();
        assertThat(source.forConnection(connection).available()).isTrue();
    }

    private static RecipeHolder<?> holder(String name, CustomRecipe recipe) {
        return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE,
                Identifier.fromNamespaceAndPath("test", name)), recipe);
    }

    private static CustomRecipe special(boolean empty, boolean fail) {
        return new CustomRecipe() {
            @Override public boolean matches(CraftingInput input, Level level) { throw new AssertionError(); }
            @Override public ItemStack assemble(CraftingInput input) { throw new AssertionError(); }
            @Override public RecipeSerializer<? extends CustomRecipe> getSerializer() { throw new AssertionError(); }
            @Override public List<RecipeDisplay> display() {
                if (fail) throw new IllegalStateException("must not be reflected");
                return empty ? List.of() : List.of(new ShapelessCraftingRecipeDisplay(
                        List.of(new SlotDisplay.ItemSlotDisplay(Items.STONE)),
                        new SlotDisplay.ItemSlotDisplay(Items.STICK),
                        new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
            }
        };
    }
}
