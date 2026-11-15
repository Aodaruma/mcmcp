package dev.aod.mcmcp.runtime;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** A provider supplies discovery/opening/identity only. All transfers use the common Menu engine. */
public interface StorageAccess {
    List<Target> discover(Minecraft minecraft);
    default Optional<Target> atBlock(Minecraft minecraft, BlockPos position, Vec3 aim,
            java.util.function.BooleanSupplier observable) { return Optional.empty(); }

    interface Target {
        String profileHash();
        Object identityKey();
        int inventorySlot();
        String location();
        ItemStack item();
        boolean stillPresent(Minecraft minecraft);
        default boolean readyToOpen(Minecraft minecraft) { return stillPresent(minecraft); }
        void open(Minecraft minecraft);
        default void release(Minecraft minecraft) { }
        boolean matches(KnownMenuProfileSupport.Context menu);
        /** Identity only, for closing an opened menu whose contents/layout are not accepted. */
        boolean matchesOpenedMenu(AbstractContainerMenu menu, Player player);
    }
}
