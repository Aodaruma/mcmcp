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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact first profile for the common Menu kernel: Vanilla generic 9x3 pure storage. */
public final class KnownMenuProfileSupport {
    public static final String PROFILE_ID = "minecraft:generic_9x3-pure-storage@26.2";
    public static final String PROFILE_HASH = profileHash("""
            mcmcp-menu-profile/v1
            minecraft:generic_9x3-pure-storage@26.2
            net.minecraft.world.inventory.ChestMenu
            net.minecraft.client.gui.screens.inventory.ContainerScreen
            minecraft:generic_9x3
            slots=63;storage=0..26;player=27..62
            transfer_to_player=quick_move
            """);
    public static final String MENU_TYPE = "minecraft:generic_9x3";

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
                || menu.getRowCount() != 3
                || menu.slots.size() != 63
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
        if (!worldSessionId.equals(open.worldSessionId())
                || !worldSessionId.equals(snapshot.worldSessionId())
                || open.containerId() != menu.containerId
                || snapshot.containerId() != menu.containerId
                || !MENU_TYPE.equals(open.menuTypeId())
                || !MENU_TYPE.equals(snapshot.menuTypeId())
                || snapshot.packetLedgerRevision() <= open.packetLedgerRevision()
                || snapshot.receivedTick() < open.receivedTick()
                || snapshot.stateId() != menu.getStateId()
                || snapshot.slots().size() != menu.slots.size()
                || !snapshot.carried().empty()) {
            return Optional.empty();
        }
        Inventory inventory = minecraft.player.getInventory();
        Object storage = menu.slots.getFirst().container;
        var storageSlots = new ArrayList<Integer>(27);
        for (int slot = 0; slot < 27; slot++) {
            if (menu.slots.get(slot).container != storage
                    || menu.slots.get(slot).getContainerSlot() != slot) {
                return Optional.empty();
            }
            storageSlots.add(slot);
        }
        var playerSlots = new ArrayList<Integer>(36);
        for (int offset = 0; offset < 36; offset++) {
            int slot = 27 + offset;
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
                screen, menu, snapshot,
                List.copyOf(storageSlots), List.copyOf(playerSlots)));
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
            List<Integer> storageSlots,
            List<Integer> playerSlots) {
        public Context {
            Objects.requireNonNull(screen, "screen");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(snapshot, "snapshot");
            storageSlots = List.copyOf(Objects.requireNonNull(storageSlots, "storageSlots"));
            playerSlots = List.copyOf(Objects.requireNonNull(playerSlots, "playerSlots"));
            if (storageSlots.size() != 27 || playerSlots.size() != 36) {
                throw new IllegalArgumentException("known Menu profile layout is invalid");
            }
        }

        public KnownMenuOperationRefs.Context referenceContext(
                UUID worldSessionId, long clientTick) {
            return new KnownMenuOperationRefs.Context(
                    worldSessionId,
                    screen,
                    menu.containerId,
                    MENU_TYPE,
                    snapshot.stateId(),
                    snapshot.slots().size(),
                    PROFILE_HASH,
                    snapshot.packetLedgerRevision(),
                    clientTick);
        }
    }
}
