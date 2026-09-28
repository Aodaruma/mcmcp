package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.MinecraftActionPrimitiveExecutor;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One native entity interaction, using the actual crosshair and no speculative retry. */
final class MinecraftV2EntityInteractDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final AgentObservations observations;
    private final V2EntityInteractArguments request;
    private final Entity entity;
    private final UUID entityId;
    private final UUID session;
    private final V2ClickArguments.EntityTarget crosshairTarget;
    private LocalPlayer player;
    private ClientLevel level;
    private V2HeldItemSelection selection;
    private V2HeldStackEvidence held;
    private MinecraftActionPrimitiveExecutor facing;
    private boolean begun;
    private boolean dispatched;
    private boolean clientConsumed;
    private String failure;

    MinecraftV2EntityInteractDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, V2EntityInteractArguments request, Entity entity) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.sessions = Objects.requireNonNull(sessions);
        this.observations = Objects.requireNonNull(observations);
        this.request = Objects.requireNonNull(request);
        this.entity = Objects.requireNonNull(entity);
        entityId = entity.getUUID();
        session = sessions.get().worldSessionId();
        crosshairTarget = new V2ClickArguments.EntityTarget(entityId, request.type());
    }

    @Override
    public void begin(long tick, BooleanSupplier outputAllowed) {
        if (begun) throw new IllegalStateException("entity interaction already begun");
        begun = true;
        player = minecraft.player;
        level = minecraft.level;
        if (!minecraft.isSameThread() || !outputAllowed.getAsBoolean() || !contextValid()
                || player.isHandsBusy() || !targetValid()) {
            failure = "entity_unavailable";
            return;
        }
        selection = new V2HeldItemSelection(minecraft, session, request.item());
        if (!selection.begin(tick, true)) failure = "item_not_available";
    }

    @Override
    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier outputAllowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!outputAllowed.getAsBoolean() || !contextValid() || !selection.stillSelected()) {
            return fail("entity_context_changed");
        }
        if (dispatched) {
            captureStack();
            // Entity interactions have no prediction ACK. Never report a fabricated server ACK.
            boolean complete = request.resultItem() == null
                    ? clientConsumed || held.changed() : held.matches(request.resultItem());
            return complete ? V2OperationJobExecution.Step.CONFIRMED : V2OperationJobExecution.Step.RUNNING;
        }
        if (!targetValid()) return fail("entity_unavailable");
        var staging = selection.poll(tick);
        if (staging == V2InventorySwap.Result.WAITING) return V2OperationJobExecution.Step.RUNNING;
        if (staging != V2InventorySwap.Result.CONFIRMED) return fail("item_swap_not_confirmed");
        if (!selection.matchesRequested() || player.isHandsBusy()) return fail("item_changed_before_use");
        if (!(minecraft.hitResult instanceof EntityHitResult currentHit)
                || currentHit.getEntity() != entity
                || !V2ClickArguments.targetMatches(minecraft, crosshairTarget)
                || !entity.getBoundingBox().inflate(0.1D).contains(currentHit.getLocation())) {
            var currentSession = sessions.get();
            var map = observations.requireAgentMap(currentSession);
            if (facing == null) {
                // Re-aim from currently visible geometry as a moving entity changes position.
                var aim = entity.getBoundingBox().getCenter();
                var cell = BlockPos.containing(aim);
                facing = new MinecraftActionPrimitiveExecutor(
                        McmcpClientConfig.maxCameraDegreesPerSecond() / 20.0F);
                facing.beginFace(new MinecraftActionPrimitiveExecutor.KnownFaceTarget(session,
                        map.worldRevision(), new ActionDsl.Position(currentSession.dimension(),
                                cell.getX(), cell.getY(), cell.getZ()),
                        aim.x, aim.y, aim.z, true), 60);
            }
            var result = facing.tick(minecraft, map, LocalObservationVolume.global(),
                    0.0D, 1_080.0D, tick, outputAllowed);
            if (result.status() == MinecraftActionPrimitiveExecutor.Status.FAILED) {
                return fail("entity_aim_failed");
            }
            if (result.status() != MinecraftActionPrimitiveExecutor.Status.RUNNING) closeFacing();
            return V2OperationJobExecution.Step.RUNNING;
        }
        closeFacing();
        if (!outputAllowed.getAsBoolean() || !targetValid()) return fail("entity_unavailable");
        held = new V2HeldStackEvidence(StackFingerprint.fromServerPacket(player.getMainHandItem()),
                HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session).revision());
        dispatched = true; // Even a throwing native call must never be replayed.
        var hit = (EntityHitResult) minecraft.hitResult;
        var result = minecraft.gameMode.interact(player, entity, hit, InteractionHand.MAIN_HAND);
        clientConsumed = result.consumesAction();
        return V2OperationJobExecution.Step.RUNNING;
    }

    private boolean contextValid() {
        return player != null && level != null && minecraft.player == player && minecraft.level == level
                && session.equals(sessions.get().worldSessionId()) && minecraft.gameMode != null
                && minecraft.getConnection() != null && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty();
    }

    private boolean targetValid() {
        return entity.isAlive() && entity.getUUID().equals(entityId) && level.getEntity(entity.getId()) == entity
                && player.isWithinEntityInteractionRange(entity, 0.0D) && player.hasLineOfSight(entity)
                && level.getWorldBorder().isWithinBounds(entity.blockPosition())
                && (request.type() == null || request.type().equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
    }

    private void captureStack() {
        if (held == null || minecraft.player != player || minecraft.level != level
                || !selection.stillSelected()) return;
        held.observe(HotbarPayloadSyncSignals.global().bindAndSnapshot(level, session)
                .slots().get(selection.slot()), StackFingerprint.fromServerPacket(player.getMainHandItem()));
    }

    private V2OperationJobExecution.Step fail(String code) {
        failure = code;
        return V2OperationJobExecution.Step.FAILED;
    }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", "entity");
        result.put("entity_ref", request.ref());
        result.put("dispatched", dispatched);
        result.put("client_consumed", clientConsumed);
        result.put("confirmation", held != null && held.after() != null ? "server_held_item"
                : dispatched ? "client_dispatch" : "none");
        result.put("effect_confirmed", held != null && held.changed());
        if (selection != null) {
            result.put("inventory_layout_may_have_changed", selection.layoutMayHaveChanged());
            if (selection.slot() >= 0) result.put("slot", selection.slot());
        }
        if (held != null && held.after() != null) result.put("held_after", Map.of(
                "item", held.after().itemId(), "count", held.after().count()));
        if (failure != null) result.put("failure", failure);
        return result;
    }

    private void closeFacing() {
        if (facing != null) { facing.close(); facing = null; }
    }

    @Override
    public void close() {
        closeFacing();
        captureStack();
        if (selection != null) selection.close();
    }
}
