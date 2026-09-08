package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.MinecraftKnownMenuPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.PhaseFiveBounds;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** 配送・計画済みの証拠からcontainer、craft、smelt、brew要求を構築する。 */
final class InventoryRequests {
    private InventoryRequests() {}

    static KnownBrewingRequest brewingRequest(
            ActionDsl.BrewKnownPotionBatch brew,
            AgentPrimitivePlanner.MutationAim brewingAim,
            float maxCameraDegreesPerTick) {
        Objects.requireNonNull(brew, "brew");
        if (!StandardPotionPolicy.BREWING_STAND.equals(brew.expectedBlock())) {
            throw new IllegalArgumentException("brewing target must be a brewing stand");
        }
        var target = new BlockTarget(
                brew.target().dimension(),
                brew.target().x(),
                brew.target().y(),
                brew.target().z());
        KnownBrewingRequest base = new KnownBrewingRequest(
                target,
                brew.input(),
                brew.ingredientItem(),
                brew.fuelItem(),
                brew.expectedOutput(),
                maxCameraDegreesPerTick);
        PhaseFiveRequest operation = withInventoryAim(
                base.operation(), brew.target(), brewingAim);
        return new KnownBrewingRequest(
                base.target(), base.input(), base.ingredientItem(), base.fuelItem(),
                base.expectedOutput(), base.maxCameraDegreesPerTick(), operation);
    }

