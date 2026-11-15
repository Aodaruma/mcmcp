package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
import dev.aod.mcmcp.agent.safety.ObservedThreats;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Uses the ordinary equip action only after a dry, quiet recovery; never opens storage. */
final class MinecraftEmergencyEquipment implements AutoCloseable {
    private final Minecraft minecraft;
    private final UUID session;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private MinecraftV2ItemUseDriver use;
    private StackFingerprint armorBefore, elytraBefore;
    private long started, beforeRevision;
    private boolean attempted, done;
    private int reservedUses;

    MinecraftEmergencyEquipment(Minecraft minecraft, UUID session) {
        this.minecraft = minecraft; this.session = session;
    }

    /** True holds recovery completion while one finite equipment action is pending. */
    boolean tick(long tick, long revision, boolean quiet, int usesLeft, long ticksLeft, BooleanSupplier allowed) {
        if (done) return false;
        var player = minecraft.player;
        if (!quiet || !allowed.getAsBoolean() || !drySupportedRetreat(revision)) {
            if (attempted) finish("equipment_safety_changed");
            return false;
        }
        var signals = PlayerMenuSyncSignals.global();
        var snapshot = signals.snapshot(minecraft);
        if (!attempted) {
            if (!player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return false;
            if (usesLeft < 2 || ticksLeft < 100 || player.isUsingItem()) return skip("equipment_budget_or_use_busy");
            if (player.containerMenu != player.inventoryMenu || !V2CraftWork.matchesLive(player.inventoryMenu, snapshot)
                    || !snapshot.carried().empty()) return skip("inventory_server_snapshot_required");
            int empty = -1, armor = -1;
            double best = 0;
            for (int slot = 0; slot < 36; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() && empty < 0) empty = slot;
                double protection = chestProtection(stack);
                if (protection > best) { best = protection; armor = slot; }
            }
            if (empty < 0 || armor < 0) return skip(empty < 0 ? "equipment_spare_slot_required" : "chest_armor_not_prepared");
            armorBefore = StackFingerprint.fromServerPacket(player.getInventory().getItem(armor));
            elytraBefore = StackFingerprint.fromServerPacket(player.getItemBySlot(EquipmentSlot.CHEST));
            beforeRevision = snapshot.packetLedgerRevision();
            started = tick; attempted = true; reservedUses = 2;
            result.put("armor", armorBefore.itemId()); result.put("spare_slot", empty);
            result.put("status", "pending"); result.remove("reason");
            // Equippable swaps the chest and held stack atomically, without a carried cursor stack.
            use = new MinecraftV2ItemUseDriver(minecraft, session,
                    new V2ItemUseArguments(armorBefore.itemId(), elytraBefore.itemId(), 1, 90),
                    stack -> armorBefore.equals(StackFingerprint.fromServerPacket(stack)));
            use.begin(tick, allowed);
        }
        if (tick - started >= 90) { finish("equipment_confirmation_timeout"); return false; }
        var step = use.tick(tick, allowed);
        result.put("use", use.result());
        if (step == V2OperationJobExecution.Step.FAILED) { finish("equipment_use_not_confirmed"); return false; }
        snapshot = signals.snapshot(minecraft);
        if (step == V2OperationJobExecution.Step.CONFIRMED
                && V2CraftWork.matchesLive(player.inventoryMenu, snapshot) && snapshot.carried().empty()
                && snapshot.packetLedgerRevision() > beforeRevision) {
            int returned = ((Number) use.result().get("slot")).intValue();
            var chest = snapshot.slots().get(menuSlot(38));
            var held = snapshot.slots().get(menuSlot(returned));
            if (confirmedExchange(armorBefore, elytraBefore, chest, held)) {
                result.put("elytra_slot", returned); result.put("status", "confirmed");
                close(); done = true; return false;
            }
        }
        return true;
    }

    private boolean drySupportedRetreat(long revision) {
        var player = minecraft.player;
        if (player == null || !player.onGround() || player.isFallFlying() || player.isInWater()
                || player.isInLava() || player.isPassenger() || player.isOnFire() || player.isInWall()
                || player.isDeadOrDying() || player.getDeltaMovement().horizontalDistance() > .005
                || ObservedThreats.recentAttack(player)) return false;
        var volume = LocalObservationVolume.global();
        var snapshot = volume.latestFor(player).orElse(null);
        if (snapshot == null || snapshot.worldRevision() != revision || !dry(snapshot.current())) return false;
        return volume.recoveryOptions(player, revision).stream().anyMatch(option ->
                option.path().canExpand() && option.path().fluid() == ObservationRecord.Fluid.NONE
                        && RecoveryPlanning.recoveryDryStable(option.endpoint()));
    }

    static boolean dry(ObservationRecord record) {
        return record.loaded() == ObservationRecord.LoadedState.LOADED
                && record.support() == ObservationRecord.Support.PRESENT
                && record.clearance() == ObservationRecord.Clearance.CLEAR
                && record.fluid() == ObservationRecord.Fluid.NONE && !record.suffocation()
                && record.hazard() == ObservationRecord.Hazard.NONE;
    }

    static double chestProtection(ItemStack stack) {
        var equip = stack.get(DataComponents.EQUIPPABLE);
        if (stack.isEmpty() || stack.getCount() != 1 || stack.getItem().getClass() != Item.class
                || equip == null || equip.slot() != EquipmentSlot.CHEST || !equip.swappable()
                || stack.has(DataComponents.GLIDER) || stack.has(DataComponents.CONSUMABLE)
                || stack.has(DataComponents.BLOCKS_ATTACKS)
                || stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 10) return 0;
        double armor = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ARMOR, 0, EquipmentSlot.CHEST);
        return Double.isFinite(armor) && armor > 0 ? armor : 0;
    }

    static boolean confirmedExchange(StackFingerprint armor, StackFingerprint elytra,
                                     StackFingerprint chest, StackFingerprint returned) {
        return !armor.empty() && !elytra.empty() && armor.equals(chest) && elytra.equals(returned);
    }

    private int menuSlot(int inventorySlot) {
        var player = minecraft.player;
        for (int i = 0; i < player.inventoryMenu.slots.size(); i++) {
            var slot = player.inventoryMenu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) return i;
        }
        throw new IllegalStateException("equipment slot unavailable");
    }
    private boolean skip(String reason) { result.put("status", "skipped"); result.put("reason", reason); return false; }
    private void finish(String reason) { result.put("status", "unconfirmed"); result.put("reason", reason); close(); done = true; }
    int reservedUses() { return reservedUses; }
    boolean pending() { return attempted && !done && use != null; }
    Map<String, Object> result() { return Map.copyOf(result); }
    @Override public void close() {
        if (use != null) {
            use.close(); result.put("use", use.result()); use = null;
            if ("pending".equals(result.get("status"))) result.put("status", "interrupted");
        }
    }
}
