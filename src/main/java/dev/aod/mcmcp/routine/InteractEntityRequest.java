package dev.aod.mcmcp.routine;

import java.util.Objects;

public record InteractEntityRequest(
        String entityRef,
        String expectedType,
        String hand,
        String heldItem,
        StationaryBreakGoal goal,
        ActionBounds bounds) implements SemanticActionRequest {
    public static final String KIND = "interact_entity";

    public InteractEntityRequest {
        Objects.requireNonNull(entityRef, "entityRef");
        if (!entityRef.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("entity ref must be a 24-character opaque reference");
        }
        expectedType = PlaceBlockRequest.requireRegistryId(expectedType, "expectedType");
        if (!"main_hand".equals(hand)) {
            throw new IllegalArgumentException("Phase 3 entity interaction requires main_hand");
        }
        heldItem = PlaceBlockRequest.requireRegistryId(heldItem, "heldItem");
        Objects.requireNonNull(goal, "goal");
        if (!"minecraft:cow".equals(expectedType)
                || !"minecraft:bucket".equals(heldItem)
                || !"minecraft:milk_bucket".equals(goal.itemId())) {
            throw new IllegalArgumentException(
                    "Phase 3 interact_entity only supports adult cow milking with a bucket");
        }
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.maxTravelBlocks() != 0
                || bounds.allowBreak()
                || bounds.maxDurationSeconds() > 30) {
            throw new IllegalArgumentException("interact_entity bounds are inconsistent");
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
