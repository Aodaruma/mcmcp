package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import dev.aod.mcmcp.routine.SafeBreakSourcePolicy;
import dev.aod.mcmcp.routine.StationaryBreakRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** 既知blockの破壊認可、道具、回収容量、発生源の事前条件。 */
final class KnownBreakSafety {
    private KnownBreakSafety() {}

    static boolean breakProgramPreconditionsCurrent(
            Minecraft minecraft,
            ActionDslCompiler.CompiledProgram program,
            Optional<ActionDsl.Node> initialPrimitive) {
        if (initialPrimitive.filter(KnownBreakSafety::isKnownBreak).isEmpty()) {
            return true;
        }
        var player = minecraft.player;
        if (player == null) return false;
        var breaks = new ArrayList<ActionDsl.Node>();
        collectBreakNodes(program.request().program().body(), breaks);
        if (breaks.isEmpty()) return true;
        if (!player.isAlive() || player.isDeadOrDying() || player.isUsingItem()
                || !player.onGround() || player.isPassenger()
                || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying
                || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return false;
        }
        int requiredDurability = Math.toIntExact(program.worstCaseCost().blocksBroken());
        // ponytail: mixed-tool branches use one conservative worst-path allowance per tool.
        for (var block : breaks) {
            if (findDurableHotbarTool(player, breakToolItem(block), requiredDurability) < 0) {
                return false;
            }
        }
        return inventoryCanReceiveKnownBreakDrops(player, program);
    }

    static boolean isKnownBreak(ActionDsl.Node node) {
        return node instanceof ActionDsl.BreakKnownFace
                || node instanceof ActionDsl.BreakKnownBlock
                || node instanceof ActionDsl.OperateKnownCobblestoneGenerator;
    }

