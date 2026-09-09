package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.observation.BlockPlan;
import dev.aod.mcmcp.observation.BlockPlanStateTransformer;
import dev.aod.mcmcp.observation.BlockStateView;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.ApplyBlockPlanOperation;
import dev.aod.mcmcp.routine.ApplyBlockPlanRequest;
import dev.aod.mcmcp.routine.ApplyBlockPlanStep;
import dev.aod.mcmcp.routine.BlockAimWitness;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.KnownConstructionRequest;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.PlacementSupportWitness;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 配送・計画済みの証拠から建築、支柱、redstoneの実行要求を構築する。 */
final class ConstructionRequests {
    private ConstructionRequests() {}

    static KnownPillarUpRequest pillarUpRequest(ActionDsl.PillarUpKnown pillar) {
        return pillarUpRequest(pillar, PlacementStateResolver.none());
    }

    static KnownPillarUpRequest pillarUpRequest(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(pillar, "pillar");
        Objects.requireNonNull(placementStates, "placementStates");
        PillarSource source = resolvePillarSource(pillar, placementStates)
                .orElseThrow(() -> new IllegalArgumentException(
                        "pillar placement_state_ref is unknown"));
        return new KnownPillarUpRequest(
                new BlockTarget(
                        pillar.support().dimension(),
                        pillar.support().x(),
                        pillar.support().y(),
                        pillar.support().z()),
                new BlockStateFingerprint(
                        pillar.expectedSupport().block(),
                        pillar.expectedSupport().properties()),
                new BlockStateFingerprint(
                        source.state().block(),
                        source.state().properties()),
                source.item());
    }

    static Optional<PillarSource> resolvePillarSource(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        if (pillar.placementStateRef().isPresent()) {
            return placementStates.resolve(pillar.placementStateRef().orElseThrow())
                    .map(remembered -> new PillarSource(
                            new ActionDsl.BlockStateSpec(
                                    remembered.state().block().value(),
                                    remembered.state().properties()),
                            remembered.placementItem().value()));
        }
        return Optional.of(new PillarSource(
                pillar.sourceState().orElseThrow(), pillar.item().orElseThrow()));
    }

