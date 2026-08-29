package dev.aod.mcmcp.brewing;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Closed wire-level allowlist plus Minecraft-backed runtime verifier for standard Vanilla
 * potion stacks and one brewing step.
 *
 * <p>The declared identities and recipe table contain no hidden world state. Runtime admission
 * additionally compares actual stack components with freshly constructed standard potions and
 * revalidates the same transition against the current connection's {@code PotionBrewing}
 * registry before clicking.</p>
 */
public final class StandardPotionPolicy {
    public static final String BREWING_STAND = "minecraft:brewing_stand";
    public static final String FUEL_ITEM = "minecraft:blaze_powder";

    private static final Set<String> POTION_ITEMS = Set.of(
            "minecraft:potion",
            "minecraft:splash_potion",
            "minecraft:lingering_potion");

    private static final Set<String> POTION_IDS = Set.of(
            "minecraft:water",
            "minecraft:mundane",
            "minecraft:thick",
            "minecraft:awkward",
            "minecraft:night_vision",
            "minecraft:long_night_vision",
            "minecraft:invisibility",
            "minecraft:long_invisibility",
            "minecraft:fire_resistance",
            "minecraft:long_fire_resistance",
            "minecraft:leaping",
            "minecraft:long_leaping",
            "minecraft:strong_leaping",
            "minecraft:slowness",
            "minecraft:long_slowness",
            "minecraft:strong_slowness",
            "minecraft:turtle_master",
            "minecraft:long_turtle_master",
            "minecraft:strong_turtle_master",
            "minecraft:swiftness",
            "minecraft:long_swiftness",
            "minecraft:strong_swiftness",
            "minecraft:water_breathing",
            "minecraft:long_water_breathing",
            "minecraft:healing",
            "minecraft:strong_healing",
            "minecraft:harming",
            "minecraft:strong_harming",
            "minecraft:poison",
            "minecraft:long_poison",
            "minecraft:strong_poison",
            "minecraft:regeneration",
            "minecraft:long_regeneration",
            "minecraft:strong_regeneration",
            "minecraft:strength",
            "minecraft:long_strength",
            "minecraft:strong_strength",
            "minecraft:weakness",
            "minecraft:long_weakness",
            "minecraft:slow_falling",
            "minecraft:long_slow_falling",
            "minecraft:wind_charged",
            "minecraft:oozing",
            "minecraft:infested",
            "minecraft:weaving");

    private static final Set<PotionMix> CORE_POTION_MIXES = Set.of(
            mix("water", "glowstone_dust", "thick"),
            mix("water", "redstone", "mundane"),
            mix("water", "nether_wart", "awkward"),
            startMix("breeze_rod", "wind_charged"),
            startMix("slime_block", "oozing"),
            startMix("stone", "infested"),
            startMix("cobweb", "weaving"),
            mix("awkward", "golden_carrot", "night_vision"),
            mix("night_vision", "redstone", "long_night_vision"),
            mix("night_vision", "fermented_spider_eye", "invisibility"),
            mix("long_night_vision", "fermented_spider_eye", "long_invisibility"),
            mix("invisibility", "redstone", "long_invisibility"),
            startMix("magma_cream", "fire_resistance"),
            mix("fire_resistance", "redstone", "long_fire_resistance"),
            startMix("rabbit_foot", "leaping"),
            mix("leaping", "redstone", "long_leaping"),
            mix("leaping", "glowstone_dust", "strong_leaping"),
            mix("leaping", "fermented_spider_eye", "slowness"),
            mix("long_leaping", "fermented_spider_eye", "long_slowness"),
            mix("slowness", "redstone", "long_slowness"),
            mix("slowness", "glowstone_dust", "strong_slowness"),
            mix("awkward", "turtle_helmet", "turtle_master"),
            mix("turtle_master", "redstone", "long_turtle_master"),
            mix("turtle_master", "glowstone_dust", "strong_turtle_master"),
            mix("swiftness", "fermented_spider_eye", "slowness"),
            mix("long_swiftness", "fermented_spider_eye", "long_slowness"),
            startMix("sugar", "swiftness"),
            mix("swiftness", "redstone", "long_swiftness"),
            mix("swiftness", "glowstone_dust", "strong_swiftness"),
            mix("awkward", "pufferfish", "water_breathing"),
            mix("water_breathing", "redstone", "long_water_breathing"),
            startMix("glistering_melon_slice", "healing"),
            mix("healing", "glowstone_dust", "strong_healing"),
            mix("healing", "fermented_spider_eye", "harming"),
            mix("strong_healing", "fermented_spider_eye", "strong_harming"),
            mix("harming", "glowstone_dust", "strong_harming"),
            mix("poison", "fermented_spider_eye", "harming"),
            mix("long_poison", "fermented_spider_eye", "harming"),
            mix("strong_poison", "fermented_spider_eye", "strong_harming"),
            startMix("spider_eye", "poison"),
            mix("poison", "redstone", "long_poison"),
            mix("poison", "glowstone_dust", "strong_poison"),
            startMix("ghast_tear", "regeneration"),
            mix("regeneration", "redstone", "long_regeneration"),
            mix("regeneration", "glowstone_dust", "strong_regeneration"),
            startMix("blaze_powder", "strength"),
            mix("strength", "redstone", "long_strength"),
            mix("strength", "glowstone_dust", "strong_strength"),
            mix("water", "fermented_spider_eye", "weakness"),
            mix("weakness", "redstone", "long_weakness"),
            mix("awkward", "phantom_membrane", "slow_falling"),
            mix("slow_falling", "redstone", "long_slow_falling"));

