package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A coordinate container request; the current state and aim are supplied by the MOD at use time. */
record V2InventoryContainerArguments(boolean inspect, int x, int y, int z,
        String blockId, String itemId, boolean store, int count,
        boolean advance, double maxDistance, int maxTicks) implements V2InventoryRequest {
    V2InventoryContainerArguments {
        if (blockId != null) V2InventoryRequest.requireItemId(blockId);
        if (itemId != null) V2InventoryRequest.requireItemId(itemId);
        if (inspect ? count != 0 || store : itemId == null || count < 1 || count > 896) {
            throw new IllegalArgumentException("invalid container operation or quantity");
        }
        if (!Double.isFinite(maxDistance) || maxDistance < 0 || maxDistance > 256
                || maxTicks < 1 || maxTicks > 1_200) {
            throw new IllegalArgumentException("invalid container operation bounds");
        }
    }

    static V2InventoryContainerArguments parse(Map<String, Object> arguments) {
        boolean inspect = "inspect".equals(RuntimeArguments.stringArgument(arguments, "operation"));
        if (!inspect && !"transfer".equals(RuntimeArguments.stringArgument(arguments, "operation"))) {
            throw new IllegalArgumentException("container operation must be inspect or transfer");
        }
        RuntimeArguments.requireAllowedKeys(arguments, "agent_inventory", inspect
                ? Set.of("operation", "target", "x", "y", "z", "block", "item",
                        "advance", "max_distance", "max_ticks")
                : Set.of("operation", "target", "x", "y", "z", "block", "item",
                        "direction", "count", "advance", "max_distance", "max_ticks"));
        if (!"container".equals(RuntimeArguments.stringArgument(arguments, "target"))) {
            throw new IllegalArgumentException("container target required");
        }
        String direction = inspect ? "take" : RuntimeArguments.stringArgument(arguments, "direction");
        if (!Set.of("take", "store").contains(direction)) {
            throw new IllegalArgumentException("direction must be take or store");
        }
        return new V2InventoryContainerArguments(inspect,
                RuntimeArguments.intArgument(arguments, "x"),
                RuntimeArguments.intArgument(arguments, "y"),
                RuntimeArguments.intArgument(arguments, "z"),
                arguments.containsKey("block") ? RuntimeArguments.stringArgument(arguments, "block") : null,
                arguments.containsKey("item") ? RuntimeArguments.stringArgument(arguments, "item") : null,
                "store".equals(direction), inspect ? 0 : RuntimeArguments.intArgument(arguments, "count"),
                !arguments.containsKey("advance") || RuntimeArguments.booleanArgument(arguments, "advance"),
                arguments.containsKey("max_distance")
                        ? RuntimeArguments.doubleArgument(arguments, "max_distance") : 64.0D,
                arguments.containsKey("max_ticks") ? RuntimeArguments.intArgument(arguments, "max_ticks") : 1_200);
    }

    NavCell target(String dimension) { return new NavCell(dimension, x, y, z); }

    PhaseFiveRequest operation(String dimension, BlockStateFingerprint state,
            Vec3 aim, double cameraDegreesPerTick) {
        if (blockId != null && !blockId.equals(state.blockId())) {
            throw new IllegalArgumentException("container block condition changed");
        }
        if (!Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z)
                || aim.x < x || aim.x > (double) x + 1
                || aim.y < y || aim.y > (double) y + 1
                || aim.z < z || aim.z > (double) z + 1) {
            throw new IllegalArgumentException("container aim outside target");
        }
        var target = new BlockTarget(dimension, x, y, z);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("container", Map.of(
                "target", Map.of("dimension", dimension, "x", x, "y", y, "z", z),
                "expected_state", Map.of("block", state.blockId(), "properties", state.properties())));
        parameters.put("direction", store ? "player_to_container" : "container_to_player");
        parameters.put("stack", Map.of("item", inspect ? "minecraft:air" : itemId,
                "stack_policy", "item_id_any_components"));
        parameters.put("goal", Map.of("minimum_destination_count", 0));
        parameters.put("max_transfer_count", inspect ? 1 : count);
        parameters.put("max_stack_moves", 14);
        if (!inspect) parameters.put("transfer_count", count);
        parameters.put("retain_view_on_release", true);
        parameters.put("max_camera_degrees_per_tick", cameraDegreesPerTick);
        parameters.put("aim_point", Map.of("dimension", dimension,
                "x", aim.x, "y", aim.y, "z", aim.z));
        return new PhaseFiveRequest("transfer_items", parameters,
                new PhaseFiveBounds(dimension, target, target, 0,
                        Math.max(1, Math.ceilDiv(maxTicks, 20)), false), 0, "items");
    }
}
