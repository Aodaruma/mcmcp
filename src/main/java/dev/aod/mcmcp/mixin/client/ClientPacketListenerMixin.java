package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Required inbound packet evidence for finite actions which do not have a block ACK. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "clearLevel", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$closeReconciliationLevel(CallbackInfo callback) {
        ClientReconciliationSignals.global().closeLevel(Minecraft.getInstance().level);
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$positionCorrection(
            ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            ClientReconciliationSignals.global().onPositionCorrection(
                    minecraft.level, packet.id(), minecraft.player.position());
        }
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$serverRotation(
            ClientboundPlayerRotationPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            ClientReconciliationSignals.global().onServerRotation(
                    minecraft.level, minecraft.player.getYRot(), minecraft.player.getXRot());
        }
    }

    @Inject(method = "handleSetEntityMotion", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$localPlayerMotion(
            ClientboundSetEntityMotionPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null
                && packet.id() == minecraft.player.getId()) {
            ClientReconciliationSignals.global().onLocalPlayerMotion(
                    minecraft.level, packet.movement(), "set_entity_motion");
        }
    }

    @Inject(method = "handleExplosion", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$explosionMotion(ClientboundExplodePacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            packet.playerKnockback().ifPresent(movement ->
                    ClientReconciliationSignals.global().onLocalPlayerMotion(
                            minecraft.level, movement, "explosion_knockback"));
        }
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$playerInventorySync(
            ClientboundSetPlayerInventoryPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        int selected = minecraft.player.getInventory().getSelectedSlot();
        rememberInventory("player_inventory", packet.slot(), packet.slot() == selected);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$containerSlotSync(
            ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        boolean relevant = false;
        var menu = minecraft.player.containerMenu;
        if (packet.getContainerId() == menu.containerId
                && packet.getSlot() >= 0
                && packet.getSlot() < menu.slots.size()) {
            Slot slot = menu.slots.get(packet.getSlot());
            relevant = slot.container == minecraft.player.getInventory()
                    && slot.getContainerSlot() == minecraft.player.getInventory().getSelectedSlot();
        }
        rememberInventory("container_slot", packet.getSlot(), relevant);
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$containerContentSync(
            ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        boolean relevant = minecraft.player.containerMenu == minecraft.player.inventoryMenu
                && packet.containerId() == minecraft.player.inventoryMenu.containerId;
        rememberInventory("container_content", -1, relevant);
    }

    private static void rememberInventory(String kind, int packetSlot, boolean relevant) {
        var minecraft = Minecraft.getInstance();
        ItemStack selected = minecraft.player.getInventory().getSelectedItem();
        String itemId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
        ClientReconciliationSignals.global().onInventorySync(
                minecraft.level, kind, packetSlot, relevant, itemId,
                selected.isEmpty() ? 0 : selected.getCount());
    }
}
