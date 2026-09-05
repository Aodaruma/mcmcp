package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.agent.observation.OmnidirectionalObserver;
import dev.aod.mcmcp.agent.observation.ClientFogDistanceSignals;
import dev.aod.mcmcp.client.AgentScreenPolicy;
import dev.aod.mcmcp.client.McmcpClientConfig;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.FrameDisplaySyncSignals;
import dev.aod.mcmcp.runtime.HotbarPayloadSyncSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Client-thread adapter for one displayed-item change; never attacks an empty frame. */
public final class MinecraftFrameItemPort implements FrameItemPort {
    private static final double MAX_VISIBLE_DISTANCE = 16.0D;
    private static final double POSITION_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float ROTATION_DRIFT = 0.1F;
    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final MinecraftObservationService observations;
    private final FrameDisplaySyncSignals displaySignals;
    private final ClientReconciliationSignals reconciliations = ClientReconciliationSignals.global();
    private final HotbarPayloadSyncSignals hotbarSignals = HotbarPayloadSyncSignals.global();
    private Request activeRequest;
    private LocalPlayer playerIdentity;
    private ClientLevel levelIdentity;
    private Object connectionIdentity;
    private ItemFrame target;
    private Vec3 initialPosition;
    private float initialHealth;
    private float expectedYaw;
    private float expectedPitch;
    private int originalSlot;
    private int selectedSlot = -1;
    private ItemStack selectedBefore = ItemStack.EMPTY;
    private List<StackFingerprint> inventoryBefore;
    private ClientReconciliationSignals.Snapshot initialReconciliation;
    private long lastAimTick = -1L;
    private long handSelectedTick = -1L;
    private long readyTick = -1L;
    private boolean dispatched;
    private boolean released = true;

