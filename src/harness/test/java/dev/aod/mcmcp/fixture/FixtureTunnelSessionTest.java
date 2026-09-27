package dev.aod.mcmcp.fixture;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class FixtureTunnelSessionTest {
    @Test
    void statusAndOracleShareTheCapturedWorldIdentityButAnotherRunCannotReuseIt() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var initial = new FixtureTunnelSession(first);
        assertThat(initial.requireCurrent(first)).isEqualTo(first); // status
        assertThat(initial.requireCurrent(first)).isEqualTo(first); // oracle
        assertThatIllegalStateException().isThrownBy(() -> initial.requireCurrent(second));
        assertThatIllegalStateException().isThrownBy(() -> initial.requireCurrent(null));
        assertThat(new FixtureTunnelSession(second).requireCurrent(second)).isNotEqualTo(first);
        assertThatNullPointerException().isThrownBy(() -> new FixtureTunnelSession(null));
    }
}
