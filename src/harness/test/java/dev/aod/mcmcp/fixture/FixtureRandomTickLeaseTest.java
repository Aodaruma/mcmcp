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

        assertThat(lease.begin(server, world, 3)).isEqualTo(30);
        assertThat(lease.begin(server, world, 99)).isEqualTo(30);
        assertThat(lease.originalSpeed()).isEqualTo(3);
        assertThatIllegalStateException()
                .isThrownBy(() -> lease.begin(new Object(), world, 7));

        lease.clear();
        assertThat(lease.active()).isFalse();
        assertThat(lease.originalSpeed()).isNull();
    }
}
