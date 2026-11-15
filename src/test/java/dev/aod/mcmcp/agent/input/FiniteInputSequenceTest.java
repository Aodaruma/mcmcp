package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.routine.BoundedInputLease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiniteInputSequenceTest {
    private static final Set<BoundedInputLease.Input> MOVE = Set.of(
            BoundedInputLease.Input.FORWARD, BoundedInputLease.Input.SNEAK);
    private static final Set<BoundedInputLease.Input> USE = Set.of(BoundedInputLease.Input.USE);

    @Test
    void simultaneousInputsRepeatThenReleaseBeforeNextStep() {
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 2, 1, 2),
                new FiniteInputSequence.Step(USE, 1, 0, 1)));
        assertThat(sequence.totalTicks()).isEqualTo(7);
        var cursor = sequence.cursor();
        assertThat(cursor.next(false, false).inputs()).isEqualTo(MOVE);
        assertThat(cursor.next(false, false).inputs()).isEqualTo(MOVE);
        assertThat(cursor.next(false, false).inputs()).isEmpty();
        assertThat(cursor.next(false, false).inputs()).isEqualTo(MOVE);
        assertThat(cursor.next(false, false).inputs()).isEqualTo(MOVE);
        assertThat(cursor.next(false, false).inputs()).isEmpty();
        assertThat(cursor.next(false, false).inputs()).isEqualTo(USE);
        var complete = cursor.next(false, false);
        assertThat(complete.state()).isEqualTo(FiniteInputSequence.State.COMPLETED);
        assertThat(complete.inputs()).isEmpty();
        assertThat(complete.elapsedTicks()).isEqualTo(7);
        assertThat(cursor.next(false, false)).isEqualTo(complete);
    }

    @Test
    void cancellationAndStopConditionNeverReissueInputs() {
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 10, 0, 1)));
        var cancelled = sequence.cursor();
        assertThat(cancelled.next(false, false).inputs()).isEqualTo(MOVE);
        var stopped = cancelled.next(false, true);
        assertThat(stopped.state()).isEqualTo(FiniteInputSequence.State.CANCELLED);
        assertThat(stopped.inputs()).isEmpty();
        assertThat(cancelled.next(false, false)).isEqualTo(stopped);

        var early = sequence.cursor();
        assertThat(early.next(false, false).inputs()).isEqualTo(MOVE);
        assertThat(early.next(true, false).state()).isEqualTo(FiniteInputSequence.State.COMPLETED);
        assertThat(early.next(false, false).inputs()).isEmpty();
    }

    @Test
    void trailingGapDoesNotDelayCompletion() {
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(USE, 1, 5, 1)));
        assertThat(sequence.totalTicks()).isEqualTo(1);
        var cursor = sequence.cursor();
        assertThat(cursor.next(false, false).inputs()).isEqualTo(USE);
        assertThat(cursor.next(false, false).state())
                .isEqualTo(FiniteInputSequence.State.COMPLETED);
    }

    @Test
    void rejectsUnboundedOrEmptyStepsBeforeAnyInput() {
        assertThatThrownBy(() -> new FiniteInputSequence(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FiniteInputSequence.Step(Set.of(), 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FiniteInputSequence.Step(MOVE, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 864_000, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
