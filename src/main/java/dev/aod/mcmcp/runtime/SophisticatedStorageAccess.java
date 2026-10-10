package dev.aod.mcmcp.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Compatibility differences for one verified build; never reads a closed bag's inventory. */
final class SophisticatedStorageAccess implements StorageAccess {
    private static final String ROOT = "net.p3pp3rf1y.sophisticatedbackpacks.";

    @Override
    public Optional<Target> atBlock(Minecraft minecraft, net.minecraft.core.BlockPos position,
            net.minecraft.world.phys.Vec3 aim, java.util.function.BooleanSupplier observable) {
        if (minecraft.level == null || minecraft.player == null || !KnownMenuProfileSupport.storageProviderAvailable()
                || !minecraft.level.isLoaded(position)) return Optional.empty();
        var entity = minecraft.level.getBlockEntity(position);
        var stack = placedStack(entity);
        return identity(stack).map(id -> new PlacedBag(minecraft, position, aim, entity, stack, id, observable));
    }

    private static ItemStack placedStack(net.minecraft.world.level.block.entity.BlockEntity entity) {
        if (entity == null || !entity.getClass().getName().equals(ROOT + "backpack.BackpackBlockEntity")) return ItemStack.EMPTY;
        try {
            Object wrapper = entity.getClass().getMethod("getBackpackWrapper").invoke(entity);
            return (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return ItemStack.EMPTY;
        }
    }

    private static final class PlacedBag implements Target {
        private final net.minecraft.client.multiplayer.ClientLevel level;
        private final net.minecraft.client.player.LocalPlayer player;
        private final net.minecraft.core.BlockPos position;
        private final net.minecraft.world.phys.Vec3 aim;
        private final net.minecraft.world.level.block.entity.BlockEntity entity;
        private final ItemStack stack;
        private final UUID identity;
        private final java.util.function.BooleanSupplier observable;
        private final int originalSlot;
        private int selectedSlot = -1;

        PlacedBag(Minecraft minecraft, net.minecraft.core.BlockPos position, net.minecraft.world.phys.Vec3 aim,
                net.minecraft.world.level.block.entity.BlockEntity entity, ItemStack stack, UUID identity,
                java.util.function.BooleanSupplier observable) {
            level = minecraft.level; player = minecraft.player;
            this.position = position.immutable(); this.aim = aim; this.entity = entity;
            this.stack = stack.copy(); this.identity = identity; this.observable = observable;
            originalSlot = player.getInventory().getSelectedSlot();
        }

        @Override public String profileHash() { return KnownMenuProfileSupport.sophisticatedBackpackProfile().profileHash(); }
        @Override public Object identityKey() { return List.of(level.dimension().identifier().toString(), position, identity); }
        @Override public int inventorySlot() { return -1; }
        @Override public String location() { return "block"; }
        @Override public ItemStack item() { return stack.copy(); }
        @Override public boolean stillPresent(Minecraft minecraft) {
            return minecraft.level == level && minecraft.player == player && level.isLoaded(position)
                    && !entity.isRemoved() && level.getBlockEntity(position) == entity
                    && SophisticatedStorageAccess.identity(placedStack(entity)).filter(identity::equals).isPresent();
        }
        @Override public boolean readyToOpen(Minecraft minecraft) {
            return stillPresent(minecraft) && !player.isShiftKeyDown() && player.isWithinBlockInteractionRange(position, 0)
                    && observable.getAsBoolean() && hit() != null;
        }
        private net.minecraft.world.phys.BlockHitResult hit() {
            var eye = player.getEyePosition();
            var ray = level.clip(new net.minecraft.world.level.ClipContext(eye,
                    aim.add(aim.subtract(eye).normalize().scale(0.002)),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            return ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !ray.isWorldBorderHit()
                    && ray.getBlockPos().equals(position) && eye.distanceToSqr(ray.getLocation()) <= 4.5 * 4.5 ? ray : null;
        }
        @Override public void open(Minecraft minecraft) {
            if (!readyToOpen(minecraft)) throw new IllegalStateException("placed storage target no longer observable");
            // BackpackBlock.useItemOn handles fluid containers before opening its menu.
            selectedSlot = dev.aod.mcmcp.routine.InventoryOpenHandPolicy.chooseProviderOpenSlot(player,
                    item -> item.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Fluid.ITEM,
                            net.neoforged.neoforge.transfer.access.ItemAccess.forStack(item)) == null)
                    .orElseThrow(() -> new IllegalStateException("no safe storage opening hand"));
            player.getInventory().setSelectedSlot(selectedSlot);
            minecraft.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit());
        }
        @Override public void release(Minecraft minecraft) {
            if (minecraft.player == player && minecraft.level == level && player.getInventory().getSelectedSlot() == selectedSlot)
                player.getInventory().setSelectedSlot(originalSlot);
        }
        @Override public boolean matches(KnownMenuProfileSupport.Context menu) {
            return !entity.isRemoved() && player.level() == level && level.isLoaded(position)
                    && level.getBlockEntity(position) == entity
                    && SophisticatedStorageAccess.identity(placedStack(entity)).filter(identity::equals).isPresent()
                    && profileHash().equals(menu.profile().profileHash()) && matchesOpenedMenu(menu.menu(), menu.player());
        }
        @Override public boolean matchesOpenedMenu(AbstractContainerMenu menu, Player menuPlayer) {
            if (menuPlayer != player || !KnownMenuProfileSupport.storageProviderAvailable()
                    || !menu.getClass().getName().equals(ROOT + "common.gui.BackpackContainer")) return false;
            try {
                Object context = menu.getClass().getMethod("getBackpackContext").invoke(menu);
                Class<?> type = Class.forName(ROOT + "common.gui.BackpackContext$Block");
                if (context.getClass() != type || !position.equals(type.getMethod("getBackpackPosition", Player.class).invoke(context, player)))
                    return false;
                Object wrapper = type.getMethod("getBackpackWrapper", Player.class).invoke(context, player);
                ItemStack opened = (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
                return SophisticatedStorageAccess.identity(opened).filter(identity::equals).isPresent();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }
    }

    @Override
    public List<Target> discover(Minecraft minecraft) {
        if (minecraft.player == null || !KnownMenuProfileSupport.storageProviderAvailable()) return List.of();
        var result = new ArrayList<Target>();
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= 36 && slot != 38 && slot != 40) continue;
            ItemStack item = inventory.getItem(slot);
            Optional<UUID> identity = identity(item);
            if (identity.isEmpty()) continue;
            result.add(new Bag(slot, item.copy(), identity.orElseThrow()));
        }
        return List.copyOf(result);
    }

    private static Optional<UUID> identity(ItemStack item) {
        if (item.isEmpty() || item.getCount() != 1
                || !item.getItem().getClass().getName().equals(ROOT + "backpack.BackpackItem")) return Optional.empty();
        try {
            Class<?> wrapperType = Class.forName(ROOT + "backpack.wrapper.BackpackWrapper");
            Object wrapper = wrapperType.getMethod("fromStack", ItemStack.class).invoke(null, item);
            // Linked/nested containers require their own identity and synchronization contract.
            if (wrapper.getClass() != wrapperType) return Optional.empty();
            Object value = wrapperType.getMethod("getContentsUuid").invoke(wrapper);
            return value instanceof Optional<?> optional && optional.orElse(null) instanceof UUID id
                    ? Optional.of(id) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private record Bag(int slot, ItemStack stack, UUID identity) implements Target {
        @Override public String profileHash() { return KnownMenuProfileSupport.sophisticatedBackpackProfile().profileHash(); }
        @Override public Object identityKey() { return identity; }
        @Override public int inventorySlot() { return slot; }
        @Override public String location() { return slot == 38 ? "chest" : slot == 40 ? "off_hand" : "inventory"; }
        @Override public ItemStack item() { return stack.copy(); }

        @Override
        public boolean stillPresent(Minecraft minecraft) {
            if (minecraft.player == null || slot >= minecraft.player.getInventory().getContainerSize()) return false;
            ItemStack current = minecraft.player.getInventory().getItem(slot);
            return ItemStack.isSameItemSameComponents(stack, current)
                    && SophisticatedStorageAccess.identity(current).filter(identity::equals).isPresent();
        }

        @Override
        public void open(Minecraft minecraft) {
            if (!stillPresent(minecraft)) throw new IllegalStateException("storage target moved");
            try {
                var payload = (CustomPacketPayload) Class.forName(ROOT + "network.BackpackOpenPayload")
                        .getConstructor(int.class, String.class, String.class).newInstance(slot, "", handler());
                // This is the provider's ordinary, targeted inventory-open action used by its UI.
                ClientPacketDistributor.sendToServer(payload);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("storage open unavailable");
            }
        }

        private String handler() throws ReflectiveOperationException {
            String field = slot == 38 ? "ARMOR_INVENTORY" : slot == 40 ? "OFFHAND_INVENTORY" : "MAIN_INVENTORY";
            return (String) Class.forName(ROOT + "util.PlayerInventoryProvider").getField(field).get(null);
        }

        @Override
        public boolean matches(KnownMenuProfileSupport.Context menu) {
            return profileHash().equals(menu.profile().profileHash())
                    && matchesOpenedMenu(menu.menu(), menu.player());
        }

        @Override
        public boolean matchesOpenedMenu(AbstractContainerMenu menu, Player player) {
            if (!KnownMenuProfileSupport.storageProviderAvailable()
                    || !menu.getClass().getName().equals(ROOT + "common.gui.BackpackContainer")) return false;
            try {
                Object context = menu.getClass().getMethod("getBackpackContext").invoke(menu);
                Class<?> type = Class.forName(ROOT + "common.gui.BackpackContext$Item");
                if (context.getClass() != type) return false;
                var handlerField = type.getDeclaredField("handlerName");
                var identifierField = type.getDeclaredField("identifier");
                if (!handlerField.trySetAccessible() || !identifierField.trySetAccessible()
                        || !handler().equals(handlerField.get(context)) || !"".equals(identifierField.get(context))) return false;
                int expectedSlot = slot == 38 ? 2 : slot == 40 ? 0 : slot;
                if ((int) type.getMethod("getBackpackSlotIndex").invoke(context) != expectedSlot) return false;
                Object wrapper = type.getMethod("getBackpackWrapper", Player.class).invoke(context, player);
                ItemStack opened = (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
                return SophisticatedStorageAccess.identity(opened).filter(identity::equals).isPresent();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }
}
