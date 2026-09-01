package dev.aod.mcmcp.observation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Session-scoped view of recipes the server actually synchronized into the client recipe book.
 *
 * <p>This class deliberately never reads an integrated server {@code RecipeManager}. Recipe
 * display ids are connection-local integers, so callers receive opaque references that are
 * invalidated whenever the world session or known-display set changes.</p>
 */
public final class ClientRecipeCatalog {
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int OPAQUE_REF_BYTES = 18;
    private static final int MAX_RESULT_ALTERNATIVES = 16;
    private static final int MAX_INGREDIENT_ALTERNATIVES = 128;

    private final SecureRandom random;
    private UUID worldSessionId;
    private long clientTick;
    private long revision;
    private String contentSignature;
    private List<CatalogRecipe> recipes = List.of();
    private Map<String, CatalogRecipe> byOpaqueRef = Map.of();

    public ClientRecipeCatalog() {
        this(new SecureRandom());
    }

    ClientRecipeCatalog(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Refreshes from the public client recipe-book collections, never from server internals. */
    public synchronized Snapshot refreshFromClient(
            Minecraft minecraft, UUID expectedWorldSessionId, long observedClientTick) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (minecraft.player == null) {
            throw new IllegalStateException("no local player recipe book is available");
        }
        var entries = new TreeMap<Integer, RecipeDisplayEntry>();
        for (var collection : minecraft.player.getRecipeBook().getCollections()) {
            for (var entry : collection.getRecipes()) {
                entries.put(entry.id().index(), entry);
            }
        }
        return refresh(expectedWorldSessionId, observedClientTick, entries.values());
    }

    /** Package-visible seam used by deterministic unit tests and packet-level adapters. */
    synchronized Snapshot refresh(
            UUID expectedWorldSessionId,
            long observedClientTick,
            Collection<RecipeDisplayEntry> knownEntries) {
        Objects.requireNonNull(expectedWorldSessionId, "expectedWorldSessionId");
        Objects.requireNonNull(knownEntries, "knownEntries");
        if (observedClientTick < 0) {
            throw new IllegalArgumentException("observedClientTick must be non-negative");
        }

        var byId = new TreeMap<Integer, RecipeDisplayEntry>();
        for (var entry : knownEntries) {
            Objects.requireNonNull(entry, "known recipe entry");
            var previous = byId.put(entry.id().index(), entry);
            if (previous != null && !previous.equals(entry)) {
                throw new IllegalArgumentException("duplicate recipe display id with different contents");
            }
        }

        var extracted = new ArrayList<ExtractedRecipe>(byId.size());
        for (var entry : byId.values()) {
            extracted.add(extract(entry));
        }
        String signature = signature(extracted);
        boolean newSession = !expectedWorldSessionId.equals(worldSessionId);
        if (newSession || !Objects.equals(signature, contentSignature)) {
            revision = newSession ? 1 : Math.addExact(revision, 1);
            var replacement = new ArrayList<CatalogRecipe>(extracted.size());
            var refs = new LinkedHashMap<String, CatalogRecipe>();
            for (var value : extracted) {
                String opaqueRef = newOpaqueRef(refs.keySet());
                var recipe = new CatalogRecipe(opaqueRef, value);
                replacement.add(recipe);
                refs.put(opaqueRef, recipe);
            }
            recipes = List.copyOf(replacement);
            byOpaqueRef = Map.copyOf(refs);
            contentSignature = signature;
        }
        worldSessionId = expectedWorldSessionId;
        clientTick = observedClientTick;
        return snapshot();
    }

    public synchronized QueryResult query(UUID expectedWorldSessionId, Query query, int maxResults) {
        requireSession(expectedWorldSessionId);
        Objects.requireNonNull(query, "query");
        if (maxResults < 1 || maxResults > 64) {
            throw new IllegalArgumentException("maxResults must be in 1..64");
        }

        List<CatalogRecipe> matched = recipes.stream()
                .filter(recipe -> query.matches(recipe.extracted()))
                .sorted(Comparator.comparing(recipe -> recipe.extracted().fingerprint()))
                .toList();
        List<RecipeView> returned = matched.stream()
                .limit(maxResults)
                .map(CatalogRecipe::toView)
                .toList();
        return new QueryResult(
                new Basis(worldSessionId, clientTick, revision),
                new Coverage(recipes.size(), matched.size(), returned.size(), matched.size() > returned.size()),
                returned);
    }

