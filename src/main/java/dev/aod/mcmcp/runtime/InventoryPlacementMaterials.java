package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver.PlacementState;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.SafeConstructionBlockPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

/** 自分の通常所持建材から、状態一意の設置identityだけを配送する。 */
final class InventoryPlacementMaterials {
    private InventoryPlacementMaterials() {}

    static Optional<PlacementState> identify(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()
                || stack.getItem().getClass() != BlockItem.class
                || !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))) {
            return Optional.empty();
        }
        var item = (BlockItem) stack.getItem();
        var state = item.getBlock().defaultBlockState();
        if (item.getBlock().getStateDefinition().getPossibleStates().size() != 1
                || state.getValues().findAny().isPresent()
                || !SafeConstructionBlockPolicy.allowsLiveState(state, false)
                || !Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
            return Optional.empty();
        }
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        String blockId = BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();
        try {
            SafeConstructionBlockPolicy.requireExpectedStateAndItem(
                    new BlockStateFingerprint(blockId, Map.of()), itemId);
        } catch (SafeConstructionBlockPolicy.UnsafeConstructionBlockException rejected) {
            return Optional.empty();
        }
        return Optional.of(new PlacementState(
                new ObservationRecord.BlockStateView(new ResourceId(blockId), Map.of()),
                new ResourceId(itemId)));
    }

    static McpRuntimePort.RuntimeReply prepare(
            Map<String, Object> state, List<ItemStack> ownStacks, DeliveredPolicyEvidenceStore store) {
        var counts = new TreeMap<String, Integer>();
        var materials = new LinkedHashMap<String, PlacementState>();
        for (ItemStack stack : ownStacks) {
            identify(stack).ifPresent(identity -> {
                String item = identity.placementItem().value();
                counts.merge(item, stack.getCount(), Math::addExact);
                materials.put(item, identity);
            });
        }
        var result = new LinkedHashMap<>(state);
        result.put("placement_materials", List.of());
        if (counts.isEmpty()) return McpRuntimePort.RuntimeReply.success(result);
        var receipt = store.preparePlacementDelivery(List.copyOf(materials.values()));
        try {
            result.put("placement_materials", counts.entrySet().stream().map(entry -> {
                var identity = materials.get(entry.getKey());
                return Map.<String, Object>of(
                        "item", entry.getKey(), "count", entry.getValue(),
                        "state", Map.of("block", identity.state().block().value(),
                                "properties", identity.state().properties()),
                        "placement_state_ref", store.preparedPlacementStateRef(receipt, identity).orElseThrow());
            }).toList());
            return McpRuntimePort.RuntimeReply.success(result,
                    new McpRuntimePort.ObservationDeliveryReceipt(receipt));
        } catch (RuntimeException failure) {
            store.abandonDelivery(receipt);
            throw failure;
        }
    }
}
