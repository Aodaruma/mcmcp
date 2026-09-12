package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.routine.BoundedInputLease;
import dev.aod.mcmcp.routine.MinecraftStationaryBreakPort;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 有限入力leaseと、その間だけ有効な姿勢・health・停滞証拠を所有する。 */
final class BoundedInputExecution {
    private final Object playerIdentity;
    private final UUID worldSessionId;
    private final ScreenOwnershipSignals screenOwnership;
    private final AgentActionStore agentActions;
    private Hold boundedInputHold;

    BoundedInputExecution(Object playerIdentity, UUID worldSessionId,
            ScreenOwnershipSignals screenOwnership, AgentActionStore agentActions) {
        this.playerIdentity = playerIdentity;
        this.worldSessionId = worldSessionId;
        this.screenOwnership = screenOwnership;
        this.agentActions = agentActions;
    }

    PrimitiveOutcome tick(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.HoldBoundedInputs hold,
            boolean recordTick, long startedAtNanos, long pausedNanos, long latestWorldRevision,
            LocalObservationProjector.CurrentSafety localSafety) {
        var player = Objects.requireNonNull(minecraft.player, "player");
        var level = Objects.requireNonNull(minecraft.level, "level");
        var progress = agentActions.get(action.actionId()).progress();
        if (boundedInputHold != null && AgentInputState.global().boundedBudgetExhausted()) {
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false,
                    "bounded_input_repetition_budget");
        }
        if (boundedInputHold != null && AgentInputState.global().boundedDispatchRejected()) {
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                    "bounded_input_dispatch_guard_changed");
        }
        if (boundedInputHold != null
                && boundedInputHold.activeTicks >= hold.durationTicks()) {
            if (!close()) {
                return PrimitiveOutcome.failed(AgentActionStore.FailureCode.INTERNAL_ERROR, false,
                        "bounded_input_release_failed");
            }
            return PrimitiveOutcome.succeeded();
        }
        long durationLimit = Duration.ofMillis(
                action.program().effectiveBudget().maxDurationMillis()).toNanos();
        if ((recordTick
                        ? progress.ticks() >= action.program().effectiveBudget().maxTicks()
                        : progress.ticks() > action.program().effectiveBudget().maxTicks())
                || ActionBudgets.activeElapsedNanos(startedAtNanos, pausedNanos, System.nanoTime()) >= durationLimit) {
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.BUDGET_EXCEEDED, false,
                    "bounded_input_duration_budget");
        }
        String unsafe = boundedInputUnsafeReason(minecraft, session, action, hold, localSafety);
        if (unsafe == null && (!hold.repeatTarget() || boundedInputHold == null)) {
            unsafe = boundedTargetMismatch(minecraft, hold);
        }
        if (unsafe != null) {
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                    "bounded_input_" + unsafe);
        }
        try {
            boolean acquired = false;
            if (boundedInputHold == null) {
                Set<BoundedInputLease.Input> inputs = hold.inputs().stream()
                        .map(BoundedInputExecution::boundedLeaseInput)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                var lease = BoundedInputLease.acquire(
                        AgentInputState.global(), inputs, System.nanoTime(), Duration.ofSeconds(1));
                boundedInputHold = new Hold(
                        lease, player.position(), player.getHealth() + player.getAbsorptionAmount(),
                        session, localSafety);
                boundedInputHold.pausedNanos = pausedNanos;
                if (hold.targetGuard().isPresent()) {
                    java.util.function.Supplier<AgentInputState.BoundedDispatchDecision> guard = () -> {
                        var current = boundedInputHold;
                        if (current == null || ActionBudgets.activeElapsedNanos(
                                startedAtNanos, current.pausedNanos, System.nanoTime()) >= durationLimit
                                || boundedInputUnsafeReason(minecraft, current.session, action, hold,
                                        current.localSafety) != null) {
                            return AgentInputState.BoundedDispatchDecision.STOP;
                        }
                        if (boundedTargetMismatch(minecraft, hold) == null) {
                            return AgentInputState.BoundedDispatchDecision.ALLOW;
                        }
                        return hold.repeatTarget() ? AgentInputState.BoundedDispatchDecision.WAIT
                                : AgentInputState.BoundedDispatchDecision.STOP;
                    };
                    if (hold.repeatTarget()) {
                        AgentInputState.global().setRepeatingBoundedDispatchGuard(guard,
                                () -> reserveInputStart(action, hold));
                    } else {
                        AgentInputState.global().setBoundedDispatchGuard(
                                () -> guard.get() == AgentInputState.BoundedDispatchDecision.ALLOW);
                    }
                }
                acquired = true;
            }
            var execution = boundedInputHold;
            execution.session = session;
            execution.localSafety = localSafety;
            execution.pausedNanos = pausedNanos;
            if (!acquired && !execution.lease.heartbeat(
                    System.nanoTime(), Duration.ofSeconds(1))) {
                return PrimitiveOutcome.failed(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                        "bounded_input_lease_expired");
            }
            if (boundedInputMoves(hold)) {
                double remaining = Math.max(0.0D,
                        action.program().effectiveBudget().maxDistanceBlocks()
                                - progress.distanceTravelled());
                AgentInputState.global().requireGoalMovementSafety(
                        player, level, latestWorldRevision, remaining);
            }
            execution.observeMovement(player.position(), boundedInputMovesHorizontally(hold));
            execution.activeTicks++;
            if (recordTick) agentActions.recordTick(action.actionId());
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP bounded input hold failed", failure);
            return PrimitiveOutcome.failed(AgentActionStore.FailureCode.SAFETY_INTERRUPTED, true,
                    "bounded_input_runtime_failure");
        }
        return PrimitiveOutcome.running();
    }

    private String boundedInputUnsafeReason(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            AgentActionStore.Active action,
            ActionDsl.HoldBoundedInputs hold, LocalObservationProjector.CurrentSafety localSafety) {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null
                || minecraft.getConnection() == null) return "world_unavailable";
        if (player != playerIdentity || !session.worldReady()
                || !Objects.equals(session.worldSessionId(), worldSessionId)) {
            return "world_session_changed";
        }
        if (!player.isAlive() || player.isDeadOrDying()) return "player_dead";
        if (minecraft.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL) {
            return "not_survival";
        }
        if (boundedInputHold != null
                && player.getHealth() + player.getAbsorptionAmount()
                        < boundedInputHold.effectiveHealthBaseline) {
            return "health_decreased";
        }
        if (player.isOnFire()) return "on_fire";
        if (player.isInLava()) return "in_lava";
        if (player.isInWater()) return "in_water";
        if (player.isPassenger() || player.isFallFlying() || player.fallDistance > 0.0F) {
            return "unstable_pose";
        }
        if (!AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())) return "screen_open";
        if (minecraft.gui.overlay() != null) return "overlay_open";
        if (screenOwnership.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) {
            return "screen_owner_active";
        }
        if (localSafety != LocalObservationProjector.CurrentSafety.CONTINUE) {
            return "local_safety_changed";
        }
        BlockPos feet = BlockPos.containing(player.position());
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())
                || !level.getWorldBorder().isWithinBounds(feet)) return "unknown_or_unloaded";
        if (boundedInputHold != null
                && boundedInputHold.stalledTicks >= 10) return "movement_blocked";
        if (action.program().effectiveBudget().maxDistanceBlocks()
                - agentActions.get(action.actionId()).progress().distanceTravelled() <= 0.0D
                && boundedInputMoves(hold)) return "distance_limit";
        if (hold.targetGuard().isEmpty()) return null;
        var guard = hold.targetGuard().orElseThrow();
        if (!guard.target().dimension().equals(level.dimension().identifier().toString())) {
            return "target_dimension_changed";
        }
        var target = new BlockPos(guard.target().x(), guard.target().y(), guard.target().z());
        if (!level.isLoaded(target) || !level.getWorldBorder().isWithinBounds(target)
                || !player.isWithinBlockInteractionRange(target, 0.0D)) {
            return "target_face_or_reach_changed";
        }
        var selected = player.getMainHandItem();
        if (selected.isEmpty() || !hold.selectedItem().orElseThrow().equals(
                BuiltInRegistries.ITEM.getKey(selected.getItem()).toString())) {
            return "selected_item_changed";
        }
        if (boundedInputHold != null
                && player.position().distanceToSqr(boundedInputHold.startPosition)
                        > 1.0D / (1024.0D * 1024.0D)) return "station_changed";
        return null;
    }

    private static String boundedTargetMismatch(Minecraft minecraft, ActionDsl.HoldBoundedInputs hold) {
        if (hold.targetGuard().isEmpty()) return null;
        var guard = hold.targetGuard().orElseThrow();
        var target = new BlockPos(guard.target().x(), guard.target().y(), guard.target().z());
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)
                || hit.getDirection() != Direction.valueOf(guard.face().name())) {
            return "target_face_or_reach_changed";
        }
        var actual = MinecraftStationaryBreakPort.fingerprintForPolicy(minecraft.level.getBlockState(target));
        return guard.matches(actual.blockId(), actual.properties()) ? null : "target_state_changed";
    }

    boolean reserveInputStart(AgentActionStore.Active action, ActionDsl.HoldBoundedInputs hold) {
        var budget = action.program().effectiveBudget();
        var progress = agentActions.get(action.actionId()).progress();
        // The hold is the sole node; its interaction ledger is also its start counter.
        if (progress.interactions() >= hold.maxRepetitions()
                || progress.interactions() >= budget.maxInteractions()
                || hold.inputs().contains(ActionDsl.BoundedInput.ATTACK)
                        && progress.interactions() >= budget.maxBlocksBroken()) return false;
        // Starts are conservative attempts, not confirmed breaks or inventory effects.
        agentActions.recordInteraction(action.actionId());
        return true;
    }

    boolean close() {
        if (boundedInputHold == null) return true;
        try {
            boundedInputHold.lease.close();
            AgentInputState.global().clearBoundedDispatchGuard();
            boundedInputHold = null;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            McmcpMod.LOGGER.error("MCMCP bounded input release failed", failure);
            return false;
        }
    }

    private static boolean boundedInputMoves(ActionDsl.HoldBoundedInputs hold) {
        return hold.inputs().stream().anyMatch(input -> switch (input) {
            case FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK -> true;
            case ATTACK, USE -> false;
        });
    }

    private static boolean boundedInputMovesHorizontally(ActionDsl.HoldBoundedInputs hold) {
        return hold.inputs().stream().anyMatch(input -> switch (input) {
            case FORWARD, BACK, LEFT, RIGHT -> true;
            case JUMP, SNEAK, ATTACK, USE -> false;
        });
    }

    private static BoundedInputLease.Input boundedLeaseInput(ActionDsl.BoundedInput input) {
        return BoundedInputLease.Input.valueOf(input.name());
    }

    private static final class Hold {
        private final BoundedInputLease lease;
        private final Vec3 startPosition;
        private final float effectiveHealthBaseline;
        private Vec3 lastPosition;
        private long activeTicks;
        private int stalledTicks;
        private long pausedNanos;
        private WorldSessionTracker.Snapshot session;
        private LocalObservationProjector.CurrentSafety localSafety;

        private Hold(
                BoundedInputLease lease, Vec3 startPosition, float effectiveHealthBaseline,
                WorldSessionTracker.Snapshot session, LocalObservationProjector.CurrentSafety localSafety) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.startPosition = Objects.requireNonNull(startPosition, "startPosition");
            this.lastPosition = startPosition;
            if (!Float.isFinite(effectiveHealthBaseline) || effectiveHealthBaseline <= 0.0F) {
                throw new IllegalArgumentException("bounded input health baseline must be positive");
            }
            this.effectiveHealthBaseline = effectiveHealthBaseline;
            this.session = session;
            this.localSafety = localSafety;
        }

        private void observeMovement(Vec3 current, boolean expectsHorizontalMovement) {
            Objects.requireNonNull(current, "current");
            double horizontal = Math.hypot(current.x - lastPosition.x, current.z - lastPosition.z);
            stalledTicks = expectsHorizontalMovement && horizontal < 1.0E-4D
                    ? Math.min(10, stalledTicks + 1) : 0;
            lastPosition = current;
        }
    }

}
