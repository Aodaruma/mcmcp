package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.routine.ExactInventoryTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static dev.aod.mcmcp.runtime.ContainerSyncSignals.*;

/** Places one craft with ordinary PICKUP, including ingredients excluded by vanilla's recipe book. */
final class V2CraftGridPlacement {
    private record Assignment(int source, int destination, ItemStack stack) { }
    private final Minecraft minecraft;
    private final List<Assignment> assignments;
    private int next;
    private ExactInventoryTransfer active;
    private ExactInventoryTransfer.CursorReturn returning;
    private long returnRevision;
    private boolean dispatched;

    V2CraftGridPlacement(Minecraft minecraft, AbstractContainerMenu menu,
            ClientRecipeCatalog.ResolvedRecipe recipe, int gridSize, int remainingCrafts) {
        this.minecraft = minecraft;
        var layout = recipe.layout();
        int side = gridSize == 4 ? 2 : 3;
        int width = recipe.view().shape() == null ? side : recipe.view().shape().width();
        var requirements = new ArrayList<ClientRecipeCatalog.IngredientView>();
        var destinations = new ArrayList<Integer>();
        for (int cell = 0; cell < layout.size(); cell++) {
            if (layout.get(cell).isEmpty()) continue;
            int destination = 1 + cell / width * side + cell % width;
            if (destination > gridSize) throw new IllegalArgumentException("craft layout exceeds grid");
            destinations.add(destination);
            requirements.add(new ClientRecipeCatalog.IngredientView(cell, 1, layout.get(cell)));
        }
        if (requirements.size() != recipe.view().ingredients().stream().mapToInt(ClientRecipeCatalog.IngredientView::countPerCraft).sum())
            throw new IllegalArgumentException("craft layout unavailable");
        var stocks = new ArrayList<ItemStack>();
        var sources = new ArrayList<Integer>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            var candidate = menu.getSlot(slot);
            if (candidate.container == minecraft.player.getInventory() && candidate.getContainerSlot() < 36) {
                stocks.add(candidate.getItem().copy());
                sources.add(slot);
            }
        }
        var available = new LinkedHashMap<String, Integer>();
        for (var stack : stocks) if (!stack.isEmpty()) available.merge(StackFingerprint.fromServerPacket(stack).itemId(), stack.getCount(), Integer::sum);
        var chosen = select(requirements, available, remainingCrafts);
        if (chosen == null) throw new IllegalArgumentException("craft materials unavailable");
        var result = new ArrayList<Assignment>();
        for (int i = 0; i < chosen.size(); i++) {
            int source = -1;
            for (int s = 0; s < stocks.size(); s++) {
                var stack = stocks.get(s);
                if (!stack.isEmpty() && chosen.get(i).equals(StackFingerprint.fromServerPacket(stack).itemId())) {
                    source = s;
                    break;
                }
            }
            if (source < 0) throw new IllegalArgumentException("craft source unavailable");
            var stack = stocks.get(source);
            if (!ExactInventoryTransfer.ordinaryPickupItem(stack.getItem().getClass()))
                throw new IllegalArgumentException("craft ingredient uses custom pickup behavior");
            result.add(new Assignment(sources.get(source), destinations.get(i), stack.copyWithCount(1)));
            stack.shrink(1);
        }
        assignments = List.copyOf(result);
    }

    static List<String> select(List<ClientRecipeCatalog.IngredientView> requirements, Map<String, Integer> stock) {
        return select(requirements, stock, 1);
    }

    static List<ClientRecipeCatalog.IngredientView> requirements(ClientRecipeCatalog.ResolvedRecipe recipe) {
        var result = new ArrayList<ClientRecipeCatalog.IngredientView>();
        for (var cell : recipe.layout()) if (!cell.isEmpty())
            result.add(new ClientRecipeCatalog.IngredientView(result.size(), 1, cell));
        return List.copyOf(result);
    }

    static List<String> select(List<ClientRecipeCatalog.IngredientView> requirements, Map<String, Integer> stock,
            int remainingCrafts) {
        if (remainingCrafts < 1) throw new IllegalArgumentException("remaining crafts must be positive");
        var available = new LinkedHashMap<>(stock);
        var chosen = new ArrayList<String>();
        for (int i = 0; i < requirements.size(); i++) {
            var future = new ArrayList<>(requirements.subList(i + 1, requirements.size()));
            if (remainingCrafts > 1) for (var requirement : requirements)
                future.add(new ClientRecipeCatalog.IngredientView(future.size(),
                        Math.multiplyExact(requirement.countPerCraft(), remainingCrafts - 1), requirement.alternatives()));
            String selected = null;
            for (String candidate : requirements.get(i).alternatives()) {
                int count = available.getOrDefault(candidate, 0);
                if (count < 1) continue;
                available.put(candidate, count - 1);
                if (V2CraftPlan.allocate(future, 1, available) != null) {
                    selected = candidate;
                    break;
                }
                available.put(candidate, count);
            }
            if (selected == null) return null;
            chosen.add(selected);
        }
        return List.copyOf(chosen);
    }

    boolean tick(AbstractContainerMenu menu, ContainerSnapshot raw, long tick, BooleanSupplier allowed) {
        var snapshot = withoutResult(raw);
        if (active != null) {
            active.confirm(snapshot, cursorRevision(menu));
            if (active.awaitingAcknowledgement()) {
                if (active.ackTimedOut(tick)) throw new IllegalStateException("craft pickup not confirmed");
                return false;
            }
            if (active.exhausted()) { active = null; next++; }
        }
        if (next == assignments.size()) return true;
        var assignment = assignments.get(next);
        if (active == null) {
            var source = menu.getSlot(assignment.source());
            var destination = menu.getSlot(assignment.destination());
            if (!ItemStack.isSameItemSameComponents(source.getItem(), assignment.stack())
                    || !source.mayPickup(minecraft.player) || !source.mayPlace(assignment.stack())
                    || !destination.mayPlace(assignment.stack()) || !destination.getItem().isEmpty())
                throw new IllegalStateException("craft source or grid changed");
            var stack = source.getItem();
            active = ExactInventoryTransfer.plan(snapshot.slots(), List.of(assignment.source()),
                    List.of(assignment.destination()), snapshot.slots().get(assignment.source()).itemId(),
                    snapshot.slots().get(assignment.source()).itemAndComponentsHash(), true, 1, 1,
                    stack.getMaxStackSize(), menu.slots.stream().map(slot -> slot.getMaxStackSize(stack)).toList()).orElseThrow();
        }
        if (!active.matchesConfirmed(snapshot)) throw new IllegalStateException("craft slots changed");
        if (allowed.getAsBoolean() && active.beginClick(snapshot, tick)) {
            dispatched = true;
            send(menu, active.next().slot(), active.next().button());
        }
        return false;
    }

    boolean dispatched() { return dispatched; }

    void release(AbstractContainerMenu menu, ContainerSnapshot raw) {
        if (active == null) return;
        var snapshot = withoutResult(raw);
        if (returning != null) {
            if (cursorRevision(menu) > returnRevision && returning.confirmed(snapshot) && active.confirmRelease(snapshot)) return;
            throw new IllegalStateException("craft cursor return awaiting confirmation");
        }
        active.confirm(snapshot, cursorRevision(menu));
        if (active.awaitingAcknowledgement()) throw new IllegalStateException("craft pickup awaiting confirmation");
        if (snapshot.carried().empty() && active.confirmRelease(snapshot)) return;
        returning = active.cursorReturn(snapshot).orElseThrow(() -> new IllegalStateException("craft cursor origin changed"));
        returnRevision = snapshot.packetLedgerRevision();
        send(menu, returning.slot(), 0);
        throw new IllegalStateException("craft cursor return awaiting confirmation");
    }

    private long cursorRevision(AbstractContainerMenu menu) {
        return menu.containerId == 0 ? PlayerMenuSyncSignals.global().cursorProofRevision(minecraft)
                : ContainerSyncSignals.global().cursorProofRevision(minecraft.level);
    }
    static ContainerSnapshot withoutResult(ContainerSnapshot snapshot) {
        var slots = new ArrayList<>(snapshot.slots());
        slots.set(0, StackFingerprint.EMPTY);
        return new ContainerSnapshot(snapshot.worldSessionId(), snapshot.containerId(), snapshot.menuTypeId(),
                snapshot.stateId(), slots, snapshot.carried(), snapshot.receivedTick(), snapshot.packetLedgerRevision());
    }
    private void send(AbstractContainerMenu menu, int slot, int button) {
        var connection = minecraft.getConnection();
        connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(menu.containerId,
                menu.getStateId(), (short) slot, (byte) button, net.minecraft.world.inventory.ContainerInput.PICKUP,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
                net.minecraft.network.HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())));
    }
}
