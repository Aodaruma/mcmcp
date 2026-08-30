package dev.aod.mcmcp.redstone;

import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.BlockAimWitness;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal, stationary placement and interaction request for the identity slice. */
public record RedstoneIdentityRequest(
        RedstoneSpec spec,
        UUID worldSessionId,
        BlockTarget lampTarget,
        BlockTarget leverTarget,
        BlockAimWitness lampPlacementAim,
        BlockAimWitness leverPlacementAim,
        ActionBounds bounds) {
    private static final BlockStateFingerprint AIR =
            new BlockStateFingerprint("minecraft:air", Map.of());
    private static final BlockStateFingerprint LAMP_OFF =
            new BlockStateFingerprint("minecraft:redstone_lamp", Map.of("lit", "false"));
    private static final BlockStateFingerprint LEVER_OFF =
            new BlockStateFingerprint("minecraft:lever", Map.of("powered", "false"));
    private static final BlockStateFingerprint LEVER_ON =
            new BlockStateFingerprint("minecraft:lever", Map.of("powered", "true"));

    public RedstoneIdentityRequest {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(lampTarget, "lampTarget");
        Objects.requireNonNull(leverTarget, "leverTarget");
        Objects.requireNonNull(lampPlacementAim, "lampPlacementAim");
        Objects.requireNonNull(leverPlacementAim, "leverPlacementAim");
        Objects.requireNonNull(bounds, "bounds");

        BlockTarget expectedLever = switch (spec.rotationDegrees()) {
            case 0 -> offset(lampTarget, 1, 0);
            case 90 -> offset(lampTarget, 0, 1);
            case 180 -> offset(lampTarget, -1, 0);
            case 270 -> offset(lampTarget, 0, -1);
            default -> throw new IllegalArgumentException("identity rotation is unsupported");
        };
        if (!expectedLever.equals(leverTarget)
                || !bounds.contains(lampTarget)
                || !bounds.contains(leverTarget)
                || !bounds.contains(lampPlacementAim.block())
                || !bounds.contains(leverPlacementAim.block())
                || bounds.maxTravelBlocks() != 0
                || bounds.allowBreak()
                || bounds.maxDurationSeconds() > 30) {
            throw new IllegalArgumentException("identity targets are outside stationary bounds");
        }
        requireTopSupport(lampTarget, lampPlacementAim);
        requireTopSupport(leverTarget, leverPlacementAim);

        // Reuse the packet-adjacent request validation before this composite can be admitted.
        new PlaceBlockRequest(
                lampTarget, AIR, "minecraft:redstone_lamp", LAMP_OFF,
                bounds, Optional.of(lampPlacementAim));
        new PlaceBlockRequest(
                leverTarget, AIR, "minecraft:lever", LEVER_OFF,
                bounds, Optional.of(leverPlacementAim));
    }

    public PlaceBlockRequest lampPlacement() {
        return new PlaceBlockRequest(
                lampTarget, AIR, "minecraft:redstone_lamp", LAMP_OFF,
                bounds, Optional.of(lampPlacementAim));
    }

    public PlaceBlockRequest leverPlacement() {
        return new PlaceBlockRequest(
                leverTarget, AIR, "minecraft:lever", LEVER_OFF,
                bounds, Optional.of(leverPlacementAim));
    }

    public InteractBlockRequest leverOn() {
        return new InteractBlockRequest(
                leverTarget, LEVER_OFF, LEVER_ON, bounds, Optional.empty());
    }

    public InteractBlockRequest leverOff() {
        return new InteractBlockRequest(
                leverTarget, LEVER_ON, LEVER_OFF, bounds, Optional.empty());
    }

    /** Five face-neighbors other than the adjacent lamp: glass below and air elsewhere. */
    public List<BlockTarget> leverSafetyHalo() {
        return List.of(
                        offset(leverTarget, 1, 0, 0),
                        offset(leverTarget, -1, 0, 0),
                        offset(leverTarget, 0, 1, 0),
                        offset(leverTarget, 0, -1, 0),
                        offset(leverTarget, 0, 0, 1),
                        offset(leverTarget, 0, 0, -1))
                .stream()
                .filter(target -> !target.equals(lampTarget))
                .toList();
    }

    private static BlockTarget offset(BlockTarget origin, int x, int z) {
        return offset(origin, x, 0, z);
    }

    private static BlockTarget offset(BlockTarget origin, int x, int y, int z) {
        return new BlockTarget(
                origin.dimension(), origin.x() + x, origin.y() + y, origin.z() + z);
    }

    private static void requireTopSupport(BlockTarget target, BlockAimWitness aim) {
        BlockTarget support = aim.block();
        if (aim.face() != BlockAimWitness.Face.UP
                || support.x() != target.x()
                || support.y() + 1 != target.y()
                || support.z() != target.z()) {
            throw new IllegalArgumentException("identity placement requires the visible top support");
        }
    }
}
