package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One PICKUP placement and one output move at a time, confirmed from server payloads. */
final class V2CraftWork implements MinecraftV2MenuDriver.Work {
    private static final int ACK_TICKS = 100;
    private final Minecraft minecraft;
    private final ClientRecipeCatalog.ResolvedRecipe recipe;
    private final V2CraftPlan plan;
    private final int gridSize;
    private Stage stage = Stage.READY;
    private Map<String, Integer> beforeItems;
    private Map<StackFingerprint, Integer> beforeAll;
    private long dispatchRevision;
    private long dispatchTick;
    private int confirmed;
    private boolean uncertain;
    private String failure;
    private final Map<String, Integer> consumed = new LinkedHashMap<>();
    private final Map<String, Integer> producedRemainders = new LinkedHashMap<>();
    private Map<StackFingerprint, Integer> expectedAll;
    private Map<String, Integer> craftMaterials;
    private Map<String, Integer> craftRemainders;
    private int returningSlot = -1;
    private V2CraftGridPlacement placement;

    V2CraftWork(Minecraft minecraft, ClientRecipeCatalog.ResolvedRecipe recipe,
            V2CraftPlan plan, boolean inventory) {
        this.minecraft = minecraft;
        this.recipe = recipe;
        this.plan = plan;
        gridSize = inventory ? 4 : 9;
    }

    public V2BlockJobExecution.StepResult tick(AbstractContainerMenu menu, ContainerSnapshot snapshot,
            long tick, BooleanSupplier allowed) {
        if (failure != null) return V2BlockJobExecution.StepResult.FAILED;
        if (!matchesLive(menu, snapshot)) {
            return waitForAck(tick);
        }
        if (stage == Stage.PREPARING && placement != null) {
            if (!placement.tick(menu, snapshot, tick, allowed)) {
                dispatchTick = tick; // Each pickup has its own acknowledgement deadline.
                return V2BlockJobExecution.StepResult.RUNNING;
            }
        }
        if (!snapshot.carried().empty()) return waitForAck(tick);
        if (stage == Stage.COLLECTING) {
            reconcile(menu, snapshot);
            if (stage == Stage.COLLECTING) return waitForAck(tick);
        }
        if (stage == Stage.RETURNING) return returnRemainders(menu, snapshot, tick, allowed);
        if (confirmed == plan.crafts()) return V2BlockJobExecution.StepResult.CONFIRMED;
        if (!allowed.getAsBoolean()) return fail("craft_dispatch_denied");
        if (stage == Stage.READY) {
            if (!gridEmpty(snapshot, gridSize)) return fail("crafting_grid_not_empty");
            beforeItems = craftItems(minecraft, menu, snapshot);
            beforeAll = allItems(minecraft, menu, snapshot);
            if (V2CraftPlan.allocate(recipe.view().ingredients(), 1, beforeItems) == null) {
                return fail("insufficient_materials");
            }
            dispatchRevision = snapshot.packetLedgerRevision();
            dispatchTick = tick;
            placement = new V2CraftGridPlacement(minecraft, menu, recipe, gridSize, plan.crafts() - confirmed);
            uncertain = true;
            stage = Stage.PREPARING; // Construction is pure; dispatch starts on the next tick.
            return V2BlockJobExecution.StepResult.RUNNING;
        }
        if (snapshot.packetLedgerRevision() <= dispatchRevision) return waitForAck(tick);
        var output = snapshot.slots().getFirst();
        int units = snapshot.slots().subList(1, gridSize + 1).stream().mapToInt(StackFingerprint::count).sum();
        int required = recipe.view().ingredients().stream().mapToInt(ClientRecipeCatalog.IngredientView::countPerCraft).sum();
        if (!output.sameItem(StackFingerprint.fromServerPacket(prototype(plan.item())))
                || output.count() != plan.outputPerCraft() || units != required) {
            return waitForAck(tick);
        }
        var grid = new LinkedHashMap<String, Integer>();
        for (int index = 1; index <= gridSize; index++) {
            var stack = snapshot.slots().get(index);
            if (stack.empty()) continue;
            grid.merge(stack.itemId(), stack.count(), Math::addExact);
        }
        var allocation = V2CraftPlan.allocate(recipe.view().ingredients(), 1, grid);
        if (allocation == null || !allocation.equals(grid)) return fail("craft_materials_changed");
        var restored = new LinkedHashMap<>(allItems(minecraft, menu, snapshot));
        for (int index = 1; index <= gridSize; index++) add(restored, snapshot.slots().get(index), 1);
        if (!restored.equals(beforeAll)) return fail("craft_inventory_changed");
        var produced = new java.util.ArrayList<ItemStack>();
        produced.add(prototype(plan.item()).copyWithCount(plan.outputPerCraft()));
        craftMaterials = Map.copyOf(grid);
        craftRemainders = new LinkedHashMap<>();
        var inputs = new LinkedHashMap<StackFingerprint, Integer>();
        var remainderKeys = new LinkedHashMap<StackFingerprint, Integer>();
        for (int index = 1; index <= gridSize; index++) {
            var stack = snapshot.slots().get(index);
            if (stack.empty()) continue;
            add(inputs, stack, 1);
            var template = menu.getSlot(index).getItem().getCraftingRemainder();
            var remainder = template == null ? ItemStack.EMPTY : template.create();
            if (remainder.isEmpty()) continue;
            remainder.setCount(Math.multiplyExact(remainder.getCount(), stack.count()));
            produced.add(remainder);
            var fingerprint = StackFingerprint.fromServerPacket(remainder);
            add(remainderKeys, fingerprint, 1);
            craftRemainders.merge(fingerprint.itemId(), fingerprint.count(), Math::addExact);
        }
        if (!hasProductionCapacity(menu, produced)) return fail("craft_output_capacity");
        expectedAll = expectedProduction(beforeAll, inputs, StackFingerprint.fromServerPacket(prototype(plan.item())).withCount(1),
                plan.outputPerCraft(), remainderKeys);
        dispatchRevision = snapshot.packetLedgerRevision();
        dispatchTick = tick;
        stage = Stage.COLLECTING;
        var connection = minecraft.getConnection();
        // No local prediction: the server must publish the changed result and ingredient slots.
        connection.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                (short) 0, (byte) 0, ContainerInput.QUICK_MOVE, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
        return V2BlockJobExecution.StepResult.RUNNING;
    }

