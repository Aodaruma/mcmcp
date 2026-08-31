package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exact Vanilla generic pure-storage profiles for the common Menu kernel. */
public final class KnownMenuProfileSupport {
    private static final List<Profile> PROFILES = List.of(
            genericProfile(1), genericProfile(2), genericProfile(3),
            genericProfile(4), genericProfile(5), genericProfile(6));
    private static final Map<String, Profile> PROFILES_BY_MENU_TYPE = PROFILES.stream()
            .collect(Collectors.toUnmodifiableMap(Profile::menuType, Function.identity()));
    private static final Profile DEFAULT_PROFILE = PROFILES.get(2);

    /** Source-compatible aliases for the original accepted 9x3 profile. */
    public static final String PROFILE_ID = DEFAULT_PROFILE.profileId();
    public static final String PROFILE_HASH = DEFAULT_PROFILE.profileHash();
    public static final String MENU_TYPE = DEFAULT_PROFILE.menuType();

    private KnownMenuProfileSupport() {
    }

    /** Resolves only a current, fully server-synchronized exact profile match. */
    public static Optional<Context> current(
            Minecraft minecraft,
            UUID worldSessionId,
            ContainerSyncSignals signals) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(signals, "signals");
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.gui.screen() == null
                || minecraft.gui.screen().getClass() != ContainerScreen.class
                || minecraft.player.containerMenu.getClass() != ChestMenu.class
                || !(minecraft.gui.screen() instanceof ContainerScreen screen)
                || !(minecraft.player.containerMenu instanceof ChestMenu menu)
                || screen.getMenu() != menu
                || !menu.getCarried().isEmpty()) {
            return Optional.empty();
        }
        ContainerSyncSignals.Snapshot ledger = signals.snapshot(minecraft.level).orElse(null);
        if (ledger == null
                || !ledger.sameSession(worldSessionId)
                || ledger.lastOpenScreen() == null
                || ledger.container() == null) {
            return Optional.empty();
        }
        ContainerSyncSignals.OpenScreenEvidence open = ledger.lastOpenScreen();
        ContainerSyncSignals.ContainerSnapshot snapshot = ledger.container();
        Profile profile = PROFILES_BY_MENU_TYPE.get(open.menuTypeId());
        if (!worldSessionId.equals(open.worldSessionId())
                || !worldSessionId.equals(snapshot.worldSessionId())
                || profile == null
                || open.containerId() != menu.containerId
                || snapshot.containerId() != menu.containerId
                || !profile.menuType().equals(snapshot.menuTypeId())
                || menu.getRowCount() != profile.rows()
                || menu.slots.size() != profile.totalSlotCount()
                || snapshot.packetLedgerRevision() <= open.packetLedgerRevision()
                || snapshot.receivedTick() < open.receivedTick()
                || snapshot.stateId() != menu.getStateId()
                || snapshot.slots().size() != menu.slots.size()
                || !snapshot.carried().empty()) {
            return Optional.empty();
        }
        Inventory inventory = minecraft.player.getInventory();
        Object storage = menu.slots.getFirst().container;
        var storageSlots = new ArrayList<Integer>(profile.storageSlotCount());
        for (int slot = 0; slot < profile.storageSlotCount(); slot++) {
            if (menu.slots.get(slot).container != storage
                    || menu.slots.get(slot).getContainerSlot() != slot) {
                return Optional.empty();
            }
            storageSlots.add(slot);
        }
        var playerSlots = new ArrayList<Integer>(36);
        for (int offset = 0; offset < 36; offset++) {
            int slot = profile.storageSlotCount() + offset;
            int expectedInventorySlot = offset < 27 ? offset + 9 : offset - 27;
            if (menu.slots.get(slot).container != inventory
                    || menu.slots.get(slot).getContainerSlot() != expectedInventorySlot) {
                return Optional.empty();
            }
            playerSlots.add(slot);
        }
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (!snapshot.slots().get(slot).equals(
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(
                            menu.slots.get(slot).getItem()))) {
                return Optional.empty();
            }
        }
        return Optional.of(new Context(
                screen, menu, snapshot, profile,
                List.copyOf(storageSlots), List.copyOf(playerSlots)));
    }

    static List<Profile> profiles() {
        return PROFILES;
    }

    private static Profile genericProfile(int rows) {
        String menuType = "minecraft:generic_9x" + rows;
        String profileId = menuType + "-pure-storage@26.2";
        int storageSlotCount = rows * 9;
        int totalSlotCount = storageSlotCount + 36;
        String canonical = "mcmcp-menu-profile/v1\n"
                + profileId + "\n"
                + "net.minecraft.world.inventory.ChestMenu\n"
                + "net.minecraft.client.gui.screens.inventory.ContainerScreen\n"
                + menuType + "\n"
                + "slots=" + totalSlotCount
                + ";storage=0.." + (storageSlotCount - 1)
                + ";player=" + storageSlotCount + ".." + (totalSlotCount - 1) + "\n"
                + "transfer_to_player=quick_move\n";
        return new Profile(
                profileId, profileHash(canonical), menuType,
                rows, storageSlotCount, totalSlotCount);
    }

    private static String profileHash(String canonical) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Context(
            ContainerScreen screen,
            ChestMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            Profile profile,
            List<Integer> storageSlots,
            List<Integer> playerSlots) {
        public Context {
            Objects.requireNonNull(screen, "screen");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(profile, "profile");
            storageSlots = List.copyOf(Objects.requireNonNull(storageSlots, "storageSlots"));
            playerSlots = List.copyOf(Objects.requireNonNull(playerSlots, "playerSlots"));
            if (storageSlots.size() != profile.storageSlotCount()
                    || playerSlots.size() != 36) {
                throw new IllegalArgumentException("known Menu profile layout is invalid");
            }
        }

        public KnownMenuOperationRefs.Context referenceContext(
                UUID worldSessionId, long clientTick) {
            return new KnownMenuOperationRefs.Context(
                    worldSessionId,
                    screen,
                    menu.containerId,
                    profile.menuType(),
                    snapshot.stateId(),
                    snapshot.slots().size(),
                    profile.profileHash(),
                    snapshot.packetLedgerRevision(),
                    clientTick);
        }
    }

    public record Profile(
            String profileId,
            String profileHash,
            String menuType,
            int rows,
            int storageSlotCount,
            int totalSlotCount) {
        public Profile {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(profileHash, "profileHash");
            Objects.requireNonNull(menuType, "menuType");
            if (rows < 1 || rows > 6
                    || storageSlotCount != rows * 9
                    || totalSlotCount != storageSlotCount + 36) {
                throw new IllegalArgumentException("known Menu profile shape is invalid");
            }
        }
    }
}