    static ActionDsl.Position breakTarget(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.target();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.target();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.target();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    static ActionDsl.BlockFace breakFace(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.face();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.face();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.face();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    static String breakBlockId(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.expectedBlock();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.expectedState().block();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.expectedState().block();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    static String breakToolItem(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.toolItem();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.toolItem();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.toolItem();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    static String breakExpectedDrop(ActionDsl.Node node) {
        if (node instanceof ActionDsl.BreakKnownFace legacy) return legacy.expectedBlock();
        if (node instanceof ActionDsl.BreakKnownBlock exact) return exact.expectedDrop();
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return operation.expectedDrop();
        }
        throw new IllegalArgumentException("node is not a known break");
    }

    static void collectBreakNodes(
            List<ActionDsl.Node> nodes, List<ActionDsl.Node> output) {
        for (var node : nodes) {
            if (isKnownBreak(node)) {
                output.add(node);
            } else if (node instanceof ActionDsl.If conditional) {
                collectBreakNodes(conditional.thenBranch(), output);
                collectBreakNodes(conditional.elseBranch(), output);
            } else if (node instanceof ActionDsl.Repeat repeat) {
                collectBreakNodes(repeat.body(), output);
            }
        }
    }

    static ActionDsl.BreakKnownBlock cobblestoneGeneratorBreak(
            ActionDsl.OperateKnownCobblestoneGenerator operation) {
        return new ActionDsl.BreakKnownBlock(
                operation.id(), operation.target(), operation.face(),
                operation.expectedState(), operation.toolItem(), operation.expectedDrop(),
                operation.minimumInventoryCount());
    }

    static int findDurableHotbarTool(
            net.minecraft.client.player.LocalPlayer player,
            String itemId,
            int requiredDurability) {
        if (requiredDurability < 1) return -1;
        var inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                    && stack.isDamageableItem()
                    && stack.getMaxDamage() - stack.getDamageValue() >= requiredDurability) {
                return slot;
            }
        }
        return -1;
    }

    static boolean inventoryCanReceiveKnownBreakDrops(
            net.minecraft.client.player.LocalPlayer player,
            ActionDslCompiler.CompiledProgram program) {
        var breaks = new ArrayList<ActionDsl.Node>();
        collectBreakNodes(program.request().program().body(), breaks);
        var dropItems = new LinkedHashSet<String>();
        breaks.forEach(block -> dropItems.add(breakExpectedDrop(block)));
        if (dropItems.isEmpty()) return true;
        int requiredPerType = Math.toIntExact(program.worstCaseCost().blocksBroken());
        var inventory = player.getInventory();
        int emptySlots = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) emptySlots++;
        }
        int newStacksNeeded = 0;
        for (String itemId : dropItems) {
            int existingCapacity = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                    existingCapacity = Math.addExact(
                            existingCapacity,
                            Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                }
            }
            if (existingCapacity < requiredPerType) newStacksNeeded++;
        }
        return emptySlots >= newStacksNeeded;
    }

    static boolean breakSourceControlled(
            Minecraft minecraft, ActionDsl.Node block) {
        return breakSourceControlled(minecraft, block, true);
    }

    static boolean breakSourceControlled(
            Minecraft minecraft, ActionDsl.Node block, boolean requireDeclaredFace) {
        var player = minecraft.player;
        var level = minecraft.level;
        var gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null
                || minecraft.getConnection() == null
                || !player.isAlive() || player.isDeadOrDying() || player.isUsingItem()
                || !player.onGround() || player.isPassenger()
                || player.isInWater() || player.isInLava()
                || player.isFallFlying() || player.getAbilities().flying
                || gameMode.getPlayerMode() != GameType.SURVIVAL
                || !breakTarget(block).dimension().equals(
                        level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                breakTarget(block).x(), breakTarget(block).y(), breakTarget(block).z());
        if (!level.isLoaded(position)
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(position)
                || requireDeclaredFace
                        && hit.getDirection() != Direction.valueOf(breakFace(block).name())
                || !player.isWithinBlockInteractionRange(position, 0.0D)
                || !level.getWorldBorder().isWithinBounds(position)
                || player.blockActionRestricted(level, position, gameMode.getPlayerMode())) {
            return false;
        }
        var state = level.getBlockState(position);
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        float destroyProgress = state.getDestroyProgress(player, level, position);
        if (!breakBlockId(block).equals(blockId)
                || !SafeBreakSourcePolicy.allowsLiveState(
                        state, level.getBlockEntity(position) != null)
                || destroyProgress <= 0.0F
                || destroyProgress * StationaryBreakRequest.MAX_ATTACK_LEASE_TICKS < 1.0F) {
            return false;
        }
        if (block instanceof ActionDsl.BreakKnownBlock exact) {
            var live = MinecraftStationaryBreakPort.fingerprintForPolicy(state);
            if (!new BlockStateFingerprint(
                            exact.expectedState().block(), exact.expectedState().properties())
                    .equals(live)
                    || !SafeBreakSourcePolicy.allowsKnownBlockCombination(
                            exact.expectedState().block(), exact.toolItem(), exact.expectedDrop())) {
                return false;
            }
        }
        int selected = player.getInventory().getSelectedSlot();
        if (selected < 0 || selected >= Inventory.getSelectionSize()) return false;
        var tool = player.getInventory().getItem(selected);
        return !tool.isEmpty()
                && breakToolItem(block).equals(
                        BuiltInRegistries.ITEM.getKey(tool.getItem()).toString())
                && tool.isDamageableItem()
                && tool.getMaxDamage() - tool.getDamageValue() >= 1;
    }

    static boolean breakTargetStateMatches(
            Minecraft minecraft, ActionDsl.Node block) {
        var level = minecraft.level;
        if (level == null || !breakTarget(block).dimension().equals(
                level.dimension().identifier().toString())) {
            return false;
        }
        var position = new BlockPos(
                breakTarget(block).x(), breakTarget(block).y(), breakTarget(block).z());
        if (!level.isLoaded(position)) return false;
        var state = level.getBlockState(position);
        if (!breakBlockId(block).equals(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return false;
        return !(block instanceof ActionDsl.BreakKnownBlock exact)
                || new BlockStateFingerprint(
                        exact.expectedState().block(), exact.expectedState().properties())
                        .equals(MinecraftStationaryBreakPort.fingerprintForPolicy(state));
    }
}
