package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.FrameDisplaySyncSignals;
import dev.aod.mcmcp.runtime.HotbarPayloadSyncSignals;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Required inbound packet evidence for finite actions which do not have a block ACK. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "clearLevel", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$closeReconciliationLevel(CallbackInfo callback) {
        ClientReconciliationSignals.global().closeLevel(Minecraft.getInstance().level);
        FrameDisplaySyncSignals.global().closeLevel(this.level);
        HotbarPayloadSyncSignals.global().closeLevel(this.level);
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$frameDisplayPacket(
            ClientboundSetEntityDataPacket packet, CallbackInfo callback) {
        if (this.level == Minecraft.getInstance().level) {
            FrameDisplaySyncSignals.global().onEntityData(
                    this.level, packet, ScreenOwnershipSignals.global().currentTick());
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$removedFrameEntities(
            ClientboundRemoveEntitiesPacket packet, CallbackInfo callback) {
        if (this.level != Minecraft.getInstance().level) return;
        for (int entityId : packet.getEntityIds()) {
            FrameDisplaySyncSignals.global().remove(this.level, entityId);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$levelChunkLoaded(
            ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
        recordChunkMutation(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$levelChunkUnloaded(
            ClientboundForgetLevelChunkPacket packet, CallbackInfo callback) {
        recordChunkMutation(packet.pos().x(), packet.pos().z());
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$positionCorrection(
            ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            AgentInputState.global().discardTrackedAgentVelocity();
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
            AgentInputState.global().discardTrackedAgentVelocity();
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
        if (this.level == minecraft.level) {
            HotbarPayloadSyncSignals.global().onSlot(this.level, packet.slot(),
                    StackFingerprint.fromServerPacket(packet.contents()),
                    ScreenOwnershipSignals.global().currentTick());
        }
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
            if (this.level == minecraft.level && slot.container == minecraft.player.getInventory()) {
                HotbarPayloadSyncSignals.global().onSlot(this.level, slot.getContainerSlot(),
                        StackFingerprint.fromServerPacket(packet.getItem()),
                        ScreenOwnershipSignals.global().currentTick());
            }
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
        if (relevant && this.level == minecraft.level
                && packet.items().size() == minecraft.player.inventoryMenu.slots.size()) {
            for (int index = 0; index < packet.items().size(); index++) {
                Slot slot = minecraft.player.inventoryMenu.slots.get(index);
                if (slot.container == minecraft.player.getInventory()) {
                    HotbarPayloadSyncSignals.global().onSlot(this.level, slot.getContainerSlot(),
                            StackFingerprint.fromServerPacket(packet.items().get(index)),
                            ScreenOwnershipSignals.global().currentTick());
                }
            }
        }
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

    private static void recordChunkMutation(int chunkX, int chunkZ) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            ClientReconciliationSignals.global().onChunkMutation(level, chunkX, chunkZ);
        }
    }
}
