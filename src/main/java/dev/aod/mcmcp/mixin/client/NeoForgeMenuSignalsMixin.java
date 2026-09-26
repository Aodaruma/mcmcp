package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ordinary NeoForge menu-open packet has the same ownership semantics as Vanilla. */
@Mixin(targets = "net.neoforged.neoforge.client.network.ClientPayloadHandler")
public abstract class NeoForgeMenuSignalsMixin {
    @Inject(method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedOpenScreenPayload;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V", at = @At("HEAD"))
    private static void mcmcp$open(AdvancedOpenScreenPayload payload, IPayloadContext context,
            CallbackInfo callback) {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.level == null) return;
        var signals = ScreenOwnershipSignals.global();
        signals.onOpenScreen(minecraft.level, payload.windowId(),
                ScreenOwnershipSignals.menuTypeId(payload.menuType()), signals.currentTick());
    }
}
