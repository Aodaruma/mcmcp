package dev.aodaruma.craftagent.mixin.client;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Required client-only access to BlockItem's complete fail-before-send placement calculation. */
@Mixin(BlockItem.class)
public interface BlockItemPlacementInvoker {
    @Invoker("getPlacementState")
    BlockState craftagent$invokeGetPlacementState(BlockPlaceContext context);
}
