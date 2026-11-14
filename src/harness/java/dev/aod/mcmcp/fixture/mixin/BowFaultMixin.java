package dev.aod.mcmcp.fixture.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.aod.mcmcp.fixture.BowFaultInjector;
import dev.aod.mcmcp.safety.BowUseCancellation;
import java.util.function.IntConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = BowUseCancellation.class, remap = false)
abstract class BowFaultMixin {
    @WrapOperation(method = "cancel", at = @At(value = "INVOKE", target = "Ljava/util/function/IntConsumer;accept(I)V"), require = 1)
    private void mcmcp$failOneCancelSend(IntConsumer receiver, int slot, Operation<Void> original,
            Object player, int selectedSlot, IntConsumer sendSlot, Runnable stopLocalUse) {
        BowFaultInjector.before(player, slot == selectedSlot ? "cancel_second" : "cancel_first");
        original.call(receiver, slot);
    }

    @WrapOperation(method = "fire", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"), require = 1, expect = 1)
    private void mcmcp$failOneFire(Runnable receiver, Operation<Void> original,
            Object player, int selectedSlot, IntConsumer sendSlot, Runnable fire, Runnable stopLocalUse) {
        BowFaultInjector.before(player, "fire");
        original.call(receiver);
    }
}
