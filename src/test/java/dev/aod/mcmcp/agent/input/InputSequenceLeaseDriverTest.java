package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.routine.BoundedInputLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputSequenceLeaseDriverTest {
    private static final Set<BoundedInputLease.Input> MOVE = Set.of(BoundedInputLease.Input.FORWARD);
    private static final Set<BoundedInputLease.Input> USE = Set.of(BoundedInputLease.Input.USE);

    @Test
    void zeroGapStepSwitchReleasesOldInputBeforePublishingNewInput() {
        var control = new RecordingControl();
        var sequence = new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 1, 0, 1),
                new FiniteInputSequence.Step(USE, 1, 0, 1)));
        var driver = driver(sequence, control);
        assertThat(driver.tick(0, 0, false, false).inputs()).isEqualTo(MOVE);
        assertThat(driver.tick(1, 50_000_000L, false, false).inputs()).isEqualTo(USE);
        assertThat(driver.tick(2, 100_000_000L, false, false).state())
                .isEqualTo(FiniteInputSequence.State.COMPLETED);
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD", "P:USE", "R:USE");
        driver.close();
        assertThat(control.events).hasSize(4);
    }

    @Test
    void cancellationClosesHeldInputAndCannotRestartIt() {
        var control = new RecordingControl();
        var driver = driver(new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 10, 0, 1))), control);
        driver.tick(0, 0, false, false);
        assertThat(driver.tick(1, 50_000_000L, false, true).inputs()).isEmpty();
        assertThat(driver.tick(2, 100_000_000L, false, false).state())
                .isEqualTo(FiniteInputSequence.State.CANCELLED);
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD");
    }

    @Test
    void duplicateClientTickReleasesInputWithoutAdvancing() {
        var control = new RecordingControl();
        var driver = driver(new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 2, 0, 1))), control);
        driver.tick(0, 0, false, false);
        assertThatThrownBy(() -> driver.tick(0, 1, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD");
        assertThat(driver.tick(1, 50_000_000L, false, false).inputs()).isEmpty();
        driver.close();
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD");
    }

    @Test
    void expiredLeaseFailsClosedWithoutRenewingInput() {
        var control = new RecordingControl();
        var driver = driver(new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 10, 0, 1))), control);
        driver.tick(0, 0, false, false);
        assertThatThrownBy(() -> driver.tick(1, Duration.ofSeconds(2).toNanos(), false, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
        assertThat(driver.tick(2, Duration.ofSeconds(3).toNanos(), false, false).inputs()).isEmpty();
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD");
    }

    @Test
    void failedReleaseDoesNotPublishNextStepAndCanBeRetried() {
        var control = new RecordingControl();
        var driver = driver(new FiniteInputSequence(List.of(
                new FiniteInputSequence.Step(MOVE, 1, 0, 1),
                new FiniteInputSequence.Step(USE, 1, 0, 1))), control);
        driver.tick(0, 0, false, false);
        control.failNextRelease = true;
        assertThatThrownBy(() -> driver.tick(1, 50_000_000L, false, false))
                .isInstanceOf(IllegalStateException.class);
        driver.close();
        assertThat(control.events).containsExactly("P:FORWARD", "R:FORWARD");
    }

    private static InputSequenceLeaseDriver driver(FiniteInputSequence sequence,
                                                   RecordingControl control) {
        return new InputSequenceLeaseDriver(sequence, (inputs, nowNanos) ->
                BoundedInputLease.acquire(control, inputs, nowNanos, Duration.ofSeconds(1)));
    }

    private static final class RecordingControl implements BoundedInputLease.Control {
        final List<String> events = new ArrayList<>();
        boolean failNextRelease;

        @Override
        public void publish(Set<BoundedInputLease.Input> inputs, long validUntilNanos) {
            events.add("P:" + inputs.iterator().next());
        }

        @Override
        public void release(Set<BoundedInputLease.Input> inputs) {
            if (failNextRelease) {
                failNextRelease = false;
                throw new IllegalStateException("release failed");
            }
            events.add("R:" + inputs.iterator().next());
        }
    }
}
