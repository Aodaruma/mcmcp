package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.client.AgentInputState;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One retained support, one crouched edge approach, one ordinary placement, one safe landing. */
public final class MinecraftFloorExtensionAttempt implements AutoCloseable {
    public static final int MAX_TICKS = 400;
    private final Minecraft minecraft;
    private final LocalPlayer player;
    private final ClientLevel level;
    private final UUID sessionId;
    private final long deadline;
    private final KnownPillarUpRequest source;
    private final Direction direction;
    private final BlockPos support;
    private final BlockPos target;
    private final MinecraftObservationService observations;
    private final MinecraftApplyBlockPlanPort placementPort;
    private final ClientReconciliationSignals reconciliations;
    private final long correctionRevision;
    private final float health;
    private final UUID owner = UUID.randomUUID();
    private final ArrayList<KnownConstructionAttempt.EffectDelta> effects = new ArrayList<>();
    private MovementInputLease movement;
    private KnownConstructionAttempt construction;
    private Phase phase = Phase.ORIENT;
    private float expectedYaw;
    private float expectedPitch;
    private boolean confirmed;
    private boolean closed;
    private int placedDelta;
    private long lastMovementHeartbeatTick;
    private Result leaseExpiryIntent;

    public MinecraftFloorExtensionAttempt(Minecraft minecraft, WorldSessionTracker.Snapshot session,
            KnownPillarUpRequest source, Direction direction, MinecraftObservationService observations,
            MinecraftApplyBlockPlanPort placementPort, ClientReconciliationSignals reconciliations) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.player = Objects.requireNonNull(minecraft.player);
        this.level = Objects.requireNonNull(minecraft.level);
        this.sessionId = Objects.requireNonNull(session.worldSessionId());
        this.source = Objects.requireNonNull(source);
        this.direction = Objects.requireNonNull(direction);
        if (direction.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("horizontal direction required");
        this.support = new BlockPos(source.support().x(), source.support().y(), source.support().z());
        this.target = support.relative(direction);
        this.observations = Objects.requireNonNull(observations);
        this.placementPort = Objects.requireNonNull(placementPort);
        this.reconciliations = Objects.requireNonNull(reconciliations);
        this.correctionRevision = reconciliations.bindAndSnapshot(level, sessionId).positionCorrectionRevision();
        this.deadline = Math.addExact(session.clientTick(), MAX_TICKS);
        this.health = player.getHealth();
        requireOrdinaryFloorPhysics(SafeConstructionBlockPolicy.requireExpectedState(source.sourceState()));
        expectedYaw = player.getYRot(); expectedPitch = player.getXRot();
        requireSafety(session);
        if (Math.abs(player.getX() - support.getX() - 0.5) > 0.15
                || Math.abs(player.getZ() - support.getZ() - 0.5) > 0.15
                || !level.getBlockState(target).isAir() || !settled())
            throw new IllegalStateException("floor extension requires centered stationary support and empty target");
        // Preflight resources before moving; the placement adapter still stages and rechecks its slot.
        if (player.getInventory().getNonEquipmentItems().stream().noneMatch(stack ->
                !stack.isEmpty() && source.item().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        && stack.getComponentsPatch().isEmpty()))
            throw new IllegalStateException("floor extension material unavailable");
    }

