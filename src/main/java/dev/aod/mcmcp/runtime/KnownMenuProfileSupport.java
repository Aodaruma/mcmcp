package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exact, versioned pure-storage profiles for the common Menu kernel. */
public final class KnownMenuProfileSupport {
    private static final String BACKPACK_MENU_TYPE = "sophisticatedbackpacks:backpack";
    private static final String BACKPACK_MENU_CLASS =
            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";
    private static final String BACKPACK_SCREEN_CLASS =
            "net.p3pp3rf1y.sophisticatedbackpacks.client.gui.BackpackScreen";
    private static final ArtifactRequirement BACKPACK_ARTIFACT = new ArtifactRequirement(
            "sophisticatedbackpacks", "3.25.90",
            "9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037");
    private static final ArtifactRequirement CORE_ARTIFACT = new ArtifactRequirement(
            "sophisticatedcore", "1.4.99",
            "f80b8868d15b59882c642ebaa020100e9d1f59cfbae8bdb6a584140b658fb10e");

    private static final List<Profile> PROFILES = List.of(
            genericProfile(1), genericProfile(2), genericProfile(3),
            genericProfile(4), genericProfile(5), genericProfile(6));
    private static final Map<String, Profile> PROFILES_BY_MENU_TYPE = PROFILES.stream()
            .collect(Collectors.toUnmodifiableMap(Profile::menuType, Function.identity()));
    private static final Profile DEFAULT_PROFILE = PROFILES.get(2);
    private static final ModProfile BACKPACK_PROFILE = backpackProfile();

    private static volatile boolean modProfilesInitialized;
    private static volatile BackpackRuntime backpackRuntime;

    /** Source-compatible aliases for the original accepted 9x3 profile. */
    public static final String PROFILE_ID = DEFAULT_PROFILE.profileId();
    public static final String PROFILE_HASH = DEFAULT_PROFILE.profileHash();
    public static final String MENU_TYPE = DEFAULT_PROFILE.menuType();

    private KnownMenuProfileSupport() {
    }

    /** Resolves installed optional profiles once; every mismatch leaves them disabled. */
    static void initializeModProfiles() {
        if (modProfilesInitialized) return;
        synchronized (KnownMenuProfileSupport.class) {
            if (modProfilesInitialized) return;
            try {
                if (activeArtifactMatches(BACKPACK_ARTIFACT)
                        && activeArtifactMatches(CORE_ARTIFACT)) {
                    backpackRuntime = loadBackpackRuntime();
                }
            } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                backpackRuntime = null;
            }
            modProfilesInitialized = true;
        }
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
                || !(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != minecraft.player.containerMenu
                || !minecraft.player.containerMenu.getCarried().isEmpty()) {
            return Optional.empty();
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        ContainerSyncSignals.Snapshot ledger = signals.snapshot(minecraft.level).orElse(null);
        if (!synchronizedMenuMatches(ledger, worldSessionId, menu)) {
            return Optional.empty();
        }
        ContainerSyncSignals.OpenScreenEvidence open = ledger.lastOpenScreen();
        ContainerSyncSignals.ContainerSnapshot snapshot = ledger.container();
        Profile vanilla = PROFILES_BY_MENU_TYPE.get(open.menuTypeId());
        if (vanilla != null) {
            return vanillaContext(minecraft.player, screen, menu, snapshot, vanilla);
        }
        if (!BACKPACK_MENU_TYPE.equals(open.menuTypeId())) {
            return Optional.empty();
        }
        initializeModProfiles();
        BackpackRuntime runtime = backpackRuntime;
        return runtime == null
                ? Optional.empty()
                : backpackContext(minecraft.player, screen, menu, snapshot, runtime);
    }

