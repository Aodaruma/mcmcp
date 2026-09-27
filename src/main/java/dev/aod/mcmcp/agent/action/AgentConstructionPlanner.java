package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.Aim;
import dev.aod.mcmcp.agent.action.AgentPlannerGeometry.AimError;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.ApproachPlan;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.KnownSurface;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.action.AgentProgramPlanner.PlanningWork;
import dev.aod.mcmcp.agent.action.AgentSurfaceEvidence.MutationSurface;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.navigation.TraversabilityEdge;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.construction.SafeConstructionBlocks;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

import static dev.aod.mcmcp.agent.action.AgentNavigationPlanner.APPROACH_TOLERANCE;
import static dev.aod.mcmcp.agent.action.AgentPlannerGeometry.MAX_BREAK_REACH_BLOCKS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.CAMERA_QUANTIZATION_RESERVE_DEGREES;

/** 建築・撤去・柱・redstoneの根拠と姿勢を検証し、配置用の共通接近点を選ぶ。 */
final class AgentConstructionPlanner {
    private AgentConstructionPlanner() {
    }

    private static final double PLACEMENT_HEADING_RESERVE_DEGREES =
            0.75D + CAMERA_QUANTIZATION_RESERVE_DEGREES;

    /** 配送された支持面とcamera範囲を検証し、配置後の解析姿勢を維持する。 */
    static List<Pose> analyzeBlockPlan(
            ActionDsl.ApplyKnownBlockPlan plan,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            PlacementStateResolver placementStates,
            PlanningWork work) {
        long placements = 0L;
        for (ActionDsl.BlockPlanEntry entry : plan.entries()) {
            ConstructionSource source = requireConstructionSource(
                    map, latestFrame, entry, placementStates);
            placements += SafeConstructionBlocks.placementCellCount(
                    source.state().block());
            if (source.surface() != null) {
                knownSurfaces.add(new KnownSurface(
                        new ActionDsl.Position(
                                source.surface().position().dimension().value(),
                                source.surface().position().x(),
                                source.surface().position().y(),
                                source.surface().position().z()),
                        ActionDsl.BlockFace.valueOf(source.surface().face().name()),
                        source.surface().block().value(),
                        null));
            }
            ActionDsl.PlacementSupport support = entry.support();
            if (support.expectedState().isPresent()) {
                ActionDsl.BlockStateSpec expected = support.expectedState().orElseThrow();
                MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                        map,
                        latestFrame,
                        input,
                        support.position(),
                        AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                                map, surfaceRevisionBarrier, support.position()),
                        expected.block(),
                        value -> value.face().name().equals(support.face().name())
                                && AgentSurfaceEvidence.exactObservedState(value, expected),
                        "Construction support requires the delivered exact face and block state");
                knownSurfaces.add(surface.surface());
                for (Pose pose : input) {
                    work.poseTransition();
                    requireConstructionAim(
                            pose, AgentPlannerGeometry.supportFaceCenter(support.position(), support.face()));
                }
            } else {
                Vec3 supportPoint = AgentPlannerGeometry.supportFaceCenter(support.position(), support.face());
                for (Pose pose : input) {
                    work.poseTransition();
                    if (!AgentPlannerGeometry.interactionPointReachable(pose, supportPoint)) {
                        throw new PlanningException(
                                Code.TARGET_UNKNOWN,
                                "Construction dependency support is outside interaction reach");
                    }
                    requireConstructionAim(pose, supportPoint);
                }
            }
        }
        AgentPlannerCosts.merge(costs, plan.id(),
                ActionDslCompiler.intrinsicKnownBlockPlanCost(
                        plan.entries().size(), placements));
        // The construction adapter restores the admitted camera pose and owns no movement.
        return input;
    }

    /** 変換後の撤去対象に対し、完全な観測stateとcamera範囲を検証する。 */
    static List<Pose> analyzeClearPlan(
            ActionDsl.ClearKnownBlockPlan plan,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            PlanningWork work) {
        for (ActionDsl.ClearBlockPlanEntry entry : plan.entries()) {
            ActionDsl.Position target = transformedTarget(
                    plan.anchor(), plan.transform(), entry.offset());
            ActionDsl.BlockStateSpec expected = transformedState(
                    plan.transform(), entry.expectedBefore());
            MutationSurface surface = AgentSurfaceEvidence.requireMutationSurface(
                    map,
                    latestFrame,
                    input,
                    target,
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(map, surfaceRevisionBarrier, target),
                    expected.block(),
                    value -> value.placementItem() != null
                            && AgentSurfaceEvidence.exactObservedState(value, expected),
                    "Construction clear requires the delivered exact target state");
            knownSurfaces.add(surface.surface());
            for (Pose pose : input) {
                work.poseTransition();
                requireConstructionAim(
                        pose, AgentPlannerGeometry.supportFaceCenter(target, surface.surface().face()));
            }
        }
        AgentPlannerCosts.merge(costs, plan.id(),
                ActionDslCompiler.intrinsicKnownBlockClearCost(plan.entries().size()));
        return input;
    }

    /** 柱の材料・支持面・足元の中心姿勢を検証し、固定の作業費用を登録する。 */
    static List<Pose> analyzePillar(
            ActionDsl.PillarUpKnown pillar,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            PlacementStateResolver placementStates,
            PlanningWork work) {
        ConstructionSource source = requirePillarSource(
                map, latestFrame, pillar, placementStates);
        try {
            KnownPillarUpRequest.requireSourceStateAndItem(
                    new BlockStateFingerprint(
                            source.state().block(), source.state().properties()),
                    source.item());
        } catch (RuntimeException rejected) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Pillar source requires one safe ordinary full block");
        }
        if (source.surface() != null) {
            knownSurfaces.add(new KnownSurface(
                    new ActionDsl.Position(
                            source.surface().position().dimension().value(),
                            source.surface().position().x(),
                            source.surface().position().y(),
                            source.surface().position().z()),
                    ActionDsl.BlockFace.valueOf(source.surface().face().name()),
                    source.surface().block().value(), null));
        }
        MutationSurface support = requirePillarSupport(
                map,
                latestFrame,
                pillar.support(),
                AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                        map, surfaceRevisionBarrier, pillar.support()),
                pillar.expectedSupport());
        knownSurfaces.add(support.surface());
        for (Pose pose : input) {
            work.poseTransition();
            requirePillarPose(pose, pillar.support());
        }
        AgentPlannerCosts.merge(costs, pillar.id(), ActionDslCompiler.intrinsicPillarUpCost());
        return input;
    }

    /** redstoneの構成に必要な支持面と照準を記録し、固定の作業費用を登録する。 */
    static List<Pose> analyzeRedstone(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            List<Pose> input,
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            Map<String, ActionDslCompiler.Cost> costs,
            Set<KnownSurface> knownSurfaces,
            Map<String, MutationAim> mutationAims) {
        var spec = new RedstoneSpec(
                redstone.components(), redstone.truthTable(), redstone.footprint(),
                redstone.rotation(),
                new RedstoneSpec.ExecutionBounds(true, redstone.timing().settleTicks()));
        ActionDsl.Position lampSupport = AgentPlannerGeometry.offset(redstone.anchor(), 0, -1, 0);
        int x = switch (redstone.rotation()) {
            case 0 -> 1;
            case 180 -> -1;
            case 90, 270 -> 0;
            default -> throw new PlanningException(
                    Code.TARGET_UNKNOWN, "Redstone rotation is outside the identity slice");
        };
        int z = switch (redstone.rotation()) {
            case 90 -> 1;
            case 270 -> -1;
            case 0, 180 -> 0;
            default -> throw new PlanningException(
                    Code.TARGET_UNKNOWN, "Redstone rotation is outside the identity slice");
        };
        ActionDsl.Position leverTarget = AgentPlannerGeometry.offset(
                redstone.anchor(), (1 + spec.wireCount()) * x, 0,
                (1 + spec.wireCount()) * z);
        ActionDsl.Position leverSupport = AgentPlannerGeometry.offset(leverTarget, 0, -1, 0);
        MutationSurface lamp = requireRedstoneSupport(
                map,
                latestFrame,
                input,
                lampSupport,
                AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                        map, surfaceRevisionBarrier, lampSupport));
        MutationSurface lever = requireRedstoneSupport(
                map,
                latestFrame,
                input,
                leverSupport,
                AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                        map, surfaceRevisionBarrier, leverSupport));
        if (!"minecraft:glass".equals(lever.surface().block())
                || spec.wireCount() == 1
                        && !"minecraft:glass".equals(lamp.surface().block())) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Redstone placement requires the fixed current visible glass UP support");
        }
        knownSurfaces.add(lamp.surface());
        knownSurfaces.add(lever.surface());
        mutationAims.put(
                redstone.id() + "/lamp",
                new MutationAim(lampSupport, ActionDsl.BlockFace.UP, lamp.point()));
        mutationAims.put(
                redstone.id() + "/lever",
                new MutationAim(leverSupport, ActionDsl.BlockFace.UP, lever.point()));
        if (spec.outputCount() == 2) {
            ActionDsl.Position secondLampSupport = AgentPlannerGeometry.offset(
                    redstone.anchor(), 2 * x, -1, 2 * z);
            MutationSurface secondLamp = requireRedstoneSupport(
                    map,
                    latestFrame,
                    input,
                    secondLampSupport,
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                            map, surfaceRevisionBarrier, secondLampSupport));
            knownSurfaces.add(secondLamp.surface());
            mutationAims.put(
                    redstone.id() + "/lamp_2",
                        new MutationAim(
                                secondLampSupport, ActionDsl.BlockFace.UP, secondLamp.point()));
        }
        if (spec.wireCount() == 1) {
            ActionDsl.Position wireSupport = AgentPlannerGeometry.offset(redstone.anchor(), x, -1, z);
            MutationSurface wire = requireRedstoneSupport(
                    map,
                    latestFrame,
                    input,
                    wireSupport,
                    AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                            map, surfaceRevisionBarrier, wireSupport));
            if (!"minecraft:glass".equals(wire.surface().block())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Redstone wire placement requires a current visible glass UP support");
            }
            knownSurfaces.add(wire.surface());
            mutationAims.put(
                    redstone.id() + "/wire",
                    new MutationAim(wireSupport, ActionDsl.BlockFace.UP, wire.point()));
        }
        AgentPlannerCosts.merge(costs, redstone.id(), ActionDslCompiler.intrinsicKnownRedstoneCost(
                redstone.timing().settleTicks(), spec.outputCount(), spec.wireCount()));
        return input;
    }

    /**
     * A centered player necessarily occludes the support directly below their feet. Keep the
     * previously delivered UP-face witness usable while the player takes the final centering step;
     * the pillar port still rechecks the complete live state immediately before jumping and use.
     */
    private static MutationSurface requirePillarSupport(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            ActionDsl.BlockStateSpec expected) {
        AgentSurfaceEvidence.requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> AgentSurfaceEvidence.matches(surface, position, expected.block()))
                .filter(surface -> surface.face() == ObservationRecord.Face.UP
                        && AgentSurfaceEvidence.exactObservedState(surface, expected)
                        && surface.rayHit() != null)
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.UP,
                                expected.block(),
                                null),
                        AgentPlannerGeometry.rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Pillar support requires a retained delivered exact UP face and block state"));
    }

    private static MutationSurface requireRedstoneSupport(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision) {
        AgentSurfaceEvidence.requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> AgentSurfaceEvidence.matches(surface, position)
                        && surface.face() == ObservationRecord.Face.UP
                        && SafePlacementSupportPolicy.allowsRegisteredBlockId(
                                surface.block().value()))
                .filter(surface -> surface.rayHit() != null
                        && poses.stream().allMatch(pose -> AgentPlannerGeometry.mutationSurfaceValid(pose, surface)))
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.UP,
                                surface.block().value(),
                                null),
                        AgentPlannerGeometry.rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Redstone placement requires a current visible inert UP support"));
    }

    private static ConstructionSource requireConstructionSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BlockPlanEntry entry,
            PlacementStateResolver placementStates) {
        if (entry.placementStateRef().isPresent()) {
            PlacementStateResolver.PlacementState remembered = placementStates
                    .resolve(entry.placementStateRef().orElseThrow())
                    .orElseThrow(() -> new PlanningException(
                            Code.TARGET_UNKNOWN,
                            "Construction placement_state_ref is unknown in this world session"));
            return new ConstructionSource(
                    new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(), remembered.state().properties()),
                    remembered.placementItem().value(),
                    null);
        }
        ActionDsl.BlockStateSpec expected = entry.sourceState().orElseThrow();
        String item = entry.item().orElseThrow();
        ObservationRecord.VisibleSurface surface = requireConstructionSource(
                map, latestFrame, expected, item);
        return new ConstructionSource(expected, item, surface);
    }

    private static ConstructionSource requirePillarSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        if (pillar.placementStateRef().isPresent()) {
            PlacementStateResolver.PlacementState remembered = placementStates
                    .resolve(pillar.placementStateRef().orElseThrow())
                    .orElseThrow(() -> new PlanningException(
                            Code.TARGET_UNKNOWN,
                            "Pillar placement_state_ref is unknown in this world session"));
            return new ConstructionSource(
                    new ActionDsl.BlockStateSpec(
                            remembered.state().block().value(), remembered.state().properties()),
                    remembered.placementItem().value(),
                    null);
        }
        ActionDsl.BlockStateSpec expected = pillar.sourceState().orElseThrow();
        String item = pillar.item().orElseThrow();
        ObservationRecord.VisibleSurface surface = requireConstructionSource(
                map, latestFrame, expected, item);
        return new ConstructionSource(expected, item, surface);
    }

    /** Legacy inline source admission retained for migration compatibility. */
    private static ObservationRecord.VisibleSurface requireConstructionSource(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BlockStateSpec expected,
            String item) {
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() <= map.worldRevision())
                .filter(surface -> AgentSurfaceEvidence.exactObservedState(surface, expected))
                .filter(surface -> surface.placementItem() != null
                        && item.equals(surface.placementItem().value()))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Construction source requires a delivered exact state and placement item"));
    }

    private record ConstructionSource(
            ActionDsl.BlockStateSpec state,
            String item,
            ObservationRecord.VisibleSurface surface) {
        private ConstructionSource {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(item, "item");
        }
    }

    private static ActionDsl.Position transformedTarget(
            ActionDsl.Position anchor,
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.Offset rawOffset) {
        ActionDsl.Offset offset = transform.apply(rawOffset);
        return new ActionDsl.Position(
                anchor.dimension(),
                Math.addExact(anchor.x(), offset.x()),
                Math.addExact(anchor.y(), offset.y()),
                Math.addExact(anchor.z(), offset.z()));
    }

    private static ActionDsl.BlockStateSpec transformedState(
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.BlockStateSpec source) {
        return transformedFullState(
                transform,
                source,
                "construction.clear.expected_before",
                "Construction clear expected_before must be one complete safe state");
    }

    private static ActionDsl.BlockStateSpec transformedFullState(
            ActionDsl.BlockPlanTransform transform,
            ActionDsl.BlockStateSpec source,
            String path,
            String failure) {
        try {
            BlockStateView state = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(source.block(), source.properties()),
                    new BlockPlan.Transform(
                            transform.rotation().degrees(), transform.mirror().wireName()),
                    path);
            return new ActionDsl.BlockStateSpec(state.block(), state.properties());
        } catch (RuntimeException rejected) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    failure);
        }
    }

    private static void requirePillarPose(Pose pose, ActionDsl.Position support) {
        double centerX = support.x() + 0.5D;
        double centerZ = support.z() + 0.5D;
        if (!pose.cell().dimension().equals(support.dimension())
                || pose.cell().x() != support.x()
                || pose.cell().y() != support.y() + 1
                || pose.cell().z() != support.z()
                || Math.abs(pose.x() - centerX) + pose.horizontalPositionError() > 0.15D
                || Math.abs(pose.z() - centerZ) + pose.horizontalPositionError() > 0.15D
                || Math.abs(pose.y() - (support.y() + 1.0D))
                        + Math.max(pose.yErrorBelow(), pose.yErrorAbove()) > 1.0e-4D) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Pillar support must be the centered block directly below the player");
        }
    }

    private static void requireConstructionAim(Pose pose, Vec3 point) {
        Aim desired = AgentPlannerGeometry.aim(pose, point);
        AimError uncertainty = AgentPlannerGeometry.aimError(pose, point, desired);
        double oneWay = AgentPlannerGeometry.withCameraQuantizationReserve(
                AgentPlannerGeometry.angularError(
                        pose.yaw(), pose.pitch(), desired.yaw(), desired.pitch())
                        + pose.orientationErrorDegrees()
                        + uncertainty.totalDegrees());
        if (oneWay > SafeConstructionBlocks.MAX_ONE_WAY_CAMERA_DEGREES) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Construction support requires a nearer admitted camera heading");
        }
    }

    /**
     * Selects one known stand cell that can serve every entry of the stationary placement plan.
     * The opaque placement identities and support witnesses are re-resolved at runtime.
     */
    static ApproachPlan requireKnownPlacementApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            ActionDsl.ApproachKnownPlacement approach,
            Optional<ObservationFrame> latestFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(approach, "approach");
        List<PlacementApproachRequirement> requirements =
                requirePlacementApproachRequirements(
                        map,
                        latestFrame,
                        approach,
                        surfaceRevisionBarrier,
                        placementStates);
        return requireKnownPlacementApproachPlan(
                map, pathfinder, startPose, requirements, null);
    }

    static List<PlacementApproachRequirement> requirePlacementApproachRequirements(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.ApproachKnownPlacement approach,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(surfaceRevisionBarrier, "surfaceRevisionBarrier");
        Objects.requireNonNull(placementStates, "placementStates");
        var requirements = new ArrayList<PlacementApproachRequirement>(
                approach.entries().size());
        for (int index = 0; index < approach.entries().size(); index++) {
            ActionDsl.BlockPlanEntry entry = approach.entries().get(index);
            if (entry.placementStateRef().isEmpty()
                    || entry.sourceState().isPresent()
                    || entry.item().isPresent()) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Placement approach entries require an opaque placement_state_ref");
            }
            ActionDsl.PlacementSupport support = entry.support();
            if (support.face() != ActionDsl.BlockFace.UP
                    || support.expectedState().isEmpty()
                    || support.dependencyEntryId().isPresent()) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Placement approach supports require current exact UP-face evidence");
            }
            ActionDsl.Position target = transformedTarget(
                    approach.anchor(), approach.transform(), entry.offset());
            if (!AgentPlannerGeometry.directlyAbove(target, support.position())) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Placement approach support must be directly below its transformed target");
            }

            ConstructionSource source = requireConstructionSource(
                    map, latestFrame, entry, placementStates);
            requireDryBottomStair(source.state());
            ActionDsl.BlockStateSpec transformed = transformedFullState(
                    approach.transform(),
                    source.state(),
                    "construction.approach.entries[" + index + "].placement_state_ref",
                    "Placement approach requires one complete transformable stair state");
            requireDryBottomStair(transformed);
            String facing = transformed.properties().get("facing");

            ActionDsl.BlockStateSpec expectedSupport = support.expectedState().orElseThrow();
            long barrier = AgentSurfaceEvidence.surfaceBarrierWorldRevision(
                    map, surfaceRevisionBarrier, support.position());
            List<Vec3> rayWitnesses = latestFrame.stream()
                    .filter(frame -> frame.dimension().value().equals(map.dimension()))
                    .flatMap(frame -> frame.records().stream())
                    .filter(ObservationRecord.VisibleSurface.class::isInstance)
                    .map(ObservationRecord.VisibleSurface.class::cast)
                    .filter(surface -> surface.worldRevision() >= barrier
                            && surface.worldRevision() <= map.worldRevision())
                    .filter(surface -> AgentSurfaceEvidence.matches(
                            surface, support.position(), expectedSupport.block()))
                    .filter(surface -> surface.face() == ObservationRecord.Face.UP
                            && AgentSurfaceEvidence.exactObservedState(surface, expectedSupport)
                            && surface.rayHit() != null)
                    .map(AgentPlannerGeometry::rayHit)
                    .sorted(java.util.Comparator
                            .comparingDouble((Vec3 point) -> point.x)
                            .thenComparingDouble(point -> point.y)
                            .thenComparingDouble(point -> point.z))
                    .toList();
            if (rayWitnesses.isEmpty()) {
                throw new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Placement approach support requires a current delivered exact UP-face ray witness");
            }
            requirements.add(new PlacementApproachRequirement(
                    support, facing, rayWitnesses));
        }
        return List.copyOf(requirements);
    }

    private static void requireDryBottomStair(ActionDsl.BlockStateSpec state) {
        if (!("minecraft:oak_stairs".equals(state.block())
                        || "minecraft:cobblestone_stairs".equals(state.block()))
                || !state.properties().keySet().equals(
                        Set.of("facing", "half", "shape", "waterlogged"))
                || !"bottom".equals(state.properties().get("half"))
                || !"false".equals(state.properties().get("waterlogged"))) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Placement approach is limited to complete dry bottom oak/cobblestone stairs");
        }
    }

    static ApproachPlan requireKnownPlacementApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            Pose startPose,
            List<PlacementApproachRequirement> requirements,
            PlanningWork work) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(pathfinder, "pathfinder");
        Objects.requireNonNull(startPose, "startPose");
        if (requirements.isEmpty()
                || !map.dimension().equals(startPose.cell().dimension())) {
            throw new PlanningException(
                    Code.NO_KNOWN_PATH,
                    "Placement approach is outside the current known map");
        }

        var cells = new java.util.TreeSet<NavCell>();
        cells.add(startPose.cell());
        for (TraversabilityEdge edge : map.edges().values()) {
            if (edge.traversable()) {
                cells.add(edge.key().from());
                cells.add(edge.key().to());
            }
        }

        ApproachPlan best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestWorstReach = Double.POSITIVE_INFINITY;
        for (NavCell candidate : cells) {
            Pose settled = startPose.at(candidate, APPROACH_TOLERANCE);
            double worstReach = 0.0D;
            boolean valid = true;
            for (PlacementApproachRequirement requirement : requirements) {
                double reach = requirement.rayWitnesses().stream()
                        .mapToDouble(witness -> AgentNavigationPlanner.approachRayReachSquared(
                                candidate, witness, startPose.eyeHeight()))
                        .min()
                        .orElseThrow();
                if (reach > MAX_BREAK_REACH_BLOCKS * MAX_BREAK_REACH_BLOCKS
                        || !placementHeadingSafe(
                                settled,
                                AgentPlannerGeometry.supportFaceCenter(
                                        requirement.support().position(),
                                        requirement.support().face()),
                                requirement.facing())) {
                    valid = false;
                    break;
                }
                worstReach = Math.max(worstReach, reach);
            }
            if (!valid) continue;

            DeterministicAStar.SearchResult result = work == null
                    ? pathfinder.findRoute(map, startPose.cell(), candidate)
                    : pathfinder.findRoute(
                            map,
                            startPose.cell(),
                            candidate,
                            work::canContinue,
                            work::routeExpansion);
            if (result.route().isEmpty()) continue;
            RoutePlan route = result.route().orElseThrow();
            boolean better = route.distanceBlocks() < bestDistance - 1.0e-9D
                    || Math.abs(route.distanceBlocks() - bestDistance) <= 1.0e-9D
                            && (worstReach < bestWorstReach - 1.0e-9D
                                    || Math.abs(worstReach - bestWorstReach) <= 1.0e-9D
                                            && (best == null
                                                    || candidate.compareTo(best.anchor()) < 0));
            if (better) {
                best = new ApproachPlan(route, candidate);
                bestDistance = route.distanceBlocks();
                bestWorstReach = worstReach;
            }
        }
        if (best == null) {
            throw new PlanningException(
                    Code.NO_KNOWN_PATH,
                    "No common known stand cell satisfies every placement state, ray reach, and heading");
        }
        return best;
    }

    private static boolean placementHeadingSafe(
            Pose settled, Vec3 supportPoint, String facing) {
        Aim nominal = AgentPlannerGeometry.aim(settled, supportPoint);
        double facingCenter = switch (facing) {
            case "south" -> 0.0D;
            case "west" -> 90.0D;
            case "north" -> 180.0D;
            case "east" -> -90.0D;
            default -> throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Placement approach stair facing is invalid");
        };
        double nominalError = Math.abs(Mth.wrapDegrees(nominal.yaw() - facingCenter));
        return nominalError
                + AgentPlannerGeometry.aimError(settled, supportPoint, nominal).yawDegrees()
                + PLACEMENT_HEADING_RESERVE_DEGREES < 45.0D;
    }

    record PlacementApproachRequirement(
            ActionDsl.PlacementSupport support,
            String facing,
            List<Vec3> rayWitnesses) {
        PlacementApproachRequirement {
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(facing, "facing");
            rayWitnesses = List.copyOf(Objects.requireNonNull(rayWitnesses, "rayWitnesses"));
            if (rayWitnesses.isEmpty()) {
                throw new IllegalArgumentException("placement approach requires a ray witness");
            }
        }
    }
}
