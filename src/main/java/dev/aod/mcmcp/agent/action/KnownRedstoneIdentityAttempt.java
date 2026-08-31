package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.ObservationProvenance;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;

/** Executes the fixed one- or two-output identity circuit without a generic redstone planner. */
public final class KnownRedstoneIdentityAttempt implements AutoCloseable {
    public static final long MAX_TICKS =
            5L * AgentPrimitivePlanner.BLOCK_MUTATION_TICK_UPPER_BOUND
                    + 3L * dev.aod.mcmcp.redstone.RedstoneSpec.MAX_SETTLE_TICKS;

    private final SemanticActionPort port;
    private final RedstoneIdentityRequest request;
    private final LongFunction<List<MinecraftObservationService.BlockSample>> lampObserver;
    private final LongFunction<MinecraftObservationService.BlockSample> leverObserver;
    private final LongFunction<MinecraftObservationService.BlockSample> wireObserver;
    private final LongFunction<List<MinecraftObservationService.BlockSample>> haloObserver;
    private final long deadlineTick;
    private Phase phase = Phase.PLACE_LAMPS;
    private KnownBlockMutationAttempt mutation;
    private int nextLampIndex;
    private long observationDeadlineTick;
    private long lastTick;
    private int placed;
    private int interactions;
    private int outputObservations;
    private boolean closed;

    public KnownRedstoneIdentityAttempt(
            SemanticActionPort port,
            RedstoneIdentityRequest request,
            LongFunction<List<MinecraftObservationService.BlockSample>> lampObserver,
            LongFunction<MinecraftObservationService.BlockSample> leverObserver,
            LongFunction<List<MinecraftObservationService.BlockSample>> haloObserver,
            long admittedClientTick,
            long deadlineTick) {
        this(port, request, lampObserver, leverObserver, ignored -> null, haloObserver,
                admittedClientTick, deadlineTick);
    }

