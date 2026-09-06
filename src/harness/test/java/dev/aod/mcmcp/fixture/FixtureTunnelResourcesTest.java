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
        int writes;

        FakeChunks() { FixtureTunnelResources.chunks().forEach(chunk -> values.put(chunk, false)); }
        @Override public boolean forced(FixtureTunnelResources.Chunk chunk) { return values.getOrDefault(chunk, false); }
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
