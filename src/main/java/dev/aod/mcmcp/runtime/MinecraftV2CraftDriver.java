package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shares packet-confirmed grid placement between the player grid and an owned workbench. */
final class MinecraftV2CraftDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final ClientRecipeCatalog recipes;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliation;
    private final V2CraftArguments request;
    private LocalPlayer player;
    private ClientLevel level;
    private ClientRecipeCatalog.ResolvedRecipe recipe;
    private V2CraftPlan plan;
    private V2CraftWork work;
    private MinecraftV2MenuDriver menu;
    private String failure;
    private boolean closeSent;
    private long closeRevision;
    private net.minecraft.world.phys.Vec3 lastPosition;
    private double travelled;
    private String releaseWait;
    private MinecraftV2InventoryMoveDriver preparingHand;
    private boolean handPrepared;

    MinecraftV2CraftDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            ClientRecipeCatalog recipes, AgentObservations observations,
            ClientReconciliationSignals reconciliation, V2CraftArguments request) {
        this.minecraft = minecraft;
        this.sessions = sessions;
        this.recipes = recipes;
        this.observations = observations;
        this.reconciliation = reconciliation;
        this.request = request;
    }

    public void begin(long tick, BooleanSupplier allowed) {
        player = minecraft.player;
        level = minecraft.level;
        lastPosition = player.position();
        var session = sessions.get();
        recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
        recipe = recipes.resolve(session.worldSessionId(), request.recipeRef(), request.fingerprint()).orElse(null);
        if (recipe == null) { failure = "recipe_reference_expired"; return; }
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
            failure = "inventory_menu_unavailable";
            return;
        }
        var snapshot = PlayerMenuSyncSignals.global().snapshot(minecraft);
        if (!V2CraftWork.matchesLive(player.inventoryMenu, snapshot)) {
            failure = "inventory_server_snapshot_required";
            return;
        }
        plan = V2CraftPlan.create(request, recipe.view(), V2CraftWork.craftItems(minecraft, player.inventoryMenu, snapshot));
        if (plan.failure() == null && !canStoreProduction()) {
            plan = new V2CraftPlan(plan.item(), plan.outputPerCraft(), plan.crafts(), plan.produced(),
                    plan.materials(), "craft_output_capacity");
        }
        if (plan.failure() != null || request.plan()) return;
        work = new V2CraftWork(minecraft, recipe, plan, request.inventory());
        if (!request.inventory()) {
            boolean emptyHand = java.util.stream.IntStream.range(0, 9).anyMatch(i -> player.getInventory().getItem(i).isEmpty());
            if (!emptyHand) {
                int destination = java.util.stream.IntStream.range(9, 36).filter(i -> player.getInventory().getItem(i).isEmpty()).findFirst().orElse(-1);
                if (destination < 0) { failure = "craft_empty_hand_needs_inventory_space"; return; }
                int source = player.getInventory().getSelectedSlot();
                var stack = player.getInventory().getItem(source);
                preparingHand = new MinecraftV2InventoryMoveDriver(minecraft, new V2InventoryMoveArguments(source, destination,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), request.maxTicks()));
                preparingHand.begin(tick, allowed);
            }
            var block = V2BlockInteractArguments.parse(Map.of("target", "block", "x", request.x(),
                    "y", request.y(), "z", request.z(), "block", "minecraft:crafting_table",
                    "advance", request.advance(), "max_distance", request.maxDistance(), "max_ticks", request.maxTicks()), session.dimension());
            menu = new MinecraftV2MenuDriver(minecraft, sessions, observations, reconciliation,
                    new V2MenuArguments(block, "minecraft:crafting", List.of()),
                    () -> Math.max(0, request.maxDistance() - travelled)).withWork(work);
            menu.begin(tick, allowed);
        }
    }

    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (request.plan()) return V2OperationJobExecution.Step.CONFIRMED;
        if (plan == null || plan.failure() != null) return V2OperationJobExecution.Step.FAILED;
        if (minecraft.player != player || minecraft.level != level) {
            failure = "craft_context_changed";
            return V2OperationJobExecution.Step.FAILED;
        }
        var session = sessions.get();
        recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
        if (recipes.resolve(session.worldSessionId(), request.recipeRef(), request.fingerprint()).isEmpty()) {
            failure = "recipe_reference_expired";
            return V2OperationJobExecution.Step.FAILED;
        }
        travelled += player.position().distanceTo(lastPosition);
        lastPosition = player.position();
        if (!Double.isFinite(travelled) || travelled > request.maxDistance()) {
            failure = "craft_travel_limit";
            return V2OperationJobExecution.Step.FAILED;
        }
        if (preparingHand != null) {
            var step = preparingHand.tick(tick, allowed);
            if (step == V2OperationJobExecution.Step.FAILED) { failure = "craft_empty_hand_preparation_failed"; return step; }
            if (step == V2OperationJobExecution.Step.CONFIRMED) {
                preparingHand.close();
                preparingHand = null;
                handPrepared = true;
            }
            return V2OperationJobExecution.Step.RUNNING;
        }
        if (menu != null) return menu.tick(tick, allowed);
        if (player.containerMenu != player.inventoryMenu) {
            failure = "craft_menu_changed";
            return V2OperationJobExecution.Step.FAILED;
        }
        var snapshot = PlayerMenuSyncSignals.global().snapshot(minecraft);
        if (snapshot == null) {
            failure = "inventory_server_snapshot_required";
            return V2OperationJobExecution.Step.FAILED;
        }
        return switch (work.tick(player.inventoryMenu, snapshot, tick, allowed)) {
            case RUNNING -> V2OperationJobExecution.Step.RUNNING;
            case CONFIRMED -> V2OperationJobExecution.Step.CONFIRMED;
            case FAILED, SKIPPED -> V2OperationJobExecution.Step.FAILED;
        };
    }

    public boolean allowsScreenChange() { return menu != null && menu.allowsScreenChange(); }

    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.plan() ? "plan" : "execute");
        result.put("station", request.inventory() ? "inventory_2x2" : "crafting_table");
        result.put("requested_count", request.count());
        if (handPrepared) result.put("empty_hand_prepared", true);
        if (preparingHand != null) result.put("preparation", preparingHand.result());
        if (plan != null) result.put("plan", plan.toMap());
        if (request.plan() && !request.inventory()) {
            if (plan != null) {
                var stationPlan = new LinkedHashMap<>(plan.toMap());
                stationPlan.put("materials_ready", plan.failure() == null);
                stationPlan.put("executable", false);
                result.put("plan", stationPlan);
            }
            result.put("station_verified", false);
            result.put("requires", List.of("observed_reachable_crafting_table", "empty_crafting_grid"));
        }
        if (work != null) result.putAll(work.result());
        result.put("operation", request.plan() ? "plan" : "execute");
        if (menu != null && menu.result().containsKey("failure")) result.put("failure", menu.result().get("failure"));
        if (failure != null) result.put("failure", failure);
        if (releaseWait != null) result.put("release_wait", releaseWait);
        return result;
    }

    private boolean canStoreProduction() {
        var stacks = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        // Match both crafting menus' source order, including component-specific remainders.
        for (int i = 9; i < 36; i++) stacks.add(player.getInventory().getItem(i).copy());
        for (int i = 0; i < 9; i++) stacks.add(player.getInventory().getItem(i).copy());
        var output = V2CraftWork.prototype(plan.item());
        var requirements = V2CraftGridPlacement.requirements(recipe);
        for (int craft = 0; craft < plan.crafts(); craft++) {
            var available = new LinkedHashMap<String, Integer>();
            for (var stack : stacks) {
                var fingerprint = ContainerSyncSignals.StackFingerprint.fromServerPacket(stack);
                if (!fingerprint.empty()) available.merge(fingerprint.itemId(), fingerprint.count(), Math::addExact);
            }
            var materials = V2CraftGridPlacement.select(requirements, available, plan.crafts() - craft);
            if (materials == null) return false;
            var remainders = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
            for (var material : materials) {
                boolean found = false;
                for (var stack : stacks) {
                    var fingerprint = ContainerSyncSignals.StackFingerprint.fromServerPacket(stack);
                    if (stack.isEmpty() || !fingerprint.itemId().equals(material)) continue;
                    var remainder = stack.getCraftingRemainder();
                    if (remainder != null) remainders.add(remainder.create());
                    stack.shrink(1);
                    found = true;
                    break;
                }
                if (!found) return false;
            }
            if (!V2CraftWork.store(stacks, output.copyWithCount(plan.outputPerCraft()))) return false;
            for (var remainder : remainders) if (!V2CraftWork.store(stacks, remainder)) return false;
        }
        return true;
    }

    public void close() {
        if (preparingHand != null) preparingHand.close();
        if (menu != null) { menu.close(); return; }
        if (work == null || !work.dispatched() || minecraft.player != player || minecraft.level != level) return;
        if (player.containerMenu != player.inventoryMenu) throw new IllegalStateException("craft inventory ownership changed");
        var snapshot = PlayerMenuSyncSignals.global().snapshot(minecraft);
        work.observeBeforeClose(player.inventoryMenu, snapshot);
        if (V2CraftWork.gridEmpty(snapshot, 4) && V2CraftWork.matchesLive(player.inventoryMenu, snapshot)
                && (!work.hasPending() || snapshot.packetLedgerRevision() > work.dispatchRevision())
                && (!closeSent || snapshot.packetLedgerRevision() > closeRevision)) { releaseWait = null; return; }
        releaseWait = "crafting_grid_return_not_confirmed";
        if (!closeSent) {
            closeRevision = snapshot == null ? 0 : snapshot.packetLedgerRevision();
            closeSent = true;
            // Closing menu 0 returns the owned grid through vanilla server handling. Do not
            // predict it locally or repeat an uncertain close; wait for inbound empty-grid proof.
            minecraft.getConnection().send(new ServerboundContainerClosePacket(0));
        }
        throw new IllegalStateException("craft grid release awaiting server confirmation");
    }
}
