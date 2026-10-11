package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.safety.GroundTraversal;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One checked arc using vanilla movement keys. Gravity remains owned by the game. */
final class MinecraftGroundTraversalExecutor implements AutoCloseable {
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final GroundTraversal plan;
    private final UUID owner = UUID.randomUUID();
    private MovementInputLease lease;
    private long started = -1, landed = -1;
    private boolean leftGround;

    MinecraftGroundTraversalExecutor(Minecraft minecraft, GroundTraversal plan) {
        this.minecraft = minecraft; this.plan = plan;
    }
    boolean currentSafe() { return plan.currentSafe(minecraft.player); }
    MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map, double remaining,
            long tick, BooleanSupplier allowed) {
        var player = minecraft.player;
        if (started < 0) started = tick;
        if (!allowed.getAsBoolean() || remaining <= 0 || tick - started > 100
                || AgentInputState.global().consumeGoalMovementRejection() || !currentSafe())
            return finish(MinecraftActionPrimitiveExecutor.Status.FAILED, MinecraftActionPrimitiveExecutor.Reason.UNVERIFIED_MOVEMENT_VECTOR);
        if (!player.onGround()) leftGround = true;
        if (leftGround && plan.landed(player, 0.18)) {
            if (landed < 0) landed = tick;
            // Give the server's landing damage and health packets time to arrive before another arc.
            if (tick - landed >= 10) return finish(MinecraftActionPrimitiveExecutor.Status.SUCCEEDED, MinecraftActionPrimitiveExecutor.Reason.NONE);
        } else landed = -1;
        var target = plan.landing().getCenter();
        double horizontal = Math.hypot(player.getX() - target.x, player.getZ() - target.z);
        Set<MovementInputLease.MovementKey> desired;
        if (horizontal <= 0.12 || landed >= 0) {
            desired = Set.of();
            AgentInputState.global().neutralizeTrackedAgentVelocity(player);
        } else {
            desired = MinecraftActionPrimitiveExecutor.steering(player.getX(), player.getZ(), player.getYRot(), target.x, target.z, 0.12);
        }
        if (plan.mode() == Locomotion.GAP_JUMP && !leftGround && tick - started <= 2) {
            var jumping = desired.isEmpty() ? EnumSet.noneOf(MovementInputLease.MovementKey.class) : EnumSet.copyOf(desired);
            jumping.add(MovementInputLease.MovementKey.JUMP); desired = Set.copyOf(jumping);
        }
        if (lease == null) lease = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
        lease.setDesired(owner, desired);
        if (!allowed.getAsBoolean() || !lease.heartbeat(owner, System.nanoTime(), HORIZON))
            return finish(MinecraftActionPrimitiveExecutor.Status.FAILED, MinecraftActionPrimitiveExecutor.Reason.HARD_DEADLINE);
        AgentInputState.global().requireNavigationMovementSafety(player, player.level(), map.worldRevision(), remaining,
                new AgentInputState.NavigationIntent(target, plan.mode() == Locomotion.DROP ? -1 : 0, plan.mode(), 0.18, plan));
        return new MinecraftActionPrimitiveExecutor.TickResult(MinecraftActionPrimitiveExecutor.Status.RUNNING, MinecraftActionPrimitiveExecutor.Reason.NONE);
    }
    private MinecraftActionPrimitiveExecutor.TickResult finish(MinecraftActionPrimitiveExecutor.Status status, MinecraftActionPrimitiveExecutor.Reason reason) {
        close(); return new MinecraftActionPrimitiveExecutor.TickResult(status, reason);
    }
    @Override public void close() { if (lease != null) { lease.close(); lease = null; } }
}
