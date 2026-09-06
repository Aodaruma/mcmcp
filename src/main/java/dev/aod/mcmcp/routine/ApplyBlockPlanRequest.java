package dev.aod.mcmcp.routine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded execution request for one block plan. */
public record ApplyBlockPlanRequest(
        String phaseId,
        int phaseIndex,
        int phaseTotal,
        List<ApplyBlockPlanStep> steps,
        ActionBounds bounds,
        BreakSafety breakSafety) {
    public static final String KIND = "apply_block_plan";
    public static final int MAX_STEPS = 64;

    public ApplyBlockPlanRequest {
        Objects.requireNonNull(phaseId, "phaseId");
        if (!phaseId.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid phase id");
        }
        if (phaseIndex < 1 || phaseTotal < 1 || phaseTotal > 64 || phaseIndex > phaseTotal) {
            throw new IllegalArgumentException("phase index must be in 1..phase total");
        }
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(breakSafety, "breakSafety");
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("block plan must contain 1..64 steps");
        }
        if (bounds.maxTravelBlocks() != 0) {
            throw new IllegalArgumentException("local block-plan phases must not own movement");
        }
        var targets = new HashSet<BlockTarget>();
        var ids = new HashSet<String>();
        boolean requiresBreak = false;
        for (var step : steps) {
            Objects.requireNonNull(step, "plan step");
            if (!bounds.contains(step.target())) {
                throw new IllegalArgumentException("plan step is outside the execution bounds");
            }
            if (!targets.add(step.target())) {
                throw new IllegalArgumentException("block plan targets must be unique");
            }
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("block plan entry ids must be unique");
            }
            requiresBreak |= step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR
                    || step.operation() == ApplyBlockPlanOperation.REPLACE;
        }
        if (requiresBreak != bounds.allowBreak()) {
            throw new IllegalArgumentException(
                    "allowBreak must exactly match whether the plan contains break or replace");
        }
    }

    public ApplyBlockPlanRequest(
            String phaseId,
            int phaseIndex,
            int phaseTotal,
            List<ApplyBlockPlanStep> steps,
            ActionBounds bounds) {
        this(phaseId, phaseIndex, phaseTotal, steps, bounds, BreakSafety.SAFE_BREAK_SOURCE);
    }

    public ApplyBlockPlanRequest(List<ApplyBlockPlanStep> steps, ActionBounds bounds) {
        this("phase-1", 1, 1, steps, bounds);
    }

    /** Internal-only selector; public legacy plan parsing always uses SAFE_BREAK_SOURCE. */
    public enum BreakSafety {
        SAFE_BREAK_SOURCE,
        SAFE_CONSTRUCTION_BLOCK,
        SAFE_TUNNEL_BLOCK
    }

    public String kind() {
        return KIND;
    }

    public void validateAdmissionTick(long admittedClientTick) {
        bounds.hardDeadlineClientTick(admittedClientTick);
    }

    /** Exact item counts derived from the immutable phase, before already-satisfied skips. */
    public Map<String, Integer> requiredResources() {
        var result = new LinkedHashMap<String, Integer>();
        for (var step : steps) {
            step.requiredItemId().ifPresent(item -> result.merge(item, 1, Integer::sum));
        }
        return Map.copyOf(result);
    }
}
