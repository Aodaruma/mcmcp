package dev.aodaruma.craftagent.routine;

import java.util.Objects;
import java.util.Optional;

/** One explicit desired transition. A plan may not contain two steps for the same cell. */
public record ApplyBlockPlanStep(
        String id,
        ApplyBlockPlanOperation operation,
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        BlockStateFingerprint expectedAfter,
        Optional<String> requiredItemId) {
    public ApplyBlockPlanStep {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid plan entry id");
        }
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(requiredItemId, "requiredItemId");
        requiredItemId = requiredItemId.map(ApplyBlockPlanStep::validateRegistryId);

        boolean itemRequired = operation == ApplyBlockPlanOperation.PLACE
                || operation == ApplyBlockPlanOperation.REPLACE;
        if (itemRequired != requiredItemId.isPresent()) {
            throw new IllegalArgumentException(
                    "requiredItemId is required exactly for place and replace");
        }
        boolean afterIsAir = "minecraft:air".equals(expectedAfter.blockId());
        if (operation == ApplyBlockPlanOperation.BREAK_TO_AIR && !afterIsAir) {
            throw new IllegalArgumentException("break_to_air must end in air");
        }
        if ((operation == ApplyBlockPlanOperation.PLACE
                || operation == ApplyBlockPlanOperation.REPLACE) && afterIsAir) {
            throw new IllegalArgumentException("place and replace must end in a non-air state");
        }
        if (operation == ApplyBlockPlanOperation.BREAK_TO_AIR
                && "minecraft:air".equals(expectedBefore.blockId())) {
            throw new IllegalArgumentException("break_to_air must begin with a non-air state");
        }
        if (operation == ApplyBlockPlanOperation.VERIFY_ONLY
                && !expectedBefore.equals(expectedAfter)) {
            throw new IllegalArgumentException("verify_only requires identical before and after states");
        }
        if (operation.mutating() && expectedBefore.equals(expectedAfter)) {
            throw new IllegalArgumentException("mutating plan entries must not be no-ops");
        }
        if (operation == ApplyBlockPlanOperation.REPLACE
                && "minecraft:air".equals(expectedBefore.blockId())) {
            throw new IllegalArgumentException("replace must require a non-air source state");
        }
    }

    private static String validateRegistryId(String value) {
        Objects.requireNonNull(value, "required item id");
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid required item registry id");
        }
        return value;
    }
}
