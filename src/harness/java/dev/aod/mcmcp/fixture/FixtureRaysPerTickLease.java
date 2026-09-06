package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.client.McmcpClientConfig;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Reversible, identity-bound observation-rate override shared by development fixtures. */
final class FixtureRaysPerTickLease {
    static final int ACCELERATED_RAYS_PER_TICK = 512;

    private final Supplier<OverrideAccess> accessFactory;
    private final IntSupplier effectiveRaysReader;

    private OverrideAccess access;
    private Object server;
    private Object world;
    private Integer originalOverride;
    private Integer originalEffectiveRays;

    FixtureRaysPerTickLease() {
        this(
                McmcpTestHarnessConfigBridge::acquireRaysPerTickAccess,
                McmcpClientConfig::raysPerTick);
    }

    FixtureRaysPerTickLease(
            Supplier<Integer> overrideReader,
            Consumer<Integer> overrideWriter,
            IntSupplier effectiveRaysReader) {
        this(
                () -> new OverrideAccess() {
                    @Override
                    public Integer current() {
                        return overrideReader.get();
                    }

                    @Override
                    public void set(Integer value) {
                        overrideWriter.accept(value);
                    }
                },
                effectiveRaysReader);
        Objects.requireNonNull(overrideReader, "overrideReader");
        Objects.requireNonNull(overrideWriter, "overrideWriter");
    }

    FixtureRaysPerTickLease(
            Supplier<OverrideAccess> accessFactory,
            IntSupplier effectiveRaysReader) {
        this.accessFactory = Objects.requireNonNull(accessFactory, "accessFactory");
        this.effectiveRaysReader = Objects.requireNonNull(effectiveRaysReader, "effectiveRaysReader");
    }

    int begin(Object currentServer, Object currentWorld) {
        Objects.requireNonNull(currentServer, "currentServer");
        Objects.requireNonNull(currentWorld, "currentWorld");
        requireCurrent(currentServer, currentWorld);
        if (!active()) {
            OverrideAccess acquired = Objects.requireNonNull(
                    accessFactory.get(), "accessFactory returned null");
            Integer savedOverride = acquired.current();
            int savedEffectiveRays = effectiveRaysReader.getAsInt();
            access = acquired;
            server = currentServer;
            world = currentWorld;
            originalOverride = savedOverride;
            originalEffectiveRays = savedEffectiveRays;
            // Retain ownership even if a setter applies the value and then throws.
            acquired.set(ACCELERATED_RAYS_PER_TICK);
        } else {
            access.set(ACCELERATED_RAYS_PER_TICK);
        }
        return ACCELERATED_RAYS_PER_TICK;
    }

    void restore(Object currentServer, Object currentWorld) {
        requireCurrent(currentServer, currentWorld);
        restoreOwned();
    }

    void restoreOwned() {
        if (!active()) {
            return;
        }
        access.set(originalOverride);
        if (effectiveRaysReader.getAsInt() != originalEffectiveRays) {
            access.set(originalEffectiveRays);
            if (effectiveRaysReader.getAsInt() != originalEffectiveRays) {
                throw new IllegalStateException(
                        "observation acceleration could not restore the saved effective rate");
            }
        }
        clearState();
    }

    void requireCurrent(Object currentServer, Object currentWorld) {
        if (active() && (server != currentServer || world != currentWorld)) {
            throw new IllegalStateException("observation acceleration belongs to another world");
        }
    }

    boolean active() {
        return server != null;
    }

    Object server() {
        return server;
    }

    Integer originalEffectiveRays() {
        return originalEffectiveRays;
    }

    private void clearState() {
        access = null;
        server = null;
        world = null;
        originalOverride = null;
        originalEffectiveRays = null;
    }

    interface OverrideAccess {
        Integer current();

        void set(Integer value);
    }
}
