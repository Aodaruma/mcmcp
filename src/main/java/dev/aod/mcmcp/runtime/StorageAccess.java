package dev.aod.mcmcp.runtime;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** A provider supplies discovery/opening/identity only. All transfers use the common Menu engine. */
public interface StorageAccess {
    List<Target> discover(Minecraft minecraft);

    interface Target {
        String profileHash();
        Object identityKey();
        String location();
        ItemStack item();
        boolean stillPresent(Minecraft minecraft);
        void open(Minecraft minecraft);
        boolean matches(KnownMenuProfileSupport.Context menu);
    }
}
