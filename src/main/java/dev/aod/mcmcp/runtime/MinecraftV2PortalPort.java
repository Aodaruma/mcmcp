package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.action.V2BlockAimResolver;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.routine.MovementInputLease;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Uses ordinary camera/forward input; never teleports, edits blocks, or dispatches packets. */
final class MinecraftV2PortalPort implements V2PortalJobExecution.Port {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final ClientReconciliationSignals reconciliation;
    private final V2PortalArguments request;
    private final UUID owner = UUID.randomUUID();
    private final MinecraftActionPrimitiveExecutor facing = new MinecraftActionPrimitiveExecutor(
            McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
    private LocalPlayer player;
    private ClientLevel level;
    private UUID session;
    private MovementInputLease movement;
    private boolean failed;
    private boolean faced;
    private boolean contact;
    private int entryTicks;
    private long lastContactNanos = Long.MIN_VALUE;

    MinecraftV2PortalPort(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, ClientReconciliationSignals reconciliation, V2PortalArguments request) {
        this.minecraft = minecraft;
        this.sessions = sessions;
        this.observations = observations;
        this.reconciliation = reconciliation;
        this.request = request;
    }

    @Override public void begin(long tick, BooleanSupplier allowed) {
        player = minecraft.player;
        level = minecraft.level;
        session = sessions.get().worldSessionId();
        var p = request.geometry().portal();
        var frame = observations.latestInternalFrame();
        if (!allowed.getAsBoolean() || !contextValid() || !player.onGround()
                || !request.geometry().nearby(player.getBoundingBox()) || frame.isEmpty()) { failed = true; return; }
        var ledger = reconciliation.bindAndSnapshot(level, session);
        var aim = V2BlockAimResolver.resolve(frame.orElseThrow(),
                new NavCell(request.sourceDimension(), p.getX(), p.getY(), p.getZ()), null,
                player.getEyePosition(), session, tick, ledger.worldRevision(),
                ledger.surfaceBarrierWorldRevision(p.getX(), p.getY(), p.getZ()),
                request.geometry().blockId()::equals);
        if (aim.isEmpty()) { failed = true; return; }
        facing.beginFace(approachFacingTarget(request, session, ledger.worldRevision(), player.getEyeY()),
                Math.min(80, request.maxTicks()));
    }

    static MinecraftActionPrimitiveExecutor.KnownFaceTarget approachFacingTarget(
            V2PortalArguments request, UUID session, long revision, double eyeY) {
        var p = request.geometry().portal();
        // The walk direction is level with the eye; the observed portal may be at the feet.
        var aimCell = net.minecraft.core.BlockPos.containing(p.getX() + 0.5, eyeY, p.getZ() + 0.5);
        return new MinecraftActionPrimitiveExecutor.KnownFaceTarget(session, revision,
                new ActionDsl.Position(request.sourceDimension(), aimCell.getX(), aimCell.getY(), aimCell.getZ()),
                p.getX() + 0.5, eyeY, p.getZ() + 0.5, true);
    }

    private boolean contextValid() {
        return player != null && level != null && minecraft.player == player && minecraft.level == level
                && session != null && session.equals(sessions.get().worldSessionId())
                && minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL
                && !player.isPassenger() && !player.isFallFlying() && !player.isUsingItem()
                && level.isLoaded(request.geometry().portal())
                && level.getBlockState(request.geometry().portal()).is(request.geometry().nether() ? Blocks.NETHER_PORTAL : Blocks.END_PORTAL);
    }

    @Override public boolean currentPositionSafe() {
        if (!contextValid()) return false;
        long revision = reconciliation.bindAndSnapshot(level, session).worldRevision();
        return LocalObservationVolume.global().verifiesPortalResolvedMovement(player, Vec3.ZERO, Vec3.ZERO,
                player.tickCount, revision, request.geometry());
    }

    @Override public V2PortalJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (failed || !allowed.getAsBoolean() || !contextValid()) return V2PortalJobExecution.Step.FAILED;
        if (!faced) {
            var result = facing.tick(minecraft, observations.requireAgentMap(sessions.get()),
                    LocalObservationVolume.global(), 0, 1_080, tick, allowed);
            if (result.status() == MinecraftActionPrimitiveExecutor.Status.RUNNING) return V2PortalJobExecution.Step.RUNNING;
            facing.close();
            if (result.status() != MinecraftActionPrimitiveExecutor.Status.SUCCEEDED) return V2PortalJobExecution.Step.FAILED;
            faced = true;
        }
        if (!currentPositionSafe()) return V2PortalJobExecution.Step.FAILED;
        contactObserved();
        if (contact || request.geometry().horizontalDistanceSquared(player.getBoundingBox()) <= 0.01) {
            releaseMovement();
            return V2PortalJobExecution.Step.RUNNING;
        }
        if (++entryTicks > 40 || !allowed.getAsBoolean()) return V2PortalJobExecution.Step.FAILED;
        if (movement == null) movement = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), Duration.ofMillis(250));
        movement.setDesired(owner, Set.of(MovementInputLease.MovementKey.FORWARD));
        if (!movement.heartbeat(owner, System.nanoTime(), Duration.ofMillis(250))) return V2PortalJobExecution.Step.FAILED;
        long revision = reconciliation.bindAndSnapshot(level, session).worldRevision();
        AgentInputState.global().requirePortalMovementSafety(player, level, revision, 0.7, request.geometry());
        return V2PortalJobExecution.Step.RUNNING;
    }

    @Override public boolean contactObserved() {
        if (minecraft.player == player && minecraft.level == level && !contextValid()) {
            contact = false;
            lastContactNanos = Long.MIN_VALUE;
            return false;
        }
        if (contextValid() && request.geometry().contact(player.getBoundingBox())) {
            contact = true;
            lastContactNanos = System.nanoTime();
        }
        return contact && lastContactNanos != Long.MIN_VALUE
                && System.nanoTime() - lastContactNanos <= 500_000_000L;
    }

    private void releaseMovement() {
        if (movement != null) { movement.close(); movement = null; }
    }

    @Override public void close() {
        try { facing.close(); } finally { releaseMovement(); }
    }
}
