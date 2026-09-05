package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.mixin.client.ItemFrameDataAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Session-local packet evidence only. Never samples predicted frame getters as an ACK. */
public final class FrameDisplaySyncSignals {
    public static final int MAX_TRACKED_FRAMES = 128;
    private static final FrameDisplaySyncSignals GLOBAL = new FrameDisplaySyncSignals();
    private final Map<ClientLevel, SessionChannel> channels = new WeakHashMap<>();

    public static FrameDisplaySyncSignals global() { return GLOBAL; }

    public synchronized Snapshot bindAndSnapshot(ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        return channels.computeIfAbsent(level, ignored -> new SessionChannel())
                .bindAndSnapshot(worldSessionId);
    }

    public synchronized Optional<FrameSnapshot> snapshot(
            ClientLevel level, UUID worldSessionId, int entityId, UUID entityUuid) {
        var channel = channels.get(level);
        if (level == null || channel == null || !Objects.equals(channel.session, worldSessionId)) {
            return Optional.empty();
        }
        Entity entity = level.getEntity(entityId);
        if (!supportedFrame(entity) || !entityUuid.equals(entity.getUUID())
                || entity.isRemoved() || !entity.isAlive()) return Optional.empty();
        return channel.snapshot().frame(entityId, entityUuid);
    }

    /** Called only after Vanilla has applied this inbound packet on the client thread. */
    public synchronized void onEntityData(
            ClientLevel level, ClientboundSetEntityDataPacket packet, long receivedTick) {
        var channel = channels.get(level);
        if (level == null || channel == null || channel.session == null) return;
        Entity entity = level.getEntity(packet.id());
        if (!supportedFrame(entity) || entity.isRemoved() || !entity.isAlive()) return;
        try {
            DisplayPacket display = packetFields(packet.packedItems(),
                    ItemFrameDataAccessor.mcmcp$itemData().id(),
                    ItemFrameDataAccessor.mcmcp$rotationData().id());
            if (channel.record(packet.id(), entity.getUUID(), display, receivedTick)) {
                ClientReconciliationSignals.global().onEntityDisplayMutation(level);
            }
        } catch (RuntimeException | LinkageError malformed) {
            // Malformed relevant data cannot leave older evidence usable as a new ACK.
            channel.remove(packet.id());
        }
    }

    public synchronized void remove(ClientLevel level, int entityId) {
        var channel = channels.get(level);
        if (channel != null && channel.remove(entityId)) {
            ClientReconciliationSignals.global().onEntityDisplayMutation(level);
        }
    }

    public synchronized void closeLevel(ClientLevel level) { channels.remove(level); }
    public synchronized void clear() { channels.clear(); }

    private static boolean supportedFrame(Entity entity) {
        if (!(entity instanceof ItemFrame)) return false;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return "minecraft:item_frame".equals(type) || "minecraft:glow_item_frame".equals(type);
    }

    static DisplayPacket packetFields(
            List<SynchedEntityData.DataValue<?>> fields, int itemFieldId, int rotationFieldId) {
        boolean hasItem = false;
        String itemId = null;
        Integer rotation = null;
        for (var field : fields) {
            if (field.id() == itemFieldId) {
                if (field.serializer() != EntityDataSerializers.ITEM_STACK
                        || !(field.value() instanceof ItemStack stack)) {
                    throw new IllegalArgumentException("invalid frame item packet field");
                }
                hasItem = true;
                itemId = stack.isEmpty() ? null
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            } else if (field.id() == rotationFieldId) {
                if (field.serializer() != EntityDataSerializers.INT
                        || !(field.value() instanceof Integer value)) {
                    throw new IllegalArgumentException("invalid frame rotation packet field");
                }
                rotation = value;
            }
        }
        return new DisplayPacket(hasItem, itemId, rotation);
    }

    record DisplayPacket(boolean hasItem, String itemId, Integer rotation) {
        DisplayPacket {
            if (!hasItem && itemId != null) throw new IllegalArgumentException("absent item field");
            if (itemId != null && (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || "minecraft:air".equals(itemId))) {
                throw new IllegalArgumentException("invalid frame item identity");
            }
            if (rotation != null && (rotation < 0 || rotation > 7)) {
                throw new IllegalArgumentException("invalid frame rotation");
            }
        }
    }

    public record ItemEvidence(String itemId, long receivedTick, long packetLedgerRevision) { }
    public record RotationEvidence(int rotation, long receivedTick, long packetLedgerRevision) { }
    public record FrameSnapshot(
            UUID worldSessionId, int entityId, UUID entityUuid,
            Optional<ItemEvidence> itemEvidence, Optional<RotationEvidence> rotationEvidence) { }
    public record Snapshot(UUID worldSessionId, long packetLedgerRevision,
                           Map<Integer, FrameSnapshot> frames) {
        public Snapshot { frames = Map.copyOf(frames); }

        public Optional<FrameSnapshot> frame(int entityId, UUID entityUuid) {
            return Optional.ofNullable(frames.get(entityId))
                    .filter(frame -> frame.entityUuid().equals(entityUuid));
        }
    }

    static final class SessionChannel {
        private UUID session;
        private long revision;
        private final LinkedHashMap<Integer, FrameSnapshot> frames = new LinkedHashMap<>();

        Snapshot bindAndSnapshot(UUID worldSessionId) {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (!worldSessionId.equals(session)) {
                session = worldSessionId;
                revision = 0;
                frames.clear();
            }
            return snapshot();
        }

        Snapshot snapshot() { return new Snapshot(session, revision, frames); }

        boolean record(int entityId, UUID entityUuid, DisplayPacket display, long receivedTick) {
            if (session == null) return false;
            Objects.requireNonNull(entityUuid, "entityUuid");
            if (receivedTick < 0) throw new IllegalArgumentException("negative packet tick");
            if (!display.hasItem() && display.rotation() == null) return false;
            long packetRevision = ++revision;
            FrameSnapshot before = frames.remove(entityId);
            if (before != null && !before.entityUuid().equals(entityUuid)) before = null;
            // An unknown first field is a conservative observation-boundary change. Later
            // identical packets still acknowledge the field, but do not fabricate mutations.
            boolean changed = display.hasItem() && (before == null || before.itemEvidence().isEmpty()
                    || !Objects.equals(display.itemId(), before.itemEvidence().orElseThrow().itemId()))
                    || display.rotation() != null && (before == null || before.rotationEvidence().isEmpty()
                    || display.rotation() != before.rotationEvidence().orElseThrow().rotation());
            Optional<ItemEvidence> item = display.hasItem()
                    ? Optional.of(new ItemEvidence(display.itemId(), receivedTick, packetRevision))
                    : before == null ? Optional.empty() : before.itemEvidence();
            Optional<RotationEvidence> rotation = display.rotation() != null
                    ? Optional.of(new RotationEvidence(display.rotation(), receivedTick, packetRevision))
                    : before == null ? Optional.empty() : before.rotationEvidence();
            frames.put(entityId, new FrameSnapshot(session, entityId, entityUuid, item, rotation));
            while (frames.size() > MAX_TRACKED_FRAMES) {
                frames.remove(frames.keySet().iterator().next());
            }
            return changed;
        }

        boolean remove(int entityId) {
            boolean removed = frames.remove(entityId) != null;
            ++revision;
            return removed;
        }
    }
}
