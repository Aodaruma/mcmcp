package dev.aod.mcmcp.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Session-fenced, read-only evidence from inbound merchant-offer packets. */
public final class MerchantOfferSignals {
    private static final MerchantOfferSignals GLOBAL = new MerchantOfferSignals();

    private final Object gate = new Object();
    private final Map<ClientLevel, SessionLedger> ledgers = new WeakHashMap<>();

    public static MerchantOfferSignals global() {
        return GLOBAL;
    }

    /** Binds this exact level to a world session and returns a freshness baseline. */
    public Baseline bindSession(ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        synchronized (gate) {
            return ledgers.computeIfAbsent(level, ignored -> new SessionLedger())
                    .bind(worldSessionId);
        }
    }

    /** Returns only a packet captured in the same session after the supplied baseline. */
    public Optional<Snapshot> latestAfter(ClientLevel level, Baseline baseline) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(baseline, "baseline");
        synchronized (gate) {
            var ledger = ledgers.get(level);
            return ledger == null ? Optional.empty() : ledger.latestAfter(baseline);
        }
    }

    /** Records one post-handler {@link ClientboundMerchantOffersPacket}. */
    public void onOffers(
            ClientLevel level, ClientboundMerchantOffersPacket packet, long receivedTick) {
        if (level == null) {
            return;
        }
        Objects.requireNonNull(packet, "packet");
        synchronized (gate) {
            var ledger = ledgers.get(level);
            // Packets received before an explicit routine/session binding are never evidence.
            if (ledger != null && ledger.bound()) {
                ledger.record(packet, receivedTick);
            }
        }
    }

    /** Explicit lifecycle fence for disconnect or level replacement. */
    public void clearSession(ClientLevel level) {
        if (level == null) {
            return;
        }
        synchronized (gate) {
            ledgers.remove(level);
        }
    }

    public record Baseline(UUID worldSessionId, long revision) {
        public Baseline {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (revision < 0) {
                throw new IllegalArgumentException("baseline revision must be non-negative");
            }
        }
    }

    /**
     * One offer as it arrived from the server. Stack accessors always return defensive copies.
     * No stack components, lore, or NBT are serialized into the public MCP surface here.
     */
    public record OfferSnapshot(
            int offerIndex,
            ItemStack costA,
            ItemStack costB,
            ItemStack result,
            int uses,
            int maxUses,
            boolean outOfStock,
            int offerXp,
            int specialPriceDiff,
            int demand,
            float priceMultiplier) {
        public OfferSnapshot {
            if (offerIndex < 0) {
                throw new IllegalArgumentException("offerIndex must be non-negative");
            }
            costA = copyStack(costA, "costA");
            costB = copyStack(costB, "costB");
            result = copyStack(result, "result");
        }

        @Override
        public ItemStack costA() {
            return costA.copy();
        }

        @Override
        public ItemStack costB() {
            return costB.copy();
        }

        @Override
        public ItemStack result() {
            return result.copy();
        }

        private static OfferSnapshot capture(int index, MerchantOffer offer) {
            Objects.requireNonNull(offer, "offer");
            return new OfferSnapshot(
                    index,
                    offer.getCostA(),
                    offer.getCostB(),
                    offer.getResult(),
                    offer.getUses(),
                    offer.getMaxUses(),
                    offer.isOutOfStock(),
                    offer.getXp(),
                    offer.getSpecialPriceDiff(),
                    offer.getDemand(),
                    offer.getPriceMultiplier());
        }

        private static ItemStack copyStack(ItemStack stack, String name) {
            return Objects.requireNonNull(stack, name).copy();
        }
    }

    /** Immutable snapshot of one complete merchant-offers packet. */
    public record Snapshot(
            UUID worldSessionId,
            int containerId,
            List<OfferSnapshot> offers,
            int merchantLevel,
            int merchantXp,
            boolean showProgress,
            boolean canRestock,
            long receivedTick,
            long revision) {
        public Snapshot {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (containerId < 0) {
                throw new IllegalArgumentException("containerId must be non-negative");
            }
            Objects.requireNonNull(offers, "offers");
            offers = List.copyOf(offers);
            if (offers.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("offers contains null");
            }
            if (receivedTick < 0) {
                throw new IllegalArgumentException("receivedTick must be non-negative");
            }
            if (revision <= 0) {
                throw new IllegalArgumentException("snapshot revision must be positive");
            }
        }

        private static Snapshot capture(
                UUID worldSessionId,
                ClientboundMerchantOffersPacket packet,
                long receivedTick,
                long revision) {
            Objects.requireNonNull(packet, "packet");
            var offers = new ArrayList<OfferSnapshot>(packet.getOffers().size());
            for (int index = 0; index < packet.getOffers().size(); index++) {
                offers.add(OfferSnapshot.capture(index, packet.getOffers().get(index)));
            }
            return new Snapshot(
                    worldSessionId,
                    packet.getContainerId(),
                    offers,
                    packet.getVillagerLevel(),
                    packet.getVillagerXp(),
                    packet.showProgress(),
                    packet.canRestock(),
                    receivedTick,
                    revision);
        }
    }

    /** Package-private deterministic core used by focused unit tests. */
    static final class SessionLedger {
        private UUID worldSessionId;
        private long revision;
        private Snapshot latest;

        boolean bound() {
            return worldSessionId != null;
        }

        Baseline bind(UUID requestedSession) {
            Objects.requireNonNull(requestedSession, "requestedSession");
            if (!requestedSession.equals(worldSessionId)) {
                worldSessionId = requestedSession;
                revision = 0;
                latest = null;
            }
            return new Baseline(worldSessionId, revision);
        }

        void record(ClientboundMerchantOffersPacket packet, long receivedTick) {
            if (!bound()) {
                throw new IllegalStateException("merchant-offer ledger is not session-bound");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("merchant-offer ledger exhausted");
            }
            long nextRevision = revision + 1;
            var captured = Snapshot.capture(
                    worldSessionId, packet, receivedTick, nextRevision);
            revision = nextRevision;
            latest = captured;
        }

        Optional<Snapshot> latestAfter(Baseline baseline) {
            Objects.requireNonNull(baseline, "baseline");
            if (!baseline.worldSessionId().equals(worldSessionId)
                    || latest == null
                    || latest.revision() <= baseline.revision()) {
                return Optional.empty();
            }
            return Optional.of(latest);
        }
    }
}
