package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilityMap;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import dev.aod.mcmcp.agent.observation.DeliveredPolicyEvidenceStore;
import dev.aod.mcmcp.agent.observation.ObservationFilter;
import dev.aod.mcmcp.agent.observation.ObservationFrame;
import dev.aod.mcmcp.agent.observation.ObservationFrameStore;
import dev.aod.mcmcp.agent.observation.ObservationKind;
import dev.aod.mcmcp.agent.observation.ObservationPage;
import dev.aod.mcmcp.agent.observation.ObservationStoreException;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import dev.aod.mcmcp.agent.observation.ObservationWireMapper;
import dev.aod.mcmcp.agent.observation.OmnidirectionalObserver;
import dev.aod.mcmcp.agent.observation.SoundClueStore;
import dev.aod.mcmcp.agent.observation.SoundPlaybackQueue;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.mcp.McpRuntimePort.RuntimeReply;
import dev.aod.mcmcp.mcp.McpRuntimePort;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** 観測frame、配送済み証拠、音、局所地図とそのrevisionを所有する。 */
final class AgentObservations {
    private final WorldMemory memory;
    private final WorldSessionTracker sessions;
    private final MinecraftObservationService observations;
    private final ClientReconciliationSignals reconciliationSignals;
    private final ObservationFrameStore agentObservationFrames = new ObservationFrameStore();
    private final DeliveredPolicyEvidenceStore deliveredAgentEvidence =
            new DeliveredPolicyEvidenceStore();
    private final SoundClueStore soundClues = new SoundClueStore();
    private final SoundPlaybackQueue soundPlaybacks = new SoundPlaybackQueue();
    private final KnownTraversabilityMap knownTraversability = new KnownTraversabilityMap();
    private OmnidirectionalObserver agentObserver;
    private LocalObservationVolume.Snapshot latestLocalObservation;
    private LocalObservationProjector.CurrentSafety localSafety =
            LocalObservationProjector.CurrentSafety.REPLAN;
    private long knownTraversabilityRevision;
    private boolean soundPlaybackTruncated;

    AgentObservations(WorldMemory memory, WorldSessionTracker sessions, MinecraftObservationService observations,
            ClientReconciliationSignals reconciliationSignals) {
        this.memory = memory;
        this.sessions = sessions;
        this.observations = observations;
        this.reconciliationSignals = reconciliationSignals;
    }

    ObservationFrameStore frames() { return agentObservationFrames; }
    DeliveredPolicyEvidenceStore deliveredEvidence() { return deliveredAgentEvidence; }
    SoundClueStore soundClues() { return soundClues; }
    SoundPlaybackQueue soundPlaybacks() { return soundPlaybacks; }
    LocalObservationVolume.Snapshot localObservation() { return latestLocalObservation; }
    LocalObservationProjector.CurrentSafety localSafety() { return localSafety; }
    boolean soundPlaybackTruncated() { return soundPlaybackTruncated; }

    void startSession(UUID sessionId, String dimension, long revision) {
        knownTraversability.startSession(sessionId, dimension, revision);
        knownTraversabilityRevision = revision;
    }

    void clearSession() {
        agentObservationFrames.clear();
        deliveredAgentEvidence.clear();
        soundClues.clear();
        soundPlaybacks.clear();
        soundPlaybackTruncated = false;
        latestLocalObservation = null;
        knownTraversability.clearWorld();
        knownTraversabilityRevision = 0L;
        localSafety = LocalObservationProjector.CurrentSafety.REPLAN;
    }

    void resetObserver() {
        if (agentObserver != null) agentObserver.reset();
    }

