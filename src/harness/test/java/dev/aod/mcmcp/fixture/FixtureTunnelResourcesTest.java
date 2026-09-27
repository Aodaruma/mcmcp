package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class FixtureTunnelResourcesTest {
    @Test
    void shutdownAndReadersWaitForCompleteSetupAndConcurrentRestoresDoNotDuplicateWrites() throws Exception {
        var chunks = new FakeChunks();
        var enteredFirstWrite = new CountDownLatch(1);
        var finishSetup = new CountDownLatch(1);
        var restoreRequested = new CountDownLatch(2);
        var readerRequested = new CountDownLatch(1);
        var firstWrite = new AtomicBoolean(true);
        var resources = resources(new AtomicReference<>(128));
        var server = new Object();
        var world = new Object();
        var delayedChunks = new FixtureTunnelResources.ChunkAccess() {
            @Override public boolean forced(FixtureTunnelResources.Chunk chunk) { return chunks.forced(chunk); }
            @Override public void setForced(FixtureTunnelResources.Chunk chunk, boolean forced) {
                chunks.setForced(chunk, forced);
                if (forced && firstWrite.getAndSet(false)) {
                    enteredFirstWrite.countDown();
                    try {
                        if (!finishSetup.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("setup test timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            }
        };
        try (var workers = Executors.newFixedThreadPool(4)) {
            var begin = workers.submit(() -> resources.begin(server, world, delayedChunks));
            assertThat(enteredFirstWrite.await(5, TimeUnit.SECONDS)).isTrue();
            var stop = workers.submit(() -> { restoreRequested.countDown(); resources.restore(); });
            var shutdown = workers.submit(() -> { restoreRequested.countDown(); resources.restore(); });
            var read = workers.submit(() -> { readerRequested.countDown(); return resources.server(); });
            try {
                assertThat(restoreRequested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(readerRequested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> stop.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> shutdown.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> read.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { finishSetup.countDown(); }
            begin.get(5, TimeUnit.SECONDS);
            stop.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            read.get(5, TimeUnit.SECONDS);
        }
        assertThat(resources.active()).isFalse();
        assertThat(resources.pendingChunks()).isZero();
        assertThat(chunks.values.values()).allMatch(value -> !value);
        assertThat(chunks.writes).isEqualTo(44); // 22 acquisitions plus one complete restoration, never two.
    }

    @Test
    void restoresAllTwentyTwoOriginalFlagsAndOriginalOverrideWithoutTouchingOtherChunks() {
        var chunks = new FakeChunks();
        var preexisting = FixtureTunnelResources.chunks().getFirst();
        var outside = new FixtureTunnelResources.Chunk(90, 90);
        chunks.values.put(preexisting, true);
        chunks.values.put(outside, true);
        var before = Map.copyOf(chunks.values);
        var override = new AtomicReference<>(128);
        var resources = resources(override);
        var server = new Object();
        var world = new Object();
        resources.begin(server, world, chunks);
        assertThat(FixtureTunnelResources.chunks()).hasSize(22).doesNotHaveDuplicates();
        assertThat(FixtureTunnelResources.chunks()).allMatch(chunks::forced);
        assertThat(override).hasValue(512);
        assertThatIllegalStateException().isThrownBy(() -> resources.begin(server, world, chunks));
        assertThatIllegalStateException().isThrownBy(() -> resources.requireOwner(new Object(), world));
        resources.restore();
        assertThat(resources.active()).isFalse();
        assertThat(chunks.values).isEqualTo(before);
        assertThat(override).hasValue(128);
        int writes = chunks.writes;
        resources.restore();
        assertThat(chunks.writes).isEqualTo(writes);
    }

    @Test
    void setupFailureAfterAddingATicketRestoresItAndTheObservationOverride() {
        var chunks = new FakeChunks();
        chunks.failAfterForce = true;
        var override = new AtomicReference<Integer>();
        var resources = resources(override);
        assertThatIllegalStateException().isThrownBy(() -> resources.begin(new Object(), new Object(), chunks));
        assertThat(chunks.values.values()).allMatch(value -> !value);
        assertThat(override).hasNullValue();
        assertThat(resources.active()).isFalse();
    }

    @Test
    void statusReadDetectsOneUnforcedChunkWithoutChangingTheSavedRestorationLedger() {
        var chunks = new FakeChunks();
        var preexisting = FixtureTunnelResources.chunks().getFirst();
        chunks.values.put(preexisting, true);
        // This extra forced flag must never compensate for a missing flag inside the fixed 22.
        chunks.values.put(new FixtureTunnelResources.Chunk(90, 90), true);
        var original = Map.copyOf(chunks.values);
        var resources = resources(new AtomicReference<>(128));
        resources.begin(new Object(), new Object(), chunks);
        assertThat(resources.actualForcedChunks()).isEqualTo(22);

        chunks.values.put(preexisting, false); // External unforce after the first ready/status read.
        assertThat(resources.actualForcedChunks()).isEqualTo(21);
        assertThat(resources.actualForcedChunks()).isEqualTo(21); // No cached 22 on a later status read.
        assertThat(resources.pendingChunks()).isEqualTo(22);
        assertThat(chunks.forced(preexisting)).isFalse(); // Observation itself does not repair the drift.

        resources.restore();
        assertThat(chunks.values).isEqualTo(original); // Preserve the original true, rather than the drifted false.
        assertThat(resources.actualForcedChunks()).isZero();
    }

    @Test
    void unreadableCurrentFlagsCannotFallBackToTheRecordedTwentyTwo() {
        var chunks = new FakeChunks();
        var resources = resources(new AtomicReference<>(128));
        resources.begin(new Object(), new Object(), chunks);
        chunks.failRead = true;
        assertThatIllegalStateException().isThrownBy(resources::actualForcedChunks);
        assertThat(resources.pendingChunks()).isEqualTo(22);
        chunks.failRead = false;
        resources.restore();
        assertThat(resources.active()).isFalse();
    }

    @Test
    void sharedHealthRejectsExternalRayDriftAndRestorationRecoversTheSavedRate() {
        var override = new AtomicReference<>(128);
        var resources = resources(override);
        var chunks = new FakeChunks();
        resources.begin(new Object(), new Object(), chunks);
        var ready = resources.health(override.get(), false);
        assertThat(ready.intact()).isTrue();
        assertThat(resources.health(override.get(), true).intact()).isFalse();

        override.set(64); // Same external setting change inspected by status, oracle and pre-tick.
        var drifted = resources.health(override.get(), false);
        assertThat(drifted.forcedChunks()).isEqualTo(22);
        assertThat(drifted.raysPerTick()).isEqualTo(64);
        assertThat(drifted.intact()).isFalse();
        assertThat(ready.raysPerTick()).isEqualTo(512); // Prior snapshots are immutable evidence.

        resources.restore();
        assertThat(override).hasValue(128);
        assertThat(chunks.values.values()).allMatch(value -> !value);
        assertThat(resources.health(512, false).intact()).isFalse(); // Inactive cannot become ready from a rate alone.
    }

    @Test
    void failedChunkRestorationKeepsOnlyItsLedgerAndStillRestoresRaysForLifecycleRetry() {
        var chunks = new FakeChunks();
        var override = new AtomicReference<>(64);
        var resources = resources(override);
        resources.begin(new Object(), new Object(), chunks);
        chunks.failRestore = true;
        assertThatIllegalStateException().isThrownBy(resources::restore);
        assertThat(resources.active()).isTrue();
        assertThat(resources.pendingChunks()).isEqualTo(1);
        assertThat(override).hasValue(64);
        chunks.failRestore = false;
        resources.restore();
        assertThat(resources.active()).isFalse();
        assertThat(chunks.values.values()).allMatch(value -> !value);
    }

    @Test
    void raySetterThatMutatesThenThrowsIsStillOwnedAndRestored() {
        var override = new AtomicReference<Integer>();
        var first = new AtomicBoolean(true);
        var lease = new FixtureRaysPerTickLease(override::get, value -> {
            override.set(value);
            if (Integer.valueOf(512).equals(value) && first.getAndSet(false))
                throw new IllegalStateException("set applied then failed");
        }, () -> override.get() == null ? 256 : override.get());
        var resources = new FixtureTunnelResources(lease);
        assertThatIllegalStateException().isThrownBy(() -> resources.begin(new Object(), new Object(), new FakeChunks()));
        assertThat(resources.active()).isFalse();
        assertThat(override).hasNullValue();
    }

    @Test
    void failedRayRestorationRetainsOwnershipAfterChunksAreRestored() {
        var override = new AtomicReference<Integer>();
        var failRestore = new AtomicBoolean(true);
        var lease = new FixtureRaysPerTickLease(override::get, value -> {
            if (value == null && failRestore.get()) throw new IllegalStateException("restore unavailable");
            override.set(value);
        }, () -> override.get() == null ? 256 : override.get());
        var resources = new FixtureTunnelResources(lease);
        resources.begin(new Object(), new Object(), new FakeChunks());
        assertThatIllegalStateException().isThrownBy(resources::restore);
        assertThat(resources.pendingChunks()).isZero();
        assertThat(resources.active()).isTrue();
        failRestore.set(false);
        resources.restore();
        assertThat(resources.active()).isFalse();
        assertThat(override).hasNullValue();
    }

    private static FixtureTunnelResources resources(AtomicReference<Integer> override) {
        return new FixtureTunnelResources(new FixtureRaysPerTickLease(override::get, override::set,
                () -> override.get() == null ? 256 : override.get()));
    }

    private static final class FakeChunks implements FixtureTunnelResources.ChunkAccess {
        final Map<FixtureTunnelResources.Chunk, Boolean> values = new HashMap<>();
        boolean failAfterForce;
        boolean failRestore;
        boolean failRead;
        int writes;

        FakeChunks() { FixtureTunnelResources.chunks().forEach(chunk -> values.put(chunk, false)); }
        @Override public boolean forced(FixtureTunnelResources.Chunk chunk) {
            if (failRead) throw new IllegalStateException("current chunk flags unavailable");
            return values.getOrDefault(chunk, false);
        }
        @Override public void setForced(FixtureTunnelResources.Chunk chunk, boolean forced) {
            writes++;
            if (!forced && failRestore && chunk.equals(FixtureTunnelResources.chunks().getFirst()))
                throw new IllegalStateException("chunk restore unavailable");
            values.put(chunk, forced);
            if (forced && failAfterForce) {
                failAfterForce = false;
                throw new IllegalStateException("ticket added before chunk load failed");
            }
        }
    }
}