    public Result tick(WorldSessionTracker.Snapshot session) {
        if (closed) throw new IllegalStateException("floor extension closed");
        long tickStartedNanos = System.nanoTime();
        Phase tickPhase = phase;
        try {
            // A stalled client tick must stop before camera/placement work. This check does not
            // renew the deadline or republish the previous desired movement.
            if (movement != null) {
                captureLeaseExpiry(session.clientTick(), tickPhase, "tick_entry", tickStartedNanos, tickStartedNanos);
                if (!movement.validate(owner, tickStartedNanos)) return finishLeaseExpiry();
            }
            requireSafety(session);
            if (session.clientTick() >= deadline) return failed("floor_extension_deadline");
            if (movement == null) {
                movement = MovementInputLease.acquire(minecraft, owner, System.nanoTime(), Duration.ofSeconds(2));
                lastMovementHeartbeatTick = session.clientTick();
            }
            keys(false);
            switch (phase) {
                case ORIENT -> { if (turn(outwardYaw() + 180, 80)) phase = Phase.LEAN; }
                case LEAN -> {
                    // Release before the edge goal: ordinary crouch input retains braking inertia.
                    if (progress() < 0.55) keys(true);
                    else if (settled()) phase = Phase.AIM;
                }
                case AIM -> {
                    Vec3 point = new Vec3(support.getX() + 0.5 + direction.getStepX() * 0.5,
                            support.getY() + 0.75, support.getZ() + 0.5 + direction.getStepZ() * 0.5);
                    Vec3 delta = point.subtract(player.getEyePosition());
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    if (turn(yaw, pitch) && settled() && player.isShiftKeyDown()) {
                        // The retained UP witness authorizes only this bounded approach. Acquire
                        // the real side hit before making a construction witness; the adapter
                        // separately requires current rendered fog/LOS and rechecks the hit at use.
                        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
                        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
                                || !support.equals(blockHit.getBlockPos()) || blockHit.getDirection() != direction)
                            return failed("floor_extension_side_not_visible");
                        long until = Math.min(deadline, session.clientTick() + KnownConstructionAttempt.TICKS_PER_ENTRY);
                        construction = new KnownConstructionAttempt(placementPort, constructionRequest(), session.clientTick(), until);
                        phase = Phase.PLACE;
                    }
                }
                case PLACE -> {
                    var result = construction.tick(session.clientTick());
                    effects.addAll(result.effects()); placedDelta += result.placedDelta();
                    // The ordinary construction adapter temporarily owns/restores camera and slot.
                    expectedYaw = player.getYRot(); expectedPitch = player.getXRot();
                    if (result.status() == KnownConstructionAttempt.Status.FAILED) return failed(result.evidence());
                    if (result.status() == KnownConstructionAttempt.Status.SUCCEEDED) {
                        confirmed = result.confirmedEntries() == 1;
                        if (!confirmed) return failed("floor_extension_requires_new_confirmed_placement");
                        construction = null; phase = Phase.ADVANCE_ORIENT;
                    }
                }
                case ADVANCE_ORIENT -> { if (turn(outwardYaw() + 180, 80)) phase = Phase.ADVANCE; }
                case ADVANCE -> {
                    if (progress() < 0.90) keys(true);
                    else if (settled()) { close(); return new Result(Status.SUCCEEDED, "floor_extension_complete"); }
                }
            }
            long outputNanos = System.nanoTime();
            captureLeaseExpiry(session.clientTick(), tickPhase, "heartbeat", tickStartedNanos, outputNanos);
            if (!movement.heartbeat(owner, outputNanos, Duration.ofMillis(Math.min(40, Math.max(1, deadline - session.clientTick())) * 50)))
                return finishLeaseExpiry();
            lastMovementHeartbeatTick = session.clientTick();
            return new Result(Status.RUNNING, "floor_extension_" + phase.name().toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException | LinkageError rejected) {
            // Preserve the first expiry even if lease or construction cleanup throws. The
            // runtime retains this attempt and completes input release before publishing it.
            if (leaseExpiryIntent != null) return leaseExpiryIntent;
            return failed("floor_extension_safety_changed");
        }
    }

