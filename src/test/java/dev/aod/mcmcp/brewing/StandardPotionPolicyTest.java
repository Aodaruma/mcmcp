package dev.aod.mcmcp.brewing;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.flag.FeatureFlags;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardPotionPolicyTest {
    @Test
    void identifiesOnlyComponentExactStandardPotionStacks() {
        ItemStack standard = standardPotion(
                "minecraft:potion", "minecraft:awkward");

        assertThat(StandardPotionPolicy.identify(standard))
                .contains(new StandardPotionPolicy.Identity(
                        "minecraft:potion", "minecraft:awkward", 1));

        ItemStack named = standard.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("not standard"));
        assertThat(StandardPotionPolicy.identify(named)).isEmpty();

        assertThat(StandardPotionPolicy.identify(standard.copyWithCount(2))).isEmpty();
    }

    @Test
    void closesEveryDeclaredPotionMixAcrossTheThreeStandardContainers() {
        assertThat(StandardPotionPolicy.potionMixes()).hasSize(63);
        for (var mix : StandardPotionPolicy.potionMixes()) {
            for (String item : StandardPotionPolicy.potionItems()) {
                assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                        item,
                        mix.inputPotion(),
                        mix.ingredientItem(),
                        item,
                        mix.outputPotion())).isTrue();
            }
        }
        assertThat(StandardPotionPolicy.ingredientItems()).hasSize(21);
        assertThat(StandardPotionPolicy.potionIds()).hasSize(45);
    }

    @Test
    void everyClosedTransitionMatchesTheCurrentTwentySixTwoVanillaBrewingTable() {
        PotionBrewing brewing = PotionBrewing.bootstrap(FeatureFlags.DEFAULT_FLAGS);
        for (var mix : StandardPotionPolicy.potionMixes()) {
            for (String item : StandardPotionPolicy.potionItems()) {
                ItemStack actual = brewing.mix(
                        item(mix.ingredientItem()),
                        standardPotion(item, mix.inputPotion()));
                assertThat(ItemStack.matches(
                        actual, standardPotion(item, mix.outputPotion())))
                        .as("%s + %s -> %s (%s)",
                                mix.inputPotion(), mix.ingredientItem(),
                                mix.outputPotion(), item)
                        .isTrue();
            }
        }
        for (String potion : StandardPotionPolicy.potionIds()) {
            assertThat(ItemStack.matches(
                    brewing.mix(
                            item("minecraft:gunpowder"),
                            standardPotion("minecraft:potion", potion)),
                    standardPotion("minecraft:splash_potion", potion))).isTrue();
            assertThat(ItemStack.matches(
                    brewing.mix(
                            item("minecraft:dragon_breath"),
                            standardPotion("minecraft:splash_potion", potion)),
                    standardPotion("minecraft:lingering_potion", potion))).isTrue();
        }

        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                "minecraft:potion", "minecraft:awkward", "minecraft:breeze_rod",
                "minecraft:potion", "minecraft:wind_charged")).isTrue();
        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                "minecraft:potion", "minecraft:water", "minecraft:breeze_rod",
                "minecraft:potion", "minecraft:mundane")).isTrue();
    }

    @Test
    void admitsOnlyTheTwoVanillaContainerConversionsAndMatchingBatchCounts() {
        for (String potion : StandardPotionPolicy.potionIds()) {
            assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                    "minecraft:potion", potion, "minecraft:gunpowder",
                    "minecraft:splash_potion", potion)).isTrue();
            assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                    "minecraft:splash_potion", potion, "minecraft:dragon_breath",
                    "minecraft:lingering_potion", potion)).isTrue();
        }

        var water = new StandardPotionStackSpec(
                "minecraft:potion", "minecraft:water", 3);
        var awkward = new StandardPotionStackSpec(
                "minecraft:potion", "minecraft:awkward", 3);
        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                water, "minecraft:nether_wart", awkward)).isTrue();
        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                water,
                "minecraft:nether_wart",
                new StandardPotionStackSpec(
                        "minecraft:potion", "minecraft:awkward", 2))).isFalse();
        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                "minecraft:splash_potion", "minecraft:water", "minecraft:gunpowder",
                "minecraft:lingering_potion", "minecraft:water")).isFalse();
        assertThat(StandardPotionPolicy.isKnownOneStepRecipe(
                "minecraft:potion", "minecraft:water", "minecraft:nether_wart",
                "minecraft:potion", "minecraft:strength")).isFalse();
    }

    private static ItemStack item(String item) {
        Item value = BuiltInRegistries.ITEM.get(Identifier.parse(item))
                .orElseThrow().value();
        bindEmptyTestComponents(value);
        return new ItemStack(value);
    }

    private static ItemStack standardPotion(String item, String potion) {
        var itemHolder = BuiltInRegistries.ITEM.get(Identifier.parse(item)).orElseThrow();
        var potionHolder = BuiltInRegistries.POTION.get(Identifier.parse(potion)).orElseThrow();
        bindEmptyTestComponents(itemHolder.value());
        return PotionContents.createItemStack(itemHolder.value(), potionHolder);
    }

    /** NeoForge's isolated JUnit loader does not bind Vanilla item defaults. */
    private static void bindEmptyTestComponents(Item item) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
        }
    }
}
