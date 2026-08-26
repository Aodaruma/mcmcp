package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import dev.aod.mcmcp.agent.dsl.PredicateEvaluator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded cursor which expands fixed repeats and evaluates each if exactly on entry. */
public final class ActionProgramCursor {
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    public ActionProgramCursor(ActionDsl.Program program) {
        Objects.requireNonNull(program, "program");
        frames.push(new Frame(expand(program.body())));
    }

    public Advance next(PolicySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var completedControls = new ArrayList<String>();
        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            if (frame.index == frame.entries.size()) {
                frames.pop();
                continue;
            }
            Entry entry = frame.entries.get(frame.index++);
            if (entry instanceof Control control) {
                completedControls.add(control.id);
                continue;
            }
            if (entry instanceof Conditional conditional) {
                completedControls.add(conditional.id);
                List<Entry> branch = PredicateEvaluator.evaluate(conditional.predicate, snapshot)
                        ? conditional.thenBranch
                        : conditional.elseBranch;
                if (!branch.isEmpty()) {
                    frames.push(new Frame(branch));
                }
                continue;
            }
            return new Advance(completedControls, ((Primitive) entry).node);
        }
        return new Advance(completedControls, null);
    }

    private static List<Entry> expand(List<ActionDsl.Node> nodes) {
        var result = new ArrayList<Entry>();
        for (ActionDsl.Node node : nodes) {
            if (node instanceof ActionDsl.Repeat repeat) {
                result.add(new Control(repeat.id()));
                List<Entry> body = expand(repeat.body());
                for (int count = 0; count < repeat.count(); count++) {
                    result.addAll(body);
                }
            } else if (node instanceof ActionDsl.If conditional) {
                result.add(new Conditional(
                        conditional.id(),
                        conditional.condition(),
                        expand(conditional.thenBranch()),
                        expand(conditional.elseBranch())));
            } else {
                result.add(new Primitive(node));
            }
        }
        return List.copyOf(result);
    }

    public record Advance(List<String> completedControlNodeIds, ActionDsl.Node primitive) {
        public Advance {
            completedControlNodeIds = List.copyOf(completedControlNodeIds);
            if (primitive instanceof ActionDsl.If || primitive instanceof ActionDsl.Repeat) {
                throw new IllegalArgumentException("Advance primitive must be atomic");
            }
        }

        public boolean finished() {
            return primitive == null;
        }
    }

    private sealed interface Entry permits Control, Conditional, Primitive {
    }

    private record Control(String id) implements Entry {
    }

    private record Conditional(
            String id,
            ActionDsl.Predicate predicate,
            List<Entry> thenBranch,
            List<Entry> elseBranch) implements Entry {
    }

    private record Primitive(ActionDsl.Node node) implements Entry {
    }

    private static final class Frame {
        private final List<Entry> entries;
        private int index;

        private Frame(List<Entry> entries) {
            this.entries = entries;
        }
    }
}
