package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.observation.ClientRecipeCatalog;
import dev.aodaruma.craftagent.runtime.ContainerSyncSignals;
import dev.aodaruma.craftagent.runtime.ExpectedOpenToken;
import dev.aodaruma.craftagent.runtime.ScreenOwnershipSignals;
import dev.aodaruma.craftagent.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Minecraft 26.2 adapter for the two Phase 5 inventory routines.
 *
 * <p>Every container click is single-shot. Its result is accepted only after the adapter closes
 * the owned screen, reopens the same exact block, and receives a fresh full-content packet.
 * Incremental slot packets may make a recipe result eligible for its one click, but never complete
 * either public routine.</p>
 */
public final class MinecraftPhaseFiveInventoryPort implements PhaseFivePort {
    static final String CRAFT_ITEMS = "craft_items";
    static final String TRANSFER_ITEMS = "transfer_items";
    static final String CRAFTING_MENU = "minecraft:crafting";
    static final String SINGLE_CONTAINER_MENU = "minecraft:generic_9x3";
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final float MIN_SAFE_HEALTH = 10.0F;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final ClientRecipeCatalog recipes;
    private final ScreenOwnershipSignals screens;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftPhaseFiveInventoryPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            ClientRecipeCatalog recipes,
            ScreenOwnershipSignals screens) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.screens = Objects.requireNonNull(screens, "screens");
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
                    : ongoingFailure(minecraft, session, active);
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
                || !state.request.bounds().dimension().equals(session.dimension())
                || !state.request.bounds().dimension().equals(
                        minecraft.level.dimension().identifier().toString())) {
            return failure("INVENTORY_WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN, Map.of("world_ready", true), Map.of());
        }
        if (!safePlayer(minecraft) || !minecraft.isWindowActive()) {
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
                recipes.refreshFromClient(minecraft, session.worldSessionId(), tick);
                var resolved = recipes.resolve(session.worldSessionId(),
                                craft.recipeRef(), craft.recipeFingerprint())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "recipe ref/fingerprint is stale for this client recipe book"));
                validateResolvedRecipe(craft, resolved);
                state.recipe = resolved;
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
        dispatchExpectedOpen(attempt, state, false);
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        var minecraft = assertClientThread();
        var state = requireAttempt(attempt);
        if (state.terminal()) {
            return;
        }
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        if (session == null || !session.worldReady()
                || !state.request.bounds().dimension().equals(session.dimension())) {
            fail(state, "INVENTORY_WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return;
        }
        if (!safePlayer(minecraft)) {
            fail(state, "INVENTORY_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("safe_survival_player", true), Map.of());
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
        if (screenState.phase() == ScreenOwnershipSignals.Phase.FAILED) {
            fail(state, "OWNED_SCREEN_FAILED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("expected_target", state.parameters.target().toString()),
                    Map.of("reason", Objects.toString(screenState.failureReason(), "unknown")));
            return;
        }

        switch (state.stage) {
            case OPENING_INITIAL, OPENING_READBACK -> acceptOwnedSnapshot(attempt, state, minecraft);
            case CRAFT_WAIT_RESULT -> maintainCraftResult(attempt, state, minecraft);
            case AWAITING_CLOSE -> {
                if (screenState.phase() == ScreenOwnershipSignals.Phase.IDLE
                        && minecraft.gui.screen() == null
                        && minecraft.mouseHandler.isMouseGrabbed()
                        && minecraft.player != null
                        && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
                    if (targetReadyForReopen(minecraft, state.parameters)) {
                        dispatchExpectedOpen(attempt, state, true);
                    } else if (tick > state.closeDeadlineClientTick) {
                        state.inconclusive = new InconclusiveState(
                                PhaseFiveEvidence.Certainty.UNKNOWN,
                                "target_not_ready_for_same_target_readback");
                        state.stage = Stage.TERMINAL;
                    }
                }
            }
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
        cleanupOwnedScreen(attempt.attemptId());
        if (state != null) {
            state.stage = Stage.TERMINAL;
        }
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        var iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().request == request) {
                cleanupOwnedScreen(entry.getKey().attemptId());
                iterator.remove();
            }
        }
    }

    /** Disconnect/level-replacement fence; safe to call repeatedly on the client thread. */
    public void clearSession() {
        for (var attempt : List.copyOf(attempts.keySet())) {
            cleanupOwnedScreen(attempt.attemptId());
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
        if (!attempt.attemptId().equals(session.token().routineId())
                || !state.targetIdentity().equals(session.token().targetIdentity())) {
            fail(state, "OWNED_SCREEN_AUTHORITY_MISMATCH", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of());
            return;
        }
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

        var result = state.recipe.view().result().alternatives().getFirst();
        state.beforeDestinationCount = current;
        state.expectedCraftOutputCount = result.count();
        state.lastPacketRevision = snapshot.packetLedgerRevision();
        Objects.requireNonNull(requireMinecraft().gameMode).handlePlaceRecipe(
                craftingMenu.containerId, state.recipe.displayId(), false);
        state.stage = Stage.CRAFT_WAIT_RESULT;
    }

    private void maintainCraftResult(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        var owned = screens.ownedSession();
        if (owned.isEmpty() || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof CraftingMenu menu)) {
            return;
        }
        var snapshot = owned.orElseThrow().serverSnapshot();
        if (snapshot.packetLedgerRevision() <= state.lastPacketRevision
                || snapshot.slots().isEmpty()) {
            return;
        }
        var expected = state.recipe.view().result().alternatives().getFirst();
        var resultStack = snapshot.slots().get(CraftingMenu.RESULT_SLOT);
        if (!matchesDefaultStack(resultStack, expected.item(), defaultStackHash(expected.item()))
                || resultStack.count() != expected.count()
                || !snapshot.carried().empty()) {
            return;
        }
        Objects.requireNonNull(minecraft.gameMode).handleContainerInput(
                menu.containerId, CraftingMenu.RESULT_SLOT, 0,
                ContainerInput.QUICK_MOVE, minecraft.player);
        state.dispatchedContainerClicks++;
        closeForReadback(attempt, state);
    }

    private void acceptTransferSnapshot(
            PhaseFiveAttempt attempt,
            AttemptState state,
            TransferParameters transfer,
            LocalPlayer player,
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot) {
        if (!(menu instanceof ChestMenu chestMenu)
                || chestMenu.getRowCount() != 3
                || !SINGLE_CONTAINER_MENU.equals(snapshot.menuTypeId())) {
            fail(state, "SINGLE_CHEST_OR_BARREL_MENU_REQUIRED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("menu_type", SINGLE_CONTAINER_MENU, "rows", 3),
                    Map.of("menu_type", snapshot.menuTypeId()));
            return;
        }
        int hash = defaultStackHash(transfer.item());
        var layout = layout(menu, player.getInventory());
        int playerCount = countExact(snapshot.slots(), layout.playerSlots(), transfer.item(), hash);
        int containerCount = countExact(
                snapshot.slots(), layout.containerSlots(), transfer.item(), hash);
        int source = transfer.playerToContainer() ? playerCount : containerCount;
        int destination = transfer.playerToContainer() ? containerCount : playerCount;

        if (state.stage == Stage.OPENING_READBACK) {
            var readback = verifyTransferReadback(
                    state.beforeSourceCount,
                    state.beforeDestinationCount,
                    source,
                    destination,
                    state.dispatchedStackCount,
                    transfer.minimumDestinationCount());
            state.afterSourceCount = source;
            state.afterDestinationCount = destination;
            if (!readback.exactMove()) {
                state.inconclusive = new InconclusiveState(
                        PhaseFiveEvidence.Certainty.AMBIGUOUS,
                        "transfer_readback_did_not_confirm_exact_full_stack_move");
                state.stage = Stage.TERMINAL;
                return;
            }
            state.completedUnits = Math.addExact(state.completedUnits, state.dispatchedStackCount);
            state.completedActions++;
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
        } else if (destination >= transfer.minimumDestinationCount()) {
            succeed(state, destination, Map.of(
                    "source_count_before", source,
                    "source_count_after", source,
                    "destination_count_before", destination,
                    "destination_count_after", destination,
                    "transferred", 0,
                    "full_readback", true));
            return;
        }

        int remaining = transfer.maxTransferCount() - state.completedUnits;
        if (remaining <= 0) {
            fail(state, "TRANSFER_LIMIT_EXHAUSTED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("minimum_destination_count", transfer.minimumDestinationCount()),
                    Map.of("destination_count", destination,
                            "transferred", state.completedUnits));
            return;
        }
        List<Integer> sourceSlots = transfer.playerToContainer()
                ? layout.playerSlots() : layout.containerSlots();
        var candidate = chooseFullStackSlot(
                snapshot.slots(), sourceSlots, transfer.item(), hash, remaining);
        if (candidate.isEmpty()) {
            fail(state, "TRANSFER_FULL_STACK_UNAVAILABLE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("item", transfer.item(), "maximum_remaining", remaining),
                    Map.of("source_count", source));
            return;
        }
        int slot = candidate.orElseThrow();
        state.beforeSourceCount = source;
        state.beforeDestinationCount = destination;
        state.dispatchedStackCount = snapshot.slots().get(slot).count();
        Objects.requireNonNull(requireMinecraft().gameMode).handleContainerInput(
                menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
        state.dispatchedContainerClicks++;
        closeForReadback(attempt, state);
    }

    private void closeForReadback(PhaseFiveAttempt attempt, AttemptState state) {
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
        var player = requireMinecraft().player;
        if (player != null) {
            player.closeContainer();
        }
        if (!state.terminal()) {
            long tick = currentTick();
            state.closeDeadlineClientTick = tick > Long.MAX_VALUE - OPEN_TIMEOUT_TICKS
                    ? Long.MAX_VALUE : tick + OPEN_TIMEOUT_TICKS;
            state.stage = Stage.AWAITING_CLOSE;
        }
    }

    private void dispatchExpectedOpen(
            PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        var minecraft = assertClientThread();
        var session = requireSession();
        RoutineFailure failure = preflight(minecraft, session, state.request);
        if (failure != null) {
            state.failure = failure;
            state.stage = Stage.TERMINAL;
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
        if (!Objects.requireNonNull(minecraft.gameMode)
                .useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit)
                .consumesAction()) {
            screens.cancelRoutine(attempt.attemptId());
            fail(state, "CONTAINER_NORMAL_USE_REJECTED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("target", parameters.target().toString()), Map.of());
            return;
        }
        state.openCount++;
        state.stage = readback ? Stage.OPENING_READBACK : Stage.OPENING_INITIAL;
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
        if (!safePlayer(minecraft)) {
            return failure("INVENTORY_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER, Map.of("safe_survival_player", true), Map.of());
        }
        if (!minecraft.isWindowActive() || minecraft.isPaused()
                || !minecraft.mouseHandler.isMouseGrabbed()
                || minecraft.gui.screen() != null || minecraft.gui.overlay() != null
                || player.containerMenu != player.inventoryMenu
                || screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return failure("INVENTORY_SCREEN_NOT_CLEAR", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER,
                    Map.of("screen", "clear", "ownership", "idle"), Map.of());
        }
        var position = blockPos(parameters.target());
        if (!level.isLoaded(position) || !level.getWorldBorder().isWithinBounds(position)) {
            return failure("INVENTORY_TARGET_NOT_CURRENT", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("loaded_and_within_border", true), Map.of());
        }
        exactHit(minecraft, parameters.target());
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
        } else if (!singleVanillaContainer(state)) {
            return failure("SINGLE_CHEST_OR_BARREL_REQUIRED",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN,
                    Map.of("container", "single_vanilla_chest_or_barrel"),
                    Map.of("block", actual.blockId()));
        }
        return null;
    }

    private static void validateResolvedRecipe(
            CraftParameters parameters, ClientRecipeCatalog.ResolvedRecipe recipe) {
        var view = recipe.view();
        if (!view.supported() || view.result().alternatives().size() != 1
                || !view.result().deterministic()) {
            throw new IllegalArgumentException("recipe display is not supported for execution");
        }
        var result = view.result().alternatives().getFirst();
        if (!parameters.goalItem().equals(result.item())) {
            throw new IllegalArgumentException("recipe result does not match the absolute goal item");
        }
        if (!"inventory_2x2".equals(view.requiredScreen())
                && !"crafting_table".equals(view.requiredScreen())) {
            throw new IllegalArgumentException("recipe does not use a supported crafting grid");
        }
    }

    private void cleanupOwnedScreen(UUID authority) {
        var decision = screens.cancelRoutine(authority);
        if (decision.closeMenuBestEffort()) {
            var minecraft = requireMinecraft();
            if (minecraft.player != null) {
                minecraft.player.closeContainer();
            }
        }
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
        requireDefaultComponents(stack, "stack");
        String direction = string(parameters.get("direction"), "direction");
        if (!"player_to_container".equals(direction)
                && !"container_to_player".equals(direction)) {
            throw new IllegalArgumentException("unsupported transfer direction");
        }
        return new TransferParameters(
                "player_to_container".equals(direction),
                string(stack.get("item"), "stack.item"),
                integer(goal.get("minimum_destination_count"),
                        "goal.minimum_destination_count"),
                integer(parameters.get("max_transfer_count"), "max_transfer_count"),
                target(map(container.get("target"), "container.target")),
                state(map(container.get("expected_state"), "container.expected_state")));
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

    private static BlockHitResult exactHit(Minecraft minecraft, BlockTarget target) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || hit.isWorldBorderHit()
                || !hit.getBlockPos().equals(blockPos(target))) {
            throw new IllegalArgumentException("current crosshair does not identify the exact target");
        }
        return hit;
    }

    private static boolean singleVanillaContainer(BlockState state) {
        if (state.is(Blocks.BARREL)) {
            return true;
        }
        return state.is(Blocks.CHEST)
                && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
    }

    private static boolean safePlayer(Minecraft minecraft) {
        var player = minecraft.player;
        return player != null
                && minecraft.gameMode != null
                && player.isAlive()
                && !player.isDeadOrDying()
                && player.getHealth() >= MIN_SAFE_HEALTH
                && player.hurtTime == 0
                && player.getRemainingFireTicks() <= 0
                && !player.isUsingItem()
                && !player.isShiftKeyDown()
                && !player.isPassenger()
                && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL;
    }

    private static boolean targetReadyForReopen(
            Minecraft minecraft, ParsedParameters parameters) {
        if (minecraft.level == null) {
            return false;
        }
        var position = blockPos(parameters.target());
        if (!minecraft.level.isLoaded(position)
                || !minecraft.level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        try {
            exactHit(minecraft, parameters.target());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return parameters.expectedState().equals(
                fingerprint(minecraft.level.getBlockState(position)));
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static int defaultStackHash(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        var holder = identifier == null
                ? Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item>>empty()
                : BuiltInRegistries.ITEM.get(identifier);
        if (holder.isEmpty() || holder.orElseThrow().value() == Items.AIR) {
            throw new IllegalArgumentException("item is not registered");
        }
        return ItemStack.hashItemAndComponents(new ItemStack(holder.orElseThrow().value()));
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

    private static int countPlayerItem(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            AbstractContainerMenu menu,
            Inventory inventory,
            String item,
            int defaultHash) {
        return countExact(stacks, layout(menu, inventory).playerSlots(), item, defaultHash);
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
        OPENING_INITIAL,
        CRAFT_WAIT_RESULT,
        AWAITING_CLOSE,
        OPENING_READBACK,
        TERMINAL
    }

    sealed interface ParsedParameters permits CraftParameters, TransferParameters {
        BlockTarget target();

        BlockStateFingerprint expectedState();

        String menuTypeId();
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
    }

    record TransferParameters(
            boolean playerToContainer,
            String item,
            int minimumDestinationCount,
            int maxTransferCount,
            BlockTarget target,
            BlockStateFingerprint expectedState) implements ParsedParameters {
        TransferParameters {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            if (minimumDestinationCount < 0 || minimumDestinationCount > 2_304
                    || maxTransferCount < 1 || maxTransferCount > 2_304) {
                throw new IllegalArgumentException("transfer limits are outside the v1 contract");
            }
        }

        @Override
        public String menuTypeId() {
            return SINGLE_CONTAINER_MENU;
        }
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

    private static final class AttemptState {
        private final PhaseFiveRequest request;
        private final ParsedParameters parameters;
        private Stage stage = Stage.OPENING_INITIAL;
        private ClientRecipeCatalog.ResolvedRecipe recipe;
        private RoutineFailure failure;
        private InconclusiveState inconclusive;
        private PhaseFiveResult result;
        private int openCount;
        private int dispatchedContainerClicks;
        private int completedActions;
        private int completedUnits;
        private int beforeSourceCount;
        private int afterSourceCount;
        private int beforeDestinationCount;
        private int afterDestinationCount;
        private int dispatchedStackCount;
        private int expectedCraftOutputCount;
        private long lastPacketRevision;
        private long closeDeadlineClientTick;

        private AttemptState(PhaseFiveRequest request, ParsedParameters parameters) {
            this.request = Objects.requireNonNull(request, "request");
            this.parameters = Objects.requireNonNull(parameters, "parameters");
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

        private Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
            basis.put("target", targetIdentity());
            basis.put("open_count", openCount);
            basis.put("container_clicks", dispatchedContainerClicks);
            basis.put("completed_actions", completedActions);
            basis.put("completed_units", completedUnits);
            basis.put("full_readback_required", true);
            basis.put("blind_retry", false);
            if (beforeDestinationCount != 0 || afterDestinationCount != 0) {
                basis.put("destination_before", beforeDestinationCount);
                basis.put("destination_after", afterDestinationCount);
            }
            if (parameters instanceof TransferParameters) {
                basis.put("source_before", beforeSourceCount);
                basis.put("source_after", afterSourceCount);
            }
            return basis;
        }
    }
}
