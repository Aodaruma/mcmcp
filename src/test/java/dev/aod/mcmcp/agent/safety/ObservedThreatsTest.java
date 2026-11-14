package dev.aod.mcmcp.agent.safety;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ObservedThreatsTest {
    private final Object player = new Object(), world = new Object();
    private final UUID action = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    private final ObservedThreats.History history = new ObservedThreats.History();

    @Test void returnAfterLongRescueRechecksMultipleKnownAttackers() {
        history.bind(player, world, action);
        history.received(first, 10, false);
        history.received(second, 20, false);
        assertThat(history.attacking(first, 150, false, false, false)).isTrue();
        assertThat(history.attacking(first, 700, false, false, true)).isTrue();
        assertThat(history.attacking(second, 700, false, false, true)).isTrue();
        assertThat(history.recentAttack(700)).isFalse();
    }

    @Test void oldAttackDoesNotAuthorizeAttackingCalmOrUnknownMobs() {
        history.bind(player, world, action);
        history.received(first, 10, false);
        assertThat(history.attacking(first, 700, false, false, false)).isFalse();
        assertThat(history.attacking(second, 700, false, false, true)).isFalse();
        // A currently known target takes precedence even over recent received damage.
        assertThat(history.attacking(first, 20, true, false, true)).isFalse();
    }

    @Test void witnessedTargetSurvivesRescueOnlyWithinSameParentAction() {
        history.bind(player, world, action);
        assertThat(history.attacking(first, 10, true, true, false)).isTrue();
        history.bind(player, world, action);
        assertThat(history.attacking(first, 700, false, false, true)).isTrue();
        history.bind(player, world, null);
        assertThat(history.attacking(first, 700, false, false, true)).isFalse();
        history.bind(player, world, UUID.randomUUID());
        assertThat(history.attacking(first, 700, false, false, true)).isFalse();
    }

    @Test void worldAndPlayerChangesClearEvenRecentAttribution() {
        history.bind(player, world, action);
        history.received(first, 10, true);
        history.bind(player, new Object(), action);
        assertThat(history.attacking(first, 11, false, false, true)).isFalse();
        assertThat(history.recentAttack(11)).isFalse();
        history.received(first, 12, false);
        history.bind(new Object(), world, action);
        assertThat(history.attacking(first, 13, false, false, true)).isFalse();
    }

    @Test void startingAnActionDoesNotEraseAnAttackJustReceivedWhileIdle() {
        history.bind(player, world, null);
        history.received(first, 10, false);
        history.bind(player, world, action);
        assertThat(history.attacking(first, 11, false, false, false)).isTrue();
        assertThat(history.attacking(first, 500, false, false, true)).isFalse();
    }

    @Test void identityMemoryIsBoundedAndRejectsARewoundClock() {
        history.bind(player, world, action);
        history.received(first, 10, false);
        assertThat(history.attacking(first, 9, false, false, true)).isFalse();
        assertThat(history.recentAttack(9)).isFalse();
        for (int i = 0; i < 64; i++) history.received(UUID.randomUUID(), 20, false);
        assertThat(history.attacking(first, 700, false, false, true)).isFalse();
        history.received(second, 30, false);
        assertThat(history.attacking(second, 700, false, false, true)).isTrue();
    }
}
