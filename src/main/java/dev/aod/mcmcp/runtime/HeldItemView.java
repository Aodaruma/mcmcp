package dev.aod.mcmcp.runtime;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Immutable, whitelisted facts from the player's two held stacks; never serializes components/NBT. */
final class HeldItemView {
    static final int MAX_TEXT_LENGTH = 256;
    static final int MAX_ENTRIES = 64;

    private HeldItemView() {}

    static Map<String, Object> capture(Player player) {
        return capture(player.getInventory().getSelectedSlot(), player.getMainHandItem(), player.getOffhandItem(),
                stack -> stack.getTooltipLines(Item.TooltipContext.of(player.level(), player), player, TooltipFlag.ADVANCED));
    }

    static Map<String, Object> capture(int selectedSlot, ItemStack mainHand, ItemStack offHand,
            Function<ItemStack, List<Component>> finalTooltip) {
        if (selectedSlot < 0 || selectedSlot > 8) throw new IllegalArgumentException("Invalid hotbar slot");
        var result = new LinkedHashMap<String, Object>();
        result.put("selected_hotbar_slot", selectedSlot);
        result.put("main_hand", item(Objects.requireNonNull(mainHand), EquipmentSlot.MAINHAND, finalTooltip));
        result.put("off_hand", item(Objects.requireNonNull(offHand), EquipmentSlot.OFFHAND, finalTooltip));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> item(ItemStack stack, EquipmentSlot slot,
            Function<ItemStack, List<Component>> finalTooltip) {
        if (stack.isEmpty()) return null;
        var result = new LinkedHashMap<String, Object>();
        var display = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        String itemId = publicItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        result.put("item", itemId);
        result.put("count", stack.getCount());
        result.put("tooltip_hidden", display.hideTooltip());
        result.put("display_name", null);
        result.put("durability", null);
        result.put("unbreakable", null);
        result.put("enchantments", null);
        result.put("attribute_modifiers", null);
        result.put("truncated", itemId == null);
        if (!display.hideTooltip()) {
            // getTooltipLines includes NeoForge's final ItemTooltipEvent. Never expose those arbitrary lines.
            final List<Component> lines;
            try {
                lines = finalTooltip.apply(stack);
            } catch (RuntimeException unavailable) {
                result.put("truncated", true);
                return Collections.unmodifiableMap(result);
            }
            var visible = new java.util.HashSet<String>();
            var attributeSections = new java.util.EnumMap<EquipmentSlotGroup, java.util.Set<String>>(EquipmentSlotGroup.class);
            var headings = new java.util.HashMap<String, EquipmentSlotGroup>();
            for (var group : EquipmentSlotGroup.values()) {
                headings.put(Component.translatable("item.modifiers." + group.getSerializedName()).getString(), group);
            }
            EquipmentSlotGroup section = null;
            for (int i = 0; i < Math.min(lines.size(), 256); i++) {
                String line = lines.get(i).getString(MAX_TEXT_LENGTH + 1);
                if (line.length() <= MAX_TEXT_LENGTH) visible.add(line);
                else result.put("truncated", true);
                if (line.isEmpty()) section = null;
                else if (headings.containsKey(line)) section = headings.get(line);
                else if (section != null && line.length() <= MAX_TEXT_LENGTH) {
                    attributeSections.computeIfAbsent(section, ignored -> new java.util.HashSet<>()).add(line);
                }
            }
            if (lines.size() > 256) result.put("truncated", true);
            // Component.getString is bounded; styles, click/hover events and other component data are not copied.
            String text = stack.getHoverName().getString(MAX_TEXT_LENGTH + 1);
            if (text.length() > MAX_TEXT_LENGTH) {
                result.put("truncated", true);
            }
            var name = new StringBuilder();
            text.codePoints().filter(code -> !Character.isISOControl(code)
                            && !(code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE))
                    .forEach(name::appendCodePoint);
            if (text.length() <= MAX_TEXT_LENGTH && visible.contains(text)) result.put("display_name", name.toString());
            if (display.shows(DataComponents.DAMAGE) && stack.isDamageableItem()) {
                int maximum = stack.getMaxDamage();
                int damage = stack.getDamageValue();
                if (maximum > 0 && damage > 0 && damage <= maximum
                        && visible.contains(Component.translatable("item.durability", maximum - damage, maximum).getString())) {
                    result.put("durability", Map.of(
                            "damage", damage, "maximum", maximum, "remaining", maximum - damage));
                }
            }
            if (display.shows(DataComponents.UNBREAKABLE) && stack.has(DataComponents.UNBREAKABLE)
                    && visible.contains(Component.translatable("item.unbreakable").getString())) {
                result.put("unbreakable", true);
            }
            if (display.shows(DataComponents.ENCHANTMENTS)) {
                var enchantments = new ArrayList<Map<String, Object>>();
                for (var entry : stack.getEnchantments().entrySet()) {
                    String label = Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString(MAX_TEXT_LENGTH + 1);
                    if (label.length() > MAX_TEXT_LENGTH || !visible.contains(label)) continue;
                    String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse(null);
                    if (id == null || id.length() > MAX_TEXT_LENGTH || entry.getIntValue() < 1
                            || enchantments.size() == MAX_ENTRIES) {
                        result.put("truncated", true);
                        continue;
                    }
                    enchantments.add(Map.of("enchantment", id, "level", entry.getIntValue()));
                }
                enchantments.sort(Comparator.comparing(entry -> (String) entry.get("enchantment")));
                result.put("enchantments", enchantments.isEmpty() && !stack.getEnchantments().isEmpty()
                        ? null : List.copyOf(enchantments));
            }
            if (display.shows(DataComponents.ATTRIBUTE_MODIFIERS)) {
                var attributes = new ArrayList<Map<String, Object>>();
                // Identical labels across groups cannot prove which original modifier survived an event edit.
                var occurrences = new java.util.HashMap<String, Integer>();
                for (var group : EquipmentSlotGroup.values()) stack.forEachModifier(group, (attribute, modifier, presentation) -> {
                    if (presentation instanceof ItemAttributeModifiers.Display.Default) {
                        presentation.apply(line -> occurrences.merge(line.getString(MAX_TEXT_LENGTH + 1), 1, Integer::sum),
                                null, attribute, modifier);
                    }
                });
                for (var group : EquipmentSlotGroup.values()) {
                    if (!group.test(slot)) continue;
                    // This is Vanilla's tooltip path, including enchantment attribute effects.
                    stack.forEachModifier(group, (attribute, modifier, presentation) -> {
                        // Hidden and replacement-text presentations do not reveal the underlying numeric modifier.
                        if (!(presentation instanceof ItemAttributeModifiers.Display.Default)
                                || modifier.amount() == 0.0D
                                || modifier.is(Item.BASE_ATTACK_DAMAGE_ID) || modifier.is(Item.BASE_ATTACK_SPEED_ID)) return;
                        // Do not reveal extra component precision rounded away by the displayed number.
                        double shownAmount = modifier.amount();
                        if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) shownAmount *= 100.0;
                        else if (attribute.is(Attributes.KNOCKBACK_RESISTANCE)) shownAmount *= 10.0;
                        try {
                            var format = ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT;
                            if (!Double.isFinite(shownAmount)
                                    || format.parse(format.format(shownAmount)).doubleValue() != shownAmount) return;
                        } catch (java.text.ParseException invalidNumber) {
                            return;
                        }
                        var numericLines = new ArrayList<Component>();
                        presentation.apply(numericLines::add, null, attribute, modifier);
                        if (numericLines.isEmpty() || numericLines.stream().anyMatch(line -> {
                            String label = line.getString(MAX_TEXT_LENGTH + 1);
                            return label.length() > MAX_TEXT_LENGTH || occurrences.getOrDefault(label, 0) != 1
                                    || !attributeSections.getOrDefault(group, java.util.Set.of()).contains(label);
                        })) return;
                        String id = attribute.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
                        if (id == null || id.length() > MAX_TEXT_LENGTH || !Double.isFinite(modifier.amount())
                                || attributes.size() == MAX_ENTRIES) {
                            result.put("truncated", true);
                            return;
                        }
                        attributes.add(Map.of(
                                "attribute", id,
                                "amount", modifier.amount(),
                                "operation", modifier.operation().getSerializedName()));
                    });
                }
                attributes.sort(Comparator.comparing((Map<String, Object> entry) -> (String) entry.get("attribute"))
                        .thenComparing(entry -> (String) entry.get("operation"))
                        .thenComparingDouble(entry -> (Double) entry.get("amount")));
                result.put("attribute_modifiers", List.copyOf(attributes));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static String publicItemId(Identifier id) {
        String text = id.toString();
        return text.length() <= MAX_TEXT_LENGTH ? text : null;
    }
}
