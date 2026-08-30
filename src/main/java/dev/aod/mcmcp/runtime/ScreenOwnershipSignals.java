package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fail-closed ownership gate for automation-initiated container screens.
 *
 * <p>An open packet and a matching {@link net.neoforged.neoforge.client.event.ScreenEvent.Opening}
 * are necessary but not sufficient. Ownership begins only after a later full-content packet for
 * the same world session, container id, and menu type has supplied an immutable server snapshot.</p>
 */
public final class ScreenOwnershipSignals {
    private static final ScreenOwnershipSignals GLOBAL = new ScreenOwnershipSignals(
            ContainerSyncSignals.global());

    private final Object gate = new Object();
    private final ContainerSyncSignals containerSignals;
    private final Core core = new Core();
    private ClientLevel boundLevel;
    private long clientTick;
    private volatile Consumer<String> failureHandler = ignored -> { };

    public ScreenOwnershipSignals(ContainerSyncSignals containerSignals) {
        this.containerSignals = Objects.requireNonNull(containerSignals, "containerSignals");
    }

    public static ScreenOwnershipSignals global() {
        return GLOBAL;
    }

    /** Registers the client-thread emergency-stop bridge used by packet-side failures. */
    public void setFailureHandler(Consumer<String> handler) {
        failureHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Explicitly binds the live level object to the runtime's opaque world-session UUID. */
    public ContainerSyncSignals.Snapshot bindWorldSession(
            ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Transition transition;
        ContainerSyncSignals.Snapshot ledger;
        synchronized (gate) {
            Transition levelChange = boundLevel != null && boundLevel != level
                    ? core.clearLevel()
                    : Transition.irrelevant();
            if (boundLevel != null && boundLevel != level) {
                containerSignals.closeLevel(boundLevel);
            }
            ledger = containerSignals.bindAndSnapshot(level, worldSessionId);
            Transition sessionChange = core.bindSession(
                    worldSessionId, ledger.packetLedgerRevision());
            transition = levelChange.failedNow() ? levelChange : sessionChange;
            boundLevel = level;
        }
        notifyPacketFailure(transition);
        return ledger;
    }

    /** Arms exactly one expected open. Existing/pending screens are never adopted. */
    public boolean beginExpectedOpen(ExpectedOpenToken token) {
        Objects.requireNonNull(token, "token");
        synchronized (gate) {
            long revision = boundLevel == null
                    ? 0
                    : containerSignals.snapshot(boundLevel)
                            .map(ContainerSyncSignals.Snapshot::packetLedgerRevision)
                            .orElse(0L);
            return core.beginExpectedOpen(token, clientTick, revision);
        }
    }

    public long currentTick() {
        synchronized (gate) {
            return clientTick;
        }
    }

    /** Called before the runtime's pre-tick so an ownership deadline stops work first. */
    public void onClientTick() {
        Transition transition;
        synchronized (gate) {
            if (clientTick == Long.MAX_VALUE) {
                transition = core.failIfActive("screen_clock_exhausted");
            } else {
                clientTick++;
                transition = core.expire(clientTick);
            }
        }
        notifyPacketFailure(transition);
    }

    /** Packet bridge; injection occurs after PacketUtils' client-thread handoff. */
    public Transition onOpenScreen(
            ClientLevel level, int containerId, String menuTypeId, long receivedTick) {
        Objects.requireNonNull(level, "level");
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var evidence = containerSignals.onOpenScreen(
                        level, containerId, menuTypeId, receivedTick);
                transition = evidence.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onOpenScreen(evidence.orElseThrow().snapshot().lastOpenScreen());
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    /** Convenience form used by adapters already bound to the current level. */
    public Transition onOpenScreen(int containerId, String menuTypeId, long receivedTick) {
        ClientLevel level;
        synchronized (gate) {
            level = boundLevel;
        }
        return level == null
                ? Transition.irrelevant()
                : onOpenScreen(level, containerId, menuTypeId, receivedTick);
    }

    public Transition onFullContent(
            ClientLevel level,
            int containerId,
            String observedMenuTypeId,
            int stateId,
            List<ContainerSyncSignals.StackFingerprint> slots,
            ContainerSyncSignals.StackFingerprint carried,
            boolean liveScreenMatches,
            long receivedTick) {
        Objects.requireNonNull(level, "level");
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onFullContent(level, containerId,
                        observedMenuTypeId, stateId, slots, carried, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onFullContent(
                                result.orElseThrow(), liveScreenMatches);
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    /**
     * Parent-adapter API with the packet fields only. Menu metadata is read from the active screen,
     * while slot/carried values remain exclusively packet-derived.
     */
    public Transition onFullContent(
            int containerId,
            int stateId,
            List<ContainerSyncSignals.StackFingerprint> slots,
            ContainerSyncSignals.StackFingerprint carried,
            long receivedTick) {
        var view = liveMenuView();
        ClientLevel level;
        synchronized (gate) {
            level = boundLevel;
        }
        if (level == null || view.isEmpty()) {
            Transition transition;
            synchronized (gate) {
                transition = core.failIfActive("owned_screen_missing_during_full_content");
            }
            notifyPacketFailure(transition);
            return transition;
        }
        var menu = view.orElseThrow();
        boolean exact = menu.containerId() == containerId;
        return onFullContent(level, containerId, menu.menuTypeId(), stateId,
                slots, carried, exact, receivedTick);
    }

    public Transition onSlot(
            ClientLevel level,
            int containerId,
            String observedMenuTypeId,
            int stateId,
            int slot,
            ContainerSyncSignals.StackFingerprint stack,
            boolean liveScreenMatches,
            long receivedTick) {
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onSlot(level, containerId, observedMenuTypeId,
                        stateId, slot, stack, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onIncrementalContent(
                                result.orElseThrow(), liveScreenMatches, false);
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    public Transition onPlayerInventorySlot(
            ClientLevel level,
            int containerId,
            String observedMenuTypeId,
            List<Integer> menuSlots,
            ContainerSyncSignals.StackFingerprint stack,
            boolean liveScreenMatches,
            long receivedTick) {
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onPlayerInventorySlot(level, containerId,
                        observedMenuTypeId, menuSlots, stack, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onIncrementalContent(
                                result.orElseThrow(), liveScreenMatches, false);
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    public Transition onCarried(
            ClientLevel level,
            int containerId,
            String observedMenuTypeId,
            ContainerSyncSignals.StackFingerprint carried,
            boolean liveScreenMatches,
            long receivedTick) {
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onCarried(level, containerId,
                        observedMenuTypeId, carried, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onIncrementalContent(
                                result.orElseThrow(), liveScreenMatches, true);
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    /** Packet bridge for server-authored menu data such as brewing time and fuel. */
    public Transition onData(
            ClientLevel level,
            int containerId,
            String observedMenuTypeId,
            int dataId,
            int value,
            boolean liveScreenMatches,
            long receivedTick) {
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onData(level, containerId,
                        observedMenuTypeId, dataId, value, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onIncrementalContent(
                                result.orElseThrow(), liveScreenMatches, false);
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    public Transition onContainerClose(
            ClientLevel level, int containerId, long receivedTick) {
        Transition transition;
        synchronized (gate) {
            if (level != boundLevel) {
                transition = core.failIfActive("screen_world_identity_mismatch");
            } else {
                var result = containerSignals.onClose(level, containerId, receivedTick);
                transition = result.isEmpty()
                        ? core.failIfActive("screen_world_session_unbound")
                        : core.onContainerClose(result.orElseThrow().snapshot().lastClose());
            }
        }
        notifyPacketFailure(transition);
        return transition;
    }

    /** Exact expected packet-to-screen transition; false preserves Phase 2-4 stop behavior. */
    public boolean allowScreenOpening(Screen screen) {
        var view = menuView(screen);
        synchronized (gate) {
            var transition = view.isEmpty()
                    ? core.failIfActive("unexpected_screen_opened")
                    : core.allowScreenOpening(
                            view.orElseThrow().containerId(),
                            view.orElseThrow().menuTypeId(),
                            clientTick);
            return transition.allowed();
        }
    }

    /** Returns a reason only when an owned/pending screen closed unexpectedly. */
    public Optional<String> onScreenClosing(Screen screen) {
        var view = menuView(screen);
        synchronized (gate) {
            var transition = view.isEmpty()
                    ? core.failIfActive("unexpected_screen_closed")
                    : core.onScreenClosing(
                            view.orElseThrow().containerId(), view.orElseThrow().menuTypeId());
            return transition.failedNow()
                    ? Optional.of(transition.reason())
                    : Optional.empty();
        }
    }

    /**
     * Cancels one routine's authority and returns only a cleanup decision. The caller may close an
     * exact owned menu best-effort; this layer never asks it to synthesize a cursor rescue click.
     */
    public CleanupDecision cancelRoutine(UUID routineId) {
        Objects.requireNonNull(routineId, "routineId");
        var live = liveMenuView();
        synchronized (gate) {
            return core.cancelRoutine(routineId, live.orElse(null));
        }
    }

    /**
     * Cancels authority after a normal block-use prediction crossed the network boundary. The
     * exact expected-open tombstone cannot be retired by a wall-clock deadline: only an ACK which
     * is causally after the server's use handling, or loss of the old connection context, proves
     * that no matching OpenScreen remains in flight.
     */
    public CleanupDecision cancelRoutineAfterPredictedUse(
            UUID routineId, CausalBarrierStatus barrierStatus) {
        Objects.requireNonNull(routineId, "routineId");
        Objects.requireNonNull(barrierStatus, "barrierStatus");
        var live = liveMenuView();
        synchronized (gate) {
            return core.cancelRoutine(routineId, live.orElse(null), barrierStatus);
        }
    }

    /** Releases only the named routine's old screen context after identity replacement. */
    public boolean releaseRoutineOnIdentityLoss(UUID routineId) {
        Objects.requireNonNull(routineId, "routineId");
        synchronized (gate) {
            return core.releaseRoutineOnIdentityLoss(routineId);
        }
    }

    /** Invalidates cursor evidence immediately before one Agent-owned container click. */
    public boolean invalidateServerCursorProof(UUID routineId, long dispatchRevision) {
        Objects.requireNonNull(routineId, "routineId");
        if (dispatchRevision < 0L) {
            throw new IllegalArgumentException("negative dispatch revision");
        }
        synchronized (gate) {
            return core.invalidateServerCursorProof(routineId, dispatchRevision);
        }
    }

    public Optional<OwnedScreenSession> ownedSession() {
        synchronized (gate) {
            return Optional.ofNullable(core.snapshot().ownedSession());
        }
    }

    public Snapshot snapshot() {
        synchronized (gate) {
            return core.snapshot();
        }
    }

    /** Lifecycle fence for disconnect, respawn level replacement, and clearLevel. */
    public void clearLevel(ClientLevel level) {
        Transition transition;
        synchronized (gate) {
            if (level == null || level != boundLevel) {
                containerSignals.closeLevel(level);
                return;
            }
            transition = core.clearLevel();
            containerSignals.closeLevel(level);
            boundLevel = null;
        }
        notifyPacketFailure(transition);
    }

    private Optional<MenuView> liveMenuView() {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return Optional.empty();
        }
        var screen = menuView(minecraft.gui.screen());
        if (screen.isEmpty()) {
            return Optional.empty();
        }
        var view = screen.orElseThrow();
        return minecraft.player.containerMenu.containerId == view.containerId()
                        && menuTypeId(minecraft.player.containerMenu.getType())
                                .equals(view.menuTypeId())
                ? screen
                : Optional.empty();
    }

    private static Optional<MenuView> menuView(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return Optional.empty();
        }
        var menu = containerScreen.getMenu();
        return registeredMenuTypeId(menu)
                .map(menuTypeId -> new MenuView(menu.containerId, menuTypeId));
    }

    public static Optional<String> registeredMenuTypeId(AbstractContainerMenu menu) {
        Objects.requireNonNull(menu, "menu");
        try {
            return Optional.of(menuTypeId(menu.getType()));
        } catch (UnsupportedOperationException untypedMenu) {
            return Optional.empty();
        }
    }

    public static String menuTypeId(net.minecraft.world.inventory.MenuType<?> menuType) {
        Objects.requireNonNull(menuType, "menuType");
        var id = BuiltInRegistries.MENU.getKey(menuType);
        if (id == null) {
            throw new IllegalArgumentException("unregistered menu type");
        }
        return id.toString();
    }

    private void notifyPacketFailure(Transition transition) {
        if (transition.failedNow()) {
            failureHandler.accept(transition.reason());
        }
    }

    public enum Phase {
        IDLE,
        EXPECTING_OPEN_PACKET,
        EXPECTING_SCREEN,
        EXPECTING_FULL_CONTENT,
        OWNED,
        CLOSING,
        FAILED
    }

    public enum CausalBarrierStatus {
        NOT_REQUIRED,
        WAITING_ACK,
        ACKNOWLEDGED,
        IDENTITY_RELEASED,
        INCOMPATIBLE
    }

    public record OwnedScreenSession(
            ExpectedOpenToken token,
            ContainerSyncSignals.ContainerSnapshot serverSnapshot,
            long ownedAtTick) {
        public OwnedScreenSession {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(serverSnapshot, "serverSnapshot");
        }
    }

    public record CleanupDecision(
            boolean authorityMatched,
            boolean closeMenuBestEffort,
            int containerId,
            String menuTypeId,
            boolean serverCursorEmpty,
            boolean rescueClickAllowed,
            String reason) {
        public CleanupDecision {
            Objects.requireNonNull(reason, "reason");
            if (rescueClickAllowed) {
                throw new IllegalArgumentException("cursor rescue clicks are never allowed");
            }
            if (!closeMenuBestEffort && (containerId != -1 || menuTypeId != null)) {
                throw new IllegalArgumentException("non-close decision must not name a menu");
            }
        }

        static CleanupDecision none(boolean matched, String reason) {
            return new CleanupDecision(matched, false, -1, null,
                    false, false, reason);
        }
    }

    public record Snapshot(
            Phase phase,
            UUID boundWorldSessionId,
            ExpectedOpenToken expectedOpen,
            OwnedScreenSession ownedSession,
            long packetLedgerRevision,
            String failureReason,
            boolean cancelBeforeOwnership,
            boolean screenMaterialized,
            boolean causalCancelRequired,
            boolean everOwned,
            boolean lastServerCursorProven,
            boolean lastServerCursorEmpty,
            long lastServerCursorProofRevision,
            long cursorProofRequiredAfterRevision) {
        public Snapshot {
            Objects.requireNonNull(phase, "phase");
        }

        public boolean owned() {
            return phase == Phase.OWNED && ownedSession != null;
        }
    }

    public record Transition(boolean relevant, boolean allowed, boolean failedNow, String reason) {
        public Transition {
            if (failedNow && (reason == null || allowed)) {
                throw new IllegalArgumentException("failed transition requires a reason and cannot be allowed");
            }
        }

        static Transition irrelevant() {
            return new Transition(false, false, false, null);
        }

        static Transition accepted() {
            return new Transition(true, true, false, null);
        }

        static Transition waiting() {
            return new Transition(true, false, false, null);
        }

        static Transition failed(String reason) {
            return new Transition(true, false, true, Objects.requireNonNull(reason, "reason"));
        }
    }

    record MenuView(int containerId, String menuTypeId) {
    }

    /** Package-private deterministic state machine used by pure contract tests. */
    static final class Core {
        private UUID boundWorldSessionId;
        private Phase phase = Phase.IDLE;
        private ExpectedOpenToken expectedOpen;
        private int containerId = -1;
        private String menuTypeId;
        private long baselineLedgerRevision;
        private long openLedgerRevision;
        private long latestLedgerRevision;
        private OwnedScreenSession ownedSession;
        private String failureReason;
        private boolean cancelBeforeOwnership;
        private boolean screenMaterialized;
        private boolean causalCancelRequired;
        private boolean everOwned;
        private boolean lastServerCursorProven;
        private boolean lastServerCursorEmpty;
        private long lastServerCursorProofRevision = -1L;
        private long cursorProofRequiredAfterRevision = -1L;

        Transition bindSession(UUID worldSessionId, long ledgerRevision) {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (worldSessionId.equals(boundWorldSessionId)) {
                latestLedgerRevision = ledgerRevision;
                return Transition.irrelevant();
            }
            Transition prior = failIfActive("screen_world_session_changed");
            resetActive();
            boundWorldSessionId = worldSessionId;
            latestLedgerRevision = ledgerRevision;
            return prior;
        }

        boolean beginExpectedOpen(
                ExpectedOpenToken token, long nowTick, long ledgerRevision) {
            Objects.requireNonNull(token, "token");
            if (boundWorldSessionId == null
                    || !boundWorldSessionId.equals(token.worldSessionId())
                    || phase != Phase.IDLE
                    || token.deadlineTick() < nowTick) {
                failIfActive(phase == Phase.IDLE
                        ? "expected_open_session_or_deadline_invalid"
                        : "expected_open_already_active");
                return false;
            }
            expectedOpen = token;
            baselineLedgerRevision = ledgerRevision;
            latestLedgerRevision = ledgerRevision;
            containerId = -1;
            menuTypeId = null;
            ownedSession = null;
            failureReason = null;
            cancelBeforeOwnership = false;
            screenMaterialized = false;
            causalCancelRequired = false;
            everOwned = false;
            lastServerCursorProven = false;
            lastServerCursorEmpty = false;
            lastServerCursorProofRevision = -1L;
            cursorProofRequiredAfterRevision = -1L;
            phase = Phase.EXPECTING_OPEN_PACKET;
            return true;
        }

        Transition onOpenScreen(ContainerSyncSignals.OpenScreenEvidence evidence) {
            Objects.requireNonNull(evidence, "evidence");
            latestLedgerRevision = evidence.packetLedgerRevision();
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase != Phase.EXPECTING_OPEN_PACKET) {
                return fail("unexpected_additional_open_screen_packet");
            }
            if (!evidence.worldSessionId().equals(expectedOpen.worldSessionId())) {
                return fail("open_screen_world_session_mismatch");
            }
            if (!cancelBeforeOwnership
                    && evidence.receivedTick() > expectedOpen.deadlineTick()) {
                return fail("expected_open_deadline_exceeded");
            }
            if (evidence.packetLedgerRevision() <= baselineLedgerRevision) {
                return fail("stale_open_screen_packet");
            }
            if (evidence.containerId() != 0
                    && evidence.menuTypeId().equals(expectedOpen.menuTypeId())) {
                containerId = evidence.containerId();
                menuTypeId = evidence.menuTypeId();
                openLedgerRevision = evidence.packetLedgerRevision();
                phase = Phase.EXPECTING_SCREEN;
                return Transition.accepted();
            }
            return fail("open_screen_identity_mismatch");
        }

        Transition allowScreenOpening(int observedContainerId, String observedMenuTypeId, long tick) {
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase != Phase.EXPECTING_SCREEN
                    || (!cancelBeforeOwnership && tick > expectedOpen.deadlineTick())
                    || observedContainerId != containerId
                    || !Objects.equals(observedMenuTypeId, menuTypeId)) {
                return fail("unexpected_screen_opened");
            }
            phase = Phase.EXPECTING_FULL_CONTENT;
            screenMaterialized = true;
            return Transition.accepted();
        }

        Transition onFullContent(
                ContainerSyncSignals.RecordResult result, boolean liveScreenMatches) {
            Objects.requireNonNull(result, "result");
            latestLedgerRevision = result.snapshot().packetLedgerRevision();
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase == Phase.CLOSING) {
                return Transition.waiting();
            }
            if (phase != Phase.EXPECTING_FULL_CONTENT && phase != Phase.OWNED) {
                return fail("full_content_before_expected_screen");
            }
            var content = result.snapshot().container();
            if (!result.applied()
                    || content == null
                    || !liveScreenMatches
                    || !content.worldSessionId().equals(expectedOpen.worldSessionId())
                    || content.containerId() != containerId
                    || !content.menuTypeId().equals(menuTypeId)
                    || (!cancelBeforeOwnership
                            && content.receivedTick() > expectedOpen.deadlineTick())
                    || (phase == Phase.EXPECTING_FULL_CONTENT
                            && content.packetLedgerRevision() <= openLedgerRevision)) {
                return fail("full_content_identity_or_freshness_mismatch");
            }
            ownedSession = new OwnedScreenSession(expectedOpen, content, content.receivedTick());
            everOwned = true;
            recordServerCursorProof(content);
            phase = Phase.OWNED;
            return Transition.accepted();
        }

        Transition onIncrementalContent(
                ContainerSyncSignals.RecordResult result, boolean liveScreenMatches) {
            return onIncrementalContent(result, liveScreenMatches, false);
        }

        Transition onIncrementalContent(
                ContainerSyncSignals.RecordResult result,
                boolean liveScreenMatches,
                boolean cursorAuthored) {
            Objects.requireNonNull(result, "result");
            latestLedgerRevision = result.snapshot().packetLedgerRevision();
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase == Phase.CLOSING) {
                return Transition.waiting();
            }
            var content = result.snapshot().container();
            if (phase != Phase.OWNED
                    || !result.applied()
                    || content == null
                    || !liveScreenMatches
                    || content.containerId() != containerId
                    || !content.menuTypeId().equals(menuTypeId)
                    || !content.worldSessionId().equals(expectedOpen.worldSessionId())) {
                return fail("container_incremental_sync_mismatch");
            }
            ownedSession = new OwnedScreenSession(
                    expectedOpen, content, ownedSession.ownedAtTick());
            everOwned = true;
            if (cursorAuthored) {
                recordServerCursorProof(content);
            }
            return Transition.accepted();
        }

        Transition onContainerClose(ContainerSyncSignals.CloseEvidence evidence) {
            Objects.requireNonNull(evidence, "evidence");
            latestLedgerRevision = evidence.packetLedgerRevision();
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase == Phase.CLOSING
                    && evidence.worldSessionId().equals(expectedOpen.worldSessionId())
                    && evidence.containerId() == containerId) {
                return Transition.accepted();
            }
            return fail(evidence.containerId() == containerId
                    ? "unexpected_container_close"
                    : "container_close_identity_mismatch");
        }

        Transition onScreenClosing(int observedContainerId, String observedMenuTypeId) {
            if (!active()) {
                return Transition.irrelevant();
            }
            if (phase == Phase.CLOSING
                    && observedContainerId == containerId
                    && Objects.equals(observedMenuTypeId, menuTypeId)) {
                resetActive();
                return Transition.accepted();
            }
            return fail("unexpected_screen_closed");
        }

        Transition expire(long tick) {
            if (!active() || phase == Phase.OWNED || phase == Phase.CLOSING
                    || tick <= expectedOpen.deadlineTick()) {
                return Transition.irrelevant();
            }
            if (cancelBeforeOwnership && phase == Phase.EXPECTING_OPEN_PACKET
                    && !causalCancelRequired) {
                // No server open packet arrived inside its reserved window. Retiring the
                // legacy non-predicted tombstone here preserves existing container behavior.
                resetActive();
                return Transition.accepted();
            }
            if (cancelBeforeOwnership) {
                // A predicted-use tombstone is packet-causal, not time-causal. Past the action
                // deadline it remains passive and can only be retired by its ACK or identity loss.
                return Transition.waiting();
            }
            return fail("expected_screen_deadline_exceeded");
        }

        Transition failIfActive(String reason) {
            return active() ? fail(reason) : Transition.irrelevant();
        }

        CleanupDecision cancelRoutine(UUID routineId, MenuView liveMenu) {
            return cancelRoutine(routineId, liveMenu, CausalBarrierStatus.NOT_REQUIRED);
        }

        CleanupDecision cancelRoutine(
                UUID routineId,
                MenuView liveMenu,
                CausalBarrierStatus barrierStatus) {
            Objects.requireNonNull(routineId, "routineId");
            Objects.requireNonNull(barrierStatus, "barrierStatus");
            if (expectedOpen == null || !routineId.equals(expectedOpen.routineId())) {
                return CleanupDecision.none(false, "routine_does_not_own_screen_authority");
            }
            if (barrierStatus != CausalBarrierStatus.NOT_REQUIRED) {
                causalCancelRequired = true;
            }
            if (phase == Phase.CLOSING) {
                return CleanupDecision.none(true, "owned_screen_close_pending");
            }
            if (phase == Phase.OWNED) {
                boolean exactLiveMenu = liveMenu != null
                        && liveMenu.containerId() == containerId
                        && liveMenu.menuTypeId().equals(menuTypeId);
                if (!exactLiveMenu || ownedSession == null) {
                    fail("owned_screen_missing_during_cleanup");
                    return CleanupDecision.none(true, "owned_screen_identity_ambiguous");
                }
                if (!lastServerCursorProven) {
                    return CleanupDecision.none(true,
                            "owned_server_cursor_proof_pending");
                }
                boolean cursorEmpty = lastServerCursorEmpty;
                everOwned = true;
                phase = Phase.CLOSING;
                return new CleanupDecision(true, true, containerId, menuTypeId,
                        cursorEmpty, false,
                        cursorEmpty
                                ? "close_owned_menu_best_effort"
                                : "close_owned_menu_without_cursor_rescue_click");
            }
            if (phase == Phase.EXPECTING_OPEN_PACKET
                    || phase == Phase.EXPECTING_SCREEN
                    || phase == Phase.EXPECTING_FULL_CONTENT) {
                if (!causalCancelRequired
                        && barrierStatus == CausalBarrierStatus.NOT_REQUIRED) {
                    // Legacy container callers do not expose a prediction sequence and discard
                    // their owner immediately after cancelRoutine returns. Preserve that contract:
                    // only the explicit predicted-use API may create a deferred tombstone.
                    resetActive();
                    return CleanupDecision.none(true,
                            "screen_authority_canceled_before_ownership");
                }
                // A normal-use packet may already have crossed the gameplay boundary. A
                // predicted use retains authority until its causally-later ACK proves that no
                // matching OpenScreen remains in flight. If the screen exists, cleanup closes the
                // exact menu; full content is not required because no Agent click has happened.
                cancelBeforeOwnership = true;
                if (phase == Phase.EXPECTING_OPEN_PACKET
                        && causalCancelRequired
                        && (barrierStatus == CausalBarrierStatus.ACKNOWLEDGED
                                || barrierStatus == CausalBarrierStatus.IDENTITY_RELEASED)) {
                    resetActive();
                    return CleanupDecision.none(true,
                            "predicted_open_retired_after_causal_barrier");
                }
                if (phase == Phase.EXPECTING_FULL_CONTENT && screenMaterialized) {
                    boolean exactLiveMenu = liveMenu != null
                            && liveMenu.containerId() == containerId
                            && liveMenu.menuTypeId().equals(menuTypeId);
                    if (exactLiveMenu) {
                        phase = Phase.CLOSING;
                        return new CleanupDecision(true, true, containerId, menuTypeId,
                                lastServerCursorProven && lastServerCursorEmpty, false,
                                "close_materialized_menu_before_agent_click");
                    }
                }
                return CleanupDecision.none(true, "screen_authority_cancel_pending");
            }
            if (phase == Phase.FAILED) {
                boolean causalBarrierSettled = !causalCancelRequired
                        || barrierStatus == CausalBarrierStatus.ACKNOWLEDGED
                        || barrierStatus == CausalBarrierStatus.IDENTITY_RELEASED;
                boolean exactLiveMenu = liveMenu != null
                        && containerId != -1
                        && liveMenu.containerId() == containerId
                        && liveMenu.menuTypeId().equals(menuTypeId);
                if (exactLiveMenu
                        && (!everOwned
                                || lastServerCursorProven && lastServerCursorEmpty)) {
                    phase = Phase.CLOSING;
                    return new CleanupDecision(true, true, containerId, menuTypeId,
                            lastServerCursorProven && lastServerCursorEmpty, false,
                            "close_failed_exact_menu_best_effort");
                }
                if ((!screenMaterialized || liveMenu == null)
                        && causalBarrierSettled
                        && (!everOwned
                                || lastServerCursorProven && lastServerCursorEmpty)) {
                    resetActive();
                    return CleanupDecision.none(true, "failed_screen_authority_released");
                }
                return CleanupDecision.none(true, "failed_screen_close_pending");
            }
            resetActive();
            return CleanupDecision.none(true, "screen_authority_canceled_before_ownership");
        }

        boolean releaseRoutineOnIdentityLoss(UUID routineId) {
            Objects.requireNonNull(routineId, "routineId");
            if (expectedOpen == null || !routineId.equals(expectedOpen.routineId())) {
                return false;
            }
            resetActive();
            return true;
        }

        boolean invalidateServerCursorProof(UUID routineId, long dispatchRevision) {
            Objects.requireNonNull(routineId, "routineId");
            if (expectedOpen == null
                    || !routineId.equals(expectedOpen.routineId())
                    || phase != Phase.OWNED
                    || dispatchRevision < latestLedgerRevision) {
                return false;
            }
            cursorProofRequiredAfterRevision = Math.max(
                    cursorProofRequiredAfterRevision, dispatchRevision);
            lastServerCursorProven = false;
            lastServerCursorEmpty = false;
            lastServerCursorProofRevision = -1L;
            return true;
        }

        Transition clearLevel() {
            Transition transition = failIfActive("screen_level_cleared");
            boundWorldSessionId = null;
            return transition;
        }

        Snapshot snapshot() {
            return new Snapshot(phase, boundWorldSessionId, expectedOpen,
                    ownedSession, latestLedgerRevision, failureReason,
                    cancelBeforeOwnership, screenMaterialized, causalCancelRequired,
                    everOwned, lastServerCursorProven, lastServerCursorEmpty,
                    lastServerCursorProofRevision, cursorProofRequiredAfterRevision);
        }

        private boolean active() {
            return switch (phase) {
                case EXPECTING_OPEN_PACKET, EXPECTING_SCREEN,
                        EXPECTING_FULL_CONTENT, OWNED, CLOSING -> true;
                case IDLE, FAILED -> false;
            };
        }

        private Transition fail(String reason) {
            failureReason = Objects.requireNonNull(reason, "reason");
            ownedSession = null;
            phase = Phase.FAILED;
            return Transition.failed(reason);
        }

        private void resetActive() {
            phase = Phase.IDLE;
            expectedOpen = null;
            containerId = -1;
            menuTypeId = null;
            baselineLedgerRevision = 0;
            openLedgerRevision = 0;
            ownedSession = null;
            failureReason = null;
            cancelBeforeOwnership = false;
            screenMaterialized = false;
            causalCancelRequired = false;
            everOwned = false;
            lastServerCursorProven = false;
            lastServerCursorEmpty = false;
            lastServerCursorProofRevision = -1L;
            cursorProofRequiredAfterRevision = -1L;
        }

        private void recordServerCursorProof(
                ContainerSyncSignals.ContainerSnapshot content) {
            if (content.packetLedgerRevision() <= cursorProofRequiredAfterRevision) {
                return;
            }
            lastServerCursorProven = true;
            lastServerCursorEmpty = content.carried().empty();
            lastServerCursorProofRevision = content.packetLedgerRevision();
        }
    }
}
