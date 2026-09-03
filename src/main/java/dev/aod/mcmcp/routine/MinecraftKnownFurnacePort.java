package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.ExpectedOpenToken;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Private physical-client adapter for one known Vanilla furnace-family recipe.
 *
 * <p>Every mutation is a cursor-invariant QUICK_MOVE and is dispatched once. Loading and final
 * recovery are each proven by a close/reopen full-content plus four-data checkpoint.</p>
 */
public final class MinecraftKnownFurnacePort implements PhaseFivePort {
    static final String KIND = "smelt_items";
    static final int INPUT_SLOT = 0;
    static final int FUEL_SLOT = 1;
    static final int RESULT_SLOT = 2;
    static final int PLAYER_SLOT_START = 3;
    static final int PLAYER_SLOT_END = 38;
    static final int MENU_SLOT_COUNT = 39;
    static final int DATA_LIT_REMAINING = 0;
    static final int DATA_LIT_DURATION = 1;
    static final int DATA_COOK_PROGRESS = 2;
    static final int DATA_COOK_DURATION = 3;
    static final int DATA_COUNT = 4;
    static final int MAX_INTERACTIONS = 7;

    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int CLICK_TIMEOUT_TICKS = 60;
    private static final int SMELT_SYNC_MARGIN_TICKS = 2_000;
    private static final int MAX_COOK_DURATION_TICKS = 200;
    private static final int RELEASE_TIMEOUT_TICKS = 520;
    private static final int AIM_SETTLE_MARGIN_TICKS = 20;
    private static final float CAMERA_DEGREES_PER_TICK = 90.0F / 20.0F;
    private static final float MAX_ONE_WAY_CAMERA_DEGREES = 270.0F;
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float AIM_EPSILON = 0.75F;
    private static final float ROTATION_EPSILON = 0.1F;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final MinecraftObservationService observations;
    private final ScreenOwnershipSignals screens;
    private final ContainerSyncSignals containerSignals;
    private final ClientPredictionSignals predictions;
    private final ClientRecipeCatalog recipes;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftKnownFurnacePort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            MinecraftObservationService observations,
            ScreenOwnershipSignals screens,
            ContainerSyncSignals containerSignals,
            ClientPredictionSignals predictions,
            ClientRecipeCatalog recipes) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.containerSignals = Objects.requireNonNull(containerSignals, "containerSignals");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = requireMinecraft();
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        RoutineFailure failure;
        try {
            AttemptState active = activeState(request);
            failure = active == null
                    ? initialPreflight(minecraft, session, parseRequest(request))
                    : active.releasingOrTerminal() ? null
                    : ongoingFailure(minecraft, session, active);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            failure = failure("FURNACE_REQUEST_INVALID", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", boundedReason(exception)));
        }
        return new PhaseFiveFrame(tick, packetRevision(), failure);
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = assertClientThread();
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        var attempt = new PhaseFiveAttempt(
                routineId, request.kind(), tick, packetRevision(), hardDeadlineClientTick,
                Map.of("verification", "furnace_close_reopen_full_content_and_data",
                        "container_click_retry", "none",
                        "maximum_interactions", MAX_INTERACTIONS));
        final FurnaceRequest smelt;
        try {
            smelt = parseRequest(request);
        } catch (IllegalArgumentException exception) {
            AttemptState rejected = AttemptState.rejected(request);
            rejected.latchFailure(failure("FURNACE_REQUEST_INVALID",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", boundedReason(exception))));
            attempts.put(attempt, rejected);
            return attempt;
        }
        AttemptState state = new AttemptState(request, smelt);
        attempts.put(attempt, state);
        RoutineFailure preflight = initialPreflight(minecraft, session, smelt);
        if (preflight != null) {
            state.latchFailure(preflight);
            return attempt;
        }
        try {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            state.recipe = resolveRecipe(minecraft, session, tick, smelt);
            state.prepareStaticPlan();
            if (!targetReadyForOpen(minecraft, smelt, true)) {
                throw new IllegalStateException("furnace target changed during admission");
            }
            state.admittedTargetState = fingerprint(
                    Objects.requireNonNull(minecraft.level).getBlockState(
                            blockPos(smelt.target())));
            state.baseline = PlayerBaseline.capture(player, Objects.requireNonNull(session));
            state.playerIdentity = player;
            state.levelIdentity = minecraft.level;
            state.connectionIdentity = minecraft.getConnection();
            state.ownership = ViewSlotLease.acquire(player);
            state.openHand = chooseOpenHand(player).orElseThrow(() ->
                    new IllegalStateException("no side-effect-free normal-use hand is available"));
            state.ownership.selectOpenHand(minecraft, state.openHand);
            state.stage = Stage.AIMING_INITIAL;
            state.stageDeadlineClientTick = boundedDeadline(tick, aimTimeoutTicks());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            state.latchFailure(failure("FURNACE_PREPARATION_FAILED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", boundedReason(exception))));
        }
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        Minecraft minecraft = assertClientThread();
        AttemptState state = requireAttempt(attempt);
        if (state.terminal()) return;
        if (state.releasing()) {
            maintainTerminalRelease(attempt, state);
            return;
        }
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        RoutineFailure safety = ongoingFailure(minecraft, session, state);
        if (safety != null) {
            state.latchFailure(safety);
            return;
        }
        if (tick >= attempt.hardDeadlineClientTick()) {
            state.latchInconclusive("furnace_action_hard_deadline_exceeded");
            return;
        }
        if (tick > state.stageDeadlineClientTick) {
            state.latchInconclusive("furnace_stage_deadline_exceeded");
            return;
        }
        switch (state.stage) {
            case AIMING_INITIAL, AIMING_LOADED_READBACK, AIMING_FINAL_READBACK ->
                    maintainAim(attempt, state);
            case OPENING_INITIAL -> acceptInitialSnapshot(attempt, state);
            case LOAD_INPUT -> dispatchLoad(attempt, state, true);
            case LOAD_FUEL -> dispatchLoad(attempt, state, false);
            case CLOSING_LOADED_CHECKPOINT -> closeOwnedScreen(
                    attempt, state, Stage.AWAITING_LOADED_CLOSE);
            case AWAITING_LOADED_CLOSE, AWAITING_FINAL_CHECKPOINT_CLOSE,
                    AWAITING_FINAL_CLOSE -> maintainClose(state);
            case OPENING_LOADED_READBACK -> acceptLoadedSnapshot(attempt, state);
            case AWAITING_SMELT_COMPLETE -> maintainSmeltComplete(attempt, state);
            case UNLOAD_FUEL -> dispatchFuelRecovery(attempt, state);
            case UNLOAD_RESULT -> dispatchResult(attempt, state);
            case CLOSING_FINAL_CHECKPOINT -> closeOwnedScreen(
                    attempt, state, Stage.AWAITING_FINAL_CHECKPOINT_CLOSE);
            case OPENING_FINAL_READBACK -> acceptFinalSnapshot(attempt, state);
            case RELEASING -> maintainTerminalRelease(attempt, state);
            case TERMINAL -> { }
        }
    }

    @Override
    public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
        AttemptState state = requireAttempt(attempt);
        Map<String, Object> basis = state.basis();
        if (state.result != null) {
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), currentTick(), packetRevision(), state.result, basis);
        }
        if (state.failure != null) {
            return new PhaseFiveEvidence.Failed(
                    attempt.attemptId(), currentTick(), packetRevision(), state.failure, basis);
        }
        if (state.inconclusive != null) {
            return new PhaseFiveEvidence.Inconclusive(
                    attempt.attemptId(), currentTick(), packetRevision(),
                    state.inconclusive.certainty(), state.inconclusive.reason(), basis);
        }
        return new PhaseFiveEvidence.Pending(
                attempt.attemptId(), currentTick(), packetRevision(), basis);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        AttemptState state = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null || state.releaseConfirmed) return;
        state.latchInconclusive("furnace_release_requested");
        maintainTerminalRelease(attempt, state);
        if (!state.releaseConfirmed) {
            throw new IllegalStateException("furnace release remains unconfirmed");
        }
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        var iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            var state = iterator.next().getValue();
            if (state.request == request) {
                if (!state.releaseConfirmed) {
                    throw new IllegalStateException(
                            "furnace request cannot retire before confirmed release");
                }
                iterator.remove();
            }
        }
    }

    public void clearSession() {
        for (PhaseFiveAttempt attempt : List.copyOf(attempts.keySet())) release(attempt);
        attempts.clear();
    }

    private AttemptState activeState(PhaseFiveRequest request) {
        return attempts.values().stream()
                .filter(state -> state.request == request)
                .findFirst().orElse(null);
    }

    private void maintainAim(PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = assertClientThread();
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        Optional<OpenHandPlan> openHand = chooseOpenHand(player);
        if (openHand.isEmpty()) {
            state.latchFailure(failure("FURNACE_SAFE_OPEN_HAND_UNAVAILABLE",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of()));
            return;
        }
        state.openHand = openHand.orElseThrow();
        state.ownership.selectOpenHand(minecraft, state.openHand);
        BlockTarget target = state.smelt.target();
        Vec3 point = state.smelt.aimPoint();
        state.ownership.turnToward(minecraft, point);
        if (state.ownership.aligned(minecraft, point)
                && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(blockPos(target))
                && targetReadyForOpen(minecraft, state,
                        state.stage == Stage.AIMING_INITIAL)) {
            dispatchExpectedOpen(attempt, state);
        }
    }

    private void dispatchExpectedOpen(PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = assertClientThread();
        WorldSessionTracker.Snapshot session = requireSession();
        boolean initial = state.stage == Stage.AIMING_INITIAL;
        if (!targetReadyForOpen(minecraft, state, initial)) return;
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        if (state.openHand == null || !state.openHand.ready(player)) {
            state.latchFailure(failure("FURNACE_SAFE_OPEN_HAND_CHANGED",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of()));
            return;
        }
        if (!reserveInteraction(state)) return;
        long remaining = Math.max(1L,
                attempt.hardDeadlineClientTick() - session.clientTick());
        long window = Math.min(OPEN_TIMEOUT_TICKS, remaining);
        long now = screens.currentTick();
        long screenDeadline = now > Long.MAX_VALUE - window
                ? Long.MAX_VALUE : now + window;
        BlockStateFingerprint live = fingerprint(
                Objects.requireNonNull(minecraft.level).getBlockState(
                        blockPos(state.smelt.target())));
        var token = new ExpectedOpenToken(
                session.worldSessionId(), attempt.attemptId(), state.targetIdentity(),
                live.toString(), state.smelt.family().menuType(), screenDeadline);
        if (!screens.beginExpectedOpen(token)) {
            state.latchFailure(failure("FURNACE_EXPECTED_OPEN_REJECTED",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of()));
            return;
        }
        ClientPredictionSignals.PredictionAttempt prediction;
        int sequenceBefore;
        try {
            prediction = predictions.begin(
                    Objects.requireNonNull(minecraft.level),
                    blockPos(state.smelt.target()), session.clientTick());
            sequenceBefore = prediction.sequenceBeforePrediction();
            state.openPrediction = prediction;
        } catch (ClientPredictionSignals.PredictionBridgeException failure) {
            screens.cancelRoutine(attempt.attemptId());
            state.latchFailure(failure("FURNACE_OPEN_PREDICTION_UNAVAILABLE",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of()));
            return;
        }
        state.openCount++;
        final boolean consumed;
        try {
            consumed = Objects.requireNonNull(minecraft.gameMode)
                    .useItemOn(player, state.openHand.hand(), exactHit(minecraft, state.smelt.target()))
                    .consumesAction();
            if (prediction.captureIssuedPredictions() != sequenceBefore + 1) {
                throw new ClientPredictionSignals.PredictionBridgeException(
                        "block-use prediction sequence did not advance exactly once");
            }
        } catch (RuntimeException | LinkageError dispatchFailure) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            state.latchFailure(failure("FURNACE_OPEN_PREDICTION_INCOMPATIBLE",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.USER,
                    Map.of(), Map.of()));
            return;
        }
        if (!consumed) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            state.latchFailure(failure("FURNACE_NORMAL_USE_REJECTED",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of()));
            return;
        }
        state.stage = switch (state.stage) {
            case AIMING_INITIAL -> Stage.OPENING_INITIAL;
            case AIMING_LOADED_READBACK -> Stage.OPENING_LOADED_READBACK;
            case AIMING_FINAL_READBACK -> Stage.OPENING_FINAL_READBACK;
            default -> throw new IllegalStateException("furnace opened outside aim stage");
        };
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), OPEN_TIMEOUT_TICKS);
    }

    private void acceptInitialSnapshot(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<FurnaceView> optional = furnaceView(attempt, state);
        if (optional.isEmpty()) return;
        FurnaceView view = optional.orElseThrow();
        closeOpenPrediction(state);
        if (!stationSlotsEmpty(view.snapshot().slots())
                || !initialDataReady(view.data())
                || !view.snapshot().carried().empty()) {
            state.latchFailure(failure("FURNACE_NOT_INITIAL_EMPTY",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("station_slots", "empty", "data", List.of(0, 0, 0, 0)),
                    Map.of()));
            return;
        }
        if (!liveMenuMatches(view.snapshot(), view.menu())) return;
        try {
            WorldSessionTracker.Snapshot session = requireSession();
            state.recipe = resolveRecipe(
                    requireMinecraft(), session, session.clientTick(), state.smelt);
            state.prepareStaticPlan();
        } catch (IllegalArgumentException exception) {
            state.latchFailure(failure("FURNACE_RECIPE_CHANGED_BEFORE_LOAD",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("known_recipe", true), Map.of()));
            return;
        }
        ItemStack fuel = defaultItemStack(state.fuelKey.itemId());
        ClientLevel level = Objects.requireNonNull(requireMinecraft().level);
        RecipePropertySet acceptedInputs = level.recipeAccess().propertySet(switch (
                state.smelt.family()) {
            case FURNACE -> RecipePropertySet.FURNACE_INPUT;
            case BLAST_FURNACE -> RecipePropertySet.BLAST_FURNACE_INPUT;
            case SMOKER -> RecipePropertySet.SMOKER_INPUT;
        });
        RecipeType<?> recipeType = switch (state.smelt.family()) {
            case FURNACE -> RecipeType.SMELTING;
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
        };
        int fuelBurnTicks = fuel.getBurnTime(recipeType, level.fuelValues());
        if (fuelBurnTicks <= 0 || acceptedInputs.test(fuel)) {
            state.latchFailure(failure("FURNACE_QUICK_MOVE_ROUTE_AMBIGUOUS",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("fuel_routes_only_to_fuel", true), Map.of()));
            return;
        }
        int fuelItemsRequired = fuelItemsRequired(
                state.smelt.maxSmelts(), state.recipe.cookingDurationTicks(), fuelBurnTicks);
        Map<StackKey, Integer> baseline = inventoryMultiset(
                view.snapshot().slots(), playerSlots());
        Optional<SourcePlan> sourcePlan = chooseSources(
                view.snapshot().slots(), state.ingredientKeys, state.fuelKey,
                state.smelt.maxSmelts(), fuelItemsRequired);
        if (sourcePlan.isEmpty()) {
            state.latchFailure(failure("FURNACE_EXACT_STACK_SOURCES_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("ingredient_source_count", state.smelt.maxSmelts(),
                            "minimum_fuel_source_count", fuelItemsRequired),
                    Map.of()));
            return;
        }
        SourcePlan sources = sourcePlan.orElseThrow();
        ItemStack ingredient = defaultItemStack(sources.ingredientKey().itemId());
        if (!acceptedInputs.test(ingredient)) {
            state.latchFailure(failure("FURNACE_QUICK_MOVE_ROUTE_AMBIGUOUS",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("ingredient_routes_to_input", true,
                            "fuel_routes_only_to_fuel", true), Map.of()));
            return;
        }
        try {
            state.expectedLoadedInventory = expectedInventoryAfterLoad(
                    baseline, sources.ingredientKey(), state.smelt.maxSmelts(),
                    state.fuelKey, sources.fuelCount());
            state.expectedFinalInventory = expectedInventoryAfterSmelt(
                    baseline, sources.ingredientKey(), state.smelt.maxSmelts(),
                    state.fuelKey, fuelItemsRequired,
                    state.outputKey, state.outputTotal);
        } catch (IllegalArgumentException exception) {
            state.latchFailure(failure("FURNACE_RESOURCES_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        int before = baseline.getOrDefault(state.outputKey, 0);
        if (before + state.outputTotal - state.outputCount
                        >= state.smelt.minimumInventoryCount()
                || before + state.outputTotal < state.smelt.minimumInventoryCount()) {
            state.latchFailure(failure("FURNACE_GOAL_NOT_EXACT_SMELT_COUNT",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("smelts_required", state.smelt.maxSmelts()),
                    Map.of("inventory_count", before)));
            return;
        }
        state.ingredientKey = sources.ingredientKey();
        state.inputSourceSlot = sources.ingredientSlot();
        state.fuelSourceSlot = sources.fuelSlot();
        state.inputSourceStack = view.snapshot().slots().get(state.inputSourceSlot);
        state.fuelSourceStack = view.snapshot().slots().get(state.fuelSourceSlot);
        state.fuelLoadedCount = sources.fuelCount();
        state.fuelConsumedCount = fuelItemsRequired;
        state.inventoryBeforeOutput = before;
        state.menuIdentity = view.menu();
        state.stage = Stage.LOAD_INPUT;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void dispatchLoad(
            PhaseFiveAttempt attempt, AttemptState state, boolean ingredient) {
        AbstractFurnaceMenu menu = ownedMenu(attempt, state);
        if (menu == null) return;
        int slot = ingredient ? state.inputSourceSlot : state.fuelSourceSlot;
        ContainerSyncSignals.StackFingerprint expected = ingredient
                ? state.inputSourceStack : state.fuelSourceStack;
        if (!menu.getCarried().isEmpty()
                || !ContainerSyncSignals.StackFingerprint.fromServerPacket(
                        menu.slots.get(slot).getItem()).equals(expected)) {
            state.latchFailure(failure("FURNACE_LOAD_SOURCE_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("planned_full_stack_source", true), Map.of()));
            return;
        }
        if (!dispatchQuickMove(state, menu, slot)) return;
        state.stage = ingredient ? Stage.LOAD_FUEL : Stage.CLOSING_LOADED_CHECKPOINT;
    }

    private void acceptLoadedSnapshot(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<FurnaceView> optional = furnaceView(attempt, state);
        if (optional.isEmpty()) return;
        FurnaceView view = optional.orElseThrow();
        closeOpenPrediction(state);
        List<ContainerSyncSignals.StackFingerprint> slots = view.snapshot().slots();
        int completed = completedSmelts(
                slots.get(RESULT_SLOT), state.outputKey,
                state.outputCount, state.smelt.maxSmelts());
        int fuelRemaining = slots.get(FUEL_SLOT).empty()
                ? 0 : slots.get(FUEL_SLOT).count();
        if (completed < 0
                || !matches(slots.get(INPUT_SLOT), state.ingredientKey,
                        state.smelt.maxSmelts() - completed)
                || !matches(slots.get(FUEL_SLOT), state.fuelKey, fuelRemaining)
                || fuelRemaining < state.fuelLoadedCount - state.fuelConsumedCount
                || fuelRemaining >= state.fuelLoadedCount
                || !view.snapshot().carried().empty()
                || !inventoryReadbackMatches(state.expectedLoadedInventory,
                        inventoryMultiset(slots, playerSlots()))) {
            state.latchFailure(failure("FURNACE_LOADED_READBACK_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("loaded_full_content", true, "cursor", "empty"), Map.of()));
            return;
        }
        state.loadedReadbackConfirmed = true;
        state.smeltStarted = true;
        if (completed == state.smelt.maxSmelts()) {
            if (!finalDataReady(view.data())) return;
            state.smeltCompleted = true;
            state.fuelRecoveryStack = slots.get(FUEL_SLOT);
            state.resultSourceStack = slots.get(RESULT_SLOT);
            state.menuIdentity = view.menu();
            state.stage = state.fuelRecoveryStack.empty()
                    ? Stage.UNLOAD_RESULT : Stage.UNLOAD_FUEL;
            state.stageDeadlineClientTick = boundedDeadline(
                    currentTick(), CLICK_TIMEOUT_TICKS);
            return;
        }
        if (!smeltStarted(view.data())) return;
        state.startDataRevision = view.maximumDataRevision();
        state.menuIdentity = view.menu();
        state.stage = Stage.AWAITING_SMELT_COMPLETE;
        state.stageDeadlineClientTick = boundedDeadline(
                currentTick(), smeltTimeoutTicks(state));
    }

    private void maintainSmeltComplete(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<FurnaceView> optional = furnaceView(attempt, state);
        if (optional.isEmpty()) return;
        FurnaceView view = optional.orElseThrow();
        List<ContainerSyncSignals.StackFingerprint> slots = view.snapshot().slots();
        if (!state.loadedReadbackConfirmed
                || view.maximumDataRevision() <= state.startDataRevision
                || !slots.get(INPUT_SLOT).empty()
                || !matches(slots.get(FUEL_SLOT), state.fuelKey,
                        state.fuelLoadedCount - state.fuelConsumedCount)
                || !matches(slots.get(RESULT_SLOT), state.outputKey, state.outputTotal)
                || !view.snapshot().carried().empty()
                || view.data().get(DATA_COOK_PROGRESS).value() != 0) {
            return;
        }
        state.smeltCompleted = true;
        state.fuelRecoveryStack = slots.get(FUEL_SLOT);
        state.resultSourceStack = slots.get(RESULT_SLOT);
        state.menuIdentity = view.menu();
        state.stage = state.fuelRecoveryStack.empty()
                ? Stage.UNLOAD_RESULT : Stage.UNLOAD_FUEL;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void dispatchFuelRecovery(PhaseFiveAttempt attempt, AttemptState state) {
        AbstractFurnaceMenu menu = ownedMenu(attempt, state);
        if (menu == null) return;
        if (state.fuelRecoveryStack == null
                || state.fuelRecoveryStack.empty()
                || !menu.getCarried().isEmpty()
                || !ContainerSyncSignals.StackFingerprint.fromServerPacket(
                        menu.slots.get(FUEL_SLOT).getItem())
                        .equals(state.fuelRecoveryStack)) {
            state.latchFailure(failure("FURNACE_FUEL_RECOVERY_SOURCE_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("planned_remaining_fuel", true), Map.of()));
            return;
        }
        if (!dispatchQuickMove(state, menu, FUEL_SLOT)) return;
        state.fuelRecovered = true;
        state.stage = Stage.UNLOAD_RESULT;
    }

    private void dispatchResult(PhaseFiveAttempt attempt, AttemptState state) {
        AbstractFurnaceMenu menu = ownedMenu(attempt, state);
        if (menu == null) return;
        if (!menu.getCarried().isEmpty()
                || !ContainerSyncSignals.StackFingerprint.fromServerPacket(
                        menu.slots.get(RESULT_SLOT).getItem())
                        .equals(state.resultSourceStack)) {
            state.latchFailure(failure("FURNACE_RESULT_SOURCE_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("planned_result", true), Map.of()));
            return;
        }
        if (!dispatchQuickMove(state, menu, RESULT_SLOT)) return;
        state.resultRecovered = true;
        state.stage = Stage.CLOSING_FINAL_CHECKPOINT;
    }

    private void acceptFinalSnapshot(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<FurnaceView> optional = furnaceView(attempt, state);
        if (optional.isEmpty()) return;
        FurnaceView view = optional.orElseThrow();
        closeOpenPrediction(state);
        Map<StackKey, Integer> actual = inventoryMultiset(
                view.snapshot().slots(), playerSlots());
        int outputAfter = actual.getOrDefault(state.outputKey, 0);
        if (!stationSlotsEmpty(view.snapshot().slots())
                || !finalDataReady(view.data())
                || !view.snapshot().carried().empty()
                || !inventoryReadbackMatches(state.expectedFinalInventory, actual)
                || outputAfter - state.inventoryBeforeOutput != state.outputTotal
                || outputAfter < state.smelt.minimumInventoryCount()) {
            state.latchFailure(failure("FURNACE_FINAL_READBACK_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("exact_inventory_delta", true,
                            "station_empty", true, "cursor", "empty"), Map.of()));
            return;
        }
        state.finalReadbackConfirmed = true;
        state.pendingResultBasis = Map.of(
                "smelted_items", state.outputTotal,
                "smelts_completed", state.smelt.maxSmelts(),
                "remaining_fuel_recovered", state.fuelRecovered
                        || state.fuelLoadedCount == state.fuelConsumedCount,
                "loaded_full_readback", true,
                "smelt_started", state.smeltStarted,
                "smelt_completed", state.smeltCompleted,
                "fresh_full_readback", true,
                "inventory_delta_exact", true,
                "cursor_empty", true);
        closeOwnedScreen(attempt, state, Stage.AWAITING_FINAL_CLOSE);
    }

    private void closeOwnedScreen(
            PhaseFiveAttempt attempt, AttemptState state, Stage nextStage) {
        ScreenOwnershipSignals.CleanupDecision decision =
                screens.cancelRoutine(attempt.attemptId());
        if (!decision.authorityMatched() || !decision.closeMenuBestEffort()) {
            state.latchFailure(failure("FURNACE_CLOSE_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        if (!decision.serverCursorEmpty()) {
            state.latchFailure(failure("FURNACE_CURSOR_NOT_EMPTY",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("cursor", "empty"), Map.of()));
            return;
        }
        state.serverCursorReleaseConfirmed = true;
        closeOwnedMenuClient(requireMinecraft(), decision);
        state.stage = nextStage;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), OPEN_TIMEOUT_TICKS);
    }

    private void maintainClose(AttemptState state) {
        Minecraft minecraft = requireMinecraft();
        if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE
                || minecraft.gui.screen() != null
                || minecraft.player == null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
            return;
        }
        state.stage = switch (state.stage) {
            case AWAITING_LOADED_CLOSE -> Stage.AIMING_LOADED_READBACK;
            case AWAITING_FINAL_CHECKPOINT_CLOSE -> Stage.AIMING_FINAL_READBACK;
            case AWAITING_FINAL_CLOSE -> {
                state.latchSuccess();
                yield state.stage;
            }
            default -> throw new IllegalStateException("furnace close outside close stage");
        };
        if (!state.releasing()) {
            state.stageDeadlineClientTick = boundedDeadline(currentTick(), aimTimeoutTicks());
        }
    }

    /** Cleanup only; this path never resumes furnace gameplay. */
    private void maintainTerminalRelease(PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = assertClientThread();
        long tick = currentTick();
        if (state.releaseStartedTick < 0L) {
            state.releaseStartedTick = tick;
            state.releaseDeadlineTick = boundedDeadline(tick, RELEASE_TIMEOUT_TICKS);
        }
        try {
            ScreenOwnershipSignals.Snapshot snapshot = screens.snapshot();
            state.observeServerCursorProof(snapshot);
            if (state.ownershipContextLost(minecraft)) {
                screens.releaseRoutineOnIdentityLoss(attempt.attemptId());
                closeOpenPrediction(state);
                state.serverCursorProofFresh = true;
                state.serverCursorReleaseConfirmed = true;
                state.screenReleaseConfirmed = true;
                boolean viewReleased = state.ownership == null
                        || state.ownership.releaseStep(minecraft, tick);
                if (viewReleased) {
                    state.releaseConfirmed = true;
                    state.publishLatchedTerminal();
                }
                return;
            }
            if (tick > state.releaseDeadlineTick) {
                state.releaseFault = true;
                return;
            }
            ScreenOwnershipSignals.Phase phase = snapshot.phase();
            if (phase == ScreenOwnershipSignals.Phase.OWNED) {
                closeOpenPrediction(state);
                state.screenOwnedObserved = true;
                if (!state.serverCursorProofFresh) return;
                if (!exactLiveFurnaceContext(minecraft, state.smelt.family())
                        || minecraft.player == null
                        || !minecraft.player.containerMenu.getCarried().isEmpty()) {
                    state.releaseFault = true;
                    return;
                }
                state.serverCursorReleaseConfirmed = true;
                ScreenOwnershipSignals.CleanupDecision decision =
                        screens.cancelRoutine(attempt.attemptId());
                if (!decision.authorityMatched() || !decision.closeMenuBestEffort()
                        || !decision.serverCursorEmpty()) {
                    state.releaseFault = true;
                    return;
                }
                closeOwnedMenuClient(minecraft, decision);
                return;
            }
            if (phase == ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_SCREEN
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT
                    || phase == ScreenOwnershipSignals.Phase.FAILED) {
                ScreenOwnershipSignals.CleanupDecision decision =
                        cancelScreenAuthority(attempt, state);
                if (!decision.authorityMatched()) {
                    state.releaseFault = true;
                    return;
                }
                if (decision.closeMenuBestEffort()) {
                    if (state.screenOwnedObserved && !decision.serverCursorEmpty()) {
                        state.releaseFault = true;
                        return;
                    }
                    if (decision.serverCursorEmpty() || !state.screenOwnedObserved) {
                        state.serverCursorReleaseConfirmed = true;
                    }
                    closeOwnedMenuClient(minecraft, decision);
                }
                return;
            }
            if (phase == ScreenOwnershipSignals.Phase.CLOSING) return;
            if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) return;
            if (state.screenOwnedObserved
                    && (minecraft.gui.screen() != null
                            || minecraft.player == null
                            || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                            || !minecraft.player.inventoryMenu.getCarried().isEmpty())) {
                return;
            }
            if (!state.screenOwnedObserved) state.serverCursorReleaseConfirmed = true;
            closeOpenPrediction(state);
            state.screenReleaseConfirmed = true;
            boolean viewReleased = state.ownership == null
                    || state.ownership.releaseStep(minecraft, tick);
            if (viewReleased && state.serverCursorReleaseConfirmed) {
                state.releaseConfirmed = true;
                state.publishLatchedTerminal();
            }
        } catch (RuntimeException | LinkageError releaseFailure) {
            state.releaseFault = true;
        }
    }

    private ScreenOwnershipSignals.CleanupDecision cancelScreenAuthority(
            PhaseFiveAttempt attempt, AttemptState state) {
        return state.openPrediction == null
                ? screens.cancelRoutine(attempt.attemptId())
                : screens.cancelRoutineAfterPredictedUse(
                        attempt.attemptId(), causalBarrierStatus(state));
    }

    private static ScreenOwnershipSignals.CausalBarrierStatus causalBarrierStatus(
            AttemptState state) {
        if (state.openPrediction == null) {
            return ScreenOwnershipSignals.CausalBarrierStatus.NOT_REQUIRED;
        }
        return switch (state.openPrediction.acknowledgement().status()) {
            case ACKNOWLEDGED -> ScreenOwnershipSignals.CausalBarrierStatus.ACKNOWLEDGED;
            case IDENTITY_RELEASED ->
                    ScreenOwnershipSignals.CausalBarrierStatus.IDENTITY_RELEASED;
            case NO_PREDICTION, WAITING_ACK ->
                    ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK;
            case INCOMPATIBLE, CLOSED ->
                    ScreenOwnershipSignals.CausalBarrierStatus.INCOMPATIBLE;
        };
    }

    private static void closeOpenPrediction(AttemptState state) {
        if (state.openPrediction == null) return;
        state.openPrediction.close();
        state.openPrediction = null;
    }

    private boolean dispatchQuickMove(
            AttemptState state, AbstractFurnaceMenu menu, int slot) {
        if (state.openCount + state.containerClicks >= MAX_INTERACTIONS) {
            state.latchFailure(failure("FURNACE_INTERACTION_LIMIT",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE,
                    Map.of("maximum_interactions", MAX_INTERACTIONS), Map.of()));
            return false;
        }
        Minecraft minecraft = requireMinecraft();
        if (screens.ownedSession().isEmpty()
                || minecraft.player == null
                || minecraft.player.containerMenu != menu
                || menu != state.menuIdentity) {
            state.latchFailure(failure("FURNACE_CLICK_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return false;
        }
        state.containerClicks++;
        Objects.requireNonNull(minecraft.gameMode).handleContainerInput(
                menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
        return true;
    }

    private AbstractFurnaceMenu ownedMenu(
            PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = requireMinecraft();
        Optional<ScreenOwnershipSignals.OwnedScreenSession> owned = screens.ownedSession();
        if (owned.isEmpty()
                || !attempt.attemptId().equals(owned.orElseThrow().token().routineId())
                || !exactLiveFurnaceContext(minecraft, state.smelt.family())
                || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AbstractFurnaceMenu menu)
                || menu != state.menuIdentity
                || !exactFurnaceLayout(menu, minecraft.player.getInventory())) {
            state.latchFailure(failure("FURNACE_CLICK_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return null;
        }
        return menu;
    }

    private Optional<FurnaceView> furnaceView(
            PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = requireMinecraft();
        Optional<ScreenOwnershipSignals.OwnedScreenSession> optional = screens.ownedSession();
        if (optional.isEmpty() || minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        ScreenOwnershipSignals.OwnedScreenSession owned = optional.orElseThrow();
        FurnaceFamily family = state.smelt.family();
        if (!attempt.attemptId().equals(owned.token().routineId())
                || !state.targetIdentity().equals(owned.token().targetIdentity())
                || !family.menuType().equals(owned.token().menuTypeId())) {
            state.latchFailure(failure("FURNACE_SCREEN_AUTHORITY_MISMATCH",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of()));
            return Optional.empty();
        }
        if (!exactLiveFurnaceContext(minecraft, family)
                || !(minecraft.player.containerMenu instanceof AbstractFurnaceMenu menu)
                || !exactFurnaceLayout(menu, minecraft.player.getInventory())) {
            state.latchFailure(failure("FURNACE_MENU_LAYOUT_MISMATCH",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", family.menuType(), "slots", MENU_SLOT_COUNT),
                    Map.of()));
            return Optional.empty();
        }
        ContainerSyncSignals.ContainerSnapshot snapshot = owned.serverSnapshot();
        if (snapshot.containerId() != menu.containerId
                || !family.menuType().equals(snapshot.menuTypeId())
                || snapshot.slots().size() != MENU_SLOT_COUNT) {
            state.latchFailure(failure("FURNACE_PACKET_LAYOUT_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", family.menuType(), "slots", MENU_SLOT_COUNT),
                    Map.of()));
            return Optional.empty();
        }
        Optional<ContainerSyncSignals.Snapshot> ledgerOptional =
                containerSignals.snapshot(minecraft.level);
        if (ledgerOptional.isEmpty()) return Optional.empty();
        ContainerSyncSignals.Snapshot ledger = ledgerOptional.orElseThrow();
        if (!ledger.sameSession(owned.token().worldSessionId())
                || ledger.container() == null
                || ledger.container().containerId() != menu.containerId
                || !family.menuType().equals(ledger.container().menuTypeId())) {
            state.latchFailure(failure("FURNACE_PACKET_SESSION_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return Optional.empty();
        }
        var data = new ArrayList<ContainerSyncSignals.ContainerDataEvidence>(DATA_COUNT);
        for (int index = 0; index < DATA_COUNT; index++) {
            ContainerSyncSignals.ContainerDataEvidence evidence = ledger.data().get(index);
            if (!freshDataForOpen(
                    ledger.lastOpenScreen(), evidence, menu.containerId, family.menuType())) {
                return Optional.empty();
            }
            data.add(evidence);
        }
        if (!liveMenuMatches(snapshot, menu) || !liveDataMatches(menu, data)) {
            return Optional.empty();
        }
        return Optional.of(new FurnaceView(menu, snapshot, List.copyOf(data)));
    }

    private RoutineFailure initialPreflight(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            FurnaceRequest smelt) {
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || !smelt.target().dimension().equals(session.dimension())
                || !smelt.target().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("FURNACE_WORLD_NOT_READY", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("world_ready", true), Map.of());
        }
        if (!basicPlayerSafety(minecraft, null)) return safetyFailure();
        if (minecraft.gui.screen() != null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                || screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return failure("FURNACE_SCREEN_NOT_CLEAR", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("screen", "clear", "ownership", "idle"), Map.of());
        }
        if (!targetReadyForOpen(minecraft, smelt, true)) {
            return failure("FURNACE_STATION_STATE_MISMATCH",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("station", smelt.family().stationKind()), Map.of());
        }
        if (chooseOpenHand(minecraft.player).isEmpty()) {
            return failure("FURNACE_SAFE_OPEN_HAND_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("empty_or_safe_hotbar_item", true), Map.of());
        }
        Rotation rotation = ViewSlotLease.rotation(
                minecraft.player.getEyePosition(), smelt.aimPoint());
        if (MinecraftKnownBrewingPort.oneWayCameraDegrees(
                minecraft.player.getYRot(), minecraft.player.getXRot(),
                rotation.yaw(), rotation.pitch()) > MAX_ONE_WAY_CAMERA_DEGREES) {
            return failure("FURNACE_CAMERA_PREFLIGHT_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("one_way_camera_degrees_at_most",
                            (int) MAX_ONE_WAY_CAMERA_DEGREES), Map.of());
        }
        try {
            resolveRecipe(minecraft, session, session.clientTick(), smelt);
        } catch (IllegalArgumentException exception) {
            return failure("FURNACE_RECIPE_NOT_CURRENT",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("known_recipe", true),
                    Map.of("reason", boundedReason(exception)));
        }
        return null;
    }

    private RoutineFailure ongoingFailure(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AttemptState state) {
        if (state.smelt == null) return state.failure;
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || state.baseline == null || !state.baseline.sameSession(session)
                || !state.smelt.target().dimension().equals(session.dimension())
                || !state.smelt.target().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("FURNACE_WORLD_SESSION_CHANGED",
                    RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("world_session", "unchanged"), Map.of());
        }
        if (!basicPlayerSafety(minecraft, state.baseline)
                || state.ownership == null
                || !state.ownership.undisturbed(minecraft)) {
            return safetyFailure();
        }
        ScreenOwnershipSignals.Snapshot screen = screens.snapshot();
        if (screen.phase() == ScreenOwnershipSignals.Phase.FAILED) {
            return failure("FURNACE_OWNED_SCREEN_FAILED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("owned_screen", true),
                    Map.of("reason", Objects.toString(screen.failureReason(), "unknown")));
        }
        if (!screenContextMatches(minecraft, state.stage, screen.phase(), state.smelt.family())) {
            return failure("FURNACE_SCREEN_CONTEXT_CHANGED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("expected_screen_context", true), Map.of());
        }
        if (!targetFamilyCurrent(minecraft, state.smelt)
                || !sameExceptLit(state.admittedTargetState,
                        fingerprint(minecraft.level.getBlockState(
                                blockPos(state.smelt.target()))))) {
            return failure("FURNACE_TARGET_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("station_current_except_lit", true), Map.of());
        }
        return null;
    }

    private boolean basicPlayerSafety(Minecraft minecraft, PlayerBaseline baseline) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null) return false;
        return !minecraft.isPaused()
                && minecraft.gui.overlay() == null
                && player.isAlive()
                && !player.isDeadOrDying()
                && player.getHealth() >= MIN_SAFE_HEALTH
                && (baseline == null || player.getHealth() + 0.001F >= baseline.health())
                && player.hurtTime == 0
                && player.getRemainingFireTicks() <= 0
                && !player.isUsingItem()
                && !player.isShiftKeyDown()
                && !player.isPassenger()
                && player.onGround()
                && (baseline == null || baseline.matches(player))
                && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL
                && level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                        .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                                minecraft, entity, THREAT_RADIUS));
    }

    private ClientRecipeCatalog.ResolvedRecipe resolveRecipe(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long tick,
            FurnaceRequest smelt) {
        recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
        ClientRecipeCatalog.ResolvedRecipe recipe = recipes.resolve(
                        session.worldSessionId(), smelt.recipeRef(), smelt.recipeFingerprint())
                .orElseThrow(() -> new IllegalArgumentException(
                        "recipe ref/fingerprint is stale for this client recipe book"));
        validateResolvedRecipe(smelt, recipe);
        return recipe;
    }

    static void validateResolvedRecipe(
            FurnaceRequest request, ClientRecipeCatalog.ResolvedRecipe recipe) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(recipe, "recipe");
        ClientRecipeCatalog.RecipeView view = recipe.view();
        if (!view.supported()
                || !view.displayKind().equals(request.family().displayKind())
                || !view.requiredScreen().equals(request.family().requiredScreen())
                || recipe.cookingDurationTicks() < 1
                || recipe.cookingDurationTicks() > MAX_COOK_DURATION_TICKS
                || !view.result().deterministic()
                || view.result().alternatives().size() != 1
                || view.ingredients().size() != 1
                || view.ingredients().getFirst().countPerCraft() != 1
                || view.ingredients().getFirst().alternatives().isEmpty()) {
            throw new IllegalArgumentException(
                    "recipe display is incompatible with the declared furnace family");
        }
        ClientRecipeCatalog.ResultAlternative result =
                view.result().alternatives().getFirst();
        if (!request.goalItem().equals(result.item())) {
            throw new IllegalArgumentException("recipe result does not match goal item");
        }
    }

    private static boolean screenContextMatches(
            Minecraft minecraft,
            Stage stage,
            ScreenOwnershipSignals.Phase phase,
            FurnaceFamily family) {
        if (stage == Stage.AIMING_INITIAL
                || stage == Stage.AIMING_LOADED_READBACK
                || stage == Stage.AIMING_FINAL_READBACK) {
            return phase == ScreenOwnershipSignals.Phase.IDLE
                    && minecraft.gui.screen() == null
                    && minecraft.player != null
                    && minecraft.player.containerMenu == minecraft.player.inventoryMenu;
        }
        if (stage == Stage.AWAITING_LOADED_CLOSE
                || stage == Stage.AWAITING_FINAL_CHECKPOINT_CLOSE
                || stage == Stage.AWAITING_FINAL_CLOSE) {
            return phase == ScreenOwnershipSignals.Phase.CLOSING
                    || phase == ScreenOwnershipSignals.Phase.IDLE;
        }
        if (stage == Stage.OPENING_INITIAL
                || stage == Stage.OPENING_LOADED_READBACK
                || stage == Stage.OPENING_FINAL_READBACK) {
            return phase == ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_SCREEN
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT
                    || phase == ScreenOwnershipSignals.Phase.OWNED;
        }
        return phase == ScreenOwnershipSignals.Phase.OWNED
                && exactLiveFurnaceContext(minecraft, family);
    }

    private static boolean exactLiveFurnaceContext(
            Minecraft minecraft, FurnaceFamily family) {
        if (minecraft.player == null
                || minecraft.gui.screen() == null
                || minecraft.gui.screen().getClass() != family.screenClass()
                || minecraft.player.containerMenu.getClass() != family.menuClass()
                || !(minecraft.gui.screen() instanceof AbstractFurnaceScreen<?> screen)) {
            return false;
        }
        return screen.getMenu() == minecraft.player.containerMenu;
    }

    private static boolean exactFurnaceLayout(
            AbstractFurnaceMenu menu, Inventory inventory) {
        if (menu.slots.size() != MENU_SLOT_COUNT) return false;
        Object furnace = menu.slots.get(INPUT_SLOT).container;
        for (int slot = INPUT_SLOT; slot <= RESULT_SLOT; slot++) {
            if (menu.slots.get(slot).container != furnace
                    || menu.slots.get(slot).getContainerSlot() != slot) return false;
        }
        var order = new ArrayList<Integer>(36);
        for (int slot = PLAYER_SLOT_START; slot <= PLAYER_SLOT_END; slot++) {
            if (menu.slots.get(slot).container != inventory) return false;
            order.add(menu.slots.get(slot).getContainerSlot());
        }
        return MinecraftKnownBrewingPort.exactPlayerSlotOrder(order);
    }

    static boolean freshDataForOpen(
            ContainerSyncSignals.OpenScreenEvidence open,
            ContainerSyncSignals.ContainerDataEvidence data,
            int containerId,
            String menuType) {
        return open != null && data != null
                && data.worldSessionId().equals(open.worldSessionId())
                && data.containerId() == containerId
                && data.containerId() == open.containerId()
                && data.menuTypeId().equals(menuType)
                && open.menuTypeId().equals(menuType)
                && data.packetLedgerRevision() > open.packetLedgerRevision();
    }

    private static boolean liveMenuMatches(
            ContainerSyncSignals.ContainerSnapshot snapshot,
            AbstractFurnaceMenu menu) {
        if (snapshot.slots().size() != menu.slots.size()) return false;
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (!snapshot.slots().get(slot).equals(
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(
                            menu.slots.get(slot).getItem()))) return false;
        }
        return snapshot.carried().equals(
                ContainerSyncSignals.StackFingerprint.fromServerPacket(menu.getCarried()));
    }

    static boolean liveDataMatches(
            AbstractFurnaceMenu menu,
            List<ContainerSyncSignals.ContainerDataEvidence> data) {
        if (data.size() != DATA_COUNT) return false;
        int lit = data.get(DATA_LIT_REMAINING).value();
        int litDuration = data.get(DATA_LIT_DURATION).value();
        int progress = data.get(DATA_COOK_PROGRESS).value();
        int duration = data.get(DATA_COOK_DURATION).value();
        if (lit < 0 || litDuration < 0 || progress < 0 || duration < 0
                || (lit > 0) != menu.isLit()) return false;
        float expectedBurn = duration == 0 || progress == 0
                ? 0.0F : Mth.clamp((float) progress / duration, 0.0F, 1.0F);
        int denominator = litDuration == 0 ? 200 : litDuration;
        float expectedLit = Mth.clamp((float) lit / denominator, 0.0F, 1.0F);
        return Math.abs(menu.getBurnProgress() - expectedBurn) <= 0.0001F
                && Math.abs(menu.getLitProgress() - expectedLit) <= 0.0001F;
    }

    static boolean initialDataReady(
            List<ContainerSyncSignals.ContainerDataEvidence> data) {
        return data.size() == DATA_COUNT
                && data.stream().allMatch(value -> value.value() == 0);
    }

    static boolean smeltStarted(
            List<ContainerSyncSignals.ContainerDataEvidence> data) {
        if (data.size() != DATA_COUNT) return false;
        int lit = data.get(DATA_LIT_REMAINING).value();
        int litDuration = data.get(DATA_LIT_DURATION).value();
        int progress = data.get(DATA_COOK_PROGRESS).value();
        int duration = data.get(DATA_COOK_DURATION).value();
        return lit > 0 && litDuration > 0
                && duration > 0 && progress >= 0 && progress < duration;
    }

    static boolean finalDataReady(
            List<ContainerSyncSignals.ContainerDataEvidence> data) {
        if (data.size() != DATA_COUNT) return false;
        int lit = data.get(DATA_LIT_REMAINING).value();
        int litDuration = data.get(DATA_LIT_DURATION).value();
        int progress = data.get(DATA_COOK_PROGRESS).value();
        int duration = data.get(DATA_COOK_DURATION).value();
        return lit >= 0 && litDuration >= 0 && progress == 0 && duration >= 0;
    }

    static boolean stationSlotsEmpty(
            List<ContainerSyncSignals.StackFingerprint> slots) {
        return slots.size() == MENU_SLOT_COUNT
                && slots.get(INPUT_SLOT).empty()
                && slots.get(FUEL_SLOT).empty()
                && slots.get(RESULT_SLOT).empty();
    }

    static Optional<SourcePlan> chooseSources(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<StackKey> ingredientKeys,
            StackKey fuelKey,
            int ingredientCount,
            int minimumFuelCount) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(ingredientKeys, "ingredientKeys");
        Objects.requireNonNull(fuelKey, "fuelKey");
        if (ingredientCount < 1 || ingredientCount > 64
                || minimumFuelCount < 1 || minimumFuelCount > 64) {
            throw new IllegalArgumentException("source counts are outside 1..64");
        }
        for (int ingredientSlot : playerSlots()) {
            requireSlot(slots, ingredientSlot);
            ContainerSyncSignals.StackFingerprint candidate = slots.get(ingredientSlot);
            if (candidate.count() != ingredientCount) continue;
            Optional<StackKey> ingredient = ingredientKeys.stream()
                    .filter(key -> matches(candidate, key, ingredientCount)).findFirst();
            if (ingredient.isEmpty()) continue;
            for (int fuelSlot : playerSlots()) {
                requireSlot(slots, fuelSlot);
                ContainerSyncSignals.StackFingerprint fuel = slots.get(fuelSlot);
                if (fuelSlot != ingredientSlot && !fuel.empty()
                        && fuel.count() >= minimumFuelCount
                        && matches(fuel, fuelKey, fuel.count())) {
                    return Optional.of(new SourcePlan(
                            ingredientSlot, fuelSlot, ingredient.orElseThrow(), fuel.count()));
                }
            }
        }
        return Optional.empty();
    }

    static Map<StackKey, Integer> inventoryMultiset(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<Integer> inventorySlots) {
        var result = new LinkedHashMap<StackKey, Integer>();
        for (int slot : inventorySlots) {
            requireSlot(slots, slot);
            ContainerSyncSignals.StackFingerprint stack = slots.get(slot);
            if (!stack.empty()) {
                result.merge(StackKey.of(stack), stack.count(), Math::addExact);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<StackKey, Integer> expectedInventoryAfterLoad(
            Map<StackKey, Integer> baseline,
            StackKey ingredient,
            int ingredientCount,
            StackKey fuel,
            int fuelCount) {
        var expected = new LinkedHashMap<>(Objects.requireNonNull(baseline, "baseline"));
        adjust(expected, Objects.requireNonNull(ingredient, "ingredient"), -ingredientCount);
        adjust(expected, Objects.requireNonNull(fuel, "fuel"), -fuelCount);
        return Collections.unmodifiableMap(expected);
    }

    static Map<StackKey, Integer> expectedInventoryAfterSmelt(
            Map<StackKey, Integer> baseline,
            StackKey ingredient,
            int ingredientCount,
            StackKey fuel,
            int fuelConsumed,
            StackKey output,
            int outputTotal) {
        var expected = new LinkedHashMap<>(Objects.requireNonNull(baseline, "baseline"));
        adjust(expected, Objects.requireNonNull(ingredient, "ingredient"), -ingredientCount);
        adjust(expected, Objects.requireNonNull(fuel, "fuel"), -fuelConsumed);
        adjust(expected, Objects.requireNonNull(output, "output"), outputTotal);
        return Collections.unmodifiableMap(expected);
    }

    static int fuelItemsRequired(int smelts, int cookingDurationTicks, int fuelBurnTicks) {
        if (smelts < 1 || smelts > 64
                || cookingDurationTicks < 1 || cookingDurationTicks > MAX_COOK_DURATION_TICKS
                || fuelBurnTicks < 1) {
            throw new IllegalArgumentException("furnace timing is outside the closed bound");
        }
        int totalCookTicks = Math.multiplyExact(smelts, cookingDurationTicks);
        return Math.floorDiv(totalCookTicks - 1, fuelBurnTicks) + 1;
    }

    private static int smeltTimeoutTicks(AttemptState state) {
        return Math.addExact(
                SMELT_SYNC_MARGIN_TICKS,
                Math.multiplyExact(
                        state.smelt.maxSmelts(), state.recipe.cookingDurationTicks()));
    }

    static boolean inventoryReadbackMatches(
            Map<StackKey, Integer> expected,
            Map<StackKey, Integer> actual) {
        return Map.copyOf(Objects.requireNonNull(expected, "expected"))
                .equals(Map.copyOf(Objects.requireNonNull(actual, "actual")));
    }

    private static void adjust(Map<StackKey, Integer> values, StackKey key, int delta) {
        int after = Math.addExact(values.getOrDefault(key, 0), delta);
        if (after < 0) throw new IllegalArgumentException("inventory delta is impossible");
        if (after == 0) values.remove(key);
        else values.put(key, after);
    }

    private static boolean matches(
            ContainerSyncSignals.StackFingerprint stack, StackKey key, int count) {
        return count == 0 ? stack.empty()
                : !stack.empty() && stack.count() == count
                        && stack.itemId().equals(key.itemId())
                        && stack.itemAndComponentsHash() == key.componentsHash();
    }

    private static int completedSmelts(
            ContainerSyncSignals.StackFingerprint result,
            StackKey output,
            int outputCount,
            int maximumSmelts) {
        if (result.empty()) return 0;
        if (outputCount < 1
                || !result.itemId().equals(output.itemId())
                || result.itemAndComponentsHash() != output.componentsHash()
                || result.count() % outputCount != 0) return -1;
        int completed = result.count() / outputCount;
        return completed <= maximumSmelts ? completed : -1;
    }

    private static void requireSlot(
            List<ContainerSyncSignals.StackFingerprint> slots, int slot) {
        if (slot < 0 || slot >= slots.size()) {
            throw new IllegalArgumentException("slot is outside the packet snapshot");
        }
    }

    private static List<Integer> playerSlots() {
        var slots = new ArrayList<Integer>(36);
        for (int slot = PLAYER_SLOT_START; slot <= PLAYER_SLOT_END; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    static boolean sameExceptLit(
            BlockStateFingerprint expected, BlockStateFingerprint actual) {
        if (!expected.blockId().equals(actual.blockId())) return false;
        var expectedProperties = new LinkedHashMap<>(expected.properties());
        var actualProperties = new LinkedHashMap<>(actual.properties());
        expectedProperties.remove(AbstractFurnaceBlock.LIT.getName());
        actualProperties.remove(AbstractFurnaceBlock.LIT.getName());
        return expectedProperties.equals(actualProperties);
    }

    private static boolean targetReadyForOpen(
            Minecraft minecraft, AttemptState state, boolean initial) {
        if (!targetFamilyCurrent(minecraft, state.smelt)) return false;
        BlockStateFingerprint actual = fingerprint(
                Objects.requireNonNull(minecraft.level).getBlockState(
                        blockPos(state.smelt.target())));
        return initial
                ? Objects.requireNonNull(state.admittedTargetState).equals(actual)
                : sameExceptLit(state.admittedTargetState, actual);
    }

    private static boolean targetReadyForOpen(
            Minecraft minecraft, FurnaceRequest smelt, boolean initial) {
        if (!targetFamilyCurrent(minecraft, smelt)) return false;
        BlockStateFingerprint actual = fingerprint(
                Objects.requireNonNull(minecraft.level).getBlockState(blockPos(smelt.target())));
        return initial ? smelt.expectedState().matches(actual)
                : sameExceptLit(smelt.expectedState(), actual);
    }

    private static boolean targetFamilyCurrent(
            Minecraft minecraft, FurnaceRequest smelt) {
        if (minecraft.level == null || minecraft.player == null) return false;
        BlockPos position = blockPos(smelt.target());
        if (!minecraft.level.isLoaded(position)
                || !minecraft.level.getWorldBorder().isWithinBounds(position)
                || !minecraft.player.isWithinBlockInteractionRange(position, 0.0D)) return false;
        BlockState state = minecraft.level.getBlockState(position);
        return state.is(smelt.family().block())
                && minecraft.level.getBlockEntity(position) != null
                && minecraft.level.getBlockEntity(position).getClass()
                        == smelt.family().blockEntityClass();
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        return MinecraftPhaseFiveInventoryPort.fingerprintLiveState(state);
    }

    private static ItemStack defaultItemStack(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        var holder = id == null ? Optional.<net.minecraft.core.Holder.Reference<Item>>empty()
                : BuiltInRegistries.ITEM.get(id);
        if (holder.isEmpty() || holder.orElseThrow().value() == Items.AIR) {
            throw new IllegalArgumentException("item is not registered");
        }
        return new ItemStack(holder.orElseThrow().value());
    }

    private static StackKey defaultKey(String itemId) {
        ItemStack stack = defaultItemStack(itemId);
        return new StackKey(itemId, ItemStack.hashItemAndComponents(stack));
    }

    private static Optional<OpenHandPlan> chooseOpenHand(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return Optional.of(new OpenHandPlan(InteractionHand.MAIN_HAND, slot));
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (MinecraftKnownBrewingPort.safeNormalUseStack(
                    player.getInventory().getItem(slot))) {
                return Optional.of(new OpenHandPlan(InteractionHand.MAIN_HAND, slot));
            }
        }
        return Optional.empty();
    }

    private static BlockHitResult exactHit(Minecraft minecraft, BlockTarget target) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || hit.isWorldBorderHit()
                || !hit.getBlockPos().equals(blockPos(target))) {
            throw new IllegalArgumentException("crosshair does not identify exact furnace");
        }
        return hit;
    }

    private boolean reserveInteraction(AttemptState state) {
        if (state.openCount + state.containerClicks < MAX_INTERACTIONS) return true;
        state.latchFailure(failure("FURNACE_INTERACTION_LIMIT",
                RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.NONE,
                Map.of("maximum_interactions", MAX_INTERACTIONS), Map.of()));
        return false;
    }

    private void closeOwnedMenuClient(
            Minecraft minecraft, ScreenOwnershipSignals.CleanupDecision decision) {
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu().containerId != decision.containerId()
                || !ScreenOwnershipSignals.menuTypeId(screen.getMenu().getType())
                        .equals(decision.menuTypeId())) {
            throw new IllegalStateException("owned furnace screen changed before close");
        }
        screen.onClose();
        screens.onScreenClosing(screen).ifPresent(reason -> {
            throw new IllegalStateException("owned furnace screen close failed: " + reason);
        });
    }

    static FurnaceRequest parseRequest(PhaseFiveRequest request) {
        if (!KIND.equals(request.kind())) {
            throw new IllegalArgumentException("unsupported furnace adapter kind");
        }
        requireExactKeys(request.parameters(), Set.of(
                "recipe_ref", "recipe_fingerprint", "goal", "station", "fuel",
                "max_smelts", "aim_point"));
        if (request.parameters().get("aim_point") == null) {
            throw new IllegalArgumentException("furnace aim_point is required");
        }
        Map<String, Object> goal = map(request.parameters().get("goal"), "goal");
        requireExactKeys(goal, Set.of(
                "item", "stack_policy", "minimum_inventory_count"));
        requireDefaultComponents(goal, "goal");
        Map<String, Object> fuel = map(request.parameters().get("fuel"), "fuel");
        requireExactKeys(fuel, Set.of("item", "stack_policy"));
        requireDefaultComponents(fuel, "fuel");
        Map<String, Object> station = map(request.parameters().get("station"), "station");
        requireExactKeys(station, Set.of("kind", "target", "expected_state"));
        FurnaceFamily family = FurnaceFamily.fromStationKind(
                string(station.get("kind"), "station.kind"));
        BlockTarget target = target(map(station.get("target"), "station.target"));
        BlockStateFingerprint expected = state(map(
                station.get("expected_state"), "station.expected_state"));
        FurnaceRequest result = new FurnaceRequest(
                string(request.parameters().get("recipe_ref"), "recipe_ref"),
                string(request.parameters().get("recipe_fingerprint"), "recipe_fingerprint"),
                string(goal.get("item"), "goal.item"),
                integer(goal.get("minimum_inventory_count"),
                        "goal.minimum_inventory_count"),
                family,
                target,
                expected,
                MinecraftPhaseFiveInventoryPort.inventoryAimPoint(request, target),
                string(fuel.get("item"), "fuel.item"),
                integer(request.parameters().get("max_smelts"), "max_smelts"),
                request);
        if (!expected.blockId().equals(family.blockId())) {
            throw new IllegalArgumentException(
                    "expected_state must be the declared furnace family");
        }
        return result;
    }

    private static void requireDefaultComponents(Map<String, Object> value, String name) {
        if (!"default_components_only".equals(
                string(value.get("stack_policy"), name + ".stack_policy"))) {
            throw new IllegalArgumentException(name + " requires default_components_only");
        }
    }

    private static BlockTarget target(Map<String, Object> value) {
        requireExactKeys(value, Set.of("dimension", "x", "y", "z"));
        return new BlockTarget(
                string(value.get("dimension"), "dimension"),
                integer(value.get("x"), "x"),
                integer(value.get("y"), "y"),
                integer(value.get("z"), "z"));
    }

    private static BlockStateFingerprint state(Map<String, Object> value) {
        requireExactKeys(value, Set.of("block", "properties"));
        Map<String, Object> raw = map(value.get("properties"), "properties");
        var properties = new LinkedHashMap<String, String>();
        raw.forEach((key, property) ->
                properties.put(key, string(property, "property value")));
        return new BlockStateFingerprint(
                string(value.get("block"), "block"), properties);
    }

    private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactKeys(Map<String, ?> value, Set<String> expected) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "object fields do not match the furnace contract");
        }
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long integer = number.longValue();
        if (number.doubleValue() != integer
                || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) integer;
    }

    private AttemptState requireAttempt(PhaseFiveAttempt attempt) {
        AttemptState state = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null) throw new IllegalStateException("furnace attempt is not active");
        return state;
    }

    private Minecraft assertClientThread() {
        Minecraft minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("furnace adapter must run on client thread");
        }
        return minecraft;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(
                minecraftSupplier.get(), "Minecraft client is not initialized");
    }

    private WorldSessionTracker.Snapshot requireSession() {
        WorldSessionTracker.Snapshot session = Objects.requireNonNull(
                sessionSupplier.get(), "world session is unavailable");
        if (!session.worldReady()) throw new IllegalStateException("world session is not ready");
        return session;
    }

    private long currentTick() {
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        return session == null ? 0L : Math.max(0L, session.clientTick());
    }

    private long packetRevision() {
        long screen = Math.max(0L, screens.snapshot().packetLedgerRevision());
        Minecraft minecraft = minecraftSupplier.get();
        long container = minecraft == null || minecraft.level == null ? 0L
                : containerSignals.snapshot(minecraft.level)
                        .map(ContainerSyncSignals.Snapshot::packetLedgerRevision)
                        .orElse(0L);
        return Math.max(screen, container);
    }

    private static long boundedDeadline(long now, int ticks) {
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

    static int aimTimeoutTicks() {
        return Math.addExact(
                (int) Math.ceil(MAX_ONE_WAY_CAMERA_DEGREES / CAMERA_DEGREES_PER_TICK),
                AIM_SETTLE_MARGIN_TICKS);
    }

    private static RoutineFailure safetyFailure() {
        return failure("FURNACE_SAFETY_CHANGED", RoutineFailure.Category.SAFETY,
                RoutineFailure.Recovery.USER,
                Map.of("universal_safety", true),
                Map.of("universal_safety", false));
    }

    private static RoutineFailure failure(
            String code,
            RoutineFailure.Category category,
            RoutineFailure.Recovery recovery,
            Map<String, Object> expected,
            Map<String, Object> observed) {
        return new RoutineFailure(
                category, code, false, recovery, RoutineFailure.Scope.STEP, 1,
                expected, observed, Map.of(),
                List.of("player", "inventory", "target", "screen"),
                recovery == RoutineFailure.Recovery.USER);
    }

    private static String boundedReason(Exception exception) {
        String reason = Objects.toString(
                exception.getMessage(), exception.getClass().getSimpleName());
        return reason.length() <= 160 ? reason : reason.substring(0, 160);
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    enum Stage {
        AIMING_INITIAL,
        OPENING_INITIAL,
        LOAD_INPUT,
        LOAD_FUEL,
        CLOSING_LOADED_CHECKPOINT,
        AWAITING_LOADED_CLOSE,
        AIMING_LOADED_READBACK,
        OPENING_LOADED_READBACK,
        AWAITING_SMELT_COMPLETE,
        UNLOAD_FUEL,
        UNLOAD_RESULT,
        CLOSING_FINAL_CHECKPOINT,
        AWAITING_FINAL_CHECKPOINT_CLOSE,
        AIMING_FINAL_READBACK,
        OPENING_FINAL_READBACK,
        AWAITING_FINAL_CLOSE,
        RELEASING,
        TERMINAL
    }

    enum FurnaceFamily {
        FURNACE(
                "furnace", "smelting", "furnace", "minecraft:furnace",
                "minecraft:furnace", Blocks.FURNACE,
                FurnaceMenu.class, FurnaceScreen.class, FurnaceBlockEntity.class),
        BLAST_FURNACE(
                "blast_furnace", "blasting", "blast_furnace",
                "minecraft:blast_furnace", "minecraft:blast_furnace", Blocks.BLAST_FURNACE,
                BlastFurnaceMenu.class, BlastFurnaceScreen.class,
                BlastFurnaceBlockEntity.class),
        SMOKER(
                "smoker", "smoking", "smoker", "minecraft:smoker",
                "minecraft:smoker", Blocks.SMOKER,
                SmokerMenu.class, SmokerScreen.class, SmokerBlockEntity.class);

        private final String stationKind;
        private final String displayKind;
        private final String requiredScreen;
        private final String menuType;
        private final String blockId;
        private final net.minecraft.world.level.block.Block block;
        private final Class<? extends AbstractFurnaceMenu> menuClass;
        private final Class<? extends AbstractFurnaceScreen<?>> screenClass;
        private final Class<? extends AbstractFurnaceBlockEntity> blockEntityClass;

        FurnaceFamily(
                String stationKind,
                String displayKind,
                String requiredScreen,
                String menuType,
                String blockId,
                net.minecraft.world.level.block.Block block,
                Class<? extends AbstractFurnaceMenu> menuClass,
                Class<? extends AbstractFurnaceScreen<?>> screenClass,
                Class<? extends AbstractFurnaceBlockEntity> blockEntityClass) {
            this.stationKind = stationKind;
            this.displayKind = displayKind;
            this.requiredScreen = requiredScreen;
            this.menuType = menuType;
            this.blockId = blockId;
            this.block = block;
            this.menuClass = menuClass;
            this.screenClass = screenClass;
            this.blockEntityClass = blockEntityClass;
        }

        static FurnaceFamily fromStationKind(String value) {
            for (FurnaceFamily family : values()) {
                if (family.stationKind.equals(value)) return family;
            }
            throw new IllegalArgumentException("unsupported furnace station kind");
        }

        String stationKind() { return stationKind; }
        String displayKind() { return displayKind; }
        String requiredScreen() { return requiredScreen; }
        String menuType() { return menuType; }
        String blockId() { return blockId; }
        net.minecraft.world.level.block.Block block() { return block; }
        Class<? extends AbstractFurnaceMenu> menuClass() { return menuClass; }
        Class<? extends AbstractFurnaceScreen<?>> screenClass() { return screenClass; }
        Class<? extends AbstractFurnaceBlockEntity> blockEntityClass() {
            return blockEntityClass;
        }
    }

    record FurnaceRequest(
            String recipeRef,
            String recipeFingerprint,
            String goalItem,
            int minimumInventoryCount,
            FurnaceFamily family,
            BlockTarget target,
            BlockStateFingerprint expectedState,
            Vec3 aimPoint,
            String fuelItem,
            int maxSmelts,
            PhaseFiveRequest operation) {
        FurnaceRequest {
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(goalItem, "goalItem");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(aimPoint, "aimPoint");
            Objects.requireNonNull(fuelItem, "fuelItem");
            Objects.requireNonNull(operation, "operation");
            if (minimumInventoryCount < 1 || minimumInventoryCount > 2_304) {
                throw new IllegalArgumentException("minimum inventory count must be in 1..2304");
            }
            if (maxSmelts < 1 || maxSmelts > 64) {
                throw new IllegalArgumentException("private furnace slice supports 1..64 smelts");
            }
            if (!operation.bounds().contains(target)
                    || operation.bounds().maxTravelBlocks() != 0
                    || operation.bounds().allowBreak()
                    || operation.expectedUnits() != maxSmelts
                    || !operation.progressUnit().equals("smelts")) {
                throw new IllegalArgumentException("furnace operation bounds are invalid");
            }
        }
    }

    record StackKey(String itemId, int componentsHash) {
        StackKey {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid item identity");
            }
        }

        static StackKey of(ContainerSyncSignals.StackFingerprint stack) {
            if (stack.empty()) throw new IllegalArgumentException("empty stack has no key");
            return new StackKey(stack.itemId(), stack.itemAndComponentsHash());
        }
    }

    record SourcePlan(
            int ingredientSlot, int fuelSlot, StackKey ingredientKey, int fuelCount) {
        SourcePlan {
            Objects.requireNonNull(ingredientKey, "ingredientKey");
            if (ingredientSlot == fuelSlot) {
                throw new IllegalArgumentException("source slots must be distinct");
            }
            if (fuelCount < 1 || fuelCount > 64) {
                throw new IllegalArgumentException("fuel count must be in 1..64");
            }
        }
    }

    private record FurnaceView(
            AbstractFurnaceMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            List<ContainerSyncSignals.ContainerDataEvidence> data) {
        FurnaceView {
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(snapshot, "snapshot");
            data = List.copyOf(Objects.requireNonNull(data, "data"));
            if (data.size() != DATA_COUNT) {
                throw new IllegalArgumentException("furnace data count is not four");
            }
        }

        long maximumDataRevision() {
            return data.stream()
                    .mapToLong(ContainerSyncSignals.ContainerDataEvidence::packetLedgerRevision)
                    .max().orElseThrow();
        }
    }

    record OpenHandPlan(InteractionHand hand, int selectedSlot) {
        OpenHandPlan {
            Objects.requireNonNull(hand, "hand");
            if (selectedSlot < 0 || selectedSlot > 8) {
                throw new IllegalArgumentException("open-hand slot is outside hotbar");
            }
        }

        boolean ready(LocalPlayer player) {
            if (player.getInventory().getSelectedSlot() != selectedSlot) return false;
            ItemStack stack = hand == InteractionHand.OFF_HAND
                    ? player.getOffhandItem() : player.getMainHandItem();
            return stack.isEmpty()
                    || hand == InteractionHand.MAIN_HAND
                            && MinecraftKnownBrewingPort.safeNormalUseStack(stack);
        }

        boolean readyAtSlot(LocalPlayer player) {
            ItemStack stack = hand == InteractionHand.OFF_HAND
                    ? player.getOffhandItem()
                    : player.getInventory().getItem(selectedSlot);
            return stack.isEmpty()
                    || hand == InteractionHand.MAIN_HAND
                            && MinecraftKnownBrewingPort.safeNormalUseStack(stack);
        }
    }

    private record PlayerBaseline(
            UUID worldSessionId,
            String dimension,
            double x,
            double y,
            double z,
            float health) {
        static PlayerBaseline capture(
                LocalPlayer player, WorldSessionTracker.Snapshot session) {
            return new PlayerBaseline(
                    session.worldSessionId(), session.dimension(),
                    player.getX(), player.getY(), player.getZ(), player.getHealth());
        }

        boolean sameSession(WorldSessionTracker.Snapshot session) {
            return worldSessionId.equals(session.worldSessionId())
                    && dimension.equals(session.dimension());
        }

        boolean matches(LocalPlayer player) {
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                    && dx * dx + dy * dy + dz * dz <= MAX_POSITION_DRIFT_SQUARED;
        }
    }

    private record InconclusiveState(
            PhaseFiveEvidence.Certainty certainty, String reason) {
        InconclusiveState {
            Objects.requireNonNull(certainty, "certainty");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private record TerminalIntent(
            TerminalKind kind,
            RoutineFailure failure,
            InconclusiveState inconclusive) {
        static TerminalIntent success() {
            return new TerminalIntent(TerminalKind.SUCCESS, null, null);
        }
        static TerminalIntent failure(RoutineFailure failure) {
            return new TerminalIntent(
                    TerminalKind.FAILURE, Objects.requireNonNull(failure), null);
        }
        static TerminalIntent inconclusive(String reason) {
            return new TerminalIntent(TerminalKind.INCONCLUSIVE, null,
                    new InconclusiveState(PhaseFiveEvidence.Certainty.UNKNOWN, reason));
        }
    }

    private enum TerminalKind { SUCCESS, FAILURE, INCONCLUSIVE }

    private static final class AttemptState {
        private final PhaseFiveRequest request;
        private final FurnaceRequest smelt;
        private Stage stage = Stage.TERMINAL;
        private RoutineFailure failure;
        private InconclusiveState inconclusive;
        private PhaseFiveResult result;
        private TerminalIntent terminalIntent;
        private PlayerBaseline baseline;
        private LocalPlayer playerIdentity;
        private Object levelIdentity;
        private Object connectionIdentity;
        private ViewSlotLease ownership;
        private ClientRecipeCatalog.ResolvedRecipe recipe;
        private BlockStateFingerprint admittedTargetState;
        private List<StackKey> ingredientKeys = List.of();
        private StackKey ingredientKey;
        private StackKey fuelKey;
        private StackKey outputKey;
        private int outputCount;
        private int outputTotal;
        private int inventoryBeforeOutput;
        private int inputSourceSlot = -1;
        private int fuelSourceSlot = -1;
        private ContainerSyncSignals.StackFingerprint inputSourceStack;
        private ContainerSyncSignals.StackFingerprint fuelSourceStack;
        private ContainerSyncSignals.StackFingerprint fuelRecoveryStack;
        private ContainerSyncSignals.StackFingerprint resultSourceStack;
        private int fuelLoadedCount;
        private int fuelConsumedCount;
        private Map<StackKey, Integer> expectedLoadedInventory = Map.of();
        private Map<StackKey, Integer> expectedFinalInventory = Map.of();
        private AbstractFurnaceMenu menuIdentity;
        private long stageDeadlineClientTick;
        private long startDataRevision;
        private int openCount;
        private int containerClicks;
        private boolean loadedReadbackConfirmed;
        private boolean smeltStarted;
        private boolean smeltCompleted;
        private boolean fuelRecovered;
        private boolean resultRecovered;
        private boolean finalReadbackConfirmed;
        private Map<String, Object> pendingResultBasis;
        private OpenHandPlan openHand;
        private ClientPredictionSignals.PredictionAttempt openPrediction;
        private long releaseStartedTick = -1L;
        private long releaseDeadlineTick;
        private boolean screenOwnedObserved;
        private boolean serverCursorProofFresh;
        private boolean serverCursorReleaseConfirmed;
        private boolean screenReleaseConfirmed;
        private boolean releaseConfirmed;
        private boolean releaseFault;

        private AttemptState(PhaseFiveRequest request, FurnaceRequest smelt) {
            this.request = Objects.requireNonNull(request, "request");
            this.smelt = Objects.requireNonNull(smelt, "smelt");
        }

        private AttemptState(PhaseFiveRequest request) {
            this.request = Objects.requireNonNull(request, "request");
            this.smelt = null;
        }

        static AttemptState rejected(PhaseFiveRequest request) {
            return new AttemptState(request);
        }

        void prepareStaticPlan() {
            validateResolvedRecipe(smelt, Objects.requireNonNull(recipe));
            ClientRecipeCatalog.RecipeView view = recipe.view();
            ClientRecipeCatalog.ResultAlternative output =
                    view.result().alternatives().getFirst();
            outputKey = defaultKey(output.item());
            outputCount = output.count();
            outputTotal = Math.multiplyExact(outputCount, smelt.maxSmelts());
            if (request.expectedUnits() != smelt.maxSmelts()
                    || outputTotal > defaultItemStack(output.item()).getMaxStackSize()) {
                throw new IllegalArgumentException(
                        "operation/result stack is outside the exact batch bound");
            }
            ingredientKeys = view.ingredients().getFirst().alternatives().stream()
                    .map(MinecraftKnownFurnacePort::defaultKey)
                    .distinct().toList();
            ItemStack fuel = defaultItemStack(smelt.fuelItem());
            if (fuel.getCraftingRemainder() != null) {
                throw new IllegalArgumentException(
                        "fuel with a container remainder is outside the private slice");
            }
            fuelKey = defaultKey(smelt.fuelItem());
        }

        boolean ownershipContextLost(Minecraft minecraft) {
            return playerIdentity != null
                    && (minecraft.player != playerIdentity
                            || minecraft.level != levelIdentity
                            || minecraft.getConnection() != connectionIdentity);
        }

        void observeServerCursorProof(ScreenOwnershipSignals.Snapshot snapshot) {
            screenOwnedObserved |= snapshot.everOwned();
            if (snapshot.lastServerCursorProven()) {
                serverCursorProofFresh = true;
                serverCursorReleaseConfirmed = snapshot.lastServerCursorEmpty();
            }
        }

        boolean terminal() { return stage == Stage.TERMINAL; }
        boolean releasing() { return stage == Stage.RELEASING; }
        boolean releasingOrTerminal() { return releasing() || terminal(); }

        void latchSuccess() { latch(TerminalIntent.success()); }
        void latchFailure(RoutineFailure value) {
            latch(TerminalIntent.failure(value));
        }
        void latchInconclusive(String reason) {
            latch(TerminalIntent.inconclusive(reason));
        }

        private void latch(TerminalIntent value) {
            if (terminalIntent == null) terminalIntent = Objects.requireNonNull(value);
            if (!releaseConfirmed) stage = Stage.RELEASING;
        }

        void publishLatchedTerminal() {
            if (!releaseConfirmed || terminalIntent == null) {
                throw new IllegalStateException(
                        "furnace terminal cannot publish before release");
            }
            switch (terminalIntent.kind()) {
                case SUCCESS -> {
                    var verified = new LinkedHashMap<String, Object>(
                            Objects.requireNonNull(pendingResultBasis));
                    verified.put("screen_release_confirmed", screenReleaseConfirmed);
                    verified.put("server_cursor_empty", serverCursorReleaseConfirmed);
                    verified.put("view_slot_release_confirmed", true);
                    result = new PhaseFiveResult(
                            smelt.maxSmelts(), true, verified, List.of());
                }
                case FAILURE -> failure = terminalIntent.failure();
                case INCONCLUSIVE -> inconclusive = terminalIntent.inconclusive();
            }
            stage = Stage.TERMINAL;
        }

        String targetIdentity() {
            BlockTarget target = smelt.target();
            return target.dimension() + ":" + target.x() + ","
                    + target.y() + "," + target.z();
        }

        Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
            basis.put("open_count", openCount);
            basis.put("container_clicks", containerClicks);
            basis.put("recipe_placements", 0);
            basis.put("loaded_readback", loadedReadbackConfirmed);
            basis.put("smelt_started", smeltStarted);
            basis.put("smelt_completed", smeltCompleted);
            basis.put("fuel_recovered", fuelRecovered);
            basis.put("result_recovered", resultRecovered);
            basis.put("fresh_readback", finalReadbackConfirmed);
            basis.put("release_pending", releasing());
            basis.put("release_confirmed", releaseConfirmed);
            basis.put("release_fault", releaseFault);
            basis.put("blind_retry", false);
            return basis;
        }
    }

    private static final class ViewSlotLease {
        private final LocalPlayer playerIdentity;
        private final Object levelIdentity;
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private long lastReleaseTurnTick = Long.MIN_VALUE;
        private boolean closed;

        private ViewSlotLease(LocalPlayer player) {
            playerIdentity = Objects.requireNonNull(player, "player");
            levelIdentity = player.level();
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            originalSlot = player.getInventory().getSelectedSlot();
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            expectedSlot = originalSlot;
        }

        static ViewSlotLease acquire(LocalPlayer player) {
            return new ViewSlotLease(player);
        }

        void selectOpenHand(Minecraft minecraft, OpenHandPlan plan) {
            requireUndisturbed(minecraft);
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            if (!plan.readyAtSlot(player)) {
                throw new IllegalStateException("side-effect-free open hand changed");
            }
            player.getInventory().setSelectedSlot(plan.selectedSlot());
            expectedSlot = plan.selectedSlot();
            if (!plan.ready(player)) {
                throw new IllegalStateException("side-effect-free open hand is not selected");
            }
        }

        void turnToward(Minecraft minecraft, Vec3 point) {
            requireUndisturbed(minecraft);
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            MinecraftKnownBrewingPort.Rotation delta = MinecraftKnownBrewingPort.boundedTurn(
                    player.getYRot(), player.getXRot(), target.yaw(), target.pitch(),
                    CAMERA_DEGREES_PER_TICK);
            player.turn(delta.yaw() / 0.15D, delta.pitch() / 0.15D);
            expectedYaw = player.getYRot();
            expectedPitch = player.getXRot();
        }

        boolean aligned(Minecraft minecraft, Vec3 point) {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            return Math.abs(Mth.wrapDegrees(target.yaw() - player.getYRot())) <= AIM_EPSILON
                    && Math.abs(target.pitch() - player.getXRot()) <= AIM_EPSILON;
        }

        boolean undisturbed(Minecraft minecraft) {
            if (closed || ownershipContextLost(minecraft)) return false;
            LocalPlayer player = minecraft.player;
            return Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - expectedPitch) <= ROTATION_EPSILON
                    && player.getInventory().getSelectedSlot() == expectedSlot;
        }

        boolean releaseStep(Minecraft minecraft, long tick) {
            if (closed) return releaseConfirmed(minecraft);
            if (ownershipContextLost(minecraft)) {
                closed = true;
                return true;
            }
            LocalPlayer player = minecraft.player;
            if (player == null || !undisturbed(minecraft)) return false;
            player.getInventory().setSelectedSlot(originalSlot);
            expectedSlot = originalSlot;
            if (tick != lastReleaseTurnTick) {
                MinecraftKnownBrewingPort.Rotation delta =
                        MinecraftKnownBrewingPort.boundedTurn(
                                player.getYRot(), player.getXRot(),
                                originalYaw, originalPitch, CAMERA_DEGREES_PER_TICK);
                player.turn(delta.yaw() / 0.15D, delta.pitch() / 0.15D);
                expectedYaw = player.getYRot();
                expectedPitch = player.getXRot();
                lastReleaseTurnTick = tick;
            }
            if (player.getInventory().getSelectedSlot() == originalSlot
                    && Math.abs(Mth.wrapDegrees(player.getYRot() - originalYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - originalPitch) <= ROTATION_EPSILON) {
                closed = true;
            }
            return releaseConfirmed(minecraft);
        }

        private boolean releaseConfirmed(Minecraft minecraft) {
            if (closed && ownershipContextLost(minecraft)) return true;
            LocalPlayer player = minecraft.player;
            return closed && player != null
                    && player.getInventory().getSelectedSlot() == originalSlot
                    && Math.abs(Mth.wrapDegrees(player.getYRot() - originalYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - originalPitch) <= ROTATION_EPSILON;
        }

        private void requireUndisturbed(Minecraft minecraft) {
            if (!undisturbed(minecraft)) {
                throw new IllegalStateException("furnace view/slot ownership changed");
            }
        }

        private boolean ownershipContextLost(Minecraft minecraft) {
            return minecraft.player != playerIdentity || minecraft.level != levelIdentity;
        }

        static Rotation rotation(Vec3 from, Vec3 to) {
            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            return new Rotation(
                    (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F,
                    (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
        }
    }

    record Rotation(float yaw, float pitch) { }
}
