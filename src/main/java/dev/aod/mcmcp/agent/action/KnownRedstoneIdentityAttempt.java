package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.ObservationProvenance;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionRequest;

import java.util.Objects;
import java.util.function.LongFunction;

/** Executes the fixed two-block identity circuit without exposing a generic redstone planner. */
public final class KnownRedstoneIdentityAttempt implements AutoCloseable {
    public static final long MAX_TICKS =
            4L * AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                    + 3L * dev.aod.mcmcp.redstone.RedstoneSpec.MAX_SETTLE_TICKS;

    private final SemanticActionPort port;
    private final RedstoneIdentityRequest request;
    private final LongFunction<MinecraftObservationService.BlockSample> lampObserver;
    private final long deadlineTick;
    private Phase phase = Phase.PLACE_LAMP;
    private KnownBlockMutationAttempt mutation;
    private long observationDeadlineTick;
    private long lastTick;
    private int placed;
    private int interactions;
    private int outputObservations;
    private boolean closed;

    public KnownRedstoneIdentityAttempt(
            SemanticActionPort port,
            RedstoneIdentityRequest request,
            LongFunction<MinecraftObservationService.BlockSample> lampObserver,
            long admittedClientTick,
            long deadlineTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        this.lampObserver = Objects.requireNonNull(lampObserver, "lampObserver");
        if (admittedClientTick < 0L || deadlineTick <= admittedClientTick
                || deadlineTick > saturatedAdd(admittedClientTick, MAX_TICKS)
                || deadlineTick > request.bounds().hardDeadlineClientTick(admittedClientTick)) {
            throw new IllegalArgumentException("redstone deadline exceeds the fixed identity bound");
        }
        this.deadlineTick = deadlineTick;
        lastTick = admittedClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < lastTick) return fail("redstone_non_monotonic_tick", 0, 0);
        lastTick = clientTick;
        if (clientTick >= deadlineTick) return fail("redstone_deadline", 0, 0);
        try {
            return switch (phase) {
                case PLACE_LAMP, PLACE_LEVER, SWITCH_ON, SWITCH_OFF ->
                        tickMutation(clientTick);
                case OBSERVE_INITIAL_OFF -> observeOutput(clientTick, false, Phase.SWITCH_ON);
                case OBSERVE_ON -> observeOutput(clientTick, true, Phase.SWITCH_OFF);
                case OBSERVE_FINAL_OFF -> observeOutput(clientTick, false, null);
            };
        } catch (RuntimeException | LinkageError adapterFailure) {
            return fail("redstone_adapter_failed", 0, 0);
        }
    }

    private TickResult tickMutation(long clientTick) {
        if (mutation == null) {
            long childDeadline = Math.min(
                    deadlineTick,
                    saturatedAdd(clientTick, AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND));
            mutation = new KnownBlockMutationAttempt(
                    port, mutationRequest(), clientTick, childDeadline);
        }
        KnownBlockMutationAttempt.TickResult result = mutation.tick(clientTick);
        if (result.status() == KnownBlockMutationAttempt.Status.RUNNING) {
            return running();
        }
        mutation = null;
        if (result.status() == KnownBlockMutationAttempt.Status.FAILED) {
            return fail("redstone_" + result.evidence(), 0, 0);
        }
        if (!result.performed()) {
            return fail("redstone_transition_not_performed", 0, 0);
        }

        if (phase == Phase.PLACE_LAMP || phase == Phase.PLACE_LEVER) {
            placed++;
            if (phase == Phase.PLACE_LAMP) {
                phase = Phase.PLACE_LEVER;
            } else {
                beginObservation(clientTick, Phase.OBSERVE_INITIAL_OFF);
            }
            return TickResult.running(1, 0, placed, interactions, outputObservations);
        }

        interactions++;
        beginObservation(clientTick,
                phase == Phase.SWITCH_ON ? Phase.OBSERVE_ON : Phase.OBSERVE_FINAL_OFF);
        return TickResult.running(0, 1, placed, interactions, outputObservations);
    }

    private SemanticActionRequest mutationRequest() {
        return switch (phase) {
            case PLACE_LAMP -> request.lampPlacement();
            case PLACE_LEVER -> request.leverPlacement();
            case SWITCH_ON -> request.leverOn();
            case SWITCH_OFF -> request.leverOff();
            default -> throw new IllegalStateException("redstone phase is not a mutation");
        };
    }

    private void beginObservation(long clientTick, Phase observationPhase) {
        phase = observationPhase;
        observationDeadlineTick = saturatedAdd(
                clientTick, request.spec().bounds().settleTicks());
    }

    private TickResult observeOutput(long clientTick, boolean expectedLit, Phase next) {
        OutputObservation observed = outputObservation(lampObserver.apply(clientTick), clientTick, expectedLit);
        if (observed == OutputObservation.TARGET_CHANGED) {
            return fail("redstone_output_changed", 0, 0);
        }
        if (observed == OutputObservation.MATCHED) {
            outputObservations++;
            if (next == null) {
                TickResult result = TickResult.succeeded(
                        placed, interactions, outputObservations);
                close();
                return result;
            }
            phase = next;
            return TickResult.running(0, 0, placed, interactions, outputObservations);
        }
        if (clientTick >= observationDeadlineTick) {
            return fail("redstone_output_not_observed", 0, 0);
        }
        return running();
    }

    private OutputObservation outputObservation(
            MinecraftObservationService.BlockSample sample,
            long clientTick,
            boolean expectedLit) {
        Objects.requireNonNull(sample, "lamp observation");
        BlockTarget target = request.lampTarget();
        BlockPosition expectedPosition = new BlockPosition(
                target.dimension(), target.x(), target.y(), target.z());
        if (!expectedPosition.equals(sample.position())) return OutputObservation.TARGET_CHANGED;
        if (sample.outcome() != MinecraftObservationService.BlockOutcome.CURRENT) {
            return OutputObservation.PENDING;
        }
        var observation = Objects.requireNonNull(sample.observation(), "current lamp observation");
        if (sample.currentTick() != clientTick
                || observation.observedAtClientTick() != clientTick
                || !request.worldSessionId().equals(observation.worldSessionId())
                || !expectedPosition.equals(observation.position())
                || sample.visibleFaces().isEmpty()
                || observation.provenance() != ObservationProvenance.LINE_OF_SIGHT_OBSERVATION
                        && observation.provenance() != ObservationProvenance.CROSSHAIR_OBSERVATION) {
            return OutputObservation.PENDING;
        }
        if (!"minecraft:redstone_lamp".equals(observation.state().block())) {
            return OutputObservation.TARGET_CHANGED;
        }
        String lit = observation.state().properties().get("lit");
        if (!"true".equals(lit) && !"false".equals(lit)) {
            return OutputObservation.TARGET_CHANGED;
        }
        return Boolean.toString(expectedLit).equals(lit)
                ? OutputObservation.MATCHED : OutputObservation.PENDING;
    }

    private TickResult fail(String evidence, int placedDelta, int interactionDelta) {
        TickResult result = TickResult.failed(
                evidence, placedDelta, interactionDelta,
                placed, interactions, outputObservations);
        close();
        return result;
    }

    private TickResult running() {
        return TickResult.running(0, 0, placed, interactions, outputObservations);
    }

    @Override
    public void close() {
        if (closed) return;
        if (mutation != null) mutation.close();
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("redstone attempt is closed");
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private enum Phase {
        PLACE_LAMP,
        PLACE_LEVER,
        OBSERVE_INITIAL_OFF,
        SWITCH_ON,
        OBSERVE_ON,
        SWITCH_OFF,
        OBSERVE_FINAL_OFF
    }

    private enum OutputObservation { MATCHED, PENDING, TARGET_CHANGED }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public record TickResult(
            Status status,
            String evidence,
            int placedDelta,
            int interactionDelta,
            int placed,
            int interactions,
            int outputObservations) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            if (placedDelta < 0 || placedDelta > 1
                    || interactionDelta < 0 || interactionDelta > 1
                    || placed < 0 || placed > 2
                    || interactions < 0 || interactions > 2
                    || outputObservations < 0 || outputObservations > 3) {
                throw new IllegalArgumentException("redstone progress is outside the identity bound");
            }
        }

        private static TickResult running(
                int placedDelta,
                int interactionDelta,
                int placed,
                int interactions,
                int outputObservations) {
            return new TickResult(
                    Status.RUNNING, null, placedDelta, interactionDelta,
                    placed, interactions, outputObservations);
        }

        private static TickResult succeeded(
                int placed, int interactions, int outputObservations) {
            return new TickResult(
                    Status.SUCCEEDED, null, 0, 0,
                    placed, interactions, outputObservations);
        }

        private static TickResult failed(
                String evidence,
                int placedDelta,
                int interactionDelta,
                int placed,
                int interactions,
                int outputObservations) {
            return new TickResult(
                    Status.FAILED, Objects.requireNonNull(evidence, "evidence"),
                    placedDelta, interactionDelta, placed, interactions, outputObservations);
        }
    }
}