    private void requireSafety(WorldSessionTracker.Snapshot session) {
        if (!minecraft.isSameThread() || minecraft.player != player || minecraft.level != level
                || !session.worldReady() || !sessionId.equals(session.worldSessionId())
                || !source.support().dimension().equals(session.dimension())
                || minecraft.isPaused() || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || minecraft.gui.overlay() != null || minecraft.getConnection() == null
                || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL
                || player.containerMenu != player.inventoryMenu || player.isUsingItem()
                || !player.isAlive() || player.isDeadOrDying() || player.getHealth() < 10
                || player.getHealth() + 0.001F < health || player.hurtTime > 0 || player.getRemainingFireTicks() > 0
                || player.isPassenger() || player.isInWater() || player.isInLava() || !player.onGround()
                || player.isSprinting()
                || player.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.10001
                || player.getAttributeValue(Attributes.SNEAKING_SPEED) > 0.30001
                || Math.abs(player.getY() - support.getY() - 1) > 0.015
                || !withinCorridor(progress(), lateral(), confirmed)
                || reconciliations.bindAndSnapshot(level, sessionId).positionCorrectionRevision() != correctionRevision)
            throw new IllegalStateException("floor extension safety boundary changed");
        for (BlockPos cell : List.of(support, target, support.above(), support.above(2), target.above(), target.above(2))) {
            if (!level.isLoaded(cell) || !level.isInsideBuildHeight(cell.getY())
                    || !level.getWorldBorder().isWithinBounds(cell))
                throw new IllegalStateException("floor extension cells unavailable");
        }
        BlockState liveSupport = level.getBlockState(support);
        requireOrdinaryFloorPhysics(liveSupport);
        KnownPillarUpRequest.requireLiveSupport(liveSupport, level.getBlockEntity(support) != null);
        if (!source.expectedSupport().equals(fingerprint(liveSupport))
                || !Block.isShapeFullBlock(liveSupport.getCollisionShape(level, support))
                || !liveSupport.isFaceSturdy(level, support, Direction.UP))
            throw new IllegalStateException("floor extension support changed");
        if ((phase == Phase.ORIENT || phase == Phase.LEAN || phase == Phase.AIM)
                && !level.getBlockState(target).isAir())
            throw new IllegalStateException("floor extension air target changed");
        if (confirmed) {
            BlockState floor = level.getBlockState(target);
            requireOrdinaryFloorPhysics(floor);
            SafeConstructionBlockPolicy.requireLiveState(floor, level.getBlockEntity(target) != null);
            if (!source.sourceState().equals(fingerprint(floor))
                    || !Block.isShapeFullBlock(floor.getCollisionShape(level, target))
                    || !floor.isFaceSturdy(level, target, Direction.UP))
                throw new IllegalStateException("confirmed floor changed");
        }
        // Only the two-cell swept body volume is inspected; it is never returned as world knowledge.
        for (BlockPos air : List.of(support.above(), support.above(2), target.above(), target.above(2))) {
            if (!level.getBlockState(air).isAir()) throw new IllegalStateException("floor extension corridor obstructed");
        }
        if (!level.noCollision(player, player.getBoundingBox())
                || level.getEntities(player, player.getBoundingBox().inflate(8),
                    entity -> entity.isAlive() && (entity instanceof Enemy || entity instanceof Mob mob && mob.getTarget() == player))
                    .stream().anyMatch(entity -> observations.isEntityCurrentlyVisible(minecraft, entity, 8)))
            throw new IllegalStateException("floor extension collision or threat");
        if (phase != Phase.PLACE && (Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw)) > 0.25
                || Math.abs(player.getXRot() - expectedPitch) > 0.25))
            throw new IllegalStateException("floor extension view ownership changed");
    }

    /** Retains at least 0.07 blocks of the 0.6-wide body over the original full-cube support. */
    public static boolean withinCorridor(double progress, double lateral, boolean floorConfirmed) {
        return Double.isFinite(progress) && Double.isFinite(lateral)
                && Math.abs(lateral) <= 0.18 && progress >= -0.16
                && progress <= (floorConfirmed ? 1.12 : 0.73);
    }

    private static void requireOrdinaryFloorPhysics(BlockState state) {
        // Edge braking distances assume ordinary floor friction, not ice or slow/bouncy surfaces.
        if (Math.abs(state.getBlock().getFriction() - 0.6F) > 0.0001F
                || Math.abs(state.getBlock().getSpeedFactor() - 1.0F) > 0.0001F)
            throw new IllegalStateException("floor extension requires ordinary floor physics");
    }

    private double progress() {
        return (player.getX() - support.getX() - 0.5) * direction.getStepX()
                + (player.getZ() - support.getZ() - 0.5) * direction.getStepZ();
    }
    private double lateral() {
        return (player.getX() - support.getX() - 0.5) * direction.getStepZ()
                - (player.getZ() - support.getZ() - 0.5) * direction.getStepX();
    }
    private boolean settled() { return player.getDeltaMovement().horizontalDistanceSqr() < 0.000025; }
    private float outwardYaw() { return switch (direction) { case NORTH -> 180; case SOUTH -> 0; case EAST -> -90; case WEST -> 90; default -> throw new AssertionError(); }; }
    private boolean turn(float yaw, float pitch) {
        float dy = Mth.wrapDegrees(yaw - player.getYRot()), dp = pitch - player.getXRot();
        player.turn(Mth.clamp(dy, -8, 8) / 0.15D, Mth.clamp(dp, -8, 8) / 0.15D);
        expectedYaw = player.getYRot(); expectedPitch = player.getXRot();
        return Math.abs(Mth.wrapDegrees(yaw - expectedYaw)) < 0.2 && Math.abs(pitch - expectedPitch) < 0.2;
    }
    private void keys(boolean forward) {
        movement.setDesired(owner, forward ? Set.of(MovementInputLease.MovementKey.CROUCH, MovementInputLease.MovementKey.BACK)
                : Set.of(MovementInputLease.MovementKey.CROUCH));
    }
    private KnownConstructionRequest constructionRequest() {
        var targetCell = new BlockTarget(source.support().dimension(), target.getX(), target.getY(), target.getZ());
        var step = new ApplyBlockPlanStep("floor", ApplyBlockPlanOperation.PLACE, targetCell,
                new BlockStateFingerprint("minecraft:air", Map.of()), source.sourceState(), Optional.of(source.item()),
                Optional.of(PlacementSupportWitness.visible(source.support(), direction.getName(), source.expectedSupport())));
        return new KnownConstructionRequest(new ApplyBlockPlanRequest(
                "floor_extension_" + owner.toString().replace("-", ""), 1, 1, List.of(step),
                new ActionBounds(targetCell.dimension(), targetCell, targetCell, 0, 15, false),
                ApplyBlockPlanRequest.BreakSafety.SAFE_BREAK_SOURCE, ApplyBlockPlanRequest.StandPolicy.RETAINED_EDGE_SUPPORT));
    }
    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }
    public List<KnownConstructionAttempt.EffectDelta> drainEffects() {
        // tick results drain their own effects. This collects only new effects produced by close
        // (for example a cancelled, not-yet-confirmed use), exactly as the ordinary runtime does.
        if (construction != null) effects.addAll(construction.drainEffectDeltas());
        var result = List.copyOf(effects); effects.clear(); return result;
    }
    public int drainPlacedDelta() { int result = placedDelta; placedDelta = 0; return result; }
    private void captureLeaseExpiry(long clientTick, Phase tickPhase, String checkpoint, long startedNanos, long nowNanos) {
        long overdueNanos = AgentInputState.global().watchdogTime(nowNanos) - movement.deadlineNanos();
        if (overdueNanos < 0 || leaseExpiryIntent != null) return;
        var diagnostics = List.of(
                "floor_extension_phase=" + tickPhase.name().toLowerCase(java.util.Locale.ROOT),
                "floor_extension_lease_check=" + checkpoint,
                "floor_extension_lease_overdue_ms=" + overdueNanos / 1_000_000,
                "floor_extension_lease_tick_gap=" + Math.max(0, clientTick - lastMovementHeartbeatTick),
                "floor_extension_executor_ms=" + Math.max(0, nowNanos - startedNanos) / 1_000_000);
        leaseExpiryIntent = new Result(Status.FAILED, "floor_extension_input_lease_expired", diagnostics);
    }
    private Result finishLeaseExpiry() {
        close();
        return Objects.requireNonNull(leaseExpiryIntent);
    }
    private Result failed(String evidence) { close(); return new Result(Status.FAILED, evidence); }
    @Override public void close() {
        if (closed) return;
        try { if (construction != null) construction.close(); }
        finally { if (movement != null) movement.close(); }
        closed = true;
    }
    private enum Phase { ORIENT, LEAN, AIM, PLACE, ADVANCE_ORIENT, ADVANCE }
    public enum Status { RUNNING, SUCCEEDED, FAILED }
    public record Result(Status status, String evidence, List<String> diagnostics) {
        public Result(Status status, String evidence) { this(status, evidence, List.of()); }
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(evidence);
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.size() > 5 || diagnostics.stream().anyMatch(value -> value.length() > 128)
                    || !diagnostics.isEmpty() && (status != Status.FAILED
                            || !evidence.equals("floor_extension_input_lease_expired")))
                throw new IllegalArgumentException("invalid floor lease diagnostics");
        }
    }
}
