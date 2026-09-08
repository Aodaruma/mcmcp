package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.safety.LocalArmingState;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

/** 釣りのdispatch・ACK・cleanupを所有し、不明結果を再送しない。 */
final class FishingPrimitiveExecution {
    private final UUID actionId;
    private final AgentActionStore agentActions;
    private final FishingSessionRefs fishingSessionRefs;
    private final LocalArmingState arming;
    private FishingAttempt fishingAttempt;

    FishingPrimitiveExecution(UUID actionId, AgentActionStore agentActions,
            FishingSessionRefs fishingSessionRefs, LocalArmingState arming) {
        this.actionId = actionId;
        this.agentActions = agentActions;
        this.fishingSessionRefs = fishingSessionRefs;
        this.arming = arming;
    }

    boolean active() { return fishingAttempt != null; }

    boolean close(Minecraft minecraft, long clientTick, long latestWorldRevision) {
        if (fishingAttempt == null) return true;
        try {
            if (releaseFishingAttempt(minecraft, fishingAttempt, clientTick, latestWorldRevision)) {
                fishingAttempt = null;
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP known-fishing release failed", failure);
        }
        return false;
    }

    PrimitiveOutcome tickAgentFishing(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            ActionDsl.Node primitive, long latestWorldRevision) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var gameMode = Objects.requireNonNull(minecraft.gameMode, "gameMode");
        if (fishingAttempt == null) {
            if (primitive instanceof ActionDsl.CastKnownFishingRod cast) {
                if (!PlayerInventoryEvidence.exactFishingRodHeld(player, cast.hand(), cast.rodItem())
                        || player.fishing != null) {
                    return PrimitiveOutcome.failed(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                            "fishing_cast_precondition_changed");
                }
                gameMode.useItem(player, PlayerInventoryEvidence.fishingHand(cast.hand()));
                agentActions.recordInteraction(actionId);
                fishingAttempt = FishingAttempt.cast(
                        cast.hand(), cast.rodItem(), session.clientTick());
                return PrimitiveOutcome.running();
            }
            var reel = (ActionDsl.ReelKnownFishingSession) primitive;
            FishingSessionRefs.Session granted = fishingSessionRefs.consume(
                            reel.fishingSessionRef(), session.worldSessionId(),
                            session.dimension(), session.clientTick())
                    .orElse(null);
            FishingHook hook = player.fishing;
            if (granted == null
                    || !granted.hand().equals(reel.hand())
                    || !granted.rodItem().equals(reel.rodItem())
                    || !PlayerInventoryEvidence.exactFishingRodHeld(player, reel.hand(), reel.rodItem())
                    || !PlayerInventoryEvidence.ownedFishingHook(player, hook, granted.bobberId())) {
                return PrimitiveOutcome.failed(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                        "fishing_session_unavailable");
            }
            ItemStack rod = player.getItemInHand(PlayerInventoryEvidence.fishingHand(reel.hand()));
            gameMode.useItem(player, PlayerInventoryEvidence.fishingHand(reel.hand()));
            agentActions.recordInteraction(actionId);
            fishingAttempt = FishingAttempt.reel(
                    reel.hand(), reel.rodItem(), granted.bobberId(),
                    rod.getDamageValue(), PlayerInventoryEvidence.inventoryCounts(player), session.clientTick());
            return PrimitiveOutcome.running();
        }

        FishingAttempt attempt = fishingAttempt;
        if (attempt.mode == FishingMode.CAST) {
            FishingHook hook = player.fishing;
            if (PlayerInventoryEvidence.ownedFishingHook(player, hook, null)) {
                String reference = fishingSessionRefs.issue(
                        session.worldSessionId(), session.dimension(), hook.getUUID(),
                        attempt.hand, attempt.rodItem, session.clientTick());
                agentActions.recordEffect(
                        actionId, "fishing_cast", "minecraft:fishing_bobber",
                        Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                                "bobber_present", false),
                        Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                                "bobber_present", true, "fishing_session_ref", reference),
                        AgentActionStore.Verification.CONFIRMED,
                        session.clientTick(), latestWorldRevision);
                fishingAttempt = null;
                return PrimitiveOutcome.succeeded();
            }
        } else {
            FishingHook hook = player.fishing;
            if (hook == null || hook.isRemoved()) {
                int damageAfter = PlayerInventoryEvidence.fishingRodDamage(player, attempt.hand, attempt.rodItem);
                Map<String, Integer> inventoryAfter = PlayerInventoryEvidence.inventoryCounts(player);
                agentActions.recordEffect(
                        actionId, "fishing_reel", "minecraft:fishing_bobber",
                        Map.of("hand", attempt.hand, "rod_damage", attempt.rodDamageBefore,
                                "inventory_count", PlayerInventoryEvidence.totalInventoryCount(attempt.inventoryBefore),
                                "bobber_present", true),
                        Map.of("hand", attempt.hand, "rod_damage", damageAfter,
                                "inventory_count", PlayerInventoryEvidence.totalInventoryCount(inventoryAfter),
                                "bobber_present", false),
                        AgentActionStore.Verification.CONFIRMED,
                        session.clientTick(), latestWorldRevision);
                attempt.effectRecorded = true;
                fishingAttempt = null;
                return PrimitiveOutcome.succeeded();
            } else if (!PlayerInventoryEvidence.ownedFishingHook(player, hook, attempt.bobberId)) {
                return PrimitiveOutcome.failed(AgentActionStore.FailureCode.WORLD_CHANGED, true,
                        "owned_bobber_changed");
            }
        }
        if (session.clientTick() >= attempt.deadlineTick) {
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.SERVER_DENIED_OR_DESYNC, true,
                    "fishing_ack_timeout");
        }
        return PrimitiveOutcome.running();
    }

    private boolean releaseFishingAttempt(Minecraft minecraft, FishingAttempt attempt, long tick, long latestWorldRevision) {
        var player = minecraft.player;
        if (player == null) return true;
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            if (attempt.mode == FishingMode.CAST && tick < attempt.deadlineTick) return false;
            recordUnknownFishingEffect(player, attempt, false, tick, latestWorldRevision);
            return true;
        }
        long cleanupDeadline = attempt.cleanupDispatched
                ? attempt.cleanupDeadlineTick : attempt.deadlineTick;
        if (tick > cleanupDeadline) {
            recordUnknownFishingEffect(player, attempt,
                    PlayerInventoryEvidence.ownedFishingHook(player, hook, attempt.bobberId), tick, latestWorldRevision);
            fishingSessionRefs.clear();
            arming.lock("fishing_cleanup_unconfirmed");
            return true;
        }
        if (attempt.mode == FishingMode.REEL && !PlayerInventoryEvidence.ownedFishingHook(player, hook, attempt.bobberId)) {
            return false;
        }
        if (!attempt.cleanupDispatched) {
            if (!PlayerInventoryEvidence.exactFishingRodHeld(player, attempt.hand, attempt.rodItem)
                    || minecraft.gameMode == null) {
                return false;
            }
            minecraft.gameMode.useItem(player, PlayerInventoryEvidence.fishingHand(attempt.hand));
            agentActions.recordInteraction(actionId);
            attempt.cleanupDispatched = true;
            attempt.cleanupDeadlineTick = Math.addExact(tick, 20L);
            return false;
        }
        // Returning false keeps terminal publication behind the bounded stateful cleanup fence.
        return false;
    }

    private void recordUnknownFishingEffect(
            net.minecraft.client.player.LocalPlayer player,
            FishingAttempt attempt,
            boolean bobberPresent,
            long clientTick, long latestWorldRevision) {
        if (attempt.effectRecorded) return;
        Map<String, Object> before;
        Map<String, Object> after;
        String kind;
        if (attempt.mode == FishingMode.CAST) {
            kind = "fishing_cast";
            before = Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                    "bobber_present", false);
            after = Map.of("hand", attempt.hand, "rod_item", attempt.rodItem,
                    "bobber_present", bobberPresent);
        } else {
            kind = "fishing_reel";
            before = Map.of("hand", attempt.hand, "rod_damage", attempt.rodDamageBefore,
                    "inventory_count", PlayerInventoryEvidence.totalInventoryCount(attempt.inventoryBefore),
                    "bobber_present", true);
            after = Map.of("hand", attempt.hand,
                    "rod_damage", PlayerInventoryEvidence.fishingRodDamage(player, attempt.hand, attempt.rodItem),
                    "inventory_count", PlayerInventoryEvidence.totalInventoryCount(PlayerInventoryEvidence.inventoryCounts(player)),
                    "bobber_present", bobberPresent);
        }
        agentActions.recordEffect(
                actionId, kind, "minecraft:fishing_bobber",
                before, after, AgentActionStore.Verification.UNKNOWN,
                clientTick, latestWorldRevision);
        attempt.effectRecorded = true;
    }

    private enum FishingMode { CAST, REEL }

    private static final class FishingAttempt {
        private final FishingMode mode;
        private final String hand;
        private final String rodItem;
        private final UUID bobberId;
        private final int rodDamageBefore;
        private final Map<String, Integer> inventoryBefore;
        private final long deadlineTick;
        private final long startTick;
        private boolean effectRecorded;
        private boolean cleanupDispatched;
        private long cleanupDeadlineTick;

        private FishingAttempt(
                FishingMode mode,
                String hand,
                String rodItem,
                UUID bobberId,
                int rodDamageBefore,
                Map<String, Integer> inventoryBefore,
                long startTick) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.hand = Objects.requireNonNull(hand, "hand");
            this.rodItem = Objects.requireNonNull(rodItem, "rodItem");
            this.bobberId = bobberId;
            this.rodDamageBefore = rodDamageBefore;
            this.inventoryBefore = Map.copyOf(inventoryBefore);
            this.startTick = startTick;
            deadlineTick = Math.addExact(startTick, ActionDslCompiler.KNOWN_FISHING_TICKS);
        }

        private static FishingAttempt cast(String hand, String rodItem, long startTick) {
            return new FishingAttempt(
                    FishingMode.CAST, hand, rodItem, null, -1, Map.of(), startTick);
        }

        private static FishingAttempt reel(
                String hand,
                String rodItem,
                UUID bobberId,
                int rodDamageBefore,
                Map<String, Integer> inventoryBefore,
                long startTick) {
            return new FishingAttempt(
                    FishingMode.REEL, hand, rodItem,
                    Objects.requireNonNull(bobberId, "bobberId"),
                    rodDamageBefore, inventoryBefore, startTick);
        }
    }}
