package dev.aod.mcmcp.runtime;

import net.minecraft.world.item.ItemStack;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client-thread, bounded capabilities for offers actually received from the server. */
final class V2MerchantReferences {
    static final long TTL_TICKS = 2400;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    record Entry(UUID session, String dimension, UUID merchant, long expires,
                 MerchantOfferSignals.OfferSnapshot offer) { }

    String register(UUID session, String dimension, UUID merchant, long tick, MerchantOfferSignals.OfferSnapshot offer) {
        entries.entrySet().removeIf(entry -> !entry.getValue().session().equals(session) || tick > entry.getValue().expires());
        while (entries.size() >= 256) entries.remove(entries.keySet().iterator().next());
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String ref = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entries.put(ref, new Entry(session, dimension, merchant, Math.addExact(tick, TTL_TICKS), offer));
        return ref;
    }

    Entry resolve(String ref, UUID session, String dimension, UUID merchant, long tick) {
        var entry = entries.get(ref);
        if (entry == null || !entry.session().equals(session) || !entry.dimension().equals(dimension)
                || !entry.merchant().equals(merchant) || tick > entry.expires())
            throw new IllegalArgumentException("offer_ref expired or belongs to another merchant/session; inspect again");
        return entry;
    }

    static boolean sameOffer(MerchantOfferSignals.OfferSnapshot a, MerchantOfferSignals.OfferSnapshot b, int confirmed) {
        return a.offerIndex() == b.offerIndex() && ItemStack.matches(a.costA(), b.costA())
                && ItemStack.matches(a.costB(), b.costB()) && ItemStack.matches(a.result(), b.result())
                && a.uses() + confirmed == b.uses() && a.maxUses() == b.maxUses()
                && b.outOfStock() == (b.uses() >= b.maxUses()) && a.offerXp() == b.offerXp()
                && a.specialPriceDiff() == b.specialPriceDiff() && a.demand() == b.demand()
                && Float.compare(a.priceMultiplier(), b.priceMultiplier()) == 0;
    }
}
