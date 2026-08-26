package dev.aod.mcmcp.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access is deliberately isolated behind a required, client-only Mixin. */
@Mixin(ClientLevel.class)
public interface ClientLevelPredictionAccessor {
    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler mcmcp$getBlockStatePredictionHandler();
}
