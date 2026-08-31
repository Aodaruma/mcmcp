package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.PillarUpPort;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnownPillarUpAttemptTest {
    @Test
    void placesOnlyAfterClearanceAndReportsOneConfirmedBlock() {
        var port = new FakePort();
        var attempt = new KnownPillarUpAttempt(port, request(), 1L, 301L);

        assertThat(attempt.tick(1L).evidence()).isEqualTo("pillar_preparing");
        port.tick = 2L;
        assertThat(attempt.tick(2L).evidence()).isEqualTo("pillar_preparing");
        port.prepared = true;
        port.tick = 3L;
        assertThat(attempt.tick(3L).evidence()).isEqualTo("pillar_jumping");
        assertThat(port.jumpCalls).isEqualTo(1);
        port.tick = 4L;
        assertThat(attempt.tick(4L).evidence()).isEqualTo("pillar_jumping");
        assertThat(port.placeCalls).isZero();
        port.cleared = true;
        port.tick = 5L;
        assertThat(attempt.tick(5L).evidence()).isEqualTo("pillar_confirming");
        assertThat(port.placeCalls).isEqualTo(1);
        port.confirmed = true;
        port.tick = 6L;

        var result = attempt.tick(6L);

        assertThat(result.status()).isEqualTo(KnownPillarUpAttempt.Status.SUCCEEDED);
        assertThat(result.placedDelta()).isEqualTo(1);
        assertThat(port.placeCalls).isEqualTo(1);
        assertThat(port.releaseCalls).isEqualTo(1);
    }

    @Test
    void adapterFailureReleasesOwnershipWithoutDispatching() {
        var port = new FakePort();
        var attempt = new KnownPillarUpAttempt(port, request(), 1L, 301L);
        attempt.tick(1L);
        port.failure = "pillar_safety_changed";
        port.tick = 2L;

        var result = attempt.tick(2L);

        assertThat(result.status()).isEqualTo(KnownPillarUpAttempt.Status.FAILED);
        assertThat(result.evidence()).isEqualTo("pillar_safety_changed");
        assertThat(port.jumpCalls).isZero();
        assertThat(port.placeCalls).isZero();
        assertThat(port.releaseCalls).isEqualTo(1);
    }

    private static KnownPillarUpRequest request() {
        return new KnownPillarUpRequest(
                new BlockTarget("minecraft:overworld", 10, 64, 10),
                new BlockStateFingerprint("minecraft:stone", Map.of()),
                new BlockStateFingerprint("minecraft:stone", Map.of()),
                "minecraft:stone");
    }

    private static final class FakePort implements PillarUpPort {
        private final UUID id = UUID.randomUUID();
        private long tick = 1L;
        private boolean prepared;
        private boolean cleared;
        private boolean confirmed;
        private String failure;
        private int jumpCalls;
        private int placeCalls;
        private int releaseCalls;

        @Override
        public Handle begin(KnownPillarUpRequest request, long deadline) {
            return new Handle(id, tick, deadline);
        }

        @Override
        public void maintain(Handle handle) { }

        @Override
        public Evidence evidence(Handle handle) {
            return new Evidence(
                    id, tick, prepared, cleared,
                    confirmed, confirmed, confirmed, failure);
        }

        @Override
        public void startJump(Handle handle) {
            jumpCalls++;
        }

        @Override
        public void placeOnce(Handle handle) {
            placeCalls++;
        }

        @Override
        public void release(Handle handle) {
            releaseCalls++;
        }
    }
}
