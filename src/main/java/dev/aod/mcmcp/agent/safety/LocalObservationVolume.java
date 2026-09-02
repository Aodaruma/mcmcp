package dev.aod.mcmcp.agent.safety;

import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static dev.aod.mcmcp.agent.safety.ObservationRecord.Clearance;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Drop;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Fluid;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Hazard;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.LoadedState;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Point;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Support;
import static dev.aod.mcmcp.agent.safety.ObservationRecord.Transition;

/**
 * A fixed, client-only movement-safety volume around the local player. World details are reduced
 * to derived safety classifications before crossing this API boundary.
 */
public final class LocalObservationVolume {
    public static final long UNKNOWN_WORLD_REVISION = -1L;
    public static final double RADIUS_BLOCKS = 6.0D;
    /* A radius-six horizontal disk contains at most 113 feet cells. */
    public static final int MAX_TRANSITIONS = 128;

    private static final double RADIUS_SQUARED = RADIUS_BLOCKS * RADIUS_BLOCKS;
    private static final double SUPPORT_EPSILON = 1.0E-6D;
    private static final double MOVEMENT_EPSILON = 1.0E-7D;
    private static final double MAX_WALKING_DROP = 1.0D;
    private static final double MAX_LADDER_DESCENT_PER_TICK = 0.15D;
    private static final double MAX_LADDER_ASCENT_PER_TICK = 0.20D;
    private static final double DROP_PROBE = 4.0D;
    static final int MAX_CLIMBABLE_RUNG_DELTA = 4;
    /*
     * Radius stays large enough to reconnect a just-opened gate, but the
     * synchronous game-thread search has a strict work budget.  Breadth-first
     * order keeps the closest safety cells when the bounded volume is larger
     * than this budget; omitted cells remain unknown rather than inferred.
     */
    public static final int MAX_OBSERVATIONS = 512;
    private static final LocalObservationVolume GLOBAL =
            new LocalObservationVolume(AgentMovementTrace.global());
    private static final List<HorizontalDirection> DIRECTIONS = List.of(
            new HorizontalDirection(1, 0),
            new HorizontalDirection(-1, 0),
            new HorizontalDirection(0, 1),
            new HorizontalDirection(0, -1),
            new HorizontalDirection(1, 1),
            new HorizontalDirection(1, -1),
            new HorizontalDirection(-1, 1),
            new HorizontalDirection(-1, -1));
    private static final List<HorizontalDirection> CARDINAL_DIRECTIONS = DIRECTIONS.stream()
            .filter(direction -> Math.abs(direction.x()) + Math.abs(direction.z()) == 1)
            .toList();

    private final AgentMovementTrace movementTrace;
    private volatile Snapshot latest;
    private Object playerIdentity;
    private Object levelIdentity;
    private long lastTraceSequence;

    public LocalObservationVolume() {
        this(AgentMovementTrace.global());
    }

    LocalObservationVolume(AgentMovementTrace movementTrace) {
        this.movementTrace = Objects.requireNonNull(movementTrace, "movementTrace");
    }

    public static LocalObservationVolume global() {
        return GLOBAL;
    }

    /** Called by the LocalPlayer tick mixin; it never changes movement or physics itself. */
    public void onPlayerTick(LocalPlayer player) {
        onPlayerTick(player, UNKNOWN_WORLD_REVISION);
    }

    public void onPlayerTick(LocalPlayer player, long worldRevision) {
        Objects.requireNonNull(player, "player");
        observe(player, worldRevision);
    }

    /** Builds and atomically publishes one bounded safety snapshot on the game thread. */
    public Snapshot observe(LocalPlayer player) {
        return observe(player, UNKNOWN_WORLD_REVISION);
    }

    /** The supplied revision is captured with every derived record to fence later mutations. */
    public synchronized Snapshot observe(LocalPlayer player, long worldRevision) {
        Objects.requireNonNull(player, "player");
        requireRevision(worldRevision);
        var level = (ClientLevel) player.level();
        rebindIfNeeded(player, level);

        var originBox = player.getBoundingBox();
        var origin = point(originBox.getCenter());
        var current = evaluateCurrent(player, level, originBox, origin, worldRevision);
        var transitions = new ArrayList<ObservationRecord>();

        movementTrace.latestFor(player).ifPresent(frame -> {
            if (frame.sequence() > lastTraceSequence) {
                lastTraceSequence = frame.sequence();
                if (frame.worldRevision() != worldRevision) {
                    return;
                }
                for (var collision : frame.collisions()) {
                    var record = evaluateActual(
                            player,
                            level,
                            origin,
                            frame,
                            collision,
                            frame.worldRevision());
                    if (withinVolume(origin, record)) {
                        transitions.add(record);
                    }
                }
            }
        });

        transitions.addAll(expandTraversable(
                player,
                level,
                originBox,
                origin,
                worldRevision));
        var snapshot = new Snapshot(
                player.tickCount,
                worldRevision,
                origin,
                current,
                boundedTransitions(origin, transitions));
        latest = snapshot;
        return snapshot;
    }

    /** Uses the runtime's session-local tick so public visual and safety evidence share one clock. */
    public synchronized Snapshot observe(
            LocalPlayer player, long observedTick, long worldRevision) {
        if (observedTick < 0L) {
            throw new IllegalArgumentException("observedTick must be non-negative");
        }
        Snapshot sampled = observe(player, worldRevision);
        var snapshot = new Snapshot(
                observedTick,
                worldRevision,
                sampled.center(),
                atTick(sampled.current(), observedTick),
                sampled.transitions().stream()
                        .map(record -> atTick(record, observedTick))
                        .toList());
        latest = snapshot;
        return snapshot;
    }

    static ObservationRecord atTick(ObservationRecord source, long observedTick) {
        return new ObservationRecord(
                observedTick,
                source.worldRevision(),
                source.transitionDepth(),
                source.from(),
                source.requestedTo(),
                source.to(),
                source.support(),
                source.clearance(),
                source.transition(),
                source.fluid(),
                source.suffocation(),
                source.hazard(),
                source.loaded(),
                source.drop(),
                source.neutralizeAgentHorizontal(),
                source.locomotion());
    }

    public Optional<Snapshot> latestFor(LocalPlayer player) {
        Objects.requireNonNull(player, "player");
        var snapshot = latest;
        if (snapshot == null || playerIdentity != player || levelIdentity != player.level()) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /** Builds fixed-radius, safety-only endpoints for emergency movement selection. */
    public synchronized List<RecoveryOption> recoveryOptions(
            LocalPlayer player, long worldRevision) {
        Objects.requireNonNull(player, "player");
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return List.of();
        }
        var level = (ClientLevel) player.level();
        var start = player.getBoundingBox();
        var origin = point(start.getCenter());
        var options = new ArrayList<RecoveryOption>();
        for (var direction : DIRECTIONS) {
            double unit = Math.hypot(direction.x(), direction.z());
            int steps = Math.max(1, (int) Math.floor(RADIUS_BLOCKS / unit));
            for (int step = 1; step <= steps; step++) {
                var horizontal = new Vec3(
                        direction.x() * step, 0.0D, direction.z() * step);
                var horizontalEvaluation = evaluateHypothetical(
                        player, level, origin, start, horizontal, 1, worldRevision);
                addRecoveryOption(
                        options,
                        player,
                        level,
                        origin,
                        horizontalEvaluation,
                        false);

                double horizontalDistance = horizontal.horizontalDistance();
                double down = Math.sqrt(Math.max(
                        0.0D, RADIUS_SQUARED - horizontalDistance * horizontalDistance));
                var horizontalPath = horizontalEvaluation.record();
                if (down > MAX_WALKING_DROP + MOVEMENT_EPSILON
                        && horizontalPath.loaded() == LoadedState.LOADED
                        && horizontalPath.clearance() == Clearance.CLEAR
                        && horizontalPath.transition() == Transition.PROBE_ALLOWED
                        && horizontalPath.fluid() != Fluid.LAVA
                        && horizontalPath.fluid() != Fluid.UNKNOWN) {
                    addRecoveryOption(
                            options,
                            player,
                            level,
                            origin,
                            evaluateHypothetical(
                                    player,
                                    level,
                                    origin,
                                    horizontalEvaluation.endBox(),
                                    new Vec3(0.0D, -down, 0.0D),
                                    1,
                                    worldRevision),
                            true);
                }
            }
        }
        for (int step = 1; step <= (int) RADIUS_BLOCKS; step++) {
            addRecoveryOption(
                    options,
                    player,
                    level,
                    origin,
                    evaluateHypothetical(
                            player,
                            level,
                            origin,
                            start,
                            new Vec3(0.0D, step, 0.0D),
                            1,
                            worldRevision),
                    false);
        }
        return List.copyOf(options);
    }

