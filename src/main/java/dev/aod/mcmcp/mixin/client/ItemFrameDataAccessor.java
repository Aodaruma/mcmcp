package dev.aod.mcmcp.mixin.client;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Packet field identities only; no private frame settings are exposed. */
@Mixin(ItemFrame.class)
public interface ItemFrameDataAccessor {
    @Accessor("DATA_ITEM")
    static EntityDataAccessor<ItemStack> mcmcp$itemData() {
        throw new AssertionError("mixin accessor not applied");
    }

    @Accessor("DATA_ROTATION")
    static EntityDataAccessor<Integer> mcmcp$rotationData() {
        throw new AssertionError("mixin accessor not applied");
    }
}
