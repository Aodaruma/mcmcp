package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.navigation.CoordinateGoalPlanner;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.routine.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Splits exact input stacks, approaches an observed station, then delegates its finite transaction. */
final class MinecraftV2ProductionDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliation;
    private final ClientRecipeCatalog recipes;
    private final PhaseFivePort port;
    private final V2ProductionArguments request;
    private final DoubleSupplier distance;
    private final NavCell target;
    private final CoordinateGoalPlanner planner;
    private final MinecraftActionPrimitiveExecutor navigation;
    private MinecraftActionPrimitiveExecutor facing;
    private Map<TraversabilityEdge.Key, TraversabilityEdge> waitingEvidence;
    private int waitTicks;
    private final List<V2InventoryMoveArguments> preparation = new ArrayList<>();
    private MinecraftV2InventoryMoveDriver movingStack;
    private int prepared;
    private int smelts;
    private int fuelCount;
    private String inputItem;
    private String outputItem;
    private String failure;
    private String phase = "preparing_materials";
    private V2ProductionAttempt attempt;
    private List<Map<String, Object>> stationSlots = List.of();
    private long stationObservedTick;

    MinecraftV2ProductionDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, ClientReconciliationSignals reconciliation, ClientRecipeCatalog recipes,
            PhaseFivePort port, V2ProductionArguments request, DoubleSupplier distance) {
        this.minecraft = minecraft;
        this.sessions = sessions;
        this.observations = observations;
        this.reconciliation = reconciliation;
        this.recipes = recipes;
        this.port = port;
        this.request = request;
        this.distance = distance;
        target = new NavCell(sessions.get().dimension(), request.x(), request.y(), request.z());
        planner = new CoordinateGoalPlanner(sessions.get().worldSessionId(), target, cell -> !cell.equals(target));
        navigation = new MinecraftActionPrimitiveExecutor(McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
    }

    public void begin(long tick, BooleanSupplier allowed) {
        var player = minecraft.player;
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
            failure = "inventory_menu_unavailable";
            return;
        }
        var required = new ArrayList<Map.Entry<String, Integer>>();
        if (request.brew()) {
            inputItem = request.input().item();
            outputItem = request.output().item();
            required.add(Map.entry(request.ingredient(), 1));
            // Existing fuel in the stand may suffice. The adapter verifies this from synchronized data.
            if (countDefault(request.fuel()) > (request.fuel().equals(request.ingredient()) ? 1 : 0)) {
                required.add(Map.entry(request.fuel(), 1));
            }
            fuelCount = 1;
        } else {
            var session = sessions.get();
            recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
            var recipe = recipes.resolve(session.worldSessionId(), request.recipeRef(), request.fingerprint()).orElse(null);
            if (recipe == null || !recipe.view().supported() || recipe.cookingDurationTicks() <= 0
                    || !recipe.view().requiredScreen().equals(request.block().substring("minecraft:".length()))
                    || recipe.view().ingredients().size() != 1 || !recipe.view().result().deterministic()
                    || recipe.view().result().alternatives().size() != 1) {
                failure = "unsupported_cooking_recipe_or_station"; return;
            }
            var output = recipe.view().result().alternatives().getFirst();
            outputItem = output.item();
            if (request.count() % output.count() != 0) { failure = "smelt_count_not_exact"; return; }
            smelts = request.count() / output.count();
            if (smelts > request.maxSmelts() || smelts > 64) { failure = "smelt_limit"; return; }
            inputItem = recipe.view().ingredients().getFirst().alternatives().stream()
                    .filter(item -> countDefault(item) >= smelts).findFirst().orElse(null);
            if (inputItem == null) { failure = "insufficient_smelting_materials"; return; }
            var type = switch (request.block()) {
                case "minecraft:blast_furnace" -> RecipeType.BLASTING;
                case "minecraft:smoker" -> RecipeType.SMOKING;
                default -> RecipeType.SMELTING;
            };
            int burnTicks = V2CraftWork.prototype(request.fuel()).getBurnTime(type, minecraft.level.fuelValues());
            if (burnTicks <= 0) { failure = "invalid_fuel"; return; }
            fuelCount = Math.toIntExact(Math.ceilDiv((long) smelts * recipe.cookingDurationTicks(), burnTicks));
            if (fuelCount > request.maxFuel() || countDefault(request.fuel()) < fuelCount) {
                failure = "fuel_budget_or_inventory_insufficient"; return;
            }
            required.add(Map.entry(inputItem, smelts));
            required.add(Map.entry(request.fuel(), fuelCount));
        }
        planPreparation(required);
    }

    private int countDefault(String item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            var stack = ContainerSyncSignals.StackFingerprint.fromServerPacket(minecraft.player.getInventory().getItem(i));
            if (stack.itemId().equals(item) && stack.itemAndComponentsHash() == V2CraftWork.defaultHash(item)) count += stack.count();
        }
        return count;
    }

    private void planPreparation(List<Map.Entry<String, Integer>> required) {
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 36; i++) slots.add(minecraft.player.getInventory().getItem(i).copy());
        var reserved = new HashSet<Integer>();
        for (var material : required) {
            var prototype = V2CraftWork.prototype(material.getKey());
            boolean ingredientHotbar = request.brew() && reserved.isEmpty() && request.ingredient().equals(request.fuel());
            int destinationLimit = ingredientHotbar ? 9 : 36;
            int destination = -1;
            for (int i = 0; i < destinationLimit; i++) {
                if (!reserved.contains(i) && ItemStack.isSameItemSameComponents(slots.get(i), prototype)
                        && slots.get(i).getCount() == material.getValue()) { destination = i; break; }
            }
            if (destination >= 0) { reserved.add(destination); continue; }
            for (int i = 0; i < destinationLimit; i++) if (!reserved.contains(i) && slots.get(i).isEmpty()) { destination = i; break; }
            if (destination < 0 && ingredientHotbar) {
                int spare = java.util.stream.IntStream.range(9, 36).filter(i -> slots.get(i).isEmpty()).findFirst().orElse(-1);
                if (spare >= 0) {
                    destination = minecraft.player.getInventory().getSelectedSlot();
                    var held = slots.get(destination);
                    preparation.add(new V2InventoryMoveArguments(destination, spare,
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount(), request.maxTicks()));
                    slots.set(spare, held.copy());
                    slots.set(destination, ItemStack.EMPTY);
                }
            }
            if (destination < 0) { failure = "production_preparation_needs_empty_slot"; return; }
            int remaining = material.getValue();
            for (int source = 0; source < 36 && remaining > 0; source++) {
                if (source == destination || reserved.contains(source) || !ItemStack.isSameItemSameComponents(slots.get(source), prototype)) continue;
                int quantity = Math.min(remaining, slots.get(source).getCount());
                if (quantity == 0) continue;
                preparation.add(new V2InventoryMoveArguments(source, destination, material.getKey(), quantity, request.maxTicks()));
                slots.get(source).shrink(quantity);
                slots.set(destination, prototype.copyWithCount(material.getValue() - remaining + quantity));
                remaining -= quantity;
            }
            if (remaining != 0) { failure = "production_materials_insufficient"; return; }
            reserved.add(destination);
        }
    }

    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!allowed.getAsBoolean()) return fail("production_dispatch_denied");
        if (prepared < preparation.size()) {
            if (movingStack == null) {
                movingStack = new MinecraftV2InventoryMoveDriver(minecraft, preparation.get(prepared));
                movingStack.begin(tick, allowed);
            }
            var result = movingStack.tick(tick, allowed);
            if (result == V2OperationJobExecution.Step.FAILED) return fail("production_material_preparation_failed");
            if (result == V2OperationJobExecution.Step.CONFIRMED) { movingStack.close(); movingStack = null; prepared++; }
            return V2OperationJobExecution.Step.RUNNING;
        }
        if (attempt != null) {
            phase = "processing";
            captureStation();
            return attempt.tick(tick);
        }
        phase = "approaching_station";
        var session = sessions.get();
        var map = observations.requireAgentMap(session);
        if (navigation.active()) {
            var motion = navigation.tick(minecraft, map, LocalObservationVolume.global(), Math.max(0, distance.getAsDouble()), 1080, tick, allowed);
            if (motion.status() == MinecraftActionPrimitiveExecutor.Status.FAILED) return fail("production_approach_failed");
            if (motion.status() != MinecraftActionPrimitiveExecutor.Status.RUNNING) { navigation.close(); waitingEvidence = map.edges(); waitTicks = 0; }
            return V2OperationJobExecution.Step.RUNNING;
        }
        if (prepareStation(tick, allowed)) return V2OperationJobExecution.Step.RUNNING;
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!request.advance() || distance.getAsDouble() <= 0 || waitingEvidence != null && waitingEvidence.equals(map.edges())) {
            return ++waitTicks <= 80 ? V2OperationJobExecution.Step.RUNNING : fail("production_station_not_observed");
        }
        var plan = MinecraftV2BlockApproach.plan(planner, minecraft, observations, reconciliation,
                session, map, target, tick, allowed);
        return switch (plan.status()) {
            case KNOWN_GOAL_ROUTE, PARTIAL_WAYPOINT -> {
                navigation.beginNavigate(plan.route().orElseThrow(), 0.35); waitingEvidence = null; waitTicks = 0;
                yield V2OperationJobExecution.Step.RUNNING;
            }
            case BLOCKED, REACHED_KNOWN_GOAL -> {
                waitingEvidence = map.edges();
                yield ++waitTicks <= 80 ? V2OperationJobExecution.Step.RUNNING : fail("production_approach_unavailable");
            }
            default -> fail("production_approach_unavailable");
        };
    }

    private boolean prepareStation(long tick, BooleanSupplier allowed) {
        var session = sessions.get();
        if (facing != null) {
            var motion = facing.tick(minecraft, observations.requireAgentMap(session), LocalObservationVolume.global(), 0, 1080, tick, allowed);
            if (motion.status() != MinecraftActionPrimitiveExecutor.Status.RUNNING) { facing.close(); facing = null; }
            return true;
        }
        var player = minecraft.player;
        var pos = new BlockPos(target.x(), target.y(), target.z());
        if (!minecraft.level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, 0)) return false;
        var frame = observations.latestInternalFrame();
        if (frame.isEmpty()) return false;
        var ledger = reconciliation.bindAndSnapshot(minecraft.level, session.worldSessionId());
        var aim = V2BlockAimResolver.resolve(frame.orElseThrow(), target, null, player.getEyePosition(), session.worldSessionId(),
                tick, ledger.worldRevision(), ledger.surfaceBarrierWorldRevision(target.x(), target.y(), target.z()), id -> true);
        if (aim.isEmpty()) return false;
        var state = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(minecraft.level.getBlockState(pos));
        if (!state.blockId().equals(aim.orElseThrow().blockId())) return false;
        if (!state.blockId().equals(request.block())) { fail("production_station_changed"); return false; }
        if (!player.onGround() || player.isShiftKeyDown() || player.getDeltaMovement().horizontalDistanceSqr() > .01) return true;
        var ray = aim.orElseThrow().aim();
        if (!(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || !hit.getBlockPos().equals(pos)) {
            facing = new MinecraftActionPrimitiveExecutor(McmcpClientConfig.maxCameraDegreesPerSecond() / 20F);
            facing.beginFace(ray, 60);
            return true;
        }
        var blockTarget = new BlockTarget(target.dimension(), target.x(), target.y(), target.z());
        PhaseFiveRequest operation;
        if (request.brew()) {
            var brewing = new KnownBrewingRequest(blockTarget, request.input(), request.ingredient(), request.fuel(), request.output()).operation();
            var params = new LinkedHashMap<>(brewing.parameters());
            params.put("aim_point", Map.of("dimension", target.dimension(), "x", ray.aimX(), "y", ray.aimY(), "z", ray.aimZ()));
            operation = new PhaseFiveRequest(brewing.kind(), params, brewing.bounds(), brewing.expectedUnits(), brewing.progressUnit());
        } else {
            var params = new LinkedHashMap<String, Object>();
            params.put("recipe_ref", request.recipeRef());
            params.put("recipe_fingerprint", request.fingerprint());
            params.put("goal", Map.of("item", outputItem, "stack_policy", "default_components_only",
                    "minimum_inventory_count", countDefault(outputItem) + request.count()));
            params.put("fuel", Map.of("item", request.fuel(), "stack_policy", "default_components_only"));
            params.put("station", Map.of("kind", request.block().substring("minecraft:".length()),
                    "target", Map.of("dimension", target.dimension(), "x", target.x(), "y", target.y(), "z", target.z()),
                    "expected_state", Map.of("block", state.blockId(), "properties", state.properties())));
            params.put("max_smelts", smelts);
            params.put("aim_point", Map.of("dimension", target.dimension(), "x", ray.aimX(), "y", ray.aimY(), "z", ray.aimZ()));
            operation = new PhaseFiveRequest("smelt_items", params, new PhaseFiveBounds(target.dimension(), blockTarget, blockTarget, 0, 750, false), smelts, "smelts");
        }
        attempt = new V2ProductionAttempt(port, operation, tick + (request.brew() ? 1400 : 15000));
        return true;
    }

    private void captureStation() {
        ScreenOwnershipSignals.global().ownedSession().filter(owned -> owned.token().routineId().equals(attempt.owner())).ifPresent(owned -> {
            var snapshot = owned.serverSnapshot();
            int count = request.brew() ? 5 : 3;
            stationSlots = java.util.stream.IntStream.range(0, Math.min(count, snapshot.slots().size()))
                    .mapToObj(i -> Map.<String, Object>of("slot", i, "item", snapshot.slots().get(i).itemId(), "count", snapshot.slots().get(i).count())).toList();
            stationObservedTick = snapshot.receivedTick();
        });
    }
    public boolean allowsScreenChange() { return attempt != null; }
    public void close() {
        try { navigation.close(); }
        finally {
            try { if (facing != null) facing.close(); }
            finally {
                try { if (movingStack != null) movingStack.close(); }
                finally { if (attempt != null) { captureStation(); attempt.close(); } }
            }
        }
    }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.brew() ? "brew" : "smelt");
        result.put("requested_count", request.count());
        result.put("phase", phase);
        result.put("preparation_moves_confirmed", prepared);
        if (movingStack != null) result.put("preparation", movingStack.result());
        if (attempt != null) result.putAll(attempt.result());
        boolean confirmed = attempt != null && attempt.confirmed();
        result.put("confirmed_count", confirmed ? request.count() : 0);
        if (outputItem != null) result.put("item", outputItem);
        if (!stationSlots.isEmpty()) {
            result.put("last_station_slots", stationSlots);
            result.put("station_observed_tick", stationObservedTick);
            result.put("station_may_have_changed", true);
        }
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private V2OperationJobExecution.Step fail(String reason) { failure = reason; return V2OperationJobExecution.Step.FAILED; }
}
