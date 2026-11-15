package dev.aod.mcmcp.agent.input;

import dev.aod.mcmcp.agent.action.AgentJobLimits;

import dev.aod.mcmcp.routine.BoundedInputLease;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Internal tick plan for v2 logical inputs; does not acquire or publish an input lease. */
public final class FiniteInputSequence {
    public static final int MAX_STEPS = 64;
    public static final int MAX_TICKS = AgentJobLimits.MAX_TICKS;

    private final List<Step> steps;
    private final int totalTicks;

    public FiniteInputSequence(List<Step> steps) {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("input sequence requires 1..64 steps");
        }
        int total = 0;
        for (Step step : steps) {
            total = Math.addExact(total,
                    Math.multiplyExact(Math.addExact(step.holdTicks(), step.gapTicks()),
                            step.repeat()));
        }
        total -= steps.getLast().gapTicks(); // A gap separates holds; none follows the final hold.
        if (total > MAX_TICKS) {
            throw new IllegalArgumentException("input sequence exceeds the tick limit");
        }
        this.steps = steps;
        this.totalTicks = total;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public Cursor cursor() {
        return new Cursor();
    }

    public record Step(Set<BoundedInputLease.Input> inputs, int holdTicks, int gapTicks,
                       int repeat) {
        public Step {
            inputs = Set.copyOf(Objects.requireNonNull(inputs, "inputs"));
            if (inputs.isEmpty() || holdTicks < 1 || holdTicks > MAX_TICKS
                    || gapTicks < 0 || gapTicks > MAX_TICKS || repeat < 1 || repeat > MAX_TICKS) {
                throw new IllegalArgumentException("invalid input sequence step");
            }
        }
    }

    public enum State { RUNNING, COMPLETED, CANCELLED }

    public record Frame(Set<BoundedInputLease.Input> inputs, State state, int elapsedTicks) {
        public Frame {
            inputs = Set.copyOf(Objects.requireNonNull(inputs, "inputs"));
            Objects.requireNonNull(state, "state");
        }
    }

    /** Call exactly once per client tick. The caller releases the prior lease before publishing
     * a changed set, including zero-gap transitions, and releases it on empty or terminal frames.
     */
    public final class Cursor {
        private int stepIndex;
        private int repetition;
        private int holdElapsed;
        private int gapElapsed;
        private int elapsedTicks;
        private State terminal;

        private Cursor() { }

        public Frame next(boolean stopConditionMet, boolean cancelled) {
            if (terminal != null) return new Frame(Set.of(), terminal, elapsedTicks);
            if (cancelled) terminal = State.CANCELLED;
            else if (stopConditionMet || stepIndex == steps.size()) terminal = State.COMPLETED;
            if (terminal != null) return new Frame(Set.of(), terminal, elapsedTicks);

            Step step = steps.get(stepIndex);
            elapsedTicks++;
            if (holdElapsed < step.holdTicks()) {
                holdElapsed++;
                if (holdElapsed == step.holdTicks()
                        && (step.gapTicks() == 0 || repetition + 1 == step.repeat()
                                && stepIndex + 1 == steps.size())) advance();
                return new Frame(step.inputs(), State.RUNNING, elapsedTicks);
            }
            gapElapsed++;
            if (gapElapsed == step.gapTicks()) advance();
            return new Frame(Set.of(), State.RUNNING, elapsedTicks);
        }

        private void advance() {
            holdElapsed = 0;
            gapElapsed = 0;
            if (++repetition == steps.get(stepIndex).repeat()) {
                repetition = 0;
                stepIndex++;
            }
        }
    }
}
