package dev.aod.mcmcp.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Compatibility differences for one verified build; never reads a closed bag's inventory. */
final class SophisticatedStorageAccess implements StorageAccess {
    private static final String ROOT = "net.p3pp3rf1y.sophisticatedbackpacks.";

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
            if (!profileHash().equals(menu.profile().profileHash())) return false;
            try {
                Object context = menu.menu().getClass().getMethod("getBackpackContext").invoke(menu.menu());
                Class<?> type = Class.forName(ROOT + "common.gui.BackpackContext$Item");
                if (context.getClass() != type) return false;
                var handlerField = type.getDeclaredField("handlerName");
                var identifierField = type.getDeclaredField("identifier");
                if (!handlerField.trySetAccessible() || !identifierField.trySetAccessible()
                        || !handler().equals(handlerField.get(context)) || !"".equals(identifierField.get(context))) return false;
                int expectedSlot = slot == 38 ? 2 : slot == 40 ? 0 : slot;
                if ((int) type.getMethod("getBackpackSlotIndex").invoke(context) != expectedSlot) return false;
                Object wrapper = type.getMethod("getBackpackWrapper", Player.class).invoke(context, menu.player());
                ItemStack opened = (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
                return SophisticatedStorageAccess.identity(opened).filter(identity::equals).isPresent();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }
}
