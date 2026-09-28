package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;

/** Current local rays, own inventory and screen; never reads behind an occluding block. */
final class V2LocalConditions implements V2StopCondition.Context {
    private final Minecraft minecraft;
    V2LocalConditions(Minecraft minecraft) { this.minecraft = minecraft; }
    public Vec3 position() { return minecraft.player.position(); }

    public BlockStateFingerprint visibleBlock(int x, int y, int z) {
        var position = new BlockPos(x, y, z);
        return visible(minecraft, position, McmcpClientConfig.visualRadiusBlocks())
                ? fingerprint(minecraft.level.getBlockState(position)) : null;
    }

    static boolean visible(Minecraft minecraft, BlockPos target, double range) {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null || !level.isLoaded(target)) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(center) > range * range) return false;
        for (BlockPos traversed : BlockPos.betweenClosed(BlockPos.containing(eye), target)) {
            if (!level.isLoaded(traversed)) return false;
        }
        var points = new java.util.ArrayList<Vec3>();
        points.add(center);
        for (Direction face : Direction.values()) points.add(center.add(
                face.getStepX() * 0.499D, face.getStepY() * 0.499D, face.getStepZ() * 0.499D));
        for (Vec3 point : points) {
            boolean clear = true;
            for (var mode : new ClipContext.Block[] { ClipContext.Block.VISUAL, ClipContext.Block.COLLIDER }) {
                var hit = level.clip(new ClipContext(eye, point, mode, ClipContext.Fluid.ANY, player));
                if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(target)) { clear = false; break; }
            }
            if (clear) return true;
        }
        return false;
    }

    static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    public int itemCount(String item) {
        var inventory = minecraft.player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) count += stack.getCount();
        }
        return count;
    }

    public String screen() {
        if (minecraft.gui.screen() == null) return "none";
        if (minecraft.gui.screen() instanceof InventoryScreen) return "inventory";
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?>) return "container";
        if (minecraft.gui.screen() instanceof ChatScreen) return "chat";
        return "other";
    }
}
