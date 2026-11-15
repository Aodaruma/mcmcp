package dev.aod.mcmcp.runtime;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.aod.mcmcp.client.AgentScreenPolicy;

/** Bounded, one-use references for observed storage items; contents come only from opened menus. */
public final class KnownStorageRefs {
    private static final int MAX_TARGETS = 16;
    private final List<StorageAccess> providers;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final Map<StorageIdentity, String> identities = new LinkedHashMap<>();
    private List<Map<String, Object>> lastInspection = List.of();
    private UUID inspectionSession;
    private long inspectionTick;
    private long inspectionRevision;
    private String inspectionReference;
    private String inspectionStorageId;

    public KnownStorageRefs() { this(List.of(new SophisticatedStorageAccess())); }
    KnownStorageRefs(List<StorageAccess> providers) { this.providers = List.copyOf(providers); }

    public synchronized List<Map<String, Object>> payload(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        if (minecraft.player == null || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu) return List.of();
        leases.entrySet().removeIf(entry -> !entry.getValue().session.equals(session.worldSessionId())
                || entry.getValue().deadline < session.clientTick());
        var result = new ArrayList<Map<String, Object>>();
        for (StorageAccess provider : providers) {
            for (StorageAccess.Target target : provider.discover(minecraft)) {
                if (result.size() == MAX_TARGETS) break;
                long deadline = session.clientTick() + 1200;
                String reference = issue(session.worldSessionId(), minecraft.player, target, deadline);
                result.add(Map.of("operation_ref", reference, "storage_id", storageId(session.worldSessionId(), target),
                        "profile_hash", target.profileHash(),
                        "item", BuiltInRegistries.ITEM.getKey(target.item().getItem()).toString(),
                        "location", target.location(), "operations", List.of("inspect", "take", "store"),
                        "valid_through_client_tick", deadline));
            }
        }
        return List.copyOf(result);
    }

    public synchronized Optional<StorageAccess.Target> resolve(String reference, Minecraft minecraft,
            WorldSessionTracker.Snapshot session, boolean consume) {
        return resolve(reference, session.worldSessionId(), minecraft.player, session.clientTick(),
                target -> target.stillPresent(minecraft), consume);
    }

    synchronized String issue(UUID session, Object player, StorageAccess.Target target, long deadline) {
        byte[] bytes = new byte[18];
        String reference;
        do {
            random.nextBytes(bytes);
            reference = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (leases.containsKey(reference));
        while (leases.size() >= MAX_TARGETS * 4) leases.remove(leases.keySet().iterator().next());
        leases.put(reference, new Lease(session, player, target, deadline));
        return reference;
    }

    synchronized String storageId(UUID session, StorageAccess.Target target) {
        var identity = new StorageIdentity(session, target.profileHash(), target.identityKey());
        String existing = identities.get(identity);
        if (existing != null) return existing;
        while (identities.size() >= 64) identities.remove(identities.keySet().iterator().next());
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        identities.put(identity, id);
        return id;
    }

    synchronized Optional<StorageAccess.Target> resolve(String reference, UUID session, Object player, long tick,
            java.util.function.Predicate<StorageAccess.Target> stillPresent, boolean consume) {
        Lease lease = leases.get(reference);
        if (lease == null || !lease.session.equals(session) || lease.player != player
                || tick > lease.deadline || !stillPresent.test(lease.target)) return Optional.empty();
        if (consume) leases.remove(reference);
        return Optional.of(lease.target);
    }

    public synchronized void inspected(UUID session, String reference, StorageAccess.Target target,
            KnownMenuProfileSupport.Context context) {
        var contents = new ArrayList<Map<String, Object>>();
        for (int index : context.storageSlots()) {
            var stack = context.snapshot().slots().get(index);
            if (!stack.empty()) contents.add(Map.of("slot", index, "item", stack.itemId(), "count", stack.count()));
        }
        lastInspection = List.copyOf(contents);
        inspectionSession = session;
        inspectionTick = context.snapshot().receivedTick();
        inspectionRevision = context.snapshot().packetLedgerRevision();
        inspectionReference = reference;
        inspectionStorageId = storageId(session, target);
    }

    public synchronized Map<String, Object> inspection(UUID session) {
        return session.equals(inspectionSession)
                ? Map.of("operation_ref", inspectionReference, "storage_id", inspectionStorageId, "observed_client_tick", inspectionTick,
                        "packet_revision", inspectionRevision, "contents", lastInspection) : null;
    }

    public synchronized void clear() { leases.clear(); identities.clear(); lastInspection = List.of(); inspectionSession = null; }
    private record Lease(UUID session, Object player, StorageAccess.Target target, long deadline) { }
    private record StorageIdentity(UUID session, String profileHash, Object key) { }
}
