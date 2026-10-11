package dev.aod.mcmcp.agent.safety;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.Enchantments;

/** Client-visible Vanilla 26.2 fall attributes and equipped protection, in health points. */
public final class FallDamageEstimate {
    private FallDamageEstimate() { }

    public static double predict(LocalPlayer player, double distance) {
        if (player.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) return 0;
        int protection = 0;
        for (var slot : new EquipmentSlot[] {EquipmentSlot.FEET, EquipmentSlot.LEGS,
                EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            for (var entry : player.getItemBySlot(slot).getEnchantments().entrySet()) {
                if (!entry.getKey().value().matchingSlot(slot)) continue;
                if (entry.getKey().is(Enchantments.PROTECTION)) protection += entry.getIntValue();
                else if (entry.getKey().is(Enchantments.FEATHER_FALLING)) protection += 3 * entry.getIntValue();
            }
        }
        var resistance = player.getEffect(MobEffects.RESISTANCE);
        // Do not budget a temporary reduction which may expire before landing.
        int resistanceLevel = resistance != null && resistance.getDuration() >= 100
                ? resistance.getAmplifier() + 1 : 0;
        return predict(distance, player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), protection, resistanceLevel);
    }

    public static double predict(double distance, double safeDistance, double multiplier,
            int protection, int resistanceLevel) {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(safeDistance)
                || safeDistance < 0 || !Double.isFinite(multiplier) || multiplier < 0
                || protection < 0 || resistanceLevel < 0) return Double.POSITIVE_INFINITY;
        // 26.2 uses floor((distance + 1e-6 - safe distance) * multiplier), not the older ceil rule.
        float raw = (float) Math.max(0, Math.floor((distance + 1.0E-6 - safeDistance) * multiplier));
        float resisted = Math.max(0, raw * Math.max(0, 25 - 5 * Math.min(5, resistanceLevel)) / 25.0F);
        return resisted * (1.0F - Math.min(20, protection) / 25.0F);
    }
}
