package dev.aod.mcmcp.runtime;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** A provider supplies discovery/opening/identity only. All transfers use the common Menu engine. */
public interface StorageAccess {
    List<Target> discover(Minecraft minecraft);

    interface Target {
        String profileHash();
        Object identityKey();
        int inventorySlot();
        String location();
        ItemStack item();
        boolean stillPresent(Minecraft minecraft);
        void open(Minecraft minecraft);
        boolean matches(KnownMenuProfileSupport.Context menu);
        /** Identity only, for closing an opened menu whose contents/layout are not accepted. */
        boolean matchesOpenedMenu(AbstractContainerMenu menu, Player player);
    }
}
