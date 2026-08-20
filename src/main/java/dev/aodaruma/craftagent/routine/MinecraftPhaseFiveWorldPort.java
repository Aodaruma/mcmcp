package dev.aodaruma.craftagent.routine;

import dev.aodaruma.craftagent.observation.BlockPosition;
import dev.aodaruma.craftagent.observation.MinecraftObservationService;
import dev.aodaruma.craftagent.observation.MinecraftObservationService.BlockOutcome;
import dev.aodaruma.craftagent.observation.MinecraftObservationService.BlockSample;
import dev.aodaruma.craftagent.observation.MinecraftObservationService.BlockSource;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.runtime.SleepSemanticSignals;
import dev.aodaruma.craftagent.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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
import java.util.function.Supplier;

/** Minecraft 26.2 adapter for the four non-GUI Phase 5 routines. */
public final class MinecraftPhaseFiveWorldPort implements PhaseFivePort {
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double NAVIGATION_TOLERANCE = 2.0D;
    private static final double SETTLED_VELOCITY_SQUARED = 0.03D * 0.03D;
    private static final int REQUIRED_SETTLE_TICKS = 10;
    private static final int COLLECTION_WAIT_TICKS = 200;
    private static final int SLEEP_ENTER_WAIT_TICKS = 100;
    private static final float MAX_TURN_PER_TICK = 8.0F;
    private static final float ROTATION_EPSILON = 0.25F;
    private static final float AIM_EPSILON = 0.20F;
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final WorldMemory memory;
    private final MinecraftObservationService observations;
    private final SemanticActionPort semanticActions;
    private final SleepSemanticSignals sleepSignals;
    private final Map<PhaseFiveRequest, PhaseFiveWorldSpec> prepared = new IdentityHashMap<>();
    private final Map<PhaseFiveAttempt, Active> attempts = new IdentityHashMap<>();

    public MinecraftPhaseFiveWorldPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            SemanticActionPort semanticActions) {
        this(minecraftSupplier, sessionSupplier, memory, observations,
                semanticActions, SleepSemanticSignals.global());
    }

    MinecraftPhaseFiveWorldPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            SemanticActionPort semanticActions,
            SleepSemanticSignals sleepSignals) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.semanticActions = Objects.requireNonNull(semanticActions, "semanticActions");
        this.sleepSignals = Objects.requireNonNull(sleepSignals, "sleepSignals");
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : session.clientTick();
        try {
            PhaseFiveWorldSpec spec = PhaseFiveWorldSpec.parse(request);
            RoutineFailure safety = preflightSafety(minecraft, session, request);
            if (safety != null) {
                return new PhaseFiveFrame(tick, memory.revision(), safety);
            }
            RoutineFailure targetFailure = validateCurrentPreflight(minecraft, session, spec);
            if (targetFailure != null) {
                return new PhaseFiveFrame(tick, memory.revision(), targetFailure);
            }
            prepared.put(request, spec);
            return new PhaseFiveFrame(tick, memory.revision(), null);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return new PhaseFiveFrame(tick, memory.revision(), failure(
                    RoutineFailure.Category.PRECONDITION, "INVALID_PHASE_FIVE_REQUEST",
                    RoutineFailure.Recovery.REPLAN, Map.of(),
                    Map.of("reason", safeMessage(invalid)), 0));
        }
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId,
            PhaseFiveRequest request,
            long hardDeadlineClientTick) {
        var minecraft = assertClientThread();
        var session = requireSession();
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(request, "request");
        if (hardDeadlineClientTick <= session.clientTick()) {
            throw new IllegalArgumentException("Phase 5 hard deadline has already expired");
        }
        var frame = observe(request);
        if (frame.failure() != null) {
            throw new IllegalStateException("Phase 5 preflight is not clear: " + frame.failure().code());
        }
        PhaseFiveWorldSpec spec = Objects.requireNonNull(prepared.get(request), "prepared spec");
        LocalPlayer player = Objects.requireNonNull(minecraft.player);
        BlockPos start = player.blockPosition();
        var startTarget = target(session.dimension(), start);
        if (!request.bounds().contains(startTarget)) {
            throw new IllegalArgumentException("start checkpoint is outside the declared bounds");
        }
        var dispatchBasis = new LinkedHashMap<String, Object>();
        dispatchBasis.put("world_session_id", session.worldSessionId().toString());
        dispatchBasis.put("dimension", session.dimension());
        dispatchBasis.put("scope", scopeBasis(request.kind()));
        var attempt = new PhaseFiveAttempt(
                routineId, request.kind(), session.clientTick(), memory.revision(),
                hardDeadlineClientTick, dispatchBasis);
        attempts.put(attempt, new Active(
                request, spec, session.worldSessionId(), session.dimension(), startTarget,
                player.getX(), player.getY(), player.getZ(), player.getHealth(),
                hardDeadlineClientTick));
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        var minecraft = assertClientThread();
        Active active = requireActive(attempt);
        if (active.terminal() || active.released) {
            return;
        }
        var session = sessionSupplier.get();
        if (session == null || !active.sameSession(session)) {
            active.fail(failure(RoutineFailure.Category.SAFETY, "WORLD_SESSION_CHANGED",
                    RoutineFailure.Recovery.NONE, Map.of("world_session_id", active.sessionId.toString()),
                    Map.of(), 1));
            return;
        }
        if (session.clientTick() >= attempt.hardDeadlineClientTick()) {
            active.fail(failure(RoutineFailure.Category.TRANSIENT, "PHASE_FIVE_TIMEOUT",
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("before_client_tick", attempt.hardDeadlineClientTick()),
                    Map.of("client_tick", session.clientTick()), 1));
            return;
        }
        RoutineFailure safety = liveSafety(minecraft, active);
        if (safety != null) {
            active.fail(safety);
            return;
        }
        if (!active.trackTravel(Objects.requireNonNull(minecraft.player),
                active.request.bounds().maxTravelBlocks())) {
            active.fail(failure(RoutineFailure.Category.SAFETY, "TRAVEL_BOUND_EXCEEDED",
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("max_travel_blocks", active.request.bounds().maxTravelBlocks()),
                    Map.of("travel_blocks", active.travelled), 1));
            return;
        }
        try {
            switch (active.request.kind()) {
                case "tend_crop_area" -> maintainCrop(minecraft, session, active);
                case "harvest_tree_area" -> maintainTree(minecraft, session, active);
                case "sleep_at_bed" -> maintainSleep(minecraft, session, active, attempt);
                case "survey_area" -> maintainSurvey(minecraft, session, active);
                default -> throw new IllegalStateException("world adapter received a GUI kind");
            }
        } catch (RuntimeException | LinkageError unexpected) {
            active.fail(failure(RoutineFailure.Category.EXTERNAL, "PHASE_FIVE_ADAPTER_FAILURE",
                    RoutineFailure.Recovery.USER, Map.of(),
                    Map.of("reason", safeMessage(unexpected)), 1));
        }
    }

    @Override
    public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
        assertClientThread();
        Active active = requireActive(attempt);
        var session = sessionSupplier.get();
        long tick = session == null ? attempt.issuedClientTick() : session.clientTick();
        Map<String, Object> basis = active.basis();
        if (active.failure != null) {
            return new PhaseFiveEvidence.Failed(
                    attempt.attemptId(), tick, memory.revision(), active.failure, basis);
        }
        if (active.inconclusive != null) {
            return new PhaseFiveEvidence.Inconclusive(
                    attempt.attemptId(), tick, memory.revision(), active.inconclusive,
                    active.inconclusiveReason, basis);
        }
        if (active.result != null) {
            return new PhaseFiveEvidence.ServerConfirmed(
                    attempt.attemptId(), tick, memory.revision(), active.result, basis);
        }
        return new PhaseFiveEvidence.Pending(
                attempt.attemptId(), tick, memory.revision(), basis);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        assertClientThread();
        Active active = requireActive(attempt);
        releaseActive(active, false);
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        assertClientThread();
        Objects.requireNonNull(request, "request");
        prepared.remove(request);
        attempts.entrySet().removeIf(entry -> {
            if (entry.getValue().request != request) {
                return false;
            }
            releaseActive(entry.getValue(), true);
            return true;
        });
    }

    /** Idempotent world-session fence used by logout, respawn, and client shutdown. */
    public void clearSession() {
        assertClientThread();
        for (Active active : List.copyOf(attempts.values())) {
            releaseActive(active, true);
        }
        attempts.clear();
        prepared.clear();
    }

    private void maintainCrop(
            Minecraft minecraft, WorldSessionTracker.Snapshot session, Active active) {
        var spec = (PhaseFiveWorldSpec.CropSpec) active.spec;
        switch (active.stage) {
            case SELECT -> {
                if (active.verifiedUnits >= spec.minimumHarvested()) {
                    completeCrop(active, spec);
                    return;
                }
                if (active.primaryIndex >= spec.plots().size()) {
                    if ("no_wait".equals(spec.waitPolicy())) {
                        active.inconclusive(PhaseFiveEvidence.Certainty.UNKNOWN,
                                "declared current plots did not satisfy the harvest minimum");
                    } else {
                        active.primaryIndex = 0;
                    }
                    return;
                }
                var plot = spec.plots().get(active.primaryIndex);
                if (!exactCurrent(minecraft, session, plot.support(), plot.expectedSupport())) {
                    active.fail(targetFailure("CROP_SUPPORT_NOT_CURRENT", plot.support(),
                            plot.expectedSupport()));
                    return;
                }
                Optional<BlockStateFingerprint> live = currentState(minecraft, session, plot.crop());
                if (live.isEmpty() || !spec.adapter().blockId().equals(live.orElseThrow().blockId())) {
                    active.fail(targetFailure("CROP_NOT_CURRENT", plot.crop(),
                            new BlockStateFingerprint(spec.adapter().blockId(), Map.of())));
                    return;
                }
                BlockState state = Objects.requireNonNull(minecraft.level)
                        .getBlockState(blockPos(plot.crop()));
                if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
                    active.primaryIndex++;
                    return;
                }
                active.expectedBefore = fingerprint(state);
                active.expectedAfter = fingerprint(state.getBlock().defaultBlockState());
                active.collectionItem = spec.adapter().harvestItem();
                active.collectionBefore = inventoryCount(
                        Objects.requireNonNull(minecraft.player), active.collectionItem);
                startNavigation(minecraft, session, active, plot.crop(), Stage.AIM_BREAK);
            }
            case NAVIGATE -> maintainNavigation(minecraft, session, active);
            case AIM_BREAK -> startBreak(minecraft, session, active,
                    spec.plots().get(active.primaryIndex).crop());
            case BREAK -> maintainMutation(active, true, Stage.AIM_PLACE);
            case AIM_PLACE -> {
                var plot = spec.plots().get(active.primaryIndex);
                startPlace(minecraft, session, active, plot.crop(), plot.support(),
                        spec.adapter().plantingItem(), active.expectedAfter);
            }
            case PLACE -> {
                if (maintainMutation(active, false, Stage.COLLECT)) {
                    active.collectDeadline = Math.min(active.hardDeadline,
                            session.clientTick() + COLLECTION_WAIT_TICKS);
                    active.effects.add(blockEffect(
                            "crop_replanted", spec.plots().get(active.primaryIndex).crop(),
                            active.expectedBefore, active.expectedAfter));
                }
            }
            case COLLECT -> {
                int after = inventoryCount(Objects.requireNonNull(minecraft.player), active.collectionItem);
                if (after > active.collectionBefore) {
                    active.verifiedUnits++;
                    active.collectionEvidence++;
                    active.primaryIndex++;
                    active.stage = Stage.SELECT;
                } else if (session.clientTick() >= active.collectDeadline) {
                    active.inconclusive(PhaseFiveEvidence.Certainty.AMBIGUOUS,
                            "harvested drop collection had no positive inventory evidence");
                }
            }
            default -> active.fail(internalStageFailure(active.stage));
        }
    }

    private void maintainTree(
            Minecraft minecraft, WorldSessionTracker.Snapshot session, Active active) {
        var spec = (PhaseFiveWorldSpec.TreeSpec) active.spec;
        switch (active.stage) {
            case SELECT -> {
                if (active.primaryIndex >= spec.trees().size()) {
                    completeTree(active, spec);
                    return;
                }
                var tree = spec.trees().get(active.primaryIndex);
                if (active.secondaryIndex == 0 && active.collectionItem == null) {
                    active.collectionItem = tree.adapter().logBlock();
                    active.collectionBefore = inventoryCount(
                            Objects.requireNonNull(minecraft.player), active.collectionItem);
                }
                if (active.secondaryIndex < tree.logs().size()) {
                    var log = tree.logs().get(active.secondaryIndex);
                    if (!exactCurrent(minecraft, session, log.position(), log.expectedState())) {
                        active.fail(targetFailure(
                                "DECLARED_LOG_NOT_CURRENT", log.position(), log.expectedState()));
                        return;
                    }
                    active.expectedBefore = log.expectedState();
                    startNavigation(minecraft, session, active, log.position(), Stage.AIM_BREAK);
                } else {
                    if (!treePlantingCellsCurrent(minecraft, session, tree)) {
                        active.fail(targetFailure("TREE_REPLANT_CELLS_NOT_CURRENT",
                                tree.support().position(), tree.support().expectedState()));
                        return;
                    }
                    active.expectedAfter = tree.expectedSapling();
                    startNavigation(minecraft, session, active,
                            tree.saplingPosition(), Stage.AIM_PLACE);
                }
            }
            case NAVIGATE -> maintainNavigation(minecraft, session, active);
            case AIM_BREAK -> {
                var tree = spec.trees().get(active.primaryIndex);
                startBreak(minecraft, session, active,
                        tree.logs().get(active.secondaryIndex).position());
            }
            case BREAK -> {
                if (maintainMutation(active, true, Stage.SELECT)) {
                    var tree = spec.trees().get(active.primaryIndex);
                    var log = tree.logs().get(active.secondaryIndex);
                    active.effects.add(blockEffect(
                            "declared_log_harvested", log.position(), log.expectedState(), AIR));
                    active.verifiedUnits++;
                    active.secondaryIndex++;
                }
            }
            case AIM_PLACE -> {
                var tree = spec.trees().get(active.primaryIndex);
                startPlace(minecraft, session, active, tree.saplingPosition(),
                        tree.support().position(), tree.saplingItem(), tree.expectedSapling());
            }
            case PLACE -> {
                if (maintainMutation(active, false, Stage.COLLECT)) {
                    var tree = spec.trees().get(active.primaryIndex);
                    active.effects.add(blockEffect(
                            "tree_replanted", tree.saplingPosition(), AIR, tree.expectedSapling()));
                    active.collectDeadline = Math.min(active.hardDeadline,
                            session.clientTick() + COLLECTION_WAIT_TICKS);
                }
            }
            case COLLECT -> {
                int after = inventoryCount(Objects.requireNonNull(minecraft.player), active.collectionItem);
                if (after > active.collectionBefore) {
                    active.collectionEvidence++;
                    active.primaryIndex++;
                    active.secondaryIndex = 0;
                    active.collectionItem = null;
                    active.stage = Stage.SELECT;
                } else if (session.clientTick() >= active.collectDeadline) {
                    active.inconclusive(PhaseFiveEvidence.Certainty.AMBIGUOUS,
                            "declared log collection had no positive inventory evidence");
                }
            }
            default -> active.fail(internalStageFailure(active.stage));
        }
    }

    private void maintainSurvey(
            Minecraft minecraft, WorldSessionTracker.Snapshot session, Active active) {
        var spec = (PhaseFiveWorldSpec.SurveySpec) active.spec;
        switch (active.stage) {
            case SELECT -> {
                if (active.primaryIndex >= spec.waypoints().size()) {
                    if (active.sampleResults.size() < spec.minimumObserved()) {
                        active.inconclusive(PhaseFiveEvidence.Certainty.UNKNOWN,
                                "requested waypoints did not make the minimum samples current-visible");
                    } else {
                        completeSurvey(active, spec);
                    }
                    return;
                }
                startNavigation(minecraft, session, active,
                        spec.waypoints().get(active.primaryIndex).target(), Stage.SURVEY_AIM);
            }
            case NAVIGATE -> maintainNavigation(minecraft, session, active);
            case SURVEY_AIM -> {
                var waypoint = spec.waypoints().get(active.primaryIndex);
                if (active.view == null) {
                    active.view = ViewLease.acquire(minecraft, null, false);
                }
                active.view.turnToward(minecraft, center(waypoint.lookAt()));
                if (!active.view.aligned(minecraft, center(waypoint.lookAt()))) {
                    return;
                }
                observeSurveySamples(minecraft, session, active, spec);
                closeView(active, false);
                active.primaryIndex++;
                active.stage = Stage.SELECT;
            }
            default -> active.fail(internalStageFailure(active.stage));
        }
    }

    private void maintainSleep(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Active active,
            PhaseFiveAttempt attempt) {
        var spec = (PhaseFiveWorldSpec.SleepSpec) active.spec;
        switch (active.stage) {
            case SELECT -> startNavigation(
                    minecraft, session, active, spec.head().position(), Stage.SLEEP_WAIT);
            case NAVIGATE -> maintainNavigation(minecraft, session, active);
            case SLEEP_WAIT -> {
                RoutineFailure bedFailure = validateBedCurrent(minecraft, session, spec);
                if (bedFailure != null) {
                    active.fail(bedFailure);
                    return;
                }
                BedRule rule = bedRule(Objects.requireNonNull(minecraft.level), spec.head().position());
                if (rule.explodes()) {
                    active.fail(failure(RoutineFailure.Category.SAFETY, "BED_UNSAFE_DIMENSION",
                            RoutineFailure.Recovery.REPLAN, Map.of("explodes", false),
                            Map.of("explodes", true), 0));
                } else if (rule.canSleep(Objects.requireNonNull(minecraft.level))) {
                    active.stage = Stage.SLEEP_AIM;
                }
            }
            case SLEEP_AIM -> {
                if (active.view == null) {
                    active.view = ViewLease.acquire(minecraft, null, true);
                }
                active.view.turnToward(minecraft, center(spec.head().position()));
                if (!active.view.aligned(minecraft, center(spec.head().position()))
                        || !crosshair(minecraft, spec.head().position())) {
                    return;
                }
                RoutineFailure bedFailure = validateBedCurrent(minecraft, session, spec);
                if (bedFailure != null) {
                    active.fail(bedFailure);
                    return;
                }
                // The server-side BedBlock path alone starts sleeping; the client-side use path
                // returns before changing the pose, so the later pose edge is positive evidence.
                active.sleepSignalBaseline = sleepSignals.bindSession(
                        Objects.requireNonNull(minecraft.level), session.worldSessionId());
                var hit = (BlockHitResult) minecraft.hitResult;
                Objects.requireNonNull(minecraft.gameMode).useItemOn(
                        Objects.requireNonNull(minecraft.player), InteractionHand.MAIN_HAND, hit);
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                active.sleepUseTick = session.clientTick();
                active.stage = Stage.SLEEP_ENTER;
            }
            case SLEEP_ENTER -> {
                LocalPlayer player = Objects.requireNonNull(minecraft.player);
                var semanticFailure = sleepFailure(
                        Objects.requireNonNull(minecraft.level), active.sleepSignalBaseline);
                if (semanticFailure.isPresent()) {
                    active.fail(failure(RoutineFailure.Category.PRECONDITION,
                            "SLEEP_REJECTED_BY_SERVER", RoutineFailure.Recovery.REPLAN,
                            Map.of("accepted", true),
                            Map.of("semantic_key",
                                    semanticFailure.orElseThrow().key().translationKey()), 1));
                    return;
                }
                if (player.isSleeping()) {
                    active.sleepObserved = true;
                    closeView(active, false);
                    active.stage = Stage.SLEEP_WAKE;
                } else if (session.clientTick() - active.sleepUseTick >= SLEEP_ENTER_WAIT_TICKS) {
                    active.fail(failure(RoutineFailure.Category.PRECONDITION,
                            "SLEEP_NOT_SERVER_CONFIRMED", RoutineFailure.Recovery.REPLAN,
                            Map.of("sleeping", true), Map.of("sleeping", false), 1));
                }
            }
            case SLEEP_WAKE -> {
                if (active.sleepObserved && !Objects.requireNonNull(minecraft.player).isSleeping()) {
                    active.wakeObserved = true;
                    startNavigation(minecraft, session, active,
                            active.startCheckpoint, Stage.SLEEP_VERIFY);
                }
            }
            case SLEEP_VERIFY -> {
                RoutineFailure bedFailure = validateBedCurrent(minecraft, session, spec);
                if (bedFailure != null) {
                    active.fail(bedFailure);
                    return;
                }
                completeSleep(active, session, attempt);
            }
            default -> active.fail(internalStageFailure(active.stage));
        }
    }

    private void startNavigation(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Active active,
            BlockTarget target,
            Stage afterNavigation) {
        if (withinNavigationTarget(Objects.requireNonNull(minecraft.player), target)) {
            active.stage = afterNavigation;
            return;
        }
        if (active.request.bounds().maxTravelBlocks() < 1) {
            active.fail(failure(RoutineFailure.Category.PRECONDITION, "TARGET_OUT_OF_REACH",
                    RoutineFailure.Recovery.REPLAN, Map.of("within_tolerance", true),
                    Map.of("within_tolerance", false), 0));
            return;
        }
        var request = new NavigateToRequest(target, NAVIGATION_TOLERANCE,
                navigationBounds(active.request.bounds()));
        SemanticActionFrame frame = semanticActions.observe(request);
        if (!frame.universalSafetyClear() || !frame.routeSafe()) {
            semanticActions.retire(request);
            active.fail(failure(RoutineFailure.Category.SAFETY, "NAVIGATION_PRECHECK_FAILED",
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("safe", true, "route_safe", true),
                    Map.of("safe", frame.universalSafetyClear(),
                            "route_safe", frame.routeSafe(), "reason", frame.routeCheckReason()), 0));
            return;
        }
        active.childRequest = request;
        active.child = semanticActions.dispatch(
                request, Math.min(active.hardDeadline, session.clientTick() + 2_400L));
        active.afterNavigation = afterNavigation;
        active.settleTicks = 0;
        active.stage = Stage.NAVIGATE;
    }

    private void maintainNavigation(
            Minecraft minecraft, WorldSessionTracker.Snapshot session, Active active) {
        semanticActions.maintain(active.child);
        SemanticActionEvidence evidence = semanticActions.evidence(active.child);
        if (evidence.failure() != null) {
            active.fail(evidence.failure());
            return;
        }
        var request = (NavigateToRequest) active.childRequest;
        SemanticActionFrame frame = semanticActions.observe(request);
        if (!withinNavigationTarget(Objects.requireNonNull(minecraft.player), request.target())) {
            active.settleTicks = 0;
            return;
        }
        semanticActions.stopInput(active.child);
        boolean settled = frame.onGround()
                && frame.playerHorizontalVelocitySquared() <= SETTLED_VELOCITY_SQUARED
                && frame.routeSafe()
                && frame.positionCorrectionRevision()
                        == active.child.positionCorrectionRevisionAtDispatch();
        active.settleTicks = settled ? active.settleTicks + 1 : 0;
        if (active.settleTicks >= REQUIRED_SETTLE_TICKS) {
            finishChild(active, false);
            active.stage = active.afterNavigation;
        }
    }

    private void startBreak(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Active active,
            BlockTarget target) {
        if (active.view == null) {
            active.view = ViewLease.acquire(minecraft, null, false);
        }
        active.view.turnToward(minecraft, center(target));
        if (!active.view.aligned(minecraft, center(target)) || !crosshair(minecraft, target)) {
            return;
        }
        if (!exactCurrent(minecraft, session, target, active.expectedBefore)) {
            active.fail(targetFailure("MUTATION_SOURCE_NOT_CURRENT", target, active.expectedBefore));
            return;
        }
        var request = new BreakBlockRequest(
                target, active.expectedBefore, AIR, blockBounds(active.request.bounds(), true));
        SemanticActionFrame frame = semanticActions.observe(request);
        if (!frame.universalSafetyClear() || !frame.blockInReach() || !frame.crosshairOnBlock()) {
            semanticActions.retire(request);
            active.fail(failure(RoutineFailure.Category.PRECONDITION, "BREAK_PRECHECK_FAILED",
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("current_visible_reachable_crosshair", true),
                    Map.of("in_reach", frame.blockInReach(), "crosshair", frame.crosshairOnBlock()), 0));
            return;
        }
        active.childRequest = request;
        active.child = semanticActions.dispatch(
                request, Math.min(active.hardDeadline, session.clientTick() + 600L));
        active.stage = Stage.BREAK;
    }

    private void startPlace(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Active active,
            BlockTarget target,
            BlockTarget support,
            String item,
            BlockStateFingerprint expectedAfter) {
        if (active.view == null) {
            active.view = ViewLease.acquire(minecraft, item, false);
        }
        Vec3 aim = new Vec3(support.x() + 0.5D, support.y() + 0.999D, support.z() + 0.5D);
        active.view.turnToward(minecraft, aim);
        if (!active.view.aligned(minecraft, aim)) {
            return;
        }
        if (!currentState(minecraft, session, target).filter(AIR::equals).isPresent()) {
            active.fail(targetFailure("PLACEMENT_TARGET_NOT_CURRENT_AIR", target, AIR));
            return;
        }
        var request = new PlaceBlockRequest(
                target, AIR, item, expectedAfter, blockBounds(active.request.bounds(), false));
        SemanticActionFrame frame = semanticActions.observe(request);
        if (!frame.universalSafetyClear() || !frame.blockInReach() || !frame.crosshairOnBlock()) {
            semanticActions.retire(request);
            active.fail(failure(RoutineFailure.Category.PRECONDITION, "PLACE_PRECHECK_FAILED",
                    RoutineFailure.Recovery.REPLAN,
                    Map.of("current_visible_reachable_placement", true),
                    Map.of("in_reach", frame.blockInReach(), "placement_hit", frame.crosshairOnBlock()), 0));
            return;
        }
        active.childRequest = request;
        active.child = semanticActions.dispatch(
                request, Math.min(active.hardDeadline, session.clientTick() + 600L));
        active.stage = Stage.PLACE;
    }

    /** Returns true only on the tick that the mutation gains positive server evidence. */
    private boolean maintainMutation(Active active, boolean breaking, Stage successStage) {
        semanticActions.maintain(active.child);
        SemanticActionEvidence evidence = semanticActions.evidence(active.child);
        if (evidence.failure() != null) {
            active.fail(evidence.failure());
            return false;
        }
        boolean confirmed = evidence.acknowledged()
                && evidence.serverBlockState().filter(state -> breaking
                        ? AIR.equals(state) : active.expectedAfter.equals(state)).isPresent();
        if (!confirmed) {
            return false;
        }
        finishChild(active, false);
        closeView(active, false);
        active.stage = successStage;
        return true;
    }

    private void observeSurveySamples(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            Active active,
            PhaseFiveWorldSpec.SurveySpec spec) {
        List<BlockPosition> positions = spec.samples().stream()
                .map(PhaseFiveWorldSpec.Sample::position)
                .map(MinecraftPhaseFiveWorldPort::position)
                .toList();
        List<BlockSample> samples = observations.observeBlocks(
                minecraft, session.clientTick(), positions, BlockSource.LIVE);
        for (int index = 0; index < samples.size(); index++) {
            BlockSample sample = samples.get(index);
            var declared = spec.samples().get(index);
            if (sample.outcome() != BlockOutcome.CURRENT) {
                active.sampleUnknown.put(declared.id(), sample.reason());
                continue;
            }
            var observation = sample.observation();
            boolean possible = "spawn_surface_prediction".equals(spec.assessment())
                    && observation.observedContext().sturdyFaces().contains("up")
                    && observation.observedContext().fluid() == null
                    && observation.observedContext().blockLight()
                            <= Objects.requireNonNull(minecraft.level)
                                    .dimensionType().monsterSpawnBlockLightLimit();
            var result = new LinkedHashMap<String, Object>();
            result.put("id", declared.id());
            result.put("position", position(declared.position()).toMap());
            result.put("outcome", possible ? "possibly_spawnable" : "checked");
            result.put("assessment", spec.assessment());
            result.put("verification", "predicted");
            result.put("observed_at_client_tick", session.clientTick());
            active.sampleResults.putIfAbsent(declared.id(), result);
            active.sampleUnknown.remove(declared.id());
        }
    }

    private RoutineFailure validateCurrentPreflight(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PhaseFiveWorldSpec spec) {
        if (spec instanceof PhaseFiveWorldSpec.CropSpec crop) {
            for (var plot : crop.plots()) {
                if (!exactCurrent(minecraft, session, plot.support(), plot.expectedSupport())
                        || currentState(minecraft, session, plot.crop())
                                .filter(state -> crop.adapter().blockId().equals(state.blockId()))
                                .isEmpty()) {
                    return targetFailure("DECLARED_CROP_CELL_NOT_CURRENT",
                            plot.crop(), new BlockStateFingerprint(crop.adapter().blockId(), Map.of()));
                }
            }
        } else if (spec instanceof PhaseFiveWorldSpec.TreeSpec trees) {
            for (var tree : trees.trees()) {
                for (var log : tree.logs()) {
                    if (!exactCurrent(minecraft, session, log.position(), log.expectedState())) {
                        return targetFailure(
                                "DECLARED_LOG_NOT_CURRENT", log.position(), log.expectedState());
                    }
                }
                if (!exactCurrent(minecraft, session,
                        tree.support().position(), tree.support().expectedState())) {
                    return targetFailure("TREE_SUPPORT_NOT_CURRENT",
                            tree.support().position(), tree.support().expectedState());
                }
                for (var clearance : tree.clearance()) {
                    if (!exactCurrent(minecraft, session,
                            clearance.position(), clearance.expectedState())) {
                        return targetFailure("TREE_CLEARANCE_NOT_CURRENT",
                                clearance.position(), clearance.expectedState());
                    }
                }
            }
        } else if (spec instanceof PhaseFiveWorldSpec.SleepSpec sleep) {
            return validateBedCurrent(minecraft, session, sleep);
        }
        return null;
    }

    private RoutineFailure validateBedCurrent(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PhaseFiveWorldSpec.SleepSpec sleep) {
        if (!exactCurrent(minecraft, session, sleep.foot().position(), sleep.foot().expectedState())
                || !exactCurrent(minecraft, session,
                        sleep.head().position(), sleep.head().expectedState())) {
            return targetFailure("BED_HALVES_NOT_CURRENT",
                    sleep.head().position(), sleep.head().expectedState());
        }
        ClientLevel level = Objects.requireNonNull(minecraft.level);
        BlockState foot = level.getBlockState(blockPos(sleep.foot().position()));
        BlockState head = level.getBlockState(blockPos(sleep.head().position()));
        if (!(foot.getBlock() instanceof BedBlock) || !(head.getBlock() instanceof BedBlock)
                || foot.getValue(BedBlock.PART) != BedPart.FOOT
                || head.getValue(BedBlock.PART) != BedPart.HEAD
                || foot.getValue(BedBlock.FACING) != head.getValue(BedBlock.FACING)
                || !blockPos(sleep.foot().position()).relative(foot.getValue(BedBlock.FACING))
                        .equals(blockPos(sleep.head().position()))) {
            return failure(RoutineFailure.Category.PRECONDITION, "BED_GEOMETRY_MISMATCH",
                    RoutineFailure.Recovery.REPLAN, Map.of("connected_halves", true),
                    Map.of("connected_halves", false), 0);
        }
        if (bedRule(level, sleep.head().position()).explodes()) {
            return failure(RoutineFailure.Category.SAFETY, "BED_UNSAFE_DIMENSION",
                    RoutineFailure.Recovery.REPLAN, Map.of("explodes", false),
                    Map.of("explodes", true), 0);
        }
        return null;
    }

    private boolean treePlantingCellsCurrent(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PhaseFiveWorldSpec.Tree tree) {
        if (!exactCurrent(minecraft, session,
                tree.support().position(), tree.support().expectedState())
                || !currentState(minecraft, session, tree.saplingPosition()).filter(AIR::equals).isPresent()) {
            return false;
        }
        for (var clearance : tree.clearance()) {
            if (!exactCurrent(minecraft, session, clearance.position(), clearance.expectedState())) {
                return false;
            }
        }
        return true;
    }

    private RoutineFailure preflightSafety(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            PhaseFiveRequest request) {
        var level = minecraft.level;
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        if (session == null || !session.worldReady() || level == null || player == null
                || gameMode == null || minecraft.getConnection() == null
                || !request.bounds().dimension().equals(session.dimension())
                || !request.bounds().dimension().equals(level.dimension().identifier().toString())) {
            return failure(RoutineFailure.Category.SAFETY, "WORLD_NOT_READY",
                    RoutineFailure.Recovery.REPLAN, Map.of("world_ready", true),
                    Map.of("world_ready", false), 0);
        }
        if (!minecraft.isWindowActive() || !minecraft.mouseHandler.isMouseGrabbed()
                || minecraft.isPaused() || minecraft.gui.screen() != null
                || minecraft.gui.overlay() != null || player.isUsingItem()
                || gameMode.getPlayerMode() != GameType.SURVIVAL
                || !player.isAlive() || player.isDeadOrDying()
                || player.getHealth() < MIN_SAFE_HEALTH || player.hurtTime != 0
                || player.getRemainingFireTicks() > 0
                || !visibleThreatClear(minecraft, player, level)) {
            return failure(RoutineFailure.Category.SAFETY, "PHASE_FIVE_SAFETY_PRECHECK_FAILED",
                    RoutineFailure.Recovery.USER, Map.of("safe", true),
                    Map.of("safe", false), 0);
        }
        return null;
    }

    private RoutineFailure liveSafety(Minecraft minecraft, Active active) {
        var session = sessionSupplier.get();
        var level = minecraft.level;
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        boolean sleepingStage = active.stage == Stage.SLEEP_ENTER || active.stage == Stage.SLEEP_WAKE;
        boolean safe = session != null && active.sameSession(session)
                && level != null && player != null && gameMode != null
                && minecraft.getConnection() != null && minecraft.isWindowActive()
                && !minecraft.isPaused() && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null && gameMode.getPlayerMode() == GameType.SURVIVAL
                && player.isAlive() && !player.isDeadOrDying()
                && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= active.initialHealth
                && player.hurtTime == 0 && player.getRemainingFireTicks() <= 0
                && (sleepingStage || !player.isUsingItem())
                && visibleThreatClear(minecraft, player, level);
        return safe ? null : failure(RoutineFailure.Category.SAFETY,
                "PHASE_FIVE_SAFETY_CHANGED", RoutineFailure.Recovery.USER,
                Map.of("safe", true), Map.of("safe", false), 1);
    }

    private Optional<BlockStateFingerprint> currentState(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            BlockTarget target) {
        BlockSample sample = observations.observeBlock(
                minecraft, session.clientTick(), position(target), BlockSource.LIVE);
        if (sample.outcome() != BlockOutcome.CURRENT) {
            return Optional.empty();
        }
        var state = sample.observation().state();
        return Optional.of(new BlockStateFingerprint(state.block(), state.properties()));
    }

    private boolean exactCurrent(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            BlockTarget target,
            BlockStateFingerprint expected) {
        return currentState(minecraft, session, target).filter(expected::equals).isPresent();
    }

    private void completeCrop(Active active, PhaseFiveWorldSpec.CropSpec spec) {
        var basis = new LinkedHashMap<String, Object>();
        basis.put("declared_plots", spec.plots().size());
        basis.put("verified_replanted_plots", active.verifiedUnits);
        basis.put("collection_evidence", "positive_inventory_delta_per_verified_plot");
        active.complete(new PhaseFiveResult(
                active.verifiedUnits, PhaseFiveWorldEvidence.cropGoal(
                        active.verifiedUnits, spec.minimumHarvested(), active.collectionEvidence),
                basis, active.effects));
    }

    private void completeTree(Active active, PhaseFiveWorldSpec.TreeSpec spec) {
        var basis = new LinkedHashMap<String, Object>();
        basis.put("declared_current_log_cells", spec.totalLogs());
        basis.put("verified_air_log_cells", active.verifiedUnits);
        basis.put("replanted_trees", active.collectionEvidence);
        basis.put("collection_evidence", "positive_inventory_delta_per_declared_tree");
        basis.put("complete_natural_tree_claim", false);
        active.complete(new PhaseFiveResult(
                active.verifiedUnits,
                PhaseFiveWorldEvidence.treeGoal(
                        active.verifiedUnits, spec.totalLogs(),
                        active.collectionEvidence, spec.trees().size()),
                basis, active.effects));
    }

    private void completeSurvey(Active active, PhaseFiveWorldSpec.SurveySpec spec) {
        var basis = new LinkedHashMap<String, Object>();
        basis.put("requested_samples", spec.samples().size());
        basis.put("observed_samples", active.sampleResults.size());
        basis.put("unknown_samples", spec.samples().size() - active.sampleResults.size());
        basis.put("coverage", active.sampleResults.size() / (double) spec.samples().size());
        basis.put("assessment", spec.assessment());
        basis.put("assessment_verification", "predicted");
        basis.put("samples", List.copyOf(active.sampleResults.values()));
        basis.put("unknown_reasons", Map.copyOf(active.sampleUnknown));
        var effect = new RoutineEffect("survey_observations",
                Map.of("requested_samples", spec.samples().size()),
                Map.of("observed_samples", active.sampleResults.size()),
                RoutineEffect.Verification.CONFIRMED);
        active.complete(new PhaseFiveResult(
                active.sampleResults.size(),
                PhaseFiveWorldEvidence.surveyGoal(
                        active.sampleResults.size(), spec.minimumObserved(), spec.samples().size()),
                basis, List.of(effect)));
    }

    private void completeSleep(
            Active active,
            WorldSessionTracker.Snapshot session,
            PhaseFiveAttempt attempt) {
        boolean respawnConfirmed = sleepSignals.latestAfter(
                Objects.requireNonNull(requireMinecraft().level), active.sleepSignalBaseline,
                SleepSemanticSignals.Key.SET_SPAWN).isPresent();
        var effects = new ArrayList<RoutineEffect>();
        effects.add(new RoutineEffect("sleep_cycle",
                Map.of("sleeping", false), Map.of("sleeping", false, "wake_observed", true),
                RoutineEffect.Verification.CONFIRMED));
        if (respawnConfirmed) {
            effects.add(new RoutineEffect("respawn_point_changed",
                    Map.of(), Map.of("semantic_key", "block.minecraft.set_spawn"),
                    RoutineEffect.Verification.CONFIRMED));
        }
        var basis = new LinkedHashMap<String, Object>();
        basis.put("sleep_pose_server_observed", active.sleepObserved);
        basis.put("wake_server_observed", active.wakeObserved);
        basis.put("returned_to_start_checkpoint", true);
        basis.put("respawn_point_confirmed", respawnConfirmed);
        basis.put("attempt_id", attempt.attemptId().toString());
        active.complete(new PhaseFiveResult(1, PhaseFiveWorldEvidence.sleepGoal(
                active.sleepObserved, active.wakeObserved, true), basis, effects));
    }

    private Optional<SleepSemanticSignals.Signal> sleepFailure(
            ClientLevel level, SleepSemanticSignals.Baseline baseline) {
        if (baseline == null) {
            return Optional.empty();
        }
        for (SleepSemanticSignals.Key key : SleepSemanticSignals.Key.values()) {
            if (key.failure()) {
                var signal = sleepSignals.latestAfter(level, baseline, key);
                if (signal.isPresent()) {
                    return signal;
                }
            }
        }
        return Optional.empty();
    }

    private void finishChild(Active active, boolean bestEffort) {
        if (active.child == null) {
            return;
        }
        var child = active.child;
        var request = active.childRequest;
        active.child = null;
        active.childRequest = null;
        Throwable failure = null;
        try {
            semanticActions.release(child);
        } catch (RuntimeException | LinkageError releaseFailure) {
            failure = releaseFailure;
        }
        try {
            semanticActions.retire(request);
        } catch (RuntimeException | LinkageError retireFailure) {
            failure = combine(failure, retireFailure);
        }
        if (!bestEffort && failure != null) {
            rethrow(failure);
        }
    }

    private void closeView(Active active, boolean bestEffort) {
        if (active.view == null) {
            return;
        }
        var view = active.view;
        active.view = null;
        try {
            view.close(requireMinecraft());
        } catch (RuntimeException | LinkageError failure) {
            if (!bestEffort) {
                throw failure;
            }
        }
    }

    private void releaseActive(Active active, boolean bestEffort) {
        if (active.released) {
            return;
        }
        active.released = true;
        Throwable failure = null;
        try {
            finishChild(active, false);
        } catch (RuntimeException | LinkageError childFailure) {
            failure = childFailure;
        }
        try {
            closeView(active, false);
        } catch (RuntimeException | LinkageError viewFailure) {
            failure = combine(failure, viewFailure);
        }
        if (!bestEffort && failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (LinkageError) failure;
    }

    private Active requireActive(PhaseFiveAttempt attempt) {
        Active active = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (active == null) {
            throw new IllegalStateException("Phase 5 world attempt is not active");
        }
        return active;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(minecraftSupplier.get(), "minecraft");
    }

    private Minecraft assertClientThread() {
        Minecraft minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Phase 5 world adapter must run on the client thread");
        }
        return minecraft;
    }

    private WorldSessionTracker.Snapshot requireSession() {
        var session = Objects.requireNonNull(sessionSupplier.get(), "world session");
        if (!session.worldReady()) {
            throw new IllegalStateException("world session is not ready");
        }
        return session;
    }

    private boolean visibleThreatClear(
            Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        return level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
    }

    private static boolean withinNavigationTarget(LocalPlayer player, BlockTarget target) {
        double dx = player.getX() - (target.x() + 0.5D);
        double dz = player.getZ() - (target.z() + 0.5D);
        return dx * dx + dz * dz <= NAVIGATION_TOLERANCE * NAVIGATION_TOLERANCE
                && Math.abs(player.getY() - target.y()) <= 1.0D;
    }

    private static ActionBounds navigationBounds(PhaseFiveBounds bounds) {
        return new ActionBounds(bounds.dimension(), bounds.minimum(), bounds.maximum(),
                Math.max(1, bounds.maxTravelBlocks()),
                Math.min(120, bounds.maxDurationSeconds()), false);
    }

    private static ActionBounds blockBounds(PhaseFiveBounds bounds, boolean allowBreak) {
        return new ActionBounds(bounds.dimension(), bounds.minimum(), bounds.maximum(),
                0, Math.min(30, bounds.maxDurationSeconds()), allowBreak);
    }

    private static boolean crosshair(Minecraft minecraft, BlockTarget target) {
        return minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(blockPos(target));
    }

    private static int inventoryCount(LocalPlayer player, String itemId) {
        int count = 0;
        for (var stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()
                    && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    private static RoutineEffect blockEffect(
            String type,
            BlockTarget position,
            BlockStateFingerprint before,
            BlockStateFingerprint after) {
        return new RoutineEffect(type,
                Map.of("position", position(position).toMap(), "state", stateBasis(before)),
                Map.of("position", position(position).toMap(), "state", stateBasis(after)),
                RoutineEffect.Verification.CONFIRMED);
    }

    private static Map<String, Object> stateBasis(BlockStateFingerprint state) {
        return Map.of("block", state.blockId(), "properties", state.properties());
    }

    private static Map<String, Object> scopeBasis(String kind) {
        return switch (kind) {
            case "tend_crop_area" -> Map.of(
                    "cells", "declared_current_visible_plots_only",
                    "drop_claim", "positive_inventory_delta_only");
            case "harvest_tree_area" -> Map.of(
                    "cells", "declared_current_visible_logs_only",
                    "complete_natural_tree", false,
                    "drop_claim", "positive_inventory_delta_only");
            case "sleep_at_bed" -> Map.of(
                    "bed", "exact_declared_halves",
                    "respawn_claim", "action_scoped_allowlisted_semantic_signal_only");
            case "survey_area" -> Map.of(
                    "denominator", "requested_samples",
                    "spawn_assessment", "predicted");
            default -> Map.of();
        };
    }

    private static RoutineFailure targetFailure(
            String code, BlockTarget target, BlockStateFingerprint expected) {
        return failure(RoutineFailure.Category.PRECONDITION, code,
                RoutineFailure.Recovery.REPLAN,
                Map.of("position", position(target).toMap(), "state", stateBasis(expected)),
                Map.of("current_visible_exact_match", false), 0);
    }

    private static RoutineFailure internalStageFailure(Stage stage) {
        return failure(RoutineFailure.Category.EXTERNAL, "PHASE_FIVE_STAGE_INVALID",
                RoutineFailure.Recovery.USER, Map.of(), Map.of("stage", stage.name()), 1);
    }

    private static RoutineFailure failure(
            RoutineFailure.Category category,
            String code,
            RoutineFailure.Recovery recovery,
            Map<String, Object> expected,
            Map<String, Object> observed,
            int attempts) {
        return new RoutineFailure(
                category, code, false, recovery, RoutineFailure.Scope.ROUTINE,
                attempts, expected, observed, Map.of("blind_retry", false),
                List.of("player", "target", "visible_blocks", "inventory"),
                recovery == RoutineFailure.Recovery.USER);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private static BlockTarget target(String dimension, BlockPos position) {
        return new BlockTarget(dimension, position.getX(), position.getY(), position.getZ());
    }

    private static BlockPosition position(BlockTarget target) {
        return new BlockPosition(target.dimension(), target.x(), target.y(), target.z());
    }

    private static Vec3 center(BlockTarget target) {
        return new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D);
    }

    private static BedRule bedRule(ClientLevel level, BlockTarget head) {
        return level.environmentAttributes().getValue(
                EnvironmentAttributes.BED_RULE, blockPos(head));
    }

    private enum Stage {
        SELECT,
        NAVIGATE,
        AIM_BREAK,
        BREAK,
        AIM_PLACE,
        PLACE,
        COLLECT,
        SURVEY_AIM,
        SLEEP_WAIT,
        SLEEP_AIM,
        SLEEP_ENTER,
        SLEEP_WAKE,
        SLEEP_VERIFY
    }

    private static final class Active {
        private final PhaseFiveRequest request;
        private final PhaseFiveWorldSpec spec;
        private final UUID sessionId;
        private final String dimension;
        private final BlockTarget startCheckpoint;
        private final float initialHealth;
        private final long hardDeadline;
        private final List<RoutineEffect> effects = new ArrayList<>();
        private final Map<String, Map<String, Object>> sampleResults = new LinkedHashMap<>();
        private final Map<String, String> sampleUnknown = new LinkedHashMap<>();
        private Stage stage = Stage.SELECT;
        private Stage afterNavigation;
        private SemanticActionRequest childRequest;
        private SemanticActionAttempt child;
        private ViewLease view;
        private int primaryIndex;
        private int secondaryIndex;
        private int settleTicks;
        private int verifiedUnits;
        private int collectionEvidence;
        private BlockStateFingerprint expectedBefore;
        private BlockStateFingerprint expectedAfter;
        private String collectionItem;
        private int collectionBefore;
        private long collectDeadline;
        private long sleepUseTick;
        private SleepSemanticSignals.Baseline sleepSignalBaseline;
        private boolean sleepObserved;
        private boolean wakeObserved;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double travelled;
        private RoutineFailure failure;
        private PhaseFiveEvidence.Certainty inconclusive;
        private String inconclusiveReason;
        private PhaseFiveResult result;
        private boolean released;

        private Active(
                PhaseFiveRequest request,
                PhaseFiveWorldSpec spec,
                UUID sessionId,
                String dimension,
                BlockTarget startCheckpoint,
                double startX,
                double startY,
                double startZ,
                float initialHealth,
                long hardDeadline) {
            this.request = request;
            this.spec = spec;
            this.sessionId = sessionId;
            this.dimension = dimension;
            this.startCheckpoint = startCheckpoint;
            this.lastX = startX;
            this.lastY = startY;
            this.lastZ = startZ;
            this.initialHealth = initialHealth;
            this.hardDeadline = hardDeadline;
        }

        private boolean sameSession(WorldSessionTracker.Snapshot session) {
            return session.worldReady() && sessionId.equals(session.worldSessionId())
                    && dimension.equals(session.dimension());
        }

        private boolean trackTravel(LocalPlayer player, int maximum) {
            double dx = player.getX() - lastX;
            double dy = player.getY() - lastY;
            double dz = player.getZ() - lastZ;
            travelled += Math.sqrt(dx * dx + dy * dy + dz * dz);
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            return travelled <= maximum + 0.25D;
        }

        private boolean terminal() {
            return failure != null || inconclusive != null || result != null;
        }

        private void fail(RoutineFailure value) {
            if (!terminal()) {
                failure = Objects.requireNonNull(value, "failure");
            }
        }

        private void inconclusive(PhaseFiveEvidence.Certainty certainty, String reason) {
            if (!terminal()) {
                inconclusive = Objects.requireNonNull(certainty, "certainty");
                inconclusiveReason = Objects.requireNonNull(reason, "reason");
            }
        }

        private void complete(PhaseFiveResult value) {
            if (!terminal()) {
                result = Objects.requireNonNull(value, "result");
            }
        }

        private Map<String, Object> basis() {
            var basis = new LinkedHashMap<String, Object>();
            basis.put("stage", stage.name().toLowerCase());
            basis.put("verified_units", verifiedUnits);
            basis.put("travel_blocks", travelled);
            basis.put("scope", scopeBasis(request.kind()));
            basis.put("blind_retry", false);
            return basis;
        }
    }

    /** Minimal camera/selected-slot lease shared by break/place/survey/sleep. */
    private static final class ViewLease {
        private final float originalYaw;
        private final float originalPitch;
        private final int originalSlot;
        private float expectedYaw;
        private float expectedPitch;
        private int expectedSlot;
        private boolean closed;

        private ViewLease(LocalPlayer player, int selectedSlot) {
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            originalSlot = player.getInventory().getSelectedSlot();
            expectedYaw = originalYaw;
            expectedPitch = originalPitch;
            player.getInventory().setSelectedSlot(selectedSlot);
            expectedSlot = selectedSlot;
        }

        static ViewLease acquire(Minecraft minecraft, String requiredItem, boolean requireEmpty) {
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            int slot = player.getInventory().getSelectedSlot();
            if (requiredItem != null || requireEmpty) {
                slot = findHotbarSlot(player, requiredItem, requireEmpty);
            }
            return new ViewLease(player, slot);
        }

        void turnToward(Minecraft minecraft, Vec3 point) {
            requireUndisturbed(minecraft);
            LocalPlayer player = Objects.requireNonNull(minecraft.player);
            Rotation target = rotation(player.getEyePosition(), point);
            float yaw = Mth.clamp(Mth.wrapDegrees(target.yaw - player.getYRot()),
                    -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            float pitch = Mth.clamp(target.pitch - player.getXRot(),
                    -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
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
            if (player != null) {
                player.getInventory().setSelectedSlot(originalSlot);
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
                    || Math.abs(player.getXRot() - expectedPitch) > ROTATION_EPSILON
                    || player.getInventory().getSelectedSlot() != expectedSlot) {
                throw new IllegalStateException("view or selected-slot ownership changed");
            }
        }

        private static int findHotbarSlot(
                LocalPlayer player, String requiredItem, boolean requireEmpty) {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                var stack = player.getInventory().getItem(slot);
                if (requireEmpty && stack.isEmpty()
                        || requiredItem != null && !stack.isEmpty()
                        && requiredItem.equals(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                    return slot;
                }
            }
            throw new IllegalStateException(requireEmpty
                    ? "an empty hotbar slot is required for bed use"
                    : "the required planting item is not in the hotbar");
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
