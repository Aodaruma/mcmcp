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
        List<BlockTarget> lampTargets,
        BlockTarget leverTarget,
        List<BlockAimWitness> lampPlacementAims,
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
        lampTargets = List.copyOf(Objects.requireNonNull(lampTargets, "lampTargets"));
        Objects.requireNonNull(leverTarget, "leverTarget");
        lampPlacementAims = List.copyOf(
                Objects.requireNonNull(lampPlacementAims, "lampPlacementAims"));
        Objects.requireNonNull(leverPlacementAim, "leverPlacementAim");
        Objects.requireNonNull(bounds, "bounds");

        if (lampTargets.size() != spec.outputCount()
                || lampPlacementAims.size() != lampTargets.size()) {
            throw new IllegalArgumentException("identity lamp targets do not match the specification");
        }
        BlockTarget firstLamp = lampTargets.getFirst();
        int x = switch (spec.rotationDegrees()) {
            case 0 -> 1;
            case 180 -> -1;
            case 90, 270 -> 0;
            default -> throw new IllegalArgumentException("identity rotation is unsupported");
        };
        int z = switch (spec.rotationDegrees()) {
            case 90 -> 1;
            case 270 -> -1;
            case 0, 180 -> 0;
            default -> throw new IllegalArgumentException("identity rotation is unsupported");
        };
        BlockTarget expectedLever = offset(firstLamp, x, z);
        if (spec.outputCount() == 2
                && !offset(firstLamp, 2 * x, 2 * z).equals(lampTargets.get(1))) {
            throw new IllegalArgumentException("fan-out second lamp target is outside its fixed layout");
        }
        if (!expectedLever.equals(leverTarget)
                || lampTargets.stream().anyMatch(target -> !bounds.contains(target))
                || !bounds.contains(leverTarget)
                || lampPlacementAims.stream().anyMatch(aim -> !bounds.contains(aim.block()))
                || !bounds.contains(leverPlacementAim.block())
                || bounds.maxTravelBlocks() != 0
                || bounds.allowBreak()
                || bounds.maxDurationSeconds() > 30) {
            throw new IllegalArgumentException("identity targets are outside stationary bounds");
        }
        for (int index = 0; index < lampTargets.size(); index++) {
            requireTopSupport(lampTargets.get(index), lampPlacementAims.get(index));
        }
        requireTopSupport(leverTarget, leverPlacementAim);

        // Reuse the packet-adjacent request validation before this composite can be admitted.
        for (int index = 0; index < lampTargets.size(); index++) {
            new PlaceBlockRequest(
                    lampTargets.get(index), AIR, "minecraft:redstone_lamp", LAMP_OFF,
                    bounds, Optional.of(lampPlacementAims.get(index)));
        }
        new PlaceBlockRequest(
                leverTarget, AIR, "minecraft:lever", LEVER_OFF,
                bounds, Optional.of(leverPlacementAim));
    }

    public RedstoneIdentityRequest(
            RedstoneSpec spec,
            UUID worldSessionId,
            BlockTarget lampTarget,
            BlockTarget leverTarget,
            BlockAimWitness lampPlacementAim,
            BlockAimWitness leverPlacementAim,
            ActionBounds bounds) {
        this(
                spec, worldSessionId, List.of(lampTarget), leverTarget,
                List.of(lampPlacementAim), leverPlacementAim, bounds);
    }

    public BlockTarget lampTarget() {
        return lampTargets.getFirst();
    }

    public BlockAimWitness lampPlacementAim() {
        return lampPlacementAims.getFirst();
    }

    public PlaceBlockRequest lampPlacement() {
        return lampPlacements().getFirst();
    }

    public List<PlaceBlockRequest> lampPlacements() {
        var placements = new java.util.ArrayList<PlaceBlockRequest>(lampTargets.size());
        for (int index = 0; index < lampTargets.size(); index++) {
            placements.add(new PlaceBlockRequest(
                    lampTargets.get(index), AIR, "minecraft:redstone_lamp", LAMP_OFF,
                    bounds, Optional.of(lampPlacementAims.get(index))));
        }
        return List.copyOf(placements);
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

    /** Lever face-neighbors other than the adjacent lamps: glass below and air elsewhere. */
    public List<BlockTarget> leverSafetyHalo() {
        return List.of(
                        offset(leverTarget, 1, 0, 0),
                        offset(leverTarget, -1, 0, 0),
                        offset(leverTarget, 0, 1, 0),
                        offset(leverTarget, 0, -1, 0),
                        offset(leverTarget, 0, 0, 1),
                        offset(leverTarget, 0, 0, -1))
                .stream()
                .filter(target -> !lampTargets.contains(target))
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
