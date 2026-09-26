package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.runtime.ContainerSyncSignals;
import dev.aod.mcmcp.runtime.KnownMenuOperationRefs;
import dev.aod.mcmcp.runtime.KnownStorageRefs;
import dev.aod.mcmcp.runtime.StorageAccess;
import dev.aod.mcmcp.runtime.KnownMenuProfileSupport;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** One single-use operation_ref on an accepted Menu or portable storage identity. */
public final class MinecraftKnownMenuPort implements PhaseFivePort {
    public static final String KIND = "operate_known_menu";
    private static final String TRANSFER_TO_PLAYER = "transfer_to_player";
    private static final int UPDATE_TIMEOUT_TICKS = 60;
    private static final int CLOSE_TIMEOUT_TICKS = 40;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final ContainerSyncSignals signals;
    private final KnownMenuOperationRefs references;
    private final KnownStorageRefs storages;
    private final Map<PhaseFiveAttempt, AttemptState> attempts = new IdentityHashMap<>();

    public MinecraftKnownMenuPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            ContainerSyncSignals signals,
            KnownMenuOperationRefs references) {
        this(minecraftSupplier, sessionSupplier, signals, references, new KnownStorageRefs());
    }

    public MinecraftKnownMenuPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            ContainerSyncSignals signals, KnownMenuOperationRefs references, KnownStorageRefs storages) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.references = Objects.requireNonNull(references, "references");
        this.storages = Objects.requireNonNull(storages, "storages");
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        AttemptState active = activeState(request);
        RoutineFailure failure = active == null
                ? preflight(request, session, false)
                : active.releasingOrTerminal() ? null : ongoingFailure(active, session);
        return new PhaseFiveFrame(tick, packetRevision(), failure);
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
        Minecraft minecraft = requireClientMinecraft();
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        var attempt = new PhaseFiveAttempt(
                routineId, request.kind(), tick, packetRevision(), hardDeadlineClientTick,
                Map.of("verification", "fresh_server_slot_delta",
                        "container_click_retry", "none"));
        AttemptState state = new AttemptState(request, operationReference(request));
        attempts.put(attempt, state);
        RoutineFailure failure = preflight(request, session, false);
        if (failure != null) {
            state.latchFailure(failure);
            return attempt;
        }
        if (request.parameters().containsKey("operation")) {
            state.storageTarget = storages.resolve(state.operationReference, minecraft, session, true).orElseThrow();
            capturePlayer(state, minecraft);
            state.initialPacketRevision = packetRevision();
            state.awaitingOpenEvidence = true;
            state.storageTarget.open(minecraft);
            state.openCount++;
            state.stage = Stage.OPENING;
            state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
            return attempt;
        }
        KnownMenuProfileSupport.Context context = currentContext(session).orElseThrow();
        KnownMenuOperationRefs.Operation operation = references.resolve(
                        state.operationReference,
                        context.referenceContext(session.worldSessionId(), tick))
                .orElse(null);
        if (operation == null || !TRANSFER_TO_PLAYER.equals(operation.operationKind())) {
            state.latchFailure(failure(
                    "KNOWN_MENU_OPERATION_REF_STALE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN));
            return attempt;
        }
        state.operation = operation;
        state.profileHash = context.profile().profileHash();
        state.initialPacketRevision = context.snapshot().packetLedgerRevision();
        state.screenIdentity = context.screen();
        state.playerIdentity = minecraft.player;
        state.levelIdentity = minecraft.level;
        state.connectionIdentity = minecraft.getConnection();
        state.positionX = minecraft.player.getX();
        state.positionY = minecraft.player.getY();
        state.positionZ = minecraft.player.getZ();
        state.health = minecraft.player.getHealth();
        state.stage = Stage.DISPATCH;
        state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        Minecraft minecraft = requireClientMinecraft();
        AttemptState state = requireAttempt(attempt);
        if (state.terminal()) return;
        if (state.releasing()) {
            maintainRelease(state);
            return;
        }
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        RoutineFailure safety = ongoingFailure(state, session);
        if (safety != null) {
            state.latchFailure(safety);
            return;
        }
        if (tick >= attempt.hardDeadlineClientTick() || tick > state.stageDeadline) {
            state.latchInconclusive("known_menu_update_deadline_exceeded");
            return;
        }
        if (state.storageTarget != null) {
            maintainStorage(state, session, tick);
            return;
        }
        if (state.stage == Stage.DISPATCH) {
            KnownMenuProfileSupport.Context context = currentContext(session).orElseThrow();
            if (!initialContextMatches(state, context)) {
                state.latchFailure(failure(
                        "KNOWN_MENU_SOURCE_CHANGED", RoutineFailure.Category.DIVERGENCE,
                        RoutineFailure.Recovery.REPLAN));
                return;
            }
            state.containerClicks++;
            dispatchServerConfirmedQuickMove(minecraft, context, state.operation.sourceSlot());
            state.stage = Stage.AWAIT_UPDATE;
            state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
            return;
        }
        if (state.stage == Stage.AWAIT_UPDATE) {
            ContainerSyncSignals.ContainerSnapshot server = minecraft.level == null ? null
                    : signals.snapshot(minecraft.level)
                            .map(ContainerSyncSignals.Snapshot::container).orElse(null);
            if (server == null
                    || server.packetLedgerRevision() <= state.initialPacketRevision) return;
            KnownMenuProfileSupport.Context context = currentContext(session).orElse(null);
            // A server QUICK_MOVE can arrive as several SetSlot packets.
            if (context == null || !exactTransferConfirmed(state.operation, context)) return;
            context.screen().onClose();
            state.stage = Stage.AWAIT_CLOSE;
            state.stageDeadline = boundedDeadline(tick, CLOSE_TIMEOUT_TICKS);
            return;
        }
        if (state.stage == Stage.AWAIT_CLOSE
                && AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
            state.latchSuccess();
        }
    }

    private static void capturePlayer(AttemptState state, Minecraft minecraft) {
        state.playerIdentity = minecraft.player;
        state.levelIdentity = minecraft.level;
        state.connectionIdentity = minecraft.getConnection();
        state.positionX = minecraft.player.getX();
        state.positionY = minecraft.player.getY();
        state.positionZ = minecraft.player.getZ();
        state.health = minecraft.player.getHealth();
    }

    private void maintainStorage(AttemptState state, WorldSessionTracker.Snapshot session, long tick) {
        Minecraft minecraft = requireMinecraft();
        if (state.stage == Stage.OPENING || state.stage == Stage.READBACK) {
            var context = KnownMenuProfileSupport.current(minecraft, session.worldSessionId(), signals).orElse(null);
            if (context == null) return;
            if (context.snapshot().packetLedgerRevision() <= state.initialPacketRevision
                    || !state.storageTarget.matches(context)) {
                state.latchInconclusive("storage_open_identity_mismatch");
                return;
            }
            state.screenIdentity = context.screen();
            state.profileHash = context.profile().profileHash();
            state.awaitingOpenEvidence = false;
            if (state.stage == Stage.READBACK) {
                // Preserve observed counts even if replenishment or another change prevents success.
                state.inspection = context;
                if (!state.exactTransfer.reconcileReadback(context.snapshot())) {
                    state.latchInconclusive("storage_readback_changed");
                    return;
                }
                closeStorage(state, tick);
                return;
            }
            if ("inspect".equals(state.request.parameters().get("operation"))) {
                state.inspection = context;
                closeStorage(state, tick);
                return;
            }
            state.exactTransfer = planStorageTransfer(state.request, context).orElse(null);
            if (state.exactTransfer == null) {
                state.latchFailure(failure("STORAGE_TRANSFER_UNPROVABLE", RoutineFailure.Category.PRECONDITION,
                        RoutineFailure.Recovery.REPLAN));
                return;
            }
            state.transferStack = context.snapshot().slots().get(state.exactTransfer.next().slot());
            boolean take = "take".equals(state.request.parameters().get("operation"));
            state.sourceBefore = countExact(context.snapshot(), take ? context.storageSlots() : context.playerSlots(), state.transferStack);
            state.destinationBefore = countExact(context.snapshot(), take ? context.playerSlots() : context.storageSlots(), state.transferStack);
            state.stage = Stage.EXACT_DISPATCH;
            state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
            return;
        }
        if (state.stage == Stage.EXACT_DISPATCH || state.stage == Stage.EXACT_UPDATE) {
            var context = KnownMenuProfileSupport.current(minecraft, session.worldSessionId(), signals, true).orElse(null);
            if (context == null) return;
            if (context.screen() != state.screenIdentity || !state.storageTarget.matches(context)) {
                state.latchInconclusive("storage_transfer_context_changed");
                return;
            }
            if (state.stage == Stage.EXACT_DISPATCH) {
                if (!state.exactTransfer.beginClick(context.snapshot(), tick)) {
                    state.latchInconclusive("storage_transfer_snapshot_changed");
                    return;
                }
                var click = state.exactTransfer.next();
                state.containerClicks++;
                KnownMenuTransfers.dispatchServerConfirmedPickup(minecraft, context.menu(), click.slot(), click.button());
                state.stage = Stage.EXACT_UPDATE;
                state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
                return;
            }
            if (!state.exactTransfer.confirm(context.snapshot(), signals.cursorProofRevision(minecraft.level))) return;
            if (state.exactTransfer.exhausted()) {
                context.screen().onClose();
                state.stage = Stage.REOPEN;
                state.stageDeadline = boundedDeadline(tick, CLOSE_TIMEOUT_TICKS);
            } else {
                state.stage = Stage.EXACT_DISPATCH;
                state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
            }
            return;
        }
        if (state.stage == Stage.REOPEN) {
            if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                    || minecraft.player.containerMenu != minecraft.player.inventoryMenu) return;
            if (!state.storageTarget.stillPresent(minecraft)) {
                state.latchInconclusive("storage_target_changed_before_readback");
                return;
            }
            state.initialPacketRevision = packetRevision();
            state.storageTarget.open(minecraft);
            state.openCount++;
            state.screenIdentity = null;
            state.awaitingOpenEvidence = true;
            state.stage = Stage.READBACK;
            state.stageDeadline = boundedDeadline(tick, UPDATE_TIMEOUT_TICKS);
            return;
        }
        if (state.stage == Stage.AWAIT_CLOSE && AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
            storages.inspected(session.worldSessionId(), state.operationReference, state.storageTarget, state.inspection);
            state.latchSuccess();
        }
    }

    private static void closeStorage(AttemptState state, long tick) {
        state.screenIdentity.onClose();
        state.stage = Stage.AWAIT_CLOSE;
        state.stageDeadline = boundedDeadline(tick, CLOSE_TIMEOUT_TICKS);
    }

    static Optional<ExactInventoryTransfer> planStorageTransfer(
            PhaseFiveRequest request, KnownMenuProfileSupport.Context context) {
        if (!context.supportsExactPickup()) return Optional.empty();
        String item = (String) request.parameters().get("item");
        int quantity = ((Number) request.parameters().get("transfer_count")).intValue();
        boolean take = "take".equals(request.parameters().get("operation"));
        List<Integer> sources = take ? context.transferableStorageSlots() : context.playerSlots();
        List<Integer> destinations = take ? context.playerSlots() : context.transferableStorageSlots();
        for (int source : sources) {
            ItemStack stack = context.menu().slots.get(source).getItem();
            var fingerprint = context.snapshot().slots().get(source);
            if (fingerprint.empty() || !fingerprint.itemId().equals(item)
                    || !KnownMenuTransfers.ordinaryPickupItem(stack.getItem().getClass())) continue;
            var capacities = context.pickupCapacities(stack);
            var availableSources = sources.stream().filter(slot -> capacities.get(slot) > 0).toList();
            var availableDestinations = destinations.stream().filter(slot -> capacities.get(slot) > 0).toList();
            var plan = ExactInventoryTransfer.plan(context.snapshot().slots(), availableSources, availableDestinations,
                    item, fingerprint.itemAndComponentsHash(), true, quantity, 14, stack.getMaxStackSize(), capacities);
            if (plan.isPresent()) return plan;
        }
        return Optional.empty();
    }

    private static int countExact(ContainerSyncSignals.ContainerSnapshot snapshot, List<Integer> slots,
            ContainerSyncSignals.StackFingerprint item) {
        int count = 0;
        for (int slot : slots) {
            var stack = snapshot.slots().get(slot);
            if (stack.itemId().equals(item.itemId()) && stack.itemAndComponentsHash() == item.itemAndComponentsHash())
                count = Math.addExact(count, stack.count());
        }
        return count;
    }

    /** Sends the ordinary server QUICK_MOVE without client changed-slot prediction suppression. */
    private static void dispatchServerConfirmedQuickMove(
            Minecraft minecraft, KnownMenuProfileSupport.Context context, int sourceSlot) {
        if (!context.canTransferEntireStack(sourceSlot)) {
            throw new IllegalStateException("known Menu click authority changed");
        }
        KnownMenuTransfers.dispatchServerConfirmedQuickMove(minecraft, context.menu(), sourceSlot);
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
                    PhaseFiveEvidence.Certainty.UNKNOWN, state.inconclusive, basis);
        }
        return new PhaseFiveEvidence.Pending(
                attempt.attemptId(), currentTick(), packetRevision(), basis);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        AttemptState state = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null || state.releaseConfirmed) return;
        state.stage = Stage.RELEASING;
        maintainRelease(state);
        if (!state.releaseConfirmed) {
            throw new IllegalStateException(state.releaseFault
                    ? "known Menu release failed closed"
                    : "known Menu release remains unconfirmed");
        }
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        var iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            AttemptState state = iterator.next().getValue();
            if (state.request == request) {
                if (!state.releaseConfirmed) {
                    throw new IllegalStateException(
                            "known Menu request cannot retire before confirmed release");
                }
                iterator.remove();
            }
        }
    }

    public void clearSession() {
        for (AttemptState state : attempts.values()) {
            state.stage = Stage.RELEASING;
            maintainRelease(state);
        }
        attempts.clear();
    }

    private RoutineFailure preflight(
            PhaseFiveRequest request,
            WorldSessionTracker.Snapshot session,
            boolean consume) {
        String reference;
        try {
            reference = operationReference(request);
        } catch (IllegalArgumentException invalid) {
            return failure("KNOWN_MENU_REQUEST_INVALID", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN);
        }
        Minecraft minecraft = requireMinecraft();
        if (!safePlayer(minecraft, session, null)) {
            return failure("KNOWN_MENU_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER);
        }
        if (request.parameters().containsKey("operation")) {
            if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                    || !minecraft.player.onGround()
                    || minecraft.player.getDeltaMovement().lengthSqr() > 0.0001D
                    || minecraft.player.containerMenu != minecraft.player.inventoryMenu
                    || !minecraft.player.containerMenu.getCarried().isEmpty()
                    || storages.resolve(reference, minecraft, session, consume).isEmpty()) {
                return failure("STORAGE_TARGET_UNAVAILABLE", RoutineFailure.Category.PRECONDITION,
                        RoutineFailure.Recovery.REPLAN);
            }
            return null;
        }
        Optional<KnownMenuProfileSupport.Context> optional = currentContext(session);
        if (optional.isEmpty()) {
            return failure("UNSUPPORTED_MENU_PROFILE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN);
        }
        KnownMenuProfileSupport.Context context = optional.orElseThrow();
        Optional<KnownMenuOperationRefs.Operation> operation = consume
                ? references.resolve(reference,
                        context.referenceContext(session.worldSessionId(), session.clientTick()))
                : references.peek(reference,
                        context.referenceContext(session.worldSessionId(), session.clientTick()));
        if (operation.isEmpty()
                || !TRANSFER_TO_PLAYER.equals(operation.orElseThrow().operationKind())
                || !initialContextMatches(operation.orElseThrow(), context)) {
            return failure("KNOWN_MENU_OPERATION_REF_STALE",
                    RoutineFailure.Category.PRECONDITION, RoutineFailure.Recovery.REPLAN);
        }
        return null;
    }

    private RoutineFailure ongoingFailure(
            AttemptState state, WorldSessionTracker.Snapshot session) {
        Minecraft minecraft = requireMinecraft();
        if (!safePlayer(minecraft, session, state)) {
            return failure("KNOWN_MENU_PLAYER_UNSAFE", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.USER);
        }
        if (state.storageTarget != null) {
            if (state.screenIdentity != null && state.stage != Stage.AWAIT_CLOSE && state.stage != Stage.REOPEN
                    && minecraft.gui.screen() != state.screenIdentity)
                return failure("KNOWN_MENU_CONTEXT_CHANGED", RoutineFailure.Category.SAFETY,
                        RoutineFailure.Recovery.REPLAN);
            return null;
        }
        if (state.stage == Stage.AWAIT_CLOSE) {
            return null;
        }
        if (state.stage == Stage.AWAIT_UPDATE) {
            return minecraft.gui.screen() == state.screenIdentity
                            && minecraft.player.containerMenu == state.screenIdentity.getMenu()
                    ? null
                    : failure("KNOWN_MENU_CONTEXT_CHANGED", RoutineFailure.Category.SAFETY,
                            RoutineFailure.Recovery.REPLAN);
        }
        Optional<KnownMenuProfileSupport.Context> context = currentContext(session);
        if (context.isEmpty() || context.orElseThrow().screen() != state.screenIdentity) {
            return failure("KNOWN_MENU_CONTEXT_CHANGED", RoutineFailure.Category.SAFETY,
                    RoutineFailure.Recovery.REPLAN);
        }
        return null;
    }

    private Optional<KnownMenuProfileSupport.Context> currentContext(
            WorldSessionTracker.Snapshot session) {
        return session == null ? Optional.empty() : KnownMenuProfileSupport.current(
                requireMinecraft(), session.worldSessionId(), signals);
    }

    private static boolean initialContextMatches(
            AttemptState state, KnownMenuProfileSupport.Context context) {
        return state.operation != null
                && context.screen() == state.screenIdentity
                && initialContextMatches(state.operation, context);
    }

    static boolean initialContextMatches(
            KnownMenuOperationRefs.Operation operation,
            KnownMenuProfileSupport.Context context) {
        if (!operation.initialServerSlots().equals(context.snapshot().slots())
                || operation.sourceSlot() < 0
                || operation.sourceSlot() >= context.menu().slots.size()
                || !context.canTransferEntireStack(operation.sourceSlot())) return false;
        ItemStack actual = context.menu().slots.get(operation.sourceSlot()).getItem();
        ItemStack expected = operation.exactStack();
        return actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameComponents(actual, expected);
    }

    static boolean exactTransferConfirmed(
            KnownMenuOperationRefs.Operation operation,
            KnownMenuProfileSupport.Context context) {
        List<ContainerSyncSignals.StackFingerprint> before = operation.initialServerSlots();
        List<ContainerSyncSignals.StackFingerprint> after = context.snapshot().slots();
        if (after.size() != before.size()
                || !after.get(operation.sourceSlot()).empty()
                || !context.menu().getCarried().isEmpty()
                || !context.snapshot().carried().empty()
                || !KnownMenuTransfers.exactWholeStackMove(
                        before, after, operation.sourceSlot(), context.playerSlots())) return false;
        int exactCount = 0;
        ItemStack expected = operation.exactStack();
        for (int slot : context.playerSlots()) {
            ItemStack actual = context.menu().slots.get(slot).getItem();
            if (ItemStack.isSameItemSameComponents(actual, expected)) {
                exactCount = Math.addExact(exactCount, actual.getCount());
            }
        }
        return operation.expectedExactComponentPlayerCount()
                        == Math.addExact(operation.baselineExactComponentPlayerCount(),
                                operation.stack().count())
                && exactCount == operation.expectedExactComponentPlayerCount();
    }

    private void maintainRelease(AttemptState state) {
        Minecraft minecraft = requireClientMinecraft();
        if (minecraft.player != state.playerIdentity
                || minecraft.level != state.levelIdentity
                || minecraft.getConnection() != state.connectionIdentity) {
            state.releaseConfirmed = true;
            state.publishTerminal();
            return;
        }
        if (state.awaitingOpenEvidence) {
            var session = sessionSupplier.get();
            var context = currentContext(session).orElse(null);
            if (context == null || context.snapshot().packetLedgerRevision() <= state.initialPacketRevision
                    || !state.storageTarget.matches(context)) {
                if (currentTick() > state.stageDeadline) state.releaseFault = true;
                return;
            }
            state.screenIdentity = context.screen();
            state.awaitingOpenEvidence = false;
        }
        if (AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                && minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
            if (state.exactTransfer != null && state.exactTransfer.pending() && !state.cursorReleaseProven) {
                state.releaseFault = true;
                return;
            }
            state.releaseConfirmed = true;
            state.publishTerminal();
            return;
        }
        var server = signals.snapshot(minecraft.level).map(ContainerSyncSignals.Snapshot::container).orElse(null);
        boolean cursorProven = server != null && server.containerId() == minecraft.player.containerMenu.containerId
                && server.carried().empty() && (state.exactTransfer == null || !state.exactTransfer.pending()
                || signals.cursorProofRevision(minecraft.level) > state.exactTransfer.dispatchRevision());
        if (minecraft.gui.screen() == state.screenIdentity
                && minecraft.player.containerMenu.getCarried().isEmpty() && cursorProven) {
            state.cursorReleaseProven = true;
            state.screenIdentity.onClose();
            return;
        }
        state.releaseFault = true;
    }

    private static boolean safePlayer(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AttemptState state) {
        if (session == null || !session.worldReady()
                || minecraft.level == null || minecraft.player == null
                || minecraft.gameMode == null || minecraft.getConnection() == null
                || minecraft.isPaused() || minecraft.gui.overlay() != null
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !minecraft.player.isAlive() || minecraft.player.isDeadOrDying()
                || minecraft.player.getHealth() < 10.0F
                || minecraft.player.hurtTime != 0
                || minecraft.player.getRemainingFireTicks() > 0
                || minecraft.player.isPassenger()) return false;
        if (state == null) return true;
        return minecraft.player == state.playerIdentity
                && minecraft.level == state.levelIdentity
                && minecraft.getConnection() == state.connectionIdentity
                && minecraft.player.getHealth() + 0.001F >= state.health
                && squaredDistance(minecraft.player, state) <= 0.0001D;
    }

    private static double squaredDistance(LocalPlayer player, AttemptState state) {
        double x = player.getX() - state.positionX;
        double y = player.getY() - state.positionY;
        double z = player.getZ() - state.positionZ;
        return x * x + y * y + z * z;
    }

    private AttemptState activeState(PhaseFiveRequest request) {
        return attempts.values().stream()
                .filter(state -> state.request == request).findFirst().orElse(null);
    }

    private AttemptState requireAttempt(PhaseFiveAttempt attempt) {
        AttemptState state = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null) throw new IllegalArgumentException("unknown known Menu attempt");
        return state;
    }

    private static String operationReference(PhaseFiveRequest request) {
        if (!KIND.equals(request.kind())
                || !(request.parameters().keySet().equals(Set.of("operation_ref"))
                    || request.parameters().keySet().equals(Set.of("operation_ref", "operation"))
                    || request.parameters().keySet().equals(Set.of("operation_ref", "operation", "item", "transfer_count")))
                || !(request.parameters().get("operation_ref") instanceof String reference)
                || !reference.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("invalid known Menu operation request");
        }
        if (request.parameters().containsKey("operation")) {
            Object operation = request.parameters().get("operation");
            boolean inspect = "inspect".equals(operation) && request.parameters().size() == 2;
            boolean transfer = ("take".equals(operation) || "store".equals(operation))
                    && request.parameters().get("item") instanceof String item
                    && item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    && request.parameters().get("transfer_count") instanceof Integer count
                    && count >= 1 && count <= 896;
            if (!inspect && !transfer) throw new IllegalArgumentException("invalid storage operation request");
        }
        return reference;
    }

    private Minecraft requireClientMinecraft() {
        Minecraft minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("known Menu adapter must run on the client thread");
        }
        return minecraft;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(minecraftSupplier.get(), "minecraft");
    }

    private WorldSessionTracker.Snapshot requireSession() {
        return Objects.requireNonNull(sessionSupplier.get(), "world session");
    }

    private long currentTick() {
        WorldSessionTracker.Snapshot session = sessionSupplier.get();
        return session == null ? 0L : Math.max(0L, session.clientTick());
    }

    private long packetRevision() {
        Minecraft minecraft = minecraftSupplier.get();
        return minecraft == null || minecraft.level == null ? 0L
                : signals.snapshot(minecraft.level)
                        .map(ContainerSyncSignals.Snapshot::packetLedgerRevision).orElse(0L);
    }

    private static long boundedDeadline(long tick, int delta) {
        return tick > Long.MAX_VALUE - delta ? Long.MAX_VALUE : tick + delta;
    }

    private static RoutineFailure failure(
            String code, RoutineFailure.Category category, RoutineFailure.Recovery recovery) {
        return new RoutineFailure(
                category, code, false, recovery, RoutineFailure.Scope.STEP, 1,
                Map.of(), Map.of(), Map.of(), List.of("player", "inventory", "screen"),
                recovery == RoutineFailure.Recovery.USER);
    }

    private enum Stage { OPENING, EXACT_DISPATCH, EXACT_UPDATE, REOPEN, READBACK,
        DISPATCH, AWAIT_UPDATE, AWAIT_CLOSE, RELEASING, TERMINAL }

    private static final class AttemptState {
        private final PhaseFiveRequest request;
        private final String operationReference;
        private KnownMenuOperationRefs.Operation operation;
        private StorageAccess.Target storageTarget;
        private ExactInventoryTransfer exactTransfer;
        private KnownMenuProfileSupport.Context inspection;
        private int openCount;
        private boolean awaitingOpenEvidence;
        private boolean cursorReleaseProven;
        private ContainerSyncSignals.StackFingerprint transferStack;
        private int sourceBefore;
        private int destinationBefore;
        private String profileHash;
        private AbstractContainerScreen<?> screenIdentity;
        private LocalPlayer playerIdentity;
        private Object levelIdentity;
        private Object connectionIdentity;
        private double positionX;
        private double positionY;
        private double positionZ;
        private float health;
        private Stage stage = Stage.TERMINAL;
        private long stageDeadline;
        private long initialPacketRevision;
        private int containerClicks;
        private RoutineFailure pendingFailure;
        private String pendingInconclusive;
        private PhaseFiveResult pendingResult;
        private RoutineFailure failure;
        private String inconclusive;
        private PhaseFiveResult result;
        private boolean releaseConfirmed;
        private boolean releaseFault;

        private AttemptState(PhaseFiveRequest request, String operationReference) {
            this.request = request;
            this.operationReference = operationReference;
        }

        private void latchFailure(RoutineFailure value) {
            if (pendingFailure == null && pendingInconclusive == null && pendingResult == null) {
                pendingFailure = value;
            }
            stage = Stage.RELEASING;
        }

        private void latchInconclusive(String value) {
            if (pendingFailure == null && pendingInconclusive == null && pendingResult == null) {
                pendingInconclusive = value;
            }
            stage = Stage.RELEASING;
        }

        private void latchSuccess() {
            if (pendingFailure == null && pendingInconclusive == null && pendingResult == null) {
                int quantity = storageTarget == null ? operation.stack().count()
                        : exactTransfer == null ? 1 : exactTransfer.confirmedCount();
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("transferred_items", exactTransfer == null && storageTarget != null ? 0 : quantity);
                evidence.put("profile_hash", profileHash);
                evidence.put("fresh_server_slot_delta", true);
                if (exactTransfer != null) {
                    evidence.put("transferred", quantity);
                    evidence.put("source_count_before", sourceBefore);
                    evidence.put("destination_count_before", destinationBefore);
                    evidence.put("source_count_after", sourceBefore - quantity);
                    evidence.put("destination_count_after", destinationBefore + quantity);
                }
                pendingResult = new PhaseFiveResult(quantity, true, evidence, List.of());
            }
            stage = Stage.RELEASING;
        }

        private void publishTerminal() {
            if (!releaseConfirmed) return;
            result = pendingResult;
            failure = pendingFailure;
            inconclusive = pendingInconclusive;
            stage = Stage.TERMINAL;
        }

        private boolean terminal() { return stage == Stage.TERMINAL && releaseConfirmed; }
        private boolean releasing() { return stage == Stage.RELEASING; }
        private boolean releasingOrTerminal() { return releasing() || terminal(); }

        private Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("open_count", openCount);
            basis.put("container_clicks", containerClicks);
            basis.put("recipe_placements", 0);
            basis.put("release_pending", releasing());
            basis.put("release_confirmed", releaseConfirmed);
            basis.put("release_fault", releaseFault);
            if (exactTransfer != null) {
                int confirmed = exactTransfer.confirmedCount();
                basis.put("source_before", sourceBefore);
                basis.put("destination_before", destinationBefore);
                basis.put("confirmed_transfer_count", confirmed);
                basis.put("confirmed_source_count", sourceBefore - confirmed);
                basis.put("confirmed_destination_count", destinationBefore + confirmed);
                basis.put("transfer_in_flight", exactTransfer.pending());
                basis.put("pending_source_before", sourceBefore - confirmed);
                basis.put("pending_destination_before", destinationBefore + confirmed);
                basis.put("transfer_readback_observed", inspection != null);
                if (inspection != null) {
                    boolean take = "take".equals(request.parameters().get("operation"));
                    basis.put("source_after", countExact(inspection.snapshot(), take ? inspection.storageSlots() : inspection.playerSlots(), transferStack));
                    basis.put("destination_after", countExact(inspection.snapshot(), take ? inspection.playerSlots() : inspection.storageSlots(), transferStack));
                }
            }
            return Map.copyOf(basis);
        }
    }
}
