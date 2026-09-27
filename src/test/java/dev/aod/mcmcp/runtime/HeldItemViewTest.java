package dev.aod.mcmcp.runtime;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeldItemViewTest {
    @BeforeAll
    static void bindItems() {
        for (var item : List.of(Items.DIAMOND_SHOVEL, Items.STONE)) {
            if (!item.builtInRegistryHolder().areComponentsBound()) {
                item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                        .set(DataComponents.MAX_STACK_SIZE, 64).build());
            }
        }
    }

    @Test
    void capturesTheSelectedStackAndItsDurabilityEnchantmentsAndTooltipAttributeEffect() {
        var shovel = shovel();
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(enchantment("minecraft:efficiency", true), 5);
        mutable.set(enchantment("minecraft:unbreaking", false), 3);
        mutable.set(enchantment("minecraft:mending", false), 1);
        shovel.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        var captured = capture(4, shovel, new ItemStack(Items.STONE, 7));
        var main = main(captured);
        assertThat(captured.get("selected_hotbar_slot")).isEqualTo(4);
        assertThat(main.get("item")).isEqualTo("minecraft:diamond_shovel");
        assertThat(main.get("display_name")).isEqualTo("aod shovel");
        assertThat(main.get("durability")).isEqualTo(Map.of("damage", 194, "maximum", 1561, "remaining", 1367));
        assertThat(main.get("enchantments")).isEqualTo(List.of(
                Map.of("enchantment", "minecraft:efficiency", "level", 5),
                Map.of("enchantment", "minecraft:mending", "level", 1),
                Map.of("enchantment", "minecraft:unbreaking", "level", 3)));
        assertThat(main.get("attribute_modifiers")).isEqualTo(List.of(Map.of(
                "attribute", "minecraft:mining_efficiency", "amount", 26.0, "operation", "add_value")));
        assertThat(main.get("truncated")).isEqualTo(false);
        var off = (Map<?, ?>) captured.get("off_hand");
        assertThat(off.get("count")).isEqualTo(7);
        assertThat(off.get("durability")).isNull();
        shovel.set(DataComponents.DAMAGE, 200);
        assertThat(main.get("durability")).isEqualTo(Map.of("damage", 194, "maximum", 1561, "remaining", 1367));
        assertThatThrownBy(() -> main.put("count", 99)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void obeysWholeTooltipAndPerComponentHidingWithoutConfusingUnknownWithEmpty() {
        var shovel = shovel();
        shovel.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
                .withHidden(DataComponents.DAMAGE, true)
                .withHidden(DataComponents.ENCHANTMENTS, true)
                .withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
        var main = main(capture(0, shovel, ItemStack.EMPTY));
        assertThat(main.get("durability")).isNull();
        assertThat(main.get("enchantments")).isNull();
        assertThat(main.get("attribute_modifiers")).isNull();
        assertThat(main.get("display_name")).isEqualTo("aod shovel");
        shovel.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
        main = main(capture(0, shovel, ItemStack.EMPTY));
        assertThat(main.get("tooltip_hidden")).isEqualTo(true);
        assertThat(main.get("display_name")).isNull();
        assertThat(main.get("durability")).isNull();
        assertThat(main.get("unbreakable")).isNull();
        assertThat(main.get("enchantments")).isNull();
        assertThat(main.get("attribute_modifiers")).isNull();
    }

    @Test
    void filtersHiddenAndReplacementTextModifiersAndTheOtherHand() {
        var shovel = shovel();
        shovel.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(List.of(
                modifier("visible", 7, EquipmentSlotGroup.MAINHAND, ItemAttributeModifiers.Display.attributeModifiers()),
                modifier("hidden", 99, EquipmentSlotGroup.MAINHAND, ItemAttributeModifiers.Display.hidden()),
                modifier("override", 98, EquipmentSlotGroup.MAINHAND, ItemAttributeModifiers.Display.override(Component.literal("label"))),
                modifier("offhand", 8, EquipmentSlotGroup.OFFHAND, ItemAttributeModifiers.Display.attributeModifiers()))));
        var captured = capture(0, shovel, shovel);
        assertThat(main(captured).get("attribute_modifiers")).isEqualTo(List.of(Map.of(
                "attribute", "minecraft:mining_efficiency", "amount", 7.0, "operation", "add_value")));
        assertThat(((Map<?, ?>) captured.get("off_hand")).get("attribute_modifiers")).isEqualTo(List.of(Map.of(
                "attribute", "minecraft:mining_efficiency", "amount", 8.0, "operation", "add_value")));
    }

    @Test
    void limitsTextAndListsAndNeverSerializesCustomNbt() {
        var shovel = shovel();
        var custom = new CompoundTag();
        custom.putString("private_key", "must-not-leave-nbt");
        shovel.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        shovel.set(DataComponents.CUSTOM_NAME, Component.literal("visible\n" + "x".repeat(400)));
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (int i = 0; i < 65; i++) mutable.set(enchantment("test:enchantment_" + i, false), 1);
        shovel.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        var result = main(capture(0, shovel, ItemStack.EMPTY));
        assertThat(result.get("display_name")).isNull();
        assertThat((List<?>) result.get("enchantments")).hasSize(64);
        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(result.toString()).doesNotContain("private_key", "must-not-leave-nbt", "CUSTOM_DATA");
    }

    @Test
    void representsEmptyHandsAndUnbreakableItemsWithoutInventingDurability() {
        var empty = capture(8, ItemStack.EMPTY, ItemStack.EMPTY);
        assertThat(empty.get("main_hand")).isNull();
        assertThat(empty.get("off_hand")).isNull();
        var shovel = shovel();
        shovel.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        var result = main(capture(0, shovel, ItemStack.EMPTY));
        assertThat(result.get("unbreakable")).isEqualTo(true);
        assertThat(result.get("durability")).isNull();
        assertThat(result.get("enchantments")).isEqualTo(List.of());
        assertThatThrownBy(() -> capture(9, shovel, ItemStack.EMPTY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalTooltipRemovalReplacementAndFailureCannotRevealHiddenMetadata() {
        var shovel = shovel();
        var enchants = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchants.set(enchantment("minecraft:efficiency", true), 5);
        shovel.set(DataComponents.ENCHANTMENTS, enchants.toImmutable());
        // Simulates the final list after ItemTooltipEvent removes or replaces all standard lines.
        var hidden = main(HeldItemView.capture(0, shovel, ItemStack.EMPTY,
                ignored -> List.of(Component.literal("replacement tooltip"))));
        assertThat(hidden.get("display_name")).isNull();
        assertThat(hidden.get("durability")).isNull();
        assertThat(hidden.get("enchantments")).isNull();
        assertThat(hidden.get("attribute_modifiers")).isEqualTo(List.of());
        var failed = main(HeldItemView.capture(0, shovel, ItemStack.EMPTY,
                ignored -> { throw new IllegalStateException("private tooltip failure"); }));
        assertThat(failed.get("truncated")).isEqualTo(true);
        assertThat(failed.toString()).doesNotContain("private tooltip failure");
        assertThat(failed.get("durability")).isNull();
    }

    @Test
    void identicalOtherHandLineDoesNotAuthorizeARemovedMainHandModifier() {
        var shovel = shovel();
        shovel.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(List.of(
                modifier("main", 7, EquipmentSlotGroup.MAINHAND, ItemAttributeModifiers.Display.attributeModifiers()),
                modifier("off", 7, EquipmentSlotGroup.OFFHAND, ItemAttributeModifiers.Display.attributeModifiers()))));
        var lines = new java.util.ArrayList<Component>();
        lines.add(Component.translatable("item.modifiers.offhand"));
        shovel.forEachModifier(EquipmentSlotGroup.OFFHAND,
                (attribute, modifier, presentation) -> presentation.apply(lines::add, null, attribute, modifier));
        var result = main(HeldItemView.capture(0, shovel, ItemStack.EMPTY, ignored -> lines));
        assertThat(result.get("attribute_modifiers")).isEqualTo(List.of());
    }

    @Test
    void oversizedItemIdsAndRoundedAwayModifierPrecisionAreOmitted() {
        assertThat(HeldItemView.publicItemId(Identifier.parse("test:" + "a".repeat(251)))).hasSize(256);
        assertThat(HeldItemView.publicItemId(Identifier.parse("test:" + "a".repeat(252)))).isNull();
        var shovel = shovel();
        shovel.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(List.of(
                modifier("rounded", 26.000000001, EquipmentSlotGroup.MAINHAND,
                        ItemAttributeModifiers.Display.attributeModifiers()))));
        assertThat(main(capture(0, shovel, ItemStack.EMPTY)).get("attribute_modifiers")).isEqualTo(List.of());
    }

    @Test
    void pristineDurabilityAndPlayerAdjustedBaseAttackAreNotExposedAsVisibleRawNumbers() {
        var shovel = shovel();
        shovel.set(DataComponents.DAMAGE, 0);
        shovel.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(List.of(
                new ItemAttributeModifiers.Entry(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID, 5,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND))));
        var result = main(capture(0, shovel, ItemStack.EMPTY));
        assertThat(result.get("durability")).isNull();
        assertThat(result.get("attribute_modifiers")).isEqualTo(List.of());
    }

    private static Map<String, Object> capture(int selectedSlot, ItemStack main, ItemStack off) {
        return HeldItemView.capture(selectedSlot, main, off, HeldItemViewTest::visibleTooltip);
    }

    private static List<Component> visibleTooltip(ItemStack stack) {
        var lines = new java.util.ArrayList<Component>();
        lines.add(stack.getHoverName());
        if (stack.isDamageableItem() && stack.isDamaged()) lines.add(Component.translatable(
                "item.durability", stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
        if (stack.has(DataComponents.UNBREAKABLE)) lines.add(Component.translatable("item.unbreakable"));
        for (var entry : stack.getEnchantments().entrySet()) {
            lines.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()));
        }
        for (var group : EquipmentSlotGroup.values()) {
            lines.add(Component.empty());
            lines.add(Component.translatable("item.modifiers." + group.getSerializedName()));
            stack.forEachModifier(group,
                    (attribute, modifier, presentation) -> presentation.apply(lines::add, null, attribute, modifier));
        }
        return lines;
    }

    private static ItemStack shovel() {
        var stack = new ItemStack(Items.DIAMOND_SHOVEL);
        stack.set(DataComponents.MAX_DAMAGE, 1561);
        stack.set(DataComponents.DAMAGE, 194);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("aod shovel"));
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> main(Map<String, Object> captured) {
        return (Map<String, Object>) captured.get("main_hand");
    }

    private static ItemAttributeModifiers.Entry modifier(String id, double amount, EquipmentSlotGroup slot,
            ItemAttributeModifiers.Display display) {
        return new ItemAttributeModifiers.Entry(Attributes.MINING_EFFICIENCY,
                new AttributeModifier(Identifier.parse("test:" + id), amount, AttributeModifier.Operation.ADD_VALUE), slot, display);
    }

    private static Holder<Enchantment> enchantment(String id, boolean efficiency) {
        var effects = DataComponentMap.builder();
        if (efficiency) effects.set(EnchantmentEffectComponents.ATTRIBUTES, List.of(new EnchantmentAttributeEffect(
                Identifier.parse("test:efficiency"), Attributes.MINING_EFFICIENCY,
                LevelBasedValue.constant(26), AttributeModifier.Operation.ADD_VALUE)));
        var definition = Enchantment.definition(HolderSet.direct(Items.DIAMOND_SHOVEL.builtInRegistryHolder()),
                1, 5, Enchantment.constantCost(1), Enchantment.constantCost(10), 1, EquipmentSlotGroup.MAINHAND);
        var value = new Enchantment(Component.literal(id), definition, HolderSet.empty(), effects.build());
        return new BoundReference<>(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(id)), value);
    }

    private static final class BoundReference<T> extends Holder.Reference<T> {
        private BoundReference(ResourceKey<T> key, T value) {
            super(Type.STAND_ALONE, new HolderOwner<>() {}, key, value);
        }

        @Override
        public boolean is(net.minecraft.tags.TagKey<T> tag) { return false; }
    }
}