    /** Resolves only the current session/revision ref and exact advertised fingerprint. */
    public synchronized Optional<ResolvedRecipe> resolve(
            UUID expectedWorldSessionId, String recipeRef, String advertisedFingerprint) {
        requireSession(expectedWorldSessionId);
        if (recipeRef == null || advertisedFingerprint == null) {
            return Optional.empty();
        }
        CatalogRecipe recipe = byOpaqueRef.get(recipeRef);
        if (recipe == null || !MessageDigest.isEqual(
                recipe.extracted().fingerprint().getBytes(StandardCharsets.UTF_8),
                advertisedFingerprint.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedRecipe(
                recipe.extracted().displayId(),
                recipe.extracted().fingerprint(),
                recipe.toView(),
                recipe.extracted().durationTicks(),
                worldSessionId,
                revision));
    }

    public synchronized Snapshot snapshot() {
        if (worldSessionId == null) {
            throw new IllegalStateException("recipe catalog is detached");
        }
        return new Snapshot(worldSessionId, clientTick, revision, recipes.size());
    }

    public synchronized void detachSession() {
        worldSessionId = null;
        clientTick = 0;
        revision = 0;
        contentSignature = null;
        recipes = List.of();
        byOpaqueRef = Map.of();
    }

    private void requireSession(UUID expectedWorldSessionId) {
        Objects.requireNonNull(expectedWorldSessionId, "expectedWorldSessionId");
        if (!expectedWorldSessionId.equals(worldSessionId)) {
            throw new IllegalArgumentException("recipe catalog belongs to another world session");
        }
    }

    private ExtractedRecipe extract(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        String displayKind;
        Shape shape;
        String requiredScreen;
        List<SlotDisplay> displayedIngredients;
        int durationTicks = 0;
        String unsupportedReason = null;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            displayKind = "shaped";
            shape = new Shape(shaped.width(), shaped.height());
            requiredScreen = shaped.width() <= 2 && shaped.height() <= 2
                    ? "inventory_2x2" : "crafting_table";
            displayedIngredients = shaped.ingredients();
        }
        else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            displayKind = "shapeless";
            shape = null;
            requiredScreen = shapeless.ingredients().size() <= 4
                    ? "inventory_2x2" : "crafting_table";
            displayedIngredients = shapeless.ingredients();
        }
        else if (display instanceof FurnaceRecipeDisplay furnace) {
            shape = null;
            displayedIngredients = List.of(furnace.ingredient());
            durationTicks = furnace.duration();
            CookingFamily categoryFamily = cookingCategory(entry.category());
            CookingFamily stationFamily = cookingStation(furnace.craftingStation());
            if (categoryFamily == null) {
                displayKind = "other";
                requiredScreen = "unsupported";
                unsupportedReason = "unsupported_cooking_category";
            }
            else if (stationFamily == null) {
                displayKind = "other";
                requiredScreen = "unsupported";
                unsupportedReason = "unsupported_cooking_station";
            }
            else if (categoryFamily != stationFamily) {
                displayKind = "other";
                requiredScreen = "unsupported";
                unsupportedReason = "cooking_category_station_mismatch";
            }
            else {
                displayKind = categoryFamily.displayKind;
                requiredScreen = categoryFamily.requiredScreen;
                if (durationTicks < 1) {
                    unsupportedReason = "invalid_cooking_duration";
                }
            }
        }
        else {
            displayKind = "other";
            shape = null;
            requiredScreen = "unsupported";
            displayedIngredients = List.of();
        }

