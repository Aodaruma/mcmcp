package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/** Required inbound packet ledger for typed automation-owned container screens. */
@Mixin(ClientPacketListener.class)
public abstract class ClientContainerSignalsMixin {
    @Inject(method = "clearLevel", at = @At("HEAD"), require = 1, expect = 1)
    private void mcmcp$clearOwnedContainerLevel(CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        ScreenOwnershipSignals.global().clearLevel(minecraft.level);
    }

    /*
     * HEAD would run once on the network thread before PacketUtils reschedules the packet and then
     * again on the client thread. This exact invoke point is after that handoff and before the
     * screen-opening event, preserving a one-packet/one-ledger-entry transition.
     */
    @Inject(
            method = "handleOpenScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/MenuScreens;create("
                            + "Lnet/minecraft/world/inventory/MenuType;"
                            + "Lnet/minecraft/client/Minecraft;I"
                            + "Lnet/minecraft/network/chat/Component;)V",
                    shift = At.Shift.BEFORE),
            require = 1,
            expect = 1)
    private void mcmcp$expectedContainerOpen(
            ClientboundOpenScreenPacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onOpenScreen(minecraft.level, packet.getContainerId(),
                ScreenOwnershipSignals.menuTypeId(packet.getType()), signals.currentTick());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$fullContainerContent(
            ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        if (!acceptsContainerEvidence()) {
            return;
        }
        var context = currentMenuContext();
        if (context == null) {
            return;
        }
        var slots = packet.items().stream()
                .map(ContainerSyncSignals.StackFingerprint::fromServerPacket)
                .toList();
        var signals = ScreenOwnershipSignals.global();
        signals.onFullContent(context.level(), packet.containerId(), context.menuTypeId(),
                packet.stateId(), slots,
                ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.carriedItem()),
                context.screenMatches(), signals.currentTick());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$incrementalContainerSlot(
            ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        if (!acceptsContainerEvidence()) {
            return;
        }
        var context = currentMenuContext();
        if (context == null) {
            return;
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onSlot(context.level(), packet.getContainerId(), context.menuTypeId(),
                packet.getStateId(), packet.getSlot(),
                ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.getItem()),
                context.screenMatches(), signals.currentTick());
    }

    @Inject(method = "handleContainerSetData", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$serverContainerData(
            ClientboundContainerSetDataPacket packet, CallbackInfo callback) {
        if (!acceptsContainerEvidence()) {
            return;
        }
        var context = currentMenuContext();
        if (context == null) {
            return;
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onData(context.level(), packet.getContainerId(), context.menuTypeId(),
                packet.getId(), packet.getValue(), context.screenMatches(),
                signals.currentTick());
    }

    @Inject(method = "handleSetCursorItem", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$serverCursorItem(
            ClientboundSetCursorItemPacket packet, CallbackInfo callback) {
        if (!acceptsContainerEvidence()) {
            return;
        }
        var context = currentMenuContext();
        if (context == null) {
            return;
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onCarried(context.level(), context.menu().containerId, context.menuTypeId(),
                ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.contents()),
                context.screenMatches(), signals.currentTick());
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"), require = 1, expect = 1)
    private void mcmcp$serverPlayerInventorySlot(
            ClientboundSetPlayerInventoryPacket packet, CallbackInfo callback) {
        if (!acceptsContainerEvidence()) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        var context = currentMenuContext();
        if (context == null || minecraft.player == null) {
            return;
        }
        var matchingMenuSlots = new ArrayList<Integer>();
        for (int index = 0; index < context.menu().slots.size(); index++) {
            var slot = context.menu().slots.get(index);
            if (slot.container == minecraft.player.getInventory()
                    && slot.getContainerSlot() == packet.slot()) {
                matchingMenuSlots.add(index);
            }
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onPlayerInventorySlot(context.level(), context.menu().containerId,
                context.menuTypeId(), matchingMenuSlots,
                ContainerSyncSignals.StackFingerprint.fromServerPacket(packet.contents()),
                context.screenMatches(), signals.currentTick());
    }

    @Inject(
            method = "handleContainerClose",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;"
                            + "clientSideCloseContainer()V",
                    shift = At.Shift.BEFORE),
            require = 1,
            expect = 1)
    private void mcmcp$serverContainerClose(
            ClientboundContainerClosePacket packet, CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var signals = ScreenOwnershipSignals.global();
        signals.onContainerClose(
                minecraft.level, packet.getContainerId(), signals.currentTick());
    }

    private static MenuContext currentMenuContext() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        String menuTypeId = ScreenOwnershipSignals.menuTypeId(menu.getType());
        boolean screenMatches = minecraft.gui.screen()
                instanceof AbstractContainerScreen<?> containerScreen
                && containerScreen.getMenu() == menu
                && containerScreen.getMenu().containerId == menu.containerId
                && ScreenOwnershipSignals.menuTypeId(containerScreen.getMenu().getType())
                        .equals(menuTypeId);
        return new MenuContext(minecraft.level, menu, menuTypeId, screenMatches);
    }

    private static boolean acceptsContainerEvidence() {
        var signals = ScreenOwnershipSignals.global();
        return ScreenOwnershipSignals.acceptsContainerEvidence(signals.snapshot().phase());
    }

    private record MenuContext(
            net.minecraft.client.multiplayer.ClientLevel level,
            AbstractContainerMenu menu,
            String menuTypeId,
            boolean screenMatches) {
    }
}
