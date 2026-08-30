package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.ObservationProvenance;
import dev.aod.mcmcp.observation.ObservedBlock;
import dev.aod.mcmcp.observation.ObservedContext;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.BlockAimWitness;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.SemanticActionAttempt;
import dev.aod.mcmcp.routine.SemanticActionEvidence;
import dev.aod.mcmcp.routine.SemanticActionFrame;
import dev.aod.mcmcp.routine.SemanticActionPort;
import dev.aod.mcmcp.routine.SemanticActionPreparationAttempt;
import dev.aod.mcmcp.routine.SemanticActionPreparationEvidence;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownRedstoneIdentityAttemptTest {
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());

    @Test
    void requestAcceptsTheFourAdjacentRotations() {
        Map.of(
                0, List.of(1, 0),
                90, List.of(0, 1),
                180, List.of(-1, 0),
                270, List.of(0, -1))
                .forEach((rotation, offset) -> assertThat(
                        request(rotation, offset.get(0), offset.get(1)).spec().rotationDegrees())
                        .isEqualTo(rotation));
    }

    @Test
    void placesAndTestsTheCompleteIdentityTruthTableInOrder() {
        RedstoneIdentityRequest request = request();
        var port = new FakePort(request);
        int[] haloChecks = {0};
        var attempt = new KnownRedstoneIdentityAttempt(
                port,
                request,
                tick -> currentLamp(request, tick, port.lampLit),
                tick -> currentLever(request, tick, port.leverPowered),
                tick -> {
                    haloChecks[0]++;
                    return currentHalo(request, tick, null);
                },
                1,
                100);

        KnownRedstoneIdentityAttempt.TickResult result = null;
        int placed = 0;
        int interactions = 0;
        for (long tick = 1; tick < 100; tick++) {
            port.tick = tick;
            result = attempt.tick(tick);
            placed += result.placedDelta();
            interactions += result.interactionDelta();
            if (result.status() != KnownRedstoneIdentityAttempt.Status.RUNNING) break;
        }

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(KnownRedstoneIdentityAttempt.Status.SUCCEEDED);
        assertThat(result.placed()).isEqualTo(2);
        assertThat(result.interactions()).isEqualTo(2);
        assertThat(result.outputObservations()).isEqualTo(3);
        assertThat(placed).isEqualTo(2);
        assertThat(interactions).isEqualTo(2);
        assertThat(port.dispatches).containsExactly(
                "place:minecraft:redstone_lamp",
                "place:minecraft:lever",
                "lever:true",
                "lever:false");
        assertThat(port.releaseCalls).isEqualTo(4);
        assertThat(port.retireCalls).isEqualTo(4);
        assertThat(haloChecks[0]).isOne();
    }

    @Test
    void stopsBeforeTheFirstToggleWhenTheLampCannotBeSeenWithinTheSettleBound() {
        RedstoneIdentityRequest request = request();
        var port = new FakePort(request);
        var attempt = new KnownRedstoneIdentityAttempt(
                port,
                request,
                tick -> hiddenLamp(request, tick),
                tick -> currentLever(request, tick, port.leverPowered),
                tick -> currentHalo(request, tick, null),
                1,
                100);

        KnownRedstoneIdentityAttempt.TickResult result = null;
        for (long tick = 1; tick < 100; tick++) {
            port.tick = tick;
            result = attempt.tick(tick);
            if (result.status() != KnownRedstoneIdentityAttempt.Status.RUNNING) break;
        }

        assertThat(result.status()).isEqualTo(KnownRedstoneIdentityAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("redstone_output_not_observed");
        assertThat(result.placed()).isEqualTo(2);
        assertThat(result.interactions()).isZero();
        assertThat(port.dispatches).containsExactly(
                "place:minecraft:redstone_lamp", "place:minecraft:lever");
    }

    @Test
    void rejectsAnOutputThatDoesNotMatchTheCurrentLeverInput() {
        RedstoneIdentityRequest request = request();
        var port = new FakePort(request);
        var attempt = new KnownRedstoneIdentityAttempt(
                port,
                request,
                tick -> currentLamp(request, tick, port.lampLit),
                tick -> currentLever(request, tick, !port.leverPowered),
                tick -> currentHalo(request, tick, null),
                1,
                100);

        KnownRedstoneIdentityAttempt.TickResult result = null;
        for (long tick = 1; tick < 100; tick++) {
            port.tick = tick;
            result = attempt.tick(tick);
            if (result.status() != KnownRedstoneIdentityAttempt.Status.RUNNING) break;
        }

        assertThat(result.status()).isEqualTo(KnownRedstoneIdentityAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("redstone_output_changed");
        assertThat(result.interactions()).isZero();
    }

    @Test
    void stopsBeforeTheFirstPlacementWhenTheLeverHaloIsNotClear() {
        RedstoneIdentityRequest request = request();
        var port = new FakePort(request);
        BlockTarget blocked = request.leverSafetyHalo().stream()
                .filter(target -> !target.equals(request.leverPlacementAim().block()))
                .findFirst()
                .orElseThrow();
        var attempt = new KnownRedstoneIdentityAttempt(
                port,
                request,
                tick -> currentLamp(request, tick, port.lampLit),
                tick -> currentLever(request, tick, port.leverPowered),
                tick -> currentHalo(request, tick, blocked),
                1,
                100);

        KnownRedstoneIdentityAttempt.TickResult result = null;
        for (long tick = 1; tick < 100; tick++) {
            port.tick = tick;
            result = attempt.tick(tick);
            if (result.status() != KnownRedstoneIdentityAttempt.Status.RUNNING) break;
        }

        assertThat(result.status()).isEqualTo(KnownRedstoneIdentityAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("redstone_clearance_changed");
        assertThat(result.placed()).isZero();
        assertThat(result.interactions()).isZero();
        assertThat(port.dispatches).isEmpty();
    }

    @Test
    void closeReleasesTheActiveChildPreparation() {
        RedstoneIdentityRequest request = request();
        var port = new FakePort(request);
        var attempt = new KnownRedstoneIdentityAttempt(
                port,
                request,
                tick -> currentLamp(request, tick, port.lampLit),
                tick -> currentLever(request, tick, port.leverPowered),
                tick -> currentHalo(request, tick, null),
                1,
                100);

        assertThat(attempt.tick(1).status())
                .isEqualTo(KnownRedstoneIdentityAttempt.Status.RUNNING);
        attempt.close();

        assertThat(port.releasePreparationCalls).isEqualTo(1);
        assertThat(port.retireCalls).isEqualTo(1);
    }

    @Test
    void requestRejectsNonAdjacentTargetsSideSupportsAndMovingBounds() {
        RedstoneIdentityRequest valid = request();
        List<Runnable> invalid = List.of(
                () -> new RedstoneIdentityRequest(
                        valid.spec(), valid.worldSessionId(), valid.lampTarget(),
                        new BlockTarget("minecraft:overworld", 2, 65, 0),
                        valid.lampPlacementAim(), valid.leverPlacementAim(), valid.bounds()),
                () -> new RedstoneIdentityRequest(
                        valid.spec(), valid.worldSessionId(), valid.lampTarget(), valid.leverTarget(),
                        new BlockAimWitness(
                                valid.lampPlacementAim().block(), BlockAimWitness.Face.NORTH,
                                0.5, 64.5, 0),
                        valid.leverPlacementAim(), valid.bounds()),
                () -> new RedstoneIdentityRequest(
                        valid.spec(), valid.worldSessionId(), valid.lampTarget(), valid.leverTarget(),
                        valid.lampPlacementAim(), valid.leverPlacementAim(),
                        new ActionBounds(
                                "minecraft:overworld",
                                new BlockTarget("minecraft:overworld", 0, 64, 0),
                                new BlockTarget("minecraft:overworld", 1, 65, 0),
                                1, 30, false)));

        invalid.forEach(candidate -> assertThatThrownBy(candidate::run)
                .isInstanceOf(IllegalArgumentException.class));
    }

    private static RedstoneIdentityRequest request() {
        return request(0, 1, 0);
    }

    private static RedstoneIdentityRequest request(int rotation, int leverX, int leverZ) {
        var lamp = new BlockTarget("minecraft:overworld", 0, 65, 0);
        var lever = new BlockTarget("minecraft:overworld", leverX, 65, leverZ);
        var bounds = new ActionBounds(
                lamp.dimension(),
                new BlockTarget(
                        lamp.dimension(), Math.min(0, leverX), 64, Math.min(0, leverZ)),
                new BlockTarget(
                        lamp.dimension(), Math.max(0, leverX), 65, Math.max(0, leverZ)),
                0, 30, false);
        return new RedstoneIdentityRequest(
                new RedstoneSpec(
                        List.of(
                                new RedstoneSpec.Component(
                                        "input", RedstoneSpec.Role.INPUT, "minecraft:lever"),
                                new RedstoneSpec.Component(
                                        "output", RedstoneSpec.Role.OUTPUT,
                                        "minecraft:redstone_lamp")),
                        List.of(
                                new RedstoneSpec.TruthRow(
                                        Map.of("input", false), Map.of("output", false)),
                                new RedstoneSpec.TruthRow(
                                        Map.of("input", true), Map.of("output", true))),
                        new RedstoneSpec.Footprint(2, 1, 1),
                        rotation,
                        new RedstoneSpec.ExecutionBounds(true, 2)),
                UUID.randomUUID(),
                lamp,
                lever,
                new BlockAimWitness(
                        new BlockTarget(lamp.dimension(), 0, 64, 0),
                        BlockAimWitness.Face.UP, 0.5, 65, 0.5),
                new BlockAimWitness(
                        new BlockTarget(lamp.dimension(), leverX, 64, leverZ),
                        BlockAimWitness.Face.UP, leverX + 0.5, 65, leverZ + 0.5),
                bounds);
    }

    private static MinecraftObservationService.BlockSample currentLamp(
            RedstoneIdentityRequest request, long tick, boolean lit) {
        return currentBlock(
                request,
                request.lampTarget(),
                tick,
                "minecraft:redstone_lamp",
                "lit",
                lit);
    }

    private static MinecraftObservationService.BlockSample currentLever(
            RedstoneIdentityRequest request, long tick, boolean powered) {
        return currentBlock(
                request,
                request.leverTarget(),
                tick,
                "minecraft:lever",
                "powered",
                powered);
    }

    private static MinecraftObservationService.BlockSample currentBlock(
            RedstoneIdentityRequest request,
            BlockTarget target,
            long tick,
            String block,
            String property,
            boolean value) {
        return currentState(
                request, target, tick, block, Map.of(property, Boolean.toString(value)));
    }

    private static List<MinecraftObservationService.BlockSample> currentHalo(
            RedstoneIdentityRequest request, long tick, BlockTarget blocked) {
        BlockTarget support = request.leverPlacementAim().block();
        return request.leverSafetyHalo().stream()
                .map(target -> currentState(
                        request,
                        target,
                        tick,
                        target.equals(blocked)
                                ? "minecraft:stone"
                                : target.equals(support) ? "minecraft:glass" : "minecraft:air",
                        Map.of()))
                .toList();
    }

    private static MinecraftObservationService.BlockSample currentState(
            RedstoneIdentityRequest request,
            BlockTarget target,
            long tick,
            String block,
            Map<String, String> properties) {
        var position = blockPosition(target);
        var observed = new ObservedBlock(
                position,
                new BlockStateView(block, properties),
                new ObservedContext(0, 15, null, false, false, List.of("up")),
                ObservationProvenance.LINE_OF_SIGHT_OBSERVATION,
                tick,
                request.worldSessionId());
        return new MinecraftObservationService.BlockSample(
                MinecraftObservationService.BlockOutcome.CURRENT,
                position,
                observed,
                "minecraft:air".equals(block) ? List.of() : List.of("north"),
                true,
                null,
                tick);
    }

    private static MinecraftObservationService.BlockSample hiddenLamp(
            RedstoneIdentityRequest request, long tick) {
        return new MinecraftObservationService.BlockSample(
                MinecraftObservationService.BlockOutcome.NOT_CURRENTLY_OBSERVABLE,
                blockPosition(request.lampTarget()),
                null,
                List.of(),
                false,
                "occluded",
                tick);
    }

    private static BlockPosition blockPosition(BlockTarget target) {
        return new BlockPosition(target.dimension(), target.x(), target.y(), target.z());
    }

    private static final class FakePort implements SemanticActionPort {
        private final Map<BlockTarget, BlockStateFingerprint> states = new LinkedHashMap<>();
        private final List<String> dispatches = new ArrayList<>();
        private long tick = 1;
        private SemanticActionRequest preparedRequest;
        private SemanticActionAttempt action;
        private int releasePreparationCalls;
        private int releaseCalls;
        private int retireCalls;
        private boolean lampLit;
        private boolean leverPowered;

        private FakePort(RedstoneIdentityRequest request) {
            states.put(request.lampTarget(), AIR);
            states.put(request.leverTarget(), AIR);
        }

        @Override
        public SemanticActionFrame observe(SemanticActionRequest request) {
            BlockStateFingerprint live = states.get(target(request));
            return new SemanticActionFrame(
                    tick, tick, true, true, true, true, true, true,
                    Optional.of(live), true, true,
                    false, Optional.empty(), false, false, false, false,
                    0, true, 0, 64, 0, 0, true, true, "not_applicable", 0, true);
        }

        @Override
        public SemanticActionPreparationAttempt beginPreparation(
                SemanticActionRequest request, long deadline) {
            preparedRequest = request;
            return new SemanticActionPreparationAttempt(
                    UUID.randomUUID(), request.kind(), tick, tick, deadline, 0);
        }

        @Override public void maintainPreparation(SemanticActionPreparationAttempt attempt) { }

        @Override
        public SemanticActionPreparationEvidence preparationEvidence(
                SemanticActionPreparationAttempt attempt) {
            return new SemanticActionPreparationEvidence(
                    attempt.attemptId(), tick, tick,
                    Optional.of(states.get(target(preparedRequest))),
                    true, true, true, null);
        }

        @Override public void releasePreparation(SemanticActionPreparationAttempt attempt) {
            releasePreparationCalls++;
        }

        @Override
        public SemanticActionAttempt dispatchPrepared(
                SemanticActionRequest request,
                SemanticActionPreparationAttempt preparation,
                long deadline) {
            dispatches.add(request instanceof PlaceBlockRequest place
                    ? "place:" + place.item()
                    : "lever:" + ((InteractBlockRequest) request)
                            .expectedAfter().properties().get("powered"));
            action = new SemanticActionAttempt(
                    UUID.randomUUID(), request.kind(), tick, tick, deadline, 0, Map.of());
            preparedRequest = request;
            return action;
        }

        @Override
        public SemanticActionAttempt dispatch(SemanticActionRequest request, long deadline) {
            throw new UnsupportedOperationException();
        }

        @Override public void maintain(SemanticActionAttempt attempt) { }
        @Override public void stopInput(SemanticActionAttempt attempt) { }

        @Override
        public SemanticActionEvidence evidence(SemanticActionAttempt attempt) {
            BlockStateFingerprint after = expectedAfter(preparedRequest);
            states.put(target(preparedRequest), after);
            if (preparedRequest instanceof InteractBlockRequest) {
                leverPowered = Boolean.parseBoolean(after.properties().get("powered"));
                lampLit = leverPowered;
            }
            return new SemanticActionEvidence(
                    action.attemptId(), tick, tick, true, Optional.of(after),
                    false, true, 0, null, false, Map.of());
        }

        @Override public void release(SemanticActionAttempt attempt) { releaseCalls++; }
        @Override public void retire(SemanticActionRequest request) { retireCalls++; }

        private static BlockTarget target(SemanticActionRequest request) {
            return request instanceof PlaceBlockRequest place
                    ? place.target() : ((InteractBlockRequest) request).target();
        }

        private static BlockStateFingerprint expectedAfter(SemanticActionRequest request) {
            return request instanceof PlaceBlockRequest place
                    ? place.expectedAfter() : ((InteractBlockRequest) request).expectedAfter();
        }
    }
}