    public MinecraftFrameItemPort(Supplier<Minecraft> minecraftSupplier,
                                  Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
                                  MinecraftObservationService observations,
                                  FrameDisplaySyncSignals displaySignals) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.displaySignals = Objects.requireNonNull(displaySignals, "displaySignals");
    }

    @Override
    public Frame prepare(Request request, long clientTick) {
        Minecraft minecraft = requireMinecraft();
        var session = sessionSupplier.get();
        if (released) initialize(minecraft, request);
        if (activeRequest != request) return failed(clientTick, "frame_request_authority_changed");
        String failure = safetyFailure(minecraft, session, request, clientTick);
        if (failure != null) return failed(clientTick, failure);
        return withCurrentFog(clientTick, () -> prepareVisible(request, clientTick, minecraft));
    }

    private Frame prepareVisible(Request request, long clientTick, Minecraft minecraft) {
        var resolved = observations.resolveLoadedEntityRefIdentity(minecraft, clientTick,
                request.worldSessionId(), request.dimension(), request.entityRef(), MAX_VISIBLE_DISTANCE);
        if (resolved.isEmpty() || !(resolved.orElseThrow() instanceof ItemFrame frame)
                || !supportedFrame(frame)) return failed(clientTick, "frame_reference_unavailable");
        if (target != null && target != frame) return failed(clientTick, "frame_identity_changed");
        target = frame;
        String failure = displayFailure(request, clientTick, true);
        if (failure != null) return failed(clientTick, failure);
        if (selectedSlot < 0) {
            selectedSlot = chooseHotbar(playerIdentity, request);
            if (selectedSlot == -2) return failed(clientTick, "frame_item_components_ambiguous");
            if (selectedSlot < 0) return failed(clientTick, request.mode() == Mode.REMOVE
                    ? "frame_empty_hotbar_required" : "frame_item_hotbar_required");
            selectedBefore = playerIdentity.getInventory().getItem(selectedSlot).copy();
            playerIdentity.getInventory().setSelectedSlot(selectedSlot);
            handSelectedTick = clientTick;
        }
        if (!handReady(request)) return failed(clientTick, "frame_hand_changed");
        boolean aligned = align(request, clientTick);
        boolean ready = aligned && clientTick > lastAimTick && clientTick > handSelectedTick
                && exactCrosshair(minecraft, target)
                && (request.mode() != Mode.REMOVE || playerIdentity.getAttackStrengthScale(0.0F) >= 0.99F);
        if (ready) readyTick = clientTick;
        return sample(clientTick, ready, null);
    }

    @Override
    public Frame observe(Request request, long clientTick) {
        Minecraft minecraft = requireMinecraft();
        if (released || activeRequest != request || !dispatched) {
            return failed(clientTick, "frame_attempt_authority_lost");
        }
        String failure = safetyFailure(minecraft, sessionSupplier.get(), request, clientTick);
        if (failure != null) return failed(clientTick, failure);
        return withCurrentFog(clientTick, () ->
                sample(clientTick, false, displayFailure(request, clientTick, false)));
    }

    @Override
    public void dispatch(Request request, long clientTick) {
        Minecraft minecraft = requireMinecraft();
        if (released || activeRequest != request || dispatched || readyTick != clientTick
                || safetyFailure(minecraft, sessionSupplier.get(), request, clientTick) != null
                || ClientFogDistanceSignals.current(levelIdentity, playerIdentity, playerIdentity.tickCount).isEmpty()
                || displayFailure(request, clientTick, true) != null
                || !handReady(request) || !exactCrosshair(minecraft, target)) {
            throw new IllegalStateException("frame dispatch authority changed");
        }
        inventoryBefore = inventory(playerIdentity);
        dispatched = true;
        if (request.mode() == Mode.REMOVE) {
            // Empty hand prevents weapon enchantments or wide attack profiles from affecting neighbours.
            Objects.requireNonNull(minecraft.gameMode).attack(playerIdentity, target);
        } else {
            Objects.requireNonNull(minecraft.gameMode).interact(playerIdentity, target,
                    (EntityHitResult) minecraft.hitResult, InteractionHand.MAIN_HAND);
        }
        playerIdentity.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public boolean release() {
        Minecraft minecraft = requireMinecraft();
        if (released) return true;
        if (minecraft.player == playerIdentity && minecraft.level == levelIdentity
                && minecraft.getConnection() == connectionIdentity && selectedSlot >= 0
                && playerIdentity.getInventory().getSelectedSlot() == selectedSlot) {
            playerIdentity.getInventory().setSelectedSlot(originalSlot);
            if (playerIdentity.getInventory().getSelectedSlot() != originalSlot) return false;
        }
        // No key, use latch or attack latch is acquired here. The runtime owns the shared input fence.
        released = true;
        return true;
    }

    public void clearSession() {
        if (!release()) throw new IllegalStateException("frame release remains pending");
        hotbarSignals.closeLevel(levelIdentity);
        activeRequest = null;
        target = null;
        playerIdentity = null;
        levelIdentity = null;
        connectionIdentity = null;
        inventoryBefore = null;
        initialReconciliation = null;
    }

    private void initialize(Minecraft minecraft, Request request) {
        activeRequest = request;
        playerIdentity = minecraft.player;
        levelIdentity = minecraft.level;
        connectionIdentity = minecraft.getConnection();
        target = null;
        selectedSlot = -1;
        selectedBefore = ItemStack.EMPTY;
        inventoryBefore = null;
        initialReconciliation = null;
        lastAimTick = handSelectedTick = readyTick = -1L;
        dispatched = false;
        released = false;
        if (playerIdentity != null && levelIdentity != null) {
            initialPosition = playerIdentity.position();
            initialHealth = playerIdentity.getHealth();
            expectedYaw = playerIdentity.getYRot();
            expectedPitch = playerIdentity.getXRot();
            originalSlot = playerIdentity.getInventory().getSelectedSlot();
            initialReconciliation = reconciliations.bindAndSnapshot(levelIdentity, request.worldSessionId());
            displaySignals.bindAndSnapshot(levelIdentity, request.worldSessionId());
            hotbarSignals.bindAndSnapshot(levelIdentity, request.worldSessionId());
        }
    }

    private String safetyFailure(Minecraft minecraft, WorldSessionTracker.Snapshot session,
                                 Request request, long tick) {
        if (session == null || !session.worldReady() || session.clientTick() != tick
                || !request.worldSessionId().equals(session.worldSessionId())
                || !request.dimension().equals(session.dimension())
                || minecraft.player == null || minecraft.player != playerIdentity
                || minecraft.level == null || minecraft.level != levelIdentity
                || minecraft.getConnection() == null || minecraft.getConnection() != connectionIdentity) {
            return "frame_world_session_changed";
        }
        var player = playerIdentity;
        if (minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL
                || minecraft.isPaused() || minecraft.gui.overlay() != null
                || !AgentScreenPolicy.allowsWorldInput(minecraft.gui.screen())
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || !player.isAlive() || player.isDeadOrDying() || player.getHealth() < 10.0F
                || player.getHealth() + 0.001F < initialHealth || player.hurtTime > 0
                || player.getRemainingFireTicks() > 0 || player.isUsingItem() || player.isShiftKeyDown()
                || player.isPassenger() || !player.onGround() || player.isInWater() || player.isInLava()
                || player.getAirSupply() < player.getMaxAirSupply()
                || player.position().distanceToSqr(initialPosition) > POSITION_DRIFT_SQUARED
                || player.getDeltaMovement().horizontalDistanceSqr() > 0.01D
                || Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw)) > ROTATION_DRIFT
                || Math.abs(player.getXRot() - expectedPitch) > ROTATION_DRIFT) {
            return "frame_player_or_screen_unsafe";
        }
        var recon = reconciliations.bindAndSnapshot(levelIdentity, request.worldSessionId());
        if (recon.positionCorrectionRevision() != initialReconciliation.positionCorrectionRevision()
                || recon.rotationRevision() != initialReconciliation.rotationRevision()
                || recon.motionRevision() != initialReconciliation.motionRevision()) {
            return "frame_player_reconciled";
        }
        if (selectedSlot >= 0 && player.getInventory().getSelectedSlot() != selectedSlot) {
            return "frame_hand_selection_changed";
        }
        boolean threat = levelIdentity.getEntities(player, player.getBoundingBox().inflate(8.0D),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().anyMatch(entity -> observations.isEntityCurrentlyVisible(minecraft, entity, 8.0D));
        return threat ? "frame_visible_threat" : null;
    }

    private String displayFailure(Request request, long tick, boolean beforeDispatch) {
        if (target == null || !target.isAlive() || target.isRemoved()
                || levelIdentity.getEntity(target.getId()) != target) return "frame_body_unavailable";
        if (!playerIdentity.isWithinEntityInteractionRange(target, 0.0D)
                || (request.mode() == Mode.REMOVE
                    && !playerIdentity.isWithinAttackRange(ItemStack.EMPTY, target.getBoundingBox(), 0.0D))) {
            return "frame_out_of_reach";
        }
        long worldRevision = reconciliations.bindAndSnapshot(levelIdentity, request.worldSessionId()).worldRevision();
        var visible = OmnidirectionalObserver.currentFrameDisplay(
                levelIdentity, playerIdentity, target, tick, worldRevision,
                McmcpClientConfig.visualRadiusBlocks());
        if (visible.isEmpty()) return "frame_front_not_visible";
        var display = visible.orElseThrow();
        var point = display.aimPoint();
        var expected = request.frontAimPoint();
        if (Math.abs(point.x() - expected.x()) > 1.0e-5D
                || Math.abs(point.y() - expected.y()) > 1.0e-5D
                || Math.abs(point.z() - expected.z()) > 1.0e-5D) return "frame_aim_point_changed";
        if (display.rotation() != request.expectedRotation()) return "frame_rotation_changed";
        var ledger = displaySignals.snapshot(levelIdentity, request.worldSessionId(), target.getId(), target.getUUID());
        if (ledger.flatMap(FrameDisplaySyncSignals.FrameSnapshot::rotationEvidence)
                .filter(rotation -> rotation.rotation() != request.expectedRotation()).isPresent()) {
            return "frame_server_rotation_changed";
        }
        if (beforeDispatch) {
            String item = display.item() == null ? null : display.item().value();
            if (request.mode() == Mode.REMOVE ? !request.item().equals(item) : item != null) {
                return "frame_display_precondition_changed";
            }
        }
        return null;
    }

    private boolean align(Request request, long tick) {
        Vec3 eye = playerIdentity.getEyePosition();
        var point = request.frontAimPoint();
        double dx = point.x() - eye.x, dy = point.y() - eye.y, dz = point.z() - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float yawDelta = Mth.wrapDegrees(yaw - expectedYaw);
        float pitchDelta = pitch - expectedPitch;
        double degrees = Math.abs(yawDelta) + Math.abs(pitchDelta);
        if (degrees <= 1.0e-5D) return true;
        if (lastAimTick == tick) return false;
        double scale = Math.min(1.0D, request.cameraDegreesPerTick() / degrees);
        expectedYaw += (float) (yawDelta * scale);
        expectedPitch += (float) (pitchDelta * scale);
        playerIdentity.setYRot(expectedYaw);
        playerIdentity.setXRot(expectedPitch);
        lastAimTick = tick;
        return scale == 1.0D;
    }

    private boolean handReady(Request request) {
        if (selectedSlot < 0 || playerIdentity.getInventory().getSelectedSlot() != selectedSlot) return false;
        ItemStack live = playerIdentity.getMainHandItem();
        return request.mode() == Mode.REMOVE ? live.isEmpty()
                : !live.isEmpty() && live.getCount() == selectedBefore.getCount()
                    && ItemStack.isSameItemSameComponents(live, selectedBefore)
                    && request.item().equals(itemId(live));
    }

    private Frame sample(long tick, boolean ready, String failure) {
        if (levelIdentity == null || playerIdentity == null || activeRequest == null) return failed(tick, failure);
        var recon = reconciliations.bindAndSnapshot(levelIdentity, activeRequest.worldSessionId());
        var ledger = displaySignals.bindAndSnapshot(levelIdentity, activeRequest.worldSessionId());
        var entityEvidence = target == null ? null : displaySignals.snapshot(levelIdentity,
                activeRequest.worldSessionId(), target.getId(), target.getUUID()).orElse(null);
        var item = entityEvidence == null ? null : entityEvidence.itemEvidence().orElse(null);
        var hotbar = hotbarSignals.bindAndSnapshot(levelIdentity, activeRequest.worldSessionId());
        var selectedPayload = hotbar.slots().get(selectedSlot);
        boolean alive = target != null && target.isAlive() && !target.isRemoved()
                && levelIdentity.getEntity(target.getId()) == target;
        return new Frame(tick, recon.worldRevision(), ready, failure, alive,
                target == null ? activeRequest.expectedRotation() : target.getRotation(),
                target == null || target.getItem().isEmpty() ? null : itemId(target.getItem()),
                ledger.packetLedgerRevision(), item != null, item == null ? 0 : item.packetLedgerRevision(),
                item == null ? null : item.itemId(),
                !dispatched ? hotbar.revision() : selectedPayload == null ? 0 : selectedPayload.revision(),
                itemCount(playerIdentity, activeRequest.item()),
                inventoryBefore != null && selectedPayload != null
                        && exactConsumedStack(inventoryBefore.get(selectedSlot), selectedPayload.stack())
                        && exactInventoryConsumption(
                        inventoryBefore, inventory(playerIdentity), selectedSlot));
    }

    private Frame withCurrentFog(long tick, Supplier<Frame> visibleRead) {
        boolean available = ClientFogDistanceSignals.current(
                levelIdentity, playerIdentity, playerIdentity.tickCount).isPresent();
        long worldRevision = reconciliations.bindAndSnapshot(
                levelIdentity, activeRequest.worldSessionId()).worldRevision();
        return observeWithCurrentFog(available, tick, worldRevision, visibleRead);
    }

    static Frame observeWithCurrentFog(boolean available, long tick, long worldRevision,
                                      Supplier<Frame> visibleRead) {
        return available ? visibleRead.get() : Frame.pendingObservation(tick, worldRevision);
    }

    private Frame failed(long tick, String failure) {
        return new Frame(tick, 0, false, failure == null ? "frame_unavailable" : failure,
                false, activeRequest == null ? 0 : activeRequest.expectedRotation(), null,
                0, false, 0, null, 0, 0, false);
    }

    private Minecraft requireMinecraft() {
        Minecraft minecraft = Objects.requireNonNull(minecraftSupplier.get(), "minecraft");
        if (!minecraft.isSameThread()) throw new IllegalStateException("frame operation requires client thread");
        return minecraft;
    }

    static boolean exactCrosshair(Minecraft minecraft, ItemFrame frame) {
        return minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() == frame;
    }

    private static boolean supportedFrame(ItemFrame frame) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(frame.getType()).toString();
        return "minecraft:item_frame".equals(type) || "minecraft:glow_item_frame".equals(type);
    }

    private static int chooseHotbar(LocalPlayer player, Request request) {
        var hotbar = new ArrayList<ItemStack>(9);
        for (int slot = 0; slot < 9; slot++) {
            hotbar.add(player.getInventory().getItem(slot));
        }
        return chooseHotbar(hotbar, request.mode(), request.item());
    }

    static int chooseHotbar(List<ItemStack> hotbar, Mode mode, String item) {
        int selected = -1;
        for (int slot = 0; slot < Math.min(9, hotbar.size()); slot++) {
            ItemStack stack = hotbar.get(slot);
            if (mode == Mode.REMOVE && stack.isEmpty()) return slot;
            if (mode == Mode.INSERT && !stack.isEmpty() && item.equals(itemId(stack))) {
                if (selected >= 0 && !ItemStack.isSameItemSameComponents(hotbar.get(selected), stack)) return -2;
                if (selected < 0) selected = slot;
            }
        }
        return selected;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int itemCount(LocalPlayer player, String item) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && item.equals(itemId(stack))) count = Math.addExact(count, stack.getCount());
        }
        return count;
    }

    private static List<StackFingerprint> inventory(LocalPlayer player) {
        var result = new ArrayList<StackFingerprint>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result.add(StackFingerprint.fromServerPacket(player.getInventory().getItem(slot)));
        }
        return List.copyOf(result);
    }

    static boolean exactInventoryConsumption(List<StackFingerprint> before,
                                             List<StackFingerprint> after, int selectedSlot) {
        if (before.size() != after.size() || selectedSlot < 0 || selectedSlot >= before.size()) return false;
        for (int slot = 0; slot < before.size(); slot++) {
            var original = before.get(slot);
            var current = after.get(slot);
            if (slot != selectedSlot) {
                if (!original.equals(current)) return false;
            } else if (!exactConsumedStack(original, current)) return false;
        }
        return true;
    }

    static boolean exactConsumedStack(StackFingerprint before, StackFingerprint after) {
        return !before.empty() && before.count() - after.count() == 1
                && (after.empty() || (before.itemId().equals(after.itemId())
                    && before.itemAndComponentsHash() == after.itemAndComponentsHash()));
    }
}
