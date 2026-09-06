package dev.aod.mcmcp.agent.observation;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.observation.ObservationRecord.VisibleSurface;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationValues.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Green characterization of the production evidence APIs used by agentPlanningFrame and its
 * admission surface fence. This does not execute Minecraft's inbox/render loop or establish
 * which barrier/fog sample was present in Luna's actual run. The ray sampler is an unchanged
 * visible chest fixture; only the renderer sample's tick availability changes between phases.
 */
class LunaSurfacePreflightRegressionTest {
    private static final ResourceId DIMENSION = new ResourceId("minecraft:overworld");
    private static final long OLD_REVISION = 10;
    private static final long CURRENT_REVISION = OLD_REVISION + 55;
    private static final VisibleSurface CHEST = new VisibleSurface(
            new BlockPosition(DIMENSION, 1, 64, 0), ObservationRecord.Face.UP,
            new ResourceId("minecraft:chest"), ObservationRecord.ShapeClass.PARTIAL, null,
            new WorldPosition(DIMENSION, 1.5, 64.875, 0.5),
            new WorldPosition(DIMENSION, -1.5, 65.62, 0.5), 10, OLD_REVISION);
    private static final Optional<ObservationFrame> RAW_FRAME = Optional.of(new ObservationFrame(
            "obs-0000000000000001", DIMENSION, 10, 16, false, List.of(CHEST)));
    private static final ActionDsl.Position TARGET = new ActionDsl.Position(DIMENSION.value(), 1, 64, 0);

    @Test
    void unchangedChestLosesItsTemporaryFreshWitnessWhenCommitHasNoCurrentFogThenRecovers() {
        var map = map();
        var store = deliveredStore();
        var level = new Object();
        var camera = new Object();
        var rays = new AtomicInteger();
        // Explicit hypothesis: an existing surface barrier requires a newer witness. Nothing
        // changes this barrier, the map/session, player eye, or target during the three phases.
        long barrier = CURRENT_REVISION;

        ClientFogDistanceSignals.recordIdentity(level, camera, 11, 16);
        var captured = reobserveWithCurrentFog(store, level, camera, 11, rays);
        var required = AgentPrimitivePlanner.requireKnownSurface(
                map, captured, TARGET, "minecraft:chest", barrier);
        assertThat(AgentPrimitivePlanner.knownSurface(map, captured, required, barrier)).isTrue();
        assertThat(rays).hasValue(1);

        // A player tick without a new render makes the exact-tick fog sample unavailable.
        // This is the missing-fog branch of agentPlanningFrame: augment only, no new ray.
        assertThat(ClientFogDistanceSignals.currentIdentity(level, camera, 12)).isEmpty();
        var committing = store.augment(RAW_FRAME);
        assertThat(committing.orElseThrow().records()).containsExactly(CHEST);
        assertThat(AgentPrimitivePlanner.knownSurface(map, committing, required, barrier)).isFalse();
        assertThat(rays).hasValue(1);

        ClientFogDistanceSignals.recordIdentity(level, camera, 13, 16);
        var recovered = reobserveWithCurrentFog(store, level, camera, 13, rays);
        assertThat(AgentPrimitivePlanner.knownSurface(map, recovered, required, barrier)).isTrue();
        assertThat(rays).hasValue(2);
        assertThat(RAW_FRAME.orElseThrow().records()).containsExactly(CHEST);
        assertThat(store.augment(RAW_FRAME).orElseThrow().records()).containsExactly(CHEST);
        assertThat(map.worldRevision()).isEqualTo(CURRENT_REVISION);
    }

    @Test
    void fiftyFiveGlobalRevisionsAloneDoNotInvalidateTheSameDeliveredSurface() {
        var map = map();
        var store = deliveredStore();
        var planning = store.augment(RAW_FRAME);
        // Without a newer target/visual/eviction barrier, missing current fog needs no refresh.
        var required = AgentPrimitivePlanner.requireKnownSurface(
                map, planning, TARGET, "minecraft:chest", OLD_REVISION);
        assertThat(map.worldRevision() - CHEST.worldRevision()).isEqualTo(55);
        assertThat(AgentPrimitivePlanner.knownSurface(map, planning, required, OLD_REVISION)).isTrue();
        assertThat(AgentPrimitivePlanner.knownSurface(map, planning, required, CURRENT_REVISION)).isFalse();
    }

    private static Optional<ObservationFrame> reobserveWithCurrentFog(
            DeliveredPolicyEvidenceStore store, Object level, Object camera, int tick, AtomicInteger rays) {
        var fog = ClientFogDistanceSignals.currentIdentity(level, camera, tick);
        assertThat(fog).isPresent();
        var sample = new OmnidirectionalObserver.TickSample(DIMENSION, CHEST.eyeOrigin(), tick,
                CURRENT_REVISION, CURRENT_REVISION, fog.orElseThrow(),
                ObservationRecord.UnknownBoundaryReason.RADIUS_LIMIT);
        return store.reobserveForPlanning(RAW_FRAME, known ->
                OmnidirectionalObserver.reobserveSurface(known, sample, (index, direction, current) -> {
                    rays.incrementAndGet();
                    assertThat(known).isEqualTo(CHEST);
                    assertThat(current.eyeOrigin()).isEqualTo(CHEST.eyeOrigin());
                    var sameChest = new VisibleSurface(CHEST.position(), CHEST.face(), CHEST.block(),
                            CHEST.state(), CHEST.placementItem(), CHEST.shapeClass(), CHEST.cropMature(),
                            CHEST.rayHit(), current.eyeOrigin(), current.observedTick(), current.worldRevision());
                    return new OmnidirectionalObserver.RayTrace(OmnidirectionalObserver.RayOutcome.HIT,
                            List.of(sameChest), new ObservationRecord.UnknownBoundary(CHEST.rayHit(),
                                    ObservationRecord.UnknownBoundaryReason.OPAQUE_OCCLUSION,
                                    current.eyeOrigin(), current.observedTick(), current.worldRevision()));
                }));
    }

    private static DeliveredPolicyEvidenceStore deliveredStore() {
        var store = new DeliveredPolicyEvidenceStore(() -> 0L);
        var receipt = store.prepareDelivery(new ObservationPage(
                RAW_FRAME.orElseThrow().frameId(), 10, List.of(CHEST), null));
        assertThat(store.confirmDelivery(receipt)).isTrue();
        return store;
    }

    private static KnownTraversabilitySnapshot map() {
        var map = new KnownTraversabilityMap();
        map.startSession(new UUID(0, 1), DIMENSION.value(), CURRENT_REVISION);
        return map.snapshot().orElseThrow();
    }
}