    static PhaseFiveRequest containerRequest(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive,
            AgentPrimitivePlanner.MutationAim inventoryAim) {
        if (primitive instanceof ActionDsl.OperateKnownMenu operation) {
            var player = Objects.requireNonNull(minecraft.player, "player");
            return knownMenuRequest(operation, new BlockTarget(
                    session.dimension(),
                    Mth.floor(player.getX()),
                    Mth.floor(player.getY()),
                    Mth.floor(player.getZ())));
        }
        if (primitive instanceof ActionDsl.CraftKnownRecipe craft) {
            return withInventoryAim(craftRequest(craft), craft.target(), inventoryAim);
        }
        if (primitive instanceof ActionDsl.SmeltKnownRecipe smelt) {
            return smeltRequest(smelt, inventoryAim);
        }
        ActionDsl.Position position;
        String expectedBlock;
        String item;
        String stackPolicy;
        int minimumDestinationCount;
        int maxStacks = 1;
        int maxTransferCount = 64;
        if (primitive instanceof ActionDsl.InspectKnownContainer inspect) {
            position = inspect.target();
            expectedBlock = inspect.expectedBlock();
            item = "minecraft:air";
            stackPolicy = "item_id_any_components";
            minimumDestinationCount = 0;
        } else if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            position = take.target();
            expectedBlock = take.expectedBlock();
            item = take.item();
            stackPolicy = take.stackPolicy();
            minimumDestinationCount = take.minimumInventoryCount();
            maxStacks = take.maxStacks();
            maxTransferCount = take.maxTransferCount();
        } else if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            position = store.target();
            expectedBlock = store.expectedBlock();
            item = store.item();
            stackPolicy = store.stackPolicy();
            minimumDestinationCount = store.minimumContainerCount();
            maxStacks = store.maxStacks();
            maxTransferCount = store.maxTransferCount();
        } else {
            throw new IllegalArgumentException("node is not a known container operation");
        }
        if (!position.dimension().equals(session.dimension())) {
            throw new IllegalArgumentException("container target dimension changed");
        }
        BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
        var level = Objects.requireNonNull(minecraft.level, "level");
        BlockStateFingerprint state = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(
                level.getBlockState(blockPos));
        if (!expectedBlock.equals(state.blockId())) {
            throw new IllegalArgumentException("container target block changed");
        }
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var targetMap = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
        var stateMap = Map.<String, Object>of(
                "block", state.blockId(),
                "properties", state.properties());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("container", Map.of("target", targetMap, "expected_state", stateMap));
        parameters.put("direction", knownContainerTransferDirection(primitive));
        parameters.put("stack", Map.of("item", item, "stack_policy", stackPolicy));
        parameters.put("goal", Map.of(
                "minimum_destination_count", minimumDestinationCount));
        parameters.put("max_transfer_count", maxTransferCount);
        parameters.put("max_stack_moves", maxStacks);
        parameters.put("retain_view_on_release", true);
        parameters.put("max_camera_degrees_per_tick",
                McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0D);
        parameters.put("aim_point", inventoryAimPoint(position, inventoryAim));
        knownContainerRoutingLabel(primitive).ifPresent(label -> parameters.put(
                "routing_label", Map.of(
                        "entity_ref", label.entityRef(),
                        "item", label.item())));
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0, 20, false);
        return new PhaseFiveRequest(
                "transfer_items", parameters, bounds, minimumDestinationCount, "items");
    }

    static String knownContainerTransferDirection(ActionDsl.Node primitive) {
        Objects.requireNonNull(primitive, "primitive");
        if (primitive instanceof ActionDsl.StoreKnownContainerStack) {
            return "player_to_container";
        }
        if (primitive instanceof ActionDsl.InspectKnownContainer
                || primitive instanceof ActionDsl.TakeKnownContainerStack) {
            return "container_to_player";
        }
        throw new IllegalArgumentException("node is not a known container operation");
    }

    static Optional<ActionDsl.RoutingLabel> knownContainerRoutingLabel(
            ActionDsl.Node primitive) {
        if (primitive instanceof ActionDsl.InspectKnownContainer inspect) {
            return inspect.routingLabel();
        }
        if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            return take.routingLabel();
        }
        if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            return store.routingLabel();
        }
        return Optional.empty();
    }

    static PhaseFiveRequest withInventoryAim(
            PhaseFiveRequest request,
            ActionDsl.Position target,
            AgentPrimitivePlanner.MutationAim aim) {
        var parameters = new LinkedHashMap<String, Object>(request.parameters());
        parameters.put("aim_point", inventoryAimPoint(target, aim));
        return new PhaseFiveRequest(
                request.kind(), parameters, request.bounds(),
                request.expectedUnits(), request.progressUnit());
    }

    static Map<String, Object> inventoryAimPoint(
            ActionDsl.Position target,
            AgentPrimitivePlanner.MutationAim aim) {
        if (aim == null || !target.equals(aim.block())) {
            throw new IllegalArgumentException("menu aim witness is unavailable");
        }
        Vec3 point = aim.point();
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || point.x < target.x() || point.x > target.x() + 1.0D
                || point.y < target.y() || point.y > target.y() + 1.0D
                || point.z < target.z() || point.z > target.z() + 1.0D) {
            throw new IllegalArgumentException("menu aim witness is outside its target");
        }
        return Map.of(
                "dimension", target.dimension(),
                "x", point.x,
                "y", point.y,
                "z", point.z);
    }

    static PhaseFiveRequest knownMenuRequest(
            ActionDsl.OperateKnownMenu operation, BlockTarget position) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(position, "position");
        return new PhaseFiveRequest(
                MinecraftKnownMenuPort.KIND,
                Map.of("operation_ref", operation.operationRef()),
                new PhaseFiveBounds(
                        position.dimension(), position, position, 0, 30, false),
                1,
                "items");
    }

    static PhaseFiveRequest craftRequest(ActionDsl.CraftKnownRecipe craft) {
        ActionDsl.Position position = craft.target();
        var expected = new BlockStateFingerprint(
                craft.expectedState().block(), craft.expectedState().properties());
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var targetMap = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
        var stateMap = Map.<String, Object>of(
                "block", expected.blockId(),
                "properties", expected.properties());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("recipe_ref", craft.recipeRef());
        parameters.put("recipe_fingerprint", craft.recipeFingerprint());
        parameters.put("goal", Map.of(
                "item", craft.goalItem(),
                "stack_policy", craft.stackPolicy(),
                "minimum_inventory_count", craft.minimumInventoryCount()));
        parameters.put("station", Map.of(
                "kind", craft.stationKind(),
                "target", targetMap,
                "expected_state", stateMap));
        parameters.put("max_crafts", craft.maxCrafts());
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0, 20, false);
        return new PhaseFiveRequest(
                "craft_items", parameters, bounds, craft.minimumInventoryCount(), "items");
    }

    static PhaseFiveRequest smeltRequest(
            ActionDsl.SmeltKnownRecipe smelt,
            AgentPrimitivePlanner.MutationAim smeltingAim) {
        ActionDsl.Position position = smelt.target();
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("recipe_ref", smelt.recipeRef());
        parameters.put("recipe_fingerprint", smelt.recipeFingerprint());
        parameters.put("goal", Map.of(
                "item", smelt.goalItem(),
                "stack_policy", smelt.stackPolicy(),
                "minimum_inventory_count", smelt.minimumInventoryCount()));
        parameters.put("station", Map.of(
                "kind", smelt.stationKind(),
                "target", Map.of(
                        "dimension", target.dimension(),
                        "x", target.x(), "y", target.y(), "z", target.z()),
                "expected_state", Map.of(
                        "block", smelt.expectedState().block(),
                        "properties", smelt.expectedState().properties())));
        parameters.put("fuel", Map.of(
                "item", smelt.fuelItem(),
                "stack_policy", smelt.fuelStackPolicy()));
        parameters.put("max_smelts", smelt.maxSmelts());
        long ticks = ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts());
        var bounds = new PhaseFiveBounds(
                target.dimension(), target, target, 0,
                Math.toIntExact((ticks + 19L) / 20L), false);
        PhaseFiveRequest request = new PhaseFiveRequest(
                "smelt_items", parameters, bounds, smelt.maxSmelts(), "smelts");
        return withInventoryAim(request, position, smeltingAim);
    }

    static String containerItemsTrace(List<KnownContainerAttempt.ItemCount> items) {
        var output = new StringBuilder("container_items=");
        for (var item : items) {
            String entry = (output.length() == "container_items=".length() ? "" : ",")
                    + item.item() + ":" + item.count();
            if (output.length() + entry.length() > 252) {
                output.append(",...");
                break;
            }
            output.append(entry);
        }
        return output.toString();
    }
}
