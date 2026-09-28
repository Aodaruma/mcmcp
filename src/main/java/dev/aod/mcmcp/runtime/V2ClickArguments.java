package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.agent.input.FiniteInputSequence;
import dev.aod.mcmcp.routine.BoundedInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Finite mouse input with an optional exact crosshair guard. */
record V2ClickArguments(FiniteInputSequence sequence, Target target) {
    static V2ClickArguments parse(Map<String, Object> arguments) {
        RuntimeArguments.requireAllowedKeys(arguments, "agent_click",
                Set.of("button", "count", "hold_ticks", "gap_ticks",
                        "x", "y", "z", "block", "entity_ref", "entity_type"));
        String button = RuntimeArguments.stringArgument(arguments, "button");
        BoundedInputLease.Input input = switch (button) {
            case "left" -> BoundedInputLease.Input.ATTACK;
            case "right" -> BoundedInputLease.Input.USE;
            case "middle" -> BoundedInputLease.Input.PICK;
            default -> throw new IllegalArgumentException("button must be left, right or middle");
        };
        int count = arguments.containsKey("count")
                ? RuntimeArguments.intArgument(arguments, "count") : 1;
        int holdTicks = arguments.containsKey("hold_ticks")
                ? RuntimeArguments.intArgument(arguments, "hold_ticks") : 1;
        int gapTicks = arguments.containsKey("gap_ticks")
                ? RuntimeArguments.intArgument(arguments, "gap_ticks") : 4;
        if (count < 1 || count > AgentJobLimits.MAX_TICKS || holdTicks < 1 || holdTicks > AgentJobLimits.MAX_TICKS
                || gapTicks < 0 || gapTicks > AgentJobLimits.MAX_TICKS || count > 1 && gapTicks == 0) {
            throw new IllegalArgumentException("invalid click count or tick bounds");
        }
        var sequence = new FiniteInputSequence(List.of(new FiniteInputSequence.Step(
                Set.of(input), holdTicks, gapTicks, count)));
        return new V2ClickArguments(sequence, parseTarget(arguments));
    }

    private static Target parseTarget(Map<String, Object> arguments) {
        int coordinates = (arguments.containsKey("x") ? 1 : 0)
                + (arguments.containsKey("y") ? 1 : 0)
                + (arguments.containsKey("z") ? 1 : 0);
        boolean entity = arguments.containsKey("entity_ref");
        if ((coordinates != 0 && coordinates != 3) || (entity && coordinates != 0)
                || (arguments.containsKey("block") && coordinates != 3)
                || (arguments.containsKey("entity_type") && !entity)) {
            throw new IllegalArgumentException("click target requires exact block coordinates or entity_ref");
        }
        if (coordinates == 3) {
            String block = arguments.containsKey("block")
                    ? resourceId(RuntimeArguments.stringArgument(arguments, "block")) : null;
            return new BlockTarget(RuntimeArguments.intArgument(arguments, "x"),
                    RuntimeArguments.intArgument(arguments, "y"),
                    RuntimeArguments.intArgument(arguments, "z"), block);
        }
        if (entity) {
            String ref = RuntimeArguments.stringArgument(arguments, "entity_ref");
            if (!ref.matches("[A-Za-z0-9_-]{24}")) {
                throw new IllegalArgumentException("invalid entity_ref");
            }
            String type = arguments.containsKey("entity_type")
                    ? resourceId(RuntimeArguments.stringArgument(arguments, "entity_type")) : null;
            return new EntityRefTarget(ref, type);
        }
        return null;
    }

    static boolean targetMatches(Minecraft minecraft, Target target) {
        if (target == null) return true;
        if (minecraft.player == null || minecraft.level == null) return false;
        if (target instanceof BlockTarget block) {
            if (!(minecraft.hitResult instanceof BlockHitResult hit)) return false;
            var position = new BlockPos(block.x(), block.y(), block.z());
            if (!hit.getBlockPos().equals(position)
                    || !minecraft.level.isLoaded(position)
                    || !minecraft.player.isWithinBlockInteractionRange(position, 0.0D)) return false;
            return block.blockId() == null || block.blockId().equals(
                    BuiltInRegistries.BLOCK.getKey(
                            minecraft.level.getBlockState(position).getBlock()).toString());
        }
        if (target instanceof EntityRefTarget) return false;
        var entityTarget = (EntityTarget) target;
        if (!(minecraft.hitResult instanceof EntityHitResult hit)) return false;
        var entity = hit.getEntity();
        return entity.getUUID().equals(entityTarget.uuid()) && entity.isAlive()
                && minecraft.player.isWithinEntityInteractionRange(entity, 0.0D)
                && minecraft.player.hasLineOfSight(entity)
                && (entityTarget.typeId() == null || entityTarget.typeId().equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
    }

    private static String resourceId(String value) {
        if (value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("target type must be a resource ID");
        }
        return value;
    }

    sealed interface Target permits BlockTarget, EntityRefTarget, EntityTarget { }
    record BlockTarget(int x, int y, int z, String blockId) implements Target { }
    record EntityRefTarget(String ref, String typeId) implements Target { }
    /** Client-only identity resolved from a currently visible opaque reference. */
    record EntityTarget(UUID uuid, String typeId) implements Target { }
}
