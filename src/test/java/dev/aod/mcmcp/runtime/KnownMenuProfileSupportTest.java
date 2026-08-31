package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnownMenuProfileSupportTest {
    @Test
    void genericPureStorageProfilesAreVersionedContentAddressedAndBounded() {
        var profiles = KnownMenuProfileSupport.profiles();

        assertThat(profiles).extracting(KnownMenuProfileSupport.Profile::rows)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(profiles).allSatisfy(profile -> {
            assertThat(profile.profileId()).isEqualTo(
                    profile.menuType() + "-pure-storage@26.2");
            assertThat(profile.profileHash()).matches("sha256:[0-9a-f]{64}");
            assertThat(profile.storageSlotCount()).isEqualTo(profile.rows() * 9);
            assertThat(profile.totalSlotCount()).isEqualTo(profile.storageSlotCount() + 36);
        });
        assertThat(profiles.stream().map(KnownMenuProfileSupport.Profile::profileHash)
                .distinct().count()).isEqualTo(profiles.size());

        assertThat(KnownMenuProfileSupport.PROFILE_ID)
                .isEqualTo("minecraft:generic_9x3-pure-storage@26.2");
        assertThat(KnownMenuProfileSupport.PROFILE_HASH)
                .matches("sha256:[0-9a-f]{64}");
        assertThat(KnownMenuProfileSupport.MENU_TYPE)
                .isEqualTo("minecraft:generic_9x3");
    }
}
