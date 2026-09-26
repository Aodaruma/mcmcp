package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.KnownMenuProfileSupport;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.network.SyncSlotStackPayload", remap = false)
public abstract class StorageSlotSignalsMixin {
    @Inject(method = "handlePayload", at = @At("TAIL"), require = 0)
    private static void mcmcp$slot(@Coerce Object payload, IPayloadContext context,
            CallbackInfo callback) {
        KnownMenuProfileSupport.recordStoragePacket(payload, false);
    }
}