    private static boolean synchronizedMenuMatches(
            ContainerSyncSignals.Snapshot ledger,
            UUID worldSessionId,
            AbstractContainerMenu menu) {
        if (ledger == null
                || !ledger.sameSession(worldSessionId)
                || ledger.lastOpenScreen() == null
                || ledger.container() == null) {
            return false;
        }
        ContainerSyncSignals.OpenScreenEvidence open = ledger.lastOpenScreen();
        ContainerSyncSignals.ContainerSnapshot snapshot = ledger.container();
        var liveMenuType = BuiltInRegistries.MENU.getKey(menu.getType());
        if (!worldSessionId.equals(open.worldSessionId())
                || !worldSessionId.equals(snapshot.worldSessionId())
                || open.containerId() != menu.containerId
                || snapshot.containerId() != menu.containerId
                || !open.menuTypeId().equals(snapshot.menuTypeId())
                || liveMenuType == null
                || !open.menuTypeId().equals(liveMenuType.toString())
                || menu.slots.size() != snapshot.slots().size()
                || snapshot.packetLedgerRevision() <= open.packetLedgerRevision()
                || snapshot.receivedTick() < open.receivedTick()
                || snapshot.stateId() != menu.getStateId()
                || !snapshot.carried().empty()) {
            return false;
        }
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (!snapshot.slots().get(slot).equals(
                    ContainerSyncSignals.StackFingerprint.fromServerPacket(
                            menu.slots.get(slot).getItem()))) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Context> vanillaContext(
            LocalPlayer player,
            AbstractContainerScreen<?> screen,
            AbstractContainerMenu abstractMenu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            Profile profile) {
        if (screen.getClass() != ContainerScreen.class
                || abstractMenu.getClass() != ChestMenu.class
                || !(abstractMenu instanceof ChestMenu menu)
                || menu.getRowCount() != profile.rows()
                || menu.slots.size() != profile.totalSlotCount()) {
            return Optional.empty();
        }
        Inventory inventory = player.getInventory();
        Object storage = menu.slots.getFirst().container;
        var storageSlots = new ArrayList<Integer>(profile.storageSlotCount());
        for (int slot = 0; slot < profile.storageSlotCount(); slot++) {
            if (menu.slots.get(slot).container != storage
                    || menu.slots.get(slot).getContainerSlot() != slot) {
                return Optional.empty();
            }
            storageSlots.add(slot);
        }
        List<Integer> playerSlots = canonicalPlayerSlots(
                menu, inventory, profile.storageSlotCount());
        if (playerSlots == null) return Optional.empty();
        return Optional.of(new Context(
                player, screen, menu, snapshot, profile,
                storageSlots, storageSlots, playerSlots, List.of()));
    }

    private static Optional<Context> backpackContext(
            LocalPlayer player,
            AbstractContainerScreen<?> screen,
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            BackpackRuntime runtime) {
        if (screen.getClass() != runtime.screenClass()
                || menu.getClass() != runtime.menuClass()) {
            return Optional.empty();
        }
        try {
            int storageCount = (int) runtime.storageCount().invoke(menu);
            Object openContainer = runtime.openContainer().invoke(menu);
            Object extraSlots = runtime.extraSlots().invoke(menu);
            if (storageCount < 1
                    || storageCount > menu.slots.size() - 36
                    || !(openContainer instanceof Optional<?> open) || open.isPresent()
                    || !(extraSlots instanceof List<?> extra) || !extra.isEmpty()) {
                return Optional.empty();
            }
            List<Integer> playerSlots = canonicalPlayerSlots(
                    menu, player.getInventory(), storageCount);
            if (playerSlots == null) return Optional.empty();

            var storageSlots = new ArrayList<Integer>(storageCount);
            var transferableSlots = new ArrayList<Integer>(storageCount);
            for (int slot = 0; slot < storageCount; slot++) {
                if (!(boolean) runtime.isStorageSlot().invoke(menu, slot)) {
                    return Optional.empty();
                }
                storageSlots.add(slot);
                Slot source = menu.slots.get(slot);
                if (!(boolean) runtime.isInaccessibleSlot().invoke(menu, slot)
                        && source.mayPickup(player)) {
                    transferableSlots.add(slot);
                }
            }
            var protectedSlots = new ArrayList<Integer>();
            for (int slot = storageCount + 36; slot < menu.slots.size(); slot++) {
                if ((boolean) runtime.isStorageSlot().invoke(menu, slot)) {
                    return Optional.empty();
                }
                protectedSlots.add(slot);
            }
            return Optional.of(new Context(
                    player, screen, menu, snapshot, BACKPACK_PROFILE,
                    storageSlots, transferableSlots, playerSlots, protectedSlots));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static List<Integer> canonicalPlayerSlots(
            AbstractContainerMenu menu, Inventory inventory, int firstSlot) {
        if (firstSlot < 0 || firstSlot > menu.slots.size() - 36) return null;
        var playerSlots = new ArrayList<Integer>(36);
        for (int offset = 0; offset < 36; offset++) {
            int slot = firstSlot + offset;
            int expectedInventorySlot = offset < 27 ? offset + 9 : offset - 27;
            if (menu.slots.get(slot).container != inventory
                    || menu.slots.get(slot).getContainerSlot() != expectedInventorySlot) {
                return null;
            }
            playerSlots.add(slot);
        }
        return List.copyOf(playerSlots);
    }

    static List<Profile> profiles() {
        return PROFILES;
    }

    static ModProfile sophisticatedBackpackProfile() {
        return BACKPACK_PROFILE;
    }

    static List<ArtifactRequirement> sophisticatedBackpackArtifacts() {
        return List.of(BACKPACK_ARTIFACT, CORE_ARTIFACT);
    }

    static boolean hasBackpackMenuContract(Class<?> menuClass) {
        try {
            backpackMethods(menuClass);
            return true;
        } catch (ReflectiveOperationException invalid) {
            return false;
        }
    }

    static boolean isNormalSizedStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() <= stack.getMaxStackSize();
    }

    static boolean hasFullPlayerCapacity(ItemStack source, List<Slot> destinations) {
        return hasFullDestinationCapacity(source, destinations);
    }

    /** True only when ordinary quick-move can fit the complete source stack. */
    public static boolean hasFullDestinationCapacity(
            ItemStack source, List<Slot> destinations) {
        if (!isNormalSizedStack(source)) return false;
        int remainingCapacity = source.getCount();
        for (Slot destination : destinations) {
            if (!destination.mayPlace(source)) continue;
            ItemStack actual = destination.getItem();
            int maximum = destination.getMaxStackSize(source);
            if (actual.isEmpty()) {
                remainingCapacity -= maximum;
            } else if (ItemStack.isSameItemSameComponents(actual, source)) {
                remainingCapacity -= Math.max(0, maximum - actual.getCount());
            }
            if (remainingCapacity <= 0) return true;
        }
        return false;
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

    private static ModProfile backpackProfile() {
        String profileId = BACKPACK_MENU_TYPE
                + "-pure-storage@3.25.90+core-1.4.99+mc26.2";
        String canonical = "mcmcp-menu-profile/v1\n"
                + profileId + "\n"
                + BACKPACK_MENU_CLASS + "\n"
                + BACKPACK_SCREEN_CLASS + "\n"
                + BACKPACK_MENU_TYPE + "\n"
                + "artifacts=" + BACKPACK_ARTIFACT.canonical()
                + ";" + CORE_ARTIFACT.canonical() + "\n"
                + "slots=storage:getNumberOfStorageInventorySlots;player:next36;protected:rest\n"
                + "guards=no_open_upgrade,no_extra_slots,accessible,pickup,normal_stack,full_capacity\n"
                + "transfer_to_player=quick_move;protected_unchanged=true\n";
        return new ModProfile(profileId, profileHash(canonical), BACKPACK_MENU_TYPE);
    }

    private static BackpackRuntime loadBackpackRuntime()
            throws ReflectiveOperationException {
        ClassLoader loader = KnownMenuProfileSupport.class.getClassLoader();
        Class<?> menuClass = Class.forName(BACKPACK_MENU_CLASS, false, loader);
        Class<?> screenClass = Class.forName(BACKPACK_SCREEN_CLASS, false, loader);
        BackpackMethods methods = backpackMethods(menuClass);
        return new BackpackRuntime(
                menuClass, screenClass, methods.storageCount(), methods.isStorageSlot(),
                methods.isInaccessibleSlot(), methods.openContainer(), methods.extraSlots());
    }

    private static BackpackMethods backpackMethods(Class<?> menuClass)
            throws ReflectiveOperationException {
        return new BackpackMethods(
                requireMethod(menuClass, "getNumberOfStorageInventorySlots", int.class),
                requireMethod(menuClass, "isStorageInventorySlot", boolean.class, int.class),
                requireMethod(menuClass, "isInaccessibleSlot", boolean.class, int.class),
                requireMethod(menuClass, "getOpenContainer", Optional.class),
                requireMethod(menuClass, "getExtraSlots", List.class));
    }

    private static Method requireMethod(
            Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != returnType) {
            throw new NoSuchMethodException(owner.getName() + "#" + name);
        }
        return method;
    }

    private static boolean activeArtifactMatches(ArtifactRequirement requirement)
            throws IOException {
        ModList modList = ModList.get();
        if (modList == null) return false;
        IModFileInfo file = modList.getModFileById(requirement.modId());
        if (file == null) return false;
        String version = file.getMods().stream()
                .filter(mod -> requirement.modId().equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .findFirst().orElse(null);
        Path path = file.getFile().getFilePath();
        return Files.isRegularFile(path)
                && requirement.matches(version, sha256(path));
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path);
             var digesting = new DigestInputStream(input, digest)) {
            digesting.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String profileHash(String canonical) {
        return "sha256:" + HexFormat.of().formatHex(
                sha256Digest().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public sealed interface MenuProfile permits Profile, ModProfile {
        String profileId();

        String profileHash();

        String menuType();
    }

    public record Context(
            LocalPlayer player,
            AbstractContainerScreen<?> screen,
            AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot,
            MenuProfile profile,
            List<Integer> storageSlots,
            List<Integer> transferableStorageSlots,
            List<Integer> playerSlots,
            List<Integer> protectedSlots) {
        public Context {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(screen, "screen");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(profile, "profile");
            storageSlots = List.copyOf(Objects.requireNonNull(storageSlots, "storageSlots"));
            transferableStorageSlots = List.copyOf(Objects.requireNonNull(
                    transferableStorageSlots, "transferableStorageSlots"));
            playerSlots = List.copyOf(Objects.requireNonNull(playerSlots, "playerSlots"));
            protectedSlots = List.copyOf(Objects.requireNonNull(protectedSlots, "protectedSlots"));
            var allSlots = new HashSet<Integer>();
            allSlots.addAll(storageSlots);
            allSlots.addAll(playerSlots);
            allSlots.addAll(protectedSlots);
            if (storageSlots.isEmpty()
                    || playerSlots.size() != 36
                    || !storageSlots.containsAll(transferableStorageSlots)
                    || allSlots.size() != menu.slots.size()
                    || storageSlots.size() + playerSlots.size() + protectedSlots.size()
                            != menu.slots.size()
                    || allSlots.stream().anyMatch(slot -> slot < 0 || slot >= menu.slots.size())
                    || !profile.menuType().equals(snapshot.menuTypeId())) {
                throw new IllegalArgumentException("known Menu profile layout is invalid");
            }
        }

        /** Revalidates every stack-dependent precondition immediately before dispatch. */
        public boolean canTransferEntireStack(int sourceSlot) {
            if (!transferableStorageSlots.contains(sourceSlot)) return false;
            Slot sourceSlotView = menu.slots.get(sourceSlot);
            ItemStack source = sourceSlotView.getItem();
            return sourceSlotView.mayPickup(player)
                    && hasFullPlayerCapacity(source, playerSlots.stream()
                            .map(menu.slots::get).toList());
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
            int totalSlotCount) implements MenuProfile {
        public Profile {
            requireProfileIdentity(profileId, profileHash, menuType);
            if (rows < 1 || rows > 6
                    || storageSlotCount != rows * 9
                    || totalSlotCount != storageSlotCount + 36) {
                throw new IllegalArgumentException("known Menu profile shape is invalid");
            }
        }
    }

    public record ModProfile(
            String profileId,
            String profileHash,
            String menuType) implements MenuProfile {
        public ModProfile {
            requireProfileIdentity(profileId, profileHash, menuType);
        }
    }

    static record ArtifactRequirement(String modId, String version, String sha256) {
        ArtifactRequirement {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sha256, "sha256");
            if (!modId.matches("[a-z0-9_.-]+") || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid artifact requirement");
            }
        }

        boolean matches(String actualVersion, String actualSha256) {
            return version.equals(actualVersion) && sha256.equals(actualSha256);
        }

        String canonical() {
            return modId + "@" + version + "#sha256:" + sha256;
        }
    }

    private static void requireProfileIdentity(
            String profileId, String profileHash, String menuType) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(profileHash, "profileHash");
        Objects.requireNonNull(menuType, "menuType");
        if (!profileHash.matches("sha256:[0-9a-f]{64}")
                || !menuType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid known Menu profile identity");
        }
    }

    private record BackpackMethods(
            Method storageCount,
            Method isStorageSlot,
            Method isInaccessibleSlot,
            Method openContainer,
            Method extraSlots) {
    }

    private record BackpackRuntime(
            Class<?> menuClass,
            Class<?> screenClass,
            Method storageCount,
            Method isStorageSlot,
            Method isInaccessibleSlot,
            Method openContainer,
            Method extraSlots) {
    }
}
