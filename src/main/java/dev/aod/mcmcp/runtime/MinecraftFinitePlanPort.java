package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockOutcome;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSource;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.FinitePlanPort;
import dev.aod.mcmcp.routine.FinitePlanRequest;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.SemanticActionAttempt;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionPreparationAttempt;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Minecraft adapter that delegates each finite-plan action to an existing typed port. */
final class MinecraftFinitePlanPort implements FinitePlanPort {
    private static final String CHILD_COMPLETION_INTENT = "finish_goal";
    private static final String CHILD_IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000000";
    private static final double NAVIGATION_SETTLED_VELOCITY_SQUARED = 0.03D * 0.03D;

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final WorldMemory memory;
    private final MinecraftObservationService observations;
    private final SemanticActionPort semanticActions;
    private final PhaseFivePort phaseFive;
    private final Map<ActionAttempt, ActiveAction> actions = new IdentityHashMap<>();

    MinecraftFinitePlanPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            SemanticActionPort semanticActions,
            PhaseFivePort phaseFive) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.semanticActions = Objects.requireNonNull(semanticActions, "semanticActions");
        this.phaseFive = Objects.requireNonNull(phaseFive, "phaseFive");
    }

    /** Fails invalid child requests before the parent routine acquires automation ownership. */
    void validate(FinitePlanRequest request) {
        Objects.requireNonNull(request, "request");
        var minecraft = assertClientThread();
        String dimension = requireSession().dimension();
        visitActions(request.steps(), action -> {
            var compiled = compile(action, dimension);
            if (compiled.semantic != null) {
                McmcpRuntime.validateLiveBounds(
                        minecraft, compiled.semantic.bounds(), semanticTarget(compiled.semantic));
            } else {
                McmcpRuntime.validateLiveBounds(
                        minecraft, compiled.phaseFive.bounds(), compiled.targets);
            }
        });
    }

    @Override
    public Frame observe(FinitePlanRequest request) {
        assertClientThread();
        Objects.requireNonNull(request, "request");
        var session = sessionSupplier.get();
        var minecraft = minecraftSupplier.get();
        boolean ready = session != null && session.worldReady()
                && minecraft != null && minecraft.level != null && minecraft.player != null
                && minecraft.gameMode != null && minecraft.getConnection() != null;
        return new Frame(
                session == null ? 0L : Math.max(0L, session.clientTick()),
                Math.max(0L, memory.revision()),
                ready ? null : failure(
                        RoutineFailure.Category.EXTERNAL,
                        "PLAN_WORLD_UNAVAILABLE",
                        RoutineFailure.Recovery.REPLAN,
                        RoutineFailure.Scope.ROUTINE,
                        Map.of("world_ready", true),
                        Map.of("world_ready", false),
                        Map.of()));
    }

    @Override
    public ConditionEvidence evaluate(FinitePlanRequest.Condition condition) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(condition, "condition");
        var session = sessionSupplier.get();
        long tick = session == null ? 0L : Math.max(0L, session.clientTick());
        long revision = Math.max(0L, memory.revision());
        if (session == null || !session.worldReady()
                || minecraft.player == null || minecraft.level == null) {
            return new ConditionEvidence(tick, revision, ConditionStatus.UNKNOWN,
                    Map.of("reason", "world_unavailable"));
        }
        if (condition instanceof FinitePlanRequest.InventoryAtLeast inventory) {
            int count = inventoryCount(minecraft, inventory.item());
            return new ConditionEvidence(
                    tick,
                    revision,
                    count >= inventory.minimumCount()
                            ? ConditionStatus.SATISFIED : ConditionStatus.UNSATISFIED,
                    Map.of("item", inventory.item(), "count", count,
                            "minimum_count", inventory.minimumCount()));
        }

        var block = (FinitePlanRequest.BlockMatches) condition;
        if (!block.target().dimension().equals(session.dimension())) {
            return new ConditionEvidence(tick, revision, ConditionStatus.UNKNOWN,
                    Map.of("reason", "different_dimension"));
        }
        var sample = observations.observeBlocks(
                minecraft,
                tick,
                List.of(new BlockPosition(
                        block.target().dimension(), block.target().x(),
                        block.target().y(), block.target().z())),
                BlockSource.LIVE).getFirst();
        revision = Math.max(revision, memory.revision());
        if (sample.outcome() != BlockOutcome.CURRENT || sample.observation() == null) {
            return new ConditionEvidence(tick, revision, ConditionStatus.UNKNOWN,
                    Map.of("outcome", sample.outcome().wireName()));
        }
        var state = sample.observation().state();
        var actual = new BlockStateFingerprint(state.block(), state.properties());
        return new ConditionEvidence(
                tick,
                revision,
                block.expectedState().matches(actual)
                        ? ConditionStatus.SATISFIED : ConditionStatus.UNSATISFIED,
                Map.of("block", actual.blockId(), "properties", actual.properties()));
    }

    @Override
    public ActionAttempt begin(
            UUID parentRoutineId,
            FinitePlanRequest.Action action,
            long hardDeadlineClientTick) {
        assertClientThread();
        Objects.requireNonNull(parentRoutineId, "parentRoutineId");
        Objects.requireNonNull(action, "action");
        var session = requireSession();
        UUID attemptId = UUID.randomUUID();
        CompiledAction compiled = compile(action, session.dimension());
        long childDeadline = Math.min(
                hardDeadlineClientTick,
                compiled.deadline(session.clientTick()));
        var state = new ActiveAction(action, compiled, childDeadline);
        if (childDeadline <= session.clientTick()) {
            state.failure = childDeadlineFailure(childDeadline, session.clientTick());
        } else if (compiled.semantic != null) {
            if (compiled.semantic instanceof NavigateToRequest) {
                state.semanticAttempt = semanticActions.dispatch(compiled.semantic, childDeadline);
            } else {
                state.preparation = semanticActions.beginPreparation(
                        compiled.semantic, childDeadline);
            }
        } else {
            var frame = phaseFive.observe(compiled.phaseFive);
            if (frame.failure() != null) {
                state.failure = frame.failure();
            } else {
                state.phaseFiveAttempt = phaseFive.begin(
                        attemptId, compiled.phaseFive, childDeadline);
            }
        }
        var result = new ActionAttempt(
                attemptId,
                parentRoutineId,
                action.id(),
                session.clientTick(),
                memory.revision(),
                hardDeadlineClientTick);
        actions.put(result, state);
        return result;
    }

    @Override
    public void maintain(ActionAttempt attempt) {
        assertClientThread();
        var active = requireAction(attempt);
        if (active.failure != null) {
            return;
        }
        long tick = currentTick();
        if (tick >= active.childDeadline) {
            active.failure = childDeadlineFailure(active.childDeadline, tick);
            return;
        }
        if (active.preparation != null && active.semanticAttempt == null) {
            var evidence = semanticActions.preparationEvidence(active.preparation);
            if (evidence.failure() != null) {
                active.failure = evidence.failure();
            } else if (evidence.prepared()) {
                active.semanticAttempt = semanticActions.dispatchPrepared(
                        active.compiled.semantic, active.preparation, active.childDeadline);
                semanticActions.releasePreparation(active.preparation);
                active.preparation = null;
            } else {
                semanticActions.maintainPreparation(active.preparation);
            }
        } else if (active.semanticAttempt != null) {
            semanticActions.maintain(active.semanticAttempt);
        } else if (active.phaseFiveAttempt != null) {
            phaseFive.maintain(active.phaseFiveAttempt);
        }
    }

    @Override
    public ActionEvidence evidence(ActionAttempt attempt) {
        assertClientThread();
        var active = requireAction(attempt);
        long tick = currentTick();
        if (active.failure == null && tick >= active.childDeadline) {
            active.failure = childDeadlineFailure(active.childDeadline, tick);
        }
        if (active.failure != null) {
            return actionEvidence(attempt, tick, false, active.failure,
                    Map.of("stage", active.stage()));
        }
        if (active.preparation != null && active.semanticAttempt == null) {
            var prepared = semanticActions.preparationEvidence(active.preparation);
            if (prepared.failure() != null) {
                active.failure = prepared.failure();
            }
            return actionEvidence(attempt, prepared.clientTick(), false, active.failure,
                    Map.of("stage", "prepare", "prepared", prepared.prepared()));
        }
        if (active.semanticAttempt != null) {
            var observed = semanticActions.evidence(active.semanticAttempt);
            boolean confirmed = observed.failure() == null
                    && semanticConfirmed(active.compiled.semantic, observed);
            return new ActionEvidence(
                    attempt.attemptId(), observed.clientTick(), observed.observationRevision(),
                    confirmed, observed.failure(), observed.basis());
        }

        var observed = phaseFive.evidence(active.phaseFiveAttempt);
        return switch (observed) {
            case PhaseFiveEvidence.Pending pending -> new ActionEvidence(
                    attempt.attemptId(), pending.clientTick(),
                    planRevision(attempt),
                    false, null, pending.basis());
            case PhaseFiveEvidence.ServerConfirmed confirmed -> new ActionEvidence(
                    attempt.attemptId(), confirmed.clientTick(),
                    planRevision(attempt),
                    confirmed.result().goalVerified(),
                    confirmed.result().goalVerified() ? null : failure(
                            RoutineFailure.Category.DIVERGENCE,
                            "PLAN_CHILD_GOAL_NOT_VERIFIED",
                            RoutineFailure.Recovery.REPLAN,
                            RoutineFailure.Scope.STEP,
                            Map.of("goal_verified", true),
                            Map.of("goal_verified", false),
                            confirmed.basis()),
                    confirmed.basis());
            case PhaseFiveEvidence.Failed failed -> new ActionEvidence(
                    attempt.attemptId(), failed.clientTick(),
                    planRevision(attempt),
                    false, failed.failure(), failed.basis());
            case PhaseFiveEvidence.Inconclusive inconclusive -> new ActionEvidence(
                    attempt.attemptId(), inconclusive.clientTick(),
                    planRevision(attempt),
                    false, failure(
                            RoutineFailure.Category.DIVERGENCE,
                            "PLAN_CHILD_INCONCLUSIVE",
                            RoutineFailure.Recovery.REPLAN,
                            RoutineFailure.Scope.STEP,
                            Map.of("certainty", "server_confirmed"),
                            Map.of("certainty", inconclusive.certainty().name().toLowerCase()),
                            inconclusive.basis()),
                    inconclusive.basis());
        };
    }

    @Override
    public void release(ActionAttempt attempt) {
        assertClientThread();
        Objects.requireNonNull(attempt, "attempt");
        var active = actions.get(attempt);
        if (active == null) {
            return;
        }
        if (active.compiled.semantic != null) {
            try {
                if (active.preparation != null) {
                    semanticActions.releasePreparation(active.preparation);
                }
                if (active.semanticAttempt != null) {
                    semanticActions.release(active.semanticAttempt);
                }
            } finally {
                semanticActions.retire(active.compiled.semantic);
            }
        } else {
            try {
                if (active.phaseFiveAttempt != null) {
                    phaseFive.release(active.phaseFiveAttempt);
                }
            } finally {
                phaseFive.retire(active.compiled.phaseFive);
            }
        }
        actions.remove(attempt, active);
    }

    @Override
    public void retire(FinitePlanRequest request) {
        assertClientThread();
        Objects.requireNonNull(request, "request");
        for (var attempt : List.copyOf(actions.keySet())) {
            release(attempt);
        }
    }

    void clearSession() {
        actions.clear();
    }

    private CompiledAction compile(FinitePlanRequest.Action action, String dimension) {
        var full = new LinkedHashMap<String, Object>();
        full.put("kind", action.kind().wireName());
        full.put("parameters", action.arguments().get("parameters"));
        full.put("bounds", action.arguments().get("bounds"));
        full.put("completion_intent", CHILD_COMPLETION_INTENT);
        full.put("idempotency_key", CHILD_IDEMPOTENCY_KEY);
        if (PhaseFiveRequest.KINDS.contains(action.kind().wireName())) {
            var parsed = McmcpRuntime.phaseFiveRequestArgument(full, dimension);
            return new CompiledAction(
                    null,
                    parsed.request(),
                    parsed.targets());
        }
        return new CompiledAction(
                McmcpRuntime.semanticActionArgument(full, dimension), null, List.of());
    }

    private static BlockTarget semanticTarget(SemanticActionRequest request) {
        return switch (request) {
            case NavigateToRequest action -> action.target();
            case BreakBlockRequest action -> action.target();
            case PlaceBlockRequest action -> action.target();
            case InteractBlockRequest action -> action.target();
            case UseItemOnBlockRequest action -> action.target();
            default -> throw new IllegalArgumentException(
                    "unsupported finite-plan semantic action");
        };
    }

    private static boolean semanticConfirmed(
            SemanticActionRequest request,
            dev.aod.mcmcp.routine.SemanticActionEvidence evidence) {
        if (request instanceof NavigateToRequest navigation) {
            // The adapter only acknowledges navigation after a server-reconciled settle window.
            return evidence.acknowledged();
        }
        BlockStateFingerprint expected = switch (request) {
            case dev.aod.mcmcp.routine.BreakBlockRequest block -> block.expectedAfter();
            case dev.aod.mcmcp.routine.PlaceBlockRequest block -> block.expectedAfter();
            case dev.aod.mcmcp.routine.UseItemOnBlockRequest block -> block.expectedAfter();
            case dev.aod.mcmcp.routine.InteractBlockRequest block -> block.expectedAfter();
            default -> throw new IllegalArgumentException("unsupported finite-plan semantic action");
        };
        return evidence.acknowledged()
                && evidence.serverBlockState().filter(expected::matches).isPresent();
    }

    private ActionEvidence actionEvidence(
            ActionAttempt attempt,
            long tick,
            boolean confirmed,
            RoutineFailure failure,
            Map<String, Object> basis) {
        return new ActionEvidence(
                attempt.attemptId(), tick, Math.max(memory.revision(),
                attempt.issuedObservationRevision()), confirmed, failure, basis);
    }

    private long planRevision(ActionAttempt attempt) {
        return Math.max(memory.revision(), attempt.issuedObservationRevision());
    }

    private ActiveAction requireAction(ActionAttempt attempt) {
        var result = actions.get(Objects.requireNonNull(attempt, "attempt"));
        if (result == null) {
            throw new IllegalStateException("finite-plan action is not active");
        }
        return result;
    }

    private WorldSessionTracker.Snapshot requireSession() {
        var session = Objects.requireNonNull(sessionSupplier.get(), "world session is unavailable");
        if (!session.worldReady()) {
            throw new IllegalStateException("world session is not ready");
        }
        return session;
    }

    private Minecraft assertClientThread() {
        var minecraft = Objects.requireNonNull(
                minecraftSupplier.get(), "Minecraft client is not initialized");
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("finite-plan adapter must run on the Minecraft client thread");
        }
        return minecraft;
    }

    private long currentTick() {
        var session = sessionSupplier.get();
        return session == null ? 0L : Math.max(0L, session.clientTick());
    }

    private static int inventoryCount(Minecraft minecraft, String itemId) {
        int count = 0;
        var inventory = Objects.requireNonNull(minecraft.player).getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                count = Math.min(2_304, count + stack.getCount());
            }
        }
        return count;
    }

    private static void visitActions(
            List<FinitePlanRequest.Step> steps,
            java.util.function.Consumer<FinitePlanRequest.Action> visitor) {
        for (var step : steps) {
            if (step instanceof FinitePlanRequest.Action action) {
                visitor.accept(action);
            } else if (step instanceof FinitePlanRequest.RepeatUntil repeat) {
                visitActions(repeat.steps(), visitor);
            }
        }
    }

    private static RoutineFailure childDeadlineFailure(long deadline, long tick) {
        return failure(
                RoutineFailure.Category.TRANSIENT,
                "PLAN_CHILD_DEADLINE_EXPIRED",
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of("before_client_tick", deadline),
                Map.of("client_tick", tick),
                Map.of());
    }

    private static RoutineFailure failure(
            RoutineFailure.Category category,
            String code,
            RoutineFailure.Recovery recovery,
            RoutineFailure.Scope scope,
            Map<String, Object> expected,
            Map<String, Object> observed,
            Map<String, Object> evidence) {
        return new RoutineFailure(
                category, code, false, recovery, scope, 1,
                expected, observed, evidence,
                List.of("player", "target", "inventory"), false);
    }

    private record CompiledAction(
            SemanticActionRequest semantic,
            PhaseFiveRequest phaseFive,
            List<BlockTarget> targets) {
        private CompiledAction {
            if ((semantic == null) == (phaseFive == null)) {
                throw new IllegalArgumentException("a plan child must use exactly one action port");
            }
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }

        long deadline(long admittedTick) {
            return semantic != null
                    ? semantic.bounds().hardDeadlineClientTick(admittedTick)
                    : phaseFive.bounds().hardDeadlineClientTick(admittedTick);
        }
    }

    private static final class ActiveAction {
        private final FinitePlanRequest.Action action;
        private final CompiledAction compiled;
        private final long childDeadline;
        private SemanticActionPreparationAttempt preparation;
        private SemanticActionAttempt semanticAttempt;
        private PhaseFiveAttempt phaseFiveAttempt;
        private RoutineFailure failure;

        private ActiveAction(
                FinitePlanRequest.Action action,
                CompiledAction compiled,
                long childDeadline) {
            this.action = action;
            this.compiled = compiled;
            this.childDeadline = childDeadline;
        }

        private String stage() {
            if (preparation != null && semanticAttempt == null) return "prepare";
            if (semanticAttempt != null || phaseFiveAttempt != null) return "execute";
            return "preflight";
        }
    }
}
