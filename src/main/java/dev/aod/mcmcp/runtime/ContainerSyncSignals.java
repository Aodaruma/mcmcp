package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Immutable, session-fenced ledger of inbound container packets.
 *
 * <p>Only packet payloads enter this ledger. In particular, menu slot values produced by client
 * prediction are never sampled as server evidence. Minecraft's {@code stateId} is retained as an
 * opaque value: no ordering comparison is made, so protocol wraparound is harmless.</p>
 */
public final class ContainerSyncSignals {
    private static final ContainerSyncSignals GLOBAL = new ContainerSyncSignals();

    private final Object gate = new Object();
    private final Map<ClientLevel, SessionChannel> channels = new WeakHashMap<>();

    public static ContainerSyncSignals global() {
        return GLOBAL;
    }

    public Snapshot bindAndSnapshot(ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        synchronized (gate) {
            return channels.computeIfAbsent(level, ignored -> new SessionChannel())
                    .bindAndSnapshot(worldSessionId);
        }
    }

    public Optional<Snapshot> snapshot(ClientLevel level) {
        if (level == null) {
            return Optional.empty();
        }
        synchronized (gate) {
            var channel = channels.get(level);
            return channel == null || !channel.bound()
                    ? Optional.empty()
                    : Optional.of(channel.snapshot());
        }
    }

    public Optional<RecordResult> onOpenScreen(
            ClientLevel level, int containerId, String menuTypeId, long receivedTick) {
        return record(level, channel -> channel.openScreen(containerId, menuTypeId, receivedTick));
    }

    public Optional<RecordResult> onFullContent(
            ClientLevel level,
            int containerId,
            String menuTypeId,
            int stateId,
            List<StackFingerprint> slots,
            StackFingerprint carried,
            long receivedTick) {
        return record(level, channel -> channel.fullContent(
                containerId, menuTypeId, stateId, slots, carried, receivedTick));
    }

    public Optional<RecordResult> onSlot(
            ClientLevel level,
            int containerId,
            String menuTypeId,
            int stateId,
            int slot,
            StackFingerprint stack,
            long receivedTick) {
        return record(level, channel -> channel.slot(
                containerId, menuTypeId, stateId, slot, stack, receivedTick));
    }

    public Optional<RecordResult> onPlayerInventorySlot(
            ClientLevel level,
            int containerId,
            String menuTypeId,
            List<Integer> menuSlots,
            StackFingerprint stack,
            long receivedTick) {
        return record(level, channel -> channel.playerInventorySlot(
                containerId, menuTypeId, menuSlots, stack, receivedTick));
    }

    public Optional<RecordResult> onCarried(
            ClientLevel level,
            int containerId,
            String menuTypeId,
            StackFingerprint carried,
            long receivedTick) {
        return record(level, channel -> channel.carried(
                containerId, menuTypeId, carried, receivedTick));
    }

    public Optional<RecordResult> onClose(
            ClientLevel level, int containerId, long receivedTick) {
        return record(level, channel -> channel.close(containerId, receivedTick));
    }

    public void closeLevel(ClientLevel level) {
        if (level == null) {
            return;
        }
        synchronized (gate) {
            channels.remove(level);
        }
    }

