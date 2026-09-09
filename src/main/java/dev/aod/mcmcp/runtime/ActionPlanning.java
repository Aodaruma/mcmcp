package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.ActionProgramCursor;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.agent.dsl.PolicySnapshot;
import dev.aod.mcmcp.agent.navigation.DeterministicAStar;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.PlacementStateResolver;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.runtime.ConstructionRequests.PillarSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.minecraft.util.Mth;

/** Action構造の静的検査、初期primitiveと有限コストの算出。 */
final class ActionPlanning {
    private ActionPlanning() {}

    static String frameItemTargetRef(ActionDsl.Node primitive) {
        if (primitive instanceof ActionDsl.RemoveVisibleFrameItem remove) return remove.entityRef();
        if (primitive instanceof ActionDsl.InsertVisibleFrameItem insert) return insert.entityRef();
        return null;
    }

    static AgentPrimitivePlanner.Analysis emptyPrimitiveAnalysis() {
        return new AgentPrimitivePlanner.Analysis(
                Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
    }

    static Optional<ActionDsl.Node> firstPrimitive(
            ActionDsl.Program program, PolicySnapshot snapshot) {
        return Optional.ofNullable(new ActionProgramCursor(program).next(snapshot).primitive());
    }

    static boolean containsRecipeReference(ActionDsl.Program program) {
        return program.body().stream().anyMatch(ActionPlanning::containsRecipeReference);
    }

    static boolean containsRecipeReference(ActionDsl.Node node) {
        if (node instanceof ActionDsl.CraftKnownRecipe
                || node instanceof ActionDsl.SmeltKnownRecipe) {
            return true;
        }
        if (node instanceof ActionDsl.If conditional) {
            return conditional.thenBranch().stream().anyMatch(ActionPlanning::containsRecipeReference)
                    || conditional.elseBranch().stream()
                            .anyMatch(ActionPlanning::containsRecipeReference);
        }
        return node instanceof ActionDsl.Repeat repeat
                && repeat.body().stream().anyMatch(ActionPlanning::containsRecipeReference);
    }

    static boolean requiresWorldPlanning(ActionDsl.Node node) {
        return !(node instanceof ActionDsl.WaitTicks
                || node instanceof ActionDsl.OperateKnownMenu
                || node instanceof ActionDsl.ReelKnownFishingSession
                || node instanceof ActionDsl.OperateKillZone
                || node instanceof ActionDsl.HoldBoundedInputs
                || node instanceof ActionDsl.WaitUntil wait
                        && wait.condition() instanceof ActionDsl.SoundClueCondition);
    }

    static AgentPrimitivePlanner.ApproachPlan requireRuntimeApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            AgentPrimitivePlanner.Pose startPose,
            ActionDsl.ApproachKnownSurface approach,
            Optional<ObservationFrame> planningFrame,
            long surfaceBarrierWorldRevision) {
        AgentPrimitivePlanner.requireKnownSurface(
                map,
                planningFrame,
                approach.target(),
                approach.expectedBlock(),
                surfaceBarrierWorldRevision);
        return AgentPrimitivePlanner.requireApproachPlan(
                map,
                pathfinder,
                startPose,
                approach.target(),
                approach.expectedBlock(),
                planningFrame,
                surfaceBarrierWorldRevision);
    }

    static AgentPrimitivePlanner.ApproachPlan requireRuntimeKnownPlacementApproachPlan(
            KnownTraversabilitySnapshot map,
            DeterministicAStar pathfinder,
            AgentPrimitivePlanner.Pose startPose,
            ActionDsl.ApproachKnownPlacement approach,
            Optional<ObservationFrame> planningFrame,
            ToLongFunction<ActionDsl.Position> surfaceRevisionBarrier,
            PlacementStateResolver placementStates) {
        return AgentPrimitivePlanner.requireKnownPlacementApproachPlan(
                map,
                pathfinder,
                startPose,
                approach,
                planningFrame,
                surfaceRevisionBarrier,
                placementStates);
    }

    static boolean actionAdmissionRequiresLocalSafety(ActionDsl.Program program) {
        Objects.requireNonNull(program, "program");
        return program.body().size() != 1
                || !(program.body().getFirst() instanceof ActionDsl.OperateKnownMenu);
    }

