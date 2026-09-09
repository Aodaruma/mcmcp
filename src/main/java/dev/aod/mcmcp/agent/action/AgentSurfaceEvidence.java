package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Code;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.FrameItemAim;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.KnownSurface;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.MutationAim;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.PlanningException;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.observation.ContainerAimOcclusion;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationRecord;
import dev.aod.mcmcp.agent.observation.ObservationValues;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

import static dev.aod.mcmcp.agent.action.AgentPlannerGeometry.MAX_BREAK_EYE_ORIGIN_DRIFT;
import static dev.aod.mcmcp.agent.action.AgentPlannerGeometry.MAX_BREAK_REACH_BLOCKS;
import static dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.WAIT_WITNESS_EYE_EPSILON_BLOCKS;

/** 配送済みの観測証拠を、対象・revision・実ray witnessの条件で照合する。 */
final class AgentSurfaceEvidence {
    private AgentSurfaceEvidence() {
    }

    static MinecraftActionPrimitiveExecutor.KnownFaceTarget requireKnownFaceTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        if (!knownFacingTarget(map, latestFrame, target)) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Face target is not delivered policy evidence in this world session");
        }
        return new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                map.worldSessionId(), map.worldRevision(), target, true);
    }

    static MinecraftActionPrimitiveExecutor.KnownFaceTarget requireKnownBlockFaceTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.FaceKnownBlockFace target) {
        Objects.requireNonNull(target, "target");
        KnownSurface required = new KnownSurface(
                target.target(), target.face(), target.expectedBlock());
        if (!knownFacingSurface(map, latestFrame, required)) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Face block target requires delivered matching surface evidence");
        }
        return MinecraftActionPrimitiveExecutor.KnownFaceTarget.forBlockFaceRevisionWindow(
                map.worldSessionId(), map.worldRevision(), target.target(), target.face());
    }

    /**
     * Camera-only recovery may reuse an unexpired delivered surface identity.
     * Mutation admission remains separately fenced by {@link #knownSurface}.
     */
    static boolean knownFacingSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(required, "required");
        if (!map.dimension().equals(required.position().dimension())) return false;
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() <= map.worldRevision())
                .anyMatch(surface -> matches(surface, required));
    }

    /**
     * Camera-only facing may use a successfully delivered coordinate until that delivery expires.
     *
     * <p>The runtime supplies its delivery-filtered planner frame, so accepting an older revision
     * here does not reveal hidden world state. Facing is the recovery operation which lets the
     * observer obtain a new ray after a nearby mutation invalidated visual evidence. Mutation
     * primitives continue to use {@link #knownSurface} and its current revision barrier.</p>
     */
    static boolean knownFacingTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        if (!map.dimension().equals(target.dimension())) {
            return false;
        }
        if (map.containsCell(AgentPlannerGeometry.navCell(target))) {
            return true;
        }
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(target.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(record -> record.worldRevision() <= map.worldRevision())
                .anyMatch(record -> matches(record, target));
    }

    static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target) {
        return knownTarget(map, latestFrame, target, map.worldRevision());
    }

    static boolean knownTarget(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(target, "target");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        if (!map.dimension().equals(target.dimension())) {
            return false;
        }
        if (map.containsCell(AgentPlannerGeometry.navCell(target))) {
            return true;
        }
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(target.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(record -> record.worldRevision() >= surfaceBarrierWorldRevision
                        && record.worldRevision() <= map.worldRevision())
                .anyMatch(record -> matches(record, target));
    }

    static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target) {
        return requireKnownBreakSurface(
                map, latestFrame, target, map.worldRevision());
    }

    static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        var required = new KnownSurface(
                target.target(), target.face(), target.expectedBlock());
        if (knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).isEmpty()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face is not current matching visible-surface evidence");
        }
        return required;
    }

    static MutationAim requireKnownBreakAim(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownFace target,
            long surfaceBarrierWorldRevision) {
        KnownSurface required = requireKnownBreakSurface(
                map, latestFrame, target, surfaceBarrierWorldRevision);
        ObservationRecord.VisibleSurface surface = knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).orElseThrow();
        if (surface.rayHit() == null) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face lacks an exact visible ray witness");
        }
        return new MutationAim(target.target(), target.face(), AgentPlannerGeometry.rayHit(surface));
    }

    static KnownSurface requireKnownBreakSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownBlock target,
            long surfaceBarrierWorldRevision) {
        var required = new KnownSurface(
                target.target(), target.face(), target.expectedState().block());
        ObservationRecord.VisibleSurface surface = knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision)
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Break target face is not current matching visible-surface evidence"));
        if (!exactObservedState(surface, target.expectedState())) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target requires delivered matching complete-state evidence");
        }
        return required;
    }

    static MutationAim requireKnownBreakAim(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.BreakKnownBlock target,
            long surfaceBarrierWorldRevision) {
        KnownSurface required = requireKnownBreakSurface(
                map, latestFrame, target, surfaceBarrierWorldRevision);
        ObservationRecord.VisibleSurface surface = knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).orElseThrow();
        if (surface.rayHit() == null) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Break target face lacks an exact visible ray witness");
        }
        return new MutationAim(target.target(), target.face(), AgentPlannerGeometry.rayHit(surface));
    }

    static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return knownSurface(
                map, latestFrame, required, map.worldRevision());
    }

    static boolean knownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required,
            long surfaceBarrierWorldRevision) {
        return knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision).isPresent();
    }

    static boolean knownExactSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position target,
            ActionDsl.BlockFace face,
            ActionDsl.BlockStateSpec expected,
            long surfaceBarrierWorldRevision) {
        KnownSurface required = new KnownSurface(target, face, expected.block());
        return knownSurfaceRecord(
                map, latestFrame, required, surfaceBarrierWorldRevision)
                .filter(surface -> exactObservedState(surface, expected))
                .isPresent();
    }

    static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block) {
        return requireKnownSurface(
                map, latestFrame, position, block, map.worldRevision());
    }

    static KnownSurface requireKnownSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            ActionDsl.Position position,
            String block,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(block, "block");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position, block))
                .map(surface -> new KnownSurface(
                        position,
                        ActionDsl.BlockFace.valueOf(surface.face().name()),
                        block))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Mutation target is not current matching visible-surface evidence"));
    }

    /**
     * Authorizes one explicit crop wait from current policy-visible wheat evidence.
     * Crop maturity is deliberately not retained in the fence: normal AGE changes are
     * the state transition the bounded wait is intended to observe.
     */
    static KnownSurface requireKnownWheatWaitSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(poses, "poses");
        if (poses.isEmpty()) {
            throw new IllegalArgumentException("crop wait requires at least one current pose");
        }
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position, "minecraft:wheat"))
                .filter(surface -> surface.cropMature() != null)
                .filter(surface -> poses.stream().allMatch(pose ->
                        AgentPlannerGeometry.waitWitnessOriginMatches(pose, surface.eyeOrigin())))
                .map(surface -> new KnownSurface(
                        position,
                        ActionDsl.BlockFace.valueOf(surface.face().name()),
                        "minecraft:wheat",
                        null,
                        new Vec3(
                                surface.eyeOrigin().x(),
                                surface.eyeOrigin().y(),
                                surface.eyeOrigin().z())))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        "Crop wait target requires current visible wheat evidence"));
    }

    static MutationSurface requireMutationSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            String block,
            java.util.function.Predicate<ObservationRecord.VisibleSurface> allowed,
            String failure) {
        return requireMutationSurface(
                map, latestFrame, poses, position, surfaceBarrierWorldRevision,
                block, allowed, failure, MAX_BREAK_REACH_BLOCKS);
    }

    static MutationSurface requireMutationSurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            String block,
            java.util.function.Predicate<ObservationRecord.VisibleSurface> allowed,
            String failure,
            double maxAimDistance) {
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        List<ObservationRecord.VisibleSurface> matchingSurfaces = latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, position, block))
                .filter(allowed)
                .toList();
        if (matchingSurfaces.isEmpty()) {
            throw new PlanningException(Code.TARGET_UNKNOWN, failure);
        }
        return matchingSurfaces.stream()
                .filter(surface -> surface.rayHit() != null
                        && poses.stream().allMatch(
                                pose -> AgentPlannerGeometry.mutationSurfaceValid(pose, surface, maxAimDistance)))
                .sorted(java.util.Comparator
                        .comparingInt((ObservationRecord.VisibleSurface surface) ->
                                surface.face() == ObservationRecord.Face.UP ? 0 : 1)
                        .thenComparingDouble(surface -> AgentPlannerGeometry.distanceSquared(
                                poses.getFirst(), surface.rayHit())))
                .map(surface -> new MutationSurface(
                        new KnownSurface(
                                position,
                                ActionDsl.BlockFace.valueOf(surface.face().name()),
                                block,
                                Boolean.TRUE.equals(surface.cropMature()) ? true : null),
                        AgentPlannerGeometry.rayHit(surface)))
                .findFirst()
                .orElseThrow(() -> new PlanningException(
                        Code.TARGET_UNKNOWN,
                        failure
                                + "; matching surface evidence exists, but its ray witness "
                                + "is not valid from the current pose"));
    }

    /** Choose among delivered witnesses before reserving one exact camera path and output pose. */
    static MutationSurface requireInventorySurface(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            List<Pose> poses,
            ActionDsl.Position position,
            long surfaceBarrierWorldRevision,
            long visualBarrierWorldRevision,
            String block,
            String failure) {
        var entityBounds = latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleEntity.class::isInstance)
                .map(ObservationRecord.VisibleEntity.class::cast)
                .filter(entity -> entity.dimension().value().equals(position.dimension())
                        && entity.worldRevision() >= visualBarrierWorldRevision
                        && entity.worldRevision() <= map.worldRevision())
                .map(ObservationRecord.VisibleEntity::aabb)
                .toList();
        return requireMutationSurface(
                map, latestFrame, poses, position, surfaceBarrierWorldRevision, block,
                surface -> ContainerAimOcclusion.hasSurfaceClearance(surface)
                        && poses.stream().allMatch(pose -> {
                    Vec3 eye = new Vec3(pose.x(), pose.y() + pose.eyeHeight(), pose.z());
                    Vec3 point = AgentPlannerGeometry.rayHit(surface);
                    return entityBounds.stream().noneMatch(bounds ->
                            ContainerAimOcclusion.intersects(eye, point, bounds));
                }),
                failure + "; no delivered aim witness clear of observed entity bounds and outline edges");
    }

    private static Optional<ObservationRecord.VisibleSurface> knownSurfaceRecord(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required) {
        return knownSurfaceRecord(
                map, latestFrame, required, map.worldRevision());
    }

    static Optional<ObservationRecord.VisibleSurface> knownSurfaceRecord(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            KnownSurface required,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(latestFrame, "latestFrame");
        Objects.requireNonNull(required, "required");
        requireSurfaceBarrierWorldRevision(map, surfaceBarrierWorldRevision);
        if (!map.dimension().equals(required.position().dimension())) return Optional.empty();
        return latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleSurface.class::isInstance)
                .map(ObservationRecord.VisibleSurface.class::cast)
                .filter(surface -> surface.worldRevision() >= surfaceBarrierWorldRevision
                        && surface.worldRevision() <= map.worldRevision())
                .filter(surface -> matches(surface, required))
                .findFirst();
    }

    static boolean exactObservedState(
            ObservationRecord.VisibleSurface surface,
            ActionDsl.BlockStateSpec expected) {
        return surface.state() != null
                && expected.block().equals(surface.state().block().value())
                && expected.properties().equals(surface.state().properties());
    }

    /**
     * Fences a reconciliation-provided visual barrier to the exact traversability revision.
     */
    static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        return requireVisualBarrierWorldRevision(
                map,
                map.worldSessionId(),
                reconciliationWorldRevision,
                visualBarrierWorldRevision);
    }

    static long requireVisualBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            UUID reconciliationWorldSessionId,
            long reconciliationWorldRevision,
            long visualBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(reconciliationWorldSessionId, "reconciliationWorldSessionId");
        if (!map.worldSessionId().equals(reconciliationWorldSessionId)
                || reconciliationWorldRevision < 0L || visualBarrierWorldRevision < 0L
                || reconciliationWorldRevision != map.worldRevision()
                || visualBarrierWorldRevision > reconciliationWorldRevision) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Visual evidence revision window does not match the current map");
        }
        return visualBarrierWorldRevision;
    }

    static long requireSurfaceBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            long surfaceBarrierWorldRevision) {
        Objects.requireNonNull(map, "map");
        if (surfaceBarrierWorldRevision < 0L
                || surfaceBarrierWorldRevision > map.worldRevision()) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Surface evidence revision window does not match the current map");
        }
        return surfaceBarrierWorldRevision;
    }

    static long surfaceBarrierWorldRevision(
            KnownTraversabilitySnapshot map,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            ActionDsl.Position position) {
        Objects.requireNonNull(surfaceRevisionBarrier, "surfaceRevisionBarrier");
        Objects.requireNonNull(position, "position");
        return requireSurfaceBarrierWorldRevision(
                map, surfaceRevisionBarrier.applyAsLong(position));
    }

    static boolean matches(ObservationRecord record, ActionDsl.Position target) {
        if (record instanceof ObservationRecord.VisibleSurface surface) {
            var position = surface.position();
            return position.dimension().value().equals(target.dimension())
                    && position.x() == target.x()
                    && position.y() == target.y()
                    && position.z() == target.z();
        }
        if (record instanceof ObservationRecord.VisibleEntity entity) {
            var position = entity.position();
            return position.dimension().value().equals(target.dimension())
                    && AgentPlannerGeometry.floor(position.x()) == target.x()
                    && AgentPlannerGeometry.floor(position.y()) == target.y()
                    && AgentPlannerGeometry.floor(position.z()) == target.z();
        }
        return false;
    }

    static void requireRoutingLabel(
            KnownTraversabilitySnapshot map,
            Optional<ObservationFrame> latestFrame,
            Optional<ActionDsl.RoutingLabel> requested,
            ActionDsl.Position target,
            String expectedBlock,
            long visualBarrierWorldRevision) {
        if (requested.isEmpty()) return;
        requireVisualBarrierWorldRevision(
                map, map.worldRevision(), visualBarrierWorldRevision);
        ActionDsl.RoutingLabel witness = requested.orElseThrow();
        boolean matched = latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleEntity.class::isInstance)
                .map(ObservationRecord.VisibleEntity.class::cast)
                .filter(entity -> entity.worldRevision() >= visualBarrierWorldRevision
                        && entity.worldRevision() <= map.worldRevision())
                .filter(entity -> witness.entityRef().equals(entity.entityRef()))
                .map(ObservationRecord.VisibleEntity::containerLabel)
                .filter(Objects::nonNull)
                .anyMatch(label -> witness.item().equals(label.item().value())
                        && expectedBlock.equals(label.containerBlock().value())
                        && label.containerPosition().dimension().value()
                                .equals(target.dimension())
                        && label.containerPosition().x() == target.x()
                        && label.containerPosition().y() == target.y()
                        && label.containerPosition().z() == target.z());
        if (!matched) {
            throw new PlanningException(
                    Code.TARGET_UNKNOWN,
                    "Container routing label requires current delivered item-frame evidence");
        }
    }

    private static boolean matches(
            ObservationRecord.VisibleSurface surface, KnownSurface required) {
        var position = surface.position();
        var target = required.position();
        return position.dimension().value().equals(target.dimension())
                && position.x() == target.x()
                && position.y() == target.y()
                && position.z() == target.z()
                && surface.face().name().equals(required.face().name())
                && surface.block().value().equals(required.block())
                && (required.cropMature() == null
                        || required.cropMature().equals(surface.cropMature()))
                && (required.eyeOrigin() == null
                        || surface.eyeOrigin().dimension().value().equals(target.dimension())
                                && AgentPlannerGeometry.square(required.eyeOrigin().x - surface.eyeOrigin().x())
                                + AgentPlannerGeometry.square(required.eyeOrigin().y - surface.eyeOrigin().y())
                                + AgentPlannerGeometry.square(required.eyeOrigin().z - surface.eyeOrigin().z())
                                <= WAIT_WITNESS_EYE_EPSILON_BLOCKS
                                        * WAIT_WITNESS_EYE_EPSILON_BLOCKS);
    }

    static boolean matches(
            ObservationRecord.VisibleSurface surface,
            ActionDsl.Position target,
            String block) {
        var position = surface.position();
        return position.dimension().value().equals(target.dimension())
                && position.x() == target.x()
                && position.y() == target.y()
                && position.z() == target.z()
                && surface.block().value().equals(block);
    }

    /** Resolves only a delivered front-face witness; no guessed position or container label is used. */
    static FrameItemAim requireFrameItemAim(
            KnownTraversabilitySnapshot map, Pose pose, Optional<ObservationFrame> latestFrame,
            ActionDsl.Node node, long visualBarrierWorldRevision) {
        String ref;
        Optional<String> expected;
        Optional<String> inserted;
        if (node instanceof ActionDsl.RemoveVisibleFrameItem remove) {
            ref = remove.entityRef();
            expected = Optional.of(remove.expectedItem());
            inserted = Optional.empty();
        } else if (node instanceof ActionDsl.InsertVisibleFrameItem insert) {
            ref = insert.entityRef();
            expected = Optional.empty();
            inserted = Optional.of(insert.item());
        } else {
            throw new IllegalArgumentException("node is not a frame item operation");
        }
        requireVisualBarrierWorldRevision(map, map.worldRevision(), visualBarrierWorldRevision);
        var candidates = latestFrame.stream()
                .filter(frame -> frame.dimension().value().equals(map.dimension()))
                .flatMap(frame -> frame.records().stream())
                .filter(ObservationRecord.VisibleEntity.class::isInstance)
                .map(ObservationRecord.VisibleEntity.class::cast)
                .filter(entity -> ref.equals(entity.entityRef()))
                .filter(entity -> Set.of("minecraft:item_frame", "minecraft:glow_item_frame")
                        .contains(entity.entityType().value()))
                .toList();
        if (candidates.size() != 1) {
            throw new PlanningException(Code.TARGET_UNKNOWN,
                    "Frame witness rejected: missing_or_ambiguous_observation");
        }
        var entity = candidates.getFirst();
        long completedTick = latestFrame.orElseThrow().frameCompletedTick();
        if (entity.observedTick() > completedTick || completedTick - entity.observedTick() > 100) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Frame witness rejected: observation_expired");
        }
        if (entity.frameDisplay() == null) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Frame witness rejected: display_not_authorized");
        }
        if (entity.worldRevision() < visualBarrierWorldRevision || entity.worldRevision() > map.worldRevision()) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Frame witness rejected: visual_revision_outdated");
        }
        if (!expected.equals(Optional.ofNullable(entity.frameDisplay().item()).map(ObservationValues.ResourceId::value))) {
            throw new PlanningException(Code.TARGET_UNKNOWN, "Frame witness rejected: expected_item_changed");
        }
        var display = entity.frameDisplay();
        var point = display.aimPoint();
        var eye = new Vec3(pose.x(), pose.y() + pose.eyeHeight(), pose.z());
        var observedEye = entity.eyeOrigin();
        if (!point.dimension().value().equals(map.dimension())
                || eye.distanceTo(new Vec3(observedEye.x(), observedEye.y(), observedEye.z()))
                        > MAX_BREAK_EYE_ORIGIN_DRIFT) {
            throw new PlanningException(Code.TARGET_UNKNOWN,
                    "Frame display was not observed from the current interaction pose");
        }
        var aimPoint = new Vec3(point.x(), point.y(), point.z());
        double poseError = Math.hypot(pose.horizontalPositionError(),
                Math.max(pose.yErrorBelow(), pose.yErrorAbove()));
        if (eye.distanceTo(aimPoint) + poseError > 3.0D) {
            throw new PlanningException(Code.TARGET_UNKNOWN,
                    "Frame display is outside the bounded normal entity reach");
        }
        return new FrameItemAim(ref, entity.entityType().value(), expected, inserted,
                display.rotation(), aimPoint, entity.observedTick(), entity.worldRevision());
    }

    record MutationSurface(KnownSurface surface, Vec3 point) {
        MutationSurface {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(point, "point");
        }
    }
}
