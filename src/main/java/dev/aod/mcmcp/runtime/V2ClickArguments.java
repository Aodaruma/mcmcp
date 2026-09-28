package dev.aod.mcmcp.runtime;

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
                        "x", "y", "z", "block", "entity_uuid", "entity_type"));
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
        if (count < 1 || count > 32 || holdTicks < 1 || holdTicks > 20
                || gapTicks < 0 || gapTicks > 40 || count > 1 && gapTicks == 0) {
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
        boolean entity = arguments.containsKey("entity_uuid");
        if ((coordinates != 0 && coordinates != 3) || (entity && coordinates != 0)
                || (arguments.containsKey("block") && coordinates != 3)
                || (arguments.containsKey("entity_type") && !entity)) {
            throw new IllegalArgumentException("click target requires exact block coordinates or entity UUID");
        }
        if (coordinates == 3) {
            String block = arguments.containsKey("block")
                    ? resourceId(RuntimeArguments.stringArgument(arguments, "block")) : null;
            return new BlockTarget(RuntimeArguments.intArgument(arguments, "x"),
                    RuntimeArguments.intArgument(arguments, "y"),
                    RuntimeArguments.intArgument(arguments, "z"), block);
        }
        if (entity) {
            String raw = RuntimeArguments.stringArgument(arguments, "entity_uuid");
            UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException("invalid entity UUID");
            String type = arguments.containsKey("entity_type")
                    ? resourceId(RuntimeArguments.stringArgument(arguments, "entity_type")) : null;
            return new EntityTarget(id, type);
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

    sealed interface Target permits BlockTarget, EntityTarget { }
    record BlockTarget(int x, int y, int z, String blockId) implements Target { }
    record EntityTarget(UUID uuid, String typeId) implements Target { }
}