    static Optional<ActionDslCompiler.Cost> structuralPrimitiveCost(ActionDsl.Node node) {
        if (ActionEvidence.isFrameItemPrimitive(node)) {
            return Optional.of(new ActionDslCompiler.Cost(
                    ActionDslCompiler.FRAME_ITEM_DURATION_MILLIS,
                    ActionDslCompiler.FRAME_ITEM_TICKS, 0.0D, 360.0D, 1L, 0L, 0L));
        }
        if (node instanceof ActionDsl.HoldBoundedInputs hold) {
            return Optional.of(ActionDslCompiler.intrinsicBoundedInputCost(hold));
        }
        long durationMillis = node instanceof ActionDsl.CraftKnownRecipe
                ? ActionDslCompiler.KNOWN_CRAFTING_DURATION_MILLIS
                : node instanceof ActionDsl.TakeKnownContainerStack take
                        ? ActionDslCompiler.knownContainerTransferTicks(take.maxStacks()) * 50L
                : node instanceof ActionDsl.StoreKnownContainerStack store
                        ? ActionDslCompiler.knownContainerTransferTicks(store.maxStacks()) * 50L
                : node instanceof ActionDsl.SmeltKnownRecipe smelt
                        ? ActionDslCompiler.knownSmeltingDurationMillis(smelt.maxSmelts())
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_DURATION_MILLIS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_DURATION_MILLIS
                : node instanceof ActionDsl.CastKnownFishingRod
                        ? ActionDslCompiler.KNOWN_FISHING_DURATION_MILLIS : 0L;
        long ticks = node instanceof ActionDsl.CraftKnownRecipe
                ? ActionDslCompiler.KNOWN_CRAFTING_TICKS
                : node instanceof ActionDsl.TakeKnownContainerStack take
                        ? ActionDslCompiler.knownContainerTransferTicks(take.maxStacks())
                : node instanceof ActionDsl.StoreKnownContainerStack store
                        ? ActionDslCompiler.knownContainerTransferTicks(store.maxStacks())
                : node instanceof ActionDsl.SmeltKnownRecipe smelt
                        ? ActionDslCompiler.knownSmeltingTicks(smelt.maxSmelts())
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_TICKS
                : node instanceof ActionDsl.CastKnownFishingRod
                        ? ActionDslCompiler.KNOWN_FISHING_TICKS : 0L;
        long interactions = node instanceof ActionDsl.TillKnownBlock
                        || node instanceof ActionDsl.OpenKnownFenceGate
                        || node instanceof ActionDsl.OpenKnownPassage
                        || node instanceof ActionDsl.InspectKnownContainer
                ? 1L
                : node instanceof ActionDsl.TillKnownBatch batch
                        ? batch.targets().size()
                : node instanceof ActionDsl.TakeKnownContainerStack take
                        ? ActionDslCompiler.knownContainerTransferInteractions(take.maxStacks())
                : node instanceof ActionDsl.StoreKnownContainerStack store
                        ? ActionDslCompiler.knownContainerTransferInteractions(store.maxStacks())
                : node instanceof ActionDsl.CraftKnownRecipe craft
                        ? ActionDslCompiler.knownCraftInteractions(craft.maxCrafts())
                : node instanceof ActionDsl.SmeltKnownRecipe
                        ? ActionDslCompiler.KNOWN_SMELTING_INTERACTIONS
                : node instanceof ActionDsl.OperateKnownMenu
                        ? ActionDslCompiler.KNOWN_MENU_OPERATION_INTERACTIONS
                : node instanceof ActionDsl.BrewKnownPotionBatch
                        ? ActionDslCompiler.KNOWN_BREWING_INTERACTIONS
                : node instanceof ActionDsl.CastKnownFishingRod ? 2L : 0L;
        if (node instanceof ActionDsl.OperateKillZone operation) {
            return Optional.of(ActionDslCompiler.intrinsicKillZoneCost(operation));
        }
        if (node instanceof ActionDsl.OperateKnownCobblestoneGenerator operation) {
            return Optional.of(ActionDslCompiler.intrinsicCobblestoneGeneratorCost(operation));
        }
        long breaks = node instanceof ActionDsl.BreakKnownFace
                        || node instanceof ActionDsl.BreakKnownBlock
                        || node instanceof ActionDsl.HarvestKnownWheat
                ? 1L : 0L;
        if (node instanceof ActionDsl.HarvestKnownWheatBatch batch) {
            breaks = batch.targets().size();
        }
        long placements = node instanceof ActionDsl.PlantKnownWheat ? 1L : 0L;
        if (node instanceof ActionDsl.PlantKnownWheatBatch batch) {
            placements = batch.targets().size();
        }
        return Optional.of(new ActionDslCompiler.Cost(
                durationMillis, ticks, 0.0D, 0.0D, interactions, breaks, placements));
    }

    static ActionDslCompiler.Cost pillarAdmissionCost(
            ActionDsl.PillarUpKnown pillar,
            PlacementStateResolver placementStates) {
        Optional<PillarSource> source = ConstructionRequests.resolvePillarSource(pillar, placementStates);
        source.ifPresent(value -> KnownPillarUpRequest.requireSourceStateAndItem(
                new BlockStateFingerprint(
                        value.state().block(), value.state().properties()),
                value.item()));
        // The footprint is fixed at one. Unknown/evicted refs need no identity guess for cost;
        // the admission planner resolves them separately and reports TARGET_UNKNOWN.
        return ActionDslCompiler.intrinsicPillarUpCost();
    }

    static AgentPrimitivePlanner.Pose playerPose(
            net.minecraft.client.player.LocalPlayer player, String dimension) {
        Objects.requireNonNull(player, "player");
        return new AgentPrimitivePlanner.Pose(
                playerCell(player, dimension),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getEyeY() - player.getY(),
                player.getYRot(),
                player.getXRot());
    }

    static NavCell playerCell(
            net.minecraft.client.player.LocalPlayer player, String dimension) {
        return new NavCell(
                dimension,
                Mth.floor(player.getX()),
                Mth.floor(player.getY()),
                Mth.floor(player.getZ()));
    }
}