    /** Classifies only the loaded four-block column directly below the current AABB. */
    public synchronized LandingEvidence directLanding(
            LocalPlayer player, long worldRevision) {
        Objects.requireNonNull(player, "player");
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return LandingEvidence.UNKNOWN;
        }
        var level = (ClientLevel) player.level();
        var start = player.getBoundingBox();
        var origin = point(start.getCenter());
        var evaluation = evaluateHypothetical(
                player,
                level,
                origin,
                start,
                new Vec3(0.0D, -DROP_PROBE, 0.0D),
                1,
                worldRevision);
        var endpoint = endpointSafety(player, level, origin, evaluation.endBox());
        if (evaluation.record().loaded() != LoadedState.LOADED
                || endpoint.loaded() != LoadedState.LOADED) {
            return LandingEvidence.UNKNOWN;
        }
        if (endpoint.fluid() == Fluid.LAVA) return LandingEvidence.LAVA;
        if (endpoint.support() == Support.PRESENT
                && endpoint.clearance() == Clearance.CLEAR
                && endpoint.fluid() == Fluid.NONE
                && !endpoint.suffocation()
                && endpoint.hazard() == Hazard.NONE) {
            return LandingEvidence.SAFE;
        }
        return evaluation.endBox().getCenter().distanceTo(start.getCenter())
                        >= DROP_PROBE - MOVEMENT_EPSILON
                ? LandingEvidence.VOID : LandingEvidence.UNKNOWN;
    }

    private static void addRecoveryOption(
            List<RecoveryOption> options,
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            Evaluation evaluation,
            boolean landing) {
        if (options.size() >= MAX_OBSERVATIONS
                || evaluation.record().loaded() != LoadedState.LOADED
                || origin.distanceSquared(point(evaluation.endBox().getCenter()))
                        > RADIUS_SQUARED + MOVEMENT_EPSILON
                || evaluation.endBox().getCenter().distanceToSqr(player.getBoundingBox().getCenter())
                        <= MOVEMENT_EPSILON * MOVEMENT_EPSILON) {
            return;
        }
        options.add(new RecoveryOption(
                point(evaluation.endBox().getCenter()),
                evaluation.record(),
                endpointSafety(player, level, origin, evaluation.endBox()),
                landing));
    }

    /** Revalidates the exact world-space horizontal vector immediately before Goal output. */
    public synchronized boolean verifiesGoalHorizontalMovement(
            LocalPlayer player,
            double x,
            double z,
            long observedTick,
            long worldRevision) {
        Objects.requireNonNull(player, "player");
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || observedTick < 0L || worldRevision < 0L) {
            throw new IllegalArgumentException("movement proof inputs must be finite and current");
        }
        double length = Math.hypot(x, z);
        if (length < MOVEMENT_EPSILON || length > 1.0D + MOVEMENT_EPSILON) {
            throw new IllegalArgumentException("movement proof vector must be normalized");
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return false;
        }
        var level = (ClientLevel) player.level();
        rebindIfNeeded(player, level);
        var box = player.getBoundingBox();
        var origin = point(box.getCenter());
        var evaluated = evaluateHypothetical(
                player,
                level,
                origin,
                box,
                new Vec3(x, 0.0D, z),
                1,
                worldRevision).record();
        return goalMovementSafe(atTick(evaluated, observedTick));
    }

    /** Verifies Vanilla's actual SELF-move request, including inertia and vertical motion. */
    public synchronized GoalMovementEvaluation evaluateGoalIntendedMovement(
            LocalPlayer player,
            Vec3 intended,
            long observedTick,
            long worldRevision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intended, "intended");
        if (!Double.isFinite(intended.x) || !Double.isFinite(intended.y)
                || !Double.isFinite(intended.z) || observedTick < 0L || worldRevision < 0L
                || intended.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON) {
            return GoalMovementEvaluation.rejected();
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return GoalMovementEvaluation.rejected();
        }
        var level = (ClientLevel) player.level();
        rebindIfNeeded(player, level);
        var box = player.getBoundingBox();
        var origin = point(box.getCenter());
        var evaluated = evaluateHypothetical(
                player, level, origin, box, intended, 1, worldRevision);
        double resolvedDistance = evaluated.endBox().getCenter().distanceTo(box.getCenter());
        return new GoalMovementEvaluation(
                goalIntendedMovementSafe(atTick(evaluated.record(), observedTick)),
                resolvedDistance);
    }

    public record GoalMovementEvaluation(boolean safe, double resolvedDistance) {
        public GoalMovementEvaluation {
            if (!Double.isFinite(resolvedDistance) || resolvedDistance < 0.0D) {
                throw new IllegalArgumentException("resolved distance must be finite and non-negative");
            }
        }

        private static GoalMovementEvaluation rejected() {
            return new GoalMovementEvaluation(false, 0.0D);
        }
    }

    /** Fences collision preview to the current loaded local volume. */
    public synchronized boolean canPreviewGoalMovement(
            LocalPlayer player, Vec3 intended, long worldRevision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intended, "intended");
        if (!finite(intended) || worldRevision < 0L
                || intended.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON) {
            return false;
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return false;
        }
        var level = (ClientLevel) player.level();
        var box = player.getBoundingBox();
        var origin = point(box.getCenter());
        return loadedState(
                level,
                origin,
                sweptRegions(SweptAabbPath.segments(box, intended, intended)))
                == LoadedState.LOADED;
    }

    /** Verifies the exact collision preview supplied by the Entity.move guard. */
    public synchronized boolean verifiesGoalResolvedMovement(
            LocalPlayer player,
            Vec3 intended,
            Vec3 resolved,
            long observedTick,
            long worldRevision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        if (!finite(intended) || !finite(resolved) || observedTick < 0L || worldRevision < 0L
                || intended.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON
                || resolved.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON) {
            return false;
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return false;
        }
        var level = (ClientLevel) player.level();
        var box = player.getBoundingBox();
        var evaluated = evaluateResolvedHypothetical(
                player,
                level,
                point(box.getCenter()),
                box,
                intended,
                resolved,
                1,
                worldRevision);
        return !bouncySupport(level, player, evaluated.endBox())
                && goalIntendedMovementSafe(atTick(evaluated.record(), observedTick));
    }

    /** Allows only the selected one-block vertical edge to relax the horizontal ground preview. */
    public synchronized boolean verifiesNavigationResolvedMovement(
            LocalPlayer player,
            Vec3 intended,
            Vec3 resolved,
            long observedTick,
            long worldRevision,
            AgentInputState.NavigationIntent intent) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(intent, "intent");
        if (!finite(intended) || !finite(resolved) || observedTick < 0L || worldRevision < 0L
                || intended.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON
                || resolved.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON) {
            return false;
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return false;
        }
        var level = (ClientLevel) player.level();
        var start = player.getBoundingBox();
        var startPoint = point(start.getCenter());
        var evaluated = evaluateResolvedHypothetical(
                player,
                level,
                startPoint,
                start,
                intended,
                resolved,
                1,
                worldRevision);
        var path = atTick(evaluated.record(), observedTick);
        if (intent.locomotion() != Locomotion.GROUND) {
            var endpoint = endpointSafety(player, level, startPoint, evaluated.endBox());
            var targetBox = start.move(intent.target().subtract(start.getCenter()));
            boolean sourceClimbable = safeClimbableCell(
                    player, level, startPoint, start, intent.locomotion());
            boolean resolvedClimbable = safeClimbableCell(
                    player, level, startPoint, evaluated.endBox(), intent.locomotion());
            boolean targetClimbable = safeClimbableCell(
                    player, level, startPoint, targetBox, intent.locomotion());
            boolean ladderBackingClip = intent.locomotion() == Locomotion.LADDER
                    && exactLadderBackingClip(
                            level, start, evaluated.endBox(), intended, resolved);
            boolean targetLanding = !exactClimbableAtFeet(
                    level, targetBox, intent.locomotion())
                    && stableLanding(player, level, startPoint, targetBox);
            boolean resolvedBouncy = bouncySupport(level, player, evaluated.endBox());
            boolean targetBouncy = bouncySupport(level, player, targetBox);
            boolean safe = !resolvedBouncy
                    && !targetBouncy
                    && climbableNavigationMovementSafe(
                            path,
                            endpoint,
                            startPoint,
                            point(evaluated.endBox().getCenter()),
                            intent,
                            sourceClimbable,
                            resolvedClimbable,
                            targetClimbable,
                            targetLanding,
                            ladderBackingClip,
                            intended,
                            resolved);
            return safe;
        }
        if (intent.verticalDelta() < 0) {
            return !bouncySupport(level, player, evaluated.endBox())
                    && goalIntendedMovementSafe(path)
                    && movesTowardNavigationTarget(
                            startPoint, point(evaluated.endBox().getCenter()), intent);
        }
        if (intent.verticalDelta() == 0) {
            return !bouncySupport(level, player, evaluated.endBox())
                    && goalIntendedMovementSafe(path);
        }
        var endpoint = endpointSafety(player, level, startPoint, evaluated.endBox());
        boolean ordinarySafe = goalIntendedMovementSafe(path);
        boolean plannedAscentClip = path.loaded() == LoadedState.LOADED
                && path.transition() == Transition.BLOCKED
                && resolved.y > MOVEMENT_EPSILON
                && endpoint.clearance() == Clearance.CLEAR
                && endpoint.fluid() == Fluid.NONE
                && !endpoint.suffocation()
                && endpoint.hazard() == Hazard.NONE
                && intendedTowardNavigationTarget(startPoint, intended, intent);
        return !bouncySupport(level, player, evaluated.endBox())
                && (ordinarySafe || plannedAscentClip)
                && movesTowardNavigationTarget(
                        startPoint, point(evaluated.endBox().getCenter()), intent);
    }

    static boolean climbableNavigationMovementSafe(
            ObservationRecord path,
            EndpointSafety endpoint,
            Point start,
            Point end,
            AgentInputState.NavigationIntent intent,
            boolean sourceClimbable,
            boolean resolvedClimbable,
            boolean targetClimbable,
            boolean targetLanding,
            boolean ladderBackingClip,
            Vec3 intended,
            Vec3 resolved) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        boolean climbableContact = sourceClimbable || resolvedClimbable;
        boolean ordinaryPath = path.clearance() == Clearance.CLEAR
                && path.transition() == Transition.PROBE_ALLOWED
                && (path.hazard() == Hazard.NONE || path.hazard() == Hazard.FALL);
        var target = point(intent.target());
        double horizontalBefore = square(start.x() - target.x()) + square(start.z() - target.z());
        double horizontalAfter = square(end.x() - target.x()) + square(end.z() - target.z());
        boolean horizontalNonDivergence = horizontalAfter <= horizontalBefore + MOVEMENT_EPSILON;
        double horizontalToleranceSquared = square(intent.horizontalTolerance());
        boolean withinHorizontalTolerance = horizontalBefore
                        <= horizontalToleranceSquared + MOVEMENT_EPSILON
                && horizontalAfter <= horizontalToleranceSquared + MOVEMENT_EPSILON;
        boolean horizontalTargetProgress = horizontalAfter + MOVEMENT_EPSILON
                < horizontalBefore;
        boolean exactLadderAscent = intent.locomotion() == Locomotion.LADDER
                && intent.verticalDelta() > 0
                && sourceClimbable
                && resolvedClimbable
                && targetClimbable;
        // Vanilla applies the JUMP ladder boost after move(...). The first guarded move may
        // therefore retain the ladder's -0.15 descent clamp. A blocked path still requires the
        // exact backing clip; an otherwise ordinary rung path needs no collision exception.
        // Steering stops inside the declared waypoint tolerance, so residual inertia may cross
        // the exact center while remaining inside that already accepted circle.
        boolean plannedLadderAscentClip = exactLadderAscent
                && ladderBackingClip
                && path.clearance() == Clearance.BLOCKED
                && path.transition() == Transition.BLOCKED
                && (path.hazard() == Hazard.COLLISION || path.hazard() == Hazard.FALL)
                && resolved.y >= -MAX_LADDER_DESCENT_PER_TICK - MOVEMENT_EPSILON
                && (horizontalNonDivergence
                        && intendedTowardNavigationTarget(start, intended, intent)
                        || withinHorizontalTolerance);
        boolean ladderAscentBootstrap = exactLadderAscent
                && resolved.y >= -MAX_LADDER_DESCENT_PER_TICK - MOVEMENT_EPSILON
                && resolved.y <= MOVEMENT_EPSILON
                && (horizontalNonDivergence
                        && intendedTowardNavigationTarget(start, intended, intent)
                        || withinHorizontalTolerance)
                && (ordinaryPath || plannedLadderAscentClip);
        // A same-level rung-to-floor edge can briefly clip the floor lip while the ladder descent
        // clamp is still active. Keep this exception cardinal, targetward and bounded until the
        // executor's verified supported-landing JUMP raises the player onto the floor.
        boolean plannedLadderLandingLip = intent.locomotion() == Locomotion.LADDER
                && intent.verticalDelta() >= 0
                && sourceClimbable
                && resolvedClimbable
                && !targetClimbable
                && targetLanding
                && cardinalSameLevelTarget(start, target)
                && path.clearance() == Clearance.BLOCKED
                && path.transition() == Transition.BLOCKED
                && (path.hazard() == Hazard.COLLISION || path.hazard() == Hazard.FALL)
                && resolved.y >= -MAX_LADDER_DESCENT_PER_TICK - MOVEMENT_EPSILON
                && resolved.y <= MAX_LADDER_ASCENT_PER_TICK + MOVEMENT_EPSILON
                && intendedTowardNavigationTarget(start, intended, intent)
                && horizontalTargetProgress;
        if (intent.locomotion() == Locomotion.GROUND
                || !targetClimbable && !targetLanding
                || path.loaded() != LoadedState.LOADED
                || endpoint.loaded() != LoadedState.LOADED
                || endpoint.clearance() != Clearance.CLEAR
                || !ordinaryPath && !plannedLadderAscentClip && !plannedLadderLandingLip
                || path.fluid() != Fluid.NONE
                || endpoint.fluid() != Fluid.NONE
                || path.suffocation()
                || endpoint.suffocation()
                || endpoint.hazard() != Hazard.NONE && endpoint.hazard() != Hazard.FALL) {
            return false;
        }
        if (!climbableContact && !goalIntendedMovementSafe(path)) {
            return false;
        }
        return ladderAscentBootstrap
                || plannedLadderLandingLip
                || movesTowardNavigationTarget(start, end, intent);
    }

    private static boolean cardinalSameLevelTarget(Point start, Point target) {
        int dx = Math.abs(Mth.floor(start.x()) - Mth.floor(target.x()));
        int dz = Math.abs(Mth.floor(start.z()) - Mth.floor(target.z()));
        return dx + dz == 1 && Mth.floor(start.y()) == Mth.floor(target.y());
    }

    private static boolean intendedTowardNavigationTarget(
            Point start, Vec3 intended, AgentInputState.NavigationIntent intent) {
        return intended.x * (intent.target().x - start.x())
                        + intended.z * (intent.target().z - start.z())
                >= -MOVEMENT_EPSILON;
    }

    static boolean movesTowardNavigationTarget(
            Point start, Point end, AgentInputState.NavigationIntent intent) {
        var target = point(intent.target());
        double horizontalBefore = square(start.x() - target.x()) + square(start.z() - target.z());
        double horizontalAfter = square(end.x() - target.x()) + square(end.z() - target.z());
        if (horizontalAfter + MOVEMENT_EPSILON < horizontalBefore) return true;
        return intent.verticalDelta() > 0
                && start.y() + MOVEMENT_EPSILON < target.y()
                && end.y() > start.y() + MOVEMENT_EPSILON
                || intent.verticalDelta() < 0
                && horizontalAfter <= horizontalBefore + MOVEMENT_EPSILON
                && start.y() > target.y() + MOVEMENT_EPSILON
                && end.y() + MOVEMENT_EPSILON < start.y();
    }

    /** Exact post-collision gate for a selected, current recovery endpoint. */
    public synchronized boolean verifiesRecoveryResolvedMovement(
            LocalPlayer player,
            Vec3 intended,
            Vec3 resolved,
            Vec3 externalResolved,
            long observedTick,
            long worldRevision,
            AgentInputState.RecoveryIntent intent) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(externalResolved, "externalResolved");
        Objects.requireNonNull(intent, "intent");
        if (!finite(intended) || !finite(resolved) || !finite(externalResolved)
                || observedTick < 0L || worldRevision < 0L
                || intended.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON
                || resolved.lengthSqr() > RADIUS_SQUARED + MOVEMENT_EPSILON) {
            return false;
        }
        var current = latestFor(player);
        if (current.isEmpty() || current.orElseThrow().worldRevision() != worldRevision) {
            return false;
        }
        var level = (ClientLevel) player.level();
        var start = player.getBoundingBox();
        var startPoint = point(start.getCenter());
        var evaluated = evaluateResolvedHypothetical(
                player,
                level,
                startPoint,
                start,
                intended,
                resolved,
                1,
                worldRevision);
        return !bouncySupport(level, player, evaluated.endBox())
                && recoveryMovementSafe(
                        current.orElseThrow().current(),
                        atTick(evaluated.record(), observedTick),
                        endpointSafety(player, level, startPoint, evaluated.endBox()),
                        startPoint,
                        point(evaluated.endBox().getCenter()),
                        point(start.move(externalResolved).getCenter()),
                        resolved,
                        intent);
    }

    static boolean recoveryMovementSafe(
            ObservationRecord current,
            ObservationRecord path,
            EndpointSafety endpoint,
            Point start,
            Point end,
            Point externalEnd,
            Vec3 resolved,
            AgentInputState.RecoveryIntent intent) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(externalEnd, "externalEnd");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(intent, "intent");
        if (path.loaded() != LoadedState.LOADED
                || endpoint.loaded() != LoadedState.LOADED
                || path.fluid() == Fluid.UNKNOWN
                || endpoint.fluid() == Fluid.UNKNOWN
                || path.hazard() == Hazard.UNKNOWN
                || endpoint.hazard() == Hazard.UNKNOWN
                || end.distanceSquared(point(intent.target()))
                        + MOVEMENT_EPSILON >= start.distanceSquared(point(intent.target()))
                || !agentContributionNonWorsening(end, externalEnd, intent)) {
            return false;
        }
        return switch (intent.mode()) {
            case REACH_BREATHING_SPACE -> path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && !endpoint.suffocation()
                    && path.hazard() == Hazard.NONE
                    && endpoint.hazard() == Hazard.NONE;
            case EXIT_LAVA -> (current.fluid() == Fluid.LAVA
                            || current.hazard() == Hazard.FALL)
                    && avoidsNewDamageHazard(
                            current.hazard(), path.hazard(), endpoint.hazard())
                    && !endpoint.suffocation()
                    && endpoint.hazard() != Hazard.COLLISION
                    && endpoint.hazard() != Hazard.FALL;
            case CONTINUE_LAVA_ESCAPE -> current.fluid() != Fluid.LAVA
                    && current.hazard() == Hazard.NONE
                    && path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && avoidsNewDamageHazard(
                            current.hazard(), path.hazard(), endpoint.hazard())
                    && !endpoint.suffocation()
                    && endpoint.hazard() != Hazard.COLLISION
                    && endpoint.hazard() != Hazard.FALL;
            case ENTER_WATER -> current.fluid() != Fluid.LAVA
                    && path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && avoidsNewDamageHazard(
                            current.hazard(), path.hazard(), endpoint.hazard())
                    && !endpoint.suffocation()
                    && endpoint.hazard() != Hazard.COLLISION
                    && endpoint.hazard() != Hazard.FALL;
            case STEER_TO_LANDING -> resolved.y <= MOVEMENT_EPSILON
                    && path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && !endpoint.suffocation()
                    && endpoint.hazard() != Hazard.COLLISION;
            case ESCAPE_SUFFOCATION -> current.suffocation()
                    && path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && sameOrNoHazard(current.hazard(), path.hazard())
                    && sameOrNoHazard(current.hazard(), endpoint.hazard());
            case EXIT_DAMAGE_SURFACE -> (current.hazard() == Hazard.FIRE_DAMAGE
                            || current.hazard() == Hazard.CONTACT_DAMAGE
                            || current.hazard() == Hazard.FREEZING)
                    && path.fluid() != Fluid.LAVA
                    && endpoint.fluid() != Fluid.LAVA
                    && endpoint.clearance() == Clearance.CLEAR
                    && !endpoint.suffocation()
                    && sameOrNoDamageHazard(current.hazard(), path.hazard())
                    && sameOrNoDamageHazard(current.hazard(), endpoint.hazard());
            case RETREAT_FROM_THREAT -> goalIntendedMovementSafe(path)
                    && stableEndpoint(endpoint)
                    && fartherFromThreat(start, end, intent.threatOrigin());
            case RETREAT_TO_SAFE -> goalIntendedMovementSafe(path)
                    && stableEndpoint(endpoint);
        };
    }

    private static boolean fartherFromThreat(Point start, Point end, Vec3 threat) {
        if (threat == null) return false;
        double before = square(start.x() - threat.x) + square(start.z() - threat.z);
        double after = square(end.x() - threat.x) + square(end.z() - threat.z);
        return after > before + MOVEMENT_EPSILON;
    }

    private static boolean agentContributionNonWorsening(
            Point end, Point externalEnd, AgentInputState.RecoveryIntent intent) {
        if (intent.mode() == AgentInputState.RecoveryMode.RETREAT_FROM_THREAT) {
            var threat = intent.threatOrigin();
            if (threat == null) return false;
            double full = square(end.x() - threat.x) + square(end.z() - threat.z);
            double external = square(externalEnd.x() - threat.x)
                    + square(externalEnd.z() - threat.z);
            return full + MOVEMENT_EPSILON >= external;
        }
        return end.distanceSquared(point(intent.target()))
                <= externalEnd.distanceSquared(point(intent.target())) + MOVEMENT_EPSILON;
    }

    private static boolean sameOrNoDamageHazard(Hazard current, Hazard candidate) {
        return sameOrNoHazard(current, candidate);
    }

    private static boolean sameOrNoHazard(Hazard current, Hazard candidate) {
        return candidate == Hazard.NONE || candidate == current;
    }

    public static boolean avoidsNewDamageHazard(
            Hazard current, Hazard path, Hazard endpoint) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(endpoint, "endpoint");
        return path != Hazard.UNKNOWN
                && endpoint != Hazard.UNKNOWN
                && (!damageHazard(path) || path == current)
                && (!damageHazard(endpoint) || endpoint == current);
    }

    private static boolean damageHazard(Hazard hazard) {
        return hazard == Hazard.FIRE_DAMAGE
                || hazard == Hazard.CONTACT_DAMAGE
                || hazard == Hazard.FREEZING;
    }

    private static boolean stableEndpoint(EndpointSafety endpoint) {
        return endpoint.clearance() == Clearance.CLEAR
                && endpoint.support() == Support.PRESENT
                && endpoint.fluid() == Fluid.NONE
                && !endpoint.suffocation()
                && endpoint.hazard() == Hazard.NONE;
    }

    private static double square(double value) {
        return value * value;
    }

    static boolean goalMovementSafe(ObservationRecord record) {
        Objects.requireNonNull(record, "record");
        return record.loaded() == LoadedState.LOADED
                && (record.support() == Support.PRESENT
                        || record.drop() == Drop.WITHIN_WALKING_LIMIT)
                && record.clearance() == Clearance.CLEAR
                && record.transition() == Transition.PROBE_ALLOWED
                && record.fluid() == Fluid.NONE
                && !record.suffocation()
                && record.hazard() == Hazard.NONE
                && !record.neutralizeAgentHorizontal();
    }

    static boolean goalIntendedMovementSafe(ObservationRecord record) {
        Objects.requireNonNull(record, "record");
        return record.loaded() == LoadedState.LOADED
                && record.clearance() == Clearance.CLEAR
                && record.transition() == Transition.PROBE_ALLOWED
                && record.fluid() == Fluid.NONE
                && !record.suffocation()
                && record.hazard() == Hazard.NONE
                && !record.neutralizeAgentHorizontal();
    }

    /** Pure range/depth guard used by tests and by the publication boundary. */
    static List<ObservationRecord> boundedTransitions(
            Point origin,
            List<ObservationRecord> candidates) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(candidates, "candidates");
        var bounded = new ArrayList<ObservationRecord>(candidates.size());
        for (var candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (candidate.transitionDepth() == 0
                    || candidate.transitionDepth() > MAX_TRANSITIONS
                    || !withinVolume(origin, candidate)) {
                continue;
            }
            bounded.add(candidate);
            if (bounded.size() == MAX_OBSERVATIONS) {
                break;
            }
        }
        return List.copyOf(bounded);
    }

    private void rebindIfNeeded(LocalPlayer player, ClientLevel level) {
        if (playerIdentity == player && levelIdentity == level) {
            return;
        }
        playerIdentity = player;
        levelIdentity = level;
        lastTraceSequence = 0L;
        latest = null;
    }

    private List<ObservationRecord> expandTraversable(
            LocalPlayer player,
            ClientLevel level,
            AABB originBox,
            Point origin,
            long worldRevision) {
        var records = new ArrayList<ObservationRecord>();
        var queue = new ArrayDeque<Node>();
        var reached = new HashSet<NodeKey>();
        var attempted = new HashSet<Edge>();
        var attemptedClimbableColumns = new HashSet<ClimbableColumn>();
        int evaluations = 0;
        var rootOffset = new GridOffset(0, 0);
        var rootKey = NodeKey.at(rootOffset, originBox);
        reached.add(rootKey);
        queue.addLast(new Node(rootKey, originBox, 0));

        search:
        while (!queue.isEmpty()
                && records.size() < MAX_OBSERVATIONS
                && evaluations < MAX_OBSERVATIONS) {
            var node = queue.removeFirst();
            if (node.depth() >= MAX_TRANSITIONS) {
                continue;
            }
            addClimbableTransitions(
                    player,
                    level,
                    origin,
                    node,
                    worldRevision,
                    attemptedClimbableColumns,
                    records,
                    Locomotion.LADDER);
            addClimbableTransitions(
                    player,
                    level,
                    origin,
                    node,
                    worldRevision,
                    attemptedClimbableColumns,
                    records,
                    Locomotion.SCAFFOLDING);
            if (records.size() == MAX_OBSERVATIONS) {
                break;
            }
            for (var direction : DIRECTIONS) {
                var targetOffset = node.key().offset().move(direction);
                var edge = new Edge(node.key(), direction);
                if (!attempted.add(edge)) {
                    continue;
                }
                var intended = adjacentCellCenterDelta(
                        node.box(), direction.x(), direction.z());
                if (exactClimbableAtFeet(level, node.box())
                        || exactClimbableAtFeet(level, node.box().move(intended))) {
                    continue;
                }
                var targetCenter = point(node.box().move(intended).getCenter());
                if (origin.distanceSquared(targetCenter) > RADIUS_SQUARED) {
                    continue;
                }
                if (evaluations == MAX_OBSERVATIONS) {
                    break search;
                }

                // Candidate graph only: exact runtime guards keep using the resolved tick path.
                evaluations++;
                var evaluation = evaluateHypothetical(
                        player,
                        level,
                        origin,
                        node.box(),
                        intended,
                        node.depth() + 1,
                        worldRevision,
                        true);
                if (evaluation.record().canExpand()
                        && withinVolume(origin, evaluation.record())) {
                    var targetKey = NodeKey.at(targetOffset, evaluation.endBox());
                    records.add(evaluation.record());
                    if (reached.add(targetKey)) {
                        queue.addLast(new Node(
                                targetKey, evaluation.endBox(), node.depth() + 1));
                    }
                } else {
                    records.add(evaluation.record());
                }
                if (records.size() == MAX_OBSERVATIONS) {
                    break;
                }
            }
        }
        return List.copyOf(records);
    }

    private void addClimbableTransitions(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            Node node,
            long worldRevision,
            HashSet<ClimbableColumn> attemptedColumns,
            ArrayList<ObservationRecord> records,
            Locomotion locomotion) {
        int feetY = Mth.floor(node.box().minY + MOVEMENT_EPSILON);
        int feetX = Mth.floor(node.box().getCenter().x);
        int feetZ = Mth.floor(node.box().getCenter().z);
        var candidates = new ArrayList<BlockPos>(1 + CARDINAL_DIRECTIONS.size());
        candidates.add(new BlockPos(feetX, feetY, feetZ));
        for (var direction : CARDINAL_DIRECTIONS) {
            candidates.add(new BlockPos(
                    feetX + direction.x(), feetY, feetZ + direction.z()));
        }
        for (BlockPos entry : candidates) {
            var entryBox = boxAtFeetCell(node.box(), entry);
            if (!safeClimbableCell(player, level, origin, entryBox, locomotion)
                    || !attemptedColumns.add(new ClimbableColumn(
                            entry.getX(), entry.getZ(), locomotion))) {
                continue;
            }
            addClimbableColumn(
                    player, level, origin, node, entry, worldRevision, records, locomotion);
            if (records.size() == MAX_OBSERVATIONS) {
                return;
            }
        }
    }

    private void addClimbableColumn(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            Node entryNode,
            BlockPos entry,
            long worldRevision,
            ArrayList<ObservationRecord> records,
            Locomotion locomotion) {
        var rungs = new TreeMap<Integer, AABB>();
        var entryBox = boxAtFeetCell(entryNode.box(), entry);
        rungs.put(entry.getY(), entryBox);
        for (int direction : List.of(-1, 1)) {
            for (int delta = 1; delta <= MAX_CLIMBABLE_RUNG_DELTA; delta++) {
                int y = entry.getY() + direction * delta;
                var rungBox = boxAtFeetCell(
                        entryNode.box(), new BlockPos(entry.getX(), y, entry.getZ()));
                if (!safeClimbableCell(player, level, origin, rungBox, locomotion)
                        || !climbablePathSafe(
                                player,
                                level,
                                origin,
                                rungs.get(y - direction),
                                rungBox,
                                locomotion)) {
                    break;
                }
                rungs.put(y, rungBox);
            }
        }

        var rungEntries = new ArrayList<>(rungs.entrySet());
        for (int index = 1; index < rungEntries.size(); index++) {
            var lower = rungEntries.get(index - 1);
            var upper = rungEntries.get(index);
            if (upper.getKey() - lower.getKey() != 1) {
                continue;
            }
            addClimbableRecord(
                    player,
                    level,
                    origin,
                    entryNode,
                    entry.getY(),
                    lower.getValue(),
                    upper.getValue(),
                    worldRevision,
                    records,
                    locomotion);
            addClimbableRecord(
                    player,
                    level,
                    origin,
                    entryNode,
                    entry.getY(),
                    upper.getValue(),
                    lower.getValue(),
                    worldRevision,
                    records,
                    locomotion);
        }

        for (var rung : rungEntries) {
            int y = rung.getKey();
            for (var direction : CARDINAL_DIRECTIONS) {
                var landingPos = new BlockPos(
                        entry.getX() + direction.x(), y, entry.getZ() + direction.z());
                var landingBox = boxAtFeetCell(entryNode.box(), landingPos);
                if (exactClimbableAtFeet(level, landingBox)
                        || !stableLanding(player, level, origin, landingBox)
                        || !climbablePathSafe(
                                player, level, origin, rung.getValue(), landingBox, locomotion)) {
                    continue;
                }
                addClimbableRecord(
                        player,
                        level,
                        origin,
                        entryNode,
                        entry.getY(),
                        rung.getValue(),
                        landingBox,
                        worldRevision,
                        records,
                        locomotion);
                addClimbableRecord(
                        player,
                        level,
                        origin,
                        entryNode,
                        entry.getY(),
                        landingBox,
                        rung.getValue(),
                        worldRevision,
                        records,
                        locomotion);
            }
        }
    }

    private static void addClimbableRecord(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            Node entryNode,
            int entryY,
            AABB from,
            AABB to,
            long worldRevision,
            ArrayList<ObservationRecord> records,
            Locomotion locomotion) {
        if (records.size() == MAX_OBSERVATIONS) {
            return;
        }
        int depth = entryNode.depth()
                + Math.abs(Mth.floor(to.minY + MOVEMENT_EPSILON) - entryY) + 1;
        if (depth > MAX_TRANSITIONS) {
            return;
        }
        Support targetSupport = support(level, player, origin, to);
        if (targetSupport == Support.UNKNOWN) {
            return;
        }
        Point fromPoint = point(from.getCenter());
        Point toPoint = point(to.getCenter());
        records.add(new ObservationRecord(
                player.tickCount,
                worldRevision,
                depth,
                fromPoint,
                toPoint,
                toPoint,
                targetSupport,
                Clearance.CLEAR,
                Transition.PROBE_ALLOWED,
                Fluid.NONE,
                false,
                Hazard.NONE,
                LoadedState.LOADED,
                targetSupport == Support.PRESENT
                        ? Drop.SUPPORTED : Drop.AIRBORNE_OR_SWIMMING,
                false,
                locomotion));
    }

    private static boolean safeLadderCell(
            LocalPlayer player, ClientLevel level, Point origin, AABB box) {
        if (!exactSurvivingLadderAtFeet(level, origin, box)
                || loadedState(level, origin, List.of(box, supportSlab(box)))
                        != LoadedState.LOADED
                || !level.noCollision(player, box)
                || level.collidesWithSuffocatingBlock(player, box)
                || fluid(level, origin, List.of(box)) != Fluid.NONE) {
            return false;
        }
        return damageBlockHazard(level, origin, List.of(box)) == Hazard.NONE;
    }

    private static boolean safeScaffoldingCell(
            LocalPlayer player, ClientLevel level, Point origin, AABB box) {
        if (!exactSurvivingScaffoldingAtFeet(level, origin, box)
                || loadedState(level, origin, List.of(box, supportSlab(box)))
                        != LoadedState.LOADED
                || !noScaffoldingTraversalCollision(level, player, box)
                || level.collidesWithSuffocatingBlock(player, box)
                || fluid(level, origin, List.of(box)) != Fluid.NONE) {
            return false;
        }
        return damageBlockHazard(level, origin, List.of(box)) == Hazard.NONE;
    }

    private static boolean safeClimbableCell(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB box,
            Locomotion locomotion) {
        return switch (locomotion) {
            case LADDER -> safeLadderCell(player, level, origin, box);
            case SCAFFOLDING -> safeScaffoldingCell(player, level, origin, box);
            case GROUND -> false;
        };
    }

    private static boolean stableLanding(
            LocalPlayer player, ClientLevel level, Point origin, AABB box) {
        return stableEndpoint(endpointSafety(player, level, origin, box));
    }

    private static boolean ladderPathSafe(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB from,
            AABB to) {
        Vec3 delta = to.getCenter().subtract(from.getCenter());
        var regions = sweptRegions(SweptAabbPath.segments(from, delta, delta));
        AABB swept = from.expandTowards(delta);
        return loadedState(level, origin, append(regions, to)) == LoadedState.LOADED
                && level.noCollision(player, swept)
                && !level.collidesWithSuffocatingBlock(player, swept)
                && fluid(level, origin, regions) == Fluid.NONE
                && damageBlockHazard(level, origin, regions) == Hazard.NONE;
    }

    private static boolean scaffoldingPathSafe(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB from,
            AABB to) {
        Vec3 delta = to.getCenter().subtract(from.getCenter());
        var regions = sweptRegions(SweptAabbPath.segments(from, delta, delta));
        AABB swept = from.expandTowards(delta);
        return loadedState(level, origin, append(regions, to)) == LoadedState.LOADED
                && noScaffoldingTraversalCollision(level, player, swept)
                && !level.collidesWithSuffocatingBlock(player, swept)
                && fluid(level, origin, regions) == Fluid.NONE
                && damageBlockHazard(level, origin, regions) == Hazard.NONE;
    }

    private static boolean noScaffoldingTraversalCollision(
            ClientLevel level, LocalPlayer player, AABB box) {
        return !level.getBlockCollisionsFromContext(CollisionContext.empty(), box)
                        .iterator().hasNext()
                && level.noEntityCollision(player, box)
                && level.noBorderCollision(player, box);
    }

    private static boolean climbablePathSafe(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB from,
            AABB to,
            Locomotion locomotion) {
        return switch (locomotion) {
            case LADDER -> ladderPathSafe(player, level, origin, from, to);
            case SCAFFOLDING -> scaffoldingPathSafe(player, level, origin, from, to);
            case GROUND -> false;
        };
    }

    private static boolean exactLadderAtFeet(ClientLevel level, AABB box) {
        BlockPos position = feetBlock(box);
        return level.isLoaded(position) && level.getBlockState(position).is(Blocks.LADDER);
    }

    private static boolean exactLadderBackingClip(
            ClientLevel level, AABB start, AABB end, Vec3 intended, Vec3 resolved) {
        BlockPos sourcePosition = feetBlock(start);
        BlockPos resolvedPosition = feetBlock(end);
        return level.isLoaded(sourcePosition)
                && level.isLoaded(resolvedPosition)
                && ladderBackingClip(
                        level.getBlockState(sourcePosition),
                        level.getBlockState(resolvedPosition),
                        intended,
                        resolved);
    }

    static boolean ladderBackingClip(
            BlockState source, BlockState end, Vec3 intended, Vec3 resolved) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(resolved, "resolved");
        if (!source.is(Blocks.LADDER)
                || !end.is(Blocks.LADDER)
                || !source.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || !end.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return false;
        }
        var backing = source.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        if (end.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() != backing) {
            return false;
        }
        double intendedIntoBacking = intended.x * backing.getStepX()
                + intended.z * backing.getStepZ();
        double resolvedIntoBacking = resolved.x * backing.getStepX()
                + resolved.z * backing.getStepZ();
        return intendedIntoBacking > MOVEMENT_EPSILON
                && intendedIntoBacking - resolvedIntoBacking > MOVEMENT_EPSILON;
    }

    private static boolean exactScaffoldingAtFeet(ClientLevel level, AABB box) {
        BlockPos position = feetBlock(box);
        return level.isLoaded(position) && level.getBlockState(position).is(Blocks.SCAFFOLDING);
    }

    private static boolean exactClimbableAtFeet(ClientLevel level, AABB box) {
        return exactLadderAtFeet(level, box) || exactScaffoldingAtFeet(level, box);
    }

    private static boolean exactClimbableAtFeet(
            ClientLevel level, AABB box, Locomotion locomotion) {
        return switch (locomotion) {
            case LADDER -> exactLadderAtFeet(level, box);
            case SCAFFOLDING -> exactScaffoldingAtFeet(level, box);
            case GROUND -> false;
        };
    }

    private static boolean exactSurvivingLadderAtFeet(
            ClientLevel level, Point origin, AABB box) {
        BlockPos position = feetBlock(box);
        if (!level.isLoaded(position)) {
            return false;
        }
        var state = level.getBlockState(position);
        if (!state.is(Blocks.LADDER)
                || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return false;
        }
        BlockPos attachment = position.relative(
                state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        return insideRadius(
                        origin, attachment.getX(), attachment.getY(), attachment.getZ())
                && level.isLoaded(attachment)
                && state.canSurvive(level, position);
    }

    private static boolean exactSurvivingScaffoldingAtFeet(
            ClientLevel level, Point origin, AABB box) {
        BlockPos position = feetBlock(box);
        if (!insideRadius(origin, position.getX(), position.getY(), position.getZ())
                || !level.isLoaded(position)) {
            return false;
        }
        var state = level.getBlockState(position);
        return dryStableScaffoldingState(state)
                && state.canSurvive(level, position);
    }

    static boolean dryStableScaffoldingState(
            net.minecraft.world.level.block.state.BlockState state) {
        Objects.requireNonNull(state, "state");
        return state.is(Blocks.SCAFFOLDING)
                && state.hasProperty(ScaffoldingBlock.DISTANCE)
                && state.hasProperty(ScaffoldingBlock.WATERLOGGED)
                && state.getValue(ScaffoldingBlock.DISTANCE)
                        < ScaffoldingBlock.STABILITY_MAX_DISTANCE
                && !state.getValue(ScaffoldingBlock.WATERLOGGED);
    }

    private static BlockPos feetBlock(AABB box) {
        return new BlockPos(
                Mth.floor(box.getCenter().x),
                Mth.floor(box.minY + MOVEMENT_EPSILON),
                Mth.floor(box.getCenter().z));
    }

    private static AABB boxAtFeetCell(AABB template, BlockPos cell) {
        Vec3 center = template.getCenter();
        return template.move(
                cell.getX() + 0.5D - center.x,
                cell.getY() - template.minY,
                cell.getZ() + 0.5D - center.z);
    }

    private ObservationRecord evaluateCurrent(
            LocalPlayer player,
            ClientLevel level,
            AABB box,
            Point origin,
            long worldRevision) {
        var loaded = loadedState(level, origin, List.of(box, supportSlab(box)));
        if (loaded == LoadedState.UNKNOWN) {
            return unknownRecord(
                    player.tickCount,
                    worldRevision,
                    0,
                    origin,
                    origin,
                    origin,
                    Transition.STATIONARY);
        }
        var support = support(level, player, origin, box);
        var fluid = fluid(level, origin, List.of(box));
        var suffocation = level.collidesWithSuffocatingBlock(player, box);
        var clearance = level.noCollision(player, box) ? Clearance.CLEAR : Clearance.BLOCKED;
        var drop = classifyDrop(player, level, origin, box, support, fluid, true);
        if (drop == Drop.UNKNOWN) {
            loaded = LoadedState.UNKNOWN;
        }
        return new ObservationRecord(
                player.tickCount,
                worldRevision,
                0,
                origin,
                origin,
                origin,
                support,
                clearance,
                Transition.STATIONARY,
                fluid,
                suffocation,
                withBlockHazard(
                        hazard(loaded, clearance, fluid, suffocation, drop),
                        damageBlockHazard(
                                level, origin, List.of(box, damageSupportRegion(box)))),
                loaded,
                drop,
                shouldNeutralize(support, drop));
    }

    private static EndpointSafety endpointSafety(
            LocalPlayer player, ClientLevel level, Point origin, AABB box) {
        var loaded = loadedState(level, origin, List.of(box, supportSlab(box)));
        if (loaded == LoadedState.UNKNOWN) {
            return new EndpointSafety(
                    Support.UNKNOWN,
                    Clearance.UNKNOWN,
                    Fluid.UNKNOWN,
                    false,
                    Hazard.UNKNOWN,
                    LoadedState.UNKNOWN,
                    Drop.UNKNOWN);
        }
        var support = support(level, player, origin, box);
        var clearance = level.noCollision(player, box) ? Clearance.CLEAR : Clearance.BLOCKED;
        var fluid = fluid(level, origin, List.of(box));
        var suffocation = level.collidesWithSuffocatingBlock(player, box);
        var drop = classifyDrop(player, level, origin, box, support, fluid, false);
        if (drop == Drop.UNKNOWN) {
            loaded = LoadedState.UNKNOWN;
        }
        return new EndpointSafety(
                support,
                clearance,
                fluid,
                suffocation,
                withBlockHazard(
                        hazard(loaded, clearance, fluid, suffocation, drop),
                        damageBlockHazard(
                                level, origin, List.of(box, damageSupportRegion(box)))),
                loaded,
                drop);
    }

    private Evaluation evaluateHypothetical(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB start,
            Vec3 intended,
            int depth,
            long worldRevision) {
        return evaluateHypothetical(
                player, level, origin, start, intended, depth, worldRevision, false);
    }

    private Evaluation evaluateHypothetical(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB start,
            Vec3 intended,
            int depth,
            long worldRevision,
            boolean allowWalkingLanding) {
        var intendedRegions = sweptRegions(SweptAabbPath.segments(start, intended, intended));
        if (loadedState(level, origin, intendedRegions) == LoadedState.UNKNOWN) {
            var from = point(start.getCenter());
            var intendedTo = point(start.move(intended).getCenter());
            return new Evaluation(
                    unknownRecord(
                            player.tickCount,
                            worldRevision,
                            depth,
                            from,
                            intendedTo,
                            intendedTo,
                            Transition.UNKNOWN),
                    start);
        }
        var resolved = VanillaCollisionResolver.resolve(player, level, start, intended);
        return evaluateResolvedPath(
                player,
                level,
                origin,
                start,
                point(start.move(intended).getCenter()),
                SweptAabbPath.segments(start, intended, resolved),
                start.move(resolved),
                horizontallyClipped(intended, resolved),
                depth,
                worldRevision,
                allowWalkingLanding
                        && intended.y == 0.0D
                        && intended.horizontalDistanceSqr() > 0.0D);
    }

    private Evaluation evaluateResolvedHypothetical(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB start,
            Vec3 intended,
            Vec3 resolved,
            int depth,
            long worldRevision) {
        var intendedSegments = SweptAabbPath.segments(start, intended, intended);
        var intendedRegions = sweptRegions(intendedSegments);
        var from = point(start.getCenter());
        var intendedTo = point(start.move(intended).getCenter());
        if (loadedState(level, origin, intendedRegions) == LoadedState.UNKNOWN) {
            return new Evaluation(
                    unknownRecord(
                            player.tickCount,
                            worldRevision,
                            depth,
                            from,
                            intendedTo,
                            intendedTo,
                            Transition.UNKNOWN),
                    start);
        }

        return evaluateResolvedPath(
                player,
                level,
                origin,
                start,
                intendedTo,
                SweptAabbPath.segments(start, intended, resolved),
                start.move(resolved),
                horizontallyClipped(intended, resolved),
                depth,
                worldRevision,
                false);
    }

    private Evaluation evaluateResolvedPath(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB start,
            Point intendedTo,
            List<SweptAabbPath.AxisSegment> segments,
            AABB end,
            boolean clipped,
            int depth,
            long worldRevision,
            boolean allowWalkingLanding) {
        var from = point(start.getCenter());
        var regions = sweptRegions(segments);
        var loaded = loadedState(level, origin, append(append(regions, end), supportSlab(end)));
        if (loaded == LoadedState.UNKNOWN) {
            return new Evaluation(
                    unknownRecord(
                            player.tickCount,
                            worldRevision,
                            depth,
                            from,
                            intendedTo,
                            point(end.getCenter()),
                            Transition.UNKNOWN),
                    start);
        }

        var clearance = !clipped && level.noCollision(player, end)
                ? Clearance.CLEAR
                : Clearance.BLOCKED;
        var support = support(level, player, origin, end);
        var fluid = fluid(level, origin, regions);
        var suffocation = level.collidesWithSuffocatingBlock(player, end);
        var dropEvaluation = dropEvaluation(
                player, level, origin, end, support, fluid, false);
        if (allowWalkingLanding
                && dropEvaluation.drop() == Drop.WITHIN_WALKING_LIMIT
                && dropEvaluation.resolvedDelta().y < -MOVEMENT_EPSILON) {
            return evaluateResolvedPath(
                    player,
                    level,
                    origin,
                    start,
                    intendedTo,
                    appendWalkingLandingSegments(
                            segments, end, dropEvaluation.resolvedDelta()),
                    end.move(dropEvaluation.resolvedDelta()),
                    clipped,
                    depth,
                    worldRevision,
                    false);
        }
        var drop = dropEvaluation.drop();
        if (drop == Drop.UNKNOWN) {
            loaded = LoadedState.UNKNOWN;
        }
        var transition = clipped || clearance == Clearance.BLOCKED
                ? Transition.BLOCKED
                : Transition.PROBE_ALLOWED;
        var record = new ObservationRecord(
                player.tickCount,
                worldRevision,
                depth,
                from,
                intendedTo,
                point(end.getCenter()),
                support,
                clearance,
                transition,
                fluid,
                suffocation,
                withBlockHazard(
                        hazard(loaded, clearance, fluid, suffocation, drop),
                        damageBlockHazard(
                                level, origin, append(regions, damageSupportRegion(end)))),
                loaded,
                drop,
                shouldNeutralize(support, drop));
        return new Evaluation(record, end);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private ObservationRecord evaluateActual(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AgentMovementTrace.TickMovement frame,
            AgentMovementTrace.CollisionResolution collision,
            long worldRevision) {
        var start = collision.start();
        var end = collision.resolvedEnd();
        var from = point(start.getCenter());
        var requestedTo = point(start.move(collision.intendedDelta()).getCenter());
        var to = point(end.getCenter());
        var regions = append(
                append(sweptRegions(collision.segments()), end),
                supportSlab(end));
        var loaded = loadedState(level, origin, regions);
        if (loaded == LoadedState.UNKNOWN) {
            return unknownRecord(
                    frame.tick(),
                    worldRevision,
                    1,
                    from,
                    requestedTo,
                    to,
                    Transition.UNKNOWN);
        }
        var clearance = level.noCollision(player, end)
                ? Clearance.CLEAR
                : Clearance.BLOCKED;
        var support = support(level, player, origin, end);
        var fluid = fluid(level, origin, sweptRegions(collision.segments()));
        var suffocation = level.collidesWithSuffocatingBlock(player, end);
        var drop = classifyDrop(player, level, origin, end, support, fluid, false);
        if (drop == Drop.UNKNOWN) {
            loaded = LoadedState.UNKNOWN;
        }
        Transition transition = actualTransition(frame, collision);
        Locomotion locomotion;
        if ((safeLadderCell(player, level, origin, start)
                        || safeLadderCell(player, level, origin, end))
                && ladderPathSafe(player, level, origin, start, end)) {
            locomotion = Locomotion.LADDER;
        } else if ((safeScaffoldingCell(player, level, origin, start)
                        || safeScaffoldingCell(player, level, origin, end))
                && scaffoldingPathSafe(player, level, origin, start, end)) {
            locomotion = Locomotion.SCAFFOLDING;
        } else {
            locomotion = Locomotion.GROUND;
        }
        if (locomotion != Locomotion.GROUND && drop == Drop.EXCEEDS_WALKING_LIMIT) {
            drop = Drop.AIRBORNE_OR_SWIMMING;
        }
        return new ObservationRecord(
                frame.tick(),
                worldRevision,
                1,
                from,
                requestedTo,
                to,
                support,
                clearance,
                transition,
                fluid,
                suffocation,
                withBlockHazard(
                        hazard(loaded, clearance, fluid, suffocation, drop),
                        damageBlockHazard(
                                level, origin, append(regions, damageSupportRegion(end)))),
                loaded,
                drop,
                locomotion == Locomotion.GROUND && shouldNeutralize(support, drop),
                locomotion);
    }

    static Transition actualTransition(
            AgentMovementTrace.TickMovement frame,
            AgentMovementTrace.CollisionResolution collision) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(collision, "collision");
        if (collision.horizontallyClipped()) {
            return Transition.BLOCKED;
        }
        return frame.contactEvidence(collision)
                ? Transition.CONTACT : Transition.PROBE_ALLOWED;
    }

    private static Support support(
            ClientLevel level,
            LocalPlayer player,
            Point origin,
            AABB box) {
        var slab = supportSlab(box);
        if (loadedState(level, origin, List.of(slab)) == LoadedState.UNKNOWN) {
            return Support.UNKNOWN;
        }
        return level.findSupportingBlock(player, slab).isPresent()
                ? Support.PRESENT
                : Support.ABSENT;
    }

    private static Drop classifyDrop(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB box,
            Support support,
            Fluid fluid,
            boolean currentAabb) {
        return dropEvaluation(
                player, level, origin, box, support, fluid, currentAabb).drop();
    }

    private static DropEvaluation dropEvaluation(
            LocalPlayer player,
            ClientLevel level,
            Point origin,
            AABB box,
            Support support,
            Fluid fluid,
            boolean currentAabb) {
        if (support == Support.PRESENT) {
            return new DropEvaluation(Drop.SUPPORTED, Vec3.ZERO);
        }
        if (support == Support.UNKNOWN) {
            return new DropEvaluation(Drop.UNKNOWN, Vec3.ZERO);
        }
        if (currentAabb && !player.onGround()) {
            return new DropEvaluation(Drop.AIRBORNE_OR_SWIMMING, Vec3.ZERO);
        }
        if (fluid == Fluid.WATER) {
            return new DropEvaluation(Drop.AIRBORNE_OR_SWIMMING, Vec3.ZERO);
        }

        var intended = new Vec3(0.0D, -DROP_PROBE, 0.0D);
        var regions = sweptRegions(SweptAabbPath.segments(box, intended, intended));
        if (loadedState(level, origin, regions) == LoadedState.UNKNOWN) {
            return new DropEvaluation(Drop.UNKNOWN, Vec3.ZERO);
        }
        var entityCollisions = level.getEntityCollisions(player, box.expandTowards(intended));
        var resolved = Entity.collideBoundingBox(player, intended, box, level, entityCollisions);
        double distance = Math.max(0.0D, -resolved.y);
        return new DropEvaluation(
                distance <= MAX_WALKING_DROP + MOVEMENT_EPSILON
                        ? Drop.WITHIN_WALKING_LIMIT
                        : Drop.EXCEEDS_WALKING_LIMIT,
                resolved);
    }

    static Vec3 adjacentCellCenterDelta(AABB start, int x, int z) {
        Objects.requireNonNull(start, "start");
        if ((x == 0 && z == 0) || Math.abs(x) > 1 || Math.abs(z) > 1) {
            throw new IllegalArgumentException("direction must be an adjacent horizontal offset");
        }
        Vec3 center = start.getCenter();
        return new Vec3(
                Mth.floor(center.x) + x + 0.5D - center.x,
                0.0D,
                Mth.floor(center.z) + z + 0.5D - center.z);
    }

    static List<SweptAabbPath.AxisSegment> appendWalkingLandingSegments(
            List<SweptAabbPath.AxisSegment> horizontal,
            AABB horizontalEnd,
            Vec3 resolvedDrop) {
        Objects.requireNonNull(horizontal, "horizontal");
        Objects.requireNonNull(horizontalEnd, "horizontalEnd");
        Objects.requireNonNull(resolvedDrop, "resolvedDrop");
        var result = new ArrayList<>(horizontal);
        result.addAll(SweptAabbPath.segments(
                horizontalEnd,
                new Vec3(0.0D, -DROP_PROBE, 0.0D),
                resolvedDrop));
        return List.copyOf(result);
    }

    private static Fluid fluid(ClientLevel level, Point origin, List<AABB> regions) {
        var result = Fluid.NONE;
        for (var region : regions) {
            int minX = Mth.floor(region.minX);
            int maxX = Mth.floor(Math.nextDown(region.maxX));
            int minY = Mth.floor(region.minY);
            int maxY = Mth.floor(Math.nextDown(region.maxY));
            int minZ = Mth.floor(region.minZ);
            int maxZ = Mth.floor(Math.nextDown(region.maxZ));
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        cursor.set(x, y, z);
                        if (!insideRadius(origin, x, y, z) || !level.isLoaded(cursor)) {
                            return Fluid.UNKNOWN;
                        }
                        var state = level.getFluidState(cursor);
                        if (state.isEmpty()) {
                            continue;
                        }
                        var fluidBox = state.getAABB(level, cursor);
                        if (!region.intersects(fluidBox)) {
                            continue;
                        }
                        var fluidType = state.getFluidType();
                        if (state.is(FluidTags.LAVA)) {
                            return Fluid.LAVA;
                        }
                        if (state.is(FluidTags.WATER) || fluidType.getIsWaterLike()) {
                            if (result == Fluid.NONE) {
                                result = Fluid.WATER;
                            }
                        } else {
                            result = Fluid.UNKNOWN;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static LoadedState loadedState(
            ClientLevel level,
            Point origin,
            List<AABB> regions) {
        for (var region : regions) {
            int minX = Mth.floor(region.minX);
            int maxX = Mth.floor(Math.nextDown(region.maxX));
            int minY = Mth.floor(region.minY);
            int maxY = Mth.floor(Math.nextDown(region.maxY));
            int minZ = Mth.floor(region.minZ);
            int maxZ = Mth.floor(Math.nextDown(region.maxZ));
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        cursor.set(x, y, z);
                        if (!insideRadius(origin, x, y, z) || !level.isLoaded(cursor)) {
                            return LoadedState.UNKNOWN;
                        }
                    }
                }
            }
        }
        return LoadedState.LOADED;
    }

    private static boolean insideRadius(Point origin, int x, int y, int z) {
        var block = new AABB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        return block.distanceToSqr(new Vec3(origin.x(), origin.y(), origin.z()))
                <= RADIUS_SQUARED + MOVEMENT_EPSILON;
    }

    private static Hazard hazard(
            LoadedState loaded,
            Clearance clearance,
            Fluid fluid,
            boolean suffocation,
            Drop drop) {
        if (loaded == LoadedState.UNKNOWN || fluid == Fluid.UNKNOWN || drop == Drop.UNKNOWN) {
            return Hazard.UNKNOWN;
        }
        if (suffocation) {
            return Hazard.SUFFOCATION;
        }
        if (fluid == Fluid.LAVA) {
            return Hazard.LAVA;
        }
        if (drop == Drop.EXCEEDS_WALKING_LIMIT) {
            return Hazard.FALL;
        }
        if (clearance == Clearance.BLOCKED) {
            return Hazard.COLLISION;
        }
        return Hazard.NONE;
    }

    private static Hazard withBlockHazard(Hazard movement, Hazard block) {
        return movement == Hazard.NONE ? block : movement;
    }

    private static Hazard damageBlockHazard(
            ClientLevel level, Point origin, List<AABB> regions) {
        Hazard found = Hazard.NONE;
        for (var region : regions) {
            int minX = Mth.floor(region.minX);
            int maxX = Mth.floor(Math.nextDown(region.maxX));
            int minY = Mth.floor(region.minY);
            int maxY = Mth.floor(Math.nextDown(region.maxY));
            int minZ = Mth.floor(region.minZ);
            int maxZ = Mth.floor(Math.nextDown(region.maxZ));
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        cursor.set(x, y, z);
                        if (!insideRadius(origin, x, y, z) || !level.isLoaded(cursor)) {
                            return Hazard.UNKNOWN;
                        }
                        if (!region.intersects(new AABB(
                                x, y, z, x + 1.0D, y + 1.0D, z + 1.0D))) {
                            continue;
                        }
                        var state = level.getBlockState(cursor);
                        Hazard candidate = Hazard.NONE;
                        if (state.is(Blocks.POWDER_SNOW)) {
                            candidate = Hazard.FREEZING;
                        } else if (state.is(Blocks.FIRE)
                                || state.is(Blocks.SOUL_FIRE)
                                || state.is(Blocks.MAGMA_BLOCK)
                                || (state.hasProperty(BlockStateProperties.LIT)
                                        && state.getValue(BlockStateProperties.LIT)
                                        && (state.is(Blocks.CAMPFIRE)
                                                || state.is(Blocks.SOUL_CAMPFIRE)))) {
                            candidate = Hazard.FIRE_DAMAGE;
                        } else if (state.is(Blocks.CACTUS)
                                || state.is(Blocks.WITHER_ROSE)
                                || state.is(Blocks.SWEET_BERRY_BUSH)
                                        && state.getValue(SweetBerryBushBlock.AGE) > 0) {
                            candidate = Hazard.CONTACT_DAMAGE;
                        }
                        if (candidate != Hazard.NONE) {
                            if (found != Hazard.NONE && found != candidate) {
                                return Hazard.UNKNOWN;
                            }
                            found = candidate;
                        }
                    }
                }
            }
        }
        return found;
    }

    private static boolean shouldNeutralize(Support support, Drop drop) {
        return support != Support.PRESENT
                && (drop == Drop.EXCEEDS_WALKING_LIMIT || drop == Drop.UNKNOWN);
    }

    private static ObservationRecord unknownRecord(
            long tick,
            long worldRevision,
            int depth,
            Point from,
            Point requestedTo,
            Point to,
            Transition transition) {
        return new ObservationRecord(
                tick,
                worldRevision,
                depth,
                from,
                requestedTo,
                to,
                Support.UNKNOWN,
                Clearance.UNKNOWN,
                transition,
                Fluid.UNKNOWN,
                false,
                Hazard.UNKNOWN,
                LoadedState.UNKNOWN,
                Drop.UNKNOWN,
                true);
    }

    private static List<AABB> sweptRegions(List<SweptAabbPath.AxisSegment> segments) {
        return segments.stream().map(SweptAabbPath.AxisSegment::swept).toList();
    }

    private static List<AABB> append(List<AABB> regions, AABB endpoint) {
        var result = new ArrayList<AABB>(regions.size() + 1);
        result.addAll(regions);
        result.add(endpoint);
        return List.copyOf(result);
    }

    private static AABB supportSlab(AABB box) {
        return new AABB(
                box.minX,
                box.minY - SUPPORT_EPSILON,
                box.minZ,
                box.maxX,
                box.minY,
                box.maxZ);
    }

    private static AABB damageSupportRegion(AABB box) {
        return new AABB(
                box.minX,
                box.minY - SUPPORT_EPSILON,
                box.minZ,
                box.maxX,
                box.minY + SUPPORT_EPSILON,
                box.maxZ);
    }

    private static boolean bouncySupport(
            ClientLevel level, LocalPlayer player, AABB endBox) {
        if (player.isSuppressingBounce()) {
            return false;
        }
        return level.findSupportingBlock(player, supportSlab(endBox))
                .map(position -> {
                    var state = level.getBlockState(position);
                    return !state.is(BlockTags.SUPPRESSES_BOUNCE)
                            && state.getBounceRestitution(level, position, player) > 0.0F;
                })
                .orElse(false);
    }

    private static boolean horizontallyClipped(Vec3 intended, Vec3 resolved) {
        return Math.abs(intended.x - resolved.x) > MOVEMENT_EPSILON
                || Math.abs(intended.z - resolved.z) > MOVEMENT_EPSILON;
    }

    private static Point point(Vec3 value) {
        return new Point(value.x, value.y, value.z);
    }

    private static boolean withinVolume(Point origin, ObservationRecord record) {
        return origin.distanceSquared(record.from()) <= RADIUS_SQUARED + MOVEMENT_EPSILON
                && origin.distanceSquared(record.requestedTo())
                        <= RADIUS_SQUARED + MOVEMENT_EPSILON
                && origin.distanceSquared(record.to()) <= RADIUS_SQUARED + MOVEMENT_EPSILON;
    }

    public record Snapshot(
            long observedTick,
            long worldRevision,
            Point center,
            ObservationRecord current,
            List<ObservationRecord> transitions) {
        public Snapshot {
            if (observedTick < 0L) {
                throw new IllegalArgumentException("observedTick must be non-negative");
            }
            requireRevision(worldRevision);
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(current, "current");
            transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
            if (current.transitionDepth() != 0
                    || current.transition() != Transition.STATIONARY) {
                throw new IllegalArgumentException("current record must describe the stationary AABB");
            }
            if (current.worldRevision() != worldRevision
                    || transitions.stream().anyMatch(
                            record -> record.worldRevision() != worldRevision)) {
                throw new IllegalArgumentException("snapshot contains mixed world revisions");
            }
            if (transitions.size() > MAX_OBSERVATIONS
                    || transitions.stream().anyMatch(record -> !withinVolume(center, record))) {
                throw new IllegalArgumentException("snapshot exceeds the fixed local volume");
            }
        }
    }

    private static void requireRevision(long worldRevision) {
        if (worldRevision < UNKNOWN_WORLD_REVISION) {
            throw new IllegalArgumentException(
                    "worldRevision must be non-negative or UNKNOWN");
        }
    }

    public record RecoveryOption(
            Point target,
            ObservationRecord path,
            EndpointSafety endpoint,
            boolean landing) {
        public RecoveryOption {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }

    public enum LandingEvidence { SAFE, LAVA, VOID, UNKNOWN }

    public record EndpointSafety(
            Support support,
            Clearance clearance,
            Fluid fluid,
            boolean suffocation,
            Hazard hazard,
            LoadedState loaded,
            Drop drop) {
        public EndpointSafety {
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(clearance, "clearance");
            Objects.requireNonNull(fluid, "fluid");
            Objects.requireNonNull(hazard, "hazard");
            Objects.requireNonNull(loaded, "loaded");
            Objects.requireNonNull(drop, "drop");
        }
    }

    private record Evaluation(ObservationRecord record, AABB endBox) {
        private Evaluation {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(endBox, "endBox");
        }
    }

    private record DropEvaluation(Drop drop, Vec3 resolvedDelta) {
        private DropEvaluation {
            Objects.requireNonNull(drop, "drop");
            Objects.requireNonNull(resolvedDelta, "resolvedDelta");
            if (!finite(resolvedDelta)) {
                throw new IllegalArgumentException("drop resolution must be finite");
            }
        }
    }

    private record HorizontalDirection(int x, int z) {
        private HorizontalDirection {
            if ((x == 0 && z == 0) || Math.abs(x) > 1 || Math.abs(z) > 1) {
                throw new IllegalArgumentException("direction must be an adjacent horizontal offset");
            }
        }
    }

    private record GridOffset(int x, int z) {
        private GridOffset move(HorizontalDirection direction) {
            return new GridOffset(x + direction.x(), z + direction.z());
        }
    }

    private record ClimbableColumn(int x, int z, Locomotion locomotion) {
        private ClimbableColumn {
            if (locomotion == Locomotion.GROUND) {
                throw new IllegalArgumentException("ground is not a climbable column");
            }
        }
    }

    private record NodeKey(GridOffset offset, long quantizedMinY) {
        private static NodeKey at(GridOffset offset, AABB box) {
            return new NodeKey(offset, Math.round(box.minY / MOVEMENT_EPSILON));
        }
    }

    private record Edge(NodeKey from, HorizontalDirection direction) {
    }

    private record Node(NodeKey key, AABB box, int depth) {
    }
}
