package dev.aodaruma.craftagent.observation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Same-tick, provenance-preserving comparison of a bounded relative block plan. */
public final class BlockPlanComparator {
    /** Compatibility alias retained for existing compare schema/tests. */
    public static final int MAX_EXPECTED_BLOCKS = BlockPlan.MAX_EXPECTED_BLOCKS;

    private final MinecraftObservationService observations;
    private final WorldMemory memory;

    public BlockPlanComparator(MinecraftObservationService observations, WorldMemory memory) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public Map<String, Object> compare(Minecraft minecraft, long clientTick, Map<String, Object> arguments) {
        var plan = parse(arguments);
        var level = minecraft.level;
        if (level == null || memory.sessionId() == null) {
            throw new MinecraftObservationService.ObservationUnavailableException("no_world", "No client world is ready");
        }
        var currentDimension = level.dimension().identifier().toString();
        if (!plan.anchor().dimension().equals(currentDimension)) {
            throw new IllegalArgumentException("anchor dimension must equal the current dimension");
        }

        var positions = plan.expected().stream()
                .map(BlockPlan.Expected::worldPosition)
                .map(position -> new BlockPos(position.x(), position.y(), position.z()))
                .toList();
        var samples = observations.observeBlocks(
                minecraft,
                clientTick,
                positions,
                MinecraftObservationService.BlockSource.LIVE_AND_MEMORY);

        var coverage = new Counter();
        var summary = new LinkedHashMap<String, Integer>();
        for (var name : List.of("match_current", "mismatch_current", "match_last_known",
                "mismatch_last_known", "unknown")) {
            summary.put(name, 0);
        }
        var differences = new ArrayList<Map<String, Object>>();
        var required = new Counter();

        for (int index = 0; index < plan.expected().size(); index++) {
            var expected = plan.expected().get(index);
            var sample = samples.get(index);
            var outcome = classify(expected, sample);
            summary.compute(outcome.result(), (ignored, value) -> value + 1);
            coverage.record(sample.outcome());
            if (expected.required()) {
                required.total++;
                if ("match_current".equals(outcome.result())) {
                    required.current++;
                } else if ("unknown".equals(outcome.result())) {
                    required.unknown++;
                } else {
                    required.mismatch++;
                }
            }
            if (!outcome.match() || plan.includeMatches()) {
                differences.add(difference(expected, sample, outcome));
            }
        }

        var basis = new LinkedHashMap<String, Object>();
        basis.put("world_session_id", memory.sessionId().toString());
        basis.put("dimension", currentDimension);
        basis.put("client_tick", clientTick);
        basis.put("observation_revision", memory.revision());

        var result = new LinkedHashMap<String, Object>();
        result.put("plan_hash", plan.hash());
        result.put("basis", basis);
        result.put("coverage", Map.of(
                "requested", plan.expected().size(),
                "current", coverage.current,
                "last_known", coverage.lastKnown,
                "unknown", coverage.unknown));
        result.put("summary", summary);
        result.put("required_verification", Map.of(
                "total", required.total,
                "match_current", required.current,
                "mismatch_or_stale", required.mismatch,
                "unknown", required.unknown,
                "complete", required.total == required.current && required.unknown == 0));
        result.put("differences", List.copyOf(differences));
        return result;
    }

    BlockPlan parse(Map<String, Object> arguments) {
        return BlockPlan.parse(arguments);
    }

    private static Outcome classify(
            BlockPlan.Expected expected,
            MinecraftObservationService.BlockSample sample) {
        return switch (sample.outcome()) {
            case CURRENT -> {
                boolean match = expected.matches(sample.observation().state());
                yield new Outcome(match ? "match_current" : "mismatch_current", match);
            }
            case LAST_KNOWN -> {
                boolean match = expected.matches(sample.observation().state());
                yield new Outcome(match ? "match_last_known" : "mismatch_last_known", match);
            }
            case NOT_CURRENTLY_OBSERVABLE, UNKNOWN -> new Outcome("unknown", false);
        };
    }

    private static Map<String, Object> difference(
            BlockPlan.Expected expected,
            MinecraftObservationService.BlockSample sample,
            Outcome outcome) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", expected.id());
        result.put("required", expected.required());
        result.put("result", outcome.result());
        result.put("world_position", expected.worldPosition().toMap());
        result.put("expected", expected.state().toMap());
        if (sample.observation() != null) {
            result.put("actual", sample.observation().toMap(
                    sample.currentTick(),
                    sample.outcome() == MinecraftObservationService.BlockOutcome.CURRENT,
                    sample.visibleFaces(),
                    sample.withinReach()));
        } else {
            result.put("reason", sample.reason());
        }
        return result;
    }

    private record Outcome(String result, boolean match) {
    }

    private static final class Counter {
        private int total;
        private int current;
        private int lastKnown;
        private int mismatch;
        private int unknown;

        private void record(MinecraftObservationService.BlockOutcome outcome) {
            switch (outcome) {
                case CURRENT -> current++;
                case LAST_KNOWN -> lastKnown++;
                case NOT_CURRENTLY_OBSERVABLE, UNKNOWN -> unknown++;
            }
        }
    }
}
