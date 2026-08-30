package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnownMenuProfileSupportTest {
    @Test
    void profileIdentityIsVersionedAndContentAddressed() {
        assertThat(KnownMenuProfileSupport.PROFILE_ID)
                .isEqualTo("minecraft:generic_9x3-pure-storage@26.2");
        assertThat(KnownMenuProfileSupport.PROFILE_HASH)
                .matches("sha256:[0-9a-f]{64}");
    }
}