    public KnownRedstoneIdentityAttempt(
            SemanticActionPort port,
            RedstoneIdentityRequest request,
            LongFunction<List<MinecraftObservationService.BlockSample>> lampObserver,
            LongFunction<MinecraftObservationService.BlockSample> leverObserver,
            LongFunction<MinecraftObservationService.BlockSample> wireObserver,
            LongFunction<List<MinecraftObservationService.BlockSample>> haloObserver,
            long admittedClientTick,
            long deadlineTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        this.lampObserver = Objects.requireNonNull(lampObserver, "lampObserver");
        this.leverObserver = Objects.requireNonNull(leverObserver, "leverObserver");
        this.wireObserver = Objects.requireNonNull(wireObserver, "wireObserver");
        this.haloObserver = Objects.requireNonNull(haloObserver, "haloObserver");
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
                case PLACE_LAMPS, PLACE_LEVER, PLACE_WIRE, SWITCH_ON, SWITCH_OFF ->
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
            // This closed action never mutates the lever halo; prove it once before input lock
            // starts the first placement, then let each child verify its own exact target.
            if (phase == Phase.PLACE_LAMPS && nextLampIndex == 0
                    && !haloClear(haloObserver.apply(clientTick), clientTick)) {
                return fail("redstone_clearance_changed", 0, 0);
            }
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

        if (phase == Phase.PLACE_LAMPS
                || phase == Phase.PLACE_LEVER
                || phase == Phase.PLACE_WIRE) {
            placed++;
            if (phase == Phase.PLACE_LAMPS) {
                nextLampIndex++;
                if (nextLampIndex == request.lampTargets().size()) {
                    phase = Phase.PLACE_LEVER;
                }
            } else if (phase == Phase.PLACE_LEVER && request.wireTarget().isPresent()) {
                phase = Phase.PLACE_WIRE;
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
            case PLACE_LAMPS -> request.lampPlacements().get(nextLampIndex);
            case PLACE_LEVER -> request.leverPlacement();
            case PLACE_WIRE -> request.wirePlacement().orElseThrow();
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
        OutputObservation observed = outputObservation(
                lampObserver.apply(clientTick),
                leverObserver.apply(clientTick),
                wireObserver.apply(clientTick),
                clientTick,
                expectedLit);
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
            List<MinecraftObservationService.BlockSample> lampSamples,
            MinecraftObservationService.BlockSample leverSample,
            MinecraftObservationService.BlockSample wireSample,
            long clientTick,
            boolean expectedLit) {
        Objects.requireNonNull(lampSamples, "lamp observations");
        if (lampSamples.size() != request.lampTargets().size()) {
            return OutputObservation.PENDING;
        }
        OutputObservation lever = observedProperty(
                leverSample,
                request.leverTarget(),
                clientTick,
                "minecraft:lever",
                "powered",
                expectedLit,
                true);
        if (lever == OutputObservation.TARGET_CHANGED) {
            return OutputObservation.TARGET_CHANGED;
        }
        OutputObservation wire = observedWire(wireSample, clientTick, expectedLit ? 15 : 0);
        if (wire == OutputObservation.TARGET_CHANGED) {
            return OutputObservation.TARGET_CHANGED;
        }
        boolean allMatched = lever == OutputObservation.MATCHED
                && wire == OutputObservation.MATCHED;
        for (int index = 0; index < lampSamples.size(); index++) {
            OutputObservation lamp = observedProperty(
                    lampSamples.get(index),
                    request.lampTargets().get(index),
                    clientTick,
                    "minecraft:redstone_lamp",
                    "lit",
                    expectedLit,
                    false);
            if (lamp == OutputObservation.TARGET_CHANGED) {
                return OutputObservation.TARGET_CHANGED;
            }
            allMatched &= lamp == OutputObservation.MATCHED;
        }
        return allMatched ? OutputObservation.MATCHED : OutputObservation.PENDING;
    }

    private OutputObservation observedWire(
            MinecraftObservationService.BlockSample sample,
            long clientTick,
            int expectedPower) {
        if (request.wireTarget().isEmpty()) {
            return sample == null ? OutputObservation.MATCHED : OutputObservation.TARGET_CHANGED;
        }
        BlockTarget target = request.wireTarget().orElseThrow();
        OutputObservation block = observedBlock(
                sample, target, clientTick, "minecraft:redstone_wire");
        if (block != OutputObservation.MATCHED) return block;
        Map<String, String> expected = request.wireState(expectedPower).properties();
        Map<String, String> actual = Objects.requireNonNull(
                sample.observation(), "current wire observation").state().properties();
        for (String direction : List.of("north", "east", "south", "west")) {
            if (!expected.get(direction).equals(actual.get(direction))) {
                return OutputObservation.TARGET_CHANGED;
            }
        }
        String power = actual.get("power");
        if (power == null || !power.matches("(?:[0-9]|1[0-5])")) {
            return OutputObservation.TARGET_CHANGED;
        }
        return Integer.toString(expectedPower).equals(power)
                ? OutputObservation.MATCHED : OutputObservation.PENDING;
    }

    private OutputObservation observedProperty(
            MinecraftObservationService.BlockSample sample,
            BlockTarget target,
            long clientTick,
            String block,
            String property,
            boolean expected,
            boolean mismatchIsChanged) {
        OutputObservation blockObservation = observedBlock(sample, target, clientTick, block);
        if (blockObservation != OutputObservation.MATCHED) return blockObservation;
        String value = Objects.requireNonNull(sample.observation(), "current redstone observation")
                .state().properties().get(property);
        if (!"true".equals(value) && !"false".equals(value)) {
            return OutputObservation.TARGET_CHANGED;
        }
        if (Boolean.toString(expected).equals(value)) return OutputObservation.MATCHED;
        return mismatchIsChanged ? OutputObservation.TARGET_CHANGED : OutputObservation.PENDING;
    }

    private OutputObservation observedBlock(
            MinecraftObservationService.BlockSample sample,
            BlockTarget target,
            long clientTick,
            String block) {
        Objects.requireNonNull(sample, "redstone observation");
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
                || (!"minecraft:air".equals(block) && sample.visibleFaces().isEmpty())
                || observation.provenance() != ObservationProvenance.LINE_OF_SIGHT_OBSERVATION
                        && observation.provenance() != ObservationProvenance.CROSSHAIR_OBSERVATION) {
            return OutputObservation.PENDING;
        }
        if (!block.equals(observation.state().block())) {
            return OutputObservation.TARGET_CHANGED;
        }
        return OutputObservation.MATCHED;
    }

    private boolean haloClear(
            List<MinecraftObservationService.BlockSample> samples, long clientTick) {
        Objects.requireNonNull(samples, "redstone clearance observations");
        Map<BlockTarget, BlockStateFingerprint> expected = request.safetyEnvelope();
        List<BlockTarget> targets = List.copyOf(expected.keySet());
        if (samples.size() != targets.size()) return false;
        var byPosition = new HashMap<BlockPosition, MinecraftObservationService.BlockSample>();
        for (var sample : samples) {
            if (sample == null || byPosition.put(sample.position(), sample) != null) return false;
        }
        for (BlockTarget target : targets) {
            var position = new BlockPosition(
                    target.dimension(), target.x(), target.y(), target.z());
            var sample = byPosition.get(position);
            if (sample == null
                    || observedBlock(
                            sample, target, clientTick, expected.get(target).blockId())
                            != OutputObservation.MATCHED
                    || !expected.get(target).matches(new BlockStateFingerprint(
                            sample.observation().state().block(),
                            sample.observation().state().properties()))) {
                return false;
            }
        }
        return true;
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
        PLACE_LAMPS,
        PLACE_LEVER,
        PLACE_WIRE,
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
                    || placed < 0 || placed > 3
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
