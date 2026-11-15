package dev.aod.mcmcp.runtime;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MinecraftEmergencyEquipmentTest {
    @Test void unknownWaterAndUnsupportedGroundNeverAuthorizeEquipment() {
        var p = new dev.aod.mcmcp.agent.safety.ObservationRecord.Point(0, 64, 0);
        for (var fluid : dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.values()) {
            for (var support : dev.aod.mcmcp.agent.safety.ObservationRecord.Support.values()) {
                var record = new dev.aod.mcmcp.agent.safety.ObservationRecord(1, 1, 0, p, p, p,
                        support, dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance.CLEAR,
                        dev.aod.mcmcp.agent.safety.ObservationRecord.Transition.STATIONARY, fluid, false,
                        dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard.NONE,
                        dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState.LOADED,
                        dev.aod.mcmcp.agent.safety.ObservationRecord.Drop.SUPPORTED, false);
                assertThat(MinecraftEmergencyEquipment.dry(record)).isEqualTo(
                        fluid == dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid.NONE
                                && support == dev.aod.mcmcp.agent.safety.ObservationRecord.Support.PRESENT);
            }
        }
    }
    @Test void onlyUsableChestArmorCanReplaceTheElytra() {
        assertThat(MinecraftEmergencyEquipment.chestProtection(stack(Items.DIAMOND_CHESTPLATE))).isPositive();
        assertThat(MinecraftEmergencyEquipment.chestProtection(stack(Items.IRON_CHESTPLATE))).isPositive();
        for (var item : java.util.List.of(Items.ELYTRA, Items.DIAMOND_HELMET, Items.SHIELD, Items.BREAD))
            assertThat(MinecraftEmergencyEquipment.chestProtection(stack(item))).isZero();
        var damaged = stack(Items.DIAMOND_CHESTPLATE);
        damaged.setDamageValue(damaged.getMaxDamage() - 10);
        assertThat(MinecraftEmergencyEquipment.chestProtection(damaged)).isZero();
    }

    @Test void bothServerSlotsMustRetainTheirExactComponents() {
        var armor = stack(Items.DIAMOND_CHESTPLATE);
        armor.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("prepared armor"));
        var elytra = stack(Items.ELYTRA); elytra.setDamageValue(21);
        var a = ContainerSyncSignals.StackFingerprint.fromServerPacket(armor);
        var e = ContainerSyncSignals.StackFingerprint.fromServerPacket(elytra);
        assertThat(MinecraftEmergencyEquipment.confirmedExchange(a, e, a, e)).isTrue();
        assertThat(MinecraftEmergencyEquipment.confirmedExchange(a, e, e, a)).isFalse();
        assertThat(MinecraftEmergencyEquipment.confirmedExchange(a, e,
                ContainerSyncSignals.StackFingerprint.fromServerPacket(stack(Items.DIAMOND_CHESTPLATE)), e)).isFalse();
        assertThat(MinecraftEmergencyEquipment.confirmedExchange(a, e, a,
                ContainerSyncSignals.StackFingerprint.fromServerPacket(stack(Items.ELYTRA)))).isFalse();
    }

    private static ItemStack stack(net.minecraft.world.item.Item item) {
        // Isolated JUnit has no Vanilla prototypes; keep these local rather than mutating shared registries.
        var components = net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 1).set(DataComponents.MAX_DAMAGE, 500).set(DataComponents.DAMAGE, 0);
        if (item == Items.DIAMOND_CHESTPLATE || item == Items.IRON_CHESTPLATE) {
            components.set(DataComponents.EQUIPPABLE, net.minecraft.world.item.equipment.Equippable.builder(
                    net.minecraft.world.entity.EquipmentSlot.CHEST).build());
            components.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY
                    .withModifierAdded(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                            new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                    net.minecraft.resources.Identifier.withDefaultNamespace("armor"), 8,
                                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                            net.minecraft.world.entity.EquipmentSlotGroup.CHEST));
        }
        return new ItemStack(net.minecraft.core.Holder.direct(item, components.build()));
    }
}
