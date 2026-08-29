package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.mixin.client.BlockItemPlacementInvoker;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockOutcome;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSample;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSource;
import dev.aod.mcmcp.observation.ObservedContext;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Minecraft 26.2 adapter for one bounded, stationary {@code apply_block_plan} phase. */
public final class MinecraftApplyBlockPlanPort implements ApplyBlockPlanPort {
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double MAX_POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float ROTATION_DRIFT_EPSILON = 0.25F;
    private static final float MAX_AIM_TURN_DEGREES_PER_TICK = 8.0F;
    private static final float AIM_EPSILON_DEGREES = 0.20F;
    private static final int MAX_SHAPE_BOXES = 16;
    private static final double ROUTE_SURFACE_EPSILON = 1.0e-4D;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final WorldMemory memory;
    private final MinecraftObservationService observations;
    private final ClientPredictionSignals predictions;
    private final ClientReconciliationSignals reconciliations;
    private final Map<ApplyBlockPlanRequest, PlanState> plans = new IdentityHashMap<>();
    private final Map<ApplyBlockPlanPreparationAttempt, PreparationState> preparations =
            new IdentityHashMap<>();
    private final Map<ApplyBlockPlanActionAttempt, ActionState> actions = new IdentityHashMap<>();

    public MinecraftApplyBlockPlanPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            ClientPredictionSignals predictions,
            ClientReconciliationSignals reconciliations) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
    }

    @Override
    public ApplyBlockPlanFrame observe(ApplyBlockPlanRequest request) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        var session = sessionSupplier.get();
        var level = minecraft.level;
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        boolean worldReady = session != null && session.worldReady()
                && level != null && player != null && gameMode != null
                && minecraft.getConnection() != null
                && request.bounds().dimension().equals(session.dimension())
                && request.bounds().dimension().equals(level.dimension().identifier().toString());
        if (!worldReady) {
            long tick = session == null ? 0L : session.clientTick();
            return new ApplyBlockPlanFrame(
                    tick, memory.revision(), false, false, false, false, false, false,
                    false, Map.of(), Map.of(), Set.of());
        }

        var plan = plans.computeIfAbsent(request,
                ignored -> PlanState.capture(session, player));
        if (!plan.sameSession(session)) {
            return new ApplyBlockPlanFrame(
                    session.clientTick(), memory.revision(), false, false, false, false,
                    false, false, false, Map.of(), Map.of(), Set.of());
        }
        var recon = reconciliations.bindAndSnapshot(level, session.worldSessionId());
        refreshInventorySynchronization(plan, recon, player);

        boolean positionHeld = plan.positionMatches(player);
        boolean controlContextClear = !minecraft.isPaused()
                && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null
                && !player.isUsingItem()
                && positionHeld
                && gameMode.getPlayerMode() == GameType.SURVIVAL;
        boolean alive = player.isAlive() && !player.isDeadOrDying();
        boolean healthSafe = alive && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= plan.initialHealth
                && player.hurtTime == 0 && player.getRemainingFireTicks() <= 0;
        boolean threatClear = visibleThreatClear(minecraft, player, level);
        boolean screenClear = minecraft.gui.screen() == null
                && player.containerMenu == player.inventoryMenu;

        var ownedChild = ownedChild(request);
        var positions = observationPositions(request, ownedChild.orElse(null));
        var samples = observations.observeBlocks(
                minecraft, session.clientTick(), positions, BlockSource.LIVE);
        var byPosition = new LinkedHashMap<BlockPos, BlockSample>();
        for (int index = 0; index < positions.size(); index++) {
            byPosition.put(positions.get(index), samples.get(index));
        }
        boolean ownershipActive = ownedChild.isPresent();
        boolean standSafe;
        if (ownershipActive && plan.standBaseline != null) {
            standSafe = plan.standBaseline.matches(player);
        } else {
            // This routine is stationary: unlike navigation, it never authorizes entering a new
            // cell. Grounding and the already-enforced position lock are therefore the relevant
            // embodied safety facts. Requiring LIVE samples for the player's own feet/floor is
            // impossible in an ordinary first-person view; reading their raw BlockStates instead
            // would turn hidden client world data into a Boolean oracle.
            standSafe = stationaryStandReady(
                    player.onGround(), player.isPassenger(), plan.positionDriftSquared(player));
            if (standSafe) {
                plan.standBaseline = StandBaseline.capture(player);
            }
        }
        var cells = new LinkedHashMap<BlockTarget, ApplyBlockPlanCellObservation>();
        plan.candidates.clear();
        for (var step : request.steps()) {
            var position = blockPos(step.target());
            var sample = byPosition.get(position);
            Optional<BlockStateFingerprint> live = currentFingerprint(sample);
            boolean replaceable = false;
            boolean supportedMutation = withinWorldBorder(level, position);
            if (sample != null && sample.outcome() == BlockOutcome.CURRENT) {
                BlockState state = level.getBlockState(position);
                replaceable = state.canBeReplaced();
                supportedMutation &= !multiCellBlock(state);
            }
            List<AimCandidate> breakCandidates = supportedMutation
                    ? breakCandidates(level, player, step.target(), byPosition) : List.of();
            List<AimCandidate> placeCandidates = supportedMutation
                    ? placeCandidates(level, player, step, byPosition, plan) : List.of();
            if (step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                plan.candidates.put(
                        new CandidateKey(step.target(), ApplyBlockPlanChildStage.BREAK),
                        breakCandidates);
            }
            if (step.operation() == ApplyBlockPlanOperation.PLACE
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                plan.candidates.put(
                        new CandidateKey(step.target(), ApplyBlockPlanChildStage.PLACE),
                        placeCandidates);
            }
            boolean aimFeasible = switch (step.operation()) {
                case VERIFY_ONLY -> true;
                case BREAK_TO_AIR -> !breakCandidates.isEmpty();
                case PLACE -> !placeCandidates.isEmpty();
                // The source block can legitimately occlude the future support face. The
                // replacement PLACE child gets a new all-CURRENT observation after confirmed air.
                case REPLACE -> live.filter(MinecraftApplyBlockPlanPort::air).isPresent()
                        ? !placeCandidates.isEmpty() : !breakCandidates.isEmpty();
            };
            boolean stepStandSafe = standSafe
                    && (step.operation() == ApplyBlockPlanOperation.VERIFY_ONLY
                            || !standCells(player).contains(position));
            cells.put(step.target(), new ApplyBlockPlanCellObservation(
                    step.target(), live, replaceable, stepStandSafe, aimFeasible));
        }

        var inventory = inventoryFacts(player, request);
        plan.lastFrameTick = session.clientTick();
        plan.lastObservationRevision = memory.revision();
        return new ApplyBlockPlanFrame(
                session.clientTick(), memory.revision(), true, controlContextClear,
                alive, healthSafe,
                threatClear, screenClear, !plan.inventoryPending,
                cells, inventory.counts(), inventory.hotbarItems());
    }

    @Override
    public ApplyBlockPlanPreparationAttempt beginPreparation(
            ApplyBlockPlanRequest request,
            ApplyBlockPlanChildAction child,
            long leaseExpiresAtClientTick) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(child, "child");
        var session = requireSession();
        if (leaseExpiresAtClientTick <= session.clientTick()) {
            throw new IllegalArgumentException("preparation lease has already expired");
        }
        var plan = requirePlan(request, session);
        if (plan.inventoryPending) {
            throw new IllegalStateException("inventory reconciliation is still pending");
        }
        if (!universalSafetyClear(minecraft, plan)) {
            throw new ConstructionSafetyChangedException();
        }
        if (!withinWorldBorder(Objects.requireNonNull(minecraft.level), blockPos(child.target()))) {
            throw new IllegalStateException("child target is outside the current world border");
        }
        var candidates = List.copyOf(plan.candidates.getOrDefault(
                new CandidateKey(child.target(), child.stage()), List.of()));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("child has no current reachable aim candidate");
        }
        if (child.stage() == ApplyBlockPlanChildStage.PLACE) {
            String required = child.requiredItemId().orElseThrow();
            int sourceSlot = findEligibleInventorySlot(
                    Objects.requireNonNull(minecraft.player), required);
            if (sourceSlot < 0) {
                throw new IllegalStateException("required eligible block item is not in inventory");
            }
            boolean staged = sourceSlot >= Inventory.getSelectionSize();
            long stagingRevision = -1L;
            int stagingCount = -1;
            if (staged) {
                var before = reconciliations.bindAndSnapshot(
                        Objects.requireNonNull(minecraft.level), session.worldSessionId());
                stagingRevision = before.selectedSlotInventoryRevision();
                stagingCount = eligibleInventoryCount(
                        Objects.requireNonNull(minecraft.player), required);
                if (!stageIntoSelectedHotbar(minecraft,
                        Objects.requireNonNull(minecraft.player), sourceSlot, required)) {
                    throw new IllegalStateException("required block item could not be staged");
                }
            }
            plan.pendingStaging = staged
                    ? new PendingStaging(required, stagingCount, stagingRevision)
                    : null;
        }
        var attempt = new ApplyBlockPlanPreparationAttempt(
                UUID.randomUUID(), child.stepIndex(), session.clientTick(),
                memory.revision(), leaseExpiresAtClientTick);
        var ownership = ControlOwnership.acquire(
                minecraft, child.requiredItemId(), attempt.attemptId());
        try {
            ownership.selectOwnedSlot(minecraft);
            if (child.stage() == ApplyBlockPlanChildStage.PLACE) {
                candidates = exactPlacementCandidates(minecraft, child, candidates);
                if (candidates.isEmpty()) {
                    throw new IllegalStateException(
                            "no placement candidate produces the exact requested state");
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            ownership.close(minecraft);
            throw failure;
        }
        preparations.put(attempt, new PreparationState(
                request, child, plan, ownership, candidates));
        return attempt;
    }

    @Override
    public void maintainPreparation(ApplyBlockPlanPreparationAttempt attempt) {
        assertClientThread();
        var active = requirePreparation(attempt);
        if (active.failure != null || active.transferred) {
            return;
        }
        var session = requireSession();
        if (session.clientTick() >= attempt.leaseExpiresAtClientTick()) {
            active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            return;
        }
        try {
            if (!universalSafetyClear(requireMinecraft(), active.plan)) {
                active.failure = safetyFailure();
                return;
            }
            if (!refreshStagingSynchronization(active)) {
                return;
            }
            if (!candidateWithinWorldBorder(
                    Objects.requireNonNull(requireMinecraft().level),
                    active.candidates.get(active.candidateIndex))) {
                active.failure = worldBorderFailure(active.child);
                return;
            }
            active.ownership.requireUndisturbed(requireMinecraft());
            active.ownership.selectOwnedSlot(requireMinecraft());
            AimCandidate candidate = active.candidates.get(active.candidateIndex);
            active.ownership.turnToward(requireMinecraft(), candidate.point());
            if (active.ownership.aligned(requireMinecraft(), candidate.point())
                    && !actualHitMatches(requireMinecraft(), active.child, candidate, false)) {
                active.candidateIndex++;
                if (active.candidateIndex >= active.candidates.size()) {
                    active.failure = failure("AIM_RAYCAST_UNAVAILABLE",
                            RoutineFailure.Category.PRECONDITION,
                            RoutineFailure.Recovery.REPLAN, Map.of("aim_aligned", true),
                            Map.of("aim_aligned", false));
                }
            }
        } catch (RuntimeException | LinkageError drift) {
            active.failure = failure("PLAN_CONTROL_OWNERSHIP_LOST",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.USER,
                    Map.of("control_ownership", true), Map.of("control_ownership", false));
        }
    }

    @Override
    public ApplyBlockPlanPreparationEvidence preparationEvidence(
            ApplyBlockPlanPreparationAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requirePreparation(attempt);
        var session = requireSession();
        Optional<BlockStateFingerprint> live = currentLiveState(
                active.child.target(), session.clientTick());
        if (active.failure == null && !universalSafetyClear(minecraft, active.plan)) {
            active.failure = safetyFailure();
        }
        boolean stagingSynchronized = active.failure == null
                && refreshStagingSynchronization(active);
        boolean stand = active.plan.standBaseline != null
                && active.plan.standBaseline.matches(
                        Objects.requireNonNull(minecraft.player));
        boolean selected = stagingSynchronized && (active.child.requiredItemId().isEmpty()
                || active.ownership.ownedSlotSelected(minecraft));
        boolean aligned = false;
        boolean replaceable = false;
        if (active.failure == null && active.candidateIndex < active.candidates.size()) {
            try {
                if (!candidateWithinWorldBorder(
                        Objects.requireNonNull(minecraft.level),
                        active.candidates.get(active.candidateIndex))) {
                    active.failure = worldBorderFailure(active.child);
                }
                if (active.failure != null) {
                    return new ApplyBlockPlanPreparationEvidence(
                            attempt.attemptId(), session.clientTick(), memory.revision(), live,
                            false, stand, false, selected, active.failure);
                }
                active.ownership.requireUndisturbed(minecraft);
                var candidate = active.candidates.get(active.candidateIndex);
                aligned = active.ownership.aligned(minecraft, candidate.point())
                        && actualHitMatches(minecraft, active.child, candidate, true);
                var level = Objects.requireNonNull(minecraft.level);
                replaceable = level.getBlockState(blockPos(active.child.target())).canBeReplaced();
            } catch (RuntimeException | LinkageError drift) {
                active.failure = failure("PLAN_CONTROL_OWNERSHIP_LOST",
                        RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.USER,
                        Map.of("control_ownership", true), Map.of("control_ownership", false));
            }
        }
        return new ApplyBlockPlanPreparationEvidence(
                attempt.attemptId(), session.clientTick(), memory.revision(), live,
                replaceable, stand, aligned, selected, active.failure);
    }

    @Override
    public void releasePreparation(ApplyBlockPlanPreparationAttempt attempt) {
        assertClientThread();
        Objects.requireNonNull(attempt, "attempt");
        var active = preparations.get(attempt);
        if (active == null) {
            return;
        }
        if (!active.transferred) {
            active.ownership.close(requireMinecraft());
        }
        preparations.remove(attempt);
    }

    @Override
    public ApplyBlockPlanActionAttempt dispatchPrepared(
            ApplyBlockPlanRequest request,
            ApplyBlockPlanChildAction child,
            ApplyBlockPlanPreparationAttempt preparation,
            long leaseExpiresAtClientTick) {
        var minecraft = assertClientThread();
        var prepared = requirePreparation(preparation);
        var session = requireSession();
        if (prepared.transferred || prepared.request != request || !prepared.child.equals(child)
                || leaseExpiresAtClientTick <= session.clientTick()) {
            throw new IllegalStateException("preparation cannot be transferred to this action");
        }
        if (!universalSafetyClear(minecraft, prepared.plan)) {
            throw new ConstructionSafetyChangedException();
        }
        if (!refreshStagingSynchronization(prepared)) {
            if (prepared.failure != null) {
                throw new IllegalStateException("construction staging synchronization failed");
            }
            throw new IllegalStateException("construction staging synchronization is pending");
        }
        prepared.ownership.requireUndisturbed(minecraft);
        var candidate = prepared.candidates.get(prepared.candidateIndex);
        if (!candidateWithinWorldBorder(
                Objects.requireNonNull(minecraft.level), candidate)) {
            throw new IllegalStateException("child target left the current world border");
        }
        if (!actualHitMatches(minecraft, child, candidate, true)) {
            throw new IllegalStateException("actual raycast changed before dispatch");
        }
        if (child.stage() == ApplyBlockPlanChildStage.BREAK) {
            var level = Objects.requireNonNull(minecraft.level);
            var target = blockPos(child.target());
            SafeBreakSourcePolicy.requireLiveState(
                    level.getBlockState(target), level.getBlockEntity(target) != null);
        } else {
            var level = Objects.requireNonNull(minecraft.level);
            var support = candidate.hitBlock();
            if (child.supportWitness().isPresent()) {
                requireCurrentSupportWitness(level, prepared.plan, child, candidate);
            } else {
                SafePlacementSupportPolicy.requireLiveState(
                        level.getBlockState(support), level.getBlockEntity(support) != null);
            }
        }
        var live = currentLiveState(child.target(), session.clientTick());
        if (live.filter(child.expectedBefore()::equals).isEmpty()) {
            throw new IllegalStateException("child precondition changed before dispatch");
        }

        var actionAttempt = new ApplyBlockPlanActionAttempt(
                UUID.randomUUID(), child.stepIndex(), session.clientTick(),
                memory.revision(), leaseExpiresAtClientTick);
        var recon = reconciliations.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level), session.worldSessionId());
        var action = new ActionState(request, child, prepared.plan,
                prepared.ownership, candidate, recon);
        actions.put(actionAttempt, action);
        prepared.transferred = true;
        try {
            if (child.stage() == ApplyBlockPlanChildStage.BREAK) {
                dispatchBreak(minecraft, actionAttempt, action);
            } else {
                dispatchPlace(minecraft, actionAttempt, action);
            }
            return actionAttempt;
        } catch (RuntimeException | LinkageError failure) {
            actions.remove(actionAttempt);
            closeAction(action, failure);
            throw failure;
        }
    }

    @Override
    public void maintainAction(ApplyBlockPlanActionAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requireAction(attempt);
        if (active.failure != null) {
            return;
        }
        var session = requireSession();
        if (session.clientTick() >= attempt.leaseExpiresAtClientTick()) {
            active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                    RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            stopAttack(active);
            return;
        }
        detectActionSafetyFailure(active);
        if (active.failure != null) {
            stopAttack(active);
            return;
        }
        detectReconciliationFailure(active, session);
        detectWorldBorderFailure(active);
        detectStandFailure(active);
        if (active.failure != null) {
            stopAttack(active);
            return;
        }
        try {
            active.ownership.requireUndisturbed(minecraft);
            if (active.child.stage() == ApplyBlockPlanChildStage.BREAK) {
                active.prediction.captureIssuedPredictions();
                var confirmation = active.prediction.confirmation(
                        state -> active.child.expectedAfter().equals(fingerprint(state)));
                var level = Objects.requireNonNull(minecraft.level);
                var target = blockPos(active.child.target());
                var liveState = level.getBlockState(target);
                var local = fingerprint(liveState);
                if (confirmation.postconditionObserved()
                        || active.child.expectedAfter().equals(local)) {
                    stopAttack(active);
                    return;
                }
                var sourceFailure = breakHeartbeatSourceFailure(
                        liveState, level.getBlockEntity(target) != null,
                        active.child.expectedBefore());
                if (sourceFailure != null) {
                    active.failure = sourceFailure;
                    stopAttack(active);
                    return;
                }
                if (!actualHitMatches(minecraft, active.child, active.candidate, true)) {
                    active.failure = failure("BLOCK_TARGET_CHANGED",
                            RoutineFailure.Category.DIVERGENCE,
                            RoutineFailure.Recovery.REPLAN,
                            stateMap(active.child.expectedBefore()), stateMap(local));
                    stopAttack(active);
                    return;
                }
                if (active.attackLease == null || !active.attackLease.heartbeat(
                        System.nanoTime(), leaseHorizon(
                                session.clientTick(), attempt.leaseExpiresAtClientTick()))) {
                    active.failure = failure("ACTION_LEASE_EXPIRED",
                            RoutineFailure.Category.TRANSIENT,
                            RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
                    stopAttack(active);
                }
            }
        } catch (RuntimeException | LinkageError drift) {
            active.failure = failure("PLAN_CONTROL_OWNERSHIP_LOST",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.USER,
                    Map.of("control_ownership", true), Map.of("control_ownership", false));
            stopAttack(active);
        }
    }

    @Override
    public ApplyBlockPlanActionEvidence actionEvidence(ApplyBlockPlanActionAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requireAction(attempt);
        var session = requireSession();
        detectActionSafetyFailure(active);
        detectReconciliationFailure(active, session);
        detectWorldBorderFailure(active);
        detectStandFailure(active);
        if (active.failure != null) {
            return new ApplyBlockPlanActionEvidence(
                    attempt.attemptId(), session.clientTick(), memory.revision(),
                    false, false, Optional.empty(),
                    Map.of("action_preconditions_current", false), active.failure);
        }
        if (active.prediction != null) {
            active.prediction.captureIssuedPredictions();
        }
        var confirmation = active.prediction == null ? null : active.prediction.confirmation(
                state -> active.child.expectedAfter().equals(fingerprint(state)));
        boolean acknowledged = false;
        boolean serverStateExact = false;
        Optional<BlockStateFingerprint> serverState = Optional.empty();
        if (confirmation != null) {
            Integer required = confirmation.stateRequiredSequence() != null
                    ? confirmation.stateRequiredSequence() : confirmation.issuedSequence();
            acknowledged = required != null && confirmation.acknowledgedSequence() != null
                    && Integer.compareUnsigned(confirmation.acknowledgedSequence(), required) >= 0;
            serverState = Optional.ofNullable(confirmation.serverState())
                    .map(MinecraftApplyBlockPlanPort::fingerprint);
            serverStateExact = confirmation.serverConfirmed()
                    && serverState.filter(active.child.expectedAfter()::equals).isPresent();
            if (confirmation.status()
                    == ClientPredictionSignals.ConfirmationStatus.SERVER_STATE_MISMATCH) {
                active.failure = failure("POSTCONDITION_MISMATCH",
                        RoutineFailure.Category.DIVERGENCE,
                        RoutineFailure.Recovery.REPLAN,
                        stateMap(active.child.expectedAfter()),
                        serverState.map(MinecraftApplyBlockPlanPort::stateMap).orElse(Map.of()));
            } else if (confirmation.status()
                    == ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE) {
                active.failure = failure("PREDICTION_BRIDGE_INCOMPATIBLE",
                        RoutineFailure.Category.EXTERNAL,
                        RoutineFailure.Recovery.USER, Map.of(), Map.of());
            }
        }
        boolean inventoryConfirmed = active.child.stage() != ApplyBlockPlanChildStage.PLACE
                || refreshInventorySynchronization(active.plan,
                        reconciliations.bindAndSnapshot(
                                Objects.requireNonNull(minecraft.level), session.worldSessionId()),
                        Objects.requireNonNull(minecraft.player));
        if (active.child.stage() == ApplyBlockPlanChildStage.PLACE
                && active.plan.inventoryMismatch) {
            active.failure = failure("INVENTORY_POSTCONDITION_MISMATCH",
                    RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("server_inventory_sync", true),
                    Map.of("server_inventory_sync", false));
        }
        if (serverStateExact && !active.memoryRecorded) {
            if (!rememberConfirmation(active, session)) {
                active.failure = failure("CONFIRMED_STATE_ALREADY_CHANGED",
                        RoutineFailure.Category.DIVERGENCE,
                        RoutineFailure.Recovery.REPLAN,
                        stateMap(active.child.expectedAfter()),
                        currentAcceptedState(active.child.target())
                                .map(MinecraftApplyBlockPlanPort::stateMap)
                                .orElse(Map.of("currentness", "unknown")));
            } else {
                active.memoryRecorded = true;
            }
        }
        if (serverStateExact) {
            stopAttack(active);
        }
        if (serverStateExact && inventoryConfirmed
                && active.child.stage() == ApplyBlockPlanChildStage.PLACE) {
            active.plan.confirmedEntries.add(active.child.entryId());
        }
        var basis = new LinkedHashMap<String, Object>();
        basis.put("server_block_state_exact", serverStateExact);
        basis.put("server_inventory_synchronized", inventoryConfirmed);
        basis.put("prediction_acknowledged", acknowledged);
        if (confirmation != null) {
            basis.put("prediction_status", confirmation.status().name().toLowerCase());
        }
        return new ApplyBlockPlanActionEvidence(
                attempt.attemptId(), session.clientTick(), memory.revision(), acknowledged,
                serverStateExact && inventoryConfirmed, serverState, basis, active.failure);
    }

    @Override
    public void releaseAction(ApplyBlockPlanActionAttempt attempt) {
        assertClientThread();
        Objects.requireNonNull(attempt, "attempt");
        var active = actions.get(attempt);
        if (active != null) {
            closeAction(active, null);
            actions.remove(attempt);
        }
    }

    @Override
    public void retire(ApplyBlockPlanRequest request) {
        assertClientThread();
        Objects.requireNonNull(request, "request");
        for (var entry : new ArrayList<>(preparations.entrySet())) {
            if (entry.getValue().request == request) {
                preparations.remove(entry.getKey());
                if (!entry.getValue().transferred) {
                    closeOwnershipBestEffort(entry.getValue().ownership);
                }
            }
        }
        for (var entry : new ArrayList<>(actions.entrySet())) {
            if (entry.getValue().request == request) {
                actions.remove(entry.getKey());
                closeActionBestEffort(entry.getValue());
            }
        }
        plans.remove(request);
    }

    /** Releases all plan-owned state on disconnect, level replacement, and client shutdown. */
    public void clearSession() {
        var minecraft = assertClientThread();
        for (var active : new ArrayList<>(actions.values())) {
            closeActionBestEffort(active);
        }
        for (var active : new ArrayList<>(preparations.values())) {
            if (!active.transferred) {
                closeOwnershipBestEffort(active.ownership);
            }
        }
        actions.clear();
        preparations.clear();
        plans.clear();
        // Prediction channels are shared by every block-action port and are owned by the
        // ClientLevel lifecycle mixin/runtime fence. Closing the level here during world-login
        // admission would remove the bridge after its constructor hook had already bound it.
        reconciliations.closeLevel(minecraft.level);
    }

    /** Captures delayed vanilla attack predictions from {@code ClientTickEvent.Post}. */
    public void captureIssuedPredictions() {
        assertClientThread();
        for (var active : actions.values()) {
            if (active.prediction != null) {
                active.prediction.captureIssuedPredictions();
            }
        }
    }

    private void dispatchBreak(
            Minecraft minecraft, ApplyBlockPlanActionAttempt attempt, ActionState active) {
        if (!candidateWithinWorldBorder(
                Objects.requireNonNull(minecraft.level), active.candidate)) {
            throw new IllegalStateException("break target left the current world border");
        }
        var target = blockPos(active.child.target());
        var prediction = predictions.begin(
                Objects.requireNonNull(minecraft.level), target, requireSession().clientTick());
        prediction.sequenceBeforePrediction();
        active.prediction = prediction;
        active.attackLease = AttackInputLease.acquire(
                minecraft, System.nanoTime(), leaseHorizon(
                        requireSession().clientTick(), attempt.leaseExpiresAtClientTick()));
    }

    private void dispatchPlace(
            Minecraft minecraft, ApplyBlockPlanActionAttempt attempt, ActionState active) {
        var player = Objects.requireNonNull(minecraft.player);
        var level = Objects.requireNonNull(minecraft.level);
        if (!candidateWithinWorldBorder(level, active.candidate)) {
            throw new IllegalStateException("placement target left the current world border");
        }
        var hit = actualBlockHit(minecraft, active.candidate)
                .orElseThrow(() -> new IllegalStateException("placement raycast is no longer exact"));
        var stack = player.getMainHandItem();
        String required = active.child.requiredItemId().orElseThrow();
        if (!required.equals(registryItemId(stack)) || !eligibleBlockStack(stack)) {
            throw new IllegalStateException("selected placement stack is unsupported");
        }
        var item = (BlockItem) stack.getItem();
        var context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
        if (context == null || !context.canPlace()
                || !context.getClickedPos().equals(blockPos(active.child.target()))) {
            throw new IllegalStateException("actual placement context changed");
        }
        BlockState predicted = ((BlockItemPlacementInvoker) (Object) item)
                .mcmcp$invokeGetPlacementState(context);
        if (predicted == null || multiCellBlock(predicted)
                || !active.child.expectedAfter().equals(fingerprint(predicted))) {
            throw new IllegalStateException("exact placement state does not match the plan");
        }
        var support = hit.getBlockPos();
        boolean constructionWitness = active.child.supportWitness().isPresent();
        if (constructionWitness) {
            SafeConstructionBlockPolicy.requireLiveState(
                    predicted, level.getBlockEntity(context.getClickedPos()) != null);
            requireCurrentSupportWitness(level, active.plan, active.child, active.candidate);
        } else {
            SafePlacementSupportPolicy.requireLiveState(
                    level.getBlockState(support), level.getBlockEntity(support) != null);
        }

        active.inventoryCountBefore = eligibleInventoryCount(player, required);
        active.inventoryItemId = required;
        active.plan.beginInventoryWait(
                active.reconciliationAtDispatch.selectedSlotInventoryRevision(),
                required, active.inventoryCountBefore,
                player.getInventory().getSelectedSlot());
        var prediction = predictions.begin(
                level, context.getClickedPos(), requireSession().clientTick());
        int before = prediction.sequenceBeforePrediction();
        active.prediction = prediction;
        var result = constructionWitness
                ? dispatchConstructionUseIfAllowed(
                        level, support,
                        () -> Objects.requireNonNull(minecraft.gameMode)
                                .useItemOn(player, InteractionHand.MAIN_HAND, hit))
                : SafePlacementSupportPolicy.dispatchUseIfAllowed(
                        level.getBlockState(support), level.getBlockEntity(support) != null,
                        () -> Objects.requireNonNull(minecraft.gameMode)
                                .useItemOn(player, InteractionHand.MAIN_HAND, hit));
        int after = prediction.captureIssuedPredictions();
        if (after != before + 1) {
            throw new ClientPredictionSignals.PredictionBridgeException(
                    "place action did not issue exactly one prediction");
        }
        if (!result.consumesAction()) {
            active.failure = failure("PLACE_PACKET_REJECTED_LOCALLY",
                    RoutineFailure.Category.DIVERGENCE, RoutineFailure.Recovery.REPLAN,
                    Map.of("consumes_action", true), Map.of("consumes_action", false));
        }
    }

    private List<BlockPos> observationPositions(
            ApplyBlockPlanRequest request,
            ApplyBlockPlanChildAction ownedChild) {
        var positions = new LinkedHashSet<>(planObservationPositions(request, ownedChild));
        if (positions.size() > MinecraftObservationService.MAX_EXPLICIT_POSITIONS) {
            throw new IllegalArgumentException("bounded plan observation exceeds the live limit");
        }
        return List.copyOf(positions);
    }

    /** Pure policy seam: ownership narrows LIVE sampling to the only child core reconciles. */
    static Set<BlockPos> planObservationPositions(
            ApplyBlockPlanRequest request, ApplyBlockPlanChildAction ownedChild) {
        Objects.requireNonNull(request, "request");
        var positions = new LinkedHashSet<BlockPos>();
        if (ownedChild != null) {
            BlockPos target = blockPos(ownedChild.target());
            positions.add(target);
            if (ownedChild.stage() == ApplyBlockPlanChildStage.PLACE) {
                if (ownedChild.supportWitness().isPresent()) {
                    positions.add(blockPos(ownedChild.supportWitness().orElseThrow().support()));
                } else {
                    for (Direction face : Direction.values()) {
                        positions.add(target.relative(face.getOpposite()));
                    }
                }
            }
            return Set.copyOf(positions);
        }
        for (var step : request.steps()) {
            BlockPos target = blockPos(step.target());
            positions.add(target);
            if (step.operation() == ApplyBlockPlanOperation.PLACE
                    || step.operation() == ApplyBlockPlanOperation.REPLACE) {
                if (step.supportWitness().isPresent()) {
                    positions.add(blockPos(step.supportWitness().orElseThrow().support()));
                } else {
                    for (Direction face : Direction.values()) {
                        positions.add(target.relative(face.getOpposite()));
                    }
                }
            }
        }
        return Set.copyOf(positions);
    }

    private List<AimCandidate> breakCandidates(
            ClientLevel level, LocalPlayer player, BlockTarget blockTarget,
            Map<BlockPos, BlockSample> samples) {
        BlockPos target = blockPos(blockTarget);
        var sample = samples.get(target);
        if (!withinWorldBorder(level, target)
                || !currentReachable(sample) || multiCellBlock(level.getBlockState(target))) {
            return List.of();
        }
        return candidatesForVisibleFaces(level, player, target, target, sample.visibleFaces());
    }

    private List<AimCandidate> placeCandidates(
            ClientLevel level,
            LocalPlayer player,
            ApplyBlockPlanStep step,
            Map<BlockPos, BlockSample> samples,
            PlanState plan) {
        BlockPos target = blockPos(step.target());
        if (!withinWorldBorder(level, target) || !currentReachable(samples.get(target))) {
            return List.of();
        }
        if (step.supportWitness().isPresent()) {
            PlacementSupportWitness witness = step.supportWitness().orElseThrow();
            Direction clickedFace = Direction.byName(witness.clickedFace());
            BlockPos support = blockPos(witness.support());
            if (clickedFace == null
                    || !support.relative(clickedFace).equals(target)
                    || !supportWitnessCurrent(
                            level, samples.get(support), witness, plan, clickedFace)) {
                return List.of();
            }
            return List.copyOf(candidatesForFace(
                    level, player, support, target, clickedFace));
        }
        var result = new ArrayList<AimCandidate>();
        for (Direction clickedFace : Direction.values()) {
            BlockPos support = target.relative(clickedFace.getOpposite());
            var sample = samples.get(support);
            if (!withinWorldBorder(level, support) || !currentReachable(sample)
                    || !sample.visibleFaces().contains(clickedFace.getSerializedName())
                    || !SafePlacementSupportPolicy.allowsLiveState(
                            level.getBlockState(support),
                            level.getBlockEntity(support) != null)) {
                continue;
            }
            result.addAll(candidatesForFace(level, player, support, target, clickedFace));
        }
        return List.copyOf(result);
    }

    private boolean supportWitnessCurrent(
            ClientLevel level,
            BlockSample sample,
            PlacementSupportWitness witness,
            PlanState plan,
            Direction clickedFace) {
        if (!currentReachable(sample)
                || currentFingerprint(sample).filter(witness.expectedState()::equals).isEmpty()) {
            return false;
        }
        if (witness.confirmedDependencyEntryId().isPresent()) {
            String dependency = witness.confirmedDependencyEntryId().orElseThrow();
            if (!plan.confirmedEntries.contains(dependency)) {
                return false;
            }
        } else if (!sample.visibleFaces().contains(clickedFace.getSerializedName())) {
            return false;
        }
        BlockPos support = blockPos(witness.support());
        BlockState live = level.getBlockState(support);
        return witness.expectedState().equals(fingerprint(live))
                && allowsSafePlacementSupport(live, level.getBlockEntity(support) != null);
    }

    private void requireCurrentSupportWitness(
            ClientLevel level,
            PlanState plan,
            ApplyBlockPlanChildAction child,
            AimCandidate candidate) {
        PlacementSupportWitness witness = child.supportWitness().orElseThrow();
        Direction clickedFace = Direction.byName(witness.clickedFace());
        BlockPos support = blockPos(witness.support());
        if (clickedFace == null
                || !candidate.hitBlock().equals(support)
                || candidate.face() != clickedFace
                || !support.relative(clickedFace).equals(candidate.target())
                || witness.confirmedDependencyEntryId().isPresent()
                        && !plan.confirmedEntries.contains(
                                witness.confirmedDependencyEntryId().orElseThrow())) {
            throw new SafeConstructionBlockPolicy.UnsafeConstructionBlockException();
        }
        BlockState live = level.getBlockState(support);
        if (!witness.expectedState().equals(fingerprint(live))
                || !allowsSafePlacementSupport(
                        live, level.getBlockEntity(support) != null)) {
            throw new SafeConstructionBlockPolicy.UnsafeConstructionBlockException();
        }
    }

    private List<AimCandidate> exactPlacementCandidates(
            Minecraft minecraft,
            ApplyBlockPlanChildAction child,
            List<AimCandidate> candidates) {
        var player = Objects.requireNonNull(minecraft.player);
        ItemStack stack = player.getMainHandItem();
        String required = child.requiredItemId().orElseThrow();
        if (!required.equals(registryItemId(stack)) || !eligibleBlockStack(stack)) {
            return List.of();
        }
        var item = (BlockItem) stack.getItem();
        var exact = new ArrayList<AimCandidate>();
        for (AimCandidate candidate : candidates) {
            var hit = new BlockHitResult(
                    candidate.point(), candidate.face(), candidate.hitBlock(), false);
            var context = item.updatePlacementContext(new BlockPlaceContext(
                    new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
            if (context == null || !context.canPlace()
                    || !context.getClickedPos().equals(blockPos(child.target()))) {
                continue;
            }
            BlockState predicted = ((BlockItemPlacementInvoker) (Object) item)
                    .mcmcp$invokeGetPlacementState(context);
            if (predicted == null || multiCellBlock(predicted)
                    || !child.expectedAfter().equals(fingerprint(predicted))) {
                continue;
            }
            if (child.supportWitness().isPresent()
                    && !SafeConstructionBlockPolicy.allowsLiveState(predicted, false)) {
                continue;
            }
            exact.add(candidate);
        }
        return List.copyOf(exact);
    }

    private static boolean allowsSafePlacementSupport(
            BlockState state, boolean liveBlockEntityPresent) {
        return SafePlacementSupportPolicy.allowsLiveState(state, liveBlockEntityPresent)
                || SafeConstructionBlockPolicy.allowsLiveState(
                        state, liveBlockEntityPresent);
    }

    private static <T> T dispatchConstructionUseIfAllowed(
            ClientLevel level, BlockPos support, java.util.function.Supplier<T> dispatch) {
        Objects.requireNonNull(dispatch, "dispatch");
        BlockState state = level.getBlockState(support);
        if (!allowsSafePlacementSupport(state, level.getBlockEntity(support) != null)) {
            throw new SafeConstructionBlockPolicy.UnsafeConstructionBlockException();
        }
        return dispatch.get();
    }

    private List<AimCandidate> candidatesForVisibleFaces(
            ClientLevel level, LocalPlayer player, BlockPos hitBlock, BlockPos target,
            List<String> visibleFaces) {
        var result = new ArrayList<AimCandidate>();
        for (String name : visibleFaces) {
            Direction face = Direction.byName(name);
            if (face != null) {
                result.addAll(candidatesForFace(level, player, hitBlock, target, face));
            }
        }
        return List.copyOf(result);
    }

    private List<AimCandidate> candidatesForFace(
            ClientLevel level, LocalPlayer player, BlockPos hitBlock, BlockPos target,
            Direction face) {
        var shape = level.getBlockState(hitBlock)
                .getShape(level, hitBlock, CollisionContext.of(player));
        List<AABB> boxes = shape.isEmpty()
                ? List.of(new AABB(hitBlock))
                : shape.toAabbs().stream().limit(MAX_SHAPE_BOXES)
                        .map(box -> box.move(hitBlock)).toList();
        var result = new ArrayList<AimCandidate>();
        for (AABB box : boxes) {
            Vec3 point = faceCenter(box, face);
            if (player.getEyePosition().distanceToSqr(point)
                    > square(player.blockInteractionRange() + 0.25D)) {
                continue;
            }
            Rotation desired = rotationTo(player.getEyePosition(), point);
            double displacement = Math.abs(Mth.wrapDegrees(
                    desired.yaw() - player.getYRot()))
                    + Math.abs(desired.pitch() - player.getXRot());
            if (displacement <= SafeConstructionBlocks.MAX_ONE_WAY_CAMERA_DEGREES) {
                result.add(new AimCandidate(hitBlock, face, point, target));
            }
        }
        return result;
    }

    private static Set<BlockPos> standCells(LocalPlayer player) {
        int feetY = Mth.floor(player.getY() + ROUTE_SURFACE_EPSILON);
        var feet = new BlockPos(Mth.floor(player.getX()), feetY, Mth.floor(player.getZ()));
        return Set.of(feet, feet.above(), feet.below());
    }

    private static int findEligibleInventorySlot(LocalPlayer player, String item) {
        var inventory = player.getInventory();
        return MinecraftSemanticActionPort.firstPreparingSlot(
                inventory.getSelectedSlot(),
                slot -> {
                    ItemStack stack = inventory.getItem(slot);
                    return !stack.isEmpty()
                            && item.equals(registryItemId(stack))
                            && eligibleBlockStack(stack);
                });
    }

    private static boolean stageIntoSelectedHotbar(
            Minecraft minecraft,
            LocalPlayer player,
            int sourceInventorySlot,
            String item) {
        return MinecraftSemanticActionPort.stageInventorySlotIntoSelectedHotbar(
                minecraft,
                player,
                sourceInventorySlot,
                stack -> !stack.isEmpty()
                        && item.equals(registryItemId(stack))
                        && eligibleBlockStack(stack));
    }

    private static int eligibleInventoryCount(LocalPlayer player, String item) {
        var inventory = player.getInventory();
        int limit = Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
        int count = 0;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && item.equals(registryItemId(stack))
                    && eligibleBlockStack(stack)) {
                count = saturatedInventoryCount(count, stack.getCount());
            }
        }
        return count;
    }

    private InventoryFacts inventoryFacts(LocalPlayer player, ApplyBlockPlanRequest request) {
        var required = new LinkedHashSet<String>();
        request.steps().forEach(step -> step.requiredItemId().ifPresent(required::add));
        var counts = new LinkedHashMap<String, Integer>();
        var hotbar = new LinkedHashSet<String>();
        var inventory = player.getInventory();
        int limit = Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String item = registryItemId(stack);
            if (required.contains(item) && eligibleBlockStack(stack)) {
                counts.merge(item, stack.getCount(),
                        MinecraftApplyBlockPlanPort::saturatedInventoryCount);
                // The legacy frame name is retained, but the set now means "selectable or safely
                // stageable by this adapter".  Shortage is therefore discovered before any SWAP.
                hotbar.add(item);
            }
        }
        return new InventoryFacts(Map.copyOf(counts), Set.copyOf(hotbar));
    }

    private boolean refreshInventorySynchronization(
            PlanState plan,
            ClientReconciliationSignals.Snapshot recon,
            LocalPlayer player) {
        if (!plan.inventoryPending) {
            return true;
        }
        if (!plan.sessionId.equals(recon.worldSessionId())) {
            plan.inventoryMismatch = true;
            return false;
        }
        if (recon.selectedSlotInventoryRevision() <= plan.inventoryRevisionBefore) {
            return false;
        }
        var inventory = player.getInventory();
        boolean ownedSlotHeld = inventory.getSelectedSlot() == plan.inventoryOwnedSlot;
        ItemStack selected = inventory.getItem(plan.inventoryOwnedSlot);
        boolean selectedStackCurrent = selected.isEmpty()
                || plan.inventoryItemId.equals(registryItemId(selected));
        InventoryConsumption consumption = inventoryConsumption(
                plan.inventoryCountBefore,
                eligibleInventoryCount(player, plan.inventoryItemId));
        // selectedSlotInventoryRevision is incremented only by an inbound packet which the
        // packet mixin mapped to the then-selected player slot. Read back that still-owned slot
        // instead of lastInventorySync, which later unrelated packets are allowed to overwrite.
        if (ownedSlotHeld && selectedStackCurrent
                && consumption == InventoryConsumption.CONFIRMED) {
            plan.inventoryPending = false;
            return true;
        }
        if (ownedSlotHeld && selectedStackCurrent
                && consumption == InventoryConsumption.PENDING) {
            // A delayed SWAP/container response may advance the same selected-slot revision
            // before the placement consumption response. Keep waiting for the exact -1 total.
            return false;
        }
        plan.inventoryMismatch = true;
        return false;
    }

    private boolean refreshStagingSynchronization(PreparationState active) {
        PendingStaging pending = active.plan.pendingStaging;
        if (pending == null) return true;
        var session = requireSession();
        var minecraft = requireMinecraft();
        var recon = reconciliations.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level), session.worldSessionId());
        if (recon.selectedSlotInventoryRevision() <= pending.revisionBefore()) {
            return false;
        }
        var player = Objects.requireNonNull(minecraft.player);
        ItemStack selected = player.getInventory().getSelectedItem();
        boolean selectedExact = !selected.isEmpty()
                && pending.item().equals(registryItemId(selected))
                && eligibleBlockStack(selected);
        boolean totalUnchanged = eligibleInventoryCount(player, pending.item())
                == pending.countBefore();
        if (selectedExact && totalUnchanged) {
            active.plan.pendingStaging = null;
            return true;
        }
        active.failure = failure("INVENTORY_STAGING_MISMATCH",
                RoutineFailure.Category.DIVERGENCE, RoutineFailure.Recovery.REPLAN,
                Map.of("server_inventory_sync", true),
                Map.of("server_inventory_sync", false));
        return false;
    }

    private void detectReconciliationFailure(
            ActionState active, WorldSessionTracker.Snapshot session) {
        if (active.failure != null) return;
        var level = requireMinecraft().level;
        if (level == null || !active.plan.sameSession(session)) {
            active.failure = failure("WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of());
            return;
        }
        var current = reconciliations.bindAndSnapshot(level, session.worldSessionId());
        if (!active.reconciliationAtDispatch.sameSession(current)) {
            active.failure = failure("WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    RoutineFailure.Recovery.NONE, Map.of(), Map.of());
        } else if (current.positionCorrectionRevision()
                > active.reconciliationAtDispatch.positionCorrectionRevision()) {
            active.failure = failure("SERVER_POSITION_CORRECTION",
                    RoutineFailure.Category.DIVERGENCE, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of("position_correction", true));
        } else if (current.rotationRevision()
                > active.reconciliationAtDispatch.rotationRevision()) {
            active.failure = failure("SERVER_ROTATION_CORRECTION",
                    RoutineFailure.Category.DIVERGENCE, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of("rotation_correction", true));
        } else if (current.motionRevision()
                > active.reconciliationAtDispatch.motionRevision()) {
            active.failure = failure("SERVER_MOTION_APPLIED",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of(), Map.of("server_motion", true));
        }
    }

    private void detectWorldBorderFailure(ActionState active) {
        var level = requireMinecraft().level;
        if (active.failure == null
                && level != null
                && !candidateWithinWorldBorder(level, active.candidate)) {
            active.failure = worldBorderFailure(active.child);
            stopAttack(active);
        }
    }

    private void detectStandFailure(ActionState active) {
        var player = requireMinecraft().player;
        if (active.failure != null) return;
        if (player == null || active.plan.standBaseline == null
                || !active.plan.standBaseline.matches(player)) {
            active.failure = failure("SAFE_STAND_CHANGED",
                    RoutineFailure.Category.SAFETY, RoutineFailure.Recovery.REPLAN,
                    Map.of("safe_stand", true), Map.of("safe_stand", false));
            stopAttack(active);
        }
    }

    private Optional<BlockStateFingerprint> currentLiveState(BlockTarget target, long tick) {
        var sample = observations.observeBlock(
                requireMinecraft(), tick,
                new BlockPos(target.x(), target.y(), target.z()), BlockSource.LIVE);
        return currentFingerprint(sample);
    }

    private boolean actualHitMatches(
            Minecraft minecraft,
            ApplyBlockPlanChildAction child,
            AimCandidate candidate,
            boolean requireRenderedHitForBreak) {
        var player = Objects.requireNonNull(minecraft.player);
        var level = Objects.requireNonNull(minecraft.level);
        if (!candidateWithinWorldBorder(level, candidate)) return false;
        var actual = actualBlockHit(minecraft, candidate);
        if (actual.isEmpty()) return false;
        if (!player.isWithinBlockInteractionRange(candidate.hitBlock(), 0.0D)
                || !player.isWithinBlockInteractionRange(candidate.target(), 0.0D)) {
            return false;
        }
        if (child.stage() == ApplyBlockPlanChildStage.BREAK && requireRenderedHitForBreak) {
            return minecraft.hitResult instanceof BlockHitResult rendered
                    && rendered.getType() == HitResult.Type.BLOCK
                    && rendered.getBlockPos().equals(candidate.hitBlock())
                    && rendered.getDirection() == candidate.face();
        }
        return true;
    }

    private Optional<BlockHitResult> actualBlockHit(
            Minecraft minecraft, AimCandidate candidate) {
        var player = Objects.requireNonNull(minecraft.player);
        HitResult result = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (!(result instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(candidate.hitBlock())
                || hit.getDirection() != candidate.face()) {
            return Optional.empty();
        }
        return Optional.of(hit);
    }

    private boolean visibleThreatClear(
            Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        return level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
    }

    /** Universal semantic/block-mutation gate; deliberately independent of OS focus/mouse grab. */
    private boolean universalSafetyClear(Minecraft minecraft, PlanState plan) {
        var session = sessionSupplier.get();
        var level = minecraft.level;
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        if (session == null || !session.worldReady()
                || level == null || player == null || gameMode == null
                || minecraft.getConnection() == null
                || !plan.sameSession(session)
                || !plan.dimension.equals(level.dimension().identifier().toString())) {
            return false;
        }
        boolean controlContextClear = !minecraft.isPaused()
                && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null
                && !player.isUsingItem()
                && plan.positionMatches(player)
                && gameMode.getPlayerMode() == GameType.SURVIVAL;
        boolean alive = player.isAlive() && !player.isDeadOrDying();
        boolean healthSafe = alive && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= plan.initialHealth
                && player.hurtTime == 0 && player.getRemainingFireTicks() <= 0;
        boolean screenClear = minecraft.gui.screen() == null
                && player.containerMenu == player.inventoryMenu;
        return controlContextClear && healthSafe && screenClear
                && visibleThreatClear(minecraft, player, level);
    }

    private void detectActionSafetyFailure(ActionState active) {
        if (active.failure != null) return;
        var minecraft = requireMinecraft();
        if (!universalSafetyClear(minecraft, active.plan)) {
            active.failure = safetyFailure();
            return;
        }
        try {
            active.ownership.requireUndisturbed(minecraft);
        } catch (RuntimeException | LinkageError drift) {
            active.failure = safetyFailure();
        }
    }

    private static RoutineFailure safetyFailure() {
        return failure("ACTION_SAFETY_CHANGED", RoutineFailure.Category.SAFETY,
                RoutineFailure.Recovery.USER,
                Map.of("universal_safety", true),
                Map.of("universal_safety", false));
    }

    private boolean rememberConfirmation(
            ActionState active, WorldSessionTracker.Snapshot session) {
        var minecraft = requireMinecraft();
        var level = minecraft.level;
        if (level == null) return false;
        BlockPos position = blockPos(active.child.target());
        if (!level.isLoaded(position)) return false;
        BlockState current = level.getBlockState(position);
        BlockStateFingerprint currentState = fingerprint(current);
        if (!active.child.expectedAfter().equals(currentState)) return false;
        return InteractionConfirmationRecorder.rememberIfCurrent(
                memory, session, active.child.target(), active.child.expectedAfter(),
                currentState, observedContext(level, position, current));
    }

    private Optional<BlockStateFingerprint> currentAcceptedState(BlockTarget target) {
        var level = requireMinecraft().level;
        BlockPos position = blockPos(target);
        if (level == null || !level.isLoaded(position)) return Optional.empty();
        return Optional.of(fingerprint(level.getBlockState(position)));
    }

    @SuppressWarnings("deprecation")
    private static ObservedContext observedContext(
            ClientLevel level, BlockPos position, BlockState state) {
        var fluid = state.getFluidState();
        var sturdy = new ArrayList<String>();
        for (Direction face : Direction.values()) {
            if (state.isFaceSturdy(level, position, face)) {
                sturdy.add(face.getSerializedName());
            }
        }
        return new ObservedContext(
                level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position),
                fluid.isEmpty() ? null
                        : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
                fluid.isEmpty() ? null : fluid.isSource(),
                fluid.isEmpty() ? null : fluid.getAmount(),
                state.canBeReplaced(), state.getCollisionShape(level, position).isEmpty(), sturdy);
    }

    static boolean eligibleBlockStack(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)
                || item instanceof BedItem || item instanceof DoubleHighBlockItem
                || item instanceof SolidBucketItem
                || !supportsPlacementItem(item)) {
            return false;
        }
        if (multiCellBlock(item.getBlock().defaultBlockState())) {
            return false;
        }
        // Phase 4 verifies only the complete BlockState.  A component patch may carry block
        // entity contents, names, loot, bees, or placement-state overrides that are outside that
        // contract, so the initial bounded executor accepts only the item's unmodified prototype.
        return stack.getComponentsPatch().isEmpty()
                && stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                        .isEmpty();
    }

    /**
     * V1 permits only audited vanilla one-cell placement implementations. Exact-class checks are
     * deliberate: a mod subclass may override placement hooks and mutate unplanned cells even when
     * its selected BlockState is exact.
     */
    public static boolean supportsPlacementItem(BlockItem item) {
        Objects.requireNonNull(item, "item");
        var identifier = BuiltInRegistries.ITEM.getKey(item);
        var registered = BuiltInRegistries.ITEM.get(identifier);
        if (!"minecraft".equals(identifier.getNamespace())
                || registered.isEmpty()
                || registered.orElseThrow().value() != item) {
            return false;
        }
        Class<?> type = item.getClass();
        return type == BlockItem.class
                || type == StandingAndWallBlockItem.class;
    }

    private static boolean multiCellBlock(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART);
    }

    private Optional<ApplyBlockPlanChildAction> ownedChild(ApplyBlockPlanRequest request) {
        for (var active : actions.values()) {
            if (active.request == request) return Optional.of(active.child);
        }
        for (var active : preparations.values()) {
            if (active.request == request) return Optional.of(active.child);
        }
        return Optional.empty();
    }

    static int saturatedInventoryCount(int left, int right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("inventory counts must be non-negative");
        }
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    static boolean exactInventoryConsumption(int before, int after) {
        return inventoryConsumption(before, after) == InventoryConsumption.CONFIRMED;
    }

    static InventoryConsumption inventoryConsumption(int before, int after) {
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("inventory counts must be non-negative");
        }
        if (before > 0 && after == before - 1) {
            return InventoryConsumption.CONFIRMED;
        }
        return after == before
                ? InventoryConsumption.PENDING : InventoryConsumption.MISMATCH;
    }

    /** Pure stationary-support policy; deliberately accepts no world/block state. */
    static boolean stationaryStandReady(
            boolean onGround, boolean passenger, double positionDriftSquared) {
        return onGround && !passenger
                && Double.isFinite(positionDriftSquared)
                && positionDriftSquared <= MAX_POSITION_DRIFT_SQUARED;
    }

    /** Fail-closed source check performed before every break-input heartbeat. */
    static RoutineFailure breakHeartbeatSourceFailure(
            BlockState liveState,
            boolean liveBlockEntityPresent,
            BlockStateFingerprint expectedBefore) {
        Objects.requireNonNull(liveState, "liveState");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        if (!SafeBreakSourcePolicy.allowsLiveState(liveState, liveBlockEntityPresent)) {
            return failure("UNSAFE_BREAK_SOURCE", RoutineFailure.Category.PRECONDITION,
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("safe_break_source", true),
                    Map.of("safe_break_source", false));
        }
        var actual = fingerprint(liveState);
        if (!expectedBefore.equals(actual)) {
            return failure("BLOCK_TARGET_CHANGED", RoutineFailure.Category.DIVERGENCE,
                    RoutineFailure.Recovery.REPLAN,
                    stateMap(expectedBefore), stateMap(actual));
        }
        return null;
    }

    private static boolean withinWorldBorder(ClientLevel level, BlockPos position) {
        return level.getWorldBorder().isWithinBounds(position);
    }

    private static boolean candidateWithinWorldBorder(
            ClientLevel level, AimCandidate candidate) {
        return withinWorldBorder(level, candidate.target())
                && withinWorldBorder(level, candidate.hitBlock());
    }

    private static RoutineFailure worldBorderFailure(ApplyBlockPlanChildAction child) {
        return failure("TARGET_OUTSIDE_WORLD_BORDER", RoutineFailure.Category.PRECONDITION,
                RoutineFailure.Recovery.REPLAN,
                Map.of("inside_world_border", true, "step_index", child.stepIndex()),
                Map.of("inside_world_border", false, "step_index", child.stepIndex()));
    }

    private static Optional<BlockStateFingerprint> currentFingerprint(BlockSample sample) {
        if (!current(sample)) return Optional.empty();
        var state = sample.observation().state();
        return Optional.of(new BlockStateFingerprint(state.block(), state.properties()));
    }

    private static boolean air(BlockStateFingerprint state) {
        return "minecraft:air".equals(state.blockId()) && state.properties().isEmpty();
    }

    private static boolean current(BlockSample sample) {
        return sample != null && sample.outcome() == BlockOutcome.CURRENT;
    }

    private static boolean currentReachable(BlockSample sample) {
        return current(sample) && sample.withinReach();
    }

    private static Rotation rotationTo(Vec3 eye, Vec3 point) {
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        return new Rotation(yaw, pitch);
    }

    private static Vec3 faceCenter(AABB box, Direction face) {
        double x = (box.minX + box.maxX) * 0.5D;
        double y = (box.minY + box.maxY) * 0.5D;
        double z = (box.minZ + box.maxZ) * 0.5D;
        return switch (face) {
            case DOWN -> new Vec3(x, box.minY, z);
            case UP -> new Vec3(x, box.maxY, z);
            case NORTH -> new Vec3(x, y, box.minZ);
            case SOUTH -> new Vec3(x, y, box.maxZ);
            case WEST -> new Vec3(box.minX, y, z);
            case EAST -> new Vec3(box.maxX, y, z);
        };
    }

    private static double square(double value) {
        return value * value;
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private static String registryItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static Map<String, Object> stateMap(BlockStateFingerprint state) {
        return Map.of("block", state.blockId(), "properties", state.properties());
    }

    private static RoutineFailure failure(
            String code,
            RoutineFailure.Category category,
            RoutineFailure.Recovery recovery,
            Map<String, Object> expected,
            Map<String, Object> observed) {
        return new RoutineFailure(category, code, false, recovery, RoutineFailure.Scope.STEP,
                1, expected, observed, Map.of(),
                List.of("player", "target", "inventory"),
                recovery == RoutineFailure.Recovery.USER);
    }

    private static Duration leaseHorizon(long currentTick, long expiresAtTick) {
        long remainingTicks = Math.max(1L, expiresAtTick - currentTick);
        return Duration.ofMillis(Math.min(2_000L, Math.multiplyExact(remainingTicks, 50L)));
    }

    private void stopAttack(ActionState active) {
        if (active.attackLease == null) return;
        var lease = active.attackLease;
        active.attackLease = null;
        lease.close();
    }

    private void closeAction(ActionState active, Throwable primary) {
        Throwable failure = primary;
        try {
            stopAttack(active);
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = combine(failure, closeFailure);
        }
        try {
            if (active.prediction != null) active.prediction.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = combine(failure, closeFailure);
        }
        try {
            active.ownership.close(requireMinecraft());
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = combine(failure, closeFailure);
        }
        if (primary == null && failure != null) rethrow(failure);
    }

    private void closeActionBestEffort(ActionState active) {
        try {
            closeAction(active, new IllegalStateException("best-effort close"));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void closeOwnershipBestEffort(ControlOwnership ownership) {
        try {
            ownership.close(requireMinecraft());
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static Throwable combine(Throwable primary, Throwable next) {
        if (primary == null) return next;
        primary.addSuppressed(next);
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
        throw new IllegalStateException(failure);
    }

    private PlanState requirePlan(
            ApplyBlockPlanRequest request, WorldSessionTracker.Snapshot session) {
        var state = plans.get(request);
        if (state == null || !state.sameSession(session)) {
            throw new IllegalStateException("block plan has no current observation state");
        }
        return state;
    }

    private PreparationState requirePreparation(ApplyBlockPlanPreparationAttempt attempt) {
        var state = preparations.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null) throw new IllegalStateException("preparation attempt is not active");
        return state;
    }

    private ActionState requireAction(ApplyBlockPlanActionAttempt attempt) {
        var state = actions.get(Objects.requireNonNull(attempt, "attempt"));
        if (state == null) throw new IllegalStateException("action attempt is not active");
        return state;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(minecraftSupplier.get(), "minecraft");
    }

    private Minecraft assertClientThread() {
        var minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("block plan adapter must run on the client thread");
        }
        return minecraft;
    }

    private WorldSessionTracker.Snapshot requireSession() {
        var session = Objects.requireNonNull(sessionSupplier.get(), "world session");
        if (!session.worldReady()) throw new IllegalStateException("world session is not ready");
        return session;
    }

    private static final class PlanState {
        private final UUID sessionId;
        private final String dimension;
        private final double initialX;
        private final double initialY;
        private final double initialZ;
        private final float initialHealth;
        private final Map<CandidateKey, List<AimCandidate>> candidates = new LinkedHashMap<>();
        private final Set<String> confirmedEntries = new LinkedHashSet<>();
        private StandBaseline standBaseline;
        private long lastFrameTick;
        private long lastObservationRevision;
        private boolean inventoryPending;
        private boolean inventoryMismatch;
        private long inventoryRevisionBefore;
        private String inventoryItemId;
        private int inventoryCountBefore;
        private int inventoryOwnedSlot;
        private PendingStaging pendingStaging;

        private PlanState(
                UUID sessionId, String dimension,
                double initialX, double initialY, double initialZ, float initialHealth) {
            this.sessionId = sessionId;
            this.dimension = dimension;
            this.initialX = initialX;
            this.initialY = initialY;
            this.initialZ = initialZ;
            this.initialHealth = initialHealth;
        }

        static PlanState capture(
                WorldSessionTracker.Snapshot session, LocalPlayer player) {
            return new PlanState(session.worldSessionId(), session.dimension(),
                    player.getX(), player.getY(), player.getZ(), player.getHealth());
        }

        boolean sameSession(WorldSessionTracker.Snapshot session) {
            return session != null && session.worldReady()
                    && sessionId.equals(session.worldSessionId())
                    && dimension.equals(session.dimension());
        }

        boolean positionMatches(LocalPlayer player) {
            return positionDriftSquared(player) <= MAX_POSITION_DRIFT_SQUARED;
        }

        double positionDriftSquared(LocalPlayer player) {
            double dx = player.getX() - initialX;
            double dy = player.getY() - initialY;
            double dz = player.getZ() - initialZ;
            return dx * dx + dy * dy + dz * dz;
        }

        void beginInventoryWait(long revision, String item, int count, int ownedSlot) {
            inventoryPending = true;
            inventoryMismatch = false;
            inventoryRevisionBefore = revision;
            inventoryItemId = item;
            inventoryCountBefore = count;
            inventoryOwnedSlot = ownedSlot;
        }
    }

    private static final class PreparationState {
        private final ApplyBlockPlanRequest request;
        private final ApplyBlockPlanChildAction child;
        private final PlanState plan;
        private final ControlOwnership ownership;
        private final List<AimCandidate> candidates;
        private int candidateIndex;
        private boolean transferred;
        private RoutineFailure failure;

        private PreparationState(
                ApplyBlockPlanRequest request,
                ApplyBlockPlanChildAction child,
                PlanState plan,
                ControlOwnership ownership,
                List<AimCandidate> candidates) {
            this.request = request;
            this.child = child;
            this.plan = plan;
            this.ownership = ownership;
            this.candidates = candidates;
        }
    }

    private static final class ActionState {
        private final ApplyBlockPlanRequest request;
        private final ApplyBlockPlanChildAction child;
        private final PlanState plan;
        private final ControlOwnership ownership;
        private final AimCandidate candidate;
        private final ClientReconciliationSignals.Snapshot reconciliationAtDispatch;
        private ClientPredictionSignals.PredictionAttempt prediction;
        private AttackInputLease attackLease;
        private String inventoryItemId;
        private int inventoryCountBefore;
        private RoutineFailure failure;
        private boolean memoryRecorded;

        private ActionState(
                ApplyBlockPlanRequest request,
                ApplyBlockPlanChildAction child,
                PlanState plan,
                ControlOwnership ownership,
                AimCandidate candidate,
                ClientReconciliationSignals.Snapshot reconciliationAtDispatch) {
            this.request = request;
            this.child = child;
            this.plan = plan;
            this.ownership = ownership;
            this.candidate = candidate;
            this.reconciliationAtDispatch = reconciliationAtDispatch;
        }
    }

    private static final class ControlOwnership {
        private final UUID owner;
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private final int ownedSlot;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private final ControlRestoration restoration = new ControlRestoration();

        private ControlOwnership(
                UUID owner, float yaw, float pitch, int originalSlot, int ownedSlot) {
            this.owner = owner;
            this.originalYaw = yaw;
            this.originalPitch = pitch;
            this.originalSlot = originalSlot;
            this.ownedSlot = ownedSlot;
            this.expectedYaw = yaw;
            this.expectedPitch = pitch;
            this.expectedSlot = originalSlot;
        }

        static ControlOwnership acquire(
                Minecraft minecraft, Optional<String> requiredItem, UUID owner) {
            var player = Objects.requireNonNull(minecraft.player);
            int original = player.getInventory().getSelectedSlot();
            int owned = requiredItem.map(item -> findEligibleHotbar(player, item))
                    .orElse(original);
            return new ControlOwnership(owner, player.getYRot(), player.getXRot(),
                    original, owned);
        }

        void requireUndisturbed(Minecraft minecraft) {
            if (restoration.started()) {
                throw new IllegalStateException("control ownership is closing");
            }
            var player = Objects.requireNonNull(minecraft.player);
            if (Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw))
                            > ROTATION_DRIFT_EPSILON
                    || Math.abs(player.getXRot() - expectedPitch) > ROTATION_DRIFT_EPSILON
                    || player.getInventory().getSelectedSlot() != expectedSlot) {
                throw new IllegalStateException("owned player controls changed externally");
            }
        }

        void selectOwnedSlot(Minecraft minecraft) {
            requireUndisturbed(minecraft);
            var inventory = Objects.requireNonNull(minecraft.player).getInventory();
            inventory.setSelectedSlot(ownedSlot);
            expectedSlot = ownedSlot;
        }

        boolean ownedSlotSelected(Minecraft minecraft) {
            return !restoration.started() && Objects.requireNonNull(minecraft.player)
                    .getInventory().getSelectedSlot() == ownedSlot;
        }

        void turnToward(Minecraft minecraft, Vec3 point) {
            requireUndisturbed(minecraft);
            var player = Objects.requireNonNull(minecraft.player);
            Rotation desired = rotationTo(player.getEyePosition(), point);
            float yawDelta = Mth.clamp(Mth.wrapDegrees(desired.yaw() - player.getYRot()),
                    -MAX_AIM_TURN_DEGREES_PER_TICK, MAX_AIM_TURN_DEGREES_PER_TICK);
            float pitchDelta = Mth.clamp(desired.pitch() - player.getXRot(),
                    -MAX_AIM_TURN_DEGREES_PER_TICK, MAX_AIM_TURN_DEGREES_PER_TICK);
            player.turn(yawDelta / 0.15D, pitchDelta / 0.15D);
            expectedYaw = player.getYRot();
            expectedPitch = player.getXRot();
        }

        boolean aligned(Minecraft minecraft, Vec3 point) {
            var player = Objects.requireNonNull(minecraft.player);
            Rotation desired = rotationTo(player.getEyePosition(), point);
            return Math.abs(Mth.wrapDegrees(desired.yaw() - player.getYRot()))
                            <= AIM_EPSILON_DEGREES
                    && Math.abs(desired.pitch() - player.getXRot()) <= AIM_EPSILON_DEGREES;
        }

        void close(Minecraft minecraft) {
            if (restoration.complete()) return;
            var player = minecraft.player;
            if (player == null) {
                restoration.markComplete();
                return;
            }
            restoration.restore(
                    () -> player.getInventory().setSelectedSlot(originalSlot),
                    () -> {
                    float yawDelta = Mth.wrapDegrees(originalYaw - player.getYRot());
                    float pitchDelta = originalPitch - player.getXRot();
                    player.turn(yawDelta / 0.15D, pitchDelta / 0.15D);
                    });
        }

        private static int findEligibleHotbar(LocalPlayer player, String item) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (!stack.isEmpty() && item.equals(registryItemId(stack))
                        && eligibleBlockStack(stack)) {
                    return slot;
                }
            }
            throw new IllegalStateException("required eligible block item is not in the hotbar");
        }
    }

    private record AimCandidate(
            BlockPos hitBlock, Direction face, Vec3 point, BlockPos target) {
        private AimCandidate {
            Objects.requireNonNull(hitBlock, "hitBlock");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(target, "target");
        }
    }

    private record CandidateKey(
            BlockTarget target, ApplyBlockPlanChildStage stage) {
        private CandidateKey {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(stage, "stage");
        }
    }

    private record StandBaseline(double x, double y, double z) {
        private StandBaseline {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("stand baseline must be finite");
            }
        }

        static StandBaseline capture(LocalPlayer player) {
            return new StandBaseline(player.getX(), player.getY(), player.getZ());
        }

        boolean matches(LocalPlayer player) {
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            return stationaryStandReady(
                    player.onGround(), player.isPassenger(),
                    dx * dx + dy * dy + dz * dz);
        }
    }

    private record Rotation(float yaw, float pitch) {
    }

    private record InventoryFacts(Map<String, Integer> counts, Set<String> hotbarItems) {
    }

    private record PendingStaging(String item, int countBefore, long revisionBefore) {
        private PendingStaging {
            Objects.requireNonNull(item, "item");
            if (countBefore < 1 || revisionBefore < 0L) {
                throw new IllegalArgumentException("invalid staging synchronization baseline");
            }
        }
    }

    enum InventoryConsumption { PENDING, CONFIRMED, MISMATCH }

    /** Independent, retryable restoration state for slot and camera ownership. */
    static final class ControlRestoration {
        private boolean started;
        private boolean slotRestored;
        private boolean rotationRestored;

        boolean started() {
            return started;
        }

        boolean complete() {
            return slotRestored && rotationRestored;
        }

        void markComplete() {
            started = true;
            slotRestored = true;
            rotationRestored = true;
        }

        void restore(Runnable restoreSlot, Runnable restoreRotation) {
            Objects.requireNonNull(restoreSlot, "restoreSlot");
            Objects.requireNonNull(restoreRotation, "restoreRotation");
            started = true;
            Throwable failure = null;
            if (!slotRestored) {
                try {
                    restoreSlot.run();
                    slotRestored = true;
                } catch (RuntimeException | LinkageError slotFailure) {
                    failure = slotFailure;
                }
            }
            if (!rotationRestored) {
                try {
                    restoreRotation.run();
                    rotationRestored = true;
                } catch (RuntimeException | LinkageError rotationFailure) {
                    failure = combine(failure, rotationFailure);
                }
            }
            if (failure != null) rethrow(failure);
        }
    }
}