    /** Ingredients registered through Vanilla Builder.addStartMix. */
    private static final Set<String> START_MIX_INGREDIENTS = Set.of(
            "minecraft:breeze_rod",
            "minecraft:slime_block",
            "minecraft:stone",
            "minecraft:cobweb",
            "minecraft:magma_cream",
            "minecraft:rabbit_foot",
            "minecraft:sugar",
            "minecraft:glistering_melon_slice",
            "minecraft:spider_eye",
            "minecraft:ghast_tear",
            "minecraft:blaze_powder");

    private static final Set<PotionMix> POTION_MIXES = buildPotionMixes();

    private static final Set<String> INGREDIENT_ITEMS = buildIngredientItems();

    private StandardPotionPolicy() {
    }

    public static Set<String> potionItems() {
        return POTION_ITEMS;
    }

    public static Set<String> potionIds() {
        return POTION_IDS;
    }

    public static Set<String> ingredientItems() {
        return INGREDIENT_ITEMS;
    }

    public static Set<PotionMix> potionMixes() {
        return POTION_MIXES;
    }

    /**
     * Identifies only a component-exact Vanilla singleton potion stack. Custom potion contents,
     * names, lore, any other component patch, or an impossible overstack make it ineligible.
     */
    public static Optional<Identity> identify(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty() || stack.getCount() != 1) {
            return Optional.empty();
        }
        Identifier itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey == null) return Optional.empty();
        String itemId = itemKey.toString();
        if (!POTION_ITEMS.contains(itemId)) return Optional.empty();
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()
                || contents.customColor().isPresent()
                || !contents.customEffects().isEmpty()
                || contents.customName().isPresent()) {
            return Optional.empty();
        }
        Identifier potionKey = BuiltInRegistries.POTION
                .getKey(contents.potion().orElseThrow().value());
        if (potionKey == null) return Optional.empty();
        String potionId = potionKey.toString();
        if (!POTION_IDS.contains(potionId)) return Optional.empty();
        var identity = new Identity(itemId, potionId, stack.getCount());
        Optional<ItemStack> standard = standardStack(
                identity.item(), identity.potion(), identity.count());
        return standard.filter(value -> ItemStack.matches(stack, value))
                .map(ignored -> identity);
    }

    /**
     * Validates the catalog-fixed transition and independently requires the current connection's
     * Vanilla brewing registry to produce the exact declared standard output.
     */
    public static boolean validateDeclaredTransition(
            ClientPacketListener connection,
            StandardPotionStackSpec input,
            String ingredientItem,
            StandardPotionStackSpec output) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(ingredientItem, "ingredientItem");
        Objects.requireNonNull(output, "output");
        if (input.count() != output.count()
                || !isKnownOneStepRecipe(input, ingredientItem, output)) {
            return false;
        }
        Optional<ItemStack> inputStack = standardStack(input);
        Optional<ItemStack> outputStack = standardStack(output);
        Identifier ingredientId = Identifier.tryParse(ingredientItem);
        var ingredient = ingredientId == null
                ? Optional.<net.minecraft.core.Holder.Reference<Item>>empty()
                : BuiltInRegistries.ITEM.get(ingredientId);
        if (inputStack.isEmpty() || outputStack.isEmpty() || ingredient.isEmpty()) {
            return false;
        }
        ItemStack mixed = connection.potionBrewing().mix(
                new ItemStack(ingredient.orElseThrow()), inputStack.orElseThrow());
        if (mixed.isEmpty()) return false;
        mixed = mixed.copyWithCount(input.count());
        return ItemStack.matches(mixed, outputStack.orElseThrow());
    }

    public static boolean isKnownOneStepRecipe(
            StandardPotionStackSpec input,
            String ingredientItem,
            StandardPotionStackSpec output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        return input.count() == output.count()
                && isKnownOneStepRecipe(
                        input.item(), input.potion(), ingredientItem,
                        output.item(), output.potion());
    }

    /** True only for a catalog-fixed standard Vanilla one-step recipe. */
    public static boolean isKnownOneStepRecipe(
            String inputItem,
            String inputPotion,
            String ingredientItem,
            String outputItem,
            String outputPotion) {
        if (!POTION_ITEMS.contains(inputItem)
                || !POTION_IDS.contains(inputPotion)
                || !INGREDIENT_ITEMS.contains(ingredientItem)
                || !POTION_ITEMS.contains(outputItem)
                || !POTION_IDS.contains(outputPotion)) {
            return false;
        }
        if ("minecraft:potion".equals(inputItem)
                && "minecraft:gunpowder".equals(ingredientItem)) {
            return "minecraft:splash_potion".equals(outputItem)
                    && inputPotion.equals(outputPotion);
        }
        if ("minecraft:splash_potion".equals(inputItem)
                && "minecraft:dragon_breath".equals(ingredientItem)) {
            return "minecraft:lingering_potion".equals(outputItem)
                    && inputPotion.equals(outputPotion);
        }
        return inputItem.equals(outputItem)
                && POTION_MIXES.contains(new PotionMix(
                        inputPotion, ingredientItem, outputPotion));
    }

    private static Optional<ItemStack> standardStack(StandardPotionStackSpec spec) {
        return standardStack(spec.item(), spec.potion(), spec.count());
    }

    private static Optional<ItemStack> standardStack(
            String itemResource, String potionResource, int count) {
        Identifier itemId = Identifier.tryParse(itemResource);
        Identifier potionId = Identifier.tryParse(potionResource);
        if (itemId == null || potionId == null) return Optional.empty();
        var item = BuiltInRegistries.ITEM.get(itemId);
        var potion = BuiltInRegistries.POTION.get(potionId);
        if (item.isEmpty() || potion.isEmpty()) return Optional.empty();
        return Optional.of(PotionContents.createItemStack(
                        item.orElseThrow().value(), potion.orElseThrow())
                .copyWithCount(count));
    }

    private static Set<String> buildIngredientItems() {
        var result = new LinkedHashSet<String>();
        result.add("minecraft:gunpowder");
        result.add("minecraft:dragon_breath");
        POTION_MIXES.stream().map(PotionMix::ingredientItem).forEach(result::add);
        return Set.copyOf(result);
    }

    private static Set<PotionMix> buildPotionMixes() {
        var result = new LinkedHashSet<>(CORE_POTION_MIXES);
        for (String ingredient : START_MIX_INGREDIENTS) {
            result.add(new PotionMix(
                    "minecraft:water", ingredient, "minecraft:mundane"));
        }
        return Set.copyOf(result);
    }

    private static PotionMix startMix(String ingredient, String output) {
        return mix("awkward", ingredient, output);
    }

    private static PotionMix mix(String input, String ingredient, String output) {
        return new PotionMix(id(input), id(ingredient), id(output));
    }

    private static String id(String path) {
        return "minecraft:" + path;
    }

    public record PotionMix(
            String inputPotion,
            String ingredientItem,
            String outputPotion) {
        public PotionMix {
            Objects.requireNonNull(inputPotion, "inputPotion");
            Objects.requireNonNull(ingredientItem, "ingredientItem");
            Objects.requireNonNull(outputPotion, "outputPotion");
        }
    }

    public record Identity(String item, String potion, int count) {
        public Identity {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(potion, "potion");
            if (!POTION_ITEMS.contains(item) || !POTION_IDS.contains(potion) || count < 1) {
                throw new IllegalArgumentException("invalid standard potion identity");
            }
        }
    }
}
