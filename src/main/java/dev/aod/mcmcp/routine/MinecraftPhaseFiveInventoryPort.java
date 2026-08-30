package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.ExpectedOpenToken;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
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
    private static final int RELEASE_TIMEOUT_TICKS = 40;
    private static final int CRAFTING_GRID_LAST_SLOT = 9;
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final float LEGACY_MAX_TURN_PER_TICK = 8.0F;
    private static final float AIM_EPSILON = 0.75F;
    private static final float ROTATION_EPSILON = 0.1F;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final ClientRecipeCatalog recipes;
    private final ScreenOwnershipSignals screens;
    private final DoubleSupplier runtimeCameraDegreesPerTick;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftPhaseFiveInventoryPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            ClientRecipeCatalog recipes,
            ScreenOwnershipSignals screens,
            DoubleSupplier runtimeCameraDegreesPerTick) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.runtimeCameraDegreesPerTick = Objects.requireNonNull(
                runtimeCameraDegreesPerTick, "runtimeCameraDegreesPerTick");
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
        if (!safePlayer(minecraft)) {
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
        float cameraDegreesPerTick = state.parameters instanceof CraftParameters
                ? configuredCameraDegreesPerTick()
                : state.parameters.maxCameraDegreesPerTick();
        state.view = ViewLease.acquire(
                Objects.requireNonNull(minecraft.player),
                state.parameters.restoreViewOnRelease(),
                cameraDegreesPerTick);
        state.stage = Stage.AIMING_INITIAL;
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
            case AIMING_INITIAL -> maintainAim(attempt, state, false);
            case AIMING_READBACK -> maintainAim(attempt, state, true);
            case OPENING_INITIAL, OPENING_READBACK -> acceptOwnedSnapshot(attempt, state, minecraft);
            case CRAFT_WAIT_RESULT -> maintainCraftResult(attempt, state, minecraft);
            case CRAFT_RESULT_PICKUP_ACK ->
                    maintainCraftResultPickupAck(attempt, state, minecraft);
            case AWAITING_CLICK_ACK -> maintainClickAck(attempt, state);
            case AWAITING_CLOSE -> {
                if (screenState.phase() == ScreenOwnershipSignals.Phase.IDLE
                        && minecraft.gui.screen() == null
                        && minecraft.player != null
                        && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
                    if (targetReadyForReopen(minecraft, state.parameters)) {
                        state.stage = Stage.AIMING_READBACK;
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
                    : "inventory release remains unconfirmed");
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
        state.craftDestinationSlot = destination.orElseThrow();
        state.craftDestinationBefore = snapshot.slots().get(state.craftDestinationSlot);
        dispatchContainerClick(
                attempt, state, menu, CraftingMenu.RESULT_SLOT, ContainerInput.PICKUP,
                Stage.CRAFT_RESULT_PICKUP_ACK);
    }

    private void maintainCraftResultPickupAck(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof CraftingMenu menu)) {
            return;
        }
        var snapshot = proof.orElseThrow();
        var expected = state.recipe.view().result().alternatives().getFirst();
        if (!matchesDefaultStack(
                        snapshot.carried(), expected.item(), defaultStackHash(expected.item()))
                || snapshot.carried().count() != expected.count()
                || !craftingGridAndResultEmpty(snapshot.slots())
                || state.craftDestinationSlot < 0
                || state.craftDestinationSlot >= snapshot.slots().size()
                || !state.craftDestinationBefore.equals(
                        snapshot.slots().get(state.craftDestinationSlot))) {
            return;
        }
        dispatchContainerClick(
                attempt, state, menu, state.craftDestinationSlot, ContainerInput.PICKUP,
                Stage.AWAITING_CLICK_ACK);
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
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("source_count_before", source);
            evidence.put("source_count_after", source);
            evidence.put("destination_count_before", destination);
            evidence.put("destination_count_after", destination);
            evidence.put("transferred", 0);
            evidence.put("full_readback", true);
            evidence.putAll(availableItemEvidence(snapshot.slots(), sourceSlots, 27));
            succeed(state, destination, evidence);
            return;
        }

        if (state.completedActions >= transfer.maxStackMoves()) {
            fail(state, "TRANSFER_STACK_LIMIT_EXHAUSTED", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("maximum_stack_moves", transfer.maxStackMoves(),
                            "minimum_destination_count", transfer.minimumDestinationCount()),
                    Map.of("destination_count", destination,
                            "completed_stack_moves", state.completedActions));
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
        var candidate = chooseTransferSlot(
                snapshot.slots(), sourceSlots, transfer.item(), hash, remaining,
                transfer.defaultComponentsOnly());
        if (candidate.isEmpty()) {
            var observed = new LinkedHashMap<String, Object>();
            observed.put("source_count", source);
            observed.putAll(availableItemEvidence(snapshot.slots(), sourceSlots, 27));
            fail(state, "TRANSFER_FULL_STACK_UNAVAILABLE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("item", transfer.item(), "maximum_remaining", remaining),
                    observed);
            return;
        }
        int slot = candidate.orElseThrow();
        state.beforeSourceCount = source;
        state.beforeDestinationCount = destination;
        state.dispatchedStackCount = snapshot.slots().get(slot).count();
        dispatchContainerClick(
                attempt, state, menu, slot, ContainerInput.QUICK_MOVE,
                Stage.AWAITING_CLICK_ACK);
    }

    private void dispatchRecipePlacement(
            PhaseFiveAttempt attempt,
            AttemptState state,
            CraftingMenu menu,
            long snapshotRevision) {
        long before = prepareOwnedDispatch(attempt, state, menu);
        if (before < 0L) return;
        state.lastPacketRevision = Math.max(snapshotRevision, before);
        // Count before dispatch so a rejected or throwing call cannot disappear from usage.
        state.recipePlacements++;
        Objects.requireNonNull(requireMinecraft().gameMode).handlePlaceRecipe(
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

    /** Shared ownership/cursor barrier for every Agent-authored menu mutation. */
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
        long before = packetRevision();
        if (!screens.invalidateServerCursorProof(attempt.attemptId(), before)) {
            fail(state, "OWNED_SCREEN_CLICK_AUTHORITY_LOST", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return -1L;
        }
        state.cursorProofRequiredAfterRevision = Math.max(
                state.cursorProofRequiredAfterRevision, before);
        state.cursorReleaseConfirmed = false;
        state.screenOwnedObserved = true;
        return before;
    }

    private void maintainClickAck(PhaseFiveAttempt attempt, AttemptState state) {
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || !proof.orElseThrow().carried().empty()) {
            return;
        }
        if (state.parameters instanceof CraftParameters) {
            var expected = state.recipe.view().result().alternatives().getFirst();
            if (!craftingGridAndResultEmpty(proof.orElseThrow().slots())
                    || !craftDestinationConfirmed(
                            proof.orElseThrow().slots(), state.craftDestinationSlot,
                            state.craftDestinationBefore, expected.item(),
                            defaultStackHash(expected.item()), expected.count())) {
                return;
            }
        }
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
                || snapshot.lastServerCursorProofRevision()
                        <= state.cursorProofRequiredAfterRevision
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

    private void maintainAim(PhaseFiveAttempt attempt, AttemptState state, boolean readback) {
        var minecraft = assertClientThread();
        RoutineFailure failure = preflight(minecraft, sessionSupplier.get(), state.request);
        if (failure != null) {
            state.failure = failure;
            state.stage = Stage.TERMINAL;
            return;
        }
        var target = state.parameters.target();
        Vec3 point = new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D);
        state.view.turnToward(minecraft, point);
        if (state.view.aligned(minecraft, point)
                && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.isWorldBorderHit()
                && hit.getBlockPos().equals(blockPos(target))) {
            dispatchExpectedOpen(attempt, state, readback);
        }
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
        if (player.containerMenu != player.inventoryMenu
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
            return;
        }
        try {
            ScreenOwnershipSignals.Snapshot screen = screens.snapshot();
            state.screenOwnedObserved |= screen.everOwned();
            switch (screen.phase()) {
                case OWNED -> releaseOwnedMenu(attempt, state, minecraft);
                case EXPECTING_OPEN_PACKET, EXPECTING_SCREEN, EXPECTING_FULL_CONTENT, FAILED -> {
                    var decision = screens.cancelRoutine(attempt.attemptId());
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
                case IDLE -> confirmReleaseIfClear(state, minecraft);
            }
        } catch (RuntimeException | LinkageError releaseFailure) {
            state.releaseFault = true;
        }
    }

    private void releaseOwnedMenu(
            PhaseFiveAttempt attempt, AttemptState state, Minecraft minecraft) {
        var proof = freshServerCursorSnapshot(attempt, state);
        if (proof.isEmpty() || minecraft.player == null) return;
        var snapshot = proof.orElseThrow();
        if (!snapshot.carried().empty()) {
            if (state.cleanupCursorClickDispatched) {
                state.releaseFault = true;
                return;
            }
            if (!(minecraft.player.containerMenu instanceof CraftingMenu menu)
                    || !(state.parameters instanceof CraftParameters)
                    || state.recipe == null
                    || !craftCursorReturnSafe(
                            snapshot,
                            state.craftDestinationSlot,
                            state.craftDestinationBefore,
                            state.recipe.view().result().alternatives().getFirst().item(),
                            defaultStackHash(
                                    state.recipe.view().result().alternatives().getFirst().item()),
                            state.recipe.view().result().alternatives().getFirst().count())) {
                state.releaseFault = true;
                return;
            }
            state.cleanupCursorClickDispatched = dispatchContainerClick(
                    attempt, state, menu, state.craftDestinationSlot,
                    ContainerInput.PICKUP, Stage.RELEASING);
            state.releaseFault |= !state.cleanupCursorClickDispatched;
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
        if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE
                || minecraft.gui.screen() != null
                || minecraft.player == null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                || !minecraft.player.inventoryMenu.getCarried().isEmpty()
                || (state.screenOwnedObserved && !state.cursorReleaseConfirmed)) {
            return;
        }
        state.closeView(minecraft);
        state.releaseConfirmed = true;
        state.releasePending = false;
        state.stage = Stage.TERMINAL;
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
    private static void closeOwnedMenuClient(
            Minecraft minecraft, ScreenOwnershipSignals.CleanupDecision decision) {
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> containerScreen)
                || containerScreen.getMenu().containerId != decision.containerId()
                || !ScreenOwnershipSignals.menuTypeId(containerScreen.getMenu().getType())
                        .equals(decision.menuTypeId())) {
            throw new IllegalStateException("owned container screen changed before close");
        }
        containerScreen.onClose();
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
                        : integer(parameters.get("max_transfer_count"), "max_transfer_count"),
                Boolean.TRUE.equals(parameters.get("retain_view_on_release")),
                parameters.containsKey("max_camera_degrees_per_tick")
                        ? finiteNumber(parameters.get("max_camera_degrees_per_tick"),
                                "max_camera_degrees_per_tick")
                        : LEGACY_MAX_TURN_PER_TICK,
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

    private static double finiteNumber(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
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

    static boolean craftDestinationConfirmed(
            List<ContainerSyncSignals.StackFingerprint> stacks,
            int destinationSlot,
            ContainerSyncSignals.StackFingerprint before,
            String item,
            int defaultHash,
            int outputCount) {
        if (destinationSlot < 0 || destinationSlot >= stacks.size()
                || before == null || outputCount < 1
                || !before.empty() && !matchesDefaultStack(before, item, defaultHash)) {
            return false;
        }
        var after = stacks.get(destinationSlot);
        return matchesDefaultStack(after, item, defaultHash)
                && after.count() == (long) before.count() + outputCount;
    }

    static boolean craftCursorReturnSafe(
            ContainerSyncSignals.ContainerSnapshot snapshot,
            int destinationSlot,
            ContainerSyncSignals.StackFingerprint destinationBefore,
            String item,
            int defaultHash,
            int outputCount) {
        if (destinationSlot < 0 || destinationSlot >= snapshot.slots().size()
                || destinationBefore == null
                || outputCount < 1
                || !craftingGridAndResultEmpty(snapshot.slots())
                || !snapshot.slots().get(destinationSlot).equals(destinationBefore)) {
            return false;
        }
        return matchesDefaultStack(snapshot.carried(), item, defaultHash)
                && snapshot.carried().count() == outputCount;
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
        CRAFT_WAIT_RESULT,
        CRAFT_RESULT_PICKUP_ACK,
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
            BlockStateFingerprint expectedState) implements ParsedParameters {
        TransferParameters {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            if (!"default_components_only".equals(stackPolicy)
                    && !"item_id_any_components".equals(stackPolicy)) {
                throw new IllegalArgumentException("unsupported transfer stack policy");
            }
            if (minimumDestinationCount < 0 || minimumDestinationCount > 2_304
                    || maxTransferCount < 1 || maxTransferCount > 2_304
                    || maxStackMoves < 1 || maxStackMoves > 2_304
                    || !Double.isFinite(cameraDegreesPerTick)
                    || cameraDegreesPerTick < 0.1D || cameraDegreesPerTick > 18.0D) {
                throw new IllegalArgumentException("transfer limits are outside the v1 contract");
            }
        }

        @Override
        public String menuTypeId() {
            return SINGLE_CONTAINER_MENU;
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
        private int recipePlacements;
        private int completedActions;
        private int completedUnits;
        private int beforeSourceCount;
        private int afterSourceCount;
        private int beforeDestinationCount;
        private int afterDestinationCount;
        private int dispatchedStackCount;
        private int expectedCraftOutputCount;
        private int craftDestinationSlot = -1;
        private ContainerSyncSignals.StackFingerprint craftDestinationBefore =
                ContainerSyncSignals.StackFingerprint.EMPTY;
        private long lastPacketRevision;
        private long closeDeadlineClientTick;
        private long cursorProofRequiredAfterRevision = -1L;
        private long releaseStartedTick = -1L;
        private long releaseDeadlineTick;
        private boolean cleanupCursorClickDispatched;
        private boolean screenOwnedObserved;
        private boolean cursorReleaseConfirmed;
        private boolean releasePending;
        private boolean releaseConfirmed;
        private boolean releaseFault;
        private ViewLease view;

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
            basis.put("recipe_placements", recipePlacements);
            basis.put("completed_actions", completedActions);
            basis.put("completed_units", completedUnits);
            basis.put("full_readback_required", true);
            basis.put("blind_retry", false);
            basis.put("release_pending", releasePending);
            basis.put("release_confirmed", releaseConfirmed);
            basis.put("release_fault", releaseFault);
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

        private void closeView(Minecraft minecraft) {
            if (view != null) {
                view.close(minecraft);
                view = null;
            }
        }
    }

    /** Bounded camera lease; container use remains a normal first-person interaction. */
    private static final class ViewLease {
        private final float originalYaw;
        private final float originalPitch;
        private float expectedYaw;
        private float expectedPitch;
        private final boolean restoreOnClose;
        private final float maxTurnPerTick;
        private boolean closed;

        private ViewLease(
                LocalPlayer player, boolean restoreOnClose, float maxTurnPerTick) {
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            this.restoreOnClose = restoreOnClose;
            this.maxTurnPerTick = maxTurnPerTick;
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

        void close(Minecraft minecraft) {
            if (closed) {
                return;
            }
            LocalPlayer player = minecraft.player;
            if (player != null && restoreOnClose) {
                float yaw = Mth.wrapDegrees(originalYaw - player.getYRot());
                float pitch = originalPitch - player.getXRot();
                player.turn(yaw / 0.15D, pitch / 0.15D);
            }
            closed = true;
        }

        private void requireUndisturbed(Minecraft minecraft) {
            if (closed) {
                throw new IllegalStateException("view lease is closed");
            }
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            if (Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw)) > ROTATION_EPSILON
                    || Math.abs(player.getXRot() - expectedPitch) > ROTATION_EPSILON) {
                throw new IllegalStateException("view ownership changed");
            }
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
