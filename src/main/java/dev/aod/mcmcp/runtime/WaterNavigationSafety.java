package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;

/** Only nearby loaded air/water cells can supply an oxygen retreat destination. */
final class WaterNavigationSafety {
    static boolean needsAir(Minecraft minecraft) {
        var player = minecraft.player;
        return player.isEyeInFluid(FluidTags.WATER) && !player.isCreative() && !player.isSpectator()
                && !player.hasEffect(MobEffects.WATER_BREATHING) && !player.hasEffect(MobEffects.CONDUIT_POWER);
    }
    static NavCell surface(Minecraft minecraft, String dimension) {
        var player = minecraft.player;
        var level = minecraft.level;
        var feet = player.blockPosition();
        // A vertical retreat avoids pretending that unseen air beyond a wall is reachable.
        for (int dy = 0; dy <= 5; dy++) {
            var target = feet.above(dy);
            if (!level.isLoaded(target) || !level.isLoaded(target.above())) return null;
            var state = level.getBlockState(target);
            if (!state.getCollisionShape(level, target).isEmpty()
                    || !state.getFluidState().isEmpty() && !state.getFluidState().is(FluidTags.WATER)) return null;
            var head = level.getBlockState(target.above());
            if (head.getFluidState().isEmpty() && head.getCollisionShape(level, target.above()).isEmpty())
                return new NavCell(dimension, target.getX(), target.getY(), target.getZ());
        }
        return null;
    }
}