    record PillarSource(ActionDsl.BlockStateSpec state, String item) {
        PillarSource {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(item, "item");
        }
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ApplyKnownBlockPlan plan) {
        return constructionRequest(plan, PlacementStateResolver.none());
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ApplyKnownBlockPlan plan,
            PlacementStateResolver placementStates) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(placementStates, "placementStates");
        var transform = new BlockPlan.Transform(
                plan.transform().rotation().degrees(),
                plan.transform().mirror().wireName());
        var entries = new ArrayList<ApplyBlockPlanStep>(plan.entries().size());
        var prior = new LinkedHashMap<String, ApplyBlockPlanStep>();
        BlockTarget minimum = null;
        BlockTarget maximum = null;
        for (ActionDsl.BlockPlanEntry entry : plan.entries()) {
            ActionDsl.BlockStateSpec sourceState;
            String item;
            if (entry.placementStateRef().isPresent()) {
                PlacementStateResolver.PlacementState remembered = placementStates
                        .resolve(entry.placementStateRef().orElseThrow())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "construction placement_state_ref is unknown"));
                sourceState = new ActionDsl.BlockStateSpec(
                        remembered.state().block().value(), remembered.state().properties());
                item = remembered.placementItem().value();
            } else {
                sourceState = entry.sourceState().orElseThrow();
                item = entry.item().orElseThrow();
            }
            ActionDsl.Offset offset = plan.transform().apply(entry.offset());
            var target = new BlockTarget(
                    plan.anchor().dimension(),
                    Math.addExact(plan.anchor().x(), offset.x()),
                    Math.addExact(plan.anchor().y(), offset.y()),
                    Math.addExact(plan.anchor().z(), offset.z()));
            BlockStateView transformed = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(
                            sourceState.block(), sourceState.properties()),
                    transform,
                    "construction.entry.source_state");
            var expectedAfter = new BlockStateFingerprint(
                    transformed.block(), transformed.properties());
            ActionDsl.PlacementSupport support = entry.support();
            var supportTarget = new BlockTarget(
                    support.position().dimension(),
                    support.position().x(),
                    support.position().y(),
                    support.position().z());
            String face = support.face().name().toLowerCase(Locale.ROOT);
            PlacementSupportWitness witness;
            if (support.expectedState().isPresent()) {
                ActionDsl.BlockStateSpec state = support.expectedState().orElseThrow();
                witness = PlacementSupportWitness.visible(
                        supportTarget,
                        face,
                        new BlockStateFingerprint(state.block(), state.properties()));
            } else {
                String dependencyId = support.dependencyEntryId().orElseThrow();
                ApplyBlockPlanStep dependency = prior.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "construction dependency is not an earlier entry");
                }
                witness = PlacementSupportWitness.confirmedDependency(
                        supportTarget,
                        face,
                        dependency.expectedAfter(),
                        dependencyId);
            }
            var step = new ApplyBlockPlanStep(
                    entry.id(),
                    ApplyBlockPlanOperation.PLACE,
                    target,
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    expectedAfter,
                    Optional.of(item),
                    Optional.of(witness));
            entries.add(step);
            prior.put(entry.id(), step);
            minimum = minimum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.min(minimum.x(), target.x()),
                    Math.min(minimum.y(), target.y()),
                    Math.min(minimum.z(), target.z()));
            maximum = maximum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.max(maximum.x(), target.x()),
                    Math.max(maximum.y(), target.y()),
                    Math.max(maximum.z(), target.z()));
            if (MinecraftApplyBlockPlanPort.supportedDoorPlacement(expectedAfter)) {
                maximum = new BlockTarget(
                        target.dimension(), maximum.x(),
                        Math.max(maximum.y(), Math.addExact(target.y(), 1)), maximum.z());
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("construction plan is empty");
        }
        int maxDurationSeconds = Math.multiplyExact(entries.size(), 15);
        return new KnownConstructionRequest(
                "construction",
                entries,
                new ActionBounds(
                        plan.anchor().dimension(),
                        Objects.requireNonNull(minimum, "minimum"),
                        Objects.requireNonNull(maximum, "maximum"),
                        0,
                        maxDurationSeconds,
                        false));
    }

    static KnownConstructionRequest constructionRequest(
            ActionDsl.ClearKnownBlockPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var transform = new BlockPlan.Transform(
                plan.transform().rotation().degrees(),
                plan.transform().mirror().wireName());
        var entries = new ArrayList<ApplyBlockPlanStep>(plan.entries().size());
        BlockTarget minimum = null;
        BlockTarget maximum = null;
        for (ActionDsl.ClearBlockPlanEntry entry : plan.entries()) {
            ActionDsl.Offset offset = plan.transform().apply(entry.offset());
            var target = new BlockTarget(
                    plan.anchor().dimension(),
                    Math.addExact(plan.anchor().x(), offset.x()),
                    Math.addExact(plan.anchor().y(), offset.y()),
                    Math.addExact(plan.anchor().z(), offset.z()));
            BlockStateView transformed = BlockPlanStateTransformer.transformFull(
                    new BlockStateView(
                            entry.expectedBefore().block(),
                            entry.expectedBefore().properties()),
                    transform,
                    "construction.clear.expected_before");
            entries.add(new ApplyBlockPlanStep(
                    entry.id(),
                    ApplyBlockPlanOperation.BREAK_TO_AIR,
                    target,
                    new BlockStateFingerprint(
                            transformed.block(), transformed.properties()),
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    Optional.empty(),
                    Optional.empty()));
            minimum = minimum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.min(minimum.x(), target.x()),
                    Math.min(minimum.y(), target.y()),
                    Math.min(minimum.z(), target.z()));
            maximum = maximum == null ? target : new BlockTarget(
                    target.dimension(),
                    Math.max(maximum.x(), target.x()),
                    Math.max(maximum.y(), target.y()),
                    Math.max(maximum.z(), target.z()));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("construction clear plan is empty");
        }
        return new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "construction-clear",
                1,
                1,
                entries,
                new ActionBounds(
                        plan.anchor().dimension(),
                        Objects.requireNonNull(minimum, "minimum"),
                        Objects.requireNonNull(maximum, "maximum"),
                        0,
                        Math.multiplyExact(entries.size(), 15),
                        true),
                ApplyBlockPlanRequest.BreakSafety.SAFE_CONSTRUCTION_BLOCK));
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            AgentPrimitivePlanner.MutationAim lampAim,
            AgentPrimitivePlanner.MutationAim leverAim) {
        return redstoneIdentityRequest(
                redstone, worldSessionId, List.of(lampAim), leverAim, Optional.empty());
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            List<AgentPrimitivePlanner.MutationAim> lampAims,
            AgentPrimitivePlanner.MutationAim leverAim) {
        return redstoneIdentityRequest(
                redstone, worldSessionId, lampAims, leverAim, Optional.empty());
    }

    static RedstoneIdentityRequest redstoneIdentityRequest(
            ActionDsl.ApplyKnownRedstoneSpec redstone,
            UUID worldSessionId,
            List<AgentPrimitivePlanner.MutationAim> lampAims,
            AgentPrimitivePlanner.MutationAim leverAim,
            Optional<AgentPrimitivePlanner.MutationAim> wireAim) {
        Objects.requireNonNull(redstone, "redstone");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        lampAims = List.copyOf(Objects.requireNonNull(lampAims, "lampAims"));
        Objects.requireNonNull(leverAim, "leverAim");
        Objects.requireNonNull(wireAim, "wireAim");
        var spec = new RedstoneSpec(
                redstone.components(),
                redstone.truthTable(),
                redstone.footprint(),
                redstone.rotation(),
                new RedstoneSpec.ExecutionBounds(
                        true, redstone.timing().settleTicks()));
        ActionDsl.Position anchor = redstone.anchor();
        var firstLampTarget = new BlockTarget(
                anchor.dimension(), anchor.x(), anchor.y(), anchor.z());
        int x = switch (redstone.rotation()) {
            case 0 -> 1;
            case 180 -> -1;
            case 90, 270 -> 0;
            default -> throw new IllegalArgumentException("unsupported redstone rotation");
        };
        int z = switch (redstone.rotation()) {
            case 90 -> 1;
            case 270 -> -1;
            case 0, 180 -> 0;
            default -> throw new IllegalArgumentException("unsupported redstone rotation");
        };
        var leverTarget = new BlockTarget(
                anchor.dimension(), anchor.x() + (1 + spec.wireCount()) * x, anchor.y(),
                anchor.z() + (1 + spec.wireCount()) * z);
        var lampTargets = new ArrayList<BlockTarget>();
        lampTargets.add(firstLampTarget);
        if (spec.outputCount() == 2) {
            lampTargets.add(new BlockTarget(
                    anchor.dimension(), anchor.x() + 2 * x, anchor.y(), anchor.z() + 2 * z));
        }
        Optional<BlockTarget> wireTarget = spec.wireCount() == 1
                ? Optional.of(new BlockTarget(
                        anchor.dimension(), anchor.x() + x, anchor.y(), anchor.z() + z))
                : Optional.empty();
        if (wireAim.isPresent() != wireTarget.isPresent()) {
            throw new IllegalArgumentException("redstone wire aim does not match the specification");
        }
        if (lampAims.size() != lampTargets.size()) {
            throw new IllegalArgumentException("redstone lamp aims do not match the specification");
        }
        var targets = new ArrayList<>(lampTargets);
        targets.add(leverTarget);
        wireTarget.ifPresent(targets::add);
        var minimum = new BlockTarget(
                anchor.dimension(),
                targets.stream().mapToInt(BlockTarget::x).min().orElseThrow(),
                anchor.y() - 1,
                targets.stream().mapToInt(BlockTarget::z).min().orElseThrow());
        var maximum = new BlockTarget(
                anchor.dimension(),
                targets.stream().mapToInt(BlockTarget::x).max().orElseThrow(),
                anchor.y(),
                targets.stream().mapToInt(BlockTarget::z).max().orElseThrow());
        var bounds = new ActionBounds(
                anchor.dimension(), minimum, maximum, 0, 30, false);
        return new RedstoneIdentityRequest(
                spec,
                worldSessionId,
                lampTargets,
                leverTarget,
                lampAims.stream().map(ConstructionRequests::blockAimWitness).toList(),
                blockAimWitness(leverAim),
                wireAim.map(ConstructionRequests::blockAimWitness),
                bounds);
    }

    static SemanticActionRequest blockMutationRequest(
            ActionDsl.Node node, AgentPrimitivePlanner.MutationAim plannedAim) {
        Objects.requireNonNull(plannedAim, "plannedAim");
        ActionDsl.Position position = switch (node) {
            case ActionDsl.TillKnownBlock value -> value.target();
            case ActionDsl.PlantKnownWheat value -> value.target();
            case ActionDsl.HarvestKnownWheat value -> value.target();
            case ActionDsl.OpenKnownFenceGate value -> value.target();
            case ActionDsl.OpenKnownPassage value -> value.target();
            default -> throw new IllegalArgumentException("node is not a known block mutation");
        };
        var target = new BlockTarget(
                position.dimension(), position.x(), position.y(), position.z());
        boolean breaking = node instanceof ActionDsl.HarvestKnownWheat;
        var bounds = new ActionBounds(
                position.dimension(), target, target, 0, 5, breaking);
        var aim = Optional.of(blockAimWitness(plannedAim));
        return switch (node) {
            case ActionDsl.TillKnownBlock till -> new UseItemOnBlockRequest(
                    target,
                    new BlockStateFingerprint(till.expectedBlock(), Map.of()),
                    till.hoeItem(),
                    new BlockStateFingerprint("minecraft:farmland", Map.of("moisture", "0")),
                    bounds,
                    aim);
            case ActionDsl.PlantKnownWheat plant -> new PlaceBlockRequest(
                    target,
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    plant.seedItem(),
                    new BlockStateFingerprint("minecraft:wheat", Map.of("age", "0")),
                    bounds,
                    aim);
            case ActionDsl.HarvestKnownWheat ignored -> new BreakBlockRequest(
                    target,
                    new BlockStateFingerprint("minecraft:wheat", Map.of("age", "7")),
                    new BlockStateFingerprint("minecraft:air", Map.of()),
                    bounds,
                    aim);
            case ActionDsl.OpenKnownFenceGate ignored -> new InteractBlockRequest(
                    target,
                    new BlockStateFingerprint(
                            "minecraft:oak_fence_gate", Map.of("open", "false")),
                    new BlockStateFingerprint(
                            "minecraft:oak_fence_gate", Map.of("open", "true")),
                    bounds,
                    aim);
            case ActionDsl.OpenKnownPassage passage -> new InteractBlockRequest(
                    target,
                    new BlockStateFingerprint(passage.expectedBlock(), Map.of("open", "false")),
                    new BlockStateFingerprint(passage.expectedBlock(), Map.of("open", "true")),
                    bounds,
                    aim);
            default -> throw new IllegalArgumentException("node is not a known block mutation");
        };
    }

    static BlockAimWitness blockAimWitness(AgentPrimitivePlanner.MutationAim aim) {
        var block = aim.block();
        return new BlockAimWitness(
                new BlockTarget(block.dimension(), block.x(), block.y(), block.z()),
                BlockAimWitness.Face.valueOf(aim.face().name()),
                aim.point().x,
                aim.point().y,
                aim.point().z);
    }
}
