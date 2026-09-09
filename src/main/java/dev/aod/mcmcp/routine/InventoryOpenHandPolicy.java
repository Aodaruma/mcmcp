package dev.aod.mcmcp.routine;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 既知Vanilla containerをMAIN_HANDで開くためのhotbar選択とNeoForge hook検証。 */
final class InventoryOpenHandPolicy {
    private InventoryOpenHandPolicy() {}

    static Optional<OpenHandPlan> chooseOpenHand(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        var hotbar = new ArrayList<ItemStack>(9);
        for (int slot = 0; slot < 9; slot++) {
            hotbar.add(player.getInventory().getItem(slot));
        }
        return chooseOpenHand(
                hotbar, player.getOffhandItem(), player.getInventory().getSelectedSlot());
    }

    static Optional<OpenHandPlan> chooseOpenHand(
            List<ItemStack> hotbar, ItemStack offhand, int selectedSlot) {
        Objects.requireNonNull(offhand, "offhand");
        return chooseOpenHand(hotbar, safeEmptyMainHandOffhand(offhand), selectedSlot);
    }

    static Optional<OpenHandPlan> chooseOpenHand(
            List<ItemStack> hotbar, boolean emptyMainHandAllowed, int selectedSlot) {
        Objects.requireNonNull(hotbar, "hotbar");
        if (hotbar.size() != 9 || selectedSlot < 0 || selectedSlot > 8) {
            throw new IllegalArgumentException("invalid hotbar selection context");
        }
        // NeoForge checks sneak-use bypass and invokes onItemUseFirst before the block. A nonempty
        // MAIN_HAND with default hooks short-circuits the offhand bypass hook. Empty MAIN_HAND
        // reports bypass=true, so its offhand bypass hook must also be proven default.
        for (int slot = 0; slot < 9; slot++) {
            if (!hotbar.get(slot).isEmpty() && safeKnownMenuOpenStack(hotbar.get(slot))) {
                return Optional.of(new OpenHandPlan(slot));
            }
        }
        if (emptyMainHandAllowed) {
            for (int slot = 0; slot < 9; slot++) {
                if (hotbar.get(slot).isEmpty()) {
                    return Optional.of(new OpenHandPlan(slot));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Safety proof for an exact known crafting table, chest or barrel interaction. NeoForge calls
     * sneak-use bypass and {@code onItemUseFirst} before the block. Empty MAIN_HAND also evaluates
     * the offhand sneak-use bypass hook, so that hook must remain default too. The supported
     * Vanilla block then consumes {@code useWithoutItem} before {@code ItemStack.useOn}.
     */
    static boolean safeKnownMenuOpenStack(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return !stack.isEmpty() && usesDefaultNeoForgeOpenHooks(stack.getItem().getClass());
    }

    static boolean safeKnownMenuOpenContext(ItemStack mainHand, ItemStack offhand) {
        Objects.requireNonNull(mainHand, "mainHand");
        Objects.requireNonNull(offhand, "offhand");
        return mainHand.isEmpty()
                ? safeEmptyMainHandOffhand(offhand) : safeKnownMenuOpenStack(mainHand);
    }

    static boolean safeEmptyMainHandOffhand(ItemStack offhand) {
        Objects.requireNonNull(offhand, "offhand");
        return safeEmptyMainHandOffhand(
                offhand.isEmpty(), offhand.getItem().getClass());
    }

    static boolean safeEmptyMainHandOffhand(boolean empty, Class<?> itemType) {
        Objects.requireNonNull(itemType, "itemType");
        return empty || usesDefaultNeoForgeSneakBypass(itemType);
    }

    static boolean inboundTransferKeepsOpenHandSafe(
            boolean playerToContainer, boolean mainHandEmpty, Class<?> transferredItemType) {
        Objects.requireNonNull(transferredItemType, "transferredItemType");
        return playerToContainer || !mainHandEmpty || usesDefaultNeoForgeOpenHooks(transferredItemType);
    }

    static boolean usesDefaultNeoForgeOpenHooks(Class<?> itemType) {
        return usesDefaultNeoForgeFirstUse(itemType) && usesDefaultNeoForgeSneakBypass(itemType);
    }

    static boolean usesDefaultNeoForgeFirstUse(Class<?> itemType) {
        Objects.requireNonNull(itemType, "itemType");
        try {
            return itemType
                    .getMethod("onItemUseFirst", ItemStack.class, UseOnContext.class)
                    .getDeclaringClass() == IItemExtension.class;
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            return false;
        }
    }

    static boolean usesDefaultNeoForgeSneakBypass(Class<?> itemType) {
        Objects.requireNonNull(itemType, "itemType");
        try {
            return itemType
                    .getMethod("doesSneakBypassUse", ItemStack.class, LevelReader.class,
                            BlockPos.class, Player.class)
                    .getDeclaringClass() == IItemExtension.class;
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            return false;
        }
    }

    record OpenHandPlan(int selectedSlot) {
        OpenHandPlan {
            if (selectedSlot < 0 || selectedSlot > 8) {
                throw new IllegalArgumentException("open-hand slot is outside the hotbar");
            }
        }

        boolean ready(LocalPlayer player) {
            if (player.getInventory().getSelectedSlot() != selectedSlot) return false;
            return safeKnownMenuOpenContext(player.getMainHandItem(), player.getOffhandItem());
        }

        boolean readyAtSlot(LocalPlayer player) {
            return safeKnownMenuOpenContext(
                    player.getInventory().getItem(selectedSlot), player.getOffhandItem());
        }
    }
}
