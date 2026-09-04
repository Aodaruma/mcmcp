package dev.aod.mcmcp.runtime;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnownMenuProfileSupportTest {
    @BeforeAll
    static void bindStoneComponents() {
        var holder = Items.STONE.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, 64)
                    .build());
        }
    }

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

    @Test
    void sophisticatedBackpackProfilePinsBothArtifactsAndExactContract() {
        var profile = KnownMenuProfileSupport.sophisticatedBackpackProfile();
        var artifacts = KnownMenuProfileSupport.sophisticatedBackpackArtifacts();

        assertThat(profile.profileId()).isEqualTo(
                "sophisticatedbackpacks:backpack-pure-storage@3.25.90+core-1.4.99+mc26.2");
        assertThat(profile.menuType()).isEqualTo("sophisticatedbackpacks:backpack");
        assertThat(profile.profileHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(artifacts)
                .extracting(KnownMenuProfileSupport.ArtifactRequirement::modId)
                .containsExactly("sophisticatedbackpacks", "sophisticatedcore");
        assertThat(artifacts.get(0).matches(
                "3.25.90",
                "9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037"))
                .isTrue();
        assertThat(artifacts.get(0).matches(
                "3.25.91",
                "9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037"))
                .isFalse();
        assertThat(artifacts.get(0).matches("3.25.90", "0".repeat(64))).isFalse();
        assertThat(artifacts.get(1).matches(
                "1.4.99",
                "f80b8868d15b59882c642ebaa020100e9d1f59cfbae8bdb6a584140b658fb10e"))
                .isTrue();

        assertThat(KnownMenuProfileSupport.hasBackpackMenuContract(ValidContract.class))
                .isTrue();
        assertThat(KnownMenuProfileSupport.hasBackpackMenuContract(MissingContract.class))
                .isFalse();
        assertThat(KnownMenuProfileSupport.hasBackpackMenuContract(WrongReturnContract.class))
                .isFalse();
    }

    @Test
    void artifactHashingDoesNotDependOnAnInstalledPrismPath(@TempDir Path directory)
            throws Exception {
        Path artifact = directory.resolve("profile.jar");
        Files.writeString(artifact, "abc");

        assertThat(KnownMenuProfileSupport.sha256(artifact)).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void fullTransferRejectsOversizedOrInsufficientAndHonorsSlotMayPlace() {
        var inventory = new SimpleContainer(2);
        var accepting = new Slot(inventory, 0, 0, 0);
        var rejecting = new Slot(inventory, 1, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        };

        var source = new ItemStack(Items.STONE, 1);
        int maximum = source.getMaxStackSize();
        assertThat(KnownMenuProfileSupport.hasFullPlayerCapacity(
                source, List.of(rejecting, accepting))).isTrue();

        inventory.setItem(0, new ItemStack(Items.STONE, maximum));
        assertThat(KnownMenuProfileSupport.hasFullPlayerCapacity(
                new ItemStack(Items.STONE, 1), List.of(rejecting, accepting))).isFalse();

        var oversized = new ItemStack(Items.STONE, 1);
        oversized.setCount(maximum + 1);
        assertThat(KnownMenuProfileSupport.isNormalSizedStack(oversized)).isFalse();
        assertThat(KnownMenuProfileSupport.hasFullPlayerCapacity(
                oversized, List.of(accepting))).isFalse();

        inventory.setItem(0, new ItemStack(Items.STONE, maximum - 4));
        assertThat(KnownMenuProfileSupport.hasFullDestinationCapacity(
                new ItemStack(Items.STONE, 4), List.of(accepting))).isTrue();
        assertThat(KnownMenuProfileSupport.hasFullDestinationCapacity(
                new ItemStack(Items.STONE, 5), List.of(accepting))).isFalse();
    }

    public static class ValidContract {
        public int getNumberOfStorageInventorySlots() {
            return 27;
        }

        public boolean isStorageInventorySlot(int slot) {
            return slot >= 0 && slot < 27;
        }

        public boolean isInaccessibleSlot(int slot) {
            return false;
        }

        public Optional<Object> getOpenContainer() {
            return Optional.empty();
        }

        public List<Object> getExtraSlots() {
            return List.of();
        }
    }

    public static final class MissingContract {
        public int getNumberOfStorageInventorySlots() {
            return 27;
        }
    }

    public static final class WrongReturnContract extends ValidContract {
        @Override
        public ArrayList<Object> getExtraSlots() {
            return new ArrayList<>();
        }
    }
}