    void reconcile(AbstractContainerMenu menu, ContainerSnapshot snapshot) {
        if (stage == Stage.RETURNING && snapshot != null && snapshot.packetLedgerRevision() > dispatchRevision
                && matchesLive(menu, snapshot) && gridEmpty(snapshot, gridSize)
                && allItems(minecraft, menu, snapshot).equals(expectedAll)) {
            stage = Stage.READY;
            returningSlot = -1;
            uncertain = false;
        }
        if (stage != Stage.COLLECTING || snapshot == null
                || snapshot.packetLedgerRevision() <= dispatchRevision
                || !matchesLive(menu, snapshot) || !snapshot.carried().empty()
                || !snapshot.slots().getFirst().empty()) return;
        var after = allItems(minecraft, menu, snapshot);
        for (int index = 1; index <= gridSize; index++) add(after, snapshot.slots().get(index), 1);
        if (!after.equals(expectedAll)) return;
        craftMaterials.forEach((item, count) -> consumed.merge(item, count, Math::addExact));
        craftRemainders.forEach((item, count) -> producedRemainders.merge(item, count, Math::addExact));
        confirmed++;
        uncertain = false;
        stage = gridEmpty(snapshot, gridSize) ? Stage.READY : Stage.RETURNING;
    }

    private V2BlockJobExecution.StepResult returnRemainders(AbstractContainerMenu menu, ContainerSnapshot snapshot,
            long tick, BooleanSupplier allowed) {
        var combined = allItems(minecraft, menu, snapshot);
        for (int index = 1; index <= gridSize; index++) add(combined, snapshot.slots().get(index), 1);
        if (!snapshot.slots().getFirst().empty() || !combined.equals(expectedAll)) return waitForAck(tick);
        if (returningSlot >= 0) {
            if (snapshot.packetLedgerRevision() <= dispatchRevision || !snapshot.slots().get(returningSlot).empty()) return waitForAck(tick);
            returningSlot = -1;
            uncertain = false;
        }
        if (gridEmpty(snapshot, gridSize)) {
            stage = Stage.READY;
            return confirmed == plan.crafts() ? V2BlockJobExecution.StepResult.CONFIRMED : V2BlockJobExecution.StepResult.RUNNING;
        }
        if (!allowed.getAsBoolean()) return fail("craft_dispatch_denied");
        for (int index = 1; index <= gridSize; index++) {
            if (snapshot.slots().get(index).empty()) continue;
            if (!hasProductionCapacity(menu, List.of(menu.getSlot(index).getItem().copy()))) return fail("craft_remainder_capacity");
            returningSlot = index;
            dispatchRevision = snapshot.packetLedgerRevision();
            dispatchTick = tick;
            uncertain = true;
            var connection = minecraft.getConnection();
            connection.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                    (short) index, (byte) 0, ContainerInput.QUICK_MOVE, new Int2ObjectOpenHashMap<>(),
                    HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
            break;
        }
        return V2BlockJobExecution.StepResult.RUNNING;
    }

