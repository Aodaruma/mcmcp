package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal Action adapter request for a homogeneous, one-pose construction suffix.
 *
 * <p>The wrapped legacy plan remains the Minecraft adapter boundary, while this type closes the
 * public construction subset to at most eight ordered placements or safe clears.</p>
 */
public record KnownConstructionRequest(ApplyBlockPlanRequest plan) {
    public static final int MAX_ENTRIES = 8;
    private static final BlockPlan.Transform IDENTITY_TRANSFORM =
            new BlockPlan.Transform(0, "none");

    public KnownConstructionRequest {
        Objects.requireNonNull(plan, "plan");
        if (plan.steps().size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("construction must contain at most 8 entries");
        }
        if (plan.bounds().maxTravelBlocks() != 0) {
            throw new IllegalArgumentException("construction must be stationary");
        }

        boolean clear = plan.steps().stream()
                .allMatch(step -> step.operation() == ApplyBlockPlanOperation.BREAK_TO_AIR);
        boolean place = plan.steps().stream()
                .allMatch(step -> step.operation() == ApplyBlockPlanOperation.PLACE);
        if (clear == place || clear != plan.bounds().allowBreak()) {
            throw new IllegalArgumentException(
                    "construction must be homogeneous place-only or break-only");
        }
        if (clear != (plan.breakSafety()
                == ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK)) {
            throw new IllegalArgumentException("construction break safety does not match its mode");
        }

        var priorEntries = new LinkedHashMap<String, ApplyBlockPlanStep>();
        for (var step : plan.steps()) {
            if (clear) {
                SafeConstructionBlockPolicy.requireExpectedState(step.expectedBefore());
                if (!"minecraft:air".equals(step.expectedAfter().blockId())
                        || !step.expectedAfter().properties().isEmpty()
                        || step.requiredItemId().isPresent()
                        || step.supportWitness().isPresent()) {
                    throw new IllegalArgumentException(
                            "construction clear must be exact break-to-air");
                }
                continue;
            }
            if (!"minecraft:air".equals(step.expectedBefore().blockId())
                    || !step.expectedBefore().properties().isEmpty()) {
                throw new IllegalArgumentException(
                        "construction placement must require exact minecraft:air");
            }
            String item = step.requiredItemId().orElseThrow();
            SafeConstructionBlockPolicy.requireExpectedStateAndItem(step.expectedAfter(), item);
            PlacementSupportWitness witness = step.supportWitness().orElseThrow(() ->
                    new IllegalArgumentException(
                            "construction placement requires an explicit support witness"));
            requireAdjacentSupport(step.target(), witness);
            requireSafeCompleteSupport(witness.expectedState());

            witness.confirmedDependencyEntryId().ifPresent(dependencyId -> {
                ApplyBlockPlanStep dependency = priorEntries.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "support dependency must reference an earlier construction entry");
                }
                if (!dependency.target().equals(witness.support())
                        || !dependency.expectedAfter().equals(witness.expectedState())) {
                    throw new IllegalArgumentException(
                            "support dependency must exactly match its confirmed after-state");
                }
            });
            priorEntries.put(step.id(), step);
        }
    }

    public KnownConstructionRequest(
            String planId, List<ApplyBlockPlanStep> entries, ActionBounds bounds) {
        this(new ApplyBlockPlanRequest(planId, 1, 1, entries, bounds));
    }

    public List<ApplyBlockPlanStep> entries() {
        return plan.steps();
    }

    public ActionBounds bounds() {
        return plan.bounds();
    }

    public Map<String, Integer> requiredResources() {
        return plan.requiredResources();
    }

    public boolean breakOnly() {
        return plan.breakSafety()
                == ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK;
    }

    private static void requireAdjacentSupport(
            BlockTarget target, PlacementSupportWitness witness) {
        BlockTarget support = witness.support();
        if (!target.dimension().equals(support.dimension())) {
            throw new IllegalArgumentException("support and target dimensions must match");
        }
        int expectedX = support.x();
        int expectedY = support.y();
        int expectedZ = support.z();
        switch (witness.clickedFace()) {
            case "down" -> expectedY--;
            case "up" -> expectedY++;
            case "north" -> expectedZ--;
            case "south" -> expectedZ++;
            case "west" -> expectedX--;
            case "east" -> expectedX++;
            default -> throw new AssertionError(witness.clickedFace());
        }
        if (target.x() != expectedX || target.y() != expectedY || target.z() != expectedZ) {
            throw new IllegalArgumentException(
                    "support face must point to the construction target");
        }
    }

    private static void requireSafeCompleteSupport(BlockStateFingerprint state) {
        try {
            SafeConstructionBlockPolicy.requireExpectedState(state);
            return;
        } catch (SafeConstructionBlockPolicy.UnsafeConstructionBlockException ignored) {
            // Existing inert terrain supports use the older, smaller support policy.
        }
        if (!SafePlacementSupportPolicy.allowsRegisteredBlockId(state.blockId())) {
            throw new IllegalArgumentException("construction support is outside the safe policy");
        }
        BlockStateView normalized = BlockPlanStateTransformer.transformFull(
                new BlockStateView(state.blockId(), state.properties()),
                IDENTITY_TRANSFORM,
                "construction.support.expected_state");
        if (!state.blockId().equals(normalized.block())
                || !state.properties().equals(normalized.properties())) {
            throw new IllegalArgumentException("construction support state must be exact");
        }
    }
}
