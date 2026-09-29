package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.CoordinateMoveJobExecution;
import dev.aod.mcmcp.agent.navigation.KnownTraversabilitySnapshot;
import dev.aod.mcmcp.agent.navigation.RoutePlan;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Prepares the actual vanilla posture, then executes only observed routes. */
final class MinecraftV2MoveDriver implements CoordinateMoveJobExecution.MovementDriver {
    private static final Duration HORIZON = Duration.ofMillis(500);
    private final Minecraft minecraft;
    private final V2MoveArguments request;
    private final UUID owner = UUID.randomUUID();
    private final MinecraftActionPrimitiveExecutor executor = new MinecraftActionPrimitiveExecutor(
            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
    private MovementInputLease posture;
    private long postureStarted = -1;
    private RoutePlan pendingRoute;
    private double pendingTolerance;

    MinecraftV2MoveDriver(Minecraft minecraft, V2MoveArguments request) {
        this.minecraft = minecraft;
        this.request = request;
        executor.sneakWhileMoving(request.sneak());
    }

    @Override
    public boolean prepare(long clientTick, BooleanSupplier outputAllowed) {
        if (!request.sneak()) return true;
        if (!outputAllowed.getAsBoolean()) return false;
        if (posture == null) {
            posture = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), HORIZON);
            postureStarted = clientTick;
        }
        posture.setDesired(owner, Set.of(MovementInputLease.MovementKey.CROUCH));
        if (!posture.heartbeat(owner, System.nanoTime(), HORIZON))
            throw new IllegalStateException("posture lease expired");
        // Wait for vanilla and the local projector to observe the smaller actual body.
        return clientTick - postureStarted >= 3 && minecraft.player.getBoundingBox().getYsize() <= 1.51D;
    }

    @Override
    public void begin(RoutePlan route, double tolerance) {
        if (!route.edges().isEmpty()) {
            var from = route.cells().getFirst();
            var next = route.cells().get(1);
            if (from.x() != next.x() || from.z() != next.z()) {
                double x = minecraft.player.getX() + next.x() - from.x();
                double y = minecraft.player.getEyeY();
                double z = minecraft.player.getZ() + next.z() - from.z();
                var cell = BlockPos.containing(x, y, z);
                pendingRoute = route;
                pendingTolerance = tolerance;
                executor.beginFace(new MinecraftActionPrimitiveExecutor.KnownFaceTarget(
                        route.worldSessionId(), route.worldRevision(),
                        new ActionDsl.Position(route.dimension(), cell.getX(), cell.getY(), cell.getZ()),
                        x, y, z, true), 60);
                return;
            }
        }
        executor.beginNavigate(route, tolerance);
    }

    @Override
    public MinecraftActionPrimitiveExecutor.TickResult tick(KnownTraversabilitySnapshot map,
            double remainingDistance, long clientTick, BooleanSupplier outputAllowed) {
        if (pendingRoute != null && posture != null) prepare(clientTick, outputAllowed);
        else releasePosture();
        var step = executor.tick(minecraft, map, LocalObservationVolume.global(),
                remainingDistance, 1_080.0D, clientTick, outputAllowed);
        if (pendingRoute != null && step.status() == MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) {
            executor.beginNavigate(pendingRoute, pendingTolerance);
            pendingRoute = null;
            return new MinecraftActionPrimitiveExecutor.TickResult(
                    MinecraftActionPrimitiveExecutor.Status.RUNNING, MinecraftActionPrimitiveExecutor.Reason.NONE);
        }
        return step;
    }

    @Override
    public boolean active() { return executor.active(); }

    private void releasePosture() {
        if (posture != null) { posture.close(); posture = null; }
    }

    @Override
    public void close() {
        try { executor.close(); }
        finally { releasePosture(); pendingRoute = null; }
    }
}
