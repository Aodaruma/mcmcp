package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.action.KnownBrewingAttempt;
import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.agent.action.KnownPillarUpAttempt;
import dev.aod.mcmcp.agent.action.KnownRedstoneIdentityAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.observation.BlockPosition;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.redstone.RedstoneIdentityRequest;
import dev.aod.mcmcp.redstone.RedstoneSpec;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownBrewingRequest;
import dev.aod.mcmcp.routine.KnownConstructionRequest;
import dev.aod.mcmcp.routine.KnownPillarUpRequest;
import dev.aod.mcmcp.routine.MinecraftApplyBlockPlanPort;
import dev.aod.mcmcp.routine.MinecraftKnownBrewingPort;
import dev.aod.mcmcp.routine.MinecraftKnownFurnacePort;
import dev.aod.mcmcp.routine.MinecraftKnownMenuPort;
import dev.aod.mcmcp.routine.MinecraftPhaseFiveInventoryPort;
import dev.aod.mcmcp.routine.MinecraftPillarUpPort;
import dev.aod.mcmcp.routine.MinecraftSemanticActionPort;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** 一つのActionが所有するmenu・建築attemptと、効果を回収するcleanup境界。 */
final class MenuPrimitiveExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final MinecraftKnownFurnacePort knownFurnacePort;
    private final MinecraftKnownMenuPort knownMenuPort;
    private final MinecraftPhaseFiveInventoryPort phaseFiveInventoryPort;
    private final MinecraftKnownBrewingPort knownBrewingPort;
    private final MinecraftApplyBlockPlanPort applyBlockPlanPort;
    private final MinecraftPillarUpPort pillarUpPort;
    private final MinecraftSemanticActionPort semanticActionPort;
    private final MinecraftObservationService observations;
    private final DeliveredPolicyEvidenceStore deliveredEvidence;
    private KnownContainerAttempt containerAttempt;
    private boolean containerReleaseFaultLogged;
    private KnownBrewingAttempt brewingAttempt;
    private KnownConstructionAttempt constructionAttempt;
    private KnownPillarUpAttempt pillarUpAttempt;
    private KnownRedstoneIdentityAttempt redstoneAttempt;

    MenuPrimitiveExecution(UUID actionId, AgentActionStore agentActions,
            MinecraftKnownFurnacePort knownFurnacePort, MinecraftKnownMenuPort knownMenuPort,
            MinecraftPhaseFiveInventoryPort phaseFiveInventoryPort,
            MinecraftKnownBrewingPort knownBrewingPort, MinecraftApplyBlockPlanPort applyBlockPlanPort,
            MinecraftPillarUpPort pillarUpPort, MinecraftSemanticActionPort semanticActionPort,
            MinecraftObservationService observations, DeliveredPolicyEvidenceStore deliveredEvidence) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.knownFurnacePort = knownFurnacePort;
        this.knownMenuPort = knownMenuPort;
        this.phaseFiveInventoryPort = phaseFiveInventoryPort;
        this.knownBrewingPort = knownBrewingPort;
        this.applyBlockPlanPort = applyBlockPlanPort;
        this.pillarUpPort = pillarUpPort;
        this.semanticActionPort = semanticActionPort;
        this.observations = observations;
        this.deliveredEvidence = deliveredEvidence;
    }

    boolean releaseProgressing() {
        return containerAttempt != null
                && containerAttempt.releaseStatus() == KnownContainerAttempt.ReleaseStatus.PROGRESSING
                || brewingAttempt != null
                && brewingAttempt.releaseStatus() == KnownBrewingAttempt.ReleaseStatus.PROGRESSING;
    }

    boolean close(ActionDsl.Node primitive, long worldRevision) {
        boolean closed = true;
        if (containerAttempt != null) {
            KnownContainerAttempt container = containerAttempt;
            try {
                container.close();
                containerAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (container.releaseStatus()
                        != KnownContainerAttempt.ReleaseStatus.PROGRESSING
                        && !containerReleaseFaultLogged) {
                    containerReleaseFaultLogged = true;
                    McmcpMod.LOGGER.error("MCMCP known-container release failed", failure);
                }
            } finally {
                try {
                    int releasedInteractions = container.drainReleaseInteractionDelta();
                    for (int count = 0; count < releasedInteractions; count++) {
                        agentActions.recordInteraction(actionId);
                    }
                    recordContainerEffects(
                            actionId,
                            primitive,
                            container.drainEffectDeltas(), worldRevision);
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-container release usage capture failed", failure);
                }
            }
        }
        if (brewingAttempt != null) {
            KnownBrewingAttempt brewing = brewingAttempt;
            try {
                brewing.close();
                brewingAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                if (brewing.releaseStatus() != KnownBrewingAttempt.ReleaseStatus.PROGRESSING) {
                    McmcpMod.LOGGER.error("MCMCP known-brewing release failed", failure);
                }
            } finally {
                try {
                    int releasedInteractions = brewing.drainReleaseInteractionDelta();
                    for (int count = 0; count < releasedInteractions; count++) {
                        agentActions.recordInteraction(actionId);
                    }
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-brewing release usage capture failed", failure);
                }
            }
        }
        if (constructionAttempt != null) {
            KnownConstructionAttempt construction = constructionAttempt;
            try {
                construction.close();
                constructionAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-construction release failed", failure);
            } finally {
                try {
                    recordConstructionEffects(
                            actionId,
                            construction.drainEffectDeltas(), worldRevision);
                } catch (RuntimeException | LinkageError failure) {
                    closed = false;
                    McmcpMod.LOGGER.error(
                            "MCMCP known-construction effect capture failed", failure);
                }
            }
        }
        if (pillarUpAttempt != null) {
            try {
                pillarUpAttempt.close();
                pillarUpAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-pillar release failed", failure);
            }
        }
        if (redstoneAttempt != null) {
            try {
                redstoneAttempt.close();
                redstoneAttempt = null;
            } catch (RuntimeException | LinkageError failure) {
                closed = false;
                McmcpMod.LOGGER.error("MCMCP known-redstone release failed", failure);
            }
        }
        return closed;
    }

    PrimitiveOutcome tickAgentContainer(
            Minecraft minecraft, WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive, Map<String, AgentPrimitivePlanner.MutationAim> mutationAims,
            long worldRevision) {
        if (containerAttempt == null) {
            PhaseFiveRequest request = InventoryRequests.containerRequest(
                    minecraft,
                    session,
                    primitive,
                    mutationAims.get(primitive.id()));
            boolean smelting = primitive instanceof ActionDsl.SmeltKnownRecipe;
            boolean knownMenu = primitive instanceof ActionDsl.OperateKnownMenu;
            long deadline = Math.addExact(
                    session.clientTick(),
                    smelting
                            ? ActionDslCompiler.knownSmeltingTicks(
                                    ((ActionDsl.SmeltKnownRecipe) primitive)
                                            .maxSmelts())
                            : knownMenu
                                    ? ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS
                            : primitive instanceof ActionDsl.TakeKnownContainerStack take
                                    ? ActionDslCompiler.knownContainerTransferOperationTicks(take.maxStacks())
                            : primitive instanceof ActionDsl.StoreKnownContainerStack store
                                    ? ActionDslCompiler.knownContainerTransferOperationTicks(store.maxStacks())
                            : AgentPrimitivePlanner.CONTAINER_OPERATION_TICK_UPPER_BOUND);
            PhaseFivePort port = smelting ? knownFurnacePort
                    : knownMenu ? knownMenuPort : phaseFiveInventoryPort;
            containerAttempt = new KnownContainerAttempt(
                    port, request, session.clientTick(), deadline);
        }
        KnownContainerAttempt.TickResult result =
                containerAttempt.tick(session.clientTick());
        recordContainerEffects(
                actionId, primitive, result.effects(), worldRevision);
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(actionId);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence(), result.diagnostics().toArray(String[]::new)); }
            case SUCCEEDED -> {
                KnownContainerAttempt completedContainer = containerAttempt;
                containerAttempt = null;
                if (primitive instanceof ActionDsl.InspectKnownContainer inspect) {
                    agentActions.recordContainerInspection(actionId, inspect.target(),
                            completedContainer.inspectionContents());
                    agentActions.recordNodeEvidence(
                            actionId, InventoryRequests.containerItemsTrace(result.items()));
                } else if (primitive instanceof ActionDsl.TakeKnownContainerStack) {
                    var take = (ActionDsl.TakeKnownContainerStack) primitive;
                    agentActions.recordNodeEvidence(
                            actionId, "container_transfer=" + take.item());
                } else if (primitive instanceof ActionDsl.StoreKnownContainerStack) {
                    var store = (ActionDsl.StoreKnownContainerStack) primitive;
                    agentActions.recordNodeEvidence(
                            actionId, "container_store=" + store.item());
                } else if (primitive instanceof ActionDsl.CraftKnownRecipe craft) {
                    agentActions.recordNodeEvidence(
                            actionId, "craft_complete=" + craft.goalItem());
                } else if (primitive instanceof ActionDsl.OperateKnownMenu) {
                    agentActions.recordNodeEvidence(
                            actionId, "menu_transfer_complete");
                } else {
                    var smelt = (ActionDsl.SmeltKnownRecipe) primitive;
                    agentActions.recordNodeEvidence(
                            actionId, "smelt_complete=" + smelt.goalItem());
                }
                return PrimitiveOutcome.succeeded();
            }
        }
        return PrimitiveOutcome.running();
    }

    PrimitiveOutcome tickAgentBrewing(
            WorldSessionTracker.Snapshot session, ActionDsl.Node primitive,
            Map<String, AgentPrimitivePlanner.MutationAim> mutationAims,
            float maxCameraDegreesPerTick) {
        if (brewingAttempt == null) {
            final KnownBrewingRequest request;
            try {
                request = InventoryRequests.brewingRequest(
                        (ActionDsl.BrewKnownPotionBatch) primitive,
                        mutationAims.get(primitive.id()),
                        maxCameraDegreesPerTick);
            } catch (RuntimeException rejected) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "brewing_request_rejected");
            }
            long deadline = Math.addExact(
                    session.clientTick(), KnownBrewingRequest.MAX_TICKS);
            brewingAttempt = new KnownBrewingAttempt(
                    knownBrewingPort, request, session.clientTick(), deadline);
        }
        KnownBrewingAttempt.TickResult result =
                brewingAttempt.tick(session.clientTick());
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(actionId);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence()); }
            case SUCCEEDED -> {
                brewingAttempt = null;
                agentActions.recordNodeEvidence(
                        actionId,
                        "brewing_complete=" + result.verifiedPotions());
                return PrimitiveOutcome.succeeded();
            }
        }
        return PrimitiveOutcome.running();
    }

    PrimitiveOutcome tickAgentConstruction(
            WorldSessionTracker.Snapshot session, ActionDsl.Node primitive, long worldRevision) {
        if (constructionAttempt == null) {
            final KnownConstructionRequest request;
            try {
                request = primitive instanceof ActionDsl.ApplyKnownBlockPlan plan
                        ? ConstructionRequests.constructionRequest(
                                plan, deliveredEvidence::resolvePlacementState)
                        : ConstructionRequests.constructionRequest(
                                (ActionDsl.ClearKnownBlockPlan) primitive);
            } catch (RuntimeException rejected) {
                // Registry/state diagnostics may contain submitted property names or values.
                // Keep the public trace fixed and non-reflective.
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "construction_request_rejected");
            }
            long ticks = Math.multiplyExact(
                    request.entries().size(), (long) KnownConstructionAttempt.TICKS_PER_ENTRY);
            long deadline = Math.addExact(session.clientTick(), ticks);
            constructionAttempt = new KnownConstructionAttempt(
                    applyBlockPlanPort, request, session.clientTick(), deadline,
                    (call, stepIndex, failure) -> McmcpMod.LOGGER.error(
                            "MCMCP known-construction adapter failed: call={}, step_index={}",
                            call, stepIndex, failure));
        }
        KnownConstructionAttempt.TickResult result =
                constructionAttempt.tick(session.clientTick());
        recordConstructionEffects(actionId, result.effects(), worldRevision);
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(actionId);
        }
        for (int count = 0; count < result.brokenDelta(); count++) {
            agentActions.recordBlockBreak(actionId);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence()); }
            case SUCCEEDED -> {
                constructionAttempt = null;
                agentActions.recordNodeEvidence(
                        actionId,
                        "construction_complete=" + result.completedEntries()
                                + ",server_confirmed=" + result.confirmedEntries());
                return PrimitiveOutcome.succeeded();
            }
        }
        return PrimitiveOutcome.running();
    }

    private void recordConstructionEffects(
            UUID actionId, List<KnownConstructionAttempt.EffectDelta> effects, long worldRevision) {
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    effect.kind(),
                    effect.subject(),
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    worldRevision);
        }
    }

    private void recordContainerEffects(
            UUID actionId,
            ActionDsl.Node primitive,
            List<KnownContainerAttempt.EffectDelta> effects, long worldRevision) {
        if (effects.isEmpty()) return;
        final String kind;
        final ActionDsl.Position target;
        final String item;
        if (primitive instanceof ActionDsl.TakeKnownContainerStack take) {
            kind = "container_take";
            target = take.target();
            item = take.item();
        } else if (primitive instanceof ActionDsl.StoreKnownContainerStack store) {
            kind = "container_store";
            target = store.target();
            item = store.item();
        } else {
            throw new IllegalStateException(
                    "container transfer effect has no transfer primitive");
        }
        String subject = "container:" + target.dimension() + ":"
                + target.x() + "," + target.y() + "," + target.z() + "/" + item;
        for (var effect : effects) {
            agentActions.recordEffect(
                    actionId,
                    kind,
                    subject,
                    effect.observedBefore(),
                    effect.observedAfter(),
                    effect.verification(),
                    effect.clientTick(),
                    worldRevision);
        }
    }

    PrimitiveOutcome tickAgentPillarUp(
            WorldSessionTracker.Snapshot session, ActionDsl.Node primitive) {
        if (pillarUpAttempt == null) {
            final KnownPillarUpRequest request;
            try {
                request = ConstructionRequests.pillarUpRequest(
                        (ActionDsl.PillarUpKnown) primitive,
                        deliveredEvidence::resolvePlacementState);
            } catch (RuntimeException rejected) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "pillar_request_rejected");
            }
            long deadline = Math.addExact(
                    session.clientTick(), KnownPillarUpAttempt.MAX_TICKS);
            pillarUpAttempt = new KnownPillarUpAttempt(
                    pillarUpPort, request, session.clientTick(), deadline);
        }
        KnownPillarUpAttempt.TickResult result =
                pillarUpAttempt.tick(session.clientTick());
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(actionId);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence()); }
            case SUCCEEDED -> {
                pillarUpAttempt = null;
                agentActions.recordNodeEvidence(actionId, "pillar_up_complete=1");
                return PrimitiveOutcome.succeeded();
            }
        }
        return PrimitiveOutcome.running();
    }

    PrimitiveOutcome tickAgentRedstone(
            Minecraft minecraft, WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive, Map<String, AgentPrimitivePlanner.MutationAim> mutationAims) {
        if (redstoneAttempt == null) {
            var player = Objects.requireNonNull(minecraft.player, "player");
            var redstone = (ActionDsl.ApplyKnownRedstoneSpec) primitive;
            int outputCount = (int) redstone.components().stream()
                    .filter(component -> component.role() == RedstoneSpec.Role.OUTPUT)
                    .count();
            int wireCount = (int) redstone.components().stream()
                    .filter(component -> component.role() == RedstoneSpec.Role.WIRE)
                    .count();
            if (PlayerInventoryEvidence.inventoryItemCount(player, "minecraft:redstone_lamp") < outputCount
                    || PlayerInventoryEvidence.inventoryItemCount(player, "minecraft:lever") < 1
                    || PlayerInventoryEvidence.inventoryItemCount(player, "minecraft:redstone") < wireCount) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        true,
                        "redstone_items_unavailable");
            }
            final RedstoneIdentityRequest request;
            try {
                var lampAims = new ArrayList<AgentPrimitivePlanner.MutationAim>();
                lampAims.add(Objects.requireNonNull(
                        mutationAims.get(redstone.id() + "/lamp"),
                        "lamp aim"));
                if (outputCount == 2) {
                    lampAims.add(Objects.requireNonNull(
                            mutationAims.get(redstone.id() + "/lamp_2"),
                            "second lamp aim"));
                }
                request = ConstructionRequests.redstoneIdentityRequest(
                        redstone,
                        session.worldSessionId(),
                        lampAims,
                        Objects.requireNonNull(
                                mutationAims.get(redstone.id() + "/lever"),
                                "lever aim"),
                        wireCount == 1
                                ? Optional.of(Objects.requireNonNull(
                                        mutationAims.get(redstone.id() + "/wire"),
                                        "wire aim"))
                                : Optional.empty());
            } catch (RuntimeException rejected) {
                return PrimitiveOutcome.failed(
                        AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                        false,
                        "redstone_request_rejected");
            }
            long deadline = Math.addExact(
                    session.clientTick(),
                    ActionDslCompiler.intrinsicKnownRedstoneCost(
                            redstone.timing().settleTicks(), outputCount, wireCount).ticks());
            List<BlockPosition> lamps = request.lampTargets().stream()
                    .map(target -> new BlockPosition(
                            target.dimension(), target.x(), target.y(), target.z()))
                    .toList();
            BlockTarget lever = request.leverTarget();
            Optional<BlockPosition> wire = request.wireTarget().map(target -> new BlockPosition(
                    target.dimension(), target.x(), target.y(), target.z()));
            List<BlockPosition> halo = request.safetyEnvelope().keySet().stream()
                    .map(target -> new BlockPosition(
                            target.dimension(), target.x(), target.y(), target.z()))
                    .toList();
            redstoneAttempt = new KnownRedstoneIdentityAttempt(
                    semanticActionPort,
                    request,
                    tick -> observations.observeBlocks(
                            minecraft,
                            tick,
                            lamps,
                            MinecraftObservationService.BlockSource.LIVE),
                    tick -> observations.observeBlock(
                            minecraft,
                            tick,
                            new BlockPosition(
                                    lever.dimension(), lever.x(), lever.y(), lever.z()),
                            MinecraftObservationService.BlockSource.LIVE),
                    tick -> wire.map(position -> observations.observeBlock(
                                    minecraft,
                                    tick,
                                    position,
                                    MinecraftObservationService.BlockSource.LIVE))
                            .orElse(null),
                    tick -> observations.observeBlocks(
                            minecraft,
                            tick,
                            halo,
                            MinecraftObservationService.BlockSource.LIVE),
                    session.clientTick(),
                    deadline);
        }
        KnownRedstoneIdentityAttempt.TickResult result =
                redstoneAttempt.tick(session.clientTick());
        for (int count = 0; count < result.placedDelta(); count++) {
            agentActions.recordBlockPlace(actionId);
        }
        for (int count = 0; count < result.interactionDelta(); count++) {
            agentActions.recordInteraction(actionId);
        }
        switch (result.status()) {
            case RUNNING -> { }
            case FAILED -> { return PrimitiveOutcome.failed(
                    AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC,
                    true,
                    result.evidence()); }
            case SUCCEEDED -> {
                redstoneAttempt = null;
                agentActions.recordNodeEvidence(
                        actionId,
                        "redstone_identity_observations=" + result.outputObservations());
                return PrimitiveOutcome.succeeded();
            }
        }
        return PrimitiveOutcome.running();
    }

}
