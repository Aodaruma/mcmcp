package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Uses only the bounded camera executor. Never dispatches use, attack or movement. */
final class MinecraftV2LookDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final V2LookArguments request;
    private final MinecraftActionPrimitiveExecutor facing = new MinecraftActionPrimitiveExecutor(
            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
    private String reason = "aiming";

    MinecraftV2LookDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, V2LookArguments request) {
        this.minecraft = minecraft;
        this.sessions = sessions;
        this.observations = observations;
        this.request = request;
    }

    @Override
    public void begin(long clientTick, BooleanSupplier outputAllowed) {
        var session = sessions.get();
        var map = observations.requireAgentMap(session);
        var cell = BlockPos.containing(request.x(), request.y(), request.z());
        facing.beginFace(new MinecraftActionPrimitiveExecutor.KnownFaceTarget(session.worldSessionId(),
                map.worldRevision(), new ActionDsl.Position(session.dimension(), cell.getX(), cell.getY(), cell.getZ()),
                request.x(), request.y(), request.z(), true), request.maxTicks());
    }

    @Override
    public V2OperationJobExecution.Step tick(long clientTick, BooleanSupplier outputAllowed) {
        var step = facing.tick(minecraft, observations.requireAgentMap(sessions.get()),
                LocalObservationVolume.global(), 0, 1_080, clientTick, outputAllowed);
        reason = step.reason().name().toLowerCase(java.util.Locale.ROOT);
        return switch (step.status()) {
            case RUNNING -> V2OperationJobExecution.Step.RUNNING;
            case SUCCEEDED -> V2OperationJobExecution.Step.CONFIRMED;
            case FAILED, REPLAN_REQUIRED -> V2OperationJobExecution.Step.FAILED;
        };
    }

    @Override
    public Map<String, Object> result() { return Map.of("operation", "look", "look_reason", reason); }

    @Override
    public void close() { facing.close(); }
}
