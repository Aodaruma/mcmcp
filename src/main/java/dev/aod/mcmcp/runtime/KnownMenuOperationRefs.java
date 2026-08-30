package dev.aod.mcmcp.runtime;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/** Bounded, single-use authorities for operations on one exact server-synchronized menu. */
public final class KnownMenuOperationRefs {
    static final int MAX_LEASES = 16;
    static final String TRANSFER_TO_PLAYER = "transfer_to_player";
    private static final int REFERENCE_BYTES = 18;

    private final SecureRandom random;
    private final Map<String, Lease> leases = new LinkedHashMap<>();

    public KnownMenuOperationRefs() {
        this(new SecureRandom());
    }

    KnownMenuOperationRefs(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized String issue(
            Context context,
            int sourceSlot,
            ContainerSyncSignals.StackFingerprint stack,
            ItemStack exactStack,
            List<ContainerSyncSignals.StackFingerprint> initialServerSlots,
            int baselineExactComponentPlayerCount,
            int expectedExactComponentPlayerCount,
            String operationKind,
            long deadlineClientTick) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(exactStack, "exactStack");
        initialServerSlots = List.copyOf(
                Objects.requireNonNull(initialServerSlots, "initialServerSlots"));
        operationKind = requireOperationKind(operationKind);
        if (stack.empty() || exactStack.isEmpty()) {
            throw new IllegalArgumentException("source stack must be non-empty");
        }
        if (sourceSlot < 0 || sourceSlot >= context.slotCount()) {
            throw new IllegalArgumentException("sourceSlot is outside the bound menu");
        }
        if (initialServerSlots.size() != context.slotCount()
                || initialServerSlots.stream().anyMatch(Objects::isNull)
                || !initialServerSlots.get(sourceSlot).equals(stack)) {
            throw new IllegalArgumentException("initialServerSlots do not match the bound source");
        }
        if (!ContainerSyncSignals.StackFingerprint.fromServerPacket(exactStack).equals(stack)) {
            throw new IllegalArgumentException("exactStack does not match the bound fingerprint");
        }
        if (baselineExactComponentPlayerCount < 0 || expectedExactComponentPlayerCount < 0) {
            throw new IllegalArgumentException("exact-component player counts must be non-negative");
        }
        if (deadlineClientTick < context.clientTick()) {
            throw new IllegalArgumentException("deadlineClientTick is already expired");
        }
        purgeExpired(context.clientTick());
        while (leases.size() >= MAX_LEASES) {
            leases.remove(leases.keySet().iterator().next());
        }
        String reference = newReference();
        leases.put(reference, new Lease(
                context.withoutTick(), sourceSlot, stack, exactStack.copy(), initialServerSlots,
                baselineExactComponentPlayerCount, expectedExactComponentPlayerCount,
                operationKind, deadlineClientTick));
        return reference;
    }

    /** Reads one authority without consuming it when every bound context field still matches. */
    public synchronized Optional<Operation> peek(String reference, Context context) {
        return find(reference, context, false);
    }

    /** Resolves and consumes one authority only when every bound context field still matches. */
    public synchronized Optional<Operation> resolve(String reference, Context context) {
        return find(reference, context, true);
    }

    private Optional<Operation> find(String reference, Context context, boolean consume) {
        Objects.requireNonNull(context, "context");
        if (reference == null) {
            return Optional.empty();
        }
        Lease lease = leases.get(reference);
        if (lease == null) {
            return Optional.empty();
        }
        if (context.clientTick() > lease.deadlineClientTick()) {
            leases.remove(reference);
            return Optional.empty();
        }
        if (!lease.context().matches(context)) {
            return Optional.empty();
        }
        if (consume) {
            leases.remove(reference);
        }
        return Optional.of(new Operation(
                lease.sourceSlot(), lease.stack(), lease.exactStack(), lease.initialServerSlots(),
                lease.baselineExactComponentPlayerCount(),
                lease.expectedExactComponentPlayerCount(), lease.operationKind()));
    }

