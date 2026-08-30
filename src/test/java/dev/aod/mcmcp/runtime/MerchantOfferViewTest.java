package dev.aod.mcmcp.runtime;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantOfferViewTest {
    private static final HolderOwner<Enchantment> ENCHANTMENT_OWNER = new HolderOwner<>() { };

    @BeforeAll
    static void bindItemComponents() {
        bindTestComponents(Items.ENCHANTED_BOOK, 1);
        bindTestComponents(Items.EMERALD, 64);
        bindTestComponents(Items.BOOK, 64);
        bindTestComponents(Items.BREAD, 64);
    }

    @Test
    void exposesOneKnownBookEnchantmentAsIdAndLevelOnly() {
        var result = enchantedBook(boundEnchantment("minecraft:mending"), 1);

        var view = MerchantOfferView.from(snapshot(result)).getFirst();

        assertThat(view.costA())
                .isEqualTo(new MerchantOfferView.ItemFact("minecraft:emerald", 12));
        assertThat(view.costB())
                .isEqualTo(new MerchantOfferView.ItemFact("minecraft:book", 1));
        assertThat(view.result())
                .isEqualTo(new MerchantOfferView.ItemFact("minecraft:enchanted_book", 1));
        assertThat(view.uses()).isEqualTo(2);
        assertThat(view.maxUses()).isEqualTo(12);
        assertThat(view.outOfStock()).isFalse();
        assertThat(view.merchantLevel()).isEqualTo(2);
        assertThat(view.merchantXp()).isEqualTo(31);
        assertThat(view.bookResultKind())
                .isEqualTo(MerchantOfferView.BookResultKind.SINGLE_KNOWN_ENCHANTMENT);
        assertThat(view.storedEnchantments()).containsExactly(
                new MerchantOfferView.EnchantmentFact("minecraft:mending", 1));

        var map = view.toMap();
        assertThat(map).containsOnlyKeys(
                "world_session_id", "container_id", "signal_revision", "received_tick",
                "offer_index", "cost_a", "cost_b", "result", "uses", "max_uses",
                "out_of_stock", "merchant_level", "merchant_xp", "book_result_kind",
                "stored_enchantment_count", "unresolved_enchantment_count",
                "stored_enchantments");
        assertThat(map.get("stored_enchantments"))
                .isEqualTo(List.of(java.util.Map.of(
                        "enchantment", "minecraft:mending", "level", 1)));
        assertThatThrownBy(() -> map.put("raw_components", "forbidden"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonBookAndMultipleBookResultsRemainNonDefinitive() {
        var nonBook = new ItemStack(Items.BREAD);
        nonBook.set(DataComponents.STORED_ENCHANTMENTS,
                enchantments(boundEnchantment("minecraft:mending"), 1));
        var multiBook = enchantedBook(
                boundEnchantment("minecraft:mending"), 1,
                boundEnchantment("minecraft:unbreaking"), 3);

        var ordinary = MerchantOfferView.from(snapshot(nonBook)).getFirst();
        var multiple = MerchantOfferView.from(snapshot(multiBook)).getFirst();

        assertThat(ordinary.bookResultKind())
                .isEqualTo(MerchantOfferView.BookResultKind.NOT_ENCHANTED_BOOK);
        assertThat(ordinary.storedEnchantmentCount()).isZero();
        assertThat(ordinary.storedEnchantments()).isEmpty();
        assertThat(multiple.bookResultKind())
                .isEqualTo(MerchantOfferView.BookResultKind.MULTIPLE_STORED_ENCHANTMENTS);
        assertThat(multiple.storedEnchantmentCount()).isEqualTo(2);
        assertThat(multiple.storedEnchantments())
                .extracting(MerchantOfferView.EnchantmentFact::enchantment)
                .containsExactly("minecraft:mending", "minecraft:unbreaking");
    }

    @Test
    void unknownAndUnboundEnchantmentsNeverBecomeKnownBookFacts() {
        ResourceKey<Enchantment> key = ResourceKey.create(
                Registries.ENCHANTMENT, Identifier.parse("example:unbound"));
        Holder<Enchantment> unbound = Holder.Reference.createStandAlone(
                ENCHANTMENT_OWNER, key);
        Holder<Enchantment> unknown = Holder.direct(
                boundEnchantment("example:not_registered").value());

        var unboundView = MerchantOfferView.from(
                snapshot(enchantedBook(unbound, 4))).getFirst();
        var unknownView = MerchantOfferView.from(
                snapshot(enchantedBook(unknown, 2))).getFirst();

        assertThat(unboundView.bookResultKind())
                .isEqualTo(MerchantOfferView.BookResultKind.UNRESOLVED_STORED_ENCHANTMENT);
        assertThat(unboundView.storedEnchantmentCount()).isEqualTo(1);
        assertThat(unboundView.unresolvedEnchantmentCount()).isEqualTo(1);
        assertThat(unboundView.storedEnchantments()).isEmpty();
        assertThat(unknownView.bookResultKind())
                .isEqualTo(MerchantOfferView.BookResultKind.UNRESOLVED_STORED_ENCHANTMENT);
        assertThat(unknownView.unresolvedEnchantmentCount()).isEqualTo(1);
        assertThat(unknownView.storedEnchantments()).isEmpty();
    }

    private static MerchantOfferSignals.Snapshot snapshot(ItemStack result) {
        var offer = new MerchantOfferSignals.OfferSnapshot(
                0,
                new ItemStack(Items.EMERALD, 12),
                new ItemStack(Items.BOOK),
                result,
                2,
                12,
                false,
                7,
                0,
                0,
                0.05F);
        return new MerchantOfferSignals.Snapshot(
                UUID.randomUUID(), 9, List.of(offer), 2, 31, true, true, 44, 3);
    }

    private static ItemStack enchantedBook(Holder<Enchantment> enchantment, int level) {
        var stack = new ItemStack(Items.ENCHANTED_BOOK);
        stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments(enchantment, level));
        return stack;
    }

    private static ItemStack enchantedBook(
            Holder<Enchantment> first,
            int firstLevel,
            Holder<Enchantment> second,
            int secondLevel) {
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(first, firstLevel);
        mutable.set(second, secondLevel);
        var stack = new ItemStack(Items.ENCHANTED_BOOK);
        stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return stack;
    }

    private static ItemEnchantments enchantments(
            Holder<Enchantment> enchantment, int level) {
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(enchantment, level);
        return mutable.toImmutable();
    }

    private static Holder<Enchantment> boundEnchantment(String id) {
        ResourceKey<Enchantment> key = ResourceKey.create(
                Registries.ENCHANTMENT, Identifier.parse(id));
        var definition = Enchantment.definition(
                HolderSet.direct(Items.BOOK.builtInRegistryHolder()),
                1,
                5,
                Enchantment.constantCost(1),
                Enchantment.constantCost(10),
                1,
                EquipmentSlotGroup.ANY);
        var value = new Enchantment(
                Component.literal("must not be exposed"),
                definition,
                HolderSet.empty(),
                DataComponentMap.EMPTY);
        return new BoundReference<>(key, value);
    }

    /** NeoForge's isolated JUnit loader does not bind Vanilla item defaults. */
    private static void bindTestComponents(Item item, int maxStackSize) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, maxStackSize)
                    .build());
        }
    }

    private static final class BoundReference<T> extends Holder.Reference<T> {
        private BoundReference(ResourceKey<T> key, T value) {
            super(Type.STAND_ALONE, new HolderOwner<>() { }, key, value);
        }
    }
}
