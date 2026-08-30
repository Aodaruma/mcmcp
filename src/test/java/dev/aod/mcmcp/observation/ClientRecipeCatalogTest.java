package dev.aod.mcmcp.observation;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
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
    void exposesTheThreeExactCookingFamiliesWithoutTreatingFuelAsAnIngredient() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        catalog.refresh(session, 1, List.of(
                cooking(20, Items.RAW_IRON, Items.IRON_INGOT, Items.FURNACE,
                        RecipeBookCategories.FURNACE_BLOCKS, 200,
                        Ingredient.of(Items.RAW_IRON)),
                cooking(21, Items.RAW_GOLD, Items.GOLD_INGOT, Items.BLAST_FURNACE,
                        RecipeBookCategories.BLAST_FURNACE_BLOCKS, 100,
                        Ingredient.of(Items.RAW_GOLD)),
                cooking(22, Items.BEEF, Items.COOKED_BEEF, Items.SMOKER,
                        RecipeBookCategories.SMOKER_FOOD, 100,
                        Ingredient.of(Items.BEEF))));

        assertCookingRecipe(catalog, session, "minecraft:iron_ingot",
                "smelting", "furnace", "minecraft:raw_iron");
        assertCookingRecipe(catalog, session, "minecraft:gold_ingot",
                "blasting", "blast_furnace", "minecraft:raw_gold");
        assertCookingRecipe(catalog, session, "minecraft:cooked_beef",
                "smoking", "smoker", "minecraft:beef");
    }

    @Test
    void cookingDurationParticipatesInTheOpaqueRecipeFingerprint() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        var firstEntry = cooking(30, Items.RAW_IRON, Items.IRON_INGOT, Items.FURNACE,
                RecipeBookCategories.FURNACE_BLOCKS, 200,
                Ingredient.of(Items.RAW_IRON));
        catalog.refresh(session, 1, List.of(firstEntry));
        var first = recipe(catalog, session, "minecraft:iron_ingot");

        catalog.refresh(session, 2, List.of(cooking(
                30, Items.RAW_IRON, Items.IRON_INGOT, Items.FURNACE,
                RecipeBookCategories.FURNACE_BLOCKS, 201,
                Ingredient.of(Items.RAW_IRON))));
        var changed = recipe(catalog, session, "minecraft:iron_ingot");

        assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(changed.recipeRef()).isNotEqualTo(first.recipeRef());
        assertThat(catalog.resolve(session, first.recipeRef(), first.fingerprint())).isEmpty();
    }

    @Test
    void cookingCategoryStationCampfireCustomAndAmbiguousInputsFailClosed() {
        var catalog = new ClientRecipeCatalog();
        UUID session = UUID.randomUUID();
        var ambiguousStation = new SlotDisplay.Composite(List.of(
                new SlotDisplay.ItemSlotDisplay(Items.FURNACE),
                new SlotDisplay.ItemSlotDisplay(Items.BLAST_FURNACE)));
        var customIngredient = DataComponentIngredient.of(
                true, new ItemStackTemplate(Items.COBBLESTONE, 1));
        catalog.refresh(session, 1, List.of(
                cooking(40, Items.CLAY_BALL, Items.BRICK, Items.BLAST_FURNACE,
                        RecipeBookCategories.FURNACE_MISC, 200,
                        Ingredient.of(Items.CLAY_BALL)),
                cooking(41, Items.CHICKEN, Items.COOKED_CHICKEN, Items.CAMPFIRE,
                        RecipeBookCategories.CAMPFIRE, 600,
                        Ingredient.of(Items.CHICKEN)),
                cooking(42, Items.SAND, Items.GLASS, ambiguousStation,
                        RecipeBookCategories.FURNACE_BLOCKS, 200,
                        Ingredient.of(Items.SAND)),
                cooking(43, Items.COBBLESTONE, Items.STONE, Items.FURNACE,
                        RecipeBookCategories.FURNACE_BLOCKS, 200,
                        customIngredient)));

        assertUnsupported(catalog, session, "minecraft:brick",
                "cooking_category_station_mismatch");
        assertUnsupported(catalog, session, "minecraft:cooked_chicken",
                "unsupported_cooking_category");
        assertUnsupported(catalog, session, "minecraft:glass",
                "unsupported_cooking_station");
        assertUnsupported(catalog, session, "minecraft:stone",
                "custom_or_empty_ingredient");
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

    private static RecipeDisplayEntry cooking(
            int id,
            Item ingredient,
            Item result,
            Item station,
            RecipeBookCategory category,
            int duration,
            Ingredient requirement) {
        return cooking(id, ingredient, result,
                new SlotDisplay.ItemSlotDisplay(station), category, duration, requirement);
    }

    private static RecipeDisplayEntry cooking(
            int id,
            Item ingredient,
            Item result,
            SlotDisplay station,
            RecipeBookCategory category,
            int duration,
            Ingredient requirement) {
        var display = new FurnaceRecipeDisplay(
                new SlotDisplay.ItemSlotDisplay(ingredient),
                SlotDisplay.AnyFuel.INSTANCE,
                new SlotDisplay.ItemSlotDisplay(result),
                station,
                duration,
                0.1F);
        return new RecipeDisplayEntry(
                new RecipeDisplayId(id), display, OptionalInt.empty(), category,
                Optional.of(List.of(requirement)));
    }

    private static ClientRecipeCatalog.RecipeView recipe(
            ClientRecipeCatalog catalog, UUID session, String resultItem) {
        return catalog.query(
                session,
                new ClientRecipeCatalog.Query(
                        ClientRecipeCatalog.QueryKind.RESULT_ITEM, resultItem),
                1).recipes().getFirst();
    }

    private static void assertCookingRecipe(
            ClientRecipeCatalog catalog,
            UUID session,
            String resultItem,
            String displayKind,
            String requiredScreen,
            String ingredient) {
        var recipe = recipe(catalog, session, resultItem);
        assertThat(recipe.supported()).isTrue();
        assertThat(recipe.displayKind()).isEqualTo(displayKind);
        assertThat(recipe.requiredScreen()).isEqualTo(requiredScreen);
        assertThat(recipe.ingredients()).singleElement()
                .satisfies(value -> assertThat(value.alternatives())
                        .containsExactly(ingredient));
        assertThat(recipe.ingredients()).allSatisfy(value ->
                assertThat(value.alternatives()).doesNotContain("minecraft:coal"));
    }

    private static void assertUnsupported(
            ClientRecipeCatalog catalog, UUID session, String resultItem, String reason) {
        var recipe = recipe(catalog, session, resultItem);
        assertThat(recipe.supported()).isFalse();
        assertThat(recipe.unsupportedReason()).isEqualTo(reason);
    }
}