    PreparedObservationPage getAgentObservation(Map<String, Object> arguments) {
        Set<String> required = Set.of("schema_version", "frame_id", "kinds", "cursor", "limit");
        RuntimeArguments.requireAllowedKeys(arguments, "agent_get_observation",
                Set.of("schema_version", "frame_id", "kinds", "filter", "cursor", "limit"));
        if (!arguments.keySet().containsAll(required)
                || arguments.size() < required.size()
                || arguments.size() > required.size() + 1) {
            throw new IllegalArgumentException(
                    "agent_get_observation must contain schema_version, frame_id, kinds, cursor, "
                            + "and limit; filter is optional");
        }
        if (RuntimeArguments.intArgument(arguments, "schema_version") != 1) {
            throw new IllegalArgumentException("schema_version must be 1");
        }
        Object rawKinds = arguments.get("kinds");
        if (!(rawKinds instanceof List<?> values)) {
            throw new IllegalArgumentException("kinds must be an array");
        }
        var kinds = EnumSet.noneOf(ObservationKind.class);
        for (Object value : values) {
            if (!(value instanceof String wireName) || !kinds.add(ObservationKind.fromWireName(wireName))) {
                throw new IllegalArgumentException("kinds must contain unique observation kinds");
            }
        }
        Object rawCursor = arguments.get("cursor");
        String cursor = rawCursor == null ? null : (String) rawCursor;
        ObservationFilter filter = RuntimeArguments.observationFilterArgument(arguments);
        try {
            ObservationPage page = agentObservationFrames.page(
                    RuntimeArguments.stringArgument(arguments, "frame_id"),
                    kinds,
                    filter,
                    cursor,
                    RuntimeArguments.intArgument(arguments, "limit"));
            UUID receiptId = deliveredAgentEvidence.prepareDelivery(page);
            Map<String, Object> wirePage = ObservationWireMapper.page(page, surface ->
                    deliveredAgentEvidence.preparedPlacementStateRef(receiptId, surface)
                            .orElse(null));
            return new PreparedObservationPage(wirePage, receiptId);
        } catch (ObservationStoreException failure) {
            throw new RuntimeInvocationException(
                    failure.code().name().toLowerCase(Locale.ROOT),
                    failure.getMessage(),
                    failure.code() != ObservationStoreException.Code.INVALID_CURSOR,
                    Map.of());
        }
    }

    void abandonUnconfirmedDelivery(RuntimeReply reply) {
        if (reply != null
                && reply.deliveryReceipt()
                        instanceof McpRuntimePort.ObservationDeliveryReceipt observation) {
            deliveredAgentEvidence.abandonDelivery(observation.receiptId());
        }
    }

