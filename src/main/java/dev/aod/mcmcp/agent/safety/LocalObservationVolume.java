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
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public static final double RADIUS_BLOCKS = 4.0D;
    public static final int MAX_TRANSITIONS = 6;

    private static final double RADIUS_SQUARED = RADIUS_BLOCKS * RADIUS_BLOCKS;
    private static final double SUPPORT_EPSILON = 1.0E-6D;
    private static final double MOVEMENT_EPSILON = 1.0E-7D;
    private static final double MAX_WALKING_DROP = 1.0D;
    private static final double DROP_PROBE = 4.0D;
    private static final int MAX_OBSERVATIONS = 256;
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
                source.neutralizeAgentHorizontal());
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

    private static boolean intendedTowardNavigationTarget(
            Point start, Vec3 intended, AgentInputState.NavigationIntent intent) {
        return intended.x * (intent.target().x - start.x())
                        + intended.z * (intent.target().z - start.z())
                >= -MOVEMENT_EPSILON;
    }

    private static boolean movesTowardNavigationTarget(
            Point start, Point end, AgentInputState.NavigationIntent intent) {
        var target = point(intent.target());
        double horizontalBefore = square(start.x() - target.x()) + square(start.z() - target.z());
        double horizontalAfter = square(end.x() - target.x()) + square(end.z() - target.z());
        if (horizontalAfter + MOVEMENT_EPSILON < horizontalBefore) return true;
        return intent.verticalDelta() > 0
                && start.y() + MOVEMENT_EPSILON < target.y()
                && end.y() > start.y() + MOVEMENT_EPSILON
                || intent.verticalDelta() < 0
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
        var rootOffset = new GridOffset(0, 0);
        var rootKey = NodeKey.at(rootOffset, originBox);
        reached.add(rootKey);
        queue.addLast(new Node(rootKey, originBox, 0));

        while (!queue.isEmpty() && records.size() < MAX_OBSERVATIONS) {
            var node = queue.removeFirst();
            if (node.depth() >= MAX_TRANSITIONS) {
                continue;
            }
            for (var direction : DIRECTIONS) {
                var targetOffset = node.key().offset().move(direction);
                var edge = new Edge(node.key(), direction);
                if (!attempted.add(edge)) {
                    continue;
                }
                var intended = new Vec3(direction.x(), 0.0D, direction.z());
                var targetCenter = point(node.box().move(intended).getCenter());
                if (origin.distanceSquared(targetCenter) > RADIUS_SQUARED) {
                    continue;
                }

                var evaluation = evaluateHypothetical(
                        player,
                        level,
                        origin,
                        node.box(),
                        intended,
                        node.depth() + 1,
                        worldRevision);
                if (evaluation.record().canExpand()
                        && withinVolume(origin, evaluation.record())) {
                    var targetKey = NodeKey.at(targetOffset, evaluation.endBox());
                    if (!reached.add(targetKey)) {
                        continue;
                    }
                    records.add(evaluation.record());
                    queue.addLast(new Node(targetKey, evaluation.endBox(), node.depth() + 1));
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
        return evaluateResolvedHypothetical(
                player, level, origin, start, intended, resolved, depth, worldRevision);
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

        var segments = SweptAabbPath.segments(start, intended, resolved);
        var end = start.move(resolved);
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

        boolean clipped = horizontallyClipped(intended, resolved);
        var clearance = !clipped && level.noCollision(player, end)
                ? Clearance.CLEAR
                : Clearance.BLOCKED;
        var support = support(level, player, origin, end);
        var fluid = fluid(level, origin, regions);
        var suffocation = level.collidesWithSuffocatingBlock(player, end);
        var drop = classifyDrop(player, level, origin, end, support, fluid, false);
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
                shouldNeutralize(support, drop));
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
        if (support == Support.PRESENT) {
            return Drop.SUPPORTED;
        }
        if (support == Support.UNKNOWN) {
            return Drop.UNKNOWN;
        }
        if (currentAabb && !player.onGround()) {
            return Drop.AIRBORNE_OR_SWIMMING;
        }
        if (fluid == Fluid.WATER) {
            return Drop.AIRBORNE_OR_SWIMMING;
        }

        var intended = new Vec3(0.0D, -DROP_PROBE, 0.0D);
        var regions = sweptRegions(SweptAabbPath.segments(box, intended, intended));
        if (loadedState(level, origin, regions) == LoadedState.UNKNOWN) {
            return Drop.UNKNOWN;
        }
        var entityCollisions = level.getEntityCollisions(player, box.expandTowards(intended));
        var resolved = Entity.collideBoundingBox(player, intended, box, level, entityCollisions);
        double distance = Math.max(0.0D, -resolved.y);
        return distance <= MAX_WALKING_DROP + MOVEMENT_EPSILON
                ? Drop.WITHIN_WALKING_LIMIT
                : Drop.EXCEEDS_WALKING_LIMIT;
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
