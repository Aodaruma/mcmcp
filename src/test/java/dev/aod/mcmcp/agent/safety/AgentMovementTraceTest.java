package dev.aod.mcmcp.agent.safety;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMovementTraceTest {
    private static final AABB BOX = new AABB(0.0D, 0.0D, 0.0D, 0.6D, 1.8D, 0.6D);

    @Test
    void tickBoundaryDisplacementPromotesResolvedCandidateToContactEvidence() {
        var trace = new AgentMovementTrace();
        var player = new Object();
        var level = new Object();
        var intended = new Vec3(0.5D, 0.0D, 0.25D);

        trace.beginTick(player, level, 10L, 7L, Vec3.ZERO);
        trace.recordCollision(player, level, BOX, intended, intended);
        trace.endTick(player, level, intended);

        var movement = trace.latestFor(player, level).orElseThrow();
        assertThat(movement.worldRevision()).isEqualTo(7L);
        assertThat(movement.contactEvidence(movement.collisions().getFirst())).isTrue();
    }

    @Test
    void resolvedDeltaAloneNeverCountsAsContact() {
        var trace = new AgentMovementTrace();
        var player = new Object();
        var level = new Object();

        trace.beginTick(player, level, 11L, 8L, Vec3.ZERO);
        trace.recordCollision(
                player,
                level,
                BOX,
                new Vec3(0.5D, 0.0D, 0.0D),
                new Vec3(0.5D, 0.0D, 0.0D));
        trace.endTick(player, level, Vec3.ZERO);

        var movement = trace.latestFor(player, level).orElseThrow();
        assertThat(movement.contactEvidence(movement.collisions().getFirst())).isFalse();
    }

    @Test
    void collisionCaptureCannotCrossTheTickThread() throws Exception {
        var trace = new AgentMovementTrace();
        var player = new Object();
        var level = new Object();
        var failure = new AtomicReference<Throwable>();
        trace.beginTick(player, level, 12L, 9L, Vec3.ZERO);

        var otherThread = Thread.ofPlatform().start(() -> {
            try {
                trace.recordCollision(
                        player,
                        level,
                        BOX,
                        new Vec3(0.1D, 0.0D, 0.0D),
                        new Vec3(0.1D, 0.0D, 0.0D));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        otherThread.join();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameDirectionDisplacementCannotPromoteAPartiallyClippedMove() {
        var trace = new AgentMovementTrace();
        var player = new Object();
        var level = new Object();
        trace.beginTick(player, level, 13L, 10L, Vec3.ZERO);
        trace.recordCollision(
                player,
                level,
                BOX,
                new Vec3(1.0D, 0.0D, 0.0D),
                new Vec3(0.1D, 0.0D, 0.0D));
        trace.endTick(player, level, new Vec3(0.1D, 0.0D, 0.0D));

        var movement = trace.latestFor(player, level).orElseThrow();
        var collision = movement.collisions().getFirst();
        assertThat(movement.contactEvidence(collision)).isTrue();
        assertThat(LocalObservationVolume.actualTransition(movement, collision))
                .isEqualTo(ObservationRecord.Transition.BLOCKED);
    }
}
