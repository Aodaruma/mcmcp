package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.safety.Locomotion;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor.*;

/** Final bounded sub-cell approach after an observed route, using the normal movement guards. */
public final class ExactPositionPrimitive implements AutoCloseable {
    private final Vec3 feet;
    private final double tolerance;
    private final boolean sneak;
    private final UUID owner = UUID.randomUUID();
    private final FlightInputSequence flight = new FlightInputSequence();
    private MovementInputLease lease;
    private long started = -1;
    private int settled;
    private Vec3 previous;

    public ExactPositionPrimitive(Vec3 feet, double tolerance, boolean sneak) {
        this.feet = feet; this.tolerance = tolerance; this.sneak = sneak;
    }

    public static boolean reached(Vec3 current, Vec3 target, double tolerance, double radius) {
        return Math.hypot(current.x - target.x, current.z - target.z) <= radius + tolerance
                && Math.abs(current.y - target.y) <= radius + 0.20;
    }

    public TickResult tick(Minecraft minecraft, KnownTraversabilitySnapshot map, double remaining,
            long tick, BooleanSupplier allowed) {
        var player = minecraft.player;
        var mode = Locomotion.aerialMode(player);
        if (!mode.aerial()) mode = player.isInWater() ? Locomotion.WATER : Locomotion.GROUND;
        if (started < 0) started = tick;
        if (!allowed.getAsBoolean()) return finish(Status.FAILED, Reason.HARD_DEADLINE);
        if (remaining <= 0) return finish(Status.FAILED, Reason.MOTION_BUDGET_EXHAUSTED);
        if (tick - started > 100) return finish(Status.FAILED, Reason.PRIMITIVE_TICK_BUDGET_EXHAUSTED);
        if (player.position().distanceTo(feet) > 2.0
                || AgentInputState.global().consumeGoalMovementRejection())
            return finish(Status.FAILED, Reason.UNVERIFIED_MOVEMENT_VECTOR);
        boolean reached = reached(player.position(), feet, tolerance, 0)
                && (mode.aerial() || mode == Locomotion.WATER || player.onGround());
        boolean breathing = mode != Locomotion.WATER || waterBreathingRecovered(
                player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), player.getAirSupply(), player.getMaxAirSupply());
        boolean stable = previous != null && previous.distanceToSqr(player.position()) < 0.0001;
        previous = player.position();
        settled = reached && stable && breathing ? settled + 1 : 0;
        if (settled >= 4) return finish(Status.SUCCEEDED, Reason.NONE);
        boolean fineFlight = mode.aerial() && fineFlightApproach(player.position(), feet);
        // Short pulses from rest avoid a coarse flight impulse reversing around a sub-cell target.
        // This removes only our tracked contribution; external motion keeps its normal guards.
        if (fineFlight) AgentInputState.global().neutralizeTrackedAgentVelocity(player);
        double brake = mode.aerial() && !fineFlight ? 11 : mode == Locomotion.WATER ? 3 : 0;
        var desired = steering(player.getX() + brake * player.getDeltaMovement().x,
                player.getZ() + brake * player.getDeltaMovement().z, player.getYRot(), feet.x, feet.z, tolerance);
        if (reached && mode != Locomotion.WATER) {
            AgentInputState.global().neutralizeTrackedAgentVelocity(player);
            desired = Set.of();
        } else desired = withVerticalInput(desired, 0, feet.y - player.getY(), player.maxUpStep(),
                mode, player.isInWater(), player.getDeltaMovement().y);
        if (mode.aerial() && tick - started < 8 && desired.contains(MovementInputLease.MovementKey.JUMP)) {
            // The preceding route may just have pulsed Space; do not toggle vanilla flight off.
            var keys = EnumSet.copyOf(desired); keys.remove(MovementInputLease.MovementKey.JUMP); desired = Set.copyOf(keys);
        }
        if (mode.aerial()) desired = flight.apply(desired, tick);
        if (sneak && mode == Locomotion.GROUND) {
            var keys = desired.isEmpty() ? EnumSet.noneOf(MovementInputLease.MovementKey.class) : EnumSet.copyOf(desired);
            keys.add(MovementInputLease.MovementKey.CROUCH); desired = Set.copyOf(keys);
        }
        if (lease == null) lease = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), Duration.ofMillis(500));
        lease.setDesired(owner, desired);
        if (!allowed.getAsBoolean() || !lease.heartbeat(owner, System.nanoTime(), Duration.ofMillis(500)))
            return finish(Status.FAILED, Reason.HARD_DEADLINE);
        if (mode == Locomotion.GROUND) AgentInputState.global().requireGoalMovementSafety(
                player, player.level(), map.worldRevision(), remaining);
        else AgentInputState.global().requireNavigationMovementSafety(player, player.level(), map.worldRevision(), remaining,
                new AgentInputState.NavigationIntent(feet.add(0, player.getBbHeight() * 0.5, 0), 0, mode, tolerance));
        return new TickResult(Status.RUNNING, Reason.NONE);
    }

    static boolean fineFlightApproach(Vec3 position, Vec3 target) {
        return Math.hypot(position.x - target.x, position.z - target.z) <= 0.75;
    }

    private TickResult finish(Status status, Reason reason) { close(); return new TickResult(status, reason); }
    @Override public void close() { if (lease != null) { lease.close(); lease = null; } }
}
