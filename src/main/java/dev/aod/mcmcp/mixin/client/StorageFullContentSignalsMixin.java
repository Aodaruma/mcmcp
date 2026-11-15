package dev.aod.mcmcp.mixin.client;

import dev.aod.mcmcp.runtime.KnownMenuProfileSupport;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional compatibility bridge; absent or unverified providers produce no evidence. */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.network.SyncContainerStacksPayload", remap = false)
public abstract class StorageFullContentSignalsMixin {
    @Inject(method = "handlePayload", at = @At("TAIL"), require = 0)
    private static void mcmcp$content(@Coerce Object payload, IPayloadContext context,
            CallbackInfo callback) {
        KnownMenuProfileSupport.recordStoragePacket(payload, true);
    }
}
