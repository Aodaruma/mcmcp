package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ExpectedOpenToken;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
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
 * Physical-client adapter for one bounded, standard Vanilla brewing batch.
 *
 * <p>The adapter never retries a container click. Every click is followed by a fresh inbound
 * packet snapshot which must agree with the live {@link BrewingStandMenu}. Success additionally
 * requires a second normal-use open, a fresh full-content/data readback, an exact whole-player
 * inventory delta, an empty server cursor, and confirmed screen/view/slot release.</p>
 */
public final class MinecraftKnownBrewingPort implements PhaseFivePort {
    static final String KIND = "brew_known_potion_batch";
    static final String MENU_TYPE = "minecraft:brewing_stand";
    static final int BOTTLE_SLOT_START = 0;
    static final int BOTTLE_SLOT_END = 2;
    static final int INGREDIENT_SLOT = 3;
    static final int FUEL_SLOT = 4;
    static final int PLAYER_SLOT_START = 5;
    static final int PLAYER_SLOT_END = 40;
    static final int MENU_SLOT_COUNT = 41;
    static final int DATA_BREW_TIME = 0;
    static final int DATA_FUEL = 1;
    static final int MAX_FUEL_USES = 20;
    static final int FUEL_USES_AFTER_LOADING = 20;

    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int CLICK_TIMEOUT_TICKS = 60;
    private static final int BREW_TIMEOUT_TICKS = 500;
    private static final int AIM_SETTLE_MARGIN_TICKS = 20;
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float AIM_EPSILON = 0.75F;
    private static final float ROTATION_EPSILON = 0.1F;
    // Cursor/menu ACK plus 270° restoration at the minimum admitted 0.75°/client-tick.
    private static final int RELEASE_TIMEOUT_TICKS = 520;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final MinecraftObservationService observations;
    private final ScreenOwnershipSignals screens;
    private final ContainerSyncSignals containerSignals;
    private final ClientPredictionSignals predictions;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftKnownBrewingPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            MinecraftObservationService observations,
            ScreenOwnershipSignals screens,
            ContainerSyncSignals containerSignals,
            ClientPredictionSignals predictions) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.containerSignals = Objects.requireNonNull(containerSignals, "containerSignals");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
    }

    public MinecraftKnownBrewingPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            MinecraftObservationService observations,
            ScreenOwnershipSignals screens,
            ContainerSyncSignals containerSignals) {
        this(minecraftSupplier, sessionSupplier, observations, screens, containerSignals,
                ClientPredictionSignals.global());
    }

    public MinecraftKnownBrewingPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            MinecraftObservationService observations,
            ScreenOwnershipSignals screens) {
        this(minecraftSupplier, sessionSupplier, observations, screens,
                ContainerSyncSignals.global(), ClientPredictionSignals.global());
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = requireMinecraft();
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        long revision = packetRevision();
        RoutineFailure failure;
        try {
            AttemptState active = activeState(request);
            failure = active == null
                    ? initialPreflight(minecraft, session, parse(request))
                    : active.releasingOrTerminal() ? null
                    : ongoingFailure(minecraft, session, active);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            failure = failure("BREWING_REQUEST_INVALID", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", boundedReason(exception)));
        }
        return new PhaseFiveFrame(tick, revision, failure);
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId,
            PhaseFiveRequest request,
            long hardDeadlineClientTick) {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = assertClientThread();
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        long revision = packetRevision();
        PhaseFiveAttempt attempt = new PhaseFiveAttempt(
                routineId, request.kind(), tick, revision, hardDeadlineClientTick,
                Map.of("verification", "brew_time_and_close_reopen_full_content",
                        "container_click_retry", "none",
                        "maximum_interactions", KnownBrewingRequest.MAX_INTERACTIONS));

        final KnownBrewingRequest brewing;
        try {
            brewing = parse(request);
        } catch (IllegalArgumentException exception) {
            AttemptState rejected = AttemptState.rejected(request);
            rejected.latchFailure(failure("BREWING_REQUEST_INVALID",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", boundedReason(exception))));
            attempts.put(attempt, rejected);
            return attempt;
        }

        AttemptState state = new AttemptState(request, brewing);
        attempts.put(attempt, state);
        RoutineFailure preflight = initialPreflight(minecraft, session, brewing);
        if (preflight != null) {
            state.latchFailure(preflight);
            return attempt;
        }

        try {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            state.prepareStaticPlan(minecraft);
            state.baseline = PlayerBaseline.capture(player, session);
            state.playerIdentity = player;
            state.levelIdentity = minecraft.level;
            state.connectionIdentity = minecraft.getConnection();
            state.initialTargetState = fingerprint(
                    Objects.requireNonNull(minecraft.level).getBlockState(
                            blockPos(brewing.target())));
            OpenHandPlan openHand = chooseOpenHand(player)
                    .orElseThrow(() -> new IllegalStateException(
                            "no side-effect-free normal-use hand is available"));
            state.ownership = ViewSlotLease.acquire(
                    player, brewing.maxCameraDegreesPerTick());
            state.ownership.selectOpenHand(minecraft, openHand);
            state.openHand = openHand;
            state.stage = Stage.AIMING_INITIAL;
            state.stageDeadlineClientTick = boundedDeadline(
                    tick, aimTimeoutTicks(brewing.maxCameraDegreesPerTick()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            state.latchFailure(failure("BREWING_PREPARATION_FAILED",
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
        if (state.terminal()) {
            return;
        }
        if (state.releasing()) {
            maintainTerminalRelease(attempt, state);
            return;
        }
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        RoutineFailure safety = ongoingFailure(minecraft, session, state);
        if (safety != null) {
            fail(state, safety);
            return;
        }
        if (tick >= attempt.hardDeadlineClientTick()) {
            inconclusive(state, "brewing_action_hard_deadline_exceeded");
            return;
        }
        if (timedStage(state.stage) && tick > state.stageDeadlineClientTick) {
            inconclusive(state, "brewing_stage_deadline_exceeded");
            return;
        }

        switch (state.stage) {
            case AIMING_INITIAL -> maintainAim(attempt, state, false);
            case OPENING_INITIAL -> acceptInitialSnapshot(attempt, state);
            case FUEL_TAKE_ACK -> maintainFuelTakeAck(attempt, state);
            case FUEL_PLACE_ACK -> maintainFuelPlaceAck(attempt, state);
            case FUEL_RETURN_ACK -> maintainFuelReturnAck(attempt, state);
            case AWAITING_FUEL_CONSUMPTION -> maintainFuelConsumption(attempt, state);
            case INPUT_DISPATCH -> dispatchNextInput(attempt, state);
            case INPUT_ACK -> maintainInputAck(attempt, state);
            case INGREDIENT_TAKE_ACK -> maintainIngredientTakeAck(attempt, state);
            case INGREDIENT_PLACE_ACK -> maintainIngredientPlaceAck(attempt, state);
            case INGREDIENT_RETURN_ACK -> maintainIngredientReturnAck(attempt, state);
            case AWAITING_BREW_START -> maintainBrewStart(attempt, state);
            case AWAITING_BREW_COMPLETE -> maintainBrewComplete(attempt, state);
            case REMAINDER_ACK -> maintainRemainderAck(attempt, state);
            case OUTPUT_DISPATCH -> dispatchNextOutput(attempt, state);
            case OUTPUT_ACK -> maintainOutputAck(attempt, state);
            case AWAITING_FIRST_CLOSE -> maintainClose(state, false);
            case AIMING_READBACK -> maintainAim(attempt, state, true);
            case OPENING_READBACK -> acceptReadbackSnapshot(attempt, state);
            case AWAITING_FINAL_CLOSE -> maintainClose(state, true);
            case RELEASING -> maintainTerminalRelease(attempt, state);
            case TERMINAL -> { }
        }
    }

    @Override
    public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
        AttemptState state = requireAttempt(attempt);
        long tick = currentTick();
        long revision = packetRevision();
        Map<String, Object> basis = state.basis();
        if (state.result != null) {
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), tick, revision, state.result, basis);
        }
        if (state.failure != null) {
            return new PhaseFiveEvidence.Failed(
                    attempt.attemptId(), tick, revision, state.failure, basis);
        }
        if (state.inconclusive != null) {
            return new PhaseFiveEvidence.Inconclusive(
                    attempt.attemptId(), tick, revision,
                    state.inconclusive.certainty(), state.inconclusive.reason(), basis);
        }
        // Every terminal intent stays adapter-private until screen/cursor/view/slot release is
        // confirmed. This applies equally to success, failure and inconclusive outcomes.
        return new PhaseFiveEvidence.Pending(attempt.attemptId(), tick, revision, basis);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        AttemptState state = attempts.get(attempt);
        if (state == null || state.releaseConfirmed) return;
        state.latchInconclusive("brewing_release_requested");
        maintainTerminalRelease(attempt, state);
        if (!state.releaseConfirmed) {
            throw new IllegalStateException("brewing release remains unconfirmed");
        }
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        var iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().request == request) {
                if (!entry.getValue().releaseConfirmed) {
                    throw new IllegalStateException(
                            "brewing request cannot retire before confirmed release");
                }
                iterator.remove();
            }
        }
    }

    /** Disconnect/level replacement fence; idempotent on the Minecraft client thread. */
    public void clearSession() {
        for (PhaseFiveAttempt attempt : List.copyOf(attempts.keySet())) {
            release(attempt);
        }
        attempts.clear();
    }

    private AttemptState activeState(PhaseFiveRequest request) {
        return attempts.values().stream()
                .filter(state -> state.request == request)
                .findFirst()
                .orElse(null);
    }

    private void maintainAim(
            PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        Minecraft minecraft = assertClientThread();
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        Optional<OpenHandPlan> openHand = chooseOpenHand(player);
        if (openHand.isEmpty()) {
            fail(state, failure("BREWING_SAFE_OPEN_HAND_UNAVAILABLE",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of()));
            return;
        }
        state.openHand = openHand.orElseThrow();
        state.ownership.selectOpenHand(minecraft, state.openHand);
        BlockTarget target = state.brewing.target();
        Vec3 point = new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D);
        state.ownership.turnToward(minecraft, point);
        if (state.ownership.aligned(minecraft, point)
                && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(blockPos(target))) {
            dispatchExpectedOpen(attempt, state, readback);
        }
    }

    private void dispatchExpectedOpen(
            PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        Minecraft minecraft = assertClientThread();
        WorldSessionTracker.Snapshot session = requireSession();
        if (!targetReadyForEmptyOpen(minecraft, state)) {
            fail(state, failure("BREWING_TARGET_NOT_EMPTY_FOR_OPEN",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("empty_brewing_stand", true), Map.of()));
            return;
        }
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        if (state.openHand == null || !state.openHand.ready(player)) {
            fail(state, failure("BREWING_SAFE_OPEN_HAND_CHANGED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of()));
            return;
        }
        if (!reserveInteraction(state)) {
            return;
        }

        long now = screens.currentTick();
        long remaining = Math.max(1L,
                attempt.hardDeadlineClientTick() - session.clientTick());
        long window = Math.min(OPEN_TIMEOUT_TICKS, remaining);
        long screenDeadline = now > Long.MAX_VALUE - window
                ? Long.MAX_VALUE : now + window;
        ExpectedOpenToken token = new ExpectedOpenToken(
                session.worldSessionId(), attempt.attemptId(), state.targetIdentity(),
                state.initialTargetState.toString(), MENU_TYPE, screenDeadline);
        if (!screens.beginExpectedOpen(token)) {
            fail(state, failure("BREWING_EXPECTED_OPEN_REJECTED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        BlockHitResult hit = exactHit(minecraft, state.brewing.target());
        ClientPredictionSignals.PredictionAttempt prediction;
        int sequenceBefore;
        try {
            prediction = predictions.begin(
                    Objects.requireNonNull(minecraft.level),
                    blockPos(state.brewing.target()), session.clientTick());
            sequenceBefore = prediction.sequenceBeforePrediction();
            state.openPrediction = prediction;
        } catch (ClientPredictionSignals.PredictionBridgeException failure) {
            screens.cancelRoutine(attempt.attemptId());
            fail(state, failure("BREWING_OPEN_PREDICTION_UNAVAILABLE",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        // Count the physical use even when Vanilla rejects it. It crossed the adapter boundary.
        state.openCount++;
        final boolean consumed;
        try {
            consumed = Objects.requireNonNull(minecraft.gameMode)
                    .useItemOn(player, state.openHand.hand(), hit)
                    .consumesAction();
            int sequenceAfter = prediction.captureIssuedPredictions();
            if (sequenceAfter != sequenceBefore + 1) {
                throw new ClientPredictionSignals.PredictionBridgeException(
                        "block-use prediction sequence did not advance exactly once");
            }
        } catch (RuntimeException | LinkageError dispatchFailure) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            fail(state, failure("BREWING_OPEN_PREDICTION_INCOMPATIBLE",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER, Map.of(), Map.of()));
            return;
        }
        if (!consumed) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            fail(state, failure("BREWING_NORMAL_USE_REJECTED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        state.stage = readback ? Stage.OPENING_READBACK : Stage.OPENING_INITIAL;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), OPEN_TIMEOUT_TICKS);
    }

    private void acceptInitialSnapshot(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) {
            return;
        }
        BrewingView view = optional.orElseThrow();
        closeOpenPrediction(state);
        if (!initialStandReady(view.snapshot().slots(), view.brewTime(), view.fuel())
                || !view.snapshot().carried().empty()) {
            fail(state, failure("BREWING_STAND_NOT_INITIAL_EMPTY",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("stand_slots", "empty", "brew_time", 0,
                            "fuel_uses", "0..20"),
                    Map.of()));
            return;
        }
        if (!validateLiveStandardInventory(view, state)) {
            fail(state, failure("BREWING_PACKET_LIVE_INVENTORY_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("standard_resources", true), Map.of()));
            return;
        }

        Map<StackKey, Integer> baseline = inventoryMultiset(
                view.snapshot().slots(), playerSlots());
        FuelPlan fuelPlan = fuelPlan(view.fuel());
        state.loadsInventoryFuel = fuelPlan.loadsInventoryFuel();
        state.expectedFuelUses = fuelPlan.expectedFuelUses();
        final Map<StackKey, Integer> expected;
        try {
            expected = expectedInventoryAfterBrew(
                    baseline, state.inputKey, state.brewing.input().count(),
                    state.fuelKey, state.loadsInventoryFuel,
                    state.ingredientKey, state.outputKey,
                    state.brewing.expectedOutput().count(), state.remainderKey);
        } catch (IllegalArgumentException exception) {
            fail(state, failure("BREWING_RESOURCES_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("required_resources", true), Map.of()));
            return;
        }
        if (chooseExactPotionSources(
                view.snapshot().slots(), playerSlots(), state.inputKey,
                state.brewing.input().count()).isEmpty()) {
            fail(state, failure("BREWING_INPUT_BOTTLES_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("exact_standard_input", true), Map.of()));
            return;
        }
        state.initialInventory = baseline;
        state.expectedInventory = expected;
        if (state.loadsInventoryFuel) {
            dispatchFuelTake(attempt, state, view);
        } else {
            state.stage = Stage.INPUT_DISPATCH;
            state.stageDeadlineClientTick = boundedDeadline(
                    currentTick(), CLICK_TIMEOUT_TICKS);
        }
    }

    private void dispatchFuelTake(
            PhaseFiveAttempt attempt, AttemptState state, BrewingView view) {
        Optional<Integer> source = chooseDefaultSource(
                view.snapshot().slots(), state.fuelKey);
        if (source.isEmpty()) {
            fail(state, failure("BREWING_FUEL_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("fuel", StandardPotionPolicy.FUEL_ITEM), Map.of()));
            return;
        }
        startCursorTransaction(state, view, source.orElseThrow(), FUEL_SLOT);
        dispatchContainerClick(attempt, state, view.menu(), source.orElseThrow(), 0,
                ContainerInput.PICKUP, Stage.FUEL_TAKE_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainFuelTakeAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!cursorTaken(view, state.cursor)) return;
        dispatchContainerClick(attempt, state, view.menu(), FUEL_SLOT, 1,
                ContainerInput.PICKUP, Stage.FUEL_PLACE_ACK, CLICK_TIMEOUT_TICKS);
        state.fuelPlacementPacketRevision = state.lastDispatchPacketRevision;
    }

    private void maintainFuelPlaceAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!onePlacedOrConsumed(view, state.cursor, true)) return;
        if (state.cursor.original().count() == 1) {
            state.stage = Stage.AWAITING_FUEL_CONSUMPTION;
            state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
            return;
        }
        dispatchContainerClick(attempt, state, view.menu(), state.cursor.sourceSlot(), 0,
                ContainerInput.PICKUP, Stage.FUEL_RETURN_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainFuelReturnAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!cursorReturned(view, state.cursor)) return;
        state.stage = Stage.AWAITING_FUEL_CONSUMPTION;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void maintainFuelConsumption(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!view.snapshot().slots().get(FUEL_SLOT).empty()
                || !view.snapshot().carried().empty()
                || view.brewTime() != 0
                || view.fuel() != FUEL_USES_AFTER_LOADING
                || view.fuelEvidence().packetLedgerRevision()
                        <= state.fuelPlacementPacketRevision) {
            return;
        }
        state.cursor = null;
        state.stage = Stage.INPUT_DISPATCH;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void dispatchNextInput(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int remaining = state.brewing.input().count() - state.inputsMoved;
        if (remaining <= 0) {
            dispatchIngredientTake(attempt, state, view);
            return;
        }
        Optional<List<Integer>> candidates = chooseExactPotionSources(
                view.snapshot().slots(), playerSlots(), state.inputKey, remaining);
        if (candidates.isEmpty() || candidates.orElseThrow().isEmpty()) {
            fail(state, failure("BREWING_INPUT_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("remaining_input", remaining), Map.of()));
            return;
        }
        int source = candidates.orElseThrow().getFirst();
        ContainerSyncSignals.StackFingerprint stack = view.snapshot().slots().get(source);
        if (stack.count() != 1 || state.clickedInputSlots.contains(source)) {
            fail(state, failure("BREWING_INPUT_CLICK_PLAN_INVALID",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("single_bottle_stack", true), Map.of()));
            return;
        }
        state.inputSourceSlot = source;
        state.inputPlayerBefore = countKey(
                view.snapshot().slots(), playerSlots(), state.inputKey);
        state.inputStandBefore = countKey(
                view.snapshot().slots(), bottleSlots(), state.inputKey);
        dispatchContainerClick(attempt, state, view.menu(), source, 0,
                ContainerInput.QUICK_MOVE, Stage.INPUT_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainInputAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int playerAfter = countKey(view.snapshot().slots(), playerSlots(), state.inputKey);
        int standAfter = countKey(view.snapshot().slots(), bottleSlots(), state.inputKey);
        if (!view.snapshot().slots().get(state.inputSourceSlot).empty()
                || !view.snapshot().carried().empty()
                || playerAfter != state.inputPlayerBefore - 1
                || standAfter != state.inputStandBefore + 1
                || !standContainsExactPotion(view, state.brewing.input(), standAfter)) {
            return;
        }
        state.clickedInputSlots.add(state.inputSourceSlot);
        state.inputsMoved++;
        state.stage = Stage.INPUT_DISPATCH;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void dispatchIngredientTake(
            PhaseFiveAttempt attempt, AttemptState state, BrewingView view) {
        Optional<Integer> source = chooseDefaultSource(
                view.snapshot().slots(), state.ingredientKey);
        if (source.isEmpty()) {
            fail(state, failure("BREWING_INGREDIENT_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("ingredient", state.brewing.ingredientItem()), Map.of()));
            return;
        }
        startCursorTransaction(state, view, source.orElseThrow(), INGREDIENT_SLOT);
        dispatchContainerClick(attempt, state, view.menu(), source.orElseThrow(), 0,
                ContainerInput.PICKUP, Stage.INGREDIENT_TAKE_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainIngredientTakeAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!cursorTaken(view, state.cursor)) return;
        dispatchContainerClick(attempt, state, view.menu(), INGREDIENT_SLOT, 1,
                ContainerInput.PICKUP, Stage.INGREDIENT_PLACE_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainIngredientPlaceAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!onePlacedOrConsumed(view, state.cursor, false)) return;
        state.ingredientDispatchPacketRevision = state.lastDispatchPacketRevision;
        if (state.cursor.original().count() == 1) {
            state.stage = Stage.AWAITING_BREW_START;
            state.stageDeadlineClientTick = boundedDeadline(currentTick(), BREW_TIMEOUT_TICKS);
            return;
        }
        dispatchContainerClick(attempt, state, view.menu(), state.cursor.sourceSlot(), 0,
                ContainerInput.PICKUP, Stage.INGREDIENT_RETURN_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainIngredientReturnAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (!cursorReturned(view, state.cursor)) return;
        state.stage = Stage.AWAITING_BREW_START;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), BREW_TIMEOUT_TICKS);
    }

    private void maintainBrewStart(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int inputCount = countKey(
                view.snapshot().slots(), bottleSlots(), state.inputKey);
        if (view.brewTime() <= 0
                || !fuelUseConfirmedAfterIngredient(
                        view.fuel(), state.expectedFuelUses,
                        view.fuelEvidence().packetLedgerRevision(),
                        state.ingredientDispatchPacketRevision)
                || view.brewTimeEvidence().packetLedgerRevision()
                        <= state.ingredientDispatchPacketRevision
                || inputCount != state.brewing.input().count()
                || !standContainsExactPotion(view, state.brewing.input(), inputCount)
                || !matches(view.snapshot().slots().get(INGREDIENT_SLOT),
                        state.ingredientKey, 1)
                || !view.snapshot().slots().get(FUEL_SLOT).empty()
                || !view.snapshot().carried().empty()) {
            return;
        }
        state.brewStarted = true;
        state.brewStartDataRevision = view.brewTimeEvidence().packetLedgerRevision();
        state.cursor = null;
        state.stage = Stage.AWAITING_BREW_COMPLETE;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), BREW_TIMEOUT_TICKS);
    }

    private void maintainBrewComplete(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int outputCount = countKey(
                view.snapshot().slots(), bottleSlots(), state.outputKey);
        boolean remainderMatches = state.remainderKey == null
                ? view.snapshot().slots().get(INGREDIENT_SLOT).empty()
                : matches(view.snapshot().slots().get(INGREDIENT_SLOT),
                        state.remainderKey, 1);
        if (!state.brewStarted
                || view.brewTime() != 0
                || view.brewTimeEvidence().packetLedgerRevision()
                        <= state.brewStartDataRevision
                || view.fuel() != state.expectedFuelUses
                || outputCount != state.brewing.expectedOutput().count()
                || !standContainsExactPotion(
                        view, state.brewing.expectedOutput(), outputCount)
                || !remainderMatches
                || !view.snapshot().slots().get(FUEL_SLOT).empty()
                || !view.snapshot().carried().empty()) {
            return;
        }
        state.brewCompleted = true;
        if (state.remainderKey != null) {
            dispatchContainerClick(attempt, state, view.menu(), INGREDIENT_SLOT, 0,
                    ContainerInput.QUICK_MOVE, Stage.REMAINDER_ACK,
                    CLICK_TIMEOUT_TICKS);
        } else {
            state.stage = Stage.OUTPUT_DISPATCH;
            state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
        }
    }

    private void maintainRemainderAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int expected = state.initialInventory.getOrDefault(state.remainderKey, 0) + 1;
        if (!view.snapshot().slots().get(INGREDIENT_SLOT).empty()
                || !view.snapshot().carried().empty()
                || countKey(view.snapshot().slots(), playerSlots(), state.remainderKey)
                        != expected) {
            return;
        }
        state.remainderRecovered = true;
        state.stage = Stage.OUTPUT_DISPATCH;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void dispatchNextOutput(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        if (state.outputsRecovered >= state.brewing.expectedOutput().count()) {
            if (!standSlotsEmpty(view.snapshot().slots())
                    || !view.snapshot().carried().empty()
                    || view.brewTime() != 0
                    || view.fuel() != state.expectedFuelUses) {
                return;
            }
            closeForReadback(attempt, state, false);
            return;
        }
        int slot = firstMatchingSlot(
                view.snapshot().slots(), bottleSlots(), state.outputKey).orElse(-1);
        if (slot < 0 || state.clickedOutputSlots.contains(slot)) {
            fail(state, failure("BREWING_OUTPUT_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("standard_output", true), Map.of()));
            return;
        }
        state.outputSourceSlot = slot;
        state.outputPlayerBefore = countKey(
                view.snapshot().slots(), playerSlots(), state.outputKey);
        dispatchContainerClick(attempt, state, view.menu(), slot, 0,
                ContainerInput.QUICK_MOVE, Stage.OUTPUT_ACK, CLICK_TIMEOUT_TICKS);
    }

    private void maintainOutputAck(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = freshBrewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        int playerAfter = countKey(
                view.snapshot().slots(), playerSlots(), state.outputKey);
        if (!view.snapshot().slots().get(state.outputSourceSlot).empty()
                || !view.snapshot().carried().empty()
                || playerAfter != state.outputPlayerBefore + 1) {
            return;
        }
        state.clickedOutputSlots.add(state.outputSourceSlot);
        state.outputsRecovered++;
        state.stage = Stage.OUTPUT_DISPATCH;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), CLICK_TIMEOUT_TICKS);
    }

    private void closeForReadback(
            PhaseFiveAttempt attempt, AttemptState state, boolean finalClose) {
        ScreenOwnershipSignals.CleanupDecision decision =
                screens.cancelRoutine(attempt.attemptId());
        if (!decision.authorityMatched() || !decision.closeMenuBestEffort()) {
            fail(state, failure("BREWING_CLOSE_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        if (!decision.serverCursorEmpty()) {
            fail(state, failure("BREWING_CURSOR_NOT_EMPTY",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("cursor", "empty"), Map.of()));
            return;
        }
        state.serverCursorReleaseConfirmed = true;
        closeOwnedMenuClient(requireMinecraft(), decision);
        state.stage = finalClose
                ? Stage.AWAITING_FINAL_CLOSE : Stage.AWAITING_FIRST_CLOSE;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), OPEN_TIMEOUT_TICKS);
    }

    private void maintainClose(AttemptState state, boolean terminalClose) {
        Minecraft minecraft = requireMinecraft();
        if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE
                || minecraft.gui.screen() != null
                || minecraft.player == null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
            return;
        }
        if (!terminalClose) {
            if (!targetReadyForEmptyOpen(minecraft, state)) {
                return;
            }
            state.stage = Stage.AIMING_READBACK;
            state.stageDeadlineClientTick = boundedDeadline(
                    currentTick(), aimTimeoutTicks(state.brewing.maxCameraDegreesPerTick()));
            return;
        }
        state.latchSuccess();
    }

    /** Cleanup-only path. Once entered it never dispatches brewing gameplay or replaces intent. */
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
                // A replacement player, level, or connection cannot receive any input or menu
                // click owned by the old attempt. This is a release proof, not a reason to write
                // the saved camera/slot into the replacement context.
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
                // No more menu clicks, close requests, or camera restoration after this bound.
                // The old identity is still watched passively on later ticks.
                state.releaseFault = true;
                return;
            }

            ScreenOwnershipSignals.Phase phase = snapshot.phase();
            if (phase == ScreenOwnershipSignals.Phase.OWNED) {
                Optional<BrewingView> optional = brewingView(attempt, state);
                if (optional.isEmpty()) return;
                BrewingView view = optional.orElseThrow();
                closeOpenPrediction(state);
                state.screenOwnedObserved = true;
                if (!state.serverCursorProofFresh) {
                    return;
                }
                if (!view.snapshot().carried().empty()) {
                    maintainCursorReturn(attempt, state, view);
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
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT) {
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
            if (phase == ScreenOwnershipSignals.Phase.FAILED) {
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
                    return;
                }
            }
            if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
                return;
            }
            if (state.screenOwnedObserved
                    && (minecraft.gui.screen() != null
                    || minecraft.player == null
                    || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                    || !minecraft.player.inventoryMenu.getCarried().isEmpty())) {
                return;
            }
            if (!state.screenOwnedObserved) {
                // The core's everOwned latch proves that no Agent container click was possible.
                // An unrelated pause/inventory Screen is neither closed nor used as cursor proof.
                state.serverCursorReleaseConfirmed = true;
            }
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
        ClientPredictionSignals.PredictionAcknowledgement acknowledgement =
                state.openPrediction.acknowledgement();
        return switch (acknowledgement.status()) {
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

    private void maintainCursorReturn(
            PhaseFiveAttempt attempt, AttemptState state, BrewingView view) {
        if (state.cleanupCursorClickDispatched) {
            if (view.packetLedgerRevision() <= state.cleanupCursorDispatchRevision) return;
            state.observeServerCursorProof(screens.snapshot());
            if (state.serverCursorProofFresh
                    && state.serverCursorReleaseConfirmed
                    && view.snapshot().carried().empty()) {
                state.cursor = null;
            }
            return;
        }
        if (!cursorReturnSafe(view.snapshot(), state.cursor)
                || state.openCount + state.containerClicks
                        >= KnownBrewingRequest.MAX_INTERACTIONS) {
            state.releaseFault = true;
            return;
        }
        state.cleanupCursorDispatchRevision = packetRevision();
        if (!invalidateServerCursorProof(
                attempt, state, state.cleanupCursorDispatchRevision)) {
            state.releaseFault = true;
            return;
        }
        state.containerClicks++;
        Objects.requireNonNull(requireMinecraft().gameMode).handleContainerInput(
                view.menu().containerId,
                state.cursor.sourceSlot(),
                0,
                ContainerInput.PICKUP,
                Objects.requireNonNull(requireMinecraft().player));
        state.cleanupCursorClickDispatched = true;
    }

    static boolean cursorReturnSafe(
            ContainerSyncSignals.ContainerSnapshot snapshot, CursorTransaction cursor) {
        if (cursor == null || snapshot.carried().empty()
                || !snapshot.slots().get(cursor.sourceSlot()).empty()) return false;
        ContainerSyncSignals.StackFingerprint carried = snapshot.carried();
        return StackKey.of(carried).equals(cursor.key())
                && carried.count() >= 1
                && carried.count() <= cursor.original().count();
    }

    private void acceptReadbackSnapshot(PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> optional = brewingView(attempt, state);
        if (optional.isEmpty()) return;
        BrewingView view = optional.orElseThrow();
        closeOpenPrediction(state);
        if (!standSlotsEmpty(view.snapshot().slots())
                || view.brewTime() != 0
                || view.fuel() != state.expectedFuelUses
                || !view.snapshot().carried().empty()
                || !inventoryReadbackMatches(
                        state.expectedInventory,
                        inventoryMultiset(view.snapshot().slots(), playerSlots()))
                || !validateReadbackOutputStacks(view, state)) {
            fail(state, failure("BREWING_READBACK_DELTA_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("exact_inventory_delta", true,
                            "stand_empty", true,
                            "cursor", "empty"), Map.of()));
            return;
        }
        state.readbackConfirmed = true;
        state.pendingResultBasis = Map.of(
                "brewed_potions", state.brewing.expectedOutput().count(),
                "brew_time_positive_observed", state.brewStarted,
                "brew_time_zero_observed", state.brewCompleted,
                "fresh_full_readback", true,
                "inventory_delta_exact", true,
                "cursor_empty", true);
        closeForReadback(attempt, state, true);
    }

    private void startCursorTransaction(
            AttemptState state, BrewingView view, int sourceSlot, int destinationSlot) {
        ContainerSyncSignals.StackFingerprint source = view.snapshot().slots().get(sourceSlot);
        if (source.empty() || source.count() < 1
                || !view.snapshot().carried().empty()) {
            throw new IllegalStateException("cursor transaction source is invalid");
        }
        state.cursor = new CursorTransaction(sourceSlot, destinationSlot, source);
    }

    private void dispatchContainerClick(
            PhaseFiveAttempt attempt,
            AttemptState state,
            BrewingStandMenu menu,
            int slot,
            int button,
            ContainerInput input,
            Stage nextStage,
            int timeoutTicks) {
        if (state.openCount + state.containerClicks
                >= KnownBrewingRequest.MAX_INTERACTIONS) {
            fail(state, failure("BREWING_INTERACTION_LIMIT",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE,
                    Map.of("maximum_interactions",
                            KnownBrewingRequest.MAX_INTERACTIONS), Map.of()));
            return;
        }
        Minecraft minecraft = requireMinecraft();
        if (screens.ownedSession().isEmpty()
                || minecraft.player == null
                || minecraft.player.containerMenu != menu) {
            fail(state, failure("BREWING_CLICK_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        long before = packetRevision();
        if (!invalidateServerCursorProof(attempt, state, before)) {
            fail(state, failure("BREWING_CLICK_AUTHORITY_LOST",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return;
        }
        // Account before dispatch so a rejected/throwing call cannot disappear from usage.
        state.containerClicks++;
        Objects.requireNonNull(minecraft.gameMode).handleContainerInput(
                menu.containerId, slot, button, input, minecraft.player);
        state.lastDispatchPacketRevision = before;
        state.stage = nextStage;
        state.stageDeadlineClientTick = boundedDeadline(currentTick(), timeoutTicks);
    }

    private boolean invalidateServerCursorProof(
            PhaseFiveAttempt attempt, AttemptState state, long dispatchRevision) {
        if (!screens.invalidateServerCursorProof(
                attempt.attemptId(), dispatchRevision)) {
            return false;
        }
        state.cursorProofRequiredAfterRevision = Math.max(
                state.cursorProofRequiredAfterRevision, dispatchRevision);
        state.serverCursorReleaseConfirmed = false;
        state.serverCursorProofFresh = false;
        return true;
    }

    private Optional<BrewingView> freshBrewingView(
            PhaseFiveAttempt attempt, AttemptState state) {
        Optional<BrewingView> view = brewingView(attempt, state);
        return view.filter(value -> value.packetLedgerRevision()
                > state.lastDispatchPacketRevision);
    }

    private Optional<BrewingView> brewingView(
            PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = requireMinecraft();
        Optional<ScreenOwnershipSignals.OwnedScreenSession> owned = screens.ownedSession();
        if (owned.isEmpty() || minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        ScreenOwnershipSignals.OwnedScreenSession ownership = owned.orElseThrow();
        if (!attempt.attemptId().equals(ownership.token().routineId())
                || !state.targetIdentity().equals(ownership.token().targetIdentity())
                || !MENU_TYPE.equals(ownership.token().menuTypeId())) {
            fail(state, failure("BREWING_SCREEN_AUTHORITY_MISMATCH",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of()));
            return Optional.empty();
        }
        if (!(minecraft.gui.screen() instanceof BrewingStandScreen screen)
                || !(minecraft.player.containerMenu instanceof BrewingStandMenu menu)
                || screen.getMenu() != menu
                || !exactBrewingLayout(menu, minecraft.player.getInventory())) {
            fail(state, failure("BREWING_MENU_LAYOUT_MISMATCH",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", MENU_TYPE, "slots", MENU_SLOT_COUNT), Map.of()));
            return Optional.empty();
        }
        ContainerSyncSignals.ContainerSnapshot snapshot = ownership.serverSnapshot();
        if (snapshot.containerId() != menu.containerId
                || !MENU_TYPE.equals(snapshot.menuTypeId())
                || snapshot.slots().size() != MENU_SLOT_COUNT) {
            fail(state, failure("BREWING_PACKET_LAYOUT_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", MENU_TYPE, "slots", MENU_SLOT_COUNT), Map.of()));
            return Optional.empty();
        }
        Optional<ContainerSyncSignals.Snapshot> ledgerOptional =
                containerSignals.snapshot(minecraft.level);
        if (ledgerOptional.isEmpty()) return Optional.empty();
        ContainerSyncSignals.Snapshot ledger = ledgerOptional.orElseThrow();
        if (!ledger.sameSession(ownership.token().worldSessionId())
                || ledger.container() == null
                || ledger.container().containerId() != menu.containerId
                || !MENU_TYPE.equals(ledger.container().menuTypeId())) {
            fail(state, failure("BREWING_PACKET_SESSION_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of()));
            return Optional.empty();
        }
        ContainerSyncSignals.ContainerDataEvidence brew = ledger.data().get(DATA_BREW_TIME);
        ContainerSyncSignals.ContainerDataEvidence fuel = ledger.data().get(DATA_FUEL);
        if (!freshDataForOpen(ledger.lastOpenScreen(), brew, menu.containerId)
                || !freshDataForOpen(ledger.lastOpenScreen(), fuel, menu.containerId)) {
            return Optional.empty();
        }
        if (!liveMenuMatches(snapshot, menu)
                || menu.getBrewingTicks() != brew.value()
                || menu.getFuel() != fuel.value()) {
            return Optional.empty();
        }
        long revision = Math.max(ledger.packetLedgerRevision(),
                screens.snapshot().packetLedgerRevision());
        return Optional.of(new BrewingView(
                menu, snapshot, brew, fuel, revision));
    }

    private RoutineFailure initialPreflight(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            KnownBrewingRequest brewing) {
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || !brewing.target().dimension().equals(session.dimension())
                || !brewing.target().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("BREWING_WORLD_NOT_READY",
                    RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("world_ready", true), Map.of());
        }
        if (!basicPlayerSafety(minecraft, null)) {
            return safetyFailure();
        }
        if (minecraft.gui.screen() != null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                || screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return failure("BREWING_SCREEN_NOT_CLEAR",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("screen", "clear", "ownership", "idle"), Map.of());
        }
        if (!targetReadyForEmptyOpen(minecraft, brewing.target(), null)) {
            return failure("BREWING_EMPTY_STAND_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("empty_vanilla_brewing_stand", true), Map.of());
        }
        if (chooseOpenHand(minecraft.player).isEmpty()) {
            return failure("BREWING_SAFE_OPEN_HAND_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("empty_offhand_or_safe_hotbar_item", true), Map.of());
        }
        BlockTarget target = brewing.target();
        Rotation targetRotation = ViewSlotLease.rotation(
                minecraft.player.getEyePosition(),
                new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D));
        if (oneWayCameraDegrees(
                minecraft.player.getYRot(), minecraft.player.getXRot(),
                targetRotation.yaw(), targetRotation.pitch())
                > KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES) {
            return failure("BREWING_CAMERA_PREFLIGHT_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("one_way_camera_degrees_at_most",
                                    (int) KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES,
                            "preceding_face_known_position_when_needed", true),
                    Map.of());
        }
        if (!StandardPotionPolicy.validateDeclaredTransition(
                minecraft.getConnection(), brewing.input(), brewing.ingredientItem(),
                brewing.expectedOutput())) {
            return failure("BREWING_RECIPE_NOT_CURRENT",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("one_step_standard_recipe", true), Map.of());
        }
        if (!liveInventoryHasRecipeInputs(minecraft.player, brewing)) {
            return failure("BREWING_RESOURCES_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("required_resources", true), Map.of());
        }
        return null;
    }

    private RoutineFailure ongoingFailure(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AttemptState state) {
        if (state.brewing == null) {
            return state.failure;
        }
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || state.baseline == null
                || !state.baseline.sameSession(session)
                || !state.brewing.target().dimension().equals(session.dimension())
                || !state.brewing.target().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("BREWING_WORLD_SESSION_CHANGED",
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
            return failure("BREWING_OWNED_SCREEN_FAILED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("owned_screen", true),
                    Map.of("reason", Objects.toString(screen.failureReason(), "unknown")));
        }
        if (!screenContextMatches(minecraft, state.stage, screen.phase())) {
            return failure("BREWING_SCREEN_CONTEXT_CHANGED",
                    RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("expected_screen_context", true), Map.of());
        }
        BlockPos target = blockPos(state.brewing.target());
        if (!minecraft.level.isLoaded(target)
                || !minecraft.level.getWorldBorder().isWithinBounds(target)
                || !minecraft.player.isWithinBlockInteractionRange(target, 0.0D)
                || !minecraft.level.getBlockState(target).is(Blocks.BREWING_STAND)
                || !(minecraft.level.getBlockEntity(target)
                        instanceof BrewingStandBlockEntity)) {
            return failure("BREWING_TARGET_CHANGED",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("brewing_stand_current", true), Map.of());
        }
        return null;
    }

    private boolean basicPlayerSafety(
            Minecraft minecraft, PlayerBaseline baseline) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null) return false;
        boolean baselineSafe = baseline == null || baseline.matches(player);
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
                && baselineSafe
                && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL
                && visibleThreatClear(minecraft, player, level);
    }

    private boolean visibleThreatClear(
            Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        return level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
    }

    private static boolean screenContextMatches(
            Minecraft minecraft,
            Stage stage,
            ScreenOwnershipSignals.Phase phase) {
        if (stage == Stage.AIMING_INITIAL || stage == Stage.AIMING_READBACK) {
            return phase == ScreenOwnershipSignals.Phase.IDLE
                    && minecraft.gui.screen() == null
                    && minecraft.player != null
                    && minecraft.player.containerMenu == minecraft.player.inventoryMenu;
        }
        if (stage == Stage.AWAITING_FIRST_CLOSE || stage == Stage.AWAITING_FINAL_CLOSE) {
            return phase == ScreenOwnershipSignals.Phase.CLOSING
                    || phase == ScreenOwnershipSignals.Phase.IDLE;
        }
        if (stage == Stage.OPENING_INITIAL || stage == Stage.OPENING_READBACK) {
            return phase == ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_SCREEN
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT
                    || phase == ScreenOwnershipSignals.Phase.OWNED;
        }
        return phase == ScreenOwnershipSignals.Phase.OWNED
                && minecraft.gui.screen() instanceof BrewingStandScreen
                && minecraft.player != null
                && minecraft.player.containerMenu instanceof BrewingStandMenu;
    }

    private static boolean liveInventoryHasRecipeInputs(
            LocalPlayer player, KnownBrewingRequest brewing) {
        int input = 0;
        int ingredient = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            Optional<StandardPotionPolicy.Identity> potion =
                    StandardPotionPolicy.identify(stack);
            if (potion.isPresent()
                    && potion.orElseThrow().count() == 1
                    && samePotion(potion.orElseThrow(), brewing.input())) {
                input = Math.addExact(input, stack.getCount());
            }
            if (isDefaultLiveStack(stack, brewing.ingredientItem())) {
                ingredient = Math.addExact(ingredient, stack.getCount());
            }
        }
        return input >= brewing.input().count()
                && ingredient >= 1;
    }

    private boolean validateLiveStandardInventory(
            BrewingView view, AttemptState state) {
        for (int slot : playerSlots()) {
            ItemStack live = view.menu().slots.get(slot).getItem();
            ContainerSyncSignals.StackFingerprint packet = view.snapshot().slots().get(slot);
            if (!ContainerSyncSignals.StackFingerprint.fromServerPacket(live).equals(packet)) {
                return false;
            }
            if (!packet.empty() && matches(packet, state.inputKey, packet.count())) {
                Optional<StandardPotionPolicy.Identity> identity =
                        StandardPotionPolicy.identify(live);
                if (identity.isEmpty()
                        || identity.orElseThrow().count() != 1
                        || !samePotion(identity.orElseThrow(), state.brewing.input())) {
                    return false;
                }
            }
            if ((packet.itemId().equals(state.fuelKey.itemId())
                    && packet.itemAndComponentsHash() == state.fuelKey.componentsHash()
                    || packet.itemId().equals(state.ingredientKey.itemId())
                    && packet.itemAndComponentsHash() == state.ingredientKey.componentsHash())
                    && !isDefaultLiveStack(live, packet.itemId())) {
                return false;
            }
        }
        return true;
    }

    private boolean validateReadbackOutputStacks(
            BrewingView view, AttemptState state) {
        int standard = 0;
        for (int slot : playerSlots()) {
            ContainerSyncSignals.StackFingerprint packet = view.snapshot().slots().get(slot);
            if (!matches(packet, state.outputKey, packet.count())) continue;
            ItemStack live = view.menu().slots.get(slot).getItem();
            Optional<StandardPotionPolicy.Identity> identity =
                    StandardPotionPolicy.identify(live);
            if (identity.isEmpty()
                    || identity.orElseThrow().count() != 1
                    || !samePotion(identity.orElseThrow(), state.brewing.expectedOutput())) {
                return false;
            }
            standard = Math.addExact(standard, live.getCount());
        }
        int expected = state.expectedInventory.getOrDefault(state.outputKey, 0);
        return standard == expected;
    }

    private static boolean standContainsExactPotion(
            BrewingView view, StandardPotionStackSpec spec, int expectedCount) {
        int count = 0;
        for (int slot : bottleSlots()) {
            ItemStack stack = view.menu().slots.get(slot).getItem();
            if (stack.isEmpty()) continue;
            Optional<StandardPotionPolicy.Identity> identity =
                    StandardPotionPolicy.identify(stack);
            if (identity.isEmpty()
                    || !samePotion(identity.orElseThrow(), spec)
                    || stack.getCount() != 1) {
                return false;
            }
            count++;
        }
        return count == expectedCount;
    }

    private static boolean cursorTaken(
            BrewingView view, CursorTransaction cursor) {
        if (cursor == null) return false;
        return view.snapshot().slots().get(cursor.sourceSlot()).empty()
                && sameStack(view.snapshot().carried(), cursor.original());
    }

    private static boolean onePlacedOrConsumed(
            BrewingView view, CursorTransaction cursor, boolean fuel) {
        if (cursor == null
                || !view.snapshot().slots().get(cursor.sourceSlot()).empty()) {
            return false;
        }
        int expectedCursor = cursor.original().count() - 1;
        if (!matches(view.snapshot().carried(), cursor.key(), expectedCursor)) {
            return false;
        }
        ContainerSyncSignals.StackFingerprint destination =
                view.snapshot().slots().get(cursor.destinationSlot());
        if (matches(destination, cursor.key(), 1)) return true;
        return fuel && destination.empty() && view.fuel() > 0;
    }

    private static boolean cursorReturned(
            BrewingView view, CursorTransaction cursor) {
        return cursor != null
                && view.snapshot().carried().empty()
                && matches(view.snapshot().slots().get(cursor.sourceSlot()),
                        cursor.key(), cursor.original().count() - 1);
    }

    private static boolean liveMenuMatches(
            ContainerSyncSignals.ContainerSnapshot snapshot,
            BrewingStandMenu menu) {
        if (snapshot.slots().size() != menu.slots.size()) return false;
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (!snapshot.slots().get(slot).equals(
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(
                            menu.slots.get(slot).getItem()))) {
                return false;
            }
        }
        return snapshot.carried().equals(
                ContainerSyncSignals.StackFingerprint.fromServerPacket(menu.getCarried()));
    }

    static boolean freshDataForOpen(
            ContainerSyncSignals.OpenScreenEvidence open,
            ContainerSyncSignals.ContainerDataEvidence data,
            int containerId) {
        return open != null && data != null
                && data.worldSessionId().equals(open.worldSessionId())
                && data.containerId() == containerId
                && data.containerId() == open.containerId()
                && data.menuTypeId().equals(MENU_TYPE)
                && open.menuTypeId().equals(MENU_TYPE)
                && data.packetLedgerRevision() > open.packetLedgerRevision();
    }

    private static boolean exactBrewingLayout(
            BrewingStandMenu menu, Inventory inventory) {
        if (menu.slots.size() != MENU_SLOT_COUNT) return false;
        Object stand = menu.slots.get(BOTTLE_SLOT_START).container;
        for (int slot = BOTTLE_SLOT_START; slot <= FUEL_SLOT; slot++) {
            if (menu.slots.get(slot).container != stand
                    || menu.slots.get(slot).getContainerSlot() != slot) {
                return false;
            }
        }
        for (int slot = PLAYER_SLOT_START; slot <= PLAYER_SLOT_END; slot++) {
            if (menu.slots.get(slot).container != inventory) return false;
        }
        var containerSlots = new ArrayList<Integer>(PLAYER_SLOT_END - PLAYER_SLOT_START + 1);
        for (int slot = PLAYER_SLOT_START; slot <= PLAYER_SLOT_END; slot++) {
            containerSlots.add(menu.slots.get(slot).getContainerSlot());
        }
        return exactPlayerSlotOrder(containerSlots);
    }

    static boolean exactPlayerSlotOrder(List<Integer> containerSlots) {
        Objects.requireNonNull(containerSlots, "containerSlots");
        if (containerSlots.size() != PLAYER_SLOT_END - PLAYER_SLOT_START + 1) return false;
        for (int index = 0; index < containerSlots.size(); index++) {
            int expected = index < 27 ? index + 9 : index - 27;
            if (containerSlots.get(index) == null
                    || containerSlots.get(index) != expected) return false;
        }
        return true;
    }

    static boolean initialStandReady(
            List<ContainerSyncSignals.StackFingerprint> slots,
            int brewTime,
            int fuel) {
        return standSlotsEmpty(slots)
                && brewTime == 0
                && fuel >= 0
                && fuel <= MAX_FUEL_USES;
    }

    static boolean fuelUseConfirmedAfterIngredient(
            int observedFuel,
            int expectedFuel,
            long fuelPacketRevision,
            long ingredientDispatchPacketRevision) {
        return expectedFuel >= 0
                && expectedFuel < MAX_FUEL_USES
                && observedFuel == expectedFuel
                && fuelPacketRevision > ingredientDispatchPacketRevision;
    }

    static FuelPlan fuelPlan(int initialFuelUses) {
        if (initialFuelUses < 0 || initialFuelUses > MAX_FUEL_USES) {
            throw new IllegalArgumentException("brewing fuel is outside Vanilla bounds");
        }
        boolean loadsInventoryFuel = initialFuelUses == 0;
        return new FuelPlan(
                loadsInventoryFuel,
                loadsInventoryFuel
                        ? FUEL_USES_AFTER_LOADING - 1 : initialFuelUses - 1);
    }

    static boolean standSlotsEmpty(
            List<ContainerSyncSignals.StackFingerprint> slots) {
        if (slots.size() != MENU_SLOT_COUNT) return false;
        for (int slot = BOTTLE_SLOT_START; slot <= FUEL_SLOT; slot++) {
            if (!slots.get(slot).empty()) return false;
        }
        return true;
    }

    static Optional<List<Integer>> chooseExactPotionSources(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<Integer> sourceSlots,
            StackKey potion,
            int requiredCount) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(sourceSlots, "sourceSlots");
        Objects.requireNonNull(potion, "potion");
        if (requiredCount < 1 || requiredCount > 3) return Optional.empty();
        var candidates = new ArrayList<Integer>();
        for (int slot : sourceSlots) {
            requireSlot(slots, slot);
            ContainerSyncSignals.StackFingerprint stack = slots.get(slot);
            if (matches(stack, potion, 1)) {
                candidates.add(slot);
            }
        }
        var chosen = new ArrayList<Integer>();
        return chooseExactPotionSources(
                slots, candidates, 0, requiredCount, chosen)
                ? Optional.of(List.copyOf(chosen)) : Optional.empty();
    }

    private static boolean chooseExactPotionSources(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<Integer> candidates,
            int index,
            int remaining,
            List<Integer> chosen) {
        if (remaining == 0) return true;
        if (index >= candidates.size()) return false;
        for (int candidate = index; candidate < candidates.size(); candidate++) {
            int slot = candidates.get(candidate);
            int count = slots.get(slot).count();
            if (count > remaining) continue;
            chosen.add(slot);
            if (chooseExactPotionSources(
                    slots, candidates, candidate + 1, remaining - count, chosen)) {
                return true;
            }
            chosen.removeLast();
        }
        return false;
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

    static Map<StackKey, Integer> expectedInventoryAfterBrew(
            Map<StackKey, Integer> baseline,
            StackKey input,
            int inputCount,
            StackKey fuel,
            boolean consumesInventoryFuel,
            StackKey ingredient,
            StackKey output,
            int outputCount,
            StackKey remainder) {
        var expected = new LinkedHashMap<>(Objects.requireNonNull(baseline, "baseline"));
        adjust(expected, Objects.requireNonNull(input, "input"), -inputCount);
        Objects.requireNonNull(fuel, "fuel");
        if (consumesInventoryFuel) adjust(expected, fuel, -1);
        adjust(expected, Objects.requireNonNull(ingredient, "ingredient"), -1);
        adjust(expected, Objects.requireNonNull(output, "output"), outputCount);
        if (remainder != null) adjust(expected, remainder, 1);
        return Collections.unmodifiableMap(expected);
    }

    static boolean inventoryReadbackMatches(
            Map<StackKey, Integer> expected,
            Map<StackKey, Integer> actual) {
        return Map.copyOf(Objects.requireNonNull(expected, "expected"))
                .equals(Map.copyOf(Objects.requireNonNull(actual, "actual")));
    }

    private static void adjust(
            Map<StackKey, Integer> values, StackKey key, int delta) {
        int before = values.getOrDefault(key, 0);
        int after = Math.addExact(before, delta);
        if (after < 0) {
            throw new IllegalArgumentException("inventory resource delta is impossible");
        }
        if (after == 0) values.remove(key);
        else values.put(key, after);
    }

    private static Optional<Integer> chooseDefaultSource(
            List<ContainerSyncSignals.StackFingerprint> slots, StackKey key) {
        return firstMatchingSlot(slots, playerSlots(), key);
    }

    private static Optional<Integer> firstMatchingSlot(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<Integer> candidates,
            StackKey key) {
        for (int slot : candidates) {
            requireSlot(slots, slot);
            ContainerSyncSignals.StackFingerprint stack = slots.get(slot);
            if (matches(stack, key, stack.count()) && stack.count() > 0) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private static int countKey(
            List<ContainerSyncSignals.StackFingerprint> slots,
            List<Integer> candidates,
            StackKey key) {
        int count = 0;
        for (int slot : candidates) {
            requireSlot(slots, slot);
            ContainerSyncSignals.StackFingerprint stack = slots.get(slot);
            if (matches(stack, key, stack.count())) {
                count = Math.addExact(count, stack.count());
            }
        }
        return count;
    }

    private static boolean matches(
            ContainerSyncSignals.StackFingerprint stack,
            StackKey key,
            int count) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(key, "key");
        if (count == 0) return stack.empty();
        return !stack.empty()
                && stack.count() == count
                && stack.itemId().equals(key.itemId())
                && stack.itemAndComponentsHash() == key.componentsHash();
    }

    private static boolean sameStack(
            ContainerSyncSignals.StackFingerprint left,
            ContainerSyncSignals.StackFingerprint right) {
        return left.equals(right);
    }

    private static void requireSlot(
            List<ContainerSyncSignals.StackFingerprint> slots, int slot) {
        if (slot < 0 || slot >= slots.size()) {
            throw new IllegalArgumentException("slot is outside the packet snapshot");
        }
    }

    private static List<Integer> bottleSlots() {
        return List.of(0, 1, 2);
    }

    private static List<Integer> playerSlots() {
        var slots = new ArrayList<Integer>(36);
        for (int slot = PLAYER_SLOT_START; slot <= PLAYER_SLOT_END; slot++) {
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static boolean samePotion(
            StandardPotionPolicy.Identity identity, StandardPotionStackSpec spec) {
        return identity.item().equals(spec.item())
                && identity.potion().equals(spec.potion());
    }

    private static ItemStack standardPotionStack(StandardPotionStackSpec spec) {
        Identifier itemId = Identifier.tryParse(spec.item());
        Identifier potionId = Identifier.tryParse(spec.potion());
        if (itemId == null || potionId == null) {
            throw new IllegalArgumentException("standard potion identity is invalid");
        }
        var item = BuiltInRegistries.ITEM.get(itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "standard potion item is not registered"));
        var potion = BuiltInRegistries.POTION.get(potionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "standard potion is not registered"));
        return net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                item.value(), potion).copyWithCount(spec.count());
    }

    private static ItemStack defaultItemStack(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null) {
            throw new IllegalArgumentException("item identity is invalid");
        }
        var item = BuiltInRegistries.ITEM.get(identifier)
                .orElseThrow(() -> new IllegalArgumentException(
                        "item is not registered"));
        ItemStack stack = new ItemStack(item.value());
        if (stack.isEmpty()) throw new IllegalArgumentException("item is empty");
        return stack;
    }

    private static boolean isDefaultLiveStack(ItemStack stack, String itemId) {
        if (stack.isEmpty()) return false;
        ItemStack standard;
        try {
            standard = defaultItemStack(itemId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(stack, standard);
    }

    private static StackKey stackKey(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("empty stack has no key");
        return new StackKey(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                ItemStack.hashItemAndComponents(stack));
    }

    private static StackKey potionKey(StandardPotionStackSpec spec) {
        return stackKey(standardPotionStack(
                new StandardPotionStackSpec(spec.item(), spec.potion(), 1)));
    }

    private static StackKey defaultKey(String itemId) {
        return stackKey(defaultItemStack(itemId));
    }

    private static StackKey craftingRemainderKey(String ingredientItem) {
        ItemStack ingredient = defaultItemStack(ingredientItem);
        var remainder = ingredient.getCraftingRemainder();
        if (remainder == null) return null;
        ItemStack stack = remainder.create();
        if (stack.isEmpty()) return null;
        if (stack.getCount() != 1
                || !ItemStack.isSameItemSameComponents(
                        stack, defaultItemStack(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))) {
            throw new IllegalArgumentException("unsupported ingredient remainder");
        }
        return stackKey(stack);
    }

    private static Optional<OpenHandPlan> chooseOpenHand(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        if (player.getOffhandItem().isEmpty()) {
            return Optional.of(new OpenHandPlan(
                    InteractionHand.OFF_HAND, player.getInventory().getSelectedSlot()));
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return Optional.of(new OpenHandPlan(InteractionHand.MAIN_HAND, slot));
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (safeNormalUseStack(player.getInventory().getItem(slot))) {
                return Optional.of(new OpenHandPlan(InteractionHand.MAIN_HAND, slot));
            }
        }
        return Optional.empty();
    }

    /**
     * Exact base {@link Item} instances have no block-use override. BrewingStandBlock supplies the
     * normal menu interaction, so these stacks cannot place, ignite, pour, or mutate the target.
     */
    static boolean safeNormalUseStack(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem().getClass() != Item.class) return false;
        ItemStack defaultStack = new ItemStack(stack.getItem());
        return ItemStack.isSameItemSameComponents(stack, defaultStack);
    }

    private static boolean targetReadyForEmptyOpen(
            Minecraft minecraft, AttemptState state) {
        return targetReadyForEmptyOpen(
                minecraft, state.brewing.target(), state.initialTargetState);
    }

    private static boolean targetReadyForEmptyOpen(
            Minecraft minecraft,
            BlockTarget target,
            BlockStateFingerprint expected) {
        if (minecraft.level == null || minecraft.player == null) return false;
        BlockPos position = blockPos(target);
        if (!minecraft.level.isLoaded(position)
                || !minecraft.level.getWorldBorder().isWithinBounds(position)
                || !minecraft.player.isWithinBlockInteractionRange(position, 0.0D)) {
            return false;
        }
        BlockState state = minecraft.level.getBlockState(position);
        return state.is(Blocks.BREWING_STAND)
                && bottlesEmpty(state)
                && minecraft.level.getBlockEntity(position)
                        instanceof BrewingStandBlockEntity
                && (expected == null || expected.equals(fingerprint(state)));
    }

    private static boolean bottlesEmpty(BlockState state) {
        if (!(state.getBlock() instanceof BrewingStandBlock)) return false;
        for (var property : BrewingStandBlock.HAS_BOTTLE) {
            if (state.getValue(property)) return false;
        }
        return true;
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        return MinecraftPhaseFiveInventoryPort.fingerprintLiveState(state);
    }

    private static BlockHitResult exactHit(
            Minecraft minecraft, BlockTarget target) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || hit.isWorldBorderHit()
                || !hit.getBlockPos().equals(blockPos(target))) {
            throw new IllegalArgumentException("crosshair does not identify the exact brewing stand");
        }
        return hit;
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private boolean reserveInteraction(AttemptState state) {
        if (state.openCount + state.containerClicks
                < KnownBrewingRequest.MAX_INTERACTIONS) return true;
        fail(state, failure("BREWING_INTERACTION_LIMIT",
                RoutineFailure.Category.SAFETY,
                RoutineFailure.Recovery.NONE,
                Map.of("maximum_interactions",
                        KnownBrewingRequest.MAX_INTERACTIONS), Map.of()));
        return false;
    }

    private static void closeOwnedMenuClient(
            Minecraft minecraft, ScreenOwnershipSignals.CleanupDecision decision) {
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu().containerId != decision.containerId()
                || !ScreenOwnershipSignals.menuTypeId(screen.getMenu().getType())
                        .equals(decision.menuTypeId())) {
            throw new IllegalStateException("owned brewing screen changed before close");
        }
        screen.onClose();
    }

    private static KnownBrewingRequest parse(PhaseFiveRequest request) {
        if (!KIND.equals(request.kind())) {
            throw new IllegalArgumentException("unsupported brewing adapter kind");
        }
        requireExactKeys(request.parameters(), Set.of(
                "target", "input", "ingredient_item", "fuel_item", "expected_output",
                "max_camera_degrees_per_tick"));
        BlockTarget target = target(map(request.parameters().get("target"), "target"));
        StandardPotionStackSpec input = potion(
                map(request.parameters().get("input"), "input"));
        StandardPotionStackSpec output = potion(
                map(request.parameters().get("expected_output"), "expected_output"));
        return new KnownBrewingRequest(
                target, input,
                string(request.parameters().get("ingredient_item"), "ingredient_item"),
                string(request.parameters().get("fuel_item"), "fuel_item"),
                output,
                decimal(request.parameters().get("max_camera_degrees_per_tick"),
                        "max_camera_degrees_per_tick"),
                request);
    }

    private static StandardPotionStackSpec potion(Map<String, Object> value) {
        requireExactKeys(value, Set.of("item", "potion", "count"));
        return new StandardPotionStackSpec(
                string(value.get("item"), "item"),
                string(value.get("potion"), "potion"),
                integer(value.get("count"), "count"));
    }

    private static BlockTarget target(Map<String, Object> value) {
        requireExactKeys(value, Set.of("dimension", "x", "y", "z"));
        return new BlockTarget(
                string(value.get("dimension"), "dimension"),
                integer(value.get("x"), "x"),
                integer(value.get("y"), "y"),
                integer(value.get("z"), "z"));
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

    private static void requireExactKeys(
            Map<String, ?> value, Set<String> expected) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException("object fields do not match the brewing contract");
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

    private static float decimal(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.floatValue();
    }

    private AttemptState requireAttempt(PhaseFiveAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        AttemptState state = attempts.get(attempt);
        if (state == null) throw new IllegalStateException("brewing attempt is not active");
        return state;
    }

    private Minecraft assertClientThread() {
        Minecraft minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("brewing adapter must run on the client thread");
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
        long container = minecraft == null || minecraft.level == null
                ? 0L : containerSignals.snapshot(minecraft.level)
                        .map(ContainerSyncSignals.Snapshot::packetLedgerRevision)
                        .orElse(0L);
        return Math.max(screen, container);
    }

    private static long boundedDeadline(long now, int ticks) {
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

    static int aimTimeoutTicks(float maxCameraDegreesPerTick) {
        if (!Float.isFinite(maxCameraDegreesPerTick)
                || maxCameraDegreesPerTick
                        < KnownBrewingRequest.MIN_CAMERA_DEGREES_PER_TICK
                || maxCameraDegreesPerTick
                        > KnownBrewingRequest.MAX_CAMERA_DEGREES_PER_TICK) {
            throw new IllegalArgumentException("brewing camera limit is outside policy");
        }
        return Math.addExact(
                (int) Math.ceil(
                        KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES
                                / maxCameraDegreesPerTick),
                AIM_SETTLE_MARGIN_TICKS);
    }

    static double oneWayCameraDegrees(
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch) {
        if (!Float.isFinite(currentYaw) || !Float.isFinite(currentPitch)
                || !Float.isFinite(targetYaw) || !Float.isFinite(targetPitch)) {
            throw new IllegalArgumentException("camera rotation must be finite");
        }
        return Math.abs(Mth.wrapDegrees((double) targetYaw - currentYaw))
                + Math.abs((double) Mth.clamp(targetPitch, -90.0F, 90.0F) - currentPitch);
    }

    private static boolean timedStage(Stage stage) {
        return stage != Stage.TERMINAL;
    }

    private static RoutineFailure safetyFailure() {
        return failure("BREWING_SAFETY_CHANGED", RoutineFailure.Category.SAFETY,
                RoutineFailure.Recovery.USER,
                Map.of("universal_safety", true),
                Map.of("universal_safety", false));
    }

    private static void fail(AttemptState state, RoutineFailure failure) {
        state.latchFailure(failure);
    }

    private static void inconclusive(AttemptState state, String reason) {
        state.latchInconclusive(reason);
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

    enum Stage {
        AIMING_INITIAL,
        OPENING_INITIAL,
        FUEL_TAKE_ACK,
        FUEL_PLACE_ACK,
        FUEL_RETURN_ACK,
        AWAITING_FUEL_CONSUMPTION,
        INPUT_DISPATCH,
        INPUT_ACK,
        INGREDIENT_TAKE_ACK,
        INGREDIENT_PLACE_ACK,
        INGREDIENT_RETURN_ACK,
        AWAITING_BREW_START,
        AWAITING_BREW_COMPLETE,
        REMAINDER_ACK,
        OUTPUT_DISPATCH,
        OUTPUT_ACK,
        AWAITING_FIRST_CLOSE,
        AIMING_READBACK,
        OPENING_READBACK,
        AWAITING_FINAL_CLOSE,
        RELEASING,
        TERMINAL
    }

    record StackKey(String itemId, int componentsHash) {
        StackKey {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid stack item identity");
            }
        }

        static StackKey of(ContainerSyncSignals.StackFingerprint stack) {
            if (stack.empty()) throw new IllegalArgumentException("empty stack has no key");
            return new StackKey(stack.itemId(), stack.itemAndComponentsHash());
        }
    }

    record CursorTransaction(
            int sourceSlot,
            int destinationSlot,
            ContainerSyncSignals.StackFingerprint original) {
        CursorTransaction {
            if (sourceSlot < PLAYER_SLOT_START || sourceSlot > PLAYER_SLOT_END
                    || destinationSlot < BOTTLE_SLOT_START
                    || destinationSlot > FUEL_SLOT
                    || original.empty()) {
                throw new IllegalArgumentException("cursor transaction is invalid");
            }
        }

        StackKey key() {
            return StackKey.of(original);
        }
    }

    private record BrewingView(
            BrewingStandMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            ContainerSyncSignals.ContainerDataEvidence brewTimeEvidence,
            ContainerSyncSignals.ContainerDataEvidence fuelEvidence,
            long packetLedgerRevision) {
        BrewingView {
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(brewTimeEvidence, "brewTimeEvidence");
            Objects.requireNonNull(fuelEvidence, "fuelEvidence");
            if (packetLedgerRevision < 0) {
                throw new IllegalArgumentException("negative packet revision");
            }
        }

        int brewTime() {
            return brewTimeEvidence.value();
        }

        int fuel() {
            return fuelEvidence.value();
        }
    }

    record OpenHandPlan(InteractionHand hand, int selectedSlot) {
        OpenHandPlan {
            Objects.requireNonNull(hand, "hand");
            if (selectedSlot < 0 || selectedSlot > 8) {
                throw new IllegalArgumentException("open-hand slot is outside the hotbar");
            }
        }

        boolean ready(LocalPlayer player) {
            if (player.getInventory().getSelectedSlot() != selectedSlot) return false;
            ItemStack stack = hand == InteractionHand.OFF_HAND
                    ? player.getOffhandItem() : player.getMainHandItem();
            return stack.isEmpty()
                    || hand == InteractionHand.MAIN_HAND && safeNormalUseStack(stack);
        }

        boolean readyAtSlot(LocalPlayer player) {
            ItemStack stack = hand == InteractionHand.OFF_HAND
                    ? player.getOffhandItem()
                    : player.getInventory().getItem(selectedSlot);
            return stack.isEmpty()
                    || hand == InteractionHand.MAIN_HAND && safeNormalUseStack(stack);
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
        TerminalIntent {
            Objects.requireNonNull(kind, "kind");
            if ((kind == TerminalKind.FAILURE) != (failure != null)
                    || (kind == TerminalKind.INCONCLUSIVE) != (inconclusive != null)) {
                throw new IllegalArgumentException("brewing terminal intent is inconsistent");
            }
        }

        static TerminalIntent success() {
            return new TerminalIntent(TerminalKind.SUCCESS, null, null);
        }

        static TerminalIntent failure(RoutineFailure failure) {
            return new TerminalIntent(
                    TerminalKind.FAILURE, Objects.requireNonNull(failure, "failure"), null);
        }

        static TerminalIntent inconclusive(String reason) {
            return new TerminalIntent(
                    TerminalKind.INCONCLUSIVE,
                    null,
                    new InconclusiveState(PhaseFiveEvidence.Certainty.UNKNOWN, reason));
        }
    }

    private enum TerminalKind { SUCCESS, FAILURE, INCONCLUSIVE }

    private static final class AttemptState {
        private final PhaseFiveRequest request;
        private final KnownBrewingRequest brewing;
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
        private BlockStateFingerprint initialTargetState;
        private StackKey inputKey;
        private StackKey outputKey;
        private StackKey fuelKey;
        private StackKey ingredientKey;
        private StackKey remainderKey;
        private Map<StackKey, Integer> initialInventory = Map.of();
        private Map<StackKey, Integer> expectedInventory = Map.of();
        private Map<String, Object> pendingResultBasis;
        private CursorTransaction cursor;
        private final List<Integer> clickedInputSlots = new ArrayList<>();
        private final List<Integer> clickedOutputSlots = new ArrayList<>();
        private long stageDeadlineClientTick;
        private long lastDispatchPacketRevision;
        private long fuelPlacementPacketRevision;
        private long ingredientDispatchPacketRevision;
        private long brewStartDataRevision;
        private int expectedFuelUses = -1;
        private int openCount;
        private int containerClicks;
        private int inputsMoved;
        private int outputsRecovered;
        private int inputSourceSlot = -1;
        private int inputPlayerBefore;
        private int inputStandBefore;
        private int outputSourceSlot = -1;
        private int outputPlayerBefore;
        private boolean brewStarted;
        private boolean brewCompleted;
        private boolean loadsInventoryFuel;
        private boolean remainderRecovered;
        private boolean readbackConfirmed;
        private OpenHandPlan openHand;
        private ClientPredictionSignals.PredictionAttempt openPrediction;
        private long releaseStartedTick = -1L;
        private long releaseDeadlineTick;
        private long cleanupCursorDispatchRevision;
        private long cursorProofRequiredAfterRevision = -1L;
        private boolean cleanupCursorClickDispatched;
        private boolean screenOwnedObserved;
        private boolean serverCursorProofFresh;
        private boolean serverCursorReleaseConfirmed;
        private boolean screenReleaseConfirmed;
        private boolean releaseConfirmed;
        private boolean releaseFault;

        private AttemptState(PhaseFiveRequest request, KnownBrewingRequest brewing) {
            this.request = Objects.requireNonNull(request, "request");
            this.brewing = Objects.requireNonNull(brewing, "brewing");
        }

        private AttemptState(PhaseFiveRequest request) {
            this.request = Objects.requireNonNull(request, "request");
            this.brewing = null;
        }

        private boolean ownershipContextLost(Minecraft minecraft) {
            return playerIdentity != null
                    && (minecraft.player != playerIdentity
                            || minecraft.level != levelIdentity
                            || minecraft.getConnection() != connectionIdentity);
        }

        private void observeServerCursorProof(ScreenOwnershipSignals.Snapshot snapshot) {
            screenOwnedObserved |= snapshot.everOwned();
            if (snapshot.lastServerCursorProven()
                    && snapshot.lastServerCursorProofRevision()
                            > cursorProofRequiredAfterRevision) {
                serverCursorProofFresh = true;
                serverCursorReleaseConfirmed = snapshot.lastServerCursorEmpty();
            }
        }

        static AttemptState rejected(PhaseFiveRequest request) {
            return new AttemptState(request);
        }

        void prepareStaticPlan(Minecraft minecraft) {
            if (!StandardPotionPolicy.validateDeclaredTransition(
                    Objects.requireNonNull(minecraft.getConnection()),
                    brewing.input(), brewing.ingredientItem(), brewing.expectedOutput())) {
                throw new IllegalArgumentException("declared brewing transition is unavailable");
            }
            inputKey = potionKey(brewing.input());
            outputKey = potionKey(brewing.expectedOutput());
            fuelKey = defaultKey(brewing.fuelItem());
            ingredientKey = defaultKey(brewing.ingredientItem());
            remainderKey = craftingRemainderKey(brewing.ingredientItem());
        }

        boolean terminal() {
            return stage == Stage.TERMINAL;
        }

        boolean releasing() {
            return stage == Stage.RELEASING;
        }

        boolean releasingOrTerminal() {
            return releasing() || terminal();
        }

        void latchSuccess() {
            latch(TerminalIntent.success());
        }

        void latchFailure(RoutineFailure value) {
            latch(TerminalIntent.failure(value));
        }

        void latchInconclusive(String reason) {
            latch(TerminalIntent.inconclusive(reason));
        }

        private void latch(TerminalIntent intent) {
            if (terminalIntent == null) terminalIntent = Objects.requireNonNull(intent, "intent");
            if (!releaseConfirmed) stage = Stage.RELEASING;
        }

        void publishLatchedTerminal() {
            if (!releaseConfirmed || terminalIntent == null) {
                throw new IllegalStateException("brewing terminal cannot publish before release");
            }
            switch (terminalIntent.kind()) {
                case SUCCESS -> {
                    var verified = new LinkedHashMap<String, Object>(
                            Objects.requireNonNull(pendingResultBasis, "pendingResultBasis"));
                    verified.put("screen_release_confirmed", screenReleaseConfirmed);
                    verified.put("server_cursor_empty", serverCursorReleaseConfirmed);
                    verified.put("view_slot_release_confirmed", true);
                    result = new PhaseFiveResult(
                            brewing.expectedOutput().count(), true, verified, List.of());
                }
                case FAILURE -> failure = terminalIntent.failure();
                case INCONCLUSIVE -> inconclusive = terminalIntent.inconclusive();
            }
            stage = Stage.TERMINAL;
        }

        String targetIdentity() {
            BlockTarget target = brewing.target();
            return target.dimension() + ":" + target.x() + ","
                    + target.y() + "," + target.z();
        }

        Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
            basis.put("open_count", openCount);
            basis.put("container_clicks", containerClicks);
            basis.put("brew_time_positive_observed", brewStarted);
            basis.put("brew_time_zero_observed", brewCompleted);
            basis.put("outputs_recovered", outputsRecovered);
            basis.put("fresh_readback", readbackConfirmed);
            basis.put("release_pending", releasing());
            basis.put("release_confirmed", releaseConfirmed);
            basis.put("release_fault", releaseFault);
            basis.put("blind_retry", false);
            return basis;
        }
    }

    /** Bounded first-person camera/open-hand slot ownership with tick-driven restoration. */
    private static final class ViewSlotLease {
        private final LocalPlayer playerIdentity;
        private final Object levelIdentity;
        private final float maxCameraDegreesPerTick;
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private long lastReleaseTurnTick = Long.MIN_VALUE;
        private boolean closed;

        private ViewSlotLease(LocalPlayer player, float maxCameraDegreesPerTick) {
            this.playerIdentity = Objects.requireNonNull(player, "player");
            levelIdentity = player.level();
            this.maxCameraDegreesPerTick = maxCameraDegreesPerTick;
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            originalSlot = player.getInventory().getSelectedSlot();
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            expectedSlot = originalSlot;
        }

        static ViewSlotLease acquire(LocalPlayer player, float maxCameraDegreesPerTick) {
            return new ViewSlotLease(
                    Objects.requireNonNull(player, "player"), maxCameraDegreesPerTick);
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
            Rotation delta = boundedTurn(
                    player.getYRot(), player.getXRot(), target.yaw, target.pitch,
                    maxCameraDegreesPerTick);
            player.turn(delta.yaw / 0.15D, delta.pitch / 0.15D);
            expectedYaw = player.getYRot();
            expectedPitch = player.getXRot();
        }

        boolean aligned(Minecraft minecraft, Vec3 point) {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            return Math.abs(Mth.wrapDegrees(target.yaw - player.getYRot())) <= AIM_EPSILON
                    && Math.abs(target.pitch - player.getXRot()) <= AIM_EPSILON;
        }

        boolean undisturbed(Minecraft minecraft) {
            if (closed || ownershipContextLost(minecraft)) return false;
            LocalPlayer player = minecraft.player;
            return Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - expectedPitch) <= ROTATION_EPSILON
                    && player.getInventory().getSelectedSlot() == expectedSlot;
        }

        boolean releaseStep(Minecraft minecraft, long clientTick) {
            if (closed) return releaseConfirmed(minecraft);
            if (ownershipContextLost(minecraft)) {
                // The old player/world can no longer receive input. Never write its saved pose
                // or slot into a replacement player during respawn or level replacement.
                closed = true;
                return true;
            }
            LocalPlayer player = minecraft.player;
            if (player == null || !undisturbed(minecraft)) return false;
            player.getInventory().setSelectedSlot(originalSlot);
            expectedSlot = originalSlot;
            if (clientTick != lastReleaseTurnTick) {
                Rotation delta = boundedTurn(
                        player.getYRot(), player.getXRot(), originalYaw, originalPitch,
                        maxCameraDegreesPerTick);
                player.turn(delta.yaw / 0.15D, delta.pitch / 0.15D);
                expectedYaw = player.getYRot();
                expectedPitch = player.getXRot();
                lastReleaseTurnTick = clientTick;
            }
            if (player.getInventory().getSelectedSlot() == originalSlot
                    && Math.abs(Mth.wrapDegrees(player.getYRot() - originalYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - originalPitch) <= ROTATION_EPSILON) {
                closed = true;
            }
            return releaseConfirmed(minecraft);
        }

        boolean releaseConfirmed(Minecraft minecraft) {
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
                throw new IllegalStateException("brewing view/slot ownership changed");
            }
        }

        private boolean ownershipContextLost(Minecraft minecraft) {
            return minecraft.player != playerIdentity || minecraft.level != levelIdentity;
        }

        private static Rotation rotation(Vec3 from, Vec3 to) {
            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            return new Rotation(
                    (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F,
                    (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
        }
    }

    static Rotation boundedTurn(
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            float maximumTotalDegrees) {
        if (!Float.isFinite(maximumTotalDegrees) || maximumTotalDegrees <= 0.0F) {
            throw new IllegalArgumentException("camera limit must be finite and positive");
        }
        float yaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitch = Mth.clamp(targetPitch, -90.0F, 90.0F) - currentPitch;
        float total = Math.abs(yaw) + Math.abs(pitch);
        if (total > maximumTotalDegrees) {
            float scale = maximumTotalDegrees / total;
            yaw *= scale;
            // Floating-point rounding must not let the combined turn exceed the
            // action-level safety limit, even by one ulp.
            float remaining = Math.max(
                    0.0F, maximumTotalDegrees - Math.abs(yaw));
            pitch = Math.copySign(
                    Math.min(Math.abs(pitch * scale), remaining), pitch);
        }
        return new Rotation(yaw, pitch);
    }

    record Rotation(float yaw, float pitch) {
    }

    record FuelPlan(boolean loadsInventoryFuel, int expectedFuelUses) {
        FuelPlan {
            if (expectedFuelUses < 0 || expectedFuelUses >= MAX_FUEL_USES) {
                throw new IllegalArgumentException("invalid brewing fuel plan");
            }
        }
    }
}
