package dev.aodaruma.craftagent.observation;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientRecipeCatalogTest {
    @Test
    void exposesOnlyKnownClientDisplaysWithOpaqueSessionScopedReferences() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        catalog.refresh(session, 12, List.of(shapeless(7, Items.OAK_LOG, Items.OAK_PLANKS, 4)));

        var result = catalog.query(
                session,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:oak_planks"),
                64);

        assertThat(result.basis().worldSessionId()).isEqualTo(session);
        assertThat(result.basis().recipeBookRevision()).isEqualTo(1);
        assertThat(result.coverage().known()).isEqualTo(1);
        assertThat(result.coverage().matched()).isEqualTo(1);
        assertThat(result.coverage().truncated()).isFalse();
        assertThat(result.coverage().toMap())
                .containsEntry("source", "client_known_recipe_displays")
                .containsEntry("complete", false);

        var recipe = result.recipes().getFirst();
        assertThat(recipe.recipeRef()).matches("[A-Za-z0-9_-]{24}");
        assertThat(recipe.fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(recipe.displayKind()).isEqualTo("shapeless");
        assertThat(recipe.requiredScreen()).isEqualTo("inventory_2x2");
        assertThat(recipe.supported()).isTrue();
        assertThat(recipe.result().alternatives().getFirst().item()).isEqualTo("minecraft:oak_planks");
        assertThat(recipe.result().alternatives().getFirst().count()).isEqualTo(4);
        assertThat(recipe.ingredients()).singleElement()
                .satisfies(ingredient -> assertThat(ingredient.alternatives())
                        .containsExactly("minecraft:oak_log"));

        assertThat(catalog.resolve(session, recipe.recipeRef(), recipe.fingerprint()))
                .get()
                .extracting(resolved -> resolved.displayId().index())
                .isEqualTo(7);
    }

    @Test
    void unchangedDisplaySetKeepsGenerationAndRefsButAnyChangeInvalidatesThem() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        var firstEntry = shapeless(3, Items.OAK_LOG, Items.OAK_PLANKS, 4);
        catalog.refresh(session, 1, List.of(firstEntry));
        var first = catalog.query(
                session,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:oak_planks"),
                1).recipes().getFirst();

        var unchanged = catalog.refresh(session, 2, List.of(firstEntry));
        var replay = catalog.query(
                session,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:oak_planks"),
                1).recipes().getFirst();
        assertThat(unchanged.recipeBookRevision()).isEqualTo(1);
        assertThat(replay.recipeRef()).isEqualTo(first.recipeRef());

        var changed = catalog.refresh(
                session, 3, List.of(shapeless(3, Items.OAK_LOG, Items.OAK_PLANKS, 2)));
        assertThat(changed.recipeBookRevision()).isEqualTo(2);
        assertThat(catalog.resolve(session, first.recipeRef(), first.fingerprint())).isEmpty();
    }

    @Test
    void aNewWorldSessionInvalidatesAllPreviousReferences() {
        var catalog = new ClientRecipeCatalog();
        UUID firstSession = UUID.randomUUID();
        catalog.refresh(firstSession, 1, List.of(shapeless(1, Items.OAK_LOG, Items.OAK_PLANKS, 4)));
        var first = catalog.query(
                firstSession,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:oak_planks"),
                1).recipes().getFirst();

        UUID secondSession = UUID.randomUUID();
        var replacement = catalog.refresh(
                secondSession, 1, List.of(shapeless(1, Items.OAK_LOG, Items.OAK_PLANKS, 4)));

        assertThat(replacement.recipeBookRevision()).isEqualTo(1);
        assertThatThrownBy(() -> catalog.resolve(firstSession, first.recipeRef(), first.fingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another world session");
        assertThat(catalog.resolve(secondSession, first.recipeRef(), first.fingerprint())).isEmpty();
    }

    @Test
    void craftingRemaindersAreVisibleButNeverAdvertisedAsExecutable() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        catalog.refresh(session, 1, List.of(shapeless(9, Items.MILK_BUCKET, Items.STONE, 1)));

        var recipe = catalog.query(
                session,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:stone"),
                1).recipes().getFirst();

        assertThat(recipe.supported()).isFalse();
        assertThat(recipe.unsupportedReason()).isEqualTo("crafting_remainder");
        assertThat(recipe.ingredients()).isEmpty();
    }

    @Test
    void queryAndLimitAreClosedAndBounded() {
        assertThatThrownBy(() -> new ClientRecipeCatalog.Query(
                ClientRecipeCatalog.QueryKind.RESULT_ITEM, "not a registry id"))
                .isInstanceOf(IllegalArgumentException.class);

        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        catalog.refresh(session, 0, List.of());
        assertThatThrownBy(() -> catalog.query(
                session,
                new ClientRecipeCatalog.Query(ClientRecipeCatalog.QueryKind.RESULT_ITEM, "minecraft:stone"),
                65)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RecipeDisplayEntry shapeless(
            int id, Item ingredient, Item result, int resultCount) {
        var display = new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.ItemSlotDisplay(ingredient)),
                new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(result, resultCount)),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        return new RecipeDisplayEntry(
                new RecipeDisplayId(id),
                display,
                OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_BUILDING_BLOCKS,
                Optional.of(List.of(Ingredient.of(ingredient))));
    }
}
