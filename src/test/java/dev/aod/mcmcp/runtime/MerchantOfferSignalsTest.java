package dev.aod.mcmcp.runtime;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantOfferSignalsTest {
    @Test
    void capturesOnlyPacketFactsAndDefensivelyOwnsEveryStack() {
        bindTestComponents(Items.ENCHANTED_BOOK, 1);
        bindTestComponents(Items.EMERALD, 64);
        bindTestComponents(Items.BOOK, 64);
        bindTestComponents(Items.BREAD, 64);
        var result = new ItemStack(Items.ENCHANTED_BOOK);
        var offer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 24),
                Optional.of(new ItemCost(Items.BOOK)),
                result,
                1,
                3,
                7,
                0.2F,
                2);
        offer.setSpecialPriceDiff(-3);
        var exhausted = new MerchantOffer(
                new ItemCost(Items.EMERALD, 5),
                new ItemStack(Items.BREAD),
                2,
                1,
                0.05F);
        exhausted.setToOutOfStock();
        var offers = new MerchantOffers();
        offers.add(offer);
        offers.add(exhausted);
        var packet = new ClientboundMerchantOffersPacket(9, offers, 2, 31, true, true);
        var ledger = new MerchantOfferSignals.SessionLedger();
        var baseline = ledger.bind(UUID.randomUUID());

        ledger.record(packet, 44);
        var snapshot = ledger.latestAfter(baseline).orElseThrow();

        result.setCount(8);
        offer.increaseUses();
        offers.clear();
        var first = snapshot.offers().getFirst();
        var returnedResult = first.result();
        returnedResult.setCount(6);

        assertThat(snapshot.containerId()).isEqualTo(9);
        assertThat(snapshot.merchantLevel()).isEqualTo(2);
        assertThat(snapshot.merchantXp()).isEqualTo(31);
        assertThat(snapshot.showProgress()).isTrue();
        assertThat(snapshot.canRestock()).isTrue();
        assertThat(snapshot.receivedTick()).isEqualTo(44);
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.offers()).hasSize(2);
        assertThat(first.offerIndex()).isZero();
        assertThat(first.costA().getItem()).isEqualTo(Items.EMERALD);
        assertThat(first.costA().getCount()).isEqualTo(30);
        assertThat(first.costB().getItem()).isEqualTo(Items.BOOK);
        assertThat(first.result().getItem()).isEqualTo(Items.ENCHANTED_BOOK);
        assertThat(first.result().getCount()).isEqualTo(1);
        assertThat(first.uses()).isEqualTo(1);
        assertThat(first.maxUses()).isEqualTo(3);
        assertThat(first.outOfStock()).isFalse();
        assertThat(first.offerXp()).isEqualTo(7);
        assertThat(first.specialPriceDiff()).isEqualTo(-3);
        assertThat(first.demand()).isEqualTo(2);
        assertThat(first.priceMultiplier()).isEqualTo(0.2F);
        assertThat(snapshot.offers().get(1).offerIndex()).isEqualTo(1);
        assertThat(snapshot.offers().get(1).outOfStock()).isTrue();
    }

    @Test
    void freshnessAndRebindFencePacketsByWorldSession() {
        var firstSession = UUID.randomUUID();
        var secondSession = UUID.randomUUID();
        var ledger = new MerchantOfferSignals.SessionLedger();
        var packet = emptyPacket();
        var beforePacket = ledger.bind(firstSession);

        ledger.record(packet, 1);

        assertThat(ledger.latestAfter(beforePacket)).isPresent();
        var afterPacket = ledger.bind(firstSession);
        assertThat(ledger.latestAfter(afterPacket)).isEmpty();
        var rebound = ledger.bind(secondSession);
        assertThat(rebound.revision()).isZero();
        assertThat(ledger.latestAfter(beforePacket)).isEmpty();
        assertThat(ledger.latestAfter(rebound)).isEmpty();

        ledger.record(packet, 2);
        assertThat(ledger.latestAfter(rebound))
                .get()
                .extracting(MerchantOfferSignals.Snapshot::worldSessionId)
                .isEqualTo(secondSession);
    }

    private static ClientboundMerchantOffersPacket emptyPacket() {
        return new ClientboundMerchantOffersPacket(
                1, new MerchantOffers(), 1, 0, false, false);
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
}
