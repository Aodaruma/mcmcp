package dev.aod.mcmcp.runtime;

import net.neoforged.fml.ModList;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Fixed, read-only identity bridge; unavailable unless the separate development fixture is loaded. */
public final class TestHarnessWorldSessionAccess {
    private static final TestHarnessWorldSessionAccess LIVE = new TestHarnessWorldSessionAccess(
            () -> Boolean.getBoolean("mcmcp.testHarness"),
            TestHarnessWorldSessionAccess::fixtureIsLoaded);

    private final BooleanSupplier harnessEnabled;
    private final BooleanSupplier fixtureLoaded;
    private volatile Supplier<WorldSessionTracker.Snapshot> snapshot;

    TestHarnessWorldSessionAccess(BooleanSupplier harnessEnabled, BooleanSupplier fixtureLoaded) {
        this.harnessEnabled = Objects.requireNonNull(harnessEnabled);
        this.fixtureLoaded = Objects.requireNonNull(fixtureLoaded);
    }

    static void bind(Supplier<WorldSessionTracker.Snapshot> supplier) { LIVE.bindSnapshot(supplier); }

    void bindSnapshot(Supplier<WorldSessionTracker.Snapshot> supplier) {
        snapshot = Objects.requireNonNull(supplier);
    }

    public static UUID currentWorldSessionId() { return LIVE.read(); }

    private static boolean fixtureIsLoaded() {
        try { return ModList.get().getModContainerById("mcmcp_test_fixture").isPresent(); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    UUID read() {
        if (!harnessEnabled.getAsBoolean() || !fixtureLoaded.getAsBoolean())
            throw new IllegalStateException("world session access requires the development fixture");
        Supplier<WorldSessionTracker.Snapshot> current = snapshot;
        if (current == null) throw new IllegalStateException("world session access is not bound");
        var value = current.get();
        if (value == null || !value.worldReady() || value.worldSessionId() == null)
            throw new IllegalStateException("world session is not ready");
        return value.worldSessionId();
    }
}
