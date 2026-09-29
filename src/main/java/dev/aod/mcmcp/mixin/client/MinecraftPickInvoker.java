package dev.aod.mcmcp.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accesses the same one-shot pick action as Vanilla's middle mouse binding. */
@Mixin(Minecraft.class)
public interface MinecraftPickInvoker {
    @Invoker("pickBlockOrEntity")
    void mcmcp$pickBlockOrEntity();
}
