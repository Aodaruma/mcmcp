package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.observation.ContainerLabelResolver;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.ExpectedOpenToken;
import dev.aod.mcmcp.runtime.KnownMenuProfileSupport;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.extensions.IItemExtension;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Minecraft 26.2 adapter for the two Phase 5 inventory routines.
 *
 * <p>Each container click is single-shot. Transfer batches confirm each whole stack from fresh
 * server slot updates before the next click. Completion still requires closing the owned screen,
 * reopening the same exact block, and receiving a fresh full-content packet.</p>
 */
public final class MinecraftPhaseFiveInventoryPort implements PhaseFivePort {
    static final String CRAFT_ITEMS = "craft_items";
    static final String TRANSFER_ITEMS = "transfer_items";
    static final String CRAFTING_MENU = "minecraft:crafting";
    static final String SINGLE_CONTAINER_MENU = "minecraft:generic_9x3";
    static final String DOUBLE_CONTAINER_MENU = "minecraft:generic_9x6";
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int RELEASE_TIMEOUT_TICKS = 40;
    private static final int CRAFTING_GRID_LAST_SLOT = 9;
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final double MAX_HORIZONTAL_VELOCITY_SQUARED = 0.01D;
    private static final float LEGACY_MAX_TURN_PER_TICK = 8.0F;
    private static final float AIM_EPSILON = 0.75F;
    private static final float ROTATION_EPSILON = 0.1F;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final ClientRecipeCatalog recipes;
    private final MinecraftObservationService observations;
    private final ScreenOwnershipSignals screens;
    private final DoubleSupplier runtimeCameraDegreesPerTick;
    private final ClientPredictionSignals predictions;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftPhaseFiveInventoryPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            ClientRecipeCatalog recipes,
            MinecraftObservationService observations,
            ScreenOwnershipSignals screens,
            DoubleSupplier runtimeCameraDegreesPerTick,
            ClientPredictionSignals predictions) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.runtimeCameraDegreesPerTick = Objects.requireNonNull(
                runtimeCameraDegreesPerTick, "runtimeCameraDegreesPerTick");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        var minecraft = requireMinecraft();
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        long revision = packetRevision();
        RoutineFailure failure;
        try {
            var active = activeState(request);
            failure = active == null
                    ? preflight(minecraft, session, request)
                    : active.terminal() ? null : ongoingFailure(minecraft, session, active);
        } catch (IllegalArgumentException | IllegalStateException failureException) {
            failure = failure("INVENTORY_REQUEST_INVALID", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("kind", request.kind()),
                    Map.of("reason", boundedReason(failureException)));
        }
        return new PhaseFiveFrame(tick, revision, failure);
    }

    private AttemptState activeState(PhaseFiveRequest request) {
        return attempts.values().stream()
                .filter(state -> state.request == request)
                .findFirst()
                .orElse(null);
    }

    private RoutineFailure ongoingFailure(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AttemptState state) {
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || state.baseline == null || !state.baseline.sameSession(session)
                || !state.request.bounds().dimension().equals(session.dimension())
                || !state.request.bounds().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("INVENTORY_WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN, Map.of("world_ready", true), Map.of());
        }
        if (!basicPlayerSafety(minecraft, state.baseline)
                || state.view == null || !state.view.undisturbed(minecraft)) {
            return failure("INVENTORY_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("safe_survival_player", true), Map.of());
        }
        var screen = screens.snapshot();
        if (screen.phase() == ScreenOwnershipSignals.Phase.FAILED) {
            return failure("OWNED_SCREEN_FAILED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("owned_screen", true),
                    Map.of("reason", Objects.toString(screen.failureReason(), "unknown")));
        }
        if (!screenContextMatches(minecraft, state, screen.phase())) {
            return failure("INVENTORY_SCREEN_CONTEXT_CHANGED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("expected_screen_context", true), Map.of());
        }
        RoutineFailure handFailure = safeOpenHandFailure(
                state.stage, () -> state.openHand != null && state.openHand.ready(minecraft.player));
        if (handFailure != null) return handFailure;
        BlockPos target = blockPos(state.parameters.target());
        if (!minecraft.level.isLoaded(target)
                || !minecraft.level.getWorldBorder().isWithinBounds(target)
                || !minecraft.player.isWithinBlockInteractionRange(target, 0.0D)) {
            return failure("INVENTORY_TARGET_STATE_DIVERGED", RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("target_current", true), Map.of());
        }
        BlockState liveTarget = minecraft.level.getBlockState(target);
        boolean exactStateRequired = state.parameters instanceof CraftParameters
                || state.stage == Stage.AIMING_INITIAL
                || state.stage == Stage.AIMING_READBACK;
        if (exactStateRequired
                ? !state.parameters.expectedState().equals(fingerprint(liveTarget))
                : !sameTransferContainerIdentity(state.parameters.expectedState(), liveTarget)) {
            return failure("INVENTORY_TARGET_STATE_DIVERGED", RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("target_current", true), Map.of());
        }
        return null;
    }

    static RoutineFailure safeOpenHandFailure(Stage stage, BooleanSupplier ready) {
        // Only a future block-use requires a safe hand. Once dispatched, normal inventory
        // synchronization may change that stack while the owned full-content readback arrives.
        if ((stage == Stage.AIMING_INITIAL || stage == Stage.AIMING_READBACK) && !ready.getAsBoolean()) {
            return failure("INVENTORY_SAFE_OPEN_HAND_CHANGED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of());
        }
        return null;
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId,
            PhaseFiveRequest request,
            long hardDeadlineClientTick) {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(request, "request");
        var minecraft = assertClientThread();
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        var attempt = new PhaseFiveAttempt(
                routineId, request.kind(), tick, packetRevision(), hardDeadlineClientTick,
                Map.of("verification", "close_reopen_full_content",
                        "container_click_retry", "none"));
        var state = new AttemptState(request, parse(request));
        attempts.put(attempt, state);

        RoutineFailure preflight;
        try {
            preflight = preflight(minecraft, session, request);
            if (preflight == null && state.parameters instanceof CraftParameters craft) {
                state.recipe = resolveCraftRecipe(minecraft, session, tick, craft);
            }
        } catch (IllegalArgumentException | IllegalStateException failureException) {
            preflight = failure("INVENTORY_REQUEST_INVALID",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("kind", request.kind()),
                    Map.of("reason", boundedReason(failureException)));
        }
        if (preflight != null) {
            state.failure = preflight;
            state.stage = Stage.TERMINAL;
            return attempt;
        }
        try {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            state.baseline = PlayerBaseline.capture(player, Objects.requireNonNull(session));
            float cameraDegreesPerTick = state.parameters instanceof CraftParameters
                    ? configuredCameraDegreesPerTick()
                    : state.parameters.maxCameraDegreesPerTick();
            state.view = ViewLease.acquire(
                    player, state.parameters.restoreViewOnRelease(), cameraDegreesPerTick);
            if (selectOpenHand(minecraft, state, tick)) {
                state.stage = Stage.AIMING_INITIAL;
            }
        } catch (IllegalArgumentException | IllegalStateException preparationFailure) {
            fail(state, "INVENTORY_PREPARATION_FAILED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
        }
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        var minecraft = assertClientThread();
        var state = requireAttempt(attempt);
        if (state.releasePending && !state.releaseConfirmed) {
            maintainTerminalRelease(attempt, state);
            return;
        }
        if (state.terminal()) {
            return;
        }
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        RoutineFailure ongoing = ongoingFailure(minecraft, session, state);
        if (ongoing != null) {
            state.failure = ongoing;
            state.stage = Stage.TERMINAL;
            return;
        }
        if (tick >= attempt.hardDeadlineClientTick()) {
            state.inconclusive = new InconclusiveState(
                    PhaseFiveEvidence.Certainty.UNKNOWN,
                    "inventory_action_hard_deadline_exceeded");
            state.stage = Stage.TERMINAL;
            return;
        }
        var screenState = screens.snapshot();
        switch (state.stage) {
            case AIMING_INITIAL -> maintainAim(attempt, state, false);
            case AIMING_READBACK -> maintainAim(attempt, state, true);
            case OPENING_INITIAL, OPENING_READBACK, TRANSFER_READY ->
                    acceptOwnedSnapshot(attempt, state, minecraft);
            case CRAFT_WAIT_RESULT -> maintainCraftResult(attempt, state, minecraft);
            case AWAITING_CLICK_ACK -> maintainClickAck(attempt, state);
            case AWAITING_CLOSE -> {
                if (screenState.phase() == ScreenOwnershipSignals.Phase.IDLE
                        && AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                        && minecraft.player != null
                        && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
                    if (targetReadyForReopen(minecraft, state.parameters)) {
                        if (selectOpenHand(minecraft, state, tick)) {
                            state.aimGate = new ContainerAimGate();
                            state.stage = Stage.AIMING_READBACK;
                        }
                    } else if (tick > state.closeDeadlineClientTick) {
                        state.inconclusive = new InconclusiveState(
                                PhaseFiveEvidence.Certainty.UNKNOWN,
                                "target_not_ready_for_same_target_readback");
                        state.stage = Stage.TERMINAL;
                    }
                }
            }
            case RELEASING -> maintainTerminalRelease(attempt, state);
            case TERMINAL -> { }
        }
    }

    @Override
    public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
        var state = requireAttempt(attempt);
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
        return new PhaseFiveEvidence.Pending(attempt.attemptId(), tick, revision, basis);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        var state = attempts.get(attempt);
        if (state == null || state.releaseConfirmed) {
            return;
        }
        state.releasePending = true;
        state.stage = Stage.RELEASING;
        maintainTerminalRelease(attempt, state);
        if (!state.releaseConfirmed) {
            throw new IllegalStateException(state.releaseFault
                    ? "inventory release failed closed"
                    : "inventory release remains unconfirmed", state.releaseFaultCause);
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
                            "inventory request cannot retire before confirmed release");
                }
                iterator.remove();
            }
        }
    }

    /** Disconnect/level-replacement fence; safe to call repeatedly on the client thread. */
    public void clearSession() {
        for (var attempt : List.copyOf(attempts.keySet())) {
            screens.releaseRoutineOnIdentityLoss(attempt.attemptId());
            var state = attempts.get(attempt);
            closeOpenPrediction(state);
            state.closeView(requireMinecraft());
            state.releaseConfirmed = true;
        }
        attempts.clear();
    }

    private void acceptOwnedSnapshot(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        Optional<ScreenOwnershipSignals.OwnedScreenSession> owned = screens.ownedSession();
        if (owned.isEmpty()) {
            return;
        }
        var session = owned.orElseThrow();
        state.screenOwnedObserved = true;
        if (!attempt.attemptId().equals(session.token().routineId())
                || !state.targetIdentity().equals(session.token().targetIdentity())) {
            fail(state, "OWNED_SCREEN_AUTHORITY_MISMATCH", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of());
            return;
        }
        closeOpenPrediction(state);
        var player = minecraft.player;
        if (player == null || !session.serverSnapshot().carried().empty()) {
            fail(state, "OWNED_SCREEN_CURSOR_NOT_EMPTY", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER, Map.of("cursor", "empty"), Map.of());
            return;
        }
        var menu = player.containerMenu;
        if (menu.containerId != session.serverSnapshot().containerId()
                || menu.slots.size() != session.serverSnapshot().slots().size()) {
            fail(state, "OWNED_SCREEN_SLOT_LAYOUT_MISMATCH", RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("packet_slots", session.serverSnapshot().slots().size()),
                    Map.of("menu_slots", menu.slots.size()));
            return;
        }

        if (state.parameters instanceof CraftParameters craft) {
            acceptCraftSnapshot(attempt, state, craft, player, menu, session.serverSnapshot());
        } else if (state.parameters instanceof TransferParameters transfer) {
            acceptTransferSnapshot(attempt, state, transfer, player, menu, session.serverSnapshot());
        }
    }

    private void acceptCraftSnapshot(
            PhaseFiveAttempt attempt,
            AttemptState state,
            CraftParameters craft,
            LocalPlayer player,
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (!(menu instanceof CraftingMenu craftingMenu)
                || !CRAFTING_MENU.equals(snapshot.menuTypeId())) {
            fail(state, "CRAFTING_TABLE_MENU_REQUIRED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", CRAFTING_MENU),
                    Map.of("menu_type", snapshot.menuTypeId()));
            return;
        }
        if (!craftingGridAndResultEmpty(snapshot.slots())) {
            if (state.stage == Stage.OPENING_READBACK) {
                state.inconclusive = new InconclusiveState(
                        PhaseFiveEvidence.Certainty.AMBIGUOUS,
                        "craft_readback_grid_or_result_not_empty");
                state.stage = Stage.TERMINAL;
            } else {
                fail(state, "CRAFTING_GRID_OR_RESULT_NOT_EMPTY",
                        RoutineFailure.Category.PRECONDITION,
                        RoutineFailure.Recovery.REPLAN,
                        Map.of("crafting_grid_and_result", "empty"), Map.of());
            }
            return;
        }
        int current = countPlayerItem(
                snapshot.slots(), menu, player.getInventory(), craft.goalItem(),
                defaultStackHash(craft.goalItem()));
        if (state.stage == Stage.OPENING_READBACK) {
            var readback = verifyCraftReadback(
                    state.beforeDestinationCount,
                    current,
                    state.expectedCraftOutputCount,
                    craft.minimumInventoryCount());
            state.afterDestinationCount = current;
            if (!readback.exactlyOneCraft()) {
                state.inconclusive = new InconclusiveState(
                        PhaseFiveEvidence.Certainty.AMBIGUOUS,
                        "craft_readback_delta_not_exactly_one_output");
                state.stage = Stage.TERMINAL;
                return;
            }
            state.completedUnits = Math.addExact(
                    state.completedUnits, state.expectedCraftOutputCount);
            state.completedActions++;
            if (readback.goalVerified()) {
                succeed(state, current, Map.of(
                        "inventory_count_before", state.beforeDestinationCount,
                        "inventory_count_after", current,
                        "crafts", state.completedActions,
                        "full_readback", true));
                return;
            }
        } else if (current >= craft.minimumInventoryCount()) {
            succeed(state, current, Map.of(
                    "inventory_count_before", current,
                    "inventory_count_after", current,
                    "crafts", 0,
                    "full_readback", true));
            return;
        }
        if (state.completedActions >= craft.maxCrafts()) {
            fail(state, "CRAFT_LIMIT_EXHAUSTED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("minimum_inventory_count", craft.minimumInventoryCount()),
                    Map.of("inventory_count", current, "crafts", state.completedActions));
            return;
        }

        try {
            state.recipe = resolveCraftRecipe(
                    requireMinecraft(), requireSession(), currentTick(), craft);
        } catch (IllegalArgumentException | IllegalStateException invalidRecipe) {
            fail(state, "CRAFT_RECIPE_NOT_CURRENT",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("recipe_supported_at_station", true), Map.of());
            return;
        }
        var result = state.recipe.view().result().alternatives().getFirst();
        state.beforeDestinationCount = current;
        state.expectedCraftOutputCount = result.count();
        dispatchRecipePlacement(attempt, state, craftingMenu, snapshot.packetLedgerRevision());
    }

    private void maintainCraftResult(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof CraftingMenu menu)) {
            return;
        }
        var snapshot = proof.orElseThrow();
        if (snapshot.packetLedgerRevision() <= state.lastPacketRevision
                || snapshot.slots().isEmpty()) {
            return;
        }
        var expected = state.recipe.view().result().alternatives().getFirst();
        var resultStack = snapshot.slots().get(CraftingMenu.RESULT_SLOT);
        if (!matchesDefaultStack(resultStack, expected.item(), defaultStackHash(expected.item()))
                || resultStack.count() != expected.count()
                || !snapshot.carried().empty()
                || !exactlyOneCraftPrepared(
                        snapshot.slots(), state.recipe.view().ingredients())) {
            return;
        }
        var destination = chooseCraftDestinationSlot(
                snapshot.slots(), layout(menu, minecraft.player.getInventory()).playerSlots(),
                expected.item(), defaultStackHash(expected.item()), expected.count(),
                defaultStackMaxCount(expected.item()));
        if (destination.isEmpty()) {
            fail(state, "CRAFT_OUTPUT_DESTINATION_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("compatible_or_empty_player_slot", true), Map.of());
            return;
        }
        dispatchContainerClick(
                attempt, state, menu, CraftingMenu.RESULT_SLOT, ContainerInput.QUICK_MOVE,
                Stage.AWAITING_CLICK_ACK);
    }

    private void acceptTransferSnapshot(
            PhaseFiveAttempt attempt,
            AttemptState state,
            TransferParameters transfer,
            LocalPlayer player,
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        String expectedMenu = transfer.menuTypeId();
        int expectedRows = DOUBLE_CONTAINER_MENU.equals(expectedMenu) ? 6 : 3;
        if (!(menu instanceof ChestMenu chestMenu)
                || chestMenu.getRowCount() != expectedRows
                || !expectedMenu.equals(snapshot.menuTypeId())) {
            fail(state, "VANILLA_CHEST_OR_BARREL_MENU_REQUIRED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", expectedMenu, "rows", expectedRows),
                    Map.of("menu_type", snapshot.menuTypeId()));
            return;
        }
        int hash = transfer.defaultComponentsOnly() ? defaultStackHash(transfer.item()) : 0;
        var layout = layout(menu, player.getInventory());
        int playerCount = countTransfer(
                snapshot.slots(), layout.playerSlots(), transfer.item(), hash,
                transfer.defaultComponentsOnly());
        int containerCount = countTransfer(
                snapshot.slots(), layout.containerSlots(), transfer.item(), hash,
                transfer.defaultComponentsOnly());
        List<Integer> sourceSlots = transfer.playerToContainer()
                ? layout.playerSlots() : layout.containerSlots();
        int source = transfer.playerToContainer() ? playerCount : containerCount;
        int destination = transfer.playerToContainer() ? containerCount : playerCount;
        List<Integer> destinationSlots = transfer.playerToContainer()
                ? layout.containerSlots() : layout.playerSlots();

        if (state.stage == Stage.OPENING_READBACK) {
            state.recordTransferReadback(source, destination);
            TransferBatch batch = state.transferBatch;
            boolean exactSlots = batch != null && batch.reconcileReadback(snapshot);
            state.updateTransferPrefix();
            var readback = verifyTransferReadback(
                    state.beforeSourceCount,
                    state.beforeDestinationCount,
                    source,
                    destination,
                    state.completedUnits,
                    transfer.minimumDestinationCount());
            if (!exactSlots || !readback.exactMove()
                    || !liveMenuMatchesSnapshot(menu, snapshot)) {
                state.inconclusive = new InconclusiveState(
                        PhaseFiveEvidence.Certainty.AMBIGUOUS,
                        "transfer_readback_did_not_confirm_exact_full_stack_move");
                state.stage = Stage.TERMINAL;
                return;
            }
            if (readback.goalVerified()) {
                succeed(state, destination, Map.of(
                        "source_count_before", state.beforeSourceCount,
                        "source_count_after", source,
                        "destination_count_before", state.beforeDestinationCount,
                        "destination_count_after", destination,
                        "transferred", state.completedUnits,
                        "full_readback", true));
                return;
            }
            fail(state, "TRANSFER_BATCH_GOAL_NOT_REACHED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("minimum_destination_count", transfer.minimumDestinationCount()),
                    Map.of("destination_count", destination,
                            "transferred", state.completedUnits));
            return;
        } else if (state.transferBatch == null && destination >= transfer.minimumDestinationCount()) {
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("source_count_before", source);
            evidence.put("source_count_after", source);
            evidence.put("destination_count_before", destination);
            evidence.put("destination_count_after", destination);
            evidence.put("transferred", 0);
            evidence.put("full_readback", true);
            boolean inspection = "minecraft:air".equals(transfer.item())
                    && !transfer.playerToContainer() && transfer.minimumDestinationCount() == 0;
            evidence.putAll(availableItemEvidence(snapshot.slots(), sourceSlots, inspection ? 54 : 27));
            if (inspection) {
                evidence.put("complete_container_inspection", true);
                evidence.put("contents_world_session_id", snapshot.worldSessionId().toString());
                evidence.put("contents_observed_tick", snapshot.receivedTick());
                evidence.put("contents_packet_revision", snapshot.packetLedgerRevision());
            }
            succeed(state, destination, evidence);
            return;
        }

        if (state.transferBatch == null) {
            if (!inboundTransferKeepsOpenHandSafe(transfer.playerToContainer(),
                    player.getMainHandItem().isEmpty(), defaultStack(transfer.item()).getItem().getClass())) {
                fail(state, "INVENTORY_SAFE_OPEN_HAND_UNAVAILABLE",
                        RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                        Map.of("transferred_item_safe_for_known_menu_open", true), Map.of());
                return;
            }
            // This is an absolute goal ceiling for the real opened menu, including nonstackables.
            int maximumDestination = KnownMenuTransfers.maximumDestinationCount(
                    defaultStack(transfer.item()), destinationSlots.stream().map(menu.slots::get).toList());
            if (transfer.minimumDestinationCount() > maximumDestination) {
                fail(state, "TRANSFER_DESTINATION_GOAL_EXCEEDS_MENU_CAPACITY",
                        RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                        Map.of("minimum_destination_count", transfer.minimumDestinationCount()),
                        Map.of("maximum_destination_count", maximumDestination));
                return;
            }
            state.beginTransferBatch(new TransferBatch(snapshot.slots(), sourceSlots, destinationSlots,
                    transfer.item(), hash, transfer.defaultComponentsOnly(), transfer.maxStackMoves(),
                    transfer.maxTransferCount(), transfer.minimumDestinationCount() - destination),
                    source, destination);
        }
        TransferBatch batch = state.transferBatch;
        if (batch.exhausted()) {
            if (batch.confirmedMoves > 0) {
                closeForReadback(attempt, state);
                return;
            }
            var observed = new LinkedHashMap<String, Object>();
            observed.put("source_count", source);
            observed.putAll(availableItemEvidence(snapshot.slots(), sourceSlots, 27));
            fail(state, "TRANSFER_FULL_STACK_UNAVAILABLE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("item", transfer.item(), "maximum_remaining", transfer.maxTransferCount()),
                    observed);
            return;
        }
        if (!batch.confirmedSlots.equals(snapshot.slots())) {
            // No click is repeated or newly planned after refills or unrelated slot changes.
            if (batch.confirmedMoves > 0) closeForReadback(attempt, state);
            else fail(state, "TRANSFER_INITIAL_SLOTS_CHANGED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return;
        }
        int slot = batch.next().slot();
        ItemStack sourceStack = menu.slots.get(slot).getItem();
        if (!liveMenuMatchesSnapshot(menu, snapshot)
                || !KnownMenuProfileSupport.hasFullDestinationCapacity(
                        sourceStack,
                        destinationSlots.stream().map(menu.slots::get).toList())) {
            if (batch.confirmedMoves > 0) {
                closeForReadback(attempt, state);
                return;
            }
            fail(state, "TRANSFER_FULL_STACK_DESTINATION_CAPACITY_UNAVAILABLE",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("whole_stack_capacity", true), Map.of());
            return;
        }
        dispatchTransferClick(attempt, state, menu, snapshot);
    }

    private void dispatchTransferClick(
            PhaseFiveAttempt attempt, AttemptState state, AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (prepareOwnedDispatch(attempt, state, menu) < 0L
                || !freshEmptyServerCursorProof(attempt, state)) return;
        long tick = currentTick();
        if (!state.transferBatch.beginClick(snapshot, tick)) return;
        state.transferReadbackObserved = false;
        state.dispatchedStackCount = state.transferBatch.next().stack().count();
        // Record the pending click before sending; exceptions must remain UNKNOWN, never retried.
        state.dispatchedContainerClicks++;
        state.stage = Stage.AWAITING_CLICK_ACK;
        KnownMenuTransfers.dispatchServerConfirmedQuickMove(
                requireMinecraft(), menu, state.transferBatch.next().slot());
    }

    private void dispatchRecipePlacement(
            PhaseFiveAttempt attempt,
            AttemptState state,
            CraftingMenu menu,
            long snapshotRevision) {
        Minecraft minecraft = requireMinecraft();
        Optional<ScreenOwnershipSignals.OwnedScreenSession> owned = screens.ownedSession();
        if (owned.isEmpty()
                || !attempt.attemptId().equals(owned.orElseThrow().token().routineId())
                || minecraft.player == null
                || minecraft.player.containerMenu != menu) {
            fail(state, "OWNED_SCREEN_CLICK_AUTHORITY_LOST", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return;
        }
        long before = packetRevision();
        state.lastPacketRevision = Math.max(snapshotRevision, before);
        // Count before dispatch so a rejected or throwing call cannot disappear from usage.
        state.recipePlacements++;
        Objects.requireNonNull(minecraft.gameMode).handlePlaceRecipe(
                menu.containerId, state.recipe.displayId(), false);
        state.stage = Stage.CRAFT_WAIT_RESULT;
    }

    private boolean dispatchContainerClick(
            PhaseFiveAttempt attempt,
            AttemptState state,
            AbstractContainerMenu menu,
            int slot,
            ContainerInput input,
            Stage nextStage) {
        long before = prepareOwnedDispatch(attempt, state, menu);
        if (before < 0L) return false;
        Minecraft minecraft = requireMinecraft();
        // Count before dispatch so a rejected or throwing call cannot disappear from usage.
        state.dispatchedContainerClicks++;
        Objects.requireNonNull(minecraft.gameMode).handleContainerInput(
                menu.containerId, slot, 0, input, minecraft.player);
        state.stage = nextStage;
        return true;
    }

    /** Shared ownership/cursor barrier for every Agent-authored container click. */
    private long prepareOwnedDispatch(
            PhaseFiveAttempt attempt, AttemptState state, AbstractContainerMenu menu) {
        Minecraft minecraft = requireMinecraft();
        if (screens.ownedSession().isEmpty()
                || minecraft.player == null
                || minecraft.player.containerMenu != menu) {
            fail(state, "OWNED_SCREEN_CLICK_AUTHORITY_LOST", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return -1L;
        }
        RoutineFailure routingFailure = routingLabelFailure(
                minecraft, sessionSupplier.get(), state.parameters);
        if (routingFailure != null) {
            state.failure = routingFailure;
            state.stage = Stage.TERMINAL;
            return -1L;
        }
        long before = packetRevision();
        // Every click in this adapter is QUICK_MOVE, so the server-proven empty cursor remains
        // invariant while slot packets provide the authoritative mutation/readback barrier.
        state.screenOwnedObserved = true;
        return before;
    }

    private void maintainClickAck(PhaseFiveAttempt attempt, AttemptState state) {
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || !proof.orElseThrow().carried().empty()) {
            return;
        }
        if (state.parameters instanceof TransferParameters transfer) {
            var snapshot = proof.orElseThrow();
            Minecraft minecraft = requireMinecraft();
            if (minecraft.player != null
                    && liveMenuMatchesSnapshot(minecraft.player.containerMenu, snapshot)
                    && state.transferBatch.confirm(snapshot)) {
                state.updateTransferPrefix();
                if (state.transferBatch.exhausted()
                        || state.beforeDestinationCount + state.completedUnits
                                >= transfer.minimumDestinationCount()) {
                    closeForReadback(attempt, state);
                } else {
                    // Dispatch of the next fixed source is deferred to another client tick.
                    state.stage = Stage.TRANSFER_READY;
                }
            } else if (state.transferBatch.ackTimedOut(currentTick())) {
                // A full reopen may resolve the one outstanding click; never send it again.
                closeForReadback(attempt, state);
            }
            return;
        }
        // QUICK_MOVE has no cursor transition and successful prediction has no positive ACK.
        // Close now; the same-target full-content reopen below is the authoritative result proof.
        closeForReadback(attempt, state);
    }

    private void closeForReadback(PhaseFiveAttempt attempt, AttemptState state) {
        if (!freshEmptyServerCursorProof(attempt, state)) {
            return;
        }
        state.cursorReleaseConfirmed = true;
        var decision = screens.cancelRoutine(attempt.attemptId());
        if (!decision.authorityMatched() || !decision.closeMenuBestEffort()) {
            fail(state, "OWNED_SCREEN_CLOSE_AUTHORITY_LOST", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", decision.reason()));
            return;
        }
        if (!decision.serverCursorEmpty()) {
            fail(state, "OWNED_SCREEN_CURSOR_NOT_EMPTY", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER, Map.of("cursor", "empty"), Map.of());
        }
        closeOwnedMenuClient(requireMinecraft(), decision);
        if (!state.terminal()) {
            long tick = currentTick();
            state.closeDeadlineClientTick = tick > Long.MAX_VALUE - OPEN_TIMEOUT_TICKS
                    ? Long.MAX_VALUE : tick + OPEN_TIMEOUT_TICKS;
            state.stage = Stage.AWAITING_CLOSE;
        }
    }

    private boolean freshEmptyServerCursorProof(
            PhaseFiveAttempt attempt, AttemptState state) {
        return freshServerCursorSnapshot(attempt, state)
                .filter(snapshot -> snapshot.carried().empty())
                .isPresent();
    }

    private Optional<ContainerSyncSignals.ContainerSnapshot> freshServerCursorSnapshot(
            PhaseFiveAttempt attempt, AttemptState state) {
        ScreenOwnershipSignals.Snapshot snapshot = screens.snapshot();
        if (snapshot.phase() != ScreenOwnershipSignals.Phase.OWNED
                || snapshot.expectedOpen() == null
                || !attempt.attemptId().equals(snapshot.expectedOpen().routineId())
                || !snapshot.lastServerCursorProven()
                || snapshot.ownedSession() == null
                || snapshot.ownedSession().serverSnapshot().packetLedgerRevision()
                        < snapshot.lastServerCursorProofRevision()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.ownedSession().serverSnapshot());
    }

    private void dispatchExpectedOpen(
            PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        var minecraft = assertClientThread();
        var session = requireSession();
        RoutineFailure failure = ongoingFailure(minecraft, session, state);
        if (failure != null) {
            state.failure = failure;
            state.stage = Stage.TERMINAL;
            return;
        }
        failure = routingLabelFailure(minecraft, session, state.parameters);
        if (failure != null) {
            state.failure = failure;
            state.stage = Stage.TERMINAL;
            return;
        }
        if (state.openHand == null || !state.openHand.ready(Objects.requireNonNull(minecraft.player))
                || currentTick() <= state.openHandSelectedClientTick) {
            fail(state, "CONTAINER_SAFE_OPEN_HAND_NOT_SETTLED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of());
            return;
        }
        var parameters = state.parameters;
        long now = screens.currentTick();
        long remainingRoutineTicks = Math.max(
                1L, attempt.hardDeadlineClientTick() - session.clientTick());
        long openWindow = Math.min(OPEN_TIMEOUT_TICKS, remainingRoutineTicks);
        long deadline = now > Long.MAX_VALUE - openWindow
                ? Long.MAX_VALUE : now + openWindow;
        var token = new ExpectedOpenToken(
                session.worldSessionId(), attempt.attemptId(), state.targetIdentity(),
                state.targetStateFingerprint(), parameters.menuTypeId(), deadline);
        if (!screens.beginExpectedOpen(token)) {
            fail(state, "EXPECTED_SCREEN_OPEN_REJECTED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return;
        }
        var hit = exactHit(minecraft, parameters.target());
        ClientPredictionSignals.PredictionAttempt prediction;
        int sequenceBefore;
        try {
            prediction = predictions.begin(
                    Objects.requireNonNull(minecraft.level),
                    blockPos(parameters.target()), session.clientTick());
            sequenceBefore = prediction.sequenceBeforePrediction();
            state.openPrediction = prediction;
        } catch (ClientPredictionSignals.PredictionBridgeException predictionFailure) {
            screens.cancelRoutine(attempt.attemptId());
            fail(state, "CONTAINER_OPEN_PREDICTION_UNAVAILABLE",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of(
                            "prediction_bridge", predictionFailure.kind().diagnostic()));
            return;
        }
        state.openCount++;
        final boolean consumed;
        try {
            consumed = Objects.requireNonNull(minecraft.gameMode)
                    .useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit)
                    .consumesAction();
            int sequenceAfter = prediction.captureIssuedPredictions();
            if (sequenceAfter != sequenceBefore + 1) {
                throw new ClientPredictionSignals.PredictionBridgeException(
                        "block-use prediction sequence did not advance exactly once");
            }
        } catch (RuntimeException | LinkageError dispatchFailure) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            fail(state, "CONTAINER_OPEN_PREDICTION_INCOMPATIBLE",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.USER,
                    Map.of(), Map.of());
            return;
        }
        if (!consumed) {
            screens.cancelRoutineAfterPredictedUse(
                    attempt.attemptId(), causalBarrierStatus(state));
            fail(state, "CONTAINER_NORMAL_USE_REJECTED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("target", parameters.target().toString()), Map.of());
            return;
        }
        state.stage = readback ? Stage.OPENING_READBACK : Stage.OPENING_INITIAL;
    }

    private void maintainAim(PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        var minecraft = assertClientThread();
        RoutineFailure failure = ongoingFailure(minecraft, sessionSupplier.get(), state);
        if (failure != null) {
            state.failure = failure;
            state.stage = Stage.TERMINAL;
            return;
        }
        var target = state.parameters.target();
        Vec3 point = state.aimPoint;
        state.view.turnToward(minecraft, point);
        switch (state.aimGate.observe(currentTick(), state.view.aligned(minecraft, point),
                minecraft.hitResult, blockPos(target))) {
            case OPEN -> dispatchExpectedOpen(attempt, state, readback);
            case OCCLUDED -> fail(state, "CONTAINER_AIM_OCCLUDED",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                    Map.of("exact_target_crosshair", true),
                    Map.of("crosshair", ContainerAimGate.occlusionKind(minecraft.hitResult)));
            case WAIT -> { }
        }
    }

    private static boolean selectOpenHand(
            Minecraft minecraft, AttemptState state, long clientTick) {
        Optional<OpenHandPlan> openHand = chooseOpenHand(
                Objects.requireNonNull(minecraft.player));
        if (openHand.isEmpty()) {
            fail(state, "INVENTORY_SAFE_OPEN_HAND_UNAVAILABLE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("side_effect_free_normal_use", true), Map.of());
            return false;
        }
        state.openHand = openHand.orElseThrow();
        state.view.selectOpenHand(minecraft, state.openHand);
        state.openHandSelectedClientTick = clientTick;
        return true;
    }

    private RoutineFailure preflight(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PhaseFiveRequest request) {
        if (!CRAFT_ITEMS.equals(request.kind()) && !TRANSFER_ITEMS.equals(request.kind())) {
            return failure("UNSUPPORTED_PHASE_FIVE_ADAPTER",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                    Map.of("kind", "craft_items|transfer_items"),
                    Map.of("kind", request.kind()));
        }
        var parameters = parse(request);
        var player = minecraft.player;
        var level = minecraft.level;
        if (session == null || !session.worldReady() || level == null || player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || !request.bounds().dimension().equals(session.dimension())
                || !request.bounds().dimension().equals(level.dimension().identifier().toString())) {
            return failure("INVENTORY_WORLD_NOT_READY", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN, Map.of("world_ready", true), Map.of());
        }
        if (!request.bounds().contains(parameters.target())) {
            return failure("INVENTORY_TARGET_OUT_OF_BOUNDS", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("target_in_bounds", true), Map.of());
        }
        if (!basicPlayerSafety(minecraft, null)) {
            return failure("INVENTORY_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER, Map.of("safe_survival_player", true), Map.of());
        }
        if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || player.containerMenu != player.inventoryMenu
                || screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return failure("INVENTORY_SCREEN_NOT_CLEAR", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("screen", "clear", "ownership", "idle"), Map.of());
        }
        if (chooseOpenHand(player).isEmpty()) {
            return failure("INVENTORY_SAFE_OPEN_HAND_REQUIRED",
                    RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("empty_or_safe_hotbar_item", true), Map.of());
        }
        var position = blockPos(parameters.target());
        if (!level.isLoaded(position) || !level.getWorldBorder().isWithinBounds(position)) {
            return failure("INVENTORY_TARGET_NOT_CURRENT", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("loaded_and_within_border", true), Map.of());
        }
        if (!player.isWithinBlockInteractionRange(position, 0.0)) {
            return failure("INVENTORY_TARGET_OUT_OF_REACH", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("within_reach", true), Map.of("within_reach", false));
        }
        BlockState state = level.getBlockState(position);
        var actual = fingerprint(state);
        if (!parameters.expectedState().equals(actual)) {
            return failure("INVENTORY_TARGET_STATE_DIVERGED", RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("block", parameters.expectedState().blockId(),
                            "properties", parameters.expectedState().properties()),
                    Map.of("block", actual.blockId(), "properties", actual.properties()));
        }
        if (parameters instanceof CraftParameters) {
            if (!state.is(Blocks.CRAFTING_TABLE)) {
                return failure("CRAFTING_TABLE_REQUIRED", RoutineFailure.Category.PRECONDITION,
                        RoutineFailure.Recovery.REPLAN,
                        Map.of("block", "minecraft:crafting_table"),
                        Map.of("block", actual.blockId()));
            }
        } else if (!vanillaStorageContainer(state)) {
            return failure("VANILLA_CHEST_OR_BARREL_REQUIRED",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                    Map.of("container", "vanilla_chest_or_barrel"),
                    Map.of("block", actual.blockId()));
        }
        RoutineFailure routingFailure = routingLabelFailure(minecraft, session, parameters);
        if (routingFailure != null) return routingFailure;
        return null;
    }

    private RoutineFailure routingLabelFailure(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ParsedParameters parameters) {
        Optional<RoutingLabelParameters> requested = parameters.routingLabel();
        if (requested.isEmpty()) return null;
        if (session == null || minecraft.level == null) {
            return routingLabelChanged(requested.orElseThrow());
        }
        RoutingLabelParameters witness = requested.orElseThrow();
        var entity = observations.resolveCurrentlyVisibleEntity(
                minecraft,
                session.clientTick(),
                session.worldSessionId(),
                session.dimension(),
                witness.entityRef(),
                32.0D).orElse(null);
        var label = entity == null ? null
                : ContainerLabelResolver.resolve(
                        minecraft.level, entity, session.dimension()).orElse(null);
        BlockTarget target = parameters.target();
        if (label == null
                || !witness.item().equals(label.item().value())
                || !parameters.expectedState().blockId().equals(label.containerBlock().value())
                || !target.dimension().equals(label.containerPosition().dimension().value())
                || target.x() != label.containerPosition().x()
                || target.y() != label.containerPosition().y()
                || target.z() != label.containerPosition().z()) {
            return routingLabelChanged(witness);
        }
        return null;
    }

    private static RoutineFailure routingLabelChanged(RoutingLabelParameters witness) {
        return failure(
                "CONTAINER_ROUTING_LABEL_CHANGED",
                RoutineFailure.Category.DIVERGENCE,
                RoutineFailure.Recovery.REPLAN,
                Map.of("routing_label_item", witness.item()),
                Map.of());
    }

    private static void validateResolvedRecipe(
            CraftParameters parameters, ClientRecipeCatalog.ResolvedRecipe recipe) {
        var view = recipe.view();
        if (!craftingTableRecipeSupported(view)) {
            throw new IllegalArgumentException(
                    "recipe display is not supported by the declared crafting table");
        }
        var result = view.result().alternatives().getFirst();
        if (!parameters.goalItem().equals(result.item())) {
            throw new IllegalArgumentException("recipe result does not match the absolute goal item");
        }
    }

    static boolean craftingTableRecipeSupported(ClientRecipeCatalog.RecipeView view) {
        return view.supported()
                && view.result().deterministic()
                && view.result().alternatives().size() == 1
                && ("inventory_2x2".equals(view.requiredScreen())
                        || "crafting_table".equals(view.requiredScreen()));
    }

    private ClientRecipeCatalog.ResolvedRecipe resolveCraftRecipe(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            long tick,
            CraftParameters craft) {
        recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
        var resolved = recipes.resolve(
                        session.worldSessionId(), craft.recipeRef(), craft.recipeFingerprint())
                .orElseThrow(() -> new IllegalArgumentException(
                        "recipe ref/fingerprint is stale for this client recipe book"));
        validateResolvedRecipe(craft, resolved);
        return resolved;
    }

    /** Cleanup-only path. It never resumes gameplay after release has been requested. */
    private void maintainTerminalRelease(PhaseFiveAttempt attempt, AttemptState state) {
        Minecraft minecraft = assertClientThread();
        long tick = currentTick();
        if (state.releaseStartedTick < 0L) {
            state.releaseStartedTick = tick;
            state.releaseDeadlineTick = boundedDeadline(tick, RELEASE_TIMEOUT_TICKS);
        }
        if (tick > state.releaseDeadlineTick) {
            state.releaseFault = true;
            // A timeout is not proof that an owned menu/input was released. Keep the owner,
            // but allow late server evidence or a manual/context close to make cleanup provable.
            // An unchanged failed boundary cannot trigger an unbounded stream of close attempts.
            var boundary = releaseBoundary(minecraft, screens.snapshot(), causalBarrierStatus(state));
            if (!releaseBoundaryChanged(state.expiredReleaseBoundary, boundary)) return;
            state.expiredReleaseBoundary = boundary;
        }
        try {
            ScreenOwnershipSignals.Snapshot screen = screens.snapshot();
            state.screenOwnedObserved |= screen.everOwned();
            switch (screen.phase()) {
                case OWNED -> {
                    closeOpenPrediction(state);
                    releaseOwnedMenu(attempt, state, minecraft);
                }
                case EXPECTING_OPEN_PACKET, EXPECTING_SCREEN, EXPECTING_FULL_CONTENT, FAILED -> {
                    // cancelRoutine may retire an already-closed failed screen and clear its
                    // ledger. Preserve its exact-owner empty-cursor proof before that transition.
                    state.cursorReleaseConfirmed = releaseCursorProofMatches(
                            attempt.attemptId(),
                            screen.expectedOpen() == null ? null : screen.expectedOpen().routineId(),
                            screen.lastServerCursorProven(), screen.lastServerCursorEmpty());
                    var decision = cancelScreenAuthority(attempt, state);
                    if (!decision.authorityMatched()) {
                        state.releaseFault = true;
                        return;
                    }
                    if (decision.closeMenuBestEffort()) {
                        if (state.screenOwnedObserved && !decision.serverCursorEmpty()) {
                            return;
                        }
                        state.cursorReleaseConfirmed |= decision.serverCursorEmpty();
                        closeOwnedMenuClient(minecraft, decision);
                    }
                    confirmReleaseIfClear(state, minecraft);
                }
                case CLOSING -> { }
                case IDLE -> {
                    closeOpenPrediction(state);
                    confirmReleaseIfClear(state, minecraft);
                }
            }
        } catch (RuntimeException | LinkageError releaseFailure) {
            state.releaseFault = true;
            if (state.releaseFaultCause == null) state.releaseFaultCause = releaseFailure;
        }
    }

    static boolean releaseBoundaryChanged(List<?> previous, List<?> current) {
        return previous == null || !previous.equals(Objects.requireNonNull(current));
    }

    static boolean releaseCursorProofMatches(
            UUID expectedOwner, UUID observedOwner, boolean serverProven, boolean serverEmpty) {
        return expectedOwner != null && expectedOwner.equals(observedOwner)
                && serverProven && serverEmpty;
    }

    private static List<Object> releaseBoundary(
            Minecraft minecraft, ScreenOwnershipSignals.Snapshot screen,
            ScreenOwnershipSignals.CausalBarrierStatus causalBarrier) {
        var player = minecraft.player;
        return java.util.Arrays.asList(
                minecraft.level, player, minecraft.gui.screen(),
                player == null ? null : player.containerMenu,
                screen.phase(), screen.packetLedgerRevision(), causalBarrier,
                player == null ? null : player.inventoryMenu.getCarried().isEmpty(),
                player == null ? null : player.getInventory().getSelectedSlot(),
                player == null ? null : player.getYRot(),
                player == null ? null : player.getXRot());
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
            case IDENTITY_RELEASED -> ScreenOwnershipSignals.CausalBarrierStatus.IDENTITY_RELEASED;
            case NO_PREDICTION, WAITING_ACK -> ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK;
            case INCOMPATIBLE, CLOSED -> ScreenOwnershipSignals.CausalBarrierStatus.INCOMPATIBLE;
        };
    }

    private static void closeOpenPrediction(AttemptState state) {
        if (state.openPrediction == null) return;
        state.openPrediction.close();
        state.openPrediction = null;
    }

    private void releaseOwnedMenu(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        // While the ledger still owns this menu, only its current cursor proof may authorize close.
        state.cursorReleaseConfirmed = false;
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || minecraft.player == null) return;
        var snapshot = proof.orElseThrow();
        if (!snapshot.carried().empty()) {
            state.releaseFault = true;
            return;
        }
        state.cursorReleaseConfirmed = true;
        var decision = screens.cancelRoutine(attempt.attemptId());
        if (!decision.authorityMatched() || !decision.closeMenuBestEffort()
                || !decision.serverCursorEmpty()) {
            state.releaseFault = true;
            return;
        }
        closeOwnedMenuClient(minecraft, decision);
        confirmReleaseIfClear(state, minecraft);
    }

    private void confirmReleaseIfClear(AttemptState state, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !releaseScreenContextClear(
                state.screenOwnedObserved,
                screens.snapshot().phase(),
                AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                        && player.containerMenu == player.inventoryMenu
                        && player.inventoryMenu.getCarried().isEmpty()
                        && state.cursorReleaseConfirmed)) {
            return;
        }
        if (!state.screenOwnedObserved) {
            // No Agent-owned menu or click existed. Leave an unrelated Screen/cursor untouched,
            // while still restoring the Agent-owned view and selected-slot lease.
            state.cursorReleaseConfirmed = true;
        }
        if (!state.closeView(minecraft)) {
            return;
        }
        state.releaseConfirmed = true;
        state.releasePending = false;
        state.releaseFault = false;
        state.stage = Stage.TERMINAL;
    }

    static boolean releaseScreenContextClear(
            boolean screenOwnedObserved,
            ScreenOwnershipSignals.Phase phase,
            boolean ownedContextClear) {
        return phase == ScreenOwnershipSignals.Phase.IDLE
                && (!screenOwnedObserved || ownedContextClear);
    }

    private float configuredCameraDegreesPerTick() {
        double value = runtimeCameraDegreesPerTick.getAsDouble();
        if (!Double.isFinite(value) || value < 0.1D || value > 18.0D) {
            throw new IllegalStateException("runtime camera limit is outside the safe range");
        }
        return (float) value;
    }

    private static long boundedDeadline(long tick, int durationTicks) {
        return tick > Long.MAX_VALUE - durationTicks ? Long.MAX_VALUE : tick + durationTicks;
    }

    /**
     * Closes an exact automation-owned container through the canonical screen lifecycle. Calling
     * only LocalPlayer.closeContainer closes the negotiated menu but can leave the visual container
     * screen installed, causing the next bounded DSL node to fail its clear-screen preflight.
     */
    private void closeOwnedMenuClient(
            Minecraft minecraft, ScreenOwnershipSignals.CleanupDecision decision) {
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> containerScreen)
                || containerScreen.getMenu().containerId != decision.containerId()
                || !ScreenOwnershipSignals.menuTypeId(containerScreen.getMenu().getType())
                        .equals(decision.menuTypeId())) {
            throw new IllegalStateException("owned container screen changed before close");
        }
        containerScreen.onClose();
        screens.onScreenClosing(containerScreen).ifPresent(reason -> {
            throw new IllegalStateException("owned container screen close failed: " + reason);
        });
    }

    private AttemptState requireAttempt(PhaseFiveAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        var state = attempts.get(attempt);
        if (state == null) {
            throw new IllegalStateException("Phase 5 inventory attempt is not active");
        }
        return state;
    }

    private Minecraft assertClientThread() {
        var minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("inventory adapter must run on the Minecraft client thread");
        }
        return minecraft;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(minecraftSupplier.get(), "Minecraft client is not initialized");
    }

    private WorldSessionTracker.Snapshot requireSession() {
        var session = Objects.requireNonNull(sessionSupplier.get(), "world session is unavailable");
        if (!session.worldReady()) {
            throw new IllegalStateException("world session is not ready");
        }
        return session;
    }

    private long currentTick() {
        var session = sessionSupplier.get();
        return session == null ? 0L : Math.max(0L, session.clientTick());
    }

    private long packetRevision() {
        return Math.max(0L, screens.snapshot().packetLedgerRevision());
    }

    private static ParsedParameters parse(PhaseFiveRequest request) {
        return switch (request.kind()) {
            case CRAFT_ITEMS -> parseCraft(request.parameters());
            case TRANSFER_ITEMS -> parseTransfer(request.parameters());
            default -> throw new IllegalArgumentException("unsupported inventory routine kind");
        };
    }

    private static CraftParameters parseCraft(Map<String, Object> parameters) {
        var station = map(parameters.get("station"), "station");
        String stationKind = string(station.get("kind"), "station.kind");
        if ("inventory_2x2".equals(stationKind)) {
            throw new IllegalArgumentException(
                    "inventory_2x2 is not supported because strict close/reopen readback is unavailable");
        }
        if (!"crafting_table".equals(stationKind)) {
            throw new IllegalArgumentException("unsupported crafting station");
        }
        var goal = map(parameters.get("goal"), "goal");
        requireDefaultComponents(goal, "goal");
        var target = target(map(station.get("target"), "station.target"));
        return new CraftParameters(
                string(parameters.get("recipe_ref"), "recipe_ref"),
                string(parameters.get("recipe_fingerprint"), "recipe_fingerprint"),
                string(goal.get("item"), "goal.item"),
                integer(goal.get("minimum_inventory_count"), "goal.minimum_inventory_count"),
                integer(parameters.get("max_crafts"), "max_crafts"),
                target,
                state(map(station.get("expected_state"), "station.expected_state")));
    }

    private static TransferParameters parseTransfer(Map<String, Object> parameters) {
        var container = map(parameters.get("container"), "container");
        var stack = map(parameters.get("stack"), "stack");
        var goal = map(parameters.get("goal"), "goal");
        String stackPolicy = string(stack.get("stack_policy"), "stack.stack_policy");
        if (!"default_components_only".equals(stackPolicy)
                && !"item_id_any_components".equals(stackPolicy)) {
            throw new IllegalArgumentException("unsupported transfer stack policy");
        }
        String direction = string(parameters.get("direction"), "direction");
        if (!"player_to_container".equals(direction)
                && !"container_to_player".equals(direction)) {
            throw new IllegalArgumentException("unsupported transfer direction");
        }
        return new TransferParameters(
                "player_to_container".equals(direction),
                string(stack.get("item"), "stack.item"),
                stackPolicy,
                integer(goal.get("minimum_destination_count"),
                        "goal.minimum_destination_count"),
                integer(parameters.get("max_transfer_count"), "max_transfer_count"),
                parameters.containsKey("max_stack_moves")
                        ? integer(parameters.get("max_stack_moves"), "max_stack_moves")
                        : 1,
                Boolean.TRUE.equals(parameters.get("retain_view_on_release")),
                parameters.containsKey("max_camera_degrees_per_tick")
                        ? finiteNumber(parameters.get("max_camera_degrees_per_tick"),
                                "max_camera_degrees_per_tick")
                        : LEGACY_MAX_TURN_PER_TICK,
                target(map(container.get("target"), "container.target")),
                state(map(container.get("expected_state"), "container.expected_state")),
                routingLabel(parameters));
    }

    private static Optional<RoutingLabelParameters> routingLabel(
            Map<String, Object> parameters) {
        if (!parameters.containsKey("routing_label")) return Optional.empty();
        Map<String, Object> label = map(parameters.get("routing_label"), "routing_label");
        if (!label.keySet().equals(java.util.Set.of("entity_ref", "item"))) {
            throw new IllegalArgumentException("routing_label has an invalid shape");
        }
        return Optional.of(new RoutingLabelParameters(
                string(label.get("entity_ref"), "routing_label.entity_ref"),
                string(label.get("item"), "routing_label.item")));
    }

    private static void requireDefaultComponents(Map<String, Object> source, String name) {
        if (!"default_components_only".equals(
                string(source.get("stack_policy"), name + ".stack_policy"))) {
            throw new IllegalArgumentException(name + " requires default_components_only");
        }
    }

    private static BlockTarget target(Map<String, Object> value) {
        return new BlockTarget(
                string(value.get("dimension"), "dimension"),
                integer(value.get("x"), "x"),
                integer(value.get("y"), "y"),
                integer(value.get("z"), "z"));
    }

    private static BlockStateFingerprint state(Map<String, Object> value) {
        var properties = new LinkedHashMap<String, String>();
        for (var entry : map(value.get("properties"), "properties").entrySet()) {
            properties.put(entry.getKey(), string(entry.getValue(), "property value"));
        }
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
                throw new IllegalArgumentException(name + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
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
        long longValue = number.longValue();
        if (number.doubleValue() != longValue
                || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) longValue;
    }

    private static double finiteNumber(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
    }

    static Vec3 inventoryAimPoint(PhaseFiveRequest request, BlockTarget target) {
        Object raw = request.parameters().get("aim_point");
        if (raw == null) {
            return new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D);
        }
        Map<String, Object> point = map(raw, "aim_point");
        if (!point.keySet().equals(java.util.Set.of("dimension", "x", "y", "z"))) {
            throw new IllegalArgumentException("aim_point fields are invalid");
        }
        if (!target.dimension().equals(string(point.get("dimension"), "aim_point.dimension"))) {
            throw new IllegalArgumentException("aim_point dimension does not match target");
        }
        double x = finiteNumber(point.get("x"), "aim_point.x");
        double y = finiteNumber(point.get("y"), "aim_point.y");
        double z = finiteNumber(point.get("z"), "aim_point.z");
        if (x < target.x() || x > target.x() + 1.0D
                || y < target.y() || y > target.y() + 1.0D
                || z < target.z() || z > target.z() + 1.0D) {
            throw new IllegalArgumentException("aim_point is outside its target block");
        }
        return new Vec3(x, y, z);
    }

    private static BlockHitResult exactHit(Minecraft minecraft, BlockTarget target) {
        if (!ContainerAimGate.exactTarget(minecraft.hitResult, blockPos(target))) {
            throw new IllegalArgumentException("current crosshair does not identify the exact target");
        }
        return (BlockHitResult) minecraft.hitResult;
    }

    private static boolean vanillaStorageContainer(BlockState state) {
        if (state.is(Blocks.BARREL)) {
            return true;
        }
        return state.is(Blocks.CHEST);
    }

    private boolean basicPlayerSafety(Minecraft minecraft, PlayerBaseline baseline) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null) return false;
        Vec3 velocity = player.getDeltaMovement();
        double horizontalVelocitySquared = velocity.x * velocity.x + velocity.z * velocity.z;
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
                && Double.isFinite(horizontalVelocitySquared)
                && horizontalVelocitySquared <= MAX_HORIZONTAL_VELOCITY_SQUARED
                && (baseline == null || baseline.matches(player))
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
            AttemptState state,
            ScreenOwnershipSignals.Phase phase) {
        boolean exactContainer = exactContainerScreen(minecraft, state.parameters.menuTypeId());
        if (state.stage == Stage.AIMING_INITIAL || state.stage == Stage.AIMING_READBACK) {
            return phase == ScreenOwnershipSignals.Phase.IDLE
                    && AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                    && minecraft.player != null
                    && minecraft.player.containerMenu == minecraft.player.inventoryMenu;
        }
        if (state.stage == Stage.OPENING_INITIAL || state.stage == Stage.OPENING_READBACK) {
            return (phase == ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_SCREEN
                    || phase == ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT
                    || phase == ScreenOwnershipSignals.Phase.OWNED)
                    && (AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen()) || exactContainer);
        }
        if (state.stage == Stage.AWAITING_CLOSE) {
            if (phase == ScreenOwnershipSignals.Phase.IDLE) {
                return AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                        && minecraft.player != null
                        && minecraft.player.containerMenu == minecraft.player.inventoryMenu;
            }
            return phase == ScreenOwnershipSignals.Phase.CLOSING
                    && (AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen()) || exactContainer);
        }
        return phase == ScreenOwnershipSignals.Phase.OWNED && exactContainer;
    }

    private static boolean exactContainerScreen(Minecraft minecraft, String menuTypeId) {
        return minecraft.player != null
                && minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == minecraft.player.containerMenu
                && ScreenOwnershipSignals.menuTypeId(screen.getMenu().getType()).equals(menuTypeId);
    }

    private static Optional<OpenHandPlan> chooseOpenHand(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        var hotbar = new ArrayList<ItemStack>(9);
        for (int slot = 0; slot < 9; slot++) {
            hotbar.add(player.getInventory().getItem(slot));
        }
        return chooseOpenHand(hotbar, player.getInventory().getSelectedSlot());
    }

    static Optional<OpenHandPlan> chooseOpenHand(List<ItemStack> hotbar, int selectedSlot) {
        Objects.requireNonNull(hotbar, "hotbar");
        if (hotbar.size() != 9 || selectedSlot < 0 || selectedSlot > 8) {
            throw new IllegalArgumentException("invalid hotbar selection context");
        }
        // NeoForge checks sneak-use bypass and invokes onItemUseFirst before the block. Once both
        // hooks are proven to be their default implementations, each exact supported block
        // consumes useWithoutItem before ItemStack.useOn can place or otherwise mutate anything.
        for (int slot = 0; slot < 9; slot++) {
            if (!hotbar.get(slot).isEmpty() && safeKnownMenuOpenStack(hotbar.get(slot))) {
                return Optional.of(new OpenHandPlan(slot));
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (hotbar.get(slot).isEmpty()) {
                return Optional.of(new OpenHandPlan(slot));
            }
        }
        return Optional.empty();
    }

    /**
     * Safety proof for an exact known crafting table, chest or barrel interaction. NeoForge calls
     * sneak-use bypass and {@code onItemUseFirst} before the block; custom overrides are therefore
     * rejected. The supported Vanilla block then consumes {@code useWithoutItem} before
     * {@code ItemStack.useOn}.
     */
    static boolean safeKnownMenuOpenStack(ItemStack stack) {
        return stack.isEmpty() || usesDefaultNeoForgeOpenHooks(stack.getItem().getClass());
    }

    static boolean inboundTransferKeepsOpenHandSafe(
            boolean playerToContainer, boolean mainHandEmpty, Class<?> transferredItemType) {
        Objects.requireNonNull(transferredItemType, "transferredItemType");
        return playerToContainer || !mainHandEmpty || usesDefaultNeoForgeOpenHooks(transferredItemType);
    }

    static boolean usesDefaultNeoForgeOpenHooks(Class<?> itemType) {
        return usesDefaultNeoForgeFirstUse(itemType) && usesDefaultNeoForgeSneakBypass(itemType);
    }

    static boolean usesDefaultNeoForgeFirstUse(Class<?> itemType) {
        Objects.requireNonNull(itemType, "itemType");
        try {
            return itemType
                    .getMethod("onItemUseFirst", ItemStack.class, UseOnContext.class)
                    .getDeclaringClass() == IItemExtension.class;
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            return false;
        }
    }

    static boolean usesDefaultNeoForgeSneakBypass(Class<?> itemType) {
        Objects.requireNonNull(itemType, "itemType");
        try {
            return itemType
                    .getMethod("doesSneakBypassUse", ItemStack.class, LevelReader.class,
                            BlockPos.class, Player.class)
                    .getDeclaringClass() == IItemExtension.class;
        } catch (ReflectiveOperationException | SecurityException | LinkageError failure) {
            return false;
        }
    }

    static boolean sameTransferContainerIdentity(
            BlockStateFingerprint expected, BlockState live) {
        if (!vanillaStorageContainer(live)) return false;
        BlockStateFingerprint actual = fingerprint(live);
        if (live.is(Blocks.CHEST)) return expected.equals(actual);
        if (!expected.blockId().equals(actual.blockId())) return false;
        var expectedProperties = new LinkedHashMap<>(expected.properties());
        var actualProperties = new LinkedHashMap<>(actual.properties());
        expectedProperties.remove("open");
        actualProperties.remove("open");
        return expectedProperties.equals(actualProperties);
    }

    private static boolean targetReadyForReopen(
            Minecraft minecraft, ParsedParameters parameters) {
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        var position = blockPos(parameters.target());
        if (!minecraft.level.isLoaded(position)
                || !minecraft.level.getWorldBorder().isWithinBounds(position)
                || !minecraft.player.isWithinBlockInteractionRange(position, 0.0D)) {
            return false;
        }
        return parameters.expectedState().equals(
                fingerprint(minecraft.level.getBlockState(position)));
    }

    /** Exact live state identity used by the Action DSL adapter before opening a container. */
    public static BlockStateFingerprint fingerprintLiveState(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        return fingerprintLiveState(state);
    }

    private static ItemStack defaultStack(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        var holder = identifier == null
                ? Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item>>empty()
                : BuiltInRegistries.ITEM.get(identifier);
        if (holder.isEmpty() || holder.orElseThrow().value() == Items.AIR) {
            throw new IllegalArgumentException("item is not registered");
        }
        return new ItemStack(holder.orElseThrow().value());
    }

    private static int defaultStackHash(String itemId) {
        return ItemStack.hashItemAndComponents(defaultStack(itemId));
    }

    private static int defaultStackMaxCount(String itemId) {
        return defaultStack(itemId).getMaxStackSize();
    }

    private static MenuLayout layout(AbstractContainerMenu menu, Inventory inventory) {
        var playerSlots = new ArrayList<Integer>();
        var containerSlots = new ArrayList<Integer>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.slots.get(index).container == inventory) {
                playerSlots.add(index);
            } else {
                containerSlots.add(index);
            }
        }
        return new MenuLayout(playerSlots, containerSlots);
    }

    private static boolean liveMenuMatchesSnapshot(
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (menu.slots.size() != snapshot.slots().size()) return false;
        for (int index = 0; index < menu.slots.size(); index++) {
            if (!ContainerSyncSignals.StackFingerprint.fromServerPacket(
                    menu.slots.get(index).getItem()).equals(snapshot.slots().get(index))) {
                return false;
            }
        }
        return true;
    }

    private static int countPlayerItem(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            AbstractContainerMenu menu,
            Inventory inventory,
            String item,
            int defaultHash) {
        return countExact(stacks, layout(menu, inventory).playerSlots(), item, defaultHash);
    }

    static boolean craftingGridAndResultEmpty(
            List<ContainerSyncSignals.StackFingerprint> stacks) {
        if (stacks.size() <= CRAFTING_GRID_LAST_SLOT) {
            return false;
        }
        for (int slot = CraftingMenu.RESULT_SLOT; slot <= CRAFTING_GRID_LAST_SLOT; slot++) {
            if (!stacks.get(slot).empty()) {
                return false;
            }
        }
        return true;
    }

    static boolean exactlyOneCraftPrepared(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<ClientRecipeCatalog.IngredientView> ingredients) {
        if (stacks.size() <= CRAFTING_GRID_LAST_SLOT || ingredients.isEmpty()) {
            return false;
        }
        long expectedUnits = ingredients.stream()
                .mapToLong(ClientRecipeCatalog.IngredientView::countPerCraft)
                .sum();
        long gridUnits = 0L;
        for (int slot = 1; slot <= CRAFTING_GRID_LAST_SLOT; slot++) {
            gridUnits += stacks.get(slot).count();
        }
        return gridUnits == expectedUnits;
    }

    static Optional<Integer> chooseCraftDestinationSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> playerSlots,
            String item,
            int defaultHash,
            int outputCount,
            int maximumStackCount) {
        if (outputCount < 1 || outputCount > maximumStackCount) {
            return Optional.empty();
        }
        Integer empty = null;
        for (int slot : playerSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)
                    && stack.count() <= maximumStackCount - outputCount) {
                return Optional.of(slot);
            }
            if (stack.empty() && empty == null) {
                empty = slot;
            }
        }
        return Optional.ofNullable(empty);
    }

    static int countExact(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> slots,
            String item,
            int defaultHash) {
        Objects.requireNonNull(stacks, "stacks");
        Objects.requireNonNull(slots, "slots");
        int count = 0;
        for (int slot : slots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)) {
                count = Math.addExact(count, stack.count());
            }
        }
        return count;
    }

    static Optional<Integer> chooseFullStackSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            String item,
            int defaultHash,
            int maximumCount) {
        if (maximumCount < 1) {
            return Optional.empty();
        }
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (matchesDefaultStack(stack, item, defaultHash)
                    && stack.count() > 0 && stack.count() <= maximumCount) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    static int countTransfer(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> slots,
            String item,
            int defaultHash,
            boolean defaultComponentsOnly) {
        if (defaultComponentsOnly) {
            return countExact(stacks, slots, item, defaultHash);
        }
        int count = 0;
        for (int slot : slots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && item.equals(stack.itemId())) {
                count = Math.addExact(count, stack.count());
            }
        }
        return count;
    }

    static Optional<Integer> chooseTransferSlot(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            String item,
            int defaultHash,
            int maximumCount,
            boolean defaultComponentsOnly) {
        if (defaultComponentsOnly) {
            return chooseFullStackSlot(stacks, sourceSlots, item, defaultHash, maximumCount);
        }
        if (maximumCount < 1) {
            return Optional.empty();
        }
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && item.equals(stack.itemId())
                    && stack.count() > 0 && stack.count() <= maximumCount) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    static Map<String, Object> availableItemEvidence(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            List<Integer> sourceSlots,
            int maximumItems) {
        Objects.requireNonNull(stacks, "stacks");
        Objects.requireNonNull(sourceSlots, "sourceSlots");
        if (maximumItems < 1) {
            throw new IllegalArgumentException("maximumItems must be positive");
        }
        var counts = new java.util.TreeMap<String, Integer>();
        for (int slot : sourceSlots) {
            if (slot < 0 || slot >= stacks.size()) {
                throw new IllegalArgumentException("slot is outside the full snapshot");
            }
            var stack = stacks.get(slot);
            if (!stack.empty() && stack.count() > 0) {
                counts.merge(stack.itemId(), stack.count(), Math::addExact);
            }
        }
        var items = counts.entrySet().stream()
                .limit(maximumItems)
                .map(entry -> Map.<String, Object>of(
                        "item", entry.getKey(), "count", entry.getValue()))
                .toList();
        return Map.of(
                "available_source_items", items,
                "available_source_items_truncated", counts.size() > maximumItems);
    }

    private static boolean matchesDefaultStack(
            ContainerSyncSignals.StackFingerprint stack,
            String item,
            int defaultHash) {
        return !stack.empty()
                && item.equals(stack.itemId())
                && stack.itemAndComponentsHash() == defaultHash;
    }

    static TransferReadback verifyTransferReadback(
            int sourceBefore,
            int destinationBefore,
            int sourceAfter,
            int destinationAfter,
            int dispatchedFullStackCount,
            int minimumDestinationCount) {
        boolean exact = dispatchedFullStackCount > 0
                && sourceBefore - sourceAfter == dispatchedFullStackCount
                && destinationAfter - destinationBefore == dispatchedFullStackCount;
        return new TransferReadback(exact, exact && destinationAfter >= minimumDestinationCount);
    }

    static CraftReadback verifyCraftReadback(
            int inventoryBefore,
            int inventoryAfter,
            int expectedOutputCount,
            int minimumInventoryCount) {
        boolean exact = expectedOutputCount > 0
                && inventoryAfter - inventoryBefore == expectedOutputCount;
        return new CraftReadback(exact, exact && inventoryAfter >= minimumInventoryCount);
    }

    private static void succeed(
            AttemptState state, int verifiedUnits, Map<String, Object> basis) {
        state.result = new PhaseFiveResult(verifiedUnits, true, basis, List.of());
        state.stage = Stage.TERMINAL;
    }

    private static void fail(
            AttemptState state,
            String code,
            RoutineFailure.Category category,
            RoutineFailure.Recovery recovery,
            Map<String, Object> expected,
            Map<String, Object> observed) {
        state.failure = failure(code, category, recovery, expected, observed);
        state.stage = Stage.TERMINAL;
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

    private static String boundedReason(Exception failure) {
        String reason = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        return reason.length() <= 160 ? reason : reason.substring(0, 160);
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    enum Stage {
        AIMING_INITIAL,
        OPENING_INITIAL,
        TRANSFER_READY,
        CRAFT_WAIT_RESULT,
        AWAITING_CLICK_ACK,
        AWAITING_CLOSE,
        AIMING_READBACK,
        OPENING_READBACK,
        RELEASING,
        TERMINAL
    }

    sealed interface ParsedParameters permits CraftParameters, TransferParameters {
        BlockTarget target();

        BlockStateFingerprint expectedState();

        String menuTypeId();

        default boolean restoreViewOnRelease() {
            return true;
        }

        default float maxCameraDegreesPerTick() {
            return LEGACY_MAX_TURN_PER_TICK;
        }

        default Optional<RoutingLabelParameters> routingLabel() {
            return Optional.empty();
        }
    }

    record CraftParameters(
            String recipeRef,
            String recipeFingerprint,
            String goalItem,
            int minimumInventoryCount,
            int maxCrafts,
            BlockTarget target,
            BlockStateFingerprint expectedState) implements ParsedParameters {
        CraftParameters {
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(goalItem, "goalItem");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            if (minimumInventoryCount < 1 || minimumInventoryCount > 2_304
                    || maxCrafts < 1 || maxCrafts > 64) {
                throw new IllegalArgumentException("craft limits are outside the v1 contract");
            }
        }

        @Override
        public String menuTypeId() {
            return CRAFTING_MENU;
        }

        @Override
        public boolean restoreViewOnRelease() {
            return false;
        }
    }

    record TransferParameters(
            boolean playerToContainer,
            String item,
            String stackPolicy,
            int minimumDestinationCount,
            int maxTransferCount,
            int maxStackMoves,
            boolean retainViewOnRelease,
            double cameraDegreesPerTick,
            BlockTarget target,
            BlockStateFingerprint expectedState,
            Optional<RoutingLabelParameters> routingLabel) implements ParsedParameters {
        TransferParameters {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(routingLabel, "routingLabel");
            if (!"default_components_only".equals(stackPolicy)
                    && !"item_id_any_components".equals(stackPolicy)) {
                throw new IllegalArgumentException("unsupported transfer stack policy");
            }
            if (minimumDestinationCount < 0
                    || minimumDestinationCount > (playerToContainer ? 3_456 : 2_304)
                    || maxTransferCount < 1 || maxTransferCount > 896
                    || maxStackMoves < 1 || maxStackMoves > 14
                    || !Double.isFinite(cameraDegreesPerTick)
                    || cameraDegreesPerTick < 0.1D || cameraDegreesPerTick > 18.0D) {
                throw new IllegalArgumentException("transfer limits are outside the v1 contract");
            }
        }

        TransferParameters(
                boolean playerToContainer,
                String item,
                String stackPolicy,
                int minimumDestinationCount,
                int maxTransferCount,
                int maxStackMoves,
                boolean retainViewOnRelease,
                double cameraDegreesPerTick,
                BlockTarget target,
                BlockStateFingerprint expectedState) {
            this(playerToContainer, item, stackPolicy, minimumDestinationCount,
                    maxTransferCount, maxStackMoves, retainViewOnRelease,
                    cameraDegreesPerTick, target, expectedState, Optional.empty());
        }

        @Override
        public String menuTypeId() {
            return transferMenuType(expectedState);
        }

        boolean defaultComponentsOnly() {
            return "default_components_only".equals(stackPolicy);
        }

        @Override
        public boolean restoreViewOnRelease() {
            return !retainViewOnRelease;
        }

        @Override
        public float maxCameraDegreesPerTick() {
            return (float) cameraDegreesPerTick;
        }
    }

    record RoutingLabelParameters(String entityRef, String item) {
        RoutingLabelParameters {
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(item, "item");
            if (!entityRef.matches("[A-Za-z0-9_-]{24}")
                    || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || item.length() > 128) {
                throw new IllegalArgumentException("invalid routing label");
            }
        }
    }

    static String transferMenuType(BlockStateFingerprint expectedState) {
        if ("minecraft:barrel".equals(expectedState.blockId())) {
            return SINGLE_CONTAINER_MENU;
        }
        if ("minecraft:chest".equals(expectedState.blockId())) {
            return ChestType.SINGLE.getSerializedName().equals(
                    expectedState.properties().get("type"))
                    ? SINGLE_CONTAINER_MENU
                    : DOUBLE_CONTAINER_MENU;
        }
        throw new IllegalArgumentException("unsupported vanilla storage container");
    }

    record MenuLayout(List<Integer> playerSlots, List<Integer> containerSlots) {
        MenuLayout {
            playerSlots = List.copyOf(playerSlots);
            containerSlots = List.copyOf(containerSlots);
        }
    }

    record TransferReadback(boolean exactMove, boolean goalVerified) {
    }

    record CraftReadback(boolean exactlyOneCraft, boolean goalVerified) {
    }

    private record InconclusiveState(
            PhaseFiveEvidence.Certainty certainty, String reason) {
    }

    record OpenHandPlan(int selectedSlot) {
        OpenHandPlan {
            if (selectedSlot < 0 || selectedSlot > 8) {
                throw new IllegalArgumentException("open-hand slot is outside the hotbar");
            }
        }

        boolean ready(LocalPlayer player) {
            if (player.getInventory().getSelectedSlot() != selectedSlot) return false;
            return safeKnownMenuOpenStack(player.getMainHandItem());
        }

        boolean readyAtSlot(LocalPlayer player) {
            return safeKnownMenuOpenStack(player.getInventory().getItem(selectedSlot));
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

    /** Fixed initial sources and per-click server baselines, bounded by the caller's stack/count caps. */
    static final class TransferBatch {
        private final List<PlannedTransferStack> plan;
        private final List<Integer> destinationSlots;
        private List<ContainerSyncSignals.StackFingerprint> confirmedSlots;
        private List<ContainerSyncSignals.StackFingerprint> clickBaseline;
        private long clickRevision;
        private long confirmedRevision = -1L;
        private long lastDispatchTick = -1L;
        private int confirmedMoves;
        private int confirmedCount;

        TransferBatch(
                List<ContainerSyncSignals.StackFingerprint> initial,
                List<Integer> sourceSlots, List<Integer> destinationSlots,
                String item, int defaultHash, boolean defaultComponentsOnly,
                int maximumStacks, int maximumCount, int neededCount) {
            if (maximumStacks < 1 || maximumStacks > 14 || maximumCount < 1 || maximumCount > 896) {
                throw new IllegalArgumentException("transfer batch limits are outside the contract");
            }
            this.confirmedSlots = List.copyOf(initial);
            this.destinationSlots = List.copyOf(destinationSlots);
            var selected = new ArrayList<PlannedTransferStack>();
            int plannedCount = 0;
            for (int slot : sourceSlots) {
                if (selected.size() >= maximumStacks || plannedCount >= neededCount) break;
                var stack = initial.get(slot);
                if (stack.empty() || !item.equals(stack.itemId())
                        || (defaultComponentsOnly && stack.itemAndComponentsHash() != defaultHash)
                        || stack.count() > maximumCount - plannedCount) continue;
                selected.add(new PlannedTransferStack(slot, stack));
                plannedCount = Math.addExact(plannedCount, stack.count());
            }
            this.plan = List.copyOf(selected);
        }

        PlannedTransferStack next() { return plan.get(confirmedMoves); }
        boolean exhausted() { return confirmedMoves >= plan.size(); }
        boolean inFlight() { return clickBaseline != null; }

        boolean beginClick(ContainerSyncSignals.ContainerSnapshot snapshot, long tick) {
            if (inFlight() || exhausted() || tick <= lastDispatchTick
                    || !snapshot.carried().empty()
                    || snapshot.packetLedgerRevision() < confirmedRevision
                    || !confirmedSlots.equals(snapshot.slots())
                    || !next().stack().equals(snapshot.slots().get(next().slot()))) return false;
            clickBaseline = List.copyOf(snapshot.slots());
            clickRevision = snapshot.packetLedgerRevision();
            lastDispatchTick = tick;
            return true;
        }

        boolean confirm(ContainerSyncSignals.ContainerSnapshot snapshot) {
            if (!inFlight() || snapshot.packetLedgerRevision() <= clickRevision
                    || !snapshot.carried().empty()
                    || !KnownMenuTransfers.exactWholeStackMove(
                            clickBaseline, snapshot.slots(), next().slot(), destinationSlots)) return false;
            confirmedCount = Math.addExact(confirmedCount, next().stack().count());
            confirmedMoves++;
            confirmedSlots = List.copyOf(snapshot.slots());
            confirmedRevision = snapshot.packetLedgerRevision();
            clickBaseline = null;
            return true;
        }

        boolean ackTimedOut(long tick) {
            return inFlight() && tick - lastDispatchTick >= KnownMenuTransfers.UPDATE_TIMEOUT_TICKS;
        }

        boolean reconcileReadback(ContainerSyncSignals.ContainerSnapshot snapshot) {
            if (inFlight()) confirm(snapshot);
            return !inFlight() && confirmedMoves > 0 && snapshot.carried().empty()
                    && confirmedSlots.equals(snapshot.slots());
        }
    }

    record PlannedTransferStack(int slot, ContainerSyncSignals.StackFingerprint stack) { }

    static final class AttemptState {
        private final PhaseFiveRequest request;
        private final ParsedParameters parameters;
        private final Vec3 aimPoint;
        private ContainerAimGate aimGate = new ContainerAimGate();
        private Stage stage = Stage.OPENING_INITIAL;
        private ClientRecipeCatalog.ResolvedRecipe recipe;
        private RoutineFailure failure;
        private InconclusiveState inconclusive;
        private PhaseFiveResult result;
        private int openCount;
        private int dispatchedContainerClicks;
        private int recipePlacements;
        private int completedActions;
        private int completedUnits;
        private int beforeSourceCount;
        private int afterSourceCount;
        private boolean transferReadbackObserved;
        private int beforeDestinationCount;
        private int afterDestinationCount;
        private int dispatchedStackCount;
        private TransferBatch transferBatch;
        private int expectedCraftOutputCount;
        private long lastPacketRevision;
        private long closeDeadlineClientTick;
        private long releaseStartedTick = -1L;
        private long releaseDeadlineTick;
        private boolean screenOwnedObserved;
        private boolean cursorReleaseConfirmed;
        private boolean releasePending;
        private boolean releaseConfirmed;
        private boolean releaseFault;
        private Throwable releaseFaultCause;
        private List<Object> expiredReleaseBoundary;
        private ClientPredictionSignals.PredictionAttempt openPrediction;
        private PlayerBaseline baseline;
        private OpenHandPlan openHand;
        private long openHandSelectedClientTick = -1L;
        private ViewLease view;

        AttemptState(PhaseFiveRequest request, ParsedParameters parameters) {
            this.request = Objects.requireNonNull(request, "request");
            this.parameters = Objects.requireNonNull(parameters, "parameters");
            this.aimPoint = inventoryAimPoint(request, parameters.target());
        }

        void prepareTransfer(int source, int destination, int stackCount) {
            beforeSourceCount = source;
            beforeDestinationCount = destination;
            dispatchedStackCount = stackCount;
            transferReadbackObserved = false;
        }

        void beginTransferBatch(TransferBatch batch, int source, int destination) {
            transferBatch = Objects.requireNonNull(batch, "batch");
            prepareTransfer(source, destination, 0);
        }

        void recordTransferReadback(int source, int destination) {
            afterSourceCount = source;
            afterDestinationCount = destination;
            transferReadbackObserved = true;
        }

        void updateTransferPrefix() {
            if (transferBatch == null) return;
            completedUnits = transferBatch.confirmedCount;
            completedActions = transferBatch.confirmedMoves;
        }

        private boolean terminal() {
            return stage == Stage.TERMINAL;
        }

        private String targetIdentity() {
            var target = parameters.target();
            return target.dimension() + ":" + target.x() + "," + target.y() + "," + target.z();
        }

        private String targetStateFingerprint() {
            return parameters.expectedState().toString();
        }

        Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
            basis.put("target", targetIdentity());
            basis.put("open_count", openCount);
            basis.put("container_clicks", dispatchedContainerClicks);
            basis.put("recipe_placements", recipePlacements);
            basis.put("completed_actions", completedActions);
            basis.put("completed_units", completedUnits);
            basis.put("full_readback_required", true);
            basis.put("blind_retry", false);
            basis.put("release_pending", releasePending);
            basis.put("release_confirmed", releaseConfirmed);
            basis.put("release_fault", releaseFault);
            if (parameters instanceof TransferParameters) {
                basis.put("source_before", beforeSourceCount);
                basis.put("destination_before", beforeDestinationCount);
                basis.put("transfer_readback_observed", transferReadbackObserved);
                basis.put("confirmed_transfer_count", completedUnits);
                basis.put("confirmed_stack_moves", completedActions);
                boolean inFlight = transferBatch != null && transferBatch.inFlight();
                basis.put("transfer_in_flight", inFlight);
                if (completedActions > 0) {
                    basis.put("confirmed_source_count", beforeSourceCount - completedUnits);
                    basis.put("confirmed_destination_count", beforeDestinationCount + completedUnits);
                }
                if (inFlight) {
                    basis.put("pending_source_before", beforeSourceCount - completedUnits);
                    basis.put("pending_destination_before", beforeDestinationCount + completedUnits);
                    basis.put("pending_stack_count", transferBatch.next().stack().count());
                }
                if (transferReadbackObserved) {
                    basis.put("source_after", afterSourceCount);
                    basis.put("destination_after", afterDestinationCount);
                }
            } else if (beforeDestinationCount != 0 || afterDestinationCount != 0) {
                basis.put("destination_before", beforeDestinationCount);
                basis.put("destination_after", afterDestinationCount);
            }
            return basis;
        }

        private boolean closeView(Minecraft minecraft) {
            if (view == null) return true;
            if (!view.close(minecraft)) return false;
            view = null;
            return true;
        }
    }

    /** Bounded camera lease; container use remains a normal first-person interaction. */
    private static final class ViewLease {
        private final LocalPlayer playerIdentity;
        private final Object levelIdentity;
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private final boolean restoreOnClose;
        private final float maxTurnPerTick;
        private boolean closed;

        private ViewLease(
            LocalPlayer player, boolean restoreOnClose, float maxTurnPerTick) {
            playerIdentity = Objects.requireNonNull(player, "player");
            levelIdentity = player.level();
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            originalSlot = player.getInventory().getSelectedSlot();
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            expectedSlot = originalSlot;
            this.restoreOnClose = restoreOnClose;
            this.maxTurnPerTick = maxTurnPerTick;
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

        static ViewLease acquire(
                LocalPlayer player, boolean restoreOnClose, float maxTurnPerTick) {
            if (!Float.isFinite(maxTurnPerTick) || maxTurnPerTick <= 0.0F) {
                throw new IllegalArgumentException("camera limit must be finite and positive");
            }
            return new ViewLease(
                    Objects.requireNonNull(player, "player"), restoreOnClose, maxTurnPerTick);
        }

        void turnToward(Minecraft minecraft, Vec3 point) {
            requireUndisturbed(minecraft);
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            float yaw = Mth.clamp(Mth.wrapDegrees(target.yaw - player.getYRot()),
                    -maxTurnPerTick, maxTurnPerTick);
            float pitch = Mth.clamp(target.pitch - player.getXRot(),
                    -maxTurnPerTick, maxTurnPerTick);
            player.turn(yaw / 0.15D, pitch / 0.15D);
            expectedYaw = player.getYRot();
            expectedPitch = player.getXRot();
        }

        boolean aligned(Minecraft minecraft, Vec3 point) {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            return Math.abs(Mth.wrapDegrees(target.yaw - player.getYRot())) <= AIM_EPSILON
                    && Math.abs(target.pitch - player.getXRot()) <= AIM_EPSILON;
        }

        boolean close(Minecraft minecraft) {
            if (closed) return releaseConfirmed(minecraft);
            if (ownershipContextLost(minecraft)) {
                closed = true;
                return true;
            }
            LocalPlayer player = minecraft.player;
            if (player == null || !undisturbed(minecraft)) return false;
            player.getInventory().setSelectedSlot(originalSlot);
            expectedSlot = originalSlot;
            if (restoreOnClose) {
                float yaw = Mth.wrapDegrees(originalYaw - player.getYRot());
                float pitch = originalPitch - player.getXRot();
                player.turn(yaw / 0.15D, pitch / 0.15D);
                expectedYaw = player.getYRot();
                expectedPitch = player.getXRot();
            }
            closed = true;
            return releaseConfirmed(minecraft);
        }

        boolean undisturbed(Minecraft minecraft) {
            if (closed || ownershipContextLost(minecraft) || minecraft.player == null) return false;
            LocalPlayer player = minecraft.player;
            return Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - expectedPitch) <= ROTATION_EPSILON
                    && player.getInventory().getSelectedSlot() == expectedSlot;
        }

        private boolean releaseConfirmed(Minecraft minecraft) {
            if (closed && ownershipContextLost(minecraft)) return true;
            LocalPlayer player = minecraft.player;
            return closed && player != null
                    && player.getInventory().getSelectedSlot() == originalSlot
                    && (!restoreOnClose
                    || Math.abs(Mth.wrapDegrees(player.getYRot() - originalYaw))
                            <= ROTATION_EPSILON
                    && Math.abs(player.getXRot() - originalPitch) <= ROTATION_EPSILON);
        }

        private void requireUndisturbed(Minecraft minecraft) {
            if (!undisturbed(minecraft)) {
                throw new IllegalStateException("view ownership changed");
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

    private record Rotation(float yaw, float pitch) {}
}
