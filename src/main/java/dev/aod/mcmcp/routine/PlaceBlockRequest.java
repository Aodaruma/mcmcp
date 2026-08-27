package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;

public record PlaceBlockRequest(
        BlockTarget target,
        BlockStateFingerprint expectedBefore,
        String item,
        BlockStateFingerprint expectedAfter,
        ActionBounds bounds,
        Optional<BlockAimWitness> plannedAim) implements SemanticActionRequest {
    public static final String KIND = "place_block";

    public PlaceBlockRequest {
        BreakBlockRequest.validateBlockRequest(
                target, expectedBefore, expectedAfter, bounds, false);
        item = requireRegistryId(item, "item");
        plannedAim = Objects.requireNonNull(plannedAim, "plannedAim");
        plannedAim.ifPresent(aim -> requireAdjacentTarget(target, aim));
    }

    public PlaceBlockRequest(
            BlockTarget target,
            BlockStateFingerprint expectedBefore,
            String item,
            BlockStateFingerprint expectedAfter,
            ActionBounds bounds) {
        this(target, expectedBefore, item, expectedAfter, bounds, Optional.empty());
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String requireRegistryId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid " + name + " registry id");
        }
        return value;
    }

    private static void requireAdjacentTarget(BlockTarget target, BlockAimWitness aim) {
        int x = aim.block().x();
        int y = aim.block().y();
        int z = aim.block().z();
        switch (aim.face()) {
            case DOWN -> y--;
            case UP -> y++;
            case NORTH -> z--;
            case SOUTH -> z++;
            case WEST -> x--;
            case EAST -> x++;
        }
        if (!target.equals(new BlockTarget(aim.block().dimension(), x, y, z))) {
            throw new IllegalArgumentException("planned placement aim must face the action target");
        }
    }
}
