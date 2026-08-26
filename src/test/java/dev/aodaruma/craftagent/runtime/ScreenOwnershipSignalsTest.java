package dev.aodaruma.craftagent.runtime;

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
    void ordinaryPlayerInventoryPacketsAreIgnoredWithoutOwnedScreenAuthority() {
        assertThat(ScreenOwnershipSignals.acceptsContainerEvidence(
                ScreenOwnershipSignals.Phase.IDLE)).isFalse();
        assertThat(ScreenOwnershipSignals.acceptsContainerEvidence(
                ScreenOwnershipSignals.Phase.EXPECTING_OPEN_PACKET)).isFalse();
        assertThat(ScreenOwnershipSignals.acceptsContainerEvidence(
                ScreenOwnershipSignals.Phase.EXPECTING_FULL_CONTENT)).isTrue();
        assertThat(ScreenOwnershipSignals.acceptsContainerEvidence(
                ScreenOwnershipSignals.Phase.OWNED)).isTrue();
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
    void stateIdWrapIsAcceptedAsAnExactInboundUpdate() {
        var fixture = ownedFixture(EMPTY, Integer.MAX_VALUE);
        var update = fixture.channel.slot(7, MENU, Integer.MIN_VALUE, 0, EMPTY, 4);

        assertThat(fixture.core.onIncrementalContent(update, true).allowed()).isTrue();
        assertThat(fixture.core.snapshot().ownedSession().serverSnapshot().stateId())
                .isEqualTo(Integer.MIN_VALUE);
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
