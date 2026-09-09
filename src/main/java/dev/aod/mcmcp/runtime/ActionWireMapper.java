package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.ContainerInspection;
import dev.aod.mcmcp.agent.dsl.ActionDslOperationManifest;
import dev.aod.mcmcp.agent.dsl.ActionDslValidator;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.safety.LocalArmingState;
import dev.aod.mcmcp.safety.ScopedEntityAttackConsentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Action・状態・所有menuのスナップショットをMCP応答へ変換する。 */
final class ActionWireMapper {
    private ActionWireMapper() {}

    /** Expanded only when a phase has passed its gate. */
    static final Set<String> AVAILABLE_CAPABILITIES =
            Set.of("movement", "camera", "block_break", "block_interact", "block_place",
                    "inventory_transfer", "item_use", "entity_attack");

    static Map<String, Object> actionPayload(AgentActionStore.Snapshot snapshot) {
        var progress = snapshot.progress();
        var progressPayload = new LinkedHashMap<String, Object>();
        progressPayload.put("phase", progress.phase().wireName());
        progressPayload.put("current_node_id", progress.currentNodeId());
        progressPayload.put("executed_nodes", progress.executedNodes());
        progressPayload.put("total_node_upper_bound", progress.totalNodeUpperBound());
        progressPayload.put("distance_travelled", progress.distanceTravelled());
        progressPayload.put("camera_degrees", progress.cameraDegrees());
        progressPayload.put("interactions", progress.interactions());
        progressPayload.put("blocks_broken", progress.blocksBroken());
        progressPayload.put("blocks_placed", progress.blocksPlaced());
        progressPayload.put("ticks", progress.ticks());

        Map<String, Object> failurePayload = null;
        if (snapshot.failure() != null) {
            failurePayload = Map.of(
                    "code", snapshot.failure().code().wireName(),
                    "recoverable", snapshot.failure().recoverable(),
                    "evidence", snapshot.failure().evidence());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("action_id", snapshot.actionId().toString());
        result.put("state", snapshot.state().wireName());
        result.put("progress", progressPayload);
        result.put("failure", failurePayload);
        result.put("trace", snapshot.trace().stream().map(entry -> Map.<String, Object>of(
                "tick", entry.tick(),
                "event", entry.event(),
                "detail", entry.detail())).toList());
        result.put("effects", snapshot.effects().stream().map(effect -> {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("seq", effect.seq());
            payload.put("node_id", effect.nodeId());
            payload.put("kind", effect.kind());
            payload.put("subject", effect.subject());
            payload.put("observed_before", effect.observedBefore());
            payload.put("observed_after", effect.observedAfter());
            payload.put("verification", effect.verification().wireName());
            payload.put("client_tick", effect.clientTick());
            payload.put("world_revision", effect.worldRevision());
            return Map.copyOf(payload);
        }).toList());
        var aggregate = snapshot.effectAggregate();
        result.put("effect_aggregate", Map.of(
                "total_effects", aggregate.totalEffects(),
                "retained_effects", aggregate.retainedEffects(),
                "confirmed_effects", aggregate.confirmedEffects(),
                "qualified_effects", aggregate.qualifiedEffects(),
                "unknown_effects", aggregate.unknownEffects(),
                "dispatched_attacks", aggregate.dispatchedAttacks(),
                "confirmed_attacks", aggregate.confirmedAttacks(),
                "unknown_attacks", aggregate.unknownAttacks()));
        Map<String, Object> partialPayload = null;
        if (snapshot.partial() != null) {
            var partial = snapshot.partial();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("has_confirmed_effects", partial.hasConfirmedEffects());
            payload.put("interrupted_node_id", partial.interruptedNodeId());
            payload.put("remaining_node_upper_bound", partial.remainingNodeUpperBound());
            payload.put(
                    "resume_requires_reobservation",
                    partial.resumeRequiresReobservation());
            partialPayload = payload;
        }
        result.put("partial", partialPayload);
        result.put("source", snapshot.source().sourcePayload());
        result.put("template", snapshot.source().templatePayload());
        result.put(
                "reference_requirements",
                snapshot.source().referenceRequirementPayload());
        return result;
    }

    static Map<String, Object> actionPayload(
            AgentActionStore.Snapshot snapshot, ContainerInspection.Query query) {
        var result = actionPayload(snapshot);
        if (query.include()) result.put("container_results", ContainerInspection.page(snapshot, query));
        return result;
    }

    static Map<String, Object> merchantOfferPayload(
            UUID worldSessionId,
            int containerId,
            ContainerSyncSignals.OpenScreenEvidence open,
            MerchantOfferSignals.Snapshot snapshot) {
        if (open == null
                || snapshot == null
                || !worldSessionId.equals(open.worldSessionId())
                || !worldSessionId.equals(snapshot.worldSessionId())
                || containerId != open.containerId()
                || containerId != snapshot.containerId()
                || !"minecraft:merchant".equals(open.menuTypeId())
                || snapshot.openPacketRevision() != open.packetLedgerRevision()
                || snapshot.receivedTick() < open.receivedTick()) {
            return null;
        }
        return Map.of(
                "world_session_id", worldSessionId.toString(),
                "container_id", containerId,
                "signal_revision", snapshot.revision(),
                "open_packet_revision", snapshot.openPacketRevision(),
                "received_tick", snapshot.receivedTick(),
                "offers", MerchantOfferView.from(snapshot).stream()
                        .map(MerchantOfferView::toMap)
                        .toList());
    }

    static Map<String, Object> knownMenuPayload(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ContainerSyncSignals signals,
            KnownMenuOperationRefs references) {
        KnownMenuProfileSupport.Context context = KnownMenuProfileSupport.current(
                minecraft, session.worldSessionId(), signals).orElse(null);
        if (context == null) return null;

        var operations = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        long deadline = session.clientTick() > Long.MAX_VALUE - 1_200L
                ? Long.MAX_VALUE : session.clientTick() + 1_200L;
        for (int sourceSlot : context.transferableStorageSlots()) {
            ItemStack source = context.menu().slots.get(sourceSlot).getItem();
            if (!context.canTransferEntireStack(sourceSlot)) {
                continue;
            }
            if (operations.size() == KnownMenuOperationRefs.MAX_LEASES) {
                truncated = true;
                continue;
            }
            int baseline = PlayerInventoryEvidence.exactPlayerCount(context, source);
            int expected = Math.addExact(baseline, source.getCount());
            String operationReference = references.issue(
                    context.referenceContext(session.worldSessionId(), session.clientTick()),
                    sourceSlot,
                    context.snapshot().slots().get(sourceSlot),
                    source,
                    context.snapshot().slots(),
                    baseline,
                    expected,
                    KnownMenuOperationRefs.TRANSFER_TO_PLAYER,
                    deadline);
            operations.add(Map.of(
                    "operation_ref", operationReference,
                    "kind", KnownMenuOperationRefs.TRANSFER_TO_PLAYER,
                    "stack", Map.of(
                            "item", context.snapshot().slots().get(sourceSlot).itemId(),
                            "count", source.getCount(),
                            "damage", source.getDamageValue(),
                            "max_damage", source.getMaxDamage()),
                    "expected_inventory_count", expected,
                    "valid_through_client_tick", deadline));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("profile_id", context.profile().profileId());
        payload.put("profile_hash", context.profile().profileHash());
        payload.put("menu_type", context.profile().menuType());
        payload.put("operations_truncated", truncated);
        payload.put("operations", List.copyOf(operations));
        return Map.copyOf(payload);
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory) {
        return statePayload(
                lock, paused, world, inventory, List.of(),
                false,
                McmcpClientConfig.DEFAULT_VISUAL_RADIUS_BLOCKS,
                McmcpClientConfig.DEFAULT_RAYS_PER_TICK);
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory,
            boolean multiplayerEnabled,
            int visualRadiusBlocks,
            int raysPerTick) {
        return statePayload(
                lock, paused, world, inventory, List.of(), multiplayerEnabled,
                visualRadiusBlocks, raysPerTick);
    }

    static Map<String, Object> statePayload(
            LocalArmingState.Snapshot lock,
            boolean paused,
            Map<String, Object> world,
            List<Map<String, Object>> inventory,
            List<Map<String, Object>> standardPotions,
            boolean multiplayerEnabled,
            int visualRadiusBlocks,
            int raysPerTick) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(standardPotions, "standardPotions");

        var control = new LinkedHashMap<String, Object>();
        control.put("mode", lock.mode().name().toLowerCase(Locale.ROOT));
        // Kept nullable for MCP clients written against schema version 1; READY no longer expires.
        control.put("ready_expires_at", null);
        control.put("game_paused", paused);
        control.put("granted_capabilities", lock.capabilities().stream().sorted().toList());

        var actionDsl = new LinkedHashMap<String, Object>();
        actionDsl.put("version", 1);
        actionDsl.put("max_ast_depth", 4);
        actionDsl.put("max_source_nodes", 64);
        actionDsl.put("max_executed_nodes", 256);
        actionDsl.put("max_repeat_count", 16);
        actionDsl.put(
                "allowed_capabilities", AVAILABLE_CAPABILITIES.stream().sorted().toList());
        actionDsl.put(
                "available_operations",
                ActionDslOperationManifest.operationPayload(lock.capabilities()));
        actionDsl.put(
                "reference_descriptors",
                ActionDslOperationManifest.referenceDescriptorPayload());
        actionDsl.put(
                "missing_capability_guidance",
                ActionDslOperationManifest.missingCapabilityGuidance());
        var policy = Map.<String, Object>ofEntries(
                Map.entry("profile", "survival_omnidirectional"),
                Map.entry("multiplayer_enabled", multiplayerEnabled),
                Map.entry("max_duration_ms", Math.toIntExact(
                        ActionDslValidator.MAX_ACTION_DURATION_MILLIS)),
                Map.entry("max_ticks", ActionDslValidator.MAX_ACTION_TICKS),
                Map.entry("max_distance_blocks", 32),
                Map.entry("max_camera_degrees", ActionDslValidator.MAX_ACTION_CAMERA_DEGREES),
                Map.entry("max_blocks_broken", 8),
                Map.entry("max_interactions", ActionDslValidator.MAX_INTERACTIONS),
                Map.entry("max_blocks_placed", 8),
                Map.entry("omnidirectional_visual_radius_blocks", visualRadiusBlocks),
                Map.entry(
                        "local_observation_radius_blocks",
                        (int) LocalObservationVolume.RADIUS_BLOCKS),
                Map.entry("omnidirectional_direction_count", 2_048),
                Map.entry("omnidirectional_rays_per_tick", raysPerTick),
                Map.entry("max_recent_sound_clues", 32),
                Map.entry("sound_clue_ttl_ticks", 600),
                Map.entry("action_dsl", actionDsl));

        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", 1);
        result.put("control", control);
        result.put("world", world);
        result.put("inventory", List.copyOf(inventory));
        result.put("standard_potions", List.copyOf(standardPotions));
        result.put(
                "entity_attack_consent",
                entityAttackConsentPayload(ScopedEntityAttackConsentStore.Snapshot.none()));
        result.put("recipe_query", null);
        result.put("policy", policy);
        result.put("observation", null);
        result.put("action", null);
        return result;
    }

    static Map<String, Object> entityAttackConsentPayload(
            ScopedEntityAttackConsentStore.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var result = new LinkedHashMap<String, Object>();
        result.put("state", snapshot.state().name().toLowerCase(Locale.ROOT));
        result.put("policy_binding_hash", snapshot.policyBindingHash());
        if (snapshot.scope() == null) {
            result.put("scope", null);
        } else {
            var scope = snapshot.scope();
            result.put("scope", Map.ofEntries(
                    Map.entry("dimension", scope.dimension()),
                    Map.entry(
                            "player_station_bounds",
                            entityAttackConsentBoundsPayload(scope.playerStationBounds())),
                    Map.entry(
                            "target_kill_zone_bounds",
                            entityAttackConsentBoundsPayload(scope.targetKillZoneBounds())),
                    Map.entry("entity_type_allowlist", scope.entityTypeAllowlist()),
                    Map.entry("main_hand", Map.of(
                            "item", scope.mainHandItem(),
                            "attack_effects_bound", true)),
                    Map.entry(
                            "side_effect_profile",
                            scope.attackSideEffectProfile().name().toLowerCase(Locale.ROOT)),
                    Map.entry("structure_bound", true),
                    Map.entry("max_attacks", scope.maxAttacks()),
                    Map.entry("minimum_interval_ticks", scope.minimumIntervalTicks()),
                    Map.entry(
                            "max_operation_duration_ticks",
                            scope.maxOperationDurationTicks())));
        }
        boolean granted = snapshot.state() == ScopedEntityAttackConsentStore.State.GRANTED;
        result.put("consent_ref", granted ? snapshot.consentRef() : null);
        result.put("valid_before_tick", granted ? snapshot.validBeforeClientTick() : null);
        return result;
    }

    static Map<String, Double> entityAttackConsentBoundsPayload(
            ScopedEntityAttackConsentStore.Bounds bounds) {
        return Map.of(
                "min_x", bounds.minX(),
                "min_y", bounds.minY(),
                "min_z", bounds.minZ(),
                "max_x", bounds.maxX(),
                "max_y", bounds.maxY(),
                "max_z", bounds.maxZ());
    }
}