    private Optional<RecordResult> record(
            ClientLevel level, Function<SessionChannel, RecordResult> recorder) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(recorder, "recorder");
        synchronized (gate) {
            var channel = channels.get(level);
            // Packets received before an explicit routine/session binding are never evidence.
            return channel == null || !channel.bound()
                    ? Optional.empty()
                    : Optional.of(recorder.apply(channel));
        }
    }

    /** Captures an ItemStack without retaining its mutable count or component containers. */
    public record StackFingerprint(String itemId, int count, int itemAndComponentsHash) {
        public static final StackFingerprint EMPTY = new StackFingerprint("minecraft:air", 0, 0);

        public StackFingerprint {
            Objects.requireNonNull(itemId, "itemId");
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid itemId");
            }
            if (count < 0) {
                throw new IllegalArgumentException("count must be non-negative");
            }
        }

        public static StackFingerprint fromServerPacket(ItemStack stack) {
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) {
                return EMPTY;
            }
            return new StackFingerprint(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    ItemStack.hashItemAndComponents(stack));
        }

        public boolean empty() {
            return count == 0;
        }
    }

    public record OpenScreenEvidence(
            UUID worldSessionId,
            int containerId,
            String menuTypeId,
            long receivedTick,
            long packetLedgerRevision) {
        public OpenScreenEvidence {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            requireContainerId(containerId);
            requireMenuType(menuTypeId);
            requireTick(receivedTick);
        }
    }

    public record ContainerSnapshot(
            UUID worldSessionId,
            int containerId,
            String menuTypeId,
            int stateId,
            List<StackFingerprint> slots,
            StackFingerprint carried,
            long receivedTick,
            long packetLedgerRevision) {
        public ContainerSnapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            requireContainerId(containerId);
            requireMenuType(menuTypeId);
            Objects.requireNonNull(slots, "slots");
            slots = List.copyOf(slots);
            if (slots.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("slots contains null");
            }
            Objects.requireNonNull(carried, "carried");
            requireTick(receivedTick);
        }

        private ContainerSnapshot withSlot(
                int newStateId, int slot, StackFingerprint stack, long tick, long revision) {
            var updated = new ArrayList<>(slots);
            updated.set(slot, stack);
            return new ContainerSnapshot(worldSessionId, containerId, menuTypeId, newStateId,
                    updated, carried, tick, revision);
        }

        private ContainerSnapshot withSlots(
                List<Integer> menuSlots, StackFingerprint stack, long tick, long revision) {
            var updated = new ArrayList<>(slots);
            for (int slot : menuSlots) {
                updated.set(slot, stack);
            }
            return new ContainerSnapshot(worldSessionId, containerId, menuTypeId, stateId,
                    updated, carried, tick, revision);
        }

        private ContainerSnapshot withCarried(
                StackFingerprint stack, long tick, long revision) {
            return new ContainerSnapshot(worldSessionId, containerId, menuTypeId, stateId,
                    slots, stack, tick, revision);
        }
    }

    public record CloseEvidence(
            UUID worldSessionId,
            int containerId,
            long receivedTick,
            long packetLedgerRevision) {
        public CloseEvidence {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            requireContainerId(containerId);
            requireTick(receivedTick);
        }
    }

    public record Snapshot(
            UUID worldSessionId,
            long packetLedgerRevision,
            OpenScreenEvidence lastOpenScreen,
            ContainerSnapshot container,
            CloseEvidence lastClose) {
        public Snapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
        }

        public boolean sameSession(UUID sessionId) {
            return worldSessionId.equals(sessionId);
        }
    }

    public record RecordResult(Snapshot snapshot, boolean applied, String rejectionReason) {
        public RecordResult {
            Objects.requireNonNull(snapshot, "snapshot");
            if (applied == (rejectionReason != null)) {
                throw new IllegalArgumentException("exactly one of applied/rejectionReason is required");
            }
        }

        static RecordResult applied(Snapshot snapshot) {
            return new RecordResult(snapshot, true, null);
        }

        static RecordResult rejected(Snapshot snapshot, String reason) {
            return new RecordResult(snapshot, false, Objects.requireNonNull(reason, "reason"));
        }
    }

    /** Package-private deterministic channel used by pure unit tests. */
    static final class SessionChannel {
        private UUID worldSessionId;
        private long packetLedgerRevision;
        private OpenScreenEvidence lastOpenScreen;
        private ContainerSnapshot container;
        private CloseEvidence lastClose;

        boolean bound() {
            return worldSessionId != null;
        }

        Snapshot bindAndSnapshot(UUID requestedSession) {
            Objects.requireNonNull(requestedSession, "requestedSession");
            if (!requestedSession.equals(worldSessionId)) {
                worldSessionId = requestedSession;
                packetLedgerRevision = 0;
                lastOpenScreen = null;
                container = null;
                lastClose = null;
            }
            return snapshot();
        }

        RecordResult openScreen(int containerId, String menuTypeId, long tick) {
            requireContainerId(containerId);
            requireMenuType(menuTypeId);
            requireTick(tick);
            long revision = nextRevision();
            lastOpenScreen = new OpenScreenEvidence(
                    worldSessionId, containerId, menuTypeId, tick, revision);
            return RecordResult.applied(snapshot());
        }

        RecordResult fullContent(
                int containerId,
                String menuTypeId,
                int stateId,
                List<StackFingerprint> slots,
                StackFingerprint carried,
                long tick) {
            long revision = nextRevision();
            container = new ContainerSnapshot(worldSessionId, containerId, menuTypeId, stateId,
                    slots, carried, tick, revision);
            return RecordResult.applied(snapshot());
        }

        RecordResult slot(
                int containerId,
                String menuTypeId,
                int stateId,
                int slot,
                StackFingerprint stack,
                long tick) {
            Objects.requireNonNull(stack, "stack");
            requireTick(tick);
            long revision = nextRevision();
            if (!matchesCurrent(containerId, menuTypeId)) {
                return RecordResult.rejected(snapshot(), "container_identity_mismatch");
            }
            if (slot < 0 || slot >= container.slots().size()) {
                return RecordResult.rejected(snapshot(), "slot_out_of_bounds");
            }
            // stateId is replaced exactly; it is intentionally never compared by magnitude.
            container = container.withSlot(stateId, slot, stack, tick, revision);
            return RecordResult.applied(snapshot());
        }

        RecordResult playerInventorySlot(
                int containerId,
                String menuTypeId,
                List<Integer> menuSlots,
                StackFingerprint stack,
                long tick) {
            Objects.requireNonNull(menuSlots, "menuSlots");
            Objects.requireNonNull(stack, "stack");
            requireTick(tick);
            long revision = nextRevision();
            if (!matchesCurrent(containerId, menuTypeId)) {
                return RecordResult.rejected(snapshot(), "container_identity_mismatch");
            }
            if (menuSlots.isEmpty() || menuSlots.stream().anyMatch(
                    slot -> slot == null || slot < 0 || slot >= container.slots().size())) {
                return RecordResult.rejected(snapshot(), "slot_mapping_invalid");
            }
            container = container.withSlots(List.copyOf(menuSlots), stack, tick, revision);
            return RecordResult.applied(snapshot());
        }

        RecordResult carried(
                int containerId,
                String menuTypeId,
                StackFingerprint stack,
                long tick) {
            Objects.requireNonNull(stack, "stack");
            requireTick(tick);
            long revision = nextRevision();
            if (!matchesCurrent(containerId, menuTypeId)) {
                return RecordResult.rejected(snapshot(), "container_identity_mismatch");
            }
            container = container.withCarried(stack, tick, revision);
            return RecordResult.applied(snapshot());
        }

        RecordResult close(int containerId, long tick) {
            requireContainerId(containerId);
            requireTick(tick);
            long revision = nextRevision();
            lastClose = new CloseEvidence(worldSessionId, containerId, tick, revision);
            return RecordResult.applied(snapshot());
        }

        Snapshot snapshot() {
            if (worldSessionId == null) {
                throw new IllegalStateException("container channel is not session-bound");
            }
            return new Snapshot(worldSessionId, packetLedgerRevision,
                    lastOpenScreen, container, lastClose);
        }

        private boolean matchesCurrent(int containerId, String menuTypeId) {
            return container != null
                    && container.containerId() == containerId
                    && container.menuTypeId().equals(menuTypeId);
        }

        private long nextRevision() {
            if (packetLedgerRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("container packet ledger exhausted");
            }
            return ++packetLedgerRevision;
        }
    }

    private static void requireContainerId(int containerId) {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
    }

    private static void requireMenuType(String menuTypeId) {
        Objects.requireNonNull(menuTypeId, "menuTypeId");
        if (!menuTypeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid menuTypeId");
        }
    }

    private static void requireTick(long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("receivedTick must be non-negative");
        }
    }
}