    record PreparedObservationPage(Map<String, Object> wirePage, UUID receiptId) {
        PreparedObservationPage {
            wirePage = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(
                            Objects.requireNonNull(wirePage, "wirePage")));
            Objects.requireNonNull(receiptId, "receiptId");
        }
    }

    /**
     * Planner view containing the current frame plus only static surfaces that were actually
     * returned to the MCP client. Every consumer still applies its ordinary revision, pose,
     * reach, age, commit, and JIT fences. Actual target-frame rays may refresh its internal
     * record, but neither its original delivery lease nor the public frame is extended.
     */
    Optional<ObservationFrame> agentPlanningFrame() {
        return agentPlanningFrame(null);
    }

    Optional<ObservationFrame> agentPlanningFrame(ActionDsl.Node primitive) {
        return agentPlanningFrame(primitive, null);
    }

    Optional<ObservationFrame> agentPlanningFrame(ActionDsl.Node primitive,
            DeliveredPolicyEvidenceStore.SurfaceLease surfaceLease) {
        var minecraft = Minecraft.getInstance();
        McmcpRuntime.assertClientThread(minecraft);
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.level == null || minecraft.player == null
                || agentObserver == null) return Optional.empty();
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        var fogDistance = ClientFogDistanceSignals.current(
                minecraft.level, minecraft.player, minecraft.player.tickCount);
        if (fogDistance.isEmpty()) {
            // Retain the original evidence timestamps/revisions; missing render data cannot
            // authorize a new ray or refresh a previously delivered surface.
            return deliveredAgentEvidence.augment(agentObservationFrames.latestFrame());
        }
        String frameRef = ActionPlanning.frameItemTargetRef(primitive);
        var planning = deliveredAgentEvidence.reobserveForPlanning(agentObservationFrames.latestFrame(), surface -> {
            var position = surface.position();
            long barrier = reconciliation.surfaceBarrierWorldRevision(
                    position.x(), position.y(), position.z());
            if (surface.worldRevision() >= barrier
                    && (surfaceLease == null || !surfaceLease.targets(surface))) return Optional.of(surface);
            return agentObserver.reobserveSurface(minecraft.level, minecraft.player, surface,
                    session.clientTick(), reconciliation.worldRevision(), fogDistance.getAsDouble());
        }, session.clientTick(), known -> {
            if (frameRef == null || !frameRef.equals(known.entityRef())
                    || known.worldRevision() >= reconciliation.visualBarrierWorldRevision()) {
                return Optional.empty();
            }
            return observations.resolveLoadedEntityRefIdentity(minecraft, session.clientTick(),
                            session.worldSessionId(), session.dimension(), frameRef,
                            McmcpClientConfig.visualRadiusBlocks())
                    .filter(net.minecraft.world.entity.decoration.ItemFrame.class::isInstance)
                    .map(net.minecraft.world.entity.decoration.ItemFrame.class::cast)
                    .flatMap(frame -> OmnidirectionalObserver.reobserveFrameEntity(
                            minecraft.level, minecraft.player, frame, known, session.clientTick(),
                            reconciliation.worldRevision(), McmcpClientConfig.visualRadiusBlocks()));
        }, surface -> surfaceLease != null && surfaceLease.targets(surface));
        return surfaceLease == null ? planning
                : deliveredAgentEvidence.restrictToSurfaceLease(planning, surfaceLease);
    }

    KnownTraversabilitySnapshot requireAgentMap(
            WorldSessionTracker.Snapshot session) {
        var map = knownTraversability.snapshot().orElseThrow(() ->
                new RuntimeInvocationException(
                        "unsafe_state", "No current traversability map is available.", true, Map.of()));
        if (!session.worldReady()
                || !session.worldSessionId().equals(map.worldSessionId())
                || !session.dimension().equals(map.dimension())
                || map.worldRevision() != knownTraversabilityRevision) {
            throw new RuntimeInvocationException(
                    "unsafe_state",
                    "The traversability map crossed a world boundary.",
                    true,
                    Map.of());
        }
        return map;
    }

    void synchronizeKnownTraversability(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.level == null) {
            return;
        }
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        if (reconciliation.worldRevision() <= knownTraversabilityRevision) {
            return;
        }
        var mutations = reconciliation.worldMutations().stream()
                .filter(mutation -> mutation.revision() > knownTraversabilityRevision)
                .toList();
        boolean ledgerGap = mutations.isEmpty()
                || mutations.getFirst().revision() != knownTraversabilityRevision + 1L;
        if (ledgerGap || mutations.stream().anyMatch(mutation ->
                mutation.kind() == ClientReconciliationSignals.WorldMutation.Kind.ALL)) {
            knownTraversability.startSession(
                    session.worldSessionId(), session.dimension(), reconciliation.worldRevision());
            knownTraversabilityRevision = reconciliation.worldRevision();
            return;
        }

        var affected = new LinkedHashSet<NavCell>();
        var map = knownTraversability.snapshot().orElseThrow();
        for (var key : map.edges().keySet()) {
            for (var mutation : mutations) {
                if (mutationAffects(mutation, key.from()) || mutationAffects(mutation, key.to())) {
                    affected.add(key.from());
                    affected.add(key.to());
                    break;
                }
            }
        }
        knownTraversability.advanceWorldRevision(
                reconciliation.worldRevision(), affected, List.of());
        knownTraversabilityRevision = reconciliation.worldRevision();
    }

    static boolean mutationAffects(
            ClientReconciliationSignals.WorldMutation mutation, NavCell cell) {
        if (mutation.navigationImpact() == ClientReconciliationSignals.NavigationImpact.NONE) {
            return false;
        }
        return switch (mutation.kind()) {
            case ENTITY_DISPLAY -> false;
            case ALL -> true;
            case CHUNK -> (cell.x() >> 4) == mutation.x() && (cell.z() >> 4) == mutation.z();
            case BLOCK -> Math.abs((long) cell.x() - mutation.x()) <= 1L
                    && Math.abs((long) cell.z() - mutation.z()) <= 1L
                    && cell.y() >= mutation.y() - 2
                    && cell.y() <= mutation.y() + 3;
        };
    }

    void collectAgentObservation(Minecraft minecraft) {
        var session = sessions.snapshot();
        if (!session.worldReady() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        int radius = McmcpClientConfig.visualRadiusBlocks();
        int rays = McmcpClientConfig.raysPerTick();
        if (agentObserver == null
                || agentObserver.configuredRadiusBlocks() != radius
                || agentObserver.raysPerTick() != rays) {
            agentObserver = new OmnidirectionalObserver(radius, rays);
        }
        var reconciliation = reconciliationSignals.bindAndSnapshot(
                minecraft.level, session.worldSessionId());
        long worldRevision = reconciliation.worldRevision();
        var dimension = new ResourceId(session.dimension());
        latestLocalObservation = LocalObservationVolume.global().observe(
                minecraft.player, session.clientTick(), worldRevision);
        var local = LocalObservationProjector.project(
                latestLocalObservation,
                session.worldSessionId(),
                session.dimension(),
                worldRevision,
                minecraft.player.getY());
        localSafety = local.currentSafety();
        local.edges().forEach(knownTraversability::observe);
        soundPlaybackTruncated = soundPlaybacks.drainInto(
                soundClues,
                dimension,
                session.clientTick(),
                worldRevision,
                candidate -> {
                    Identifier identifier = Identifier.tryParse(candidate);
                    return identifier != null && BuiltInRegistries.ENTITY_TYPE.get(identifier).isPresent();
                }).recentSoundCluesTruncated();
        var fogDistance = ClientFogDistanceSignals.current(
                minecraft.level,
                minecraft.player,
                minecraft.player.tickCount);
        // Several client ticks may run between renders (for example at background FPS).
        // Local safety and sound still update above; do not turn absent renderer data into
        // a fabricated one-block fog frame or redate the previous visual evidence.
        if (fogDistance.isEmpty()) return;
        agentObserver.tick(
                        minecraft.level,
                        minecraft.player,
                        session.clientTick(),
                        worldRevision,
                        reconciliation.visualRevision(),
                        fogDistance.getAsDouble(),
                        entity -> {
                            var position = entity.position();
                            var velocity = entity.getDeltaMovement();
                            String entityType = BuiltInRegistries.ENTITY_TYPE
                                    .getKey(entity.getType()).toString();
                            return memory.rememberVisibleEntityReference(
                                    session.worldSessionId(),
                                    session.dimension(),
                                    entity.getUUID(),
                                    entityType,
                                    position.x,
                                    position.y,
                                    position.z,
                                    velocity.x,
                                    velocity.y,
                                    velocity.z,
                                    entity.isVehicle(),
                                    entity.isPassenger(),
                                    session.clientTick());
                        })
                .ifPresent(visual -> {
                    var sounds = soundClues.snapshot(visual.frameCompletedTick());
                    var records = new ArrayList<>(visual.records());
                    records.addAll(local.records());
                    records.addAll(sounds.clues());
                    agentObservationFrames.publish(new ObservationFrame(
                            visual.frameId(),
                            visual.dimension(),
                            visual.frameCompletedTick(),
                            visual.configuredVisualRadiusBlocks(),
                            visual.visibleEntitiesTruncated(),
                            sounds.recentSoundCluesTruncated() || soundPlaybackTruncated,
                            records));
                });
    }

}
