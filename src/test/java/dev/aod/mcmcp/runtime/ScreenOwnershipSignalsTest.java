package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenOwnershipSignalsTest {
    private static final String MENU = "minecraft:generic_9x3";
    private static final ContainerSyncSignals.StackFingerprint EMPTY =
            ContainerSyncSignals.StackFingerprint.EMPTY;
    private static final ContainerSyncSignals.StackFingerprint STONE =
            new ContainerSyncSignals.StackFingerprint("minecraft:stone", 1, 77);

    @Test
    void playerInventoryWithoutARegisteredMenuTypeIsNotOwned() {
        var menu = new AbstractContainerMenu(null, 0) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };

        assertThat(ScreenOwnershipSignals.registeredMenuTypeId(menu)).isEmpty();
    }

    @Test
    void userOpenedMenuPacketsPopulateTheLedgerWithoutGrantingOwnership() {
        var session = UUID.randomUUID();
        var channel = new ContainerSyncSignals.SessionChannel();
        var core = new ScreenOwnershipSignals.Core();
        channel.bindAndSnapshot(session);
        core.bindSession(session, 0);

        var open = channel.openScreen(7, MENU, 1);
        var content = channel.fullContent(7, MENU, 1, List.of(STONE), EMPTY, 2);

        assertThat(core.onOpenScreen(open.snapshot().lastOpenScreen()).relevant()).isFalse();
        assertThat(core.onFullContent(content, true).relevant()).isFalse();
        assertThat(content.applied()).isTrue();
        assertThat(content.snapshot().container()).isNotNull();
        assertThat(core.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
        assertThat(core.snapshot().owned()).isFalse();
    }

    @Test
    void expectedTokenRetainsTheFullPrivateTargetAuthority() {
        var session = UUID.randomUUID();
        var routine = UUID.randomUUID();
        var token = token(session, routine, 20);

        assertThat(token.worldSessionId()).isEqualTo(session);
        assertThat(token.routineId()).isEqualTo(routine);
        assertThat(token.targetIdentity()).isEqualTo("minecraft:overworld@1,64,2");
        assertThat(token.targetStateFingerprint())
                .isEqualTo("minecraft:chest[facing=north,type=single,waterlogged=false]");
        assertThat(token.menuTypeId()).isEqualTo(MENU);
        assertThat(token.deadlineTick()).isEqualTo(20);
        assertThatThrownBy(() -> new ExpectedOpenToken(session, routine, "target", "state",
                "not a registry id", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preexistingMatchingScreenCannotBeAdoptedWithoutAFreshOpenPacket() {
        var fixture = new Fixture(20);

        var opening = fixture.core.allowScreenOpening(7, MENU, 2);

        assertThat(opening.allowed()).isFalse();
        assertThat(opening.failedNow()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
        assertThat(fixture.core.snapshot().owned()).isFalse();
    }

    @Test
    void openPacketAndScreenRemainUnownedUntilFreshFullContent() {
        var fixture = new Fixture(20);

        assertThat(fixture.open(7, MENU, 2).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.EXPECTING_SCREEN);
        assertThat(fixture.core.snapshot().owned()).isFalse();
        assertThat(fixture.core.allowScreenOpening(7, MENU, 2).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT);
        assertThat(fixture.core.snapshot().owned()).isFalse();

        var owned = fixture.full(7, MENU, 9, List.of(STONE), EMPTY, 3, true);

        assertThat(owned.allowed()).isTrue();
        assertThat(fixture.core.snapshot().owned()).isTrue();
        assertThat(fixture.core.snapshot().ownedSession().token().targetIdentity())
                .isEqualTo("minecraft:overworld@1,64,2");
        assertThat(fixture.core.snapshot().ownedSession().serverSnapshot().slots())
                .containsExactly(STONE);
    }

    @Test
    void wrongOpenIdOrTypeFailsClosedAndSecondTokenCannotReplaceTheFirst() {
        var wrongType = new Fixture(20);
        assertThat(wrongType.open(7, "minecraft:hopper", 2).failedNow()).isTrue();
        assertThat(wrongType.core.snapshot().owned()).isFalse();

        var second = new Fixture(20);
        assertThat(second.core.beginExpectedOpen(
                token(second.session, UUID.randomUUID(), 20), 1, 0)).isFalse();
        assertThat(second.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
    }

    @Test
    void expectedMenuMayReplacePassiveChatButStillRequiresFullServerContent() {
        var fixture = new Fixture(20);
        fixture.open(7, MENU, 2);
        fixture.core.allowScreenOpening(7, MENU, 2);
        assertThat(fixture.core.onPassiveScreenClosing(2).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT);
        assertThat(fixture.core.snapshot().owned()).isFalse();
        assertThat(fixture.full(7, MENU, 9, List.of(STONE), EMPTY, 3, true).allowed()).isTrue();
        assertThat(fixture.core.snapshot().owned()).isTrue();
        assertThat(fixture.core.onScreenClosing(7, MENU).failedNow()).isTrue();
    }

    @Test
    void passiveCloseExceptionIsSingleUseSameTickAndOnlyAfterExpectedOpening() {
        var before = new Fixture(20);
        assertThat(before.core.onPassiveScreenClosing(2).failedNow()).isTrue();
        var late = new Fixture(20);
        late.open(7, MENU, 2);
        late.core.allowScreenOpening(7, MENU, 2);
        assertThat(late.core.onPassiveScreenClosing(3).failedNow()).isTrue();
        var twice = new Fixture(20);
        twice.open(7, MENU, 2);
        twice.core.allowScreenOpening(7, MENU, 2);
        assertThat(twice.core.onPassiveScreenClosing(2).allowed()).isTrue();
        assertThat(twice.core.onPassiveScreenClosing(2).failedNow()).isTrue();
    }

    @Test
    void wrongFullContentIdentityMissingScreenAndOutOfOrderSlotAllFailClosed() {
        var wrongContent = new Fixture(20);
        wrongContent.open(7, MENU, 2);
        wrongContent.core.allowScreenOpening(7, MENU, 2);
        assertThat(wrongContent.full(8, MENU, 1, List.of(STONE), EMPTY, 3, true)
                .failedNow()).isTrue();

        var missingScreen = new Fixture(20);
        missingScreen.open(7, MENU, 2);
        missingScreen.core.allowScreenOpening(7, MENU, 2);
        assertThat(missingScreen.full(7, MENU, 1, List.of(STONE), EMPTY, 3, false)
                .failedNow()).isTrue();

        var slotBeforeFull = new Fixture(20);
        slotBeforeFull.open(7, MENU, 2);
        slotBeforeFull.core.allowScreenOpening(7, MENU, 2);
        var rawFull = slotBeforeFull.channel.fullContent(
                7, MENU, 1, List.of(STONE), EMPTY, 3);
        var slot = slotBeforeFull.channel.slot(7, MENU, 2, 0, EMPTY, 4);
        assertThat(rawFull.applied()).isTrue();
        assertThat(slotBeforeFull.core.onIncrementalContent(slot, true).failedNow()).isTrue();
    }

    @Test
    void deadlineManualInputUnexpectedCloseAndSessionChangeInvalidateAuthority() {
        var expired = new Fixture(2);
        assertThat(expired.core.expire(3).reason())
                .isEqualTo("expected_screen_deadline_exceeded");

        var owned = ownedFixture(EMPTY);
        assertThat(owned.core.expire(21).relevant()).isFalse();
        assertThat(owned.core.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.OWNED);

        var manual = new Fixture(20);
        assertThat(manual.core.failIfActive("manual_mouse_scroll_input").failedNow()).isTrue();
        assertThat(manual.core.snapshot().owned()).isFalse();

        var closing = ownedFixture(EMPTY);
        assertThat(closing.core.onScreenClosing(7, MENU).reason())
                .isEqualTo("unexpected_screen_closed");

        var changed = new Fixture(20);
        var transition = changed.core.bindSession(UUID.randomUUID(), 0);
        assertThat(transition.failedNow()).isTrue();
        assertThat(changed.core.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
        assertThat(changed.core.snapshot().boundWorldSessionId())
                .isNotEqualTo(changed.session);
    }

    @Test
    void nonContainerScreenOnlyFailsAnActiveContainerAuthority() {
        var idle = new ScreenOwnershipSignals.Core();
        idle.bindSession(UUID.randomUUID(), 0);
        assertThat(idle.failIfActive("unexpected_screen_opened").relevant()).isFalse();
        assertThat(idle.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.IDLE);

        var owned = ownedFixture(EMPTY);
        assertThat(owned.core.failIfActive("unexpected_screen_opened").failedNow()).isTrue();
        assertThat(owned.core.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
        assertThat(owned.core.snapshot().owned()).isFalse();
    }

    @Test
    void stateIdWrapIsAcceptedAsAnExactInboundUpdate() {
        var fixture = ownedFixture(EMPTY, Integer.MAX_VALUE);
        var update = fixture.channel.slot(7, MENU, Integer.MIN_VALUE, 0, EMPTY, 4);

        assertThat(fixture.core.onIncrementalContent(update, true).allowed()).isTrue();
        assertThat(fixture.core.snapshot().ownedSession().serverSnapshot().stateId())
                .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void ownedAsyncMenuAcceptsExactUpdatesAfterTheOpenDeadline() {
        var fixture = ownedFixture(EMPTY);
        var update = fixture.channel.slot(7, MENU, 2, 0, EMPTY, 21);

        assertThat(fixture.core.onIncrementalContent(update, true).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase()).isEqualTo(ScreenOwnershipSignals.Phase.OWNED);
    }

    @Test
    void cleanupClosesOnlyTheExactOwnedMenuAndNeverOffersACursorRescueClick() {
        var nonEmptyCursor = ownedFixture(STONE);

        var cleanup = nonEmptyCursor.core.cancelRoutine(
                nonEmptyCursor.routine, new ScreenOwnershipSignals.MenuView(7, MENU));

        assertThat(cleanup.authorityMatched()).isTrue();
        assertThat(cleanup.closeMenuBestEffort()).isTrue();
        assertThat(cleanup.serverCursorEmpty()).isFalse();
        assertThat(cleanup.rescueClickAllowed()).isFalse();
        assertThat(cleanup.reason()).isEqualTo(
                "close_owned_menu_without_cursor_rescue_click");
        assertThat(nonEmptyCursor.core.onScreenClosing(7, MENU).allowed()).isTrue();
        assertThat(nonEmptyCursor.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);

        var ambiguous = ownedFixture(EMPTY);
        var refused = ambiguous.core.cancelRoutine(
                ambiguous.routine, new ScreenOwnershipSignals.MenuView(8, MENU));
        assertThat(refused.closeMenuBestEffort()).isFalse();
        assertThat(refused.rescueClickAllowed()).isFalse();
        assertThat(ambiguous.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
    }

    @Test
    void repeatedCleanupCannotAcknowledgeAStillOpenOwnedScreen() {
        var fixture = ownedFixture(EMPTY);

        var first = fixture.core.cancelRoutine(
                fixture.routine, new ScreenOwnershipSignals.MenuView(7, MENU));
        var repeated = fixture.core.cancelRoutine(
                fixture.routine, new ScreenOwnershipSignals.MenuView(7, MENU));

        assertThat(first.closeMenuBestEffort()).isTrue();
        assertThat(repeated.authorityMatched()).isTrue();
        assertThat(repeated.closeMenuBestEffort()).isFalse();
        assertThat(repeated.reason()).isEqualTo("owned_screen_close_pending");
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.CLOSING);
        assertThat(fixture.core.expire(21).relevant()).isFalse();

        assertThat(fixture.core.onScreenClosing(7, MENU).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
    }

    @Test
    void legacyCancellationBeforeOwnershipResetsImmediately() {
        var fixture = new Fixture(20);

        var canceled = fixture.core.cancelRoutine(fixture.routine, null);

        assertThat(canceled.authorityMatched()).isTrue();
        assertThat(canceled.closeMenuBestEffort()).isFalse();
        assertThat(canceled.reason()).isEqualTo(
                "screen_authority_canceled_before_ownership");
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
        assertThat(fixture.core.snapshot().cancelBeforeOwnership()).isFalse();
        assertThat(fixture.core.expire(21).relevant()).isFalse();

        var afterOpenPacket = new Fixture(20);
        afterOpenPacket.open(7, MENU, 2);
        assertThat(afterOpenPacket.core.cancelRoutine(afterOpenPacket.routine, null)
                .reason()).isEqualTo("screen_authority_canceled_before_ownership");
        assertThat(afterOpenPacket.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);

        var afterMaterialization = new Fixture(20);
        afterMaterialization.open(7, MENU, 2);
        afterMaterialization.core.allowScreenOpening(7, MENU, 2);
        var materializedCancel = afterMaterialization.core.cancelRoutine(
                afterMaterialization.routine,
                new ScreenOwnershipSignals.MenuView(7, MENU));
        assertThat(materializedCancel.closeMenuBestEffort()).isFalse();
        assertThat(afterMaterialization.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
    }

    @Test
    void predictedUseCancellationRetiresOnlyAfterItsCausalAck() {
        var fixture = new Fixture(2);

        fixture.core.cancelRoutine(fixture.routine, null,
                ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK);
        assertThat(fixture.core.expire(3).allowed()).isFalse();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET);
        assertThat(fixture.core.snapshot().causalCancelRequired()).isTrue();

        var retired = fixture.core.cancelRoutine(fixture.routine, null,
                ScreenOwnershipSignals.CausalBarrierStatus.ACKNOWLEDGED);
        assertThat(retired.authorityMatched()).isTrue();
        assertThat(retired.reason()).isEqualTo(
                "predicted_open_retired_after_causal_barrier");
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
    }

    @Test
    void canceledPredictedUseAcceptsLateExactScreenAndClosesBeforeFullContent() {
        var fixture = new Fixture(2);
        fixture.core.cancelRoutine(fixture.routine, null,
                ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK);
        fixture.core.expire(3);

        assertThat(fixture.open(7, MENU, 3).allowed()).isTrue();
        assertThat(fixture.core.allowScreenOpening(7, MENU, 3).allowed()).isTrue();
        var close = fixture.core.cancelRoutine(
                fixture.routine, new ScreenOwnershipSignals.MenuView(7, MENU),
                ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK);

        assertThat(close.closeMenuBestEffort()).isTrue();
        assertThat(close.serverCursorEmpty()).isFalse();
        assertThat(close.reason()).isEqualTo(
                "close_materialized_menu_before_agent_click");
        assertThat(fixture.core.onScreenClosing(7, MENU).allowed()).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
    }

    @Test
    void ownershipAndLastServerCursorProofSurviveFailureUntilSafeRelease() {
        var fixture = ownedFixture(STONE);

        fixture.core.failIfActive("test_failure");

        assertThat(fixture.core.snapshot().everOwned()).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProven()).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorEmpty()).isFalse();
        assertThat(fixture.core.cancelRoutine(fixture.routine, null).reason())
                .isEqualTo("failed_screen_close_pending");
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
        assertThat(fixture.core.releaseRoutineOnIdentityLoss(fixture.routine)).isTrue();
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.IDLE);
    }

    @Test
    void agentClickInvalidatesOldCursorProofUntilANewerCursorPacketArrives() {
        var fixture = ownedFixture(EMPTY);
        long dispatchRevision = fixture.core.snapshot().packetLedgerRevision();

        assertThat(fixture.core.invalidateServerCursorProof(
                fixture.routine, dispatchRevision)).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProven()).isFalse();
        assertThat(fixture.core.snapshot().cursorProofRequiredAfterRevision())
                .isEqualTo(dispatchRevision);
        assertThat(fixture.core.cancelRoutine(
                fixture.routine, new ScreenOwnershipSignals.MenuView(7, MENU)).reason())
                .isEqualTo("owned_server_cursor_proof_pending");

        var slotOnly = fixture.channel.slot(7, MENU, 2, 0, STONE, 4);
        assertThat(fixture.core.onIncrementalContent(slotOnly, true, false).allowed())
                .isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProven()).isFalse();

        var freshCursor = fixture.channel.carried(7, MENU, EMPTY, 5);
        assertThat(fixture.core.onIncrementalContent(freshCursor, true, true).allowed())
                .isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProven()).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorEmpty()).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProofRevision())
                .isGreaterThan(dispatchRevision);
    }

    @Test
    void unexpectedCloseCannotReuseCursorProofFromBeforeAgentClick() {
        var fixture = ownedFixture(EMPTY);
        long dispatchRevision = fixture.core.snapshot().packetLedgerRevision();
        fixture.core.invalidateServerCursorProof(fixture.routine, dispatchRevision);

        fixture.core.failIfActive("unexpected_screen_closed");

        assertThat(fixture.core.snapshot().everOwned()).isTrue();
        assertThat(fixture.core.snapshot().lastServerCursorProven()).isFalse();
        assertThat(fixture.core.cancelRoutine(fixture.routine, null).reason())
                .isEqualTo("failed_screen_close_pending");
        assertThat(fixture.core.snapshot().phase())
                .isEqualTo(ScreenOwnershipSignals.Phase.FAILED);
    }

    private static Fixture ownedFixture(ContainerSyncSignals.StackFingerprint cursor) {
        return ownedFixture(cursor, 1);
    }

    private static Fixture ownedFixture(
            ContainerSyncSignals.StackFingerprint cursor, int stateId) {
        var fixture = new Fixture(20);
        fixture.open(7, MENU, 2);
        fixture.core.allowScreenOpening(7, MENU, 2);
        fixture.full(7, MENU, stateId, List.of(STONE), cursor, 3, true);
        return fixture;
    }

    private static ExpectedOpenToken token(UUID session, UUID routine, long deadline) {
        return new ExpectedOpenToken(
                session,
                routine,
                "minecraft:overworld@1,64,2",
                "minecraft:chest[facing=north,type=single,waterlogged=false]",
                MENU,
                deadline);
    }

    private static final class Fixture {
        final UUID session = UUID.randomUUID();
        final UUID routine = UUID.randomUUID();
        final ContainerSyncSignals.SessionChannel channel =
                new ContainerSyncSignals.SessionChannel();
        final ScreenOwnershipSignals.Core core = new ScreenOwnershipSignals.Core();

        Fixture(long deadline) {
            channel.bindAndSnapshot(session);
            core.bindSession(session, 0);
            assertThat(core.beginExpectedOpen(token(session, routine, deadline), 1, 0)).isTrue();
        }

        ScreenOwnershipSignals.Transition open(int id, String menu, long tick) {
            return core.onOpenScreen(channel.openScreen(id, menu, tick)
                    .snapshot().lastOpenScreen());
        }

        ScreenOwnershipSignals.Transition full(
                int id,
                String menu,
                int stateId,
                List<ContainerSyncSignals.StackFingerprint> slots,
                ContainerSyncSignals.StackFingerprint carried,
                long tick,
                boolean screenMatches) {
            return core.onFullContent(channel.fullContent(
                    id, menu, stateId, slots, carried, tick), screenMatches);
        }
    }
}