    static <K> Map<K, Integer> expectedProduction(Map<K, Integer> before, Map<K, Integer> inputs,
            K outputKey, int outputCount, Map<K, Integer> remainders) {
        var expected = new LinkedHashMap<>(before);
        inputs.forEach((key, count) -> {
            int remaining = expected.getOrDefault(key, 0) - count;
            if (remaining < 0) throw new IllegalArgumentException("craft input exceeds baseline");
            if (remaining == 0) expected.remove(key); else expected.put(key, remaining);
        });
        expected.merge(outputKey, outputCount, Math::addExact);
        remainders.forEach((key, count) -> expected.merge(key, count, Math::addExact));
        return Map.copyOf(expected);
    }

    @Override
    public void observeBeforeClose(AbstractContainerMenu menu, ContainerSnapshot snapshot) {
        if (placement != null) {
            if (!matchesLive(menu, snapshot)) throw new IllegalStateException("craft pickup snapshot unavailable");
            placement.release(menu, snapshot);
        }
        reconcile(menu, snapshot);
    }

    static Map<String, Integer> craftItems(Minecraft minecraft, AbstractContainerMenu menu, ContainerSnapshot snapshot) {
        var result = new LinkedHashMap<String, Integer>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            var actual = menu.getSlot(slot);
            if (actual.container != minecraft.player.getInventory() || actual.getContainerSlot() >= 36) continue;
            var stack = snapshot.slots().get(slot);
            if (!stack.empty()) {
                result.merge(stack.itemId(), stack.count(), Math::addExact);
            }
        }
        return result;
    }

    private static Map<StackFingerprint, Integer> allItems(Minecraft minecraft, AbstractContainerMenu menu, ContainerSnapshot snapshot) {
        var result = new LinkedHashMap<StackFingerprint, Integer>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).container == minecraft.player.getInventory()) add(result, snapshot.slots().get(slot), 1);
        }
        return result;
    }

    private static void add(Map<StackFingerprint, Integer> totals, StackFingerprint stack, int multiplier) {
        if (!stack.empty()) totals.merge(stack.withCount(1),
                stack.count() * multiplier, Math::addExact);
    }

    private boolean hasProductionCapacity(AbstractContainerMenu menu, List<ItemStack> production) {
        var stacks = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            if (slot.container == minecraft.player.getInventory() && slot.getContainerSlot() < 36) stacks.add(slot.getItem().copy());
        }
        return production.stream().allMatch(stack -> store(stacks, stack));
    }

    static boolean store(List<ItemStack> stacks, ItemStack output) {
        int left = output.getCount();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < stacks.size() && left > 0; i++) {
                var stack = stacks.get(i);
                if (pass == 0 && !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, output)) {
                    int add = Math.min(left, Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                    stack.grow(add);
                    left -= add;
                } else if (pass == 1 && stack.isEmpty()) {
                    int add = Math.min(left, output.getMaxStackSize());
                    stacks.set(i, output.copyWithCount(add));
                    left -= add;
                }
            }
        }
        return left == 0;
    }

    static ItemStack remainder(String item) {
        var template = prototype(item).getCraftingRemainder();
        return template == null ? ItemStack.EMPTY : template.create();
    }

    static boolean gridEmpty(ContainerSnapshot snapshot, int size) {
        return snapshot != null && snapshot.slots().size() > size
                && snapshot.carried().empty() && snapshot.slots().subList(0, size + 1).stream().allMatch(StackFingerprint::empty);
    }

    static boolean matchesLive(AbstractContainerMenu menu, ContainerSnapshot snapshot) {
        if (snapshot == null || menu.containerId != snapshot.containerId()
                || menu.slots.size() != snapshot.slots().size()
                || !StackFingerprint.fromServerPacket(menu.getCarried()).equals(snapshot.carried())) return false;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (!StackFingerprint.fromServerPacket(menu.getSlot(i).getItem()).equals(snapshot.slots().get(i))) return false;
        }
        return true;
    }

    static ItemStack prototype(String item) {
        return new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse(item)).orElseThrow().value());
    }
    static int defaultHash(String item) { return StackFingerprint.fromServerPacket(prototype(item)).itemAndComponentsHash(); }

    private V2BlockJobExecution.StepResult waitForAck(long tick) {
        return stage == Stage.READY || tick - dispatchTick <= ACK_TICKS
                ? V2BlockJobExecution.StepResult.RUNNING : fail("craft_sync_timeout");
    }
    private V2BlockJobExecution.StepResult fail(String reason) { failure = reason; return V2BlockJobExecution.StepResult.FAILED; }
    boolean dispatched() { return confirmed > 0 || placement != null && placement.dispatched(); }
    boolean hasPending() { return stage != Stage.READY; }
    long dispatchRevision() { return dispatchRevision; }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", "craft");
        result.put("confirmed_crafts", confirmed);
        result.put("confirmed_count", confirmed * plan.outputPerCraft());
        result.put("consumed", Map.copyOf(consumed));
        result.put("produced_remainders", Map.copyOf(producedRemainders));
        result.put("unconfirmed", uncertain);
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private enum Stage { READY, PREPARING, COLLECTING, RETURNING }
}