    public synchronized void clearSession(UUID worldSessionId) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        leases.entrySet().removeIf(
                entry -> entry.getValue().context().worldSessionId().equals(worldSessionId));
    }

    public synchronized void clear() {
        leases.clear();
    }

    private void purgeExpired(long clientTick) {
        leases.entrySet().removeIf(entry -> clientTick > entry.getValue().deadlineClientTick());
    }

    private String newReference() {
        byte[] bytes = new byte[REFERENCE_BYTES];
        String reference;
        do {
            random.nextBytes(bytes);
            reference = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (leases.containsKey(reference));
        return reference;
    }

    private static String requireOperationKind(String value) {
        Objects.requireNonNull(value, "operationKind");
        if (!TRANSFER_TO_PLAYER.equals(value)) {
            throw new IllegalArgumentException("unsupported operationKind");
        }
        return value;
    }

    public record Context(
            UUID worldSessionId,
            Object screenIdentity,
            int containerId,
            String menuTypeId,
            int stateId,
            int slotCount,
            String profileHash,
            long packetRevision,
            long clientTick) {
        public Context {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            Objects.requireNonNull(screenIdentity, "screenIdentity");
            Objects.requireNonNull(menuTypeId, "menuTypeId");
            Objects.requireNonNull(profileHash, "profileHash");
            if (containerId < 0 || slotCount < 0 || packetRevision < 0 || clientTick < 0) {
                throw new IllegalArgumentException("menu context contains a negative value");
            }
            if (!menuTypeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid menuTypeId");
            }
            if (!profileHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid profileHash");
            }
        }

        private BoundContext withoutTick() {
            return new BoundContext(
                    worldSessionId, screenIdentity, containerId, menuTypeId, stateId,
                    slotCount, profileHash, packetRevision);
        }
    }

    public record Operation(
            int sourceSlot,
            ContainerSyncSignals.StackFingerprint stack,
            ItemStack exactStack,
            List<ContainerSyncSignals.StackFingerprint> initialServerSlots,
            int baselineExactComponentPlayerCount,
            int expectedExactComponentPlayerCount,
            String operationKind) {
        public Operation {
            Objects.requireNonNull(stack, "stack");
            exactStack = Objects.requireNonNull(exactStack, "exactStack").copy();
            initialServerSlots = List.copyOf(
                    Objects.requireNonNull(initialServerSlots, "initialServerSlots"));
            Objects.requireNonNull(operationKind, "operationKind");
        }

        @Override
        public ItemStack exactStack() {
            return exactStack.copy();
        }
    }

    private record BoundContext(
            UUID worldSessionId,
            Object screenIdentity,
            int containerId,
            String menuTypeId,
            int stateId,
            int slotCount,
            String profileHash,
            long packetRevision) {
        private boolean matches(Context current) {
            return worldSessionId.equals(current.worldSessionId())
                    && screenIdentity == current.screenIdentity()
                    && containerId == current.containerId()
                    && menuTypeId.equals(current.menuTypeId())
                    && stateId == current.stateId()
                    && slotCount == current.slotCount()
                    && profileHash.equals(current.profileHash())
                    && packetRevision == current.packetRevision();
        }
    }

    private record Lease(
            BoundContext context,
            int sourceSlot,
            ContainerSyncSignals.StackFingerprint stack,
            ItemStack exactStack,
            List<ContainerSyncSignals.StackFingerprint> initialServerSlots,
            int baselineExactComponentPlayerCount,
            int expectedExactComponentPlayerCount,
            String operationKind,
            long deadlineClientTick) {
        private Lease {
            exactStack = exactStack.copy();
            initialServerSlots = List.copyOf(initialServerSlots);
        }

        @Override
        public ItemStack exactStack() {
            return exactStack.copy();
        }
    }
}
