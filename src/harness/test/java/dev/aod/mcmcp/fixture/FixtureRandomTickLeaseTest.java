package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FixtureRandomTickLeaseTest {
    @Test
    void savesTheFirstValueAndRejectsAnotherWorld() {
        var lease = new FixtureRandomTickLease();
        var server = new Object();
        var world = new Object();

        assertThat(lease.begin(server, world, 3, 30,
                FixtureRandomTickLease.Owner.GENERIC)).isEqualTo(30);
        assertThat(lease.begin(server, world, 99, 30,
                FixtureRandomTickLease.Owner.GENERIC)).isEqualTo(30);
        assertThat(lease.originalSpeed()).isEqualTo(3);
        assertThat(lease.targetSpeed()).isEqualTo(30);
        assertThat(lease.owner()).isEqualTo(FixtureRandomTickLease.Owner.GENERIC);
        assertThatIllegalStateException()
                .isThrownBy(() -> lease.begin(new Object(), world, 7, 30,
                        FixtureRandomTickLease.Owner.GENERIC));
        assertThatIllegalStateException()
                .isThrownBy(() -> lease.begin(server, world, 7, 300,
                        FixtureRandomTickLease.Owner.GENERIC));
        assertThatIllegalStateException()
                .isThrownBy(() -> lease.begin(server, world, 7, 30,
                        FixtureRandomTickLease.Owner.COMBINED_WHEAT));

        lease.clear();
        assertThat(lease.active()).isFalse();
        assertThat(lease.originalSpeed()).isNull();
        assertThat(lease.targetSpeed()).isNull();
        assertThat(lease.owner()).isNull();
    }
}
