package dev.aod.mcmcp.routine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Bounded wait for Vanilla's crosshair after the owned camera has reached its witness. */
final class ContainerAimGate {
    static final int CROSSHAIR_WAIT_TICKS = 40;
    private long alignedSince = -1L;

    Decision observe(long clientTick, boolean aligned, HitResult crosshair, BlockPos target) {
        if (!aligned) return Decision.WAIT;
        if (alignedSince < 0L) {
            alignedSince = clientTick;
            // The crosshair still belongs to the preceding rendered camera at this point.
            return Decision.WAIT;
        }
        if (clientTick > alignedSince && exactTarget(crosshair, target)) {
            return Decision.OPEN;
        }
        return clientTick - alignedSince >= CROSSHAIR_WAIT_TICKS
                ? Decision.OCCLUDED : Decision.WAIT;
    }

    static boolean exactTarget(HitResult crosshair, BlockPos target) {
        return crosshair instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(target);
    }

    /** Fixed classification only: never expose the hit position or entity identity. */
    static String occlusionKind(HitResult crosshair) {
        if (crosshair == null) return "unavailable";
        return switch (crosshair.getType()) {
            case ENTITY -> "entity";
            case MISS -> "miss";
            case BLOCK -> crosshair instanceof BlockHitResult hit
                    ? hit.isWorldBorderHit() ? "world_border" : "block_other"
                    : "unavailable";
        };
    }

    enum Decision { WAIT, OPEN, OCCLUDED }
}
