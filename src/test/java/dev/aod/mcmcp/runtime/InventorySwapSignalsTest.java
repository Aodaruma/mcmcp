package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InventorySwapSignalsTest {
    private static final StackFingerprint TORCH = new StackFingerprint("minecraft:torch", 64, 7);
    private static final StackFingerprint SNOW = new StackFingerprint("minecraft:snow_block", 59, 8);

    @Test
    void requiresBothServerPayloadsInEitherOrderWithoutCountingUnrelatedPackets() {
        for (boolean sourceFirst : new boolean[] {true, false}) {
            var session = UUID.randomUUID();
            var ticket = new InventorySwapSignals.Ticket(session, 12, 2, TORCH, SNOW);
            ticket.accept(1, TORCH);
            assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.WAITING);
            ticket.accept(sourceFirst ? 12 : 2, sourceFirst ? SNOW : TORCH);
            assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.WAITING);
            ticket.accept(sourceFirst ? 2 : 12, sourceFirst ? TORCH : SNOW);
            assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.CONFIRMED);
            assertThat(ticket.result(UUID.randomUUID())).isEqualTo(InventorySwapSignals.Result.MISMATCH);
        }
    }

    @Test
    void rejectsWrongCountsComponentsAndUnchangedServerState() {
        for (var wrong : new StackFingerprint[] {SNOW,
                new StackFingerprint("minecraft:torch", 63, 7),
                new StackFingerprint("minecraft:torch", 64, 9), StackFingerprint.EMPTY}) {
            var session = UUID.randomUUID();
            var ticket = new InventorySwapSignals.Ticket(session, 10, 0, TORCH, SNOW);
            ticket.accept(10, SNOW);
            ticket.accept(0, wrong);
            assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.MISMATCH);
        }
    }

    @Test
    void cancelledSwapCannotBeConfirmedByLatePackets() {
        var session = UUID.randomUUID();
        var ticket = new InventorySwapSignals.Ticket(session, 12, 2, TORCH, SNOW);
        ticket.accept(12, SNOW);
        ticket.close();
        ticket.accept(2, TORCH);
        assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.MISMATCH);
    }

    @Test
    void acceptsEmptyDestinationAndRejectsOutOfScopeSlots() {
        var session = UUID.randomUUID();
        var ticket = new InventorySwapSignals.Ticket(session, 35, 8, TORCH, StackFingerprint.EMPTY);
        ticket.accept(35, StackFingerprint.EMPTY);
        ticket.accept(8, TORCH);
        assertThat(ticket.result(session)).isEqualTo(InventorySwapSignals.Result.CONFIRMED);
        assertThatThrownBy(() -> new InventorySwapSignals.Ticket(session, 8, 1, TORCH, SNOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventorySwapSignals.Ticket(session, 36, 0, TORCH, SNOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
