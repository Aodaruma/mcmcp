package dev.aod.mcmcp.runtime;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Closed, component-free policy view of one server-authored merchant offer. */
public record MerchantOfferView(
        UUID worldSessionId,
        int containerId,
        long signalRevision,
        long receivedTick,
        int offerIndex,
        ItemFact costA,
        ItemFact costB,
        ItemFact result,
        int uses,
        int maxUses,
        boolean outOfStock,
        int merchantLevel,
        int merchantXp,
        BookResultKind bookResultKind,
        int storedEnchantmentCount,
        int unresolvedEnchantmentCount,
        List<EnchantmentFact> storedEnchantments) {

    public MerchantOfferView {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(costA, "costA");
        Objects.requireNonNull(costB, "costB");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(bookResultKind, "bookResultKind");
        storedEnchantments = List.copyOf(
                Objects.requireNonNull(storedEnchantments, "storedEnchantments"));
        if (containerId < 0 || signalRevision <= 0 || receivedTick < 0 || offerIndex < 0) {
            throw new IllegalArgumentException("invalid merchant-offer identity");
        }
        if (storedEnchantmentCount < 0
                || unresolvedEnchantmentCount < 0
                || unresolvedEnchantmentCount > storedEnchantmentCount
                || storedEnchantments.size()
                        != storedEnchantmentCount - unresolvedEnchantmentCount) {
            throw new IllegalArgumentException("inconsistent stored-enchantment counts");
        }
        boolean kindMatchesFacts = switch (bookResultKind) {
            case NOT_ENCHANTED_BOOK, NO_STORED_ENCHANTMENT -> storedEnchantmentCount == 0;
            case SINGLE_KNOWN_ENCHANTMENT -> storedEnchantmentCount == 1
                    && unresolvedEnchantmentCount == 0;
            case MULTIPLE_STORED_ENCHANTMENTS -> storedEnchantmentCount > 1
                    && unresolvedEnchantmentCount == 0;
            case UNRESOLVED_STORED_ENCHANTMENT -> unresolvedEnchantmentCount > 0;
        };
        if (!kindMatchesFacts) {
            throw new IllegalArgumentException("book result kind does not match its facts");
        }
    }

    /** Converts every offer without retaining ItemStack, component, lore, NBT, or display text. */
    public static List<MerchantOfferView> from(MerchantOfferSignals.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.offers().stream()
                .map(offer -> from(snapshot, offer))
                .toList();
    }

    private static MerchantOfferView from(
            MerchantOfferSignals.Snapshot snapshot,
            MerchantOfferSignals.OfferSnapshot offer) {
        var resultStack = offer.result();
        var bookFacts = bookFacts(resultStack);
        return new MerchantOfferView(
                snapshot.worldSessionId(),
                snapshot.containerId(),
                snapshot.revision(),
                snapshot.receivedTick(),
                offer.offerIndex(),
                ItemFact.from(offer.costA()),
                ItemFact.from(offer.costB()),
                ItemFact.from(resultStack),
                offer.uses(),
                offer.maxUses(),
                offer.outOfStock(),
                snapshot.merchantLevel(),
                snapshot.merchantXp(),
                bookFacts.kind(),
                bookFacts.total(),
                bookFacts.unresolved(),
                bookFacts.known());
    }

    private static BookFacts bookFacts(ItemStack result) {
        if (!result.is(Items.ENCHANTED_BOOK)) {
            return new BookFacts(BookResultKind.NOT_ENCHANTED_BOOK, 0, 0, List.of());
        }
        ItemEnchantments stored = result.getOrDefault(
                DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.isEmpty()) {
            return new BookFacts(BookResultKind.NO_STORED_ENCHANTMENT, 0, 0, List.of());
        }

        var known = new ArrayList<EnchantmentFact>(stored.size());
        int unresolved = 0;
        for (var entry : stored.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            var key = holder.unwrapKey();
            if (!holder.isBound()
                    || key.isEmpty()
                    || !key.get().isFor(Registries.ENCHANTMENT)) {
                unresolved++;
                continue;
            }
            known.add(new EnchantmentFact(
                    key.get().identifier().toString(), entry.getIntValue()));
        }
        known.sort(Comparator.comparing(EnchantmentFact::enchantment));
        var kind = unresolved > 0
                ? BookResultKind.UNRESOLVED_STORED_ENCHANTMENT
                : stored.size() == 1
                        ? BookResultKind.SINGLE_KNOWN_ENCHANTMENT
                        : BookResultKind.MULTIPLE_STORED_ENCHANTMENTS;
        return new BookFacts(kind, stored.size(), unresolved, known);
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("world_session_id", worldSessionId.toString());
        map.put("container_id", containerId);
        map.put("signal_revision", signalRevision);
        map.put("received_tick", receivedTick);
        map.put("offer_index", offerIndex);
        map.put("cost_a", costA.toMap());
        map.put("cost_b", costB.toMap());
        map.put("result", result.toMap());
        map.put("uses", uses);
        map.put("max_uses", maxUses);
        map.put("out_of_stock", outOfStock);
        map.put("merchant_level", merchantLevel);
        map.put("merchant_xp", merchantXp);
        map.put("book_result_kind", bookResultKind.wireName());
        map.put("stored_enchantment_count", storedEnchantmentCount);
        map.put("unresolved_enchantment_count", unresolvedEnchantmentCount);
        map.put("stored_enchantments", storedEnchantments.stream()
                .map(EnchantmentFact::toMap)
                .toList());
        return Map.copyOf(map);
    }

    public record ItemFact(String item, int count) {
        public ItemFact {
            requireRegistryId(item, "item");
            if (count < 0) {
                throw new IllegalArgumentException("item count must be non-negative");
            }
        }

        private static ItemFact from(ItemStack stack) {
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) {
                return new ItemFact("minecraft:air", 0);
            }
            var key = BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                    .orElseThrow(() -> new IllegalArgumentException("unregistered item in merchant offer"));
            return new ItemFact(key.identifier().toString(), stack.getCount());
        }

        public Map<String, Object> toMap() {
            return Map.of("item", item, "count", count);
        }
    }

    public record EnchantmentFact(String enchantment, int level) {
        public EnchantmentFact {
            requireRegistryId(enchantment, "enchantment");
            if (level <= 0) {
                throw new IllegalArgumentException("enchantment level must be positive");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("enchantment", enchantment, "level", level);
        }
    }

    public enum BookResultKind {
        NOT_ENCHANTED_BOOK,
        NO_STORED_ENCHANTMENT,
        SINGLE_KNOWN_ENCHANTMENT,
        MULTIPLE_STORED_ENCHANTMENTS,
        UNRESOLVED_STORED_ENCHANTMENT;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record BookFacts(
            BookResultKind kind,
            int total,
            int unresolved,
            List<EnchantmentFact> known) {
        private BookFacts {
            known = List.copyOf(known);
        }
    }

    private static void requireRegistryId(String value, String name) {
        Objects.requireNonNull(value, name);
        Identifier parsed = Identifier.tryParse(value);
        if (parsed == null || !parsed.toString().equals(value)) {
            throw new IllegalArgumentException(name + " must be a canonical registry id");
        }
    }
}
