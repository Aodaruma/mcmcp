package dev.aod.mcmcp.runtime;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class V2TradeTest {
    @BeforeAll static void bind() {
        for (var item : List.of(Items.EMERALD, Items.BOOK, Items.BREAD)) {
            var holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }
    private Map<String, Object> request() {
        return Map.of("target", "entity", "operation", "trade", "entity_ref", "abcdefghijklmnopqrstuvwx",
                "offer_ref", "ABCDEFGHIJKLMNOPQRSTUVWX", "count", 3,
                "max_total_cost", List.of(Map.of("item", "minecraft:emerald", "count", 9), Map.of("item", "minecraft:book", "count", 3)));
    }
    private MerchantOfferSignals.OfferSnapshot offer(ItemStack payment, int uses) {
        return new MerchantOfferSignals.OfferSnapshot(1, payment, new ItemStack(Items.BOOK),
                new ItemStack(Items.BREAD, 2), uses, 12, uses >= 12, 1, 0, 0, 0.05F);
    }
    @Test void budgetAccountsForAllTradesAndBothPayments() {
        var args = V2TradeArguments.parse(request());
        assertThat(args.entity().item()).isEqualTo("minecraft:air");
        assertThat(args.permits(Map.of("minecraft:emerald", 3, "minecraft:book", 1))).isTrue();
        assertThat(args.permits(Map.of("minecraft:emerald", 4, "minecraft:book", 1))).isFalse();
        assertThat(args.permits(Map.of("minecraft:diamond", 1))).isFalse();
        assertThat(args.permits(Map.of("minecraft:emerald", Integer.MAX_VALUE))).isFalse();
    }
    @Test void inspectCannotAcceptPaymentAndTradeRejectsDuplicateLimits() {
        var bad = new java.util.LinkedHashMap<>(request());
        bad.put("max_total_cost", List.of(Map.of("item", "minecraft:emerald", "count", 9), Map.of("item", "minecraft:emerald", "count", 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> V2TradeArguments.parse(bad));
        bad.put("operation", "inspect_offers");
        assertThatIllegalArgumentException().isThrownBy(() -> V2TradeArguments.parse(bad));
        assertThat(V2TradeArguments.parse(Map.of("target", "entity", "operation", "inspect_offers",
                "entity_ref", "abcdefghijklmnopqrstuvwx")).count()).isZero();
    }
    @Test void referencesFenceIdentityDimensionExpiryAndKeepExactComponents() {
        var refs = new V2MerchantReferences();
        UUID session = UUID.randomUUID(), merchant = UUID.randomUUID();
        var expected = offer(new ItemStack(Items.EMERALD, 3), 0);
        String ref = refs.register(session, "minecraft:overworld", merchant, 100, expected);
        assertThat(ref).hasSize(24);
        assertThat(refs.resolve(ref, session, "minecraft:overworld", merchant, 2500).offer()).isSameAs(expected);
        assertThatIllegalArgumentException().isThrownBy(() -> refs.resolve(ref, session, "minecraft:overworld", merchant, 2501));
        assertThatIllegalArgumentException().isThrownBy(() -> refs.resolve(ref, session, "minecraft:the_nether", merchant, 101));
        assertThatIllegalArgumentException().isThrownBy(() -> refs.resolve(ref, UUID.randomUUID(), "minecraft:overworld", merchant, 101));
        assertThatIllegalArgumentException().isThrownBy(() -> refs.resolve(ref, session, "minecraft:overworld", UUID.randomUUID(), 101));
        assertThat(V2MerchantReferences.sameOffer(expected, offer(new ItemStack(Items.EMERALD, 3), 2), 2)).isTrue();
        assertThat(V2MerchantReferences.sameOffer(expected, offer(new ItemStack(Items.EMERALD, 4), 0), 0)).isFalse();
        var named = new ItemStack(Items.EMERALD, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("another payment"));
        assertThat(V2MerchantReferences.sameOffer(expected, offer(named, 0), 0)).isFalse();
    }
    @Test void inventoryComponentsAndBothPaymentSlotsAreCountedExactly() {
        var expected = offer(new ItemStack(Items.EMERALD, 3), 0);
        var totals = new java.util.LinkedHashMap<>(V2MerchantWork.paymentTotals(expected));
        totals.replaceAll((key, count) -> count * 3);
        assertThat(V2MerchantWork.hasPayments(totals, expected, 3)).isTrue();
        assertThat(V2MerchantWork.hasPayments(totals, expected, 4)).isFalse();
        var named = new ItemStack(Items.EMERALD, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
        assertThat(V2MerchantWork.hasPayments(totals, offer(named, 0), 1)).isFalse();
        var snapshot = new ContainerSyncSignals.ContainerSnapshot(UUID.randomUUID(), 1, "minecraft:merchant", 1,
                List.of(ContainerSyncSignals.StackFingerprint.fromServerPacket(expected.costA()),
                        ContainerSyncSignals.StackFingerprint.fromServerPacket(expected.costB()),
                        ContainerSyncSignals.StackFingerprint.fromServerPacket(expected.result())),
                ContainerSyncSignals.StackFingerprint.EMPTY, 1, 1);
        assertThat(V2MerchantWork.totals(snapshot)).isEqualTo(V2MerchantWork.paymentTotals(expected));
    }
}
