package dev.aod.mcmcp.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to the same held-use dispatch and repeat delay used by Vanilla keybind input. */
@Mixin(Minecraft.class)
public interface MinecraftUseItemInvoker {
    @Invoker("startUseItem")
    void mcmcp$startUseItem();

    @Accessor("rightClickDelay")
    int mcmcp$getRightClickDelay();
}