        Result result = extractResult(display.result());
        var ingredients = new ArrayList<IngredientView>();
        if (unsupportedReason == null && displayKind.equals("other")) {
            unsupportedReason = "unsupported_display_kind";
        }
        else if (unsupportedReason == null && entry.craftingRequirements().isEmpty()) {
            unsupportedReason = "missing_crafting_requirements";
        }
        else if (unsupportedReason == null) {
            List<Ingredient> requirements = entry.craftingRequirements().orElseThrow();
            boolean cooking = durationTicks > 0;
            if (requirements.isEmpty() || requirements.size() > 9
                    || (cooking && requirements.size() != 1)) {
                unsupportedReason = "invalid_crafting_requirements";
            }
            else {
                for (int index = 0; index < requirements.size(); index++) {
                    Ingredient ingredient = requirements.get(index);
                    if (!ingredient.isSimple() || ingredient.isCustom() || ingredient.isEmpty()) {
                        unsupportedReason = "custom_or_empty_ingredient";
                        ingredients.clear();
                        break;
                    }
                    List<String> alternatives = ingredient.items()
                            .map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString())
                            .filter(REGISTRY_ID.asMatchPredicate())
                            .distinct()
                            .sorted()
                            .limit(MAX_INGREDIENT_ALTERNATIVES + 1L)
                            .toList();
                    if (alternatives.isEmpty() || alternatives.size() > MAX_INGREDIENT_ALTERNATIVES) {
                        unsupportedReason = "ingredient_alternatives_out_of_bounds";
                        ingredients.clear();
                        break;
                    }
                    boolean hasRemainder = ingredient.items().anyMatch(holder -> {
                        var remainder = holder.value().getCraftingRemainder();
                        return remainder != null
                                && remainder.count() > 0
                                && remainder.item().value() != Items.AIR;
                    });
                    if (hasRemainder) {
                        unsupportedReason = "crafting_remainder";
                        ingredients.clear();
                        break;
                    }
                    ingredients.add(new IngredientView(index, 1, alternatives));
                }
            }
        }
        if (!displayedIngredients.isEmpty()
                && entry.craftingRequirements().isPresent()
                && displayedIngredients.size() < entry.craftingRequirements().orElseThrow().size()) {
            unsupportedReason = "display_requirement_mismatch";
        }
        if (result.alternatives().size() != 1 || !result.deterministic()) {
            unsupportedReason = unsupportedReason == null ? "non_deterministic_result" : unsupportedReason;
        }
        boolean supported = unsupportedReason == null;

        var resultTags = new HashSet<String>();
        for (var alternative : result.alternatives()) {
            var item = BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.tryParse(alternative.item()));
            if (item.isPresent()) {
                item.orElseThrow().tags()
                        .map(tag -> tag.location().toString())
                        .forEach(resultTags::add);
            }
        }

        var fingerprint = fingerprint(
                entry.id().index(), displayKind, requiredScreen, shape, durationTicks,
                result, ingredients, supported, unsupportedReason);
        return new ExtractedRecipe(
                entry.id(), fingerprint, displayKind, requiredScreen, supported, unsupportedReason,
                result, List.copyOf(ingredients), shape, durationTicks, Set.copyOf(resultTags));
    }

    private static CookingFamily cookingCategory(RecipeBookCategory category) {
        if (category == RecipeBookCategories.FURNACE_FOOD
                || category == RecipeBookCategories.FURNACE_BLOCKS
                || category == RecipeBookCategories.FURNACE_MISC) {
            return CookingFamily.SMELTING;
        }
        if (category == RecipeBookCategories.BLAST_FURNACE_BLOCKS
                || category == RecipeBookCategories.BLAST_FURNACE_MISC) {
            return CookingFamily.BLASTING;
        }
        return category == RecipeBookCategories.SMOKER_FOOD
                ? CookingFamily.SMOKING : null;
    }

    private static CookingFamily cookingStation(SlotDisplay display) {
        if (!(display instanceof SlotDisplay.ItemSlotDisplay station)) {
            return null;
        }
        var item = station.item().value();
        if (item == Items.FURNACE) return CookingFamily.SMELTING;
        if (item == Items.BLAST_FURNACE) return CookingFamily.BLASTING;
        return item == Items.SMOKER ? CookingFamily.SMOKING : null;
    }

    private static Result extractResult(SlotDisplay display) {
        net.minecraft.core.Holder<net.minecraft.world.item.Item> item;
        int count;
        boolean defaultComponents;
        if (display instanceof SlotDisplay.ItemSlotDisplay direct) {
            return resultFor(direct.item(), 1, true);
        }
        else if (display instanceof SlotDisplay.ItemStackSlotDisplay template) {
            item = template.stack().item();
            count = template.stack().count();
            defaultComponents = template.stack().components().isEmpty();
        }
        else {
            return new Result(false, List.of());
        }
        return resultFor(item, count, defaultComponents);
    }

    private static Result resultFor(
            net.minecraft.core.Holder<net.minecraft.world.item.Item> item,
            int count,
            boolean defaultComponents) {
        if (item.value() == Items.AIR || count < 1 || !defaultComponents) {
            return new Result(false, List.of());
        }
        String itemId = BuiltInRegistries.ITEM.getKey(item.value()).toString();
        if (!REGISTRY_ID.matcher(itemId).matches()) {
            return new Result(false, List.of());
        }
        var alternative = new ResultAlternative(
                itemId, count, stackFingerprint(itemId, count));
        return new Result(true, List.of(alternative));
    }

    private static String signature(List<ExtractedRecipe> recipes) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(recipes.size());
            for (var recipe : recipes) {
                out.writeInt(recipe.displayId().index());
                writeString(out, recipe.fingerprint());
            }
            out.flush();
            return sha256(bytes.toByteArray());
        }
        catch (IOException impossible) {
            throw new IllegalStateException("in-memory recipe signature failed", impossible);
        }
    }

    private static String fingerprint(
            int displayId,
            String displayKind,
            String requiredScreen,
            Shape shape,
            int durationTicks,
            Result result,
            List<IngredientView> ingredients,
            boolean supported,
            String unsupportedReason) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            writeString(out, "mcmcp-recipe-display/v1");
            out.writeInt(displayId);
            writeString(out, displayKind);
            writeString(out, requiredScreen);
            out.writeBoolean(shape != null);
            if (shape != null) {
                out.writeInt(shape.width());
                out.writeInt(shape.height());
            }
            out.writeInt(durationTicks);
            out.writeBoolean(result.deterministic());
            out.writeInt(result.alternatives().size());
            for (var alternative : result.alternatives()) {
                writeString(out, alternative.item());
                out.writeInt(alternative.count());
                writeString(out, alternative.stackFingerprint());
            }
            out.writeInt(ingredients.size());
            for (var ingredient : ingredients) {
                out.writeInt(ingredient.index());
                out.writeInt(ingredient.countPerCraft());
                out.writeInt(ingredient.alternatives().size());
                for (var alternative : ingredient.alternatives()) {
                    writeString(out, alternative);
                }
            }
            out.writeBoolean(supported);
            writeString(out, unsupportedReason == null ? "" : unsupportedReason);
            out.flush();
            return "sha256:" + sha256(bytes.toByteArray());
        }
        catch (IOException impossible) {
            throw new IllegalStateException("in-memory recipe fingerprint failed", impossible);
        }
    }

    private static String stackFingerprint(String itemId, int count) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            writeString(out, "mcmcp-default-stack/v1");
            writeString(out, itemId);
            out.writeInt(count);
            out.flush();
            return "sha256:" + sha256(bytes.toByteArray());
        }
        catch (IOException impossible) {
            throw new IllegalStateException("in-memory stack fingerprint failed", impossible);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String newOpaqueRef(Set<String> existing) {
        var bytes = new byte[OPAQUE_REF_BYTES];
        String value;
        do {
            random.nextBytes(bytes);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        while (existing.contains(value));
        return value;
    }

    public enum QueryKind {
        RESULT_ITEM,
        RESULT_TAG
    }

    public record Query(QueryKind kind, String value) {
        public Query {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            if (!REGISTRY_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("query value must be a registry id");
            }
        }

        private boolean matches(ExtractedRecipe recipe) {
            return switch (kind) {
                case RESULT_ITEM -> recipe.result().alternatives().stream()
                        .anyMatch(alternative -> value.equals(alternative.item()));
                case RESULT_TAG -> recipe.resultTags().contains(value);
            };
        }
    }

    public record Snapshot(UUID worldSessionId, long clientTick, long recipeBookRevision, int knownRecipes) {
        public Snapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
        }
    }

    public record Basis(UUID worldSessionId, long clientTick, long recipeBookRevision) {
        public Basis {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "world_session_id", worldSessionId.toString(),
                    "client_tick", clientTick,
                    "recipe_book_revision", recipeBookRevision);
        }
    }

    public record Coverage(int known, int matched, int returned, boolean truncated) {
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("source", "client_known_recipe_displays");
            map.put("complete", false);
            map.put("known", known);
            map.put("matched", matched);
            map.put("returned", returned);
            map.put("truncated", truncated);
            return map;
        }
    }

    public record QueryResult(Basis basis, Coverage coverage, List<RecipeView> recipes) {
        public QueryResult {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(coverage, "coverage");
            recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
        }

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("basis", basis.toMap());
            map.put("coverage", coverage.toMap());
            map.put("recipes", recipes.stream().map(RecipeView::toMap).toList());
            return map;
        }
    }

    public record RecipeView(
            String recipeRef,
            String fingerprint,
            String displayKind,
            String requiredScreen,
            boolean supported,
            String unsupportedReason,
            Result result,
            List<IngredientView> ingredients,
            Shape shape) {
        public RecipeView {
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(displayKind, "displayKind");
            Objects.requireNonNull(requiredScreen, "requiredScreen");
            Objects.requireNonNull(result, "result");
            ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        }

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("recipe_ref", recipeRef);
            map.put("fingerprint", fingerprint);
            map.put("display_kind", displayKind);
            map.put("required_screen", requiredScreen);
            map.put("supported", supported);
            map.put("unsupported_reason", unsupportedReason);
            map.put("result", result.toMap());
            map.put("ingredients", ingredients.stream().map(IngredientView::toMap).toList());
            map.put("shape", shape == null ? null : shape.toMap());
            return map;
        }
    }

    public record Result(boolean deterministic, List<ResultAlternative> alternatives) {
        public Result {
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
            if (alternatives.size() > MAX_RESULT_ALTERNATIVES) {
                throw new IllegalArgumentException("too many result alternatives");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "deterministic", deterministic,
                    "alternatives", alternatives.stream().map(ResultAlternative::toMap).toList());
        }
    }

    public record ResultAlternative(String item, int count, String stackFingerprint) {
        public ResultAlternative {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackFingerprint, "stackFingerprint");
            if (count < 1) {
                throw new IllegalArgumentException("result count must be positive");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("item", item, "count", count, "stack_fingerprint", stackFingerprint);
        }
    }

    public record IngredientView(int index, int countPerCraft, List<String> alternatives) {
        public IngredientView {
            if (index < 0 || countPerCraft < 1) {
                throw new IllegalArgumentException("invalid ingredient index/count");
            }
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "index", index,
                    "count_per_craft", countPerCraft,
                    "alternatives", alternatives.stream().map(item -> Map.of("item", item)).toList());
        }
    }

    public record Shape(int width, int height) {
        public Shape {
            if (width < 1 || width > 3 || height < 1 || height > 3) {
                throw new IllegalArgumentException("recipe shape must fit a 3x3 crafting grid");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("width", width, "height", height);
        }
    }

    public record ResolvedRecipe(
            RecipeDisplayId displayId,
            String fingerprint,
            RecipeView view,
            int cookingDurationTicks,
            UUID worldSessionId,
            long recipeBookRevision) {
        public ResolvedRecipe {
            Objects.requireNonNull(displayId, "displayId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (cookingDurationTicks < 0) {
                throw new IllegalArgumentException("cooking duration must be non-negative");
            }
        }
    }

    private record CatalogRecipe(String opaqueRef, ExtractedRecipe extracted) {
        private CatalogRecipe {
            Objects.requireNonNull(opaqueRef, "opaqueRef");
            Objects.requireNonNull(extracted, "extracted");
        }

        private RecipeView toView() {
            return new RecipeView(
                    opaqueRef,
                    extracted.fingerprint(),
                    extracted.displayKind(),
                    extracted.requiredScreen(),
                    extracted.supported(),
                    extracted.unsupportedReason(),
                    extracted.result(),
                    extracted.ingredients(),
                    extracted.shape());
        }
    }

    private record ExtractedRecipe(
            RecipeDisplayId displayId,
            String fingerprint,
            String displayKind,
            String requiredScreen,
            boolean supported,
            String unsupportedReason,
            Result result,
            List<IngredientView> ingredients,
            Shape shape,
            int durationTicks,
            Set<String> resultTags) {
        private ExtractedRecipe {
            Objects.requireNonNull(displayId, "displayId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(displayKind, "displayKind");
            Objects.requireNonNull(requiredScreen, "requiredScreen");
            Objects.requireNonNull(result, "result");
            ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
            resultTags = Set.copyOf(Objects.requireNonNull(resultTags, "resultTags"));
        }
    }

    private enum CookingFamily {
        SMELTING("smelting", "furnace"),
        BLASTING("blasting", "blast_furnace"),
        SMOKING("smoking", "smoker");

        private final String displayKind;
        private final String requiredScreen;

        CookingFamily(String displayKind, String requiredScreen) {
            this.displayKind = displayKind;
            this.requiredScreen = requiredScreen;
        }
    }
}
