package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FixtureRaysPerTickLeaseTest {
    @Test
    void restoresTheExactPriorOverrideAndRejectsAnotherWorld() {
        var override = new AtomicReference<>(128);
        var lease = new FixtureRaysPerTickLease(
                override::get,
                override::set,
                override::get);
        var server = new Object();
        var world = new Object();

        assertThat(lease.begin(server, world)).isEqualTo(512);
        assertThat(override).hasValue(512);
        assertThat(lease.originalEffectiveRays()).isEqualTo(128);
        override.set(64);
        assertThat(lease.begin(server, world)).isEqualTo(512);
        assertThat(override).hasValue(512);
        assertThatIllegalStateException()
                .isThrownBy(() -> lease.begin(new Object(), world));

        lease.restore(server, world);
        assertThat(override).hasValue(128);
        assertThat(lease.active()).isFalse();
    }

    @Test
    void clearsAnOverrideThatDidNotExistBeforeTheLease() {
        var override = new AtomicReference<Integer>();
        var lease = new FixtureRaysPerTickLease(
                override::get,
                override::set,
                () -> 256);
        var server = new Object();
        var world = new Object();

        lease.begin(server, world);
        lease.restore(server, world);

        assertThat(override).hasNullValue();
        assertThat(lease.active()).isFalse();
    }

    @Test
    void restoresTheSavedEffectiveRateWhenTheUnderlyingConfigChanges() {
        var override = new AtomicReference<Integer>();
        var base = new AtomicReference<>(256);
        var lease = new FixtureRaysPerTickLease(
                override::get,
                override::set,
                () -> override.get() == null ? base.get() : override.get());
        var server = new Object();
        var world = new Object();

        lease.begin(server, world);
        base.set(64);
        lease.restore(server, world);

        assertThat(override).hasValue(256);
        assertThat(lease.active()).isFalse();
    }

    @Test
    void acquiredAccessCanCleanUpAfterAdmissionIsRevokedButANewRunCannotStart() {
        var admitted = new AtomicBoolean(true);
        var override = new AtomicReference<Integer>();
        var lease = new FixtureRaysPerTickLease(
                () -> {
                    if (!admitted.get()) {
                        throw new IllegalStateException("fixture admission revoked");
                    }
                    return new FixtureRaysPerTickLease.OverrideAccess() {
                        @Override
                        public Integer current() {
                            return override.get();
                        }

                        @Override
                        public void set(Integer value) {
                            override.set(value);
                        }
                    };
                },
                () -> override.get() == null ? 256 : override.get());
        var server = new Object();
        var world = new Object();

        lease.begin(server, world);
        admitted.set(false);
        lease.restore(server, world);

        assertThat(override).hasNullValue();
        assertThat(lease.active()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> lease.begin(server, world));
    }

    @Test
    void keepsOwnershipWhenRestorationFailsSoLifecycleCanRetry() {
        var override = new AtomicReference<Integer>();
        var failRestore = new AtomicBoolean(true);
        var lease = new FixtureRaysPerTickLease(
                override::get,
                value -> {
                    if (failRestore.get() && value == null) {
                        throw new IllegalStateException("test restore failure");
                    }
                    override.set(value);
                },
                () -> 256);
        var server = new Object();
        var world = new Object();

        lease.begin(server, world);
        assertThatIllegalStateException().isThrownBy(() -> lease.restore(server, world));
        assertThat(lease.active()).isTrue();

        failRestore.set(false);
        lease.restore(server, world);
        assertThat(lease.active()).isFalse();
        assertThat(override).hasNullValue();
    }

}
