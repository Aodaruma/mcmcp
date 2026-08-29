package dev.aod.mcmcp.routine;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Internal child action. It is deliberately not admitted to RoutineManager. */
public record ApplyBlockPlanChildAction(
        int stepIndex,
        String entryId,
        ApplyBlockPlanChildStage stage,
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        Optional<String> requiredItemId,
        Optional<PlacementSupportWitness> supportWitness) {
    public ApplyBlockPlanChildAction {
        if (stepIndex < 0 || stepIndex >= ApplyBlockPlanRequest.MAX_STEPS) {
            throw new IllegalArgumentException("step index must be in 0..63");
        }
        Objects.requireNonNull(entryId, "entryId");
        if (!entryId.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid plan entry id");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(requiredItemId, "requiredItemId");
        Objects.requireNonNull(supportWitness, "supportWitness");
        boolean itemRequired = stage == ApplyBlockPlanChildStage.PLACE;
        if (itemRequired != requiredItemId.isPresent()) {
            throw new IllegalArgumentException("only place children require an item");
        }
        if (stage == ApplyBlockPlanChildStage.BREAK
                && !"minecraft:air".equals(expectedAfter.blockId())) {
            throw new IllegalArgumentException("break child must end in air");
        }
        if (stage == ApplyBlockPlanChildStage.BREAK && supportWitness.isPresent()) {
            throw new IllegalArgumentException("break child must not have placement support");
        }
    }

    /** Backward-compatible constructor for child actions without an explicit support witness. */
    public ApplyBlockPlanChildAction(
            int stepIndex,
            String entryId,
            ApplyBlockPlanChildStage stage,
            BlockTarget target,
            BlockStateFingerprint expectedBefore,
            BlockStateFingerprint expectedAfter,
            Optional<String> requiredItemId) {
        this(stepIndex, entryId, stage, target, expectedBefore, expectedAfter,
                requiredItemId, Optional.empty());
    }

    public static ApplyBlockPlanChildAction first(int index, ApplyBlockPlanStep step) {
        Objects.requireNonNull(step, "step");
        return switch (step.operation()) {
            case VERIFY_ONLY -> throw new IllegalArgumentException("verify_only has no child action");
            case BREAK_TO_AIR -> new ApplyBlockPlanChildAction(
                    index, step.id(), ApplyBlockPlanChildStage.BREAK, step.target(),
                    step.expectedBefore(), step.expectedAfter(), Optional.empty(), Optional.empty());
            case PLACE -> new ApplyBlockPlanChildAction(
                    index, step.id(), ApplyBlockPlanChildStage.PLACE, step.target(),
                    step.expectedBefore(), step.expectedAfter(), step.requiredItemId(),
                    step.supportWitness());
            case REPLACE -> new ApplyBlockPlanChildAction(
                    index, step.id(), ApplyBlockPlanChildStage.BREAK, step.target(),
                    step.expectedBefore(), air(), Optional.empty(), Optional.empty());
        };
    }

    public static ApplyBlockPlanChildAction replacementPlacement(
            int index, ApplyBlockPlanStep step) {
        if (step.operation() != ApplyBlockPlanOperation.REPLACE) {
            throw new IllegalArgumentException("replacement placement requires replace operation");
        }
        return new ApplyBlockPlanChildAction(
                index, step.id(), ApplyBlockPlanChildStage.PLACE, step.target(), air(),
                step.expectedAfter(), step.requiredItemId(), step.supportWitness());
    }

    private static BlockStateFingerprint air() {
        return new BlockStateFingerprint("minecraft:air", Map.of());
    }
}
