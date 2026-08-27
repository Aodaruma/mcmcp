package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockOutcome;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSample;
import dev.aod.mcmcp.observation.MinecraftObservationService.BlockSource;
import dev.aod.mcmcp.observation.ObservedContext;
import dev.aod.mcmcp.observation.WorldMemory;
import dev.aod.mcmcp.runtime.ClientPredictionSignals;
import dev.aod.mcmcp.runtime.ClientReconciliationSignals;
import dev.aod.mcmcp.runtime.WorldSessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Minecraft 26.2 adapter for the bounded Phase 3 semantic actions. */
public final class MinecraftSemanticActionPort implements SemanticActionPort {
    private static final float MIN_SAFE_HEALTH = 10.0F;
    private static final double THREAT_RADIUS = 8.0D;
    private static final double MAX_STATIONARY_DRIFT_SQUARED = 0.01D * 0.01D;
    private static final float MAX_ROTATION_DRIFT = 0.25F;
    private static final double SETTLED_VELOCITY_SQUARED = 0.03D * 0.03D;
    private static final int REQUIRED_SETTLE_TICKS = 10;
    private static final int STUCK_TICKS = 20;
    private static final double PROGRESS_EPSILON = 0.05D;
    private static final double ROUTE_SURFACE_EPSILON = 1.0e-4D;
    private static final float NAVIGATION_SCAN_PITCH_DEGREES = 40.0F;
    private static final float PREPARATION_AIM_EPSILON_DEGREES = 0.75F;
    private static final int MAX_AIM_SHAPE_BOXES = 8;
    private static final Set<String> HOE_ITEMS = Set.of(
            "minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:iron_hoe",
            "minecraft:golden_hoe", "minecraft:diamond_hoe", "minecraft:netherite_hoe");
    private static final Set<String> TILLABLE_BLOCKS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path");
    private static final Map<String, BlockStateFingerprint> CROP_PLACEMENTS = Map.of(
            "minecraft:wheat_seeds", fingerprint(Blocks.WHEAT.defaultBlockState()),
            "minecraft:carrot", fingerprint(Blocks.CARROTS.defaultBlockState()),
            "minecraft:potato", fingerprint(Blocks.POTATOES.defaultBlockState()),
            "minecraft:beetroot_seeds", fingerprint(Blocks.BEETROOTS.defaultBlockState()));
    private static final Set<net.minecraft.world.level.block.Block> WOODEN_TRAPDOORS = Set.of(
            Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BIRCH_TRAPDOOR,
            Blocks.JUNGLE_TRAPDOOR, Blocks.ACACIA_TRAPDOOR, Blocks.CHERRY_TRAPDOOR,
            Blocks.DARK_OAK_TRAPDOOR, Blocks.PALE_OAK_TRAPDOOR,
            Blocks.MANGROVE_TRAPDOOR, Blocks.BAMBOO_TRAPDOOR,
            Blocks.CRIMSON_TRAPDOOR, Blocks.WARPED_TRAPDOOR);

    private final Supplier<Minecraft> minecraftSupplier;
    private final Supplier<WorldSessionTracker.Snapshot> sessionSupplier;
    private final WorldMemory memory;
    private final MinecraftObservationService observations;
    private final ClientPredictionSignals predictions;
    private final ClientReconciliationSignals reconciliations;
    private final Map<SemanticActionRequest, Baseline> baselines = new IdentityHashMap<>();
    private final Map<SemanticActionPreparationAttempt, PreparationState> preparations =
            new IdentityHashMap<>();
    private final Map<SemanticActionAttempt, ActiveAttempt> activeAttempts = new IdentityHashMap<>();

    public MinecraftSemanticActionPort(
            Supplier<Minecraft> minecraftSupplier,
            Supplier<WorldSessionTracker.Snapshot> sessionSupplier,
            WorldMemory memory,
            MinecraftObservationService observations,
            ClientPredictionSignals predictions,
            ClientReconciliationSignals reconciliations) {
        this.minecraftSupplier = Objects.requireNonNull(minecraftSupplier, "minecraftSupplier");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
    }

    /** Live admission gate for place_block before the routine acquires voice/input ownership. */
    public void requireSafePlacementSupportForAdmission(PlaceBlockRequest request) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !matchesPlannedAim(request, hit)
                || minecraft.player == null) {
            return;
        }
        var stack = minecraft.player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)
                || item instanceof BedItem || item instanceof DoubleHighBlockItem
                || !request.item().equals(registryItemId(stack))) {
            return;
        }
        var context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(minecraft.player, InteractionHand.MAIN_HAND, hit)));
        if (context == null || !context.canPlace()
                || !context.getClickedPos().equals(blockPos(request.target()))) {
            return;
        }
        var level = minecraft.level;
        if (level == null) {
            return;
        }
        var support = hit.getBlockPos();
        requirePlacementSupport(
                level.getBlockState(support), level.getBlockEntity(support) != null, request);
    }

    @Override
    public SemanticActionFrame observe(SemanticActionRequest request) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        var session = sessionSupplier.get();
        var level = minecraft.level;
        var player = minecraft.player;
        var gameMode = minecraft.gameMode;
        boolean worldReady = session != null && session.worldReady()
                && level != null && player != null && gameMode != null
                && minecraft.getConnection() != null
                && request.bounds().dimension().equals(session.dimension())
                && request.bounds().dimension().equals(level.dimension().identifier().toString());
        if (!worldReady) {
            return unavailableFrame(session);
        }

        var baseline = baselines.computeIfAbsent(request, ignored -> Baseline.capture(player));
        var activeNavigation = request instanceof NavigateToRequest
                ? activeAttempt(request).orElse(null) : null;
        var activePreparation = blockRequest(request)
                ? activePreparation(request).orElse(null) : null;
        var activeBlock = blockRequest(request)
                ? activeAttempt(request).orElse(null) : null;
        boolean stationary = request instanceof NavigateToRequest
                ? activeNavigation == null
                        ? baseline.rotationAndSlotMatch(player)
                        : activeNavigation.navigationView != null
                                && activeNavigation.navigationView.matches(activeNavigation.attemptId)
                : activePreparation != null && activePreparation.view != null
                        ? baseline.positionMatches(player)
                                && activePreparation.view.matches(
                                        activePreparation.attempt.attemptId())
                : activeBlock != null && activeBlock.blockView != null
                        ? baseline.positionMatches(player)
                                && activeBlock.blockView.matches(activeBlock.blockViewOwner)
                : baseline.matches(player);
        boolean focused = minecraft.isWindowActive()
                && minecraft.mouseHandler.isMouseGrabbed()
                && !minecraft.isPaused()
                && minecraft.gui.screen() == null
                && minecraft.gui.overlay() == null
                && !player.isUsingItem()
                && stationary
                && gameMode.getPlayerMode() == GameType.SURVIVAL;
        boolean alive = player.isAlive() && !player.isDeadOrDying();
        boolean healthSafe = alive && player.getHealth() >= MIN_SAFE_HEALTH
                && player.getHealth() + 0.001F >= baseline.health()
                && player.hurtTime == 0 && player.getRemainingFireTicks() <= 0;
        boolean threatClear = visibleThreatClear(minecraft, player, level);
        boolean screenClear = minecraft.gui.screen() == null
                && player.containerMenu == player.inventoryMenu;
        var recon = reconciliations.bindAndSnapshot(level, session.worldSessionId());

        Optional<BlockStateFingerprint> liveBlock = Optional.empty();
        boolean blockReach = false;
        boolean crosshairBlock = false;
        boolean entityResolved = false;
        Optional<String> entityType = Optional.empty();
        boolean entityVisible = false;
        boolean entityLos = false;
        boolean entityReach = false;
        boolean crosshairEntity = false;
        int goalCount = 0;
        boolean inventorySynchronized = screenClear;
        boolean routeSafe = true;
        String routeCheckReason = "not_applicable";

        if (request instanceof BreakBlockRequest block) {
            var facts = blockFacts(minecraft, block.target(), null, block.plannedAim());
            liveBlock = facts.state();
            blockReach = facts.inReach();
            crosshairBlock = facts.crosshair();
        } else if (request instanceof PlaceBlockRequest place) {
            var facts = blockFacts(minecraft, place.target(), place, place.plannedAim());
            liveBlock = facts.state();
            blockReach = facts.inReach();
            crosshairBlock = facts.crosshair();
        } else if (request instanceof UseItemOnBlockRequest use) {
            var facts = blockFacts(minecraft, use.target(), null, use.plannedAim());
            liveBlock = facts.state();
            boolean safeUse = facts.state().filter(use.expectedBefore()::matches).isPresent()
                    && use.item().equals(registryItemId(player.getMainHandItem()))
                    && allowedUseItemTransition(level, use);
            blockReach = facts.inReach() && safeUse;
            crosshairBlock = facts.crosshair() && safeUse;
        } else if (request instanceof InteractBlockRequest block) {
            var facts = blockFacts(minecraft, block.target(), null, block.plannedAim());
            liveBlock = facts.state();
            boolean safeHand = safeBlockInteractionHand(
                    player.isShiftKeyDown(), player.getMainHandItem().isEmpty())
                    && facts.state().isPresent()
                    && allowedInteractBlock(level.getBlockState(blockPos(block.target())));
            blockReach = facts.inReach() && safeHand;
            crosshairBlock = facts.crosshair() && safeHand;
        } else if (request instanceof InteractEntityRequest entityRequest) {
            goalCount = inventoryCount(entityRequest.goal().itemId());
            var entity = resolveEntity(entityRequest, session, minecraft);
            entityResolved = entity.isPresent();
            entityType = entity.map(value -> registryEntityId(value));
            entityVisible = entity.isPresent();
            entityLos = entity.filter(player::hasLineOfSight).isPresent();
            boolean entityInBounds = entity.filter(value -> entityRequest.bounds().contains(
                    blockTarget(session.dimension(), value.blockPosition()))).isPresent();
            boolean heldItemMatches = entityRequest.heldItem().equals(
                    registryItemId(player.getMainHandItem()));
            entityReach = entity.filter(value -> player.isWithinEntityInteractionRange(value, 0.0D)).isPresent()
                    && entityInBounds && heldItemMatches
                    && entity.filter(MinecraftSemanticActionPort::allowedEntityInteraction).isPresent();
            crosshairEntity = entity.filter(value -> minecraft.hitResult instanceof EntityHitResult hit
                    && hit.getType() == HitResult.Type.ENTITY && hit.getEntity() == value).isPresent();
        } else if (request instanceof NavigateToRequest navigation) {
            var active = activeNavigation;
            if (active != null && active.failure != null) {
                routeSafe = false;
                routeCheckReason = "active_attempt_failed";
            } else if (active != null && active.inputStopped) {
                // Once input ownership is closed there is no future cell to authorize. Settle
                // uses live player/reconciliation facts; any later drift releases this attempt
                // and returns through PRECHECK before movement can be acquired again.
                routeSafe = true;
                routeCheckReason = SemanticActionFrame.STATIONARY_NAVIGATION;
            } else {
                var route = routeCheck(
                        minecraft, level, player, navigation, baseline, session.clientTick());
                if (route.safe()) {
                    routeCheckReason = route.reason();
                } else if (active != null
                        && SemanticActionFrame.PROBE_NOT_CURRENTLY_VISIBLE.equals(route.reason())) {
                    routeSafe = true;
                    routeCheckReason = SemanticActionFrame.ROUTE_REOBSERVATION_WAIT;
                } else if (active != null
                        && SemanticActionFrame.VISIBLE_ROUTE_OCCUPIED.equals(route.reason())) {
                    routeSafe = true;
                    routeCheckReason = SemanticActionFrame.ROUTE_OCCUPANCY_WAIT;
                } else {
                    routeSafe = false;
                    routeCheckReason = route.reason();
                }
            }
        }

        boolean safeToRetry = worldReady && focused && alive && healthSafe
                && threatClear && screenClear;
        Vec3 velocity = player.getDeltaMovement();
        return new SemanticActionFrame(
                session.clientTick(), memory.revision(), worldReady, focused, alive, healthSafe,
                threatClear, screenClear, liveBlock, blockReach, crosshairBlock,
                entityResolved, entityType, entityVisible, entityLos, entityReach,
                crosshairEntity, goalCount, inventorySynchronized,
                player.getX(), player.getY(), player.getZ(), horizontalVelocitySquared(velocity),
                player.onGround(), routeSafe, routeCheckReason,
                recon.positionCorrectionRevision(), safeToRetry);
    }

    @Override
    public SemanticActionPreparationAttempt beginPreparation(
            SemanticActionRequest request, long leaseExpiresAtClientTick) {
        var minecraft = assertClientThread();
        Objects.requireNonNull(request, "request");
        if (!blockRequest(request)) {
            throw new IllegalArgumentException("only finite block actions support preparation");
        }
        var frame = observe(request);
        if (!frame.universalSafetyClear() || leaseExpiresAtClientTick <= frame.clientTick()
                || activePreparation(request).isPresent()) {
            throw new IllegalStateException("block preparation preconditions are not satisfied");
        }
        var attempt = new SemanticActionPreparationAttempt(
                UUID.randomUUID(), request.kind(), frame.clientTick(), frame.observationRevision(),
                leaseExpiresAtClientTick, frame.positionCorrectionRevision());
        var player = Objects.requireNonNull(minecraft.player);
        List<Vec3> aimPoints = aimPoints(minecraft, request);
        int sourceSlot = preparationInventorySlot(player, request);
        int ownedSlot = sourceSlot;
        RoutineFailure preparationFailure = sourceSlot < 0
                ? failure(request instanceof InteractBlockRequest
                                ? "EMPTY_HAND_SLOT_UNAVAILABLE"
                                : "REQUIRED_ITEM_NOT_IN_INVENTORY",
                        RoutineFailure.Category.PRECONDITION,
                        false, RoutineFailure.Recovery.REPLAN,
                        Map.of("existing_player_inventory_slot", true),
                        Map.of("existing_player_inventory_slot", false))
                : aimPoints.isEmpty()
                        ? failure("AIM_RAYCAST_UNAVAILABLE", RoutineFailure.Category.PRECONDITION,
                                false, RoutineFailure.Recovery.REPLAN,
                                Map.of("reachable_aim_candidate", true),
                                Map.of("reachable_aim_candidate", false))
                        : null;
        if (preparationFailure == null && sourceSlot >= Inventory.getSelectionSize()) {
            ownedSlot = player.getInventory().getSelectedSlot();
            if (!stageIntoSelectedHotbar(minecraft, player, sourceSlot, request)) {
                preparationFailure = failure("INVENTORY_STAGE_NOT_APPLIED",
                        RoutineFailure.Category.EXTERNAL, false,
                        RoutineFailure.Recovery.REPLAN,
                        Map.of("selected_hotbar_contains_required_item", true),
                        Map.of("selected_hotbar_contains_required_item", false,
                                "source_inventory_slot", sourceSlot,
                                "selected_hotbar_slot", ownedSlot));
            }
        }
        NavigationViewLease view = null;
        if (preparationFailure == null) {
            view = NavigationViewLease.acquire(player, attempt.attemptId());
            try {
                view.selectSlot(attempt.attemptId(), ownedSlot);
            } catch (RuntimeException | LinkageError selectionFailure) {
                try {
                    view.close(attempt.attemptId());
                } catch (RuntimeException | LinkageError closeFailure) {
                    selectionFailure.addSuppressed(closeFailure);
                }
                throw selectionFailure;
            }
        }
        preparations.put(attempt, new PreparationState(
                attempt, request, ownedSlot, aimPoints, view, preparationFailure));
        return attempt;
    }

    @Override
    public void maintainPreparation(SemanticActionPreparationAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requirePreparation(attempt);
        if (active.failure != null || active.transferred) {
            return;
        }
        var frame = observe(active.request);
        refreshPreparationFailure(active, frame);
        if (active.failure != null) {
            return;
        }
        Vec3 point = active.aimPoints.get(active.aimIndex);
        Rotation target = rotationTo(
                Objects.requireNonNull(minecraft.player).getEyePosition(), point);
        try {
            active.view.turnToward(attempt.attemptId(), target.yaw(), target.pitch());
        } catch (RuntimeException | LinkageError drift) {
            active.failure = controlOwnershipFailure();
            return;
        }
        if (aimAligned(minecraft.player, point)
                && !raycastPrepares(minecraft, active.request)) {
            active.aimIndex++;
            if (active.aimIndex >= active.aimPoints.size()) {
                active.failure = failure("AIM_RAYCAST_UNAVAILABLE",
                        RoutineFailure.Category.PRECONDITION, false,
                        RoutineFailure.Recovery.REPLAN,
                        Map.of("reachable_aim_candidate", true),
                        Map.of("reachable_aim_candidate", false));
            }
        }
    }

    @Override
    public SemanticActionPreparationEvidence preparationEvidence(
            SemanticActionPreparationAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requirePreparation(attempt);
        var frame = observe(active.request);
        refreshPreparationFailure(active, frame);
        boolean selected = false;
        boolean aligned = false;
        if (active.failure == null && !active.transferred) {
            try {
                selected = active.view.slotSelected(attempt.attemptId(), active.ownedSlot);
                aligned = aimAligned(
                        Objects.requireNonNull(minecraft.player),
                        active.aimPoints.get(active.aimIndex));
            } catch (RuntimeException | LinkageError drift) {
                active.failure = controlOwnershipFailure();
            }
        }
        return new SemanticActionPreparationEvidence(
                attempt.attemptId(), frame.clientTick(), frame.observationRevision(),
                frame.liveBlockState(), frame.blockInReach(),
                aligned && frame.crosshairOnBlock(), selected, active.failure);
    }

    @Override
    public void releasePreparation(SemanticActionPreparationAttempt attempt) {
        assertClientThread();
        Objects.requireNonNull(attempt, "attempt");
        var active = preparations.get(attempt);
        if (active == null) {
            return;
        }
        if (!active.transferred && active.view != null) {
            active.view.close(attempt.attemptId());
        }
        preparations.remove(attempt, active);
    }

    @Override
    public SemanticActionAttempt dispatchPrepared(
            SemanticActionRequest request,
            SemanticActionPreparationAttempt preparation,
            long leaseExpiresAtClientTick) {
        assertClientThread();
        var active = requirePreparation(preparation);
        if (active.transferred || active.request != request
                || leaseExpiresAtClientTick != preparation.leaseExpiresAtClientTick()
                || !preparationEvidence(preparation).prepared()) {
            throw new IllegalStateException("block preparation is not current for dispatch");
        }
        var action = dispatch(request, leaseExpiresAtClientTick);
        var dispatched = requireActive(action);
        dispatched.blockView = active.view;
        dispatched.blockViewOwner = preparation.attemptId();
        active.transferred = true;
        return action;
    }

    @Override
    public SemanticActionAttempt dispatch(
            SemanticActionRequest request, long leaseExpiresAtClientTick) {
        var minecraft = assertClientThread();
        var frame = observe(request);
        requireDispatchable(request, frame, leaseExpiresAtClientTick);
        var session = requireSession();
        var recon = reconciliations.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level), session.worldSessionId());
        var attemptId = UUID.randomUUID();
        var dispatchBasis = new LinkedHashMap<String, Object>();
        dispatchBasis.put("world_session_id", session.worldSessionId().toString());
        dispatchBasis.put("dimension", session.dimension());
        dispatchBasis.put("verification", verificationBasis(request));
        var attempt = new SemanticActionAttempt(
                attemptId, request.kind(), frame.clientTick(), frame.observationRevision(),
                leaseExpiresAtClientTick, recon.positionCorrectionRevision(), dispatchBasis);
        var active = new ActiveAttempt(
                attemptId, request, recon, frame.clientTick(), distanceToTarget(request, frame));

        try {
            if (request instanceof NavigateToRequest) {
                active.movementLease = MovementInputLease.acquire(
                        minecraft, attemptId, System.nanoTime(),
                        leaseHorizon(frame.clientTick(), leaseExpiresAtClientTick));
                active.navigationView = NavigationViewLease.acquire(
                        Objects.requireNonNull(minecraft.player), attemptId);
            } else if (request instanceof BreakBlockRequest block) {
                var prediction = predictions.begin(minecraft.level, blockPos(block.target()), frame.clientTick());
                prediction.sequenceBeforePrediction();
                active.prediction = prediction;
                active.attackLease = AttackInputLease.acquire(
                        minecraft, System.nanoTime(),
                        leaseHorizon(frame.clientTick(), leaseExpiresAtClientTick));
            } else if (request instanceof PlaceBlockRequest place) {
                dispatchPlace(minecraft, place, active);
            } else if (request instanceof UseItemOnBlockRequest use) {
                dispatchUseItemOnBlock(minecraft, use, active);
            } else if (request instanceof InteractBlockRequest block) {
                dispatchBlockInteraction(minecraft, block, active);
            } else if (request instanceof InteractEntityRequest entity) {
                dispatchEntityInteraction(minecraft, session, entity, active);
            }
            activeAttempts.put(attempt, active);
            return attempt;
        } catch (RuntimeException | LinkageError failure) {
            closeActive(active, failure);
            throw failure;
        }
    }

    @Override
    public void maintain(SemanticActionAttempt attempt) {
        assertClientThread();
        var active = requireActive(attempt);
        if (active.failure != null
                || active.inputStopped && !(active.request instanceof NavigateToRequest)) {
            return;
        }
        var frame = observe(active.request);
        if (!frame.universalSafetyClear()) {
            active.failure = failure("ACTION_SAFETY_CHANGED", RoutineFailure.Category.SAFETY,
                    false, RoutineFailure.Recovery.NONE, Map.of(), frameBasis(frame));
            stopInput(attempt);
            return;
        }
        if (active.request instanceof NavigateToRequest navigation) {
            maintainNavigation(attempt, active, navigation, frame);
        } else if (active.request instanceof BreakBlockRequest) {
            maintainBreak(attempt, active, frame);
        }
    }

    @Override
    public void stopInput(SemanticActionAttempt attempt) {
        assertClientThread();
        var active = requireActive(attempt);
        if (active.inputStopped) {
            return;
        }
        RuntimeException runtimeFailure = null;
        try {
            if (active.attackLease != null) {
                active.attackLease.close();
            }
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
        }
        try {
            if (active.movementLease != null) {
                active.movementLease.close(attempt.attemptId());
            }
        } catch (RuntimeException failure) {
            if (runtimeFailure == null) runtimeFailure = failure;
            else runtimeFailure.addSuppressed(failure);
        }
        active.inputStopped = true;
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
    }

    @Override
    public SemanticActionEvidence evidence(SemanticActionAttempt attempt) {
        var minecraft = assertClientThread();
        var active = requireActive(attempt);
        var session = requireSession();
        var frame = observe(active.request);
        var recon = reconciliations.bindAndSnapshot(
                Objects.requireNonNull(minecraft.level), session.worldSessionId());
        detectReconciliationFailure(active, recon);

        boolean acknowledged = false;
        Optional<BlockStateFingerprint> serverState = Optional.empty();
        boolean inventoryObserved = recon.selectedSlotInventoryRevision()
                > active.reconciliationAtDispatch.selectedSlotInventoryRevision();
        int goalCount = active.request instanceof InteractEntityRequest entity
                ? inventoryCount(entity.goal().itemId()) : 0;
        boolean inventorySynchronized = inventoryObserved
                && frame.inventoryServerSynchronized()
                && active.request instanceof InteractEntityRequest entity
                && goalCount >= entity.goal().minimumInventoryCount();
        var basis = new LinkedHashMap<String, Object>();
        basis.put("verification", verificationBasis(active.request));
        basis.put("position_correction_revision", recon.positionCorrectionRevision());
        basis.put("rotation_revision", recon.rotationRevision());
        basis.put("motion_revision", recon.motionRevision());

        if (active.prediction != null) {
            var expected = active.expectedServerState;
            var confirmation = active.prediction.confirmation(state -> expected != null
                    && expected.equals(fingerprint(state)));
            Integer required = confirmation.stateRequiredSequence() != null
                    ? confirmation.stateRequiredSequence() : confirmation.issuedSequence();
            acknowledged = required != null && confirmation.acknowledgedSequence() != null
                    && Integer.compareUnsigned(confirmation.acknowledgedSequence(), required) >= 0;
            serverState = Optional.ofNullable(confirmation.serverState()).map(
                    MinecraftSemanticActionPort::fingerprint);
            basis.put("prediction_status", confirmation.status().name().toLowerCase());
            if (confirmation.serverConfirmed() && !active.confirmationHandled) {
                active.confirmationHandled = true;
                if (expected != null
                        && currentBlockState(active.request).filter(expected::equals).isPresent()) {
                    rememberConfirmation(active.request, expected, session);
                } else {
                    active.failure = failure("POSTCONDITION_CHANGED", RoutineFailure.Category.DIVERGENCE,
                            false, RoutineFailure.Recovery.REPLAN,
                            stateMap(expected), Map.of());
                }
            } else if (confirmation.status() == ClientPredictionSignals.ConfirmationStatus.SERVER_STATE_MISMATCH) {
                active.failure = failure("POSTCONDITION_MISMATCH", RoutineFailure.Category.DIVERGENCE,
                        false, RoutineFailure.Recovery.REPLAN,
                        stateMap(expected),
                        serverState.map(MinecraftSemanticActionPort::stateMap).orElse(Map.of()));
            } else if (confirmation.status() == ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE) {
                active.failure = failure("PREDICTION_BRIDGE_INCOMPATIBLE", RoutineFailure.Category.EXTERNAL,
                        false, RoutineFailure.Recovery.USER, Map.of(), Map.of());
            }
        } else if (active.request instanceof NavigateToRequest) {
            acknowledged = active.settleTicks >= REQUIRED_SETTLE_TICKS && active.failure == null;
            basis.put("server_reconciled", acknowledged);
            basis.put("settle_ticks", active.settleTicks);
        }

        return new SemanticActionEvidence(
                attempt.attemptId(), frame.clientTick(), frame.observationRevision(), acknowledged,
                serverState, inventoryObserved, inventorySynchronized, goalCount,
                active.failure, active.failure == null && active.safeToRetry, basis);
    }

    @Override
    public void release(SemanticActionAttempt attempt) {
        assertClientThread();
        Objects.requireNonNull(attempt, "attempt");
        var active = activeAttempts.get(attempt);
        if (active != null) {
            closeActive(active, null);
            activeAttempts.remove(attempt, active);
        }
    }

    @Override
    public void retire(SemanticActionRequest request) {
        assertClientThread();
        Objects.requireNonNull(request, "request");
        RuntimeException releaseFailure = null;
        for (var entry : new ArrayList<>(activeAttempts.entrySet())) {
            if (entry.getValue().request != request) {
                continue;
            }
            try {
                closeActive(entry.getValue(), null);
                activeAttempts.remove(entry.getKey(), entry.getValue());
            } catch (RuntimeException failure) {
                if (releaseFailure == null) releaseFailure = failure;
                else releaseFailure.addSuppressed(failure);
            }
        }
        for (var entry : new ArrayList<>(preparations.entrySet())) {
            if (entry.getValue().request != request) {
                continue;
            }
            try {
                releasePreparation(entry.getKey());
            } catch (RuntimeException failure) {
                if (releaseFailure == null) releaseFailure = failure;
                else releaseFailure.addSuppressed(failure);
            }
        }
        baselines.remove(request);
        if (releaseFailure != null) throw releaseFailure;
    }

    /** Captures delayed vanilla attack prediction sequences from ClientTickEvent.Post. */
    public void captureIssuedPredictions() {
        assertClientThread();
        for (var active : activeAttempts.values()) {
            if (active.prediction != null) {
                active.prediction.captureIssuedPredictions();
            }
        }
    }

    /** Releases every owned input/prediction and drops all request state on world replacement. */
    public void clearSession() {
        var minecraft = assertClientThread();
        for (var active : new ArrayList<>(activeAttempts.values())) {
            try {
                closeActive(active, null);
            } catch (RuntimeException | LinkageError ignored) {
                // Existing global input release remains the final lifecycle fence.
            }
        }
        for (var entry : new ArrayList<>(preparations.entrySet())) {
            try {
                releasePreparation(entry.getKey());
            } catch (RuntimeException | LinkageError ignored) {
                // Existing global input release remains the final lifecycle fence.
            }
        }
        activeAttempts.clear();
        preparations.clear();
        baselines.clear();
        reconciliations.closeLevel(minecraft.level);
    }

    private void dispatchPlace(Minecraft minecraft, PlaceBlockRequest request, ActiveAttempt active) {
        var prepared = requirePreparedPlacement(minecraft, request);
        var hit = prepared.hit();
        var context = prepared.context();
        var level = Objects.requireNonNull(minecraft.level);
        var support = hit.getBlockPos();
        requirePlacementSupport(
                level.getBlockState(support), level.getBlockEntity(support) != null, request);
        var prediction = predictions.begin(minecraft.level, context.getClickedPos(), requireSession().clientTick());
        int before = prediction.sequenceBeforePrediction();
        active.prediction = prediction;
        Objects.requireNonNull(minecraft.gameMode).useItemOn(
                minecraft.player, InteractionHand.MAIN_HAND, hit);
        int after = prediction.captureIssuedPredictions();
        if (after != before + 1) {
            throw new ClientPredictionSignals.PredictionBridgeException(
                    "place action did not issue exactly one prediction");
        }
        active.expectedServerState = fingerprint(minecraft.level.getBlockState(context.getClickedPos()));
        if (!request.expectedAfter().matches(active.expectedServerState)) {
            active.failure = failure("PREDICTED_POSTCONDITION_MISMATCH", RoutineFailure.Category.DIVERGENCE,
                    false, RoutineFailure.Recovery.REPLAN,
                    stateMap(request.expectedAfter()),
                    stateMap(active.expectedServerState));
        }
        active.safeToRetry = false;
    }

    private void dispatchBlockInteraction(
            Minecraft minecraft, InteractBlockRequest request, ActiveAttempt active) {
        var hit = actualBlockHit(minecraft);
        var position = blockPos(request.target());
        var beforeState = minecraft.level.getBlockState(position);
        if (!matchesPlannedAim(request, hit)
                || !hit.getBlockPos().equals(position)
                || !safeBlockInteractionHand(
                        minecraft.player.isShiftKeyDown(), minecraft.player.getMainHandItem().isEmpty())
                || !allowedInteractBlock(beforeState)) {
            throw new IllegalArgumentException("interact_block target is not in the finite allowlist");
        }
        // Unlike placement, vanilla interactions such as levers need not mutate the local level
        // optimistically. Freeze the exact same-block transition before sending its packet so a
        // fast server-only state update is checked against the requested result, not stale local state.
        active.expectedServerState = expectedInteractionServerState(
                beforeState, request, minecraft.player.getDirection());
        var prediction = predictions.begin(minecraft.level, position, requireSession().clientTick());
        int before = prediction.sequenceBeforePrediction();
        active.prediction = prediction;
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
        int after = prediction.captureIssuedPredictions();
        if (after != before + 1) {
            throw new ClientPredictionSignals.PredictionBridgeException(
                    "block interaction did not issue exactly one prediction");
        }
        active.safeToRetry = false;
    }

    private void dispatchUseItemOnBlock(
            Minecraft minecraft, UseItemOnBlockRequest request, ActiveAttempt active) {
        var hit = actualBlockHit(minecraft);
        var level = Objects.requireNonNull(minecraft.level);
        var position = blockPos(request.target());
        if (!matchesPlannedAim(request, hit)
                || !hit.getBlockPos().equals(position)
                || !request.item().equals(registryItemId(
                        Objects.requireNonNull(minecraft.player).getMainHandItem()))
                || !request.expectedBefore().matches(fingerprint(level.getBlockState(position)))
                || !allowedUseItemTransition(level, request)) {
            throw new IllegalArgumentException("use_item_on_block transition is not allowlisted");
        }
        active.expectedServerState = request.expectedAfter();
        var prediction = predictions.begin(level, position, requireSession().clientTick());
        int before = prediction.sequenceBeforePrediction();
        active.prediction = prediction;
        Objects.requireNonNull(minecraft.gameMode).useItemOn(
                minecraft.player, InteractionHand.MAIN_HAND, hit);
        int after = prediction.captureIssuedPredictions();
        if (after != before + 1) {
            throw new ClientPredictionSignals.PredictionBridgeException(
                    "block item use did not issue exactly one prediction");
        }
        // Hoe use is server-authoritative and need not mutate ClientLevel synchronously.
        // The prediction latch below already requires both the matching server state and ACK.
        active.safeToRetry = false;
    }

    static BlockStateFingerprint expectedInteractionServerState(
            BlockState beforeState,
            InteractBlockRequest request,
            Direction playerDirection) {
        Objects.requireNonNull(beforeState, "beforeState");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(playerDirection, "playerDirection");
        var before = fingerprint(beforeState);
        if (!allowedInteractBlock(beforeState)
                || !request.expectedBefore().blockId().equals(before.blockId())
                || !request.expectedAfter().blockId().equals(before.blockId())
                || !request.expectedBefore().matches(before)) {
            throw new IllegalArgumentException(
                    "interact_block source and expected transition must match the live block");
        }

        boolean lever = beforeState.getBlock() instanceof LeverBlock;
        String toggledProperty = lever ? "powered" : "open";
        BlockState expectedState;
        if (lever) {
            expectedState = beforeState.setValue(
                    BlockStateProperties.POWERED,
                    !beforeState.getValue(BlockStateProperties.POWERED));
        } else {
            expectedState = beforeState;
            if (beforeState.getBlock() instanceof FenceGateBlock
                    && !beforeState.getValue(BlockStateProperties.OPEN)
                    && beforeState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            == playerDirection.getOpposite()) {
                expectedState = expectedState.setValue(
                        BlockStateProperties.HORIZONTAL_FACING, playerDirection);
            }
            expectedState = expectedState.setValue(
                    BlockStateProperties.OPEN,
                    !beforeState.getValue(BlockStateProperties.OPEN));
        }
        var expected = fingerprint(expectedState);
        if (!request.expectedAfter().properties().containsKey(toggledProperty)
                || !request.expectedAfter().matches(expected)) {
            throw new IllegalArgumentException(
                    "interact_block expected_after must match the allowlisted toggle state");
        }
        return expected;
    }

    private void dispatchEntityInteraction(
            Minecraft minecraft,
            WorldSessionTracker.Snapshot session,
            InteractEntityRequest request,
            ActiveAttempt active) {
        if (!"minecraft:cow".equals(request.expectedType())
                || !"minecraft:bucket".equals(request.heldItem())
                || !"minecraft:milk_bucket".equals(request.goal().itemId())) {
            throw new IllegalArgumentException("Phase 3 interact_entity only supports cow milking");
        }
        var entity = resolveEntity(request, session, minecraft)
                .orElseThrow(() -> new IllegalStateException("entity reference is not currently visible"));
        if (!allowedEntityInteraction(entity)
                || !(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != entity
                || !minecraft.player.hasLineOfSight(entity)
                || !minecraft.player.isWithinEntityInteractionRange(entity, 0.0D)
                || !request.bounds().contains(blockTarget(session.dimension(), entity.blockPosition()))
                || !request.heldItem().equals(registryItemId(minecraft.player.getMainHandItem()))) {
            throw new IllegalStateException("entity interaction preconditions changed before dispatch");
        }
        minecraft.gameMode.interact(minecraft.player, entity, hit, InteractionHand.MAIN_HAND);
        active.safeToRetry = false;
    }

    private void maintainBreak(
            SemanticActionAttempt attempt, ActiveAttempt active, SemanticActionFrame frame) {
        var request = (BreakBlockRequest) active.request;
        active.prediction.captureIssuedPredictions();
        var confirmation = active.prediction.confirmation(
                state -> request.expectedAfter().matches(fingerprint(state)));
        if (confirmation.postconditionObserved()) {
            active.expectedServerState = request.expectedAfter();
            stopInput(attempt);
            active.safeToRetry = false;
            return;
        }
        if (!frame.crosshairOnBlock() || !frame.blockInReach()
                || frame.liveBlockState().filter(request.expectedBefore()::matches).isEmpty()) {
            active.failure = failure("BLOCK_TARGET_CHANGED", RoutineFailure.Category.DIVERGENCE,
                    false, RoutineFailure.Recovery.REPLAN,
                    stateMap(request.expectedBefore()),
                    frame.liveBlockState().map(
                            MinecraftSemanticActionPort::stateMap).orElse(Map.of()));
            stopInput(attempt);
            return;
        }
        var level = Objects.requireNonNull(requireMinecraft().level);
        var position = blockPos(request.target());
        SafeBreakSourcePolicy.requireLiveState(
                level.getBlockState(position), level.getBlockEntity(position) != null);
        if (active.attackLease == null || !active.attackLease.heartbeat(
                System.nanoTime(), leaseHorizon(frame.clientTick(), attempt.leaseExpiresAtClientTick()))) {
            active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                    false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            stopInput(attempt);
            return;
        }
    }

    private void maintainNavigation(
            SemanticActionAttempt attempt,
            ActiveAttempt active,
            NavigateToRequest request,
            SemanticActionFrame frame) {
        var recon = reconciliations.bindAndSnapshot(
                Objects.requireNonNull(requireMinecraft().level), requireSession().worldSessionId());
        detectReconciliationFailure(active, recon);
        if (active.failure != null) {
            stopInput(attempt);
            return;
        }
        if (frame.routeTransientWait()) {
            active.settleTicks = 0;
            turnNavigationView(active, request, frame);
            heartbeatMovement(attempt, active, frame, Set.of());
            return;
        }
        if (!frame.routeSafe()) {
            if (active.failure == null) {
                active.failure = failure("ROUTE_UNSAFE", RoutineFailure.Category.SAFETY,
                        false, RoutineFailure.Recovery.REPLAN, Map.of(), frameBasis(frame));
            }
            stopInput(attempt);
            return;
        }
        double distance = horizontalDistance(frame.playerX(), frame.playerZ(), request.target());
        if (distance <= request.horizontalToleranceBlocks()) {
            stopInput(attempt);
            if (frame.onGround()
                    && frame.playerHorizontalVelocitySquared() <= SETTLED_VELOCITY_SQUARED) {
                active.settleTicks++;
            } else {
                active.settleTicks = 0;
            }
            return;
        }
        // Settling deliberately closes the movement lease. A later drift is handled by the
        // domain routine's bounded retry/replan path; never resurrect the closed lease here.
        if (active.inputStopped) {
            return;
        }
        turnNavigationView(active, request, frame);
        if (!heartbeatMovement(attempt, active, frame, steering(
                frame.playerX(), frame.playerZ(), requireMinecraft().player.getYRot(), request.target()))) {
            return;
        }
        if (active.bestDistance - distance >= PROGRESS_EPSILON) {
            active.bestDistance = distance;
            active.lastProgressTick = frame.clientTick();
        } else if (frame.clientTick() - active.lastProgressTick >= STUCK_TICKS) {
            active.failure = failure("NAVIGATION_STUCK", RoutineFailure.Category.TRANSIENT,
                    true, RoutineFailure.Recovery.RETRY, Map.of(), frameBasis(frame));
            stopInput(attempt);
            return;
        }
    }

    private void turnNavigationView(
            ActiveAttempt active, NavigateToRequest request, SemanticActionFrame frame) {
        if (active.navigationView == null) {
            throw new IllegalStateException("navigation view ownership is unavailable");
        }
        double dx = request.target().x() + 0.5D - frame.playerX();
        double dz = request.target().z() + 0.5D - frame.playerZ();
        if (dx * dx + dz * dz <= 1.0e-6D) {
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        active.navigationView.turnToward(
                active.attemptId, yaw, NAVIGATION_SCAN_PITCH_DEGREES);
    }

    private boolean heartbeatMovement(
            SemanticActionAttempt attempt,
            ActiveAttempt active,
            SemanticActionFrame frame,
            Set<MovementInputLease.MovementKey> desired) {
        if (active.movementLease == null) {
            active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                    false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
            stopInput(attempt);
            return false;
        }
        active.movementLease.setDesired(attempt.attemptId(), desired);
        if (active.movementLease.heartbeat(
                attempt.attemptId(), System.nanoTime(),
                leaseHorizon(frame.clientTick(), attempt.leaseExpiresAtClientTick()))) {
            return true;
        }
        active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
        stopInput(attempt);
        return false;
    }

    private void detectReconciliationFailure(
            ActiveAttempt active, ClientReconciliationSignals.Snapshot current) {
        var before = active.reconciliationAtDispatch;
        if (!before.sameSession(current)) {
            active.failure = failure("WORLD_SESSION_CHANGED", RoutineFailure.Category.EXTERNAL,
                    false, RoutineFailure.Recovery.NONE, Map.of(), Map.of());
        } else if (current.positionCorrectionRevision() > before.positionCorrectionRevision()) {
            active.failure = failure("SERVER_POSITION_CORRECTION", RoutineFailure.Category.DIVERGENCE,
                    false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of(
                            "revision", current.positionCorrectionRevision()));
        } else if (active.request instanceof NavigateToRequest
                && current.rotationRevision() > before.rotationRevision()) {
            active.failure = failure("SERVER_ROTATION_CORRECTION", RoutineFailure.Category.DIVERGENCE,
                    false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of(
                            "revision", current.rotationRevision()));
        } else if (active.request instanceof NavigateToRequest
                && current.motionRevision() > before.motionRevision()) {
            active.failure = failure("SERVER_MOTION_APPLIED", RoutineFailure.Category.SAFETY,
                    true, RoutineFailure.Recovery.RETRY, Map.of(), Map.of(
                            "revision", current.motionRevision()));
        }
    }

    private void requireDispatchable(
            SemanticActionRequest request, SemanticActionFrame frame, long leaseExpiresAtClientTick) {
        if (!frame.universalSafetyClear() || leaseExpiresAtClientTick <= frame.clientTick()) {
            throw new IllegalStateException("semantic action universal preconditions are not satisfied");
        }
        if (request instanceof NavigateToRequest) {
            if (!frame.routeSafe() && !frame.routeNeedsReobservation()) {
                throw new IllegalStateException("navigation route is unsafe");
            }
        } else if (request instanceof BreakBlockRequest block) {
            if (!"minecraft:air".equals(block.expectedAfter().blockId())
                    || !frame.crosshairOnBlock() || !frame.blockInReach()) {
                throw new IllegalStateException("break_block preconditions are not satisfied");
            }
            var level = Objects.requireNonNull(requireMinecraft().level);
            var position = blockPos(block.target());
            SafeBreakSourcePolicy.requireLiveState(
                    level.getBlockState(position), level.getBlockEntity(position) != null);
            if (frame.liveBlockState().filter(block.expectedBefore()::matches).isEmpty()) {
                throw new IllegalStateException("break_block preconditions are not satisfied");
            }
        } else if (request instanceof PlaceBlockRequest place) {
            if (!frame.crosshairOnBlock() || !frame.blockInReach()
                    || frame.liveBlockState().filter(place.expectedBefore()::matches).isEmpty()) {
                throw new IllegalStateException("place_block preconditions are not satisfied");
            }
        } else if (request instanceof UseItemOnBlockRequest use) {
            if (!frame.crosshairOnBlock() || !frame.blockInReach()
                    || frame.liveBlockState().filter(use.expectedBefore()::matches).isEmpty()
                    || !allowedUseItemTransition(
                            Objects.requireNonNull(requireMinecraft().level), use)) {
                throw new IllegalStateException("use_item_on_block preconditions are not satisfied");
            }
        } else if (request instanceof InteractBlockRequest block) {
            if (!frame.crosshairOnBlock() || !frame.blockInReach()
                    || frame.liveBlockState().filter(block.expectedBefore()::matches).isEmpty()) {
                throw new IllegalStateException("interact_block preconditions are not satisfied");
            }
        } else if (request instanceof InteractEntityRequest entity) {
            if (!frame.entityResolved() || !frame.entityVisible() || !frame.entityLineOfSight()
                    || !frame.entityInReach() || !frame.crosshairOnEntity()
                    || frame.entityType().filter(entity.expectedType()::equals).isEmpty()
                    || frame.goalItemCount() >= entity.goal().minimumInventoryCount()) {
                throw new IllegalStateException("interact_entity preconditions are not satisfied");
            }
        }
    }

    private BlockFacts blockFacts(
            Minecraft minecraft,
            BlockTarget target,
            PlaceBlockRequest placement,
            Optional<BlockAimWitness> plannedAim) {
        var level = Objects.requireNonNull(minecraft.level);
        var player = Objects.requireNonNull(minecraft.player);
        var position = blockPos(target);
        if (!level.isLoaded(position)) {
            return new BlockFacts(Optional.empty(), false, false);
        }
        boolean actualTarget = false;
        boolean reach = false;
        boolean supportSafe = placement == null;
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            if (!matchesPlannedAim(plannedAim, hit)) {
                return new BlockFacts(Optional.empty(), false, false);
            }
            if (placement == null) {
                actualTarget = hit.getBlockPos().equals(position);
                reach = actualTarget && player.isWithinBlockInteractionRange(position, 0.0D);
            } else if (placement.item() != null
                    && player.getMainHandItem().getItem() instanceof BlockItem item
                    && placement.item().equals(registryItemId(player.getMainHandItem()))) {
                var context = item.updatePlacementContext(new BlockPlaceContext(
                        new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
                actualTarget = context != null && context.getClickedPos().equals(position);
                supportSafe = actualTarget && allowsPlacementSupport(
                        level.getBlockState(hit.getBlockPos()),
                        level.getBlockEntity(hit.getBlockPos()) != null, placement);
                reach = actualTarget
                        && player.isWithinBlockInteractionRange(hit.getBlockPos(), 0.0D)
                        && player.isWithinBlockInteractionRange(position, 0.0D);
            }
        }
        Optional<BlockStateFingerprint> state = actualTarget
                ? Optional.of(fingerprint(level.getBlockState(position))) : Optional.empty();
        boolean permitted = actualTarget && reach
                && supportSafe
                && level.getWorldBorder().isWithinBounds(position)
                && !player.blockActionRestricted(level, position, minecraft.gameMode.getPlayerMode());
        return new BlockFacts(state, permitted, actualTarget);
    }

    private Optional<Entity> resolveEntity(
            InteractEntityRequest request,
            WorldSessionTracker.Snapshot session,
            Minecraft minecraft) {
        return observations.resolveCurrentlyVisibleEntity(
                        minecraft, session.clientTick(), session.worldSessionId(), session.dimension(),
                        request.entityRef(), Math.min(THREAT_RADIUS, minecraft.player.entityInteractionRange() + 1.0D))
                .filter(entity -> !(entity instanceof Player));
    }

    /** Phase 3's entity allowlist is intentionally narrower than opaque-reference resolution. */
    static boolean allowedEntityInteraction(Entity entity) {
        return entity instanceof Cow cow
                && "minecraft:cow".equals(registryEntityId(entity))
                && !cow.isBaby();
    }

    private boolean visibleThreatClear(Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        return level.getEntities(player, player.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && (entity instanceof Enemy
                                || entity instanceof Mob mob && mob.getTarget() == player))
                .stream().noneMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
    }

    static boolean allowedInteractBlock(BlockState state) {
        var block = state.getBlock();
        return block instanceof LeverBlock
                || block instanceof FenceGateBlock
                || WOODEN_TRAPDOORS.contains(block);
    }

    static boolean allowedUseItemTransition(ClientLevel level, UseItemOnBlockRequest request) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(request, "request");
        BlockPos position = blockPos(request.target());
        if (!level.isLoaded(position) || !HOE_ITEMS.contains(request.item())) {
            return false;
        }
        BlockState before = level.getBlockState(position);
        return request.expectedBefore().matches(fingerprint(before))
                && TILLABLE_BLOCKS.contains(request.expectedBefore().blockId())
                && request.expectedAfter().equals(fingerprint(Blocks.FARMLAND.defaultBlockState()))
                && level.getBlockState(position.above()).isAir()
                && !before.hasBlockEntity()
                && level.getBlockEntity(position) == null
                && before.getFluidState().isEmpty();
    }

    static boolean allowsPlacementSupport(
            BlockState state, boolean liveBlockEntityPresent, PlaceBlockRequest request) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(request, "request");
        if (!closedCropPlacement(request)) {
            return SafePlacementSupportPolicy.allowsLiveState(state, liveBlockEntityPresent);
        }
        return state.getBlock() == Blocks.FARMLAND
                && !state.hasBlockEntity()
                && !liveBlockEntityPresent
                && state.getFluidState().isEmpty();
    }

    private static void requirePlacementSupport(
            BlockState state, boolean liveBlockEntityPresent, PlaceBlockRequest request) {
        if (!allowsPlacementSupport(state, liveBlockEntityPresent, request)) {
            throw new SafePlacementSupportPolicy.UnsafePlacementSupportException();
        }
    }

    private static boolean closedCropPlacement(PlaceBlockRequest request) {
        BlockStateFingerprint crop = CROP_PLACEMENTS.get(request.item());
        return crop != null
                && "minecraft:air".equals(request.expectedBefore().blockId())
                && request.expectedBefore().properties().isEmpty()
                && crop.equals(request.expectedAfter());
    }

    static boolean safeBlockInteractionHand(boolean shiftDown, boolean mainHandEmpty) {
        return !shiftDown && mainHandEmpty;
    }

    static Set<MovementInputLease.MovementKey> steering(
            double playerX, double playerZ, float yaw, BlockTarget target) {
        double dx = target.x() + 0.5D - playerX;
        double dz = target.z() + 0.5D - playerZ;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double forward = dx * forwardX + dz * forwardZ;
        double right = dx * rightX + dz * rightZ;
        var result = java.util.EnumSet.noneOf(MovementInputLease.MovementKey.class);
        if (forward > 0.10D) result.add(MovementInputLease.MovementKey.FORWARD);
        else if (forward < -0.10D) result.add(MovementInputLease.MovementKey.BACK);
        if (right > 0.10D) result.add(MovementInputLease.MovementKey.RIGHT);
        else if (right < -0.10D) result.add(MovementInputLease.MovementKey.LEFT);
        return Set.copyOf(result);
    }

    private RouteCheck routeCheck(
            Minecraft minecraft,
            ClientLevel level,
            LocalPlayer player,
            NavigateToRequest request,
            Baseline baseline,
            long clientTick) {
        if (!player.onGround() || Math.abs(player.getY() - request.target().y()) > 1.0D) {
            return RouteCheck.unsafe(!player.onGround()
                    ? "player_not_grounded" : "vertical_target_mismatch");
        }
        int feetY = routeFeetY(player.getY());
        BlockPos containingFeet = new BlockPos(
                Mth.floor(player.getX()), feetY, Mth.floor(player.getZ()));
        feetY = routeFeetY(player.getY(), level.getBlockState(containingFeet));
        BlockPos currentFeet = new BlockPos(
                Mth.floor(player.getX()), feetY, Mth.floor(player.getZ()));
        var currentTarget = blockTarget(request.bounds().dimension(), currentFeet);
        if (!request.bounds().contains(currentTarget)) return RouteCheck.unsafe("current_feet_out_of_bounds");
        if (!level.isLoaded(currentFeet)) return RouteCheck.unsafe("current_feet_unloaded");
        if (!level.getWorldBorder().isWithinBounds(currentFeet)) {
            return RouteCheck.unsafe("current_feet_outside_world_border");
        }
        if (Math.hypot(player.getX() - baseline.x(), player.getZ() - baseline.z())
                > request.bounds().maxTravelBlocks() + request.horizontalToleranceBlocks()) {
            return RouteCheck.unsafe("travel_budget_exceeded");
        }
        double dx = request.target().x() + 0.5D - player.getX();
        double dz = request.target().z() + 0.5D - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > request.bounds().maxTravelBlocks() + request.horizontalToleranceBlocks()) {
            return RouteCheck.unsafe("target_distance_exceeded");
        }
        // Probe the next cell rather than the cell containing the player's camera column. This
        // keeps feet/head/floor simultaneously observable with an ordinary downward view.
        double scale = length == 0.0D ? 0.0D : Math.min(0.75D, length) / length;
        BlockPos feet = new BlockPos(
                Mth.floor(player.getX() + dx * scale), feetY,
                Mth.floor(player.getZ() + dz * scale));
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!request.bounds().contains(blockTarget(request.bounds().dimension(), feet))) {
            return RouteCheck.unsafe("probe_out_of_bounds");
        }
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(floor)) {
            return RouteCheck.unsafe("probe_unloaded");
        }
        if (!level.getWorldBorder().isWithinBounds(feet)
                || !level.getWorldBorder().isWithinBounds(floor)) {
            return RouteCheck.unsafe("probe_outside_world_border");
        }
        var routeCells = observations.observeBlocks(
                minecraft, clientTick, List.of(feet, head, floor), BlockSource.LIVE);
        if (!routeCellsCurrentlyVisible(routeCells)) {
            return RouteCheck.unsafe(SemanticActionFrame.PROBE_NOT_CURRENTLY_VISIBLE);
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        BlockState floorState = level.getBlockState(floor);
        if (isNavigationHazard(feetState)) return RouteCheck.unsafe("feet_hazard");
        if (isNavigationHazard(floorState)) return RouteCheck.unsafe("floor_hazard");
        if (!feetState.getCollisionShape(level, feet).isEmpty()) {
            return RouteCheck.unsafe("feet_collision");
        }
        if (!headState.getCollisionShape(level, head).isEmpty()) {
            return RouteCheck.unsafe("head_collision");
        }
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)
                && !lowFlatRouteFloor(floorState)) {
            return RouteCheck.unsafe("floor_not_sturdy");
        }
        var nextStand = player.getBoundingBox().move(dx * scale, 0.0D, dz * scale).inflate(0.05D);
        boolean occupied = level.getEntities(player, nextStand, entity -> entity.isAlive()
                        && !entity.isSpectator()
                        && (entity instanceof Mob || entity instanceof Player))
                .stream().anyMatch(entity -> observations.isEntityCurrentlyVisible(
                        minecraft, entity, THREAT_RADIUS));
        if (occupied) {
            return RouteCheck.unsafe(SemanticActionFrame.VISIBLE_ROUTE_OCCUPIED);
        }
        return RouteCheck.clear();
    }

    static int routeFeetY(double playerY) {
        if (!Double.isFinite(playerY)) {
            throw new IllegalArgumentException("playerY must be finite");
        }
        return Mth.floor(playerY + ROUTE_SURFACE_EPSILON);
    }

    static int routeFeetY(double playerY, BlockState containingState) {
        Objects.requireNonNull(containingState, "containingState");
        int feetY = routeFeetY(playerY);
        return lowFlatRouteFloor(containingState)
                && playerY - Math.floor(playerY) > 0.5D ? feetY + 1 : feetY;
    }

    static boolean lowFlatRouteFloor(BlockState state) {
        Objects.requireNonNull(state, "state");
        return state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH);
    }

    static double horizontalVelocitySquared(Vec3 velocity) {
        Objects.requireNonNull(velocity, "velocity");
        return velocity.x * velocity.x + velocity.z * velocity.z;
    }

    static boolean routeCellsCurrentlyVisible(List<BlockSample> samples) {
        return samples != null && samples.size() == 3
                && samples.stream().allMatch(sample -> sample != null
                        && sample.outcome() == BlockOutcome.CURRENT);
    }

    static boolean isNavigationHazard(BlockState state) {
        var block = state.getBlock();
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.TRIPWIRE)
                || block instanceof CampfireBlock || block instanceof Portal
                || block instanceof BasePressurePlateBlock
                || !state.getFluidState().isEmpty();
    }

    private void rememberConfirmation(
            SemanticActionRequest request,
            BlockStateFingerprint confirmed,
            WorldSessionTracker.Snapshot session) {
        if (confirmed == null || !(request instanceof BreakBlockRequest
                || request instanceof PlaceBlockRequest
                || request instanceof UseItemOnBlockRequest
                || request instanceof InteractBlockRequest)) {
            return;
        }
        var target = request instanceof BreakBlockRequest value ? value.target()
                : request instanceof PlaceBlockRequest value ? value.target()
                : request instanceof UseItemOnBlockRequest value ? value.target()
                : ((InteractBlockRequest) request).target();
        var level = requireMinecraft().level;
        var position = blockPos(target);
        if (level == null || !level.isLoaded(position)) return;
        var current = level.getBlockState(position);
        InteractionConfirmationRecorder.rememberIfCurrent(
                memory, session, target, confirmed, fingerprint(current),
                observedContext(level, position, current));
    }

    private Optional<BlockStateFingerprint> currentBlockState(SemanticActionRequest request) {
        BlockTarget target = request instanceof BreakBlockRequest value ? value.target()
                : request instanceof PlaceBlockRequest value ? value.target()
                : request instanceof UseItemOnBlockRequest value ? value.target()
                : request instanceof InteractBlockRequest value ? value.target() : null;
        var level = requireMinecraft().level;
        if (target == null || level == null || !level.isLoaded(blockPos(target))) {
            return Optional.empty();
        }
        return Optional.of(fingerprint(level.getBlockState(blockPos(target))));
    }

    private Optional<ActiveAttempt> activeAttempt(SemanticActionRequest request) {
        return activeAttempts.values().stream()
                .filter(active -> active.request == request)
                .findFirst();
    }

    private Optional<PreparationState> activePreparation(SemanticActionRequest request) {
        return preparations.values().stream()
                .filter(active -> active.request == request && !active.transferred)
                .findFirst();
    }

    private PreparationState requirePreparation(SemanticActionPreparationAttempt attempt) {
        var active = preparations.get(Objects.requireNonNull(attempt, "attempt"));
        if (active == null) {
            throw new IllegalStateException("semantic action preparation is not active");
        }
        return active;
    }

    private void refreshPreparationFailure(
            PreparationState active, SemanticActionFrame frame) {
        if (active.failure != null || active.transferred) {
            return;
        }
        if (frame.clientTick() >= active.attempt.leaseExpiresAtClientTick()) {
            active.failure = failure("ACTION_LEASE_EXPIRED", RoutineFailure.Category.TRANSIENT,
                    false, RoutineFailure.Recovery.REPLAN, Map.of(), Map.of());
        } else if (!frame.universalSafetyClear()) {
            active.failure = failure("ACTION_SAFETY_CHANGED", RoutineFailure.Category.SAFETY,
                    false, RoutineFailure.Recovery.USER,
                    Map.of("safe", true), Map.of("safe", false));
        } else if (active.view == null
                || !active.view.matches(active.attempt.attemptId())) {
            active.failure = controlOwnershipFailure();
        }
    }

    private static RoutineFailure controlOwnershipFailure() {
        return failure("BLOCK_CONTROL_OWNERSHIP_LOST", RoutineFailure.Category.SAFETY,
                false, RoutineFailure.Recovery.USER,
                Map.of("control_ownership", true), Map.of("control_ownership", false));
    }

    private static boolean blockRequest(SemanticActionRequest request) {
        return request instanceof BreakBlockRequest
                || request instanceof PlaceBlockRequest
                || request instanceof UseItemOnBlockRequest
                || request instanceof InteractBlockRequest;
    }

    private static int preparationInventorySlot(
            LocalPlayer player, SemanticActionRequest request) {
        var inventory = player.getInventory();
        if (request instanceof BreakBlockRequest) {
            return inventory.getSelectedSlot();
        }
        return firstPreparingSlot(
                inventory.getSelectedSlot(),
                slot -> slotPrepares(inventory.getItem(slot), request));
    }

    static int firstPreparingSlot(
            int selectedSlot, java.util.function.IntPredicate prepares) {
        Objects.requireNonNull(prepares, "prepares");
        if (selectedSlot < 0 || selectedSlot >= Inventory.getSelectionSize()) {
            throw new IllegalArgumentException("selected hotbar slot must be 0..8");
        }
        if (prepares.test(selectedSlot)) {
            return selectedSlot;
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (slot != selectedSlot && prepares.test(slot)) {
                return slot;
            }
        }
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (prepares.test(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean stageIntoSelectedHotbar(
            Minecraft minecraft,
            LocalPlayer player,
            int sourceInventorySlot,
            SemanticActionRequest request) {
        var inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        if (sourceInventorySlot < Inventory.getSelectionSize()
                || sourceInventorySlot >= Inventory.INVENTORY_SIZE
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        int sourceMenuSlot = -1;
        for (int slot = InventoryMenu.INV_SLOT_START;
                slot < player.inventoryMenu.slots.size(); slot++) {
            var candidate = player.inventoryMenu.slots.get(slot);
            if (candidate.container == inventory
                    && candidate.getContainerSlot() == sourceInventorySlot) {
                sourceMenuSlot = slot;
                break;
            }
        }
        if (sourceMenuSlot < 0 || minecraft.gameMode == null) {
            return false;
        }
        try {
            minecraft.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    sourceMenuSlot,
                    selectedSlot,
                    ContainerInput.SWAP,
                    player);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
        return slotPrepares(inventory.getItem(selectedSlot), request);
    }

    private static boolean slotPrepares(
            ItemStack stack, SemanticActionRequest request) {
        if (request instanceof InteractBlockRequest) {
            return stack.isEmpty();
        }
        String required = request instanceof PlaceBlockRequest place ? place.item()
                : request instanceof UseItemOnBlockRequest use ? use.item() : null;
        return required != null && !stack.isEmpty()
                && required.equals(registryItemId(stack))
                && (!(request instanceof PlaceBlockRequest)
                        || stack.getItem() instanceof BlockItem item
                        && !(item instanceof BedItem)
                        && !(item instanceof DoubleHighBlockItem));
    }

    private static List<Vec3> aimPoints(
            Minecraft minecraft, SemanticActionRequest request) {
        var level = Objects.requireNonNull(minecraft.level);
        var player = Objects.requireNonNull(minecraft.player);
        BlockPos target = blockPos(blockTarget(request));
        if (request.plannedAim().isPresent()) {
            var aim = request.plannedAim().orElseThrow();
            BlockPos block = blockPos(aim.block());
            if (!level.isLoaded(block)
                    || !level.getWorldBorder().isWithinBounds(block)
                    || !player.isWithinBlockInteractionRange(block, 0.0D)) {
                return List.of();
            }
            return List.of(new Vec3(aim.x(), aim.y(), aim.z()));
        }
        if (!level.isLoaded(target) || !level.getWorldBorder().isWithinBounds(target)
                || !player.isWithinBlockInteractionRange(target, 0.0D)) {
            return List.of();
        }
        var points = new ArrayList<Vec3>();
        if (request instanceof PlaceBlockRequest) {
            for (Direction face : Direction.values()) {
                BlockPos support = target.relative(face.getOpposite());
                if (!level.isLoaded(support)
                        || !level.getWorldBorder().isWithinBounds(support)
                        || !player.isWithinBlockInteractionRange(support, 0.0D)
                        || !allowsPlacementSupport(
                                level.getBlockState(support),
                                level.getBlockEntity(support) != null, (PlaceBlockRequest) request)) {
                    continue;
                }
                addFaceAimPoints(points, level, player, support, face);
            }
        } else {
            for (Direction face : Direction.values()) {
                addFaceAimPoints(points, level, player, target, face);
            }
        }
        Vec3 eye = player.getEyePosition();
        points.sort(java.util.Comparator.comparingDouble(eye::distanceToSqr));
        return List.copyOf(points);
    }

    private static void addFaceAimPoints(
            List<Vec3> points,
            ClientLevel level,
            LocalPlayer player,
            BlockPos position,
            Direction face) {
        var shape = level.getBlockState(position)
                .getShape(level, position, CollisionContext.of(player));
        List<AABB> boxes = shape.isEmpty()
                ? List.of(new AABB(position))
                : shape.toAabbs().stream().limit(MAX_AIM_SHAPE_BOXES)
                        .map(box -> box.move(position)).toList();
        for (AABB box : boxes) {
            Vec3 point = faceCenter(box, face);
            if (player.getEyePosition().distanceToSqr(point)
                    <= square(player.blockInteractionRange() + 0.25D)) {
                points.add(point);
            }
        }
    }

    private static boolean raycastPrepares(
            Minecraft minecraft, SemanticActionRequest request) {
        var player = Objects.requireNonNull(minecraft.player);
        if (minecraft.getCameraEntity() != player) {
            return false;
        }
        HitResult result = player.raycastHitResult(1.0F, minecraft.getCameraEntity());
        if (!(result instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || hit.isWorldBorderHit()) {
            return false;
        }
        if (!matchesPlannedAim(request, hit)) {
            return false;
        }
        var level = Objects.requireNonNull(minecraft.level);
        var target = blockPos(blockTarget(request));
        if (request instanceof PlaceBlockRequest place) {
            var stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof BlockItem item)
                    || !place.item().equals(registryItemId(stack))) {
                return false;
            }
            var context = item.updatePlacementContext(new BlockPlaceContext(
                    new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
            return context != null && context.canPlace()
                    && context.getClickedPos().equals(target)
                    && player.isWithinBlockInteractionRange(hit.getBlockPos(), 0.0D)
                    && allowsPlacementSupport(
                            level.getBlockState(hit.getBlockPos()),
                            level.getBlockEntity(hit.getBlockPos()) != null, place);
        }
        if (!hit.getBlockPos().equals(target)
                || !player.isWithinBlockInteractionRange(target, 0.0D)) {
            return false;
        }
        if (request instanceof UseItemOnBlockRequest use) {
            return use.item().equals(registryItemId(player.getMainHandItem()))
                    && allowedUseItemTransition(level, use);
        }
        if (request instanceof InteractBlockRequest) {
            return safeBlockInteractionHand(
                    player.isShiftKeyDown(), player.getMainHandItem().isEmpty())
                    && allowedInteractBlock(level.getBlockState(target));
        }
        return true;
    }

    private static boolean aimAligned(LocalPlayer player, Vec3 point) {
        Rotation desired = rotationTo(player.getEyePosition(), point);
        return Math.abs(Mth.wrapDegrees(desired.yaw() - player.getYRot()))
                        <= PREPARATION_AIM_EPSILON_DEGREES
                && Math.abs(desired.pitch() - player.getXRot())
                        <= PREPARATION_AIM_EPSILON_DEGREES;
    }

    private static Rotation rotationTo(Vec3 eye, Vec3 point) {
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        return new Rotation(
                (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D),
                (float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    private static Vec3 faceCenter(AABB box, Direction face) {
        double x = (box.minX + box.maxX) * 0.5D;
        double y = (box.minY + box.maxY) * 0.5D;
        double z = (box.minZ + box.maxZ) * 0.5D;
        return switch (face) {
            case DOWN -> new Vec3(x, box.minY, z);
            case UP -> new Vec3(x, box.maxY, z);
            case NORTH -> new Vec3(x, y, box.minZ);
            case SOUTH -> new Vec3(x, y, box.maxZ);
            case WEST -> new Vec3(box.minX, y, z);
            case EAST -> new Vec3(box.maxX, y, z);
        };
    }

    private static double square(double value) {
        return value * value;
    }

    private static BlockTarget blockTarget(SemanticActionRequest request) {
        return switch (request) {
            case BreakBlockRequest block -> block.target();
            case PlaceBlockRequest block -> block.target();
            case UseItemOnBlockRequest block -> block.target();
            case InteractBlockRequest block -> block.target();
            default -> throw new IllegalArgumentException("request has no block target");
        };
    }

    private void closeActive(ActiveAttempt active, Throwable primary) {
        Throwable failure = primary;
        try {
            if (active.attackLease != null) active.attackLease.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = append(failure, closeFailure);
        }
        try {
            if (active.movementLease != null) active.movementLease.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = append(failure, closeFailure);
        }
        try {
            if (active.navigationView != null) {
                active.navigationView.close(active.attemptId);
            }
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = append(failure, closeFailure);
        }
        try {
            if (active.blockView != null) {
                active.blockView.close(active.blockViewOwner);
            }
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = append(failure, closeFailure);
        }
        try {
            if (active.prediction != null) active.prediction.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = append(failure, closeFailure);
        }
        if (primary == null && failure instanceof RuntimeException runtime) throw runtime;
        if (primary == null && failure instanceof LinkageError linkage) throw linkage;
    }

    private static Throwable append(Throwable primary, Throwable addition) {
        if (primary == null) return addition;
        primary.addSuppressed(addition);
        return primary;
    }

    private ActiveAttempt requireActive(SemanticActionAttempt attempt) {
        var active = activeAttempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (active == null) throw new IllegalStateException("semantic action attempt is not active");
        return active;
    }

    private Minecraft assertClientThread() {
        var minecraft = requireMinecraft();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("semantic action adapter must run on the Minecraft client thread");
        }
        return minecraft;
    }

    private Minecraft requireMinecraft() {
        return Objects.requireNonNull(minecraftSupplier.get(), "Minecraft client is not initialized");
    }

    private WorldSessionTracker.Snapshot requireSession() {
        var session = Objects.requireNonNull(sessionSupplier.get(), "world session is unavailable");
        if (!session.worldReady()) throw new IllegalStateException("world session is not ready");
        return session;
    }

    private static SemanticActionFrame unavailableFrame(WorldSessionTracker.Snapshot session) {
        long tick = session == null ? 0 : Math.max(0, session.clientTick());
        return new SemanticActionFrame(tick, 0, false, false, false, false, false, false,
                Optional.empty(), false, false, false, Optional.empty(), false, false,
                false, false, 0, false, 0, 0, 0, 0, false, false,
                "world_unavailable", 0, false);
    }

    private static BlockHitResult actualBlockHit(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalStateException("current hit result is not a block");
        }
        return hit;
    }

    private static PreparedPlacement requirePreparedPlacement(
            Minecraft minecraft, PlaceBlockRequest request) {
        Objects.requireNonNull(request, "request");
        var hit = actualBlockHit(minecraft);
        if (!matchesPlannedAim(request, hit)) {
            throw new IllegalArgumentException("actual hit does not match the planned block face");
        }
        var player = Objects.requireNonNull(minecraft.player);
        var stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)
                || item instanceof BedItem || item instanceof DoubleHighBlockItem
                || !request.item().equals(registryItemId(stack))) {
            throw new IllegalArgumentException("place_block requires the selected single-cell BlockItem");
        }
        var context = item.updatePlacementContext(new BlockPlaceContext(
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
        if (context == null || !context.canPlace()
                || !context.getClickedPos().equals(blockPos(request.target()))) {
            throw new IllegalArgumentException("actual hit does not place at the requested target");
        }
        return new PreparedPlacement(hit, context);
    }

    static boolean matchesPlannedAim(
            SemanticActionRequest request, BlockHitResult hit) {
        Objects.requireNonNull(request, "request");
        return matchesPlannedAim(request.plannedAim(), hit);
    }

    private static boolean matchesPlannedAim(
            Optional<BlockAimWitness> plannedAim, BlockHitResult hit) {
        Objects.requireNonNull(plannedAim, "plannedAim");
        Objects.requireNonNull(hit, "hit");
        return plannedAim.isEmpty()
                || hit.getBlockPos().equals(blockPos(plannedAim.orElseThrow().block()))
                        && hit.getDirection() == Direction.valueOf(
                                plannedAim.orElseThrow().face().name());
    }

    private int inventoryCount(String itemId) {
        var inventory = Objects.requireNonNull(requireMinecraft().player).getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && itemId.equals(registryItemId(stack))) {
                count = Math.min(2_304, count + stack.getCount());
            }
        }
        return count;
    }

    private static double distanceToTarget(SemanticActionRequest request, SemanticActionFrame frame) {
        return request instanceof NavigateToRequest navigation
                ? horizontalDistance(frame.playerX(), frame.playerZ(), navigation.target()) : 0.0D;
    }

    private static double horizontalDistance(double x, double z, BlockTarget target) {
        return Math.hypot(target.x() + 0.5D - x, target.z() + 0.5D - z);
    }

    private static String verificationBasis(SemanticActionRequest request) {
        if (request instanceof NavigateToRequest) return "server_reconciled";
        if (request instanceof InteractEntityRequest) return "server_synced_inventory_effect";
        return "block_prediction_ack_and_server_state";
    }

    /** Break's postcondition is fixed before its delayed vanilla attack prediction is issued. */
    static BlockStateFingerprint initialExpectedServerState(SemanticActionRequest request) {
        return request instanceof BreakBlockRequest block ? block.expectedAfter() : null;
    }

    private static Map<String, Object> frameBasis(SemanticActionFrame frame) {
        return Map.of("client_tick", frame.clientTick(), "route_safe", frame.routeSafe(),
                "route_check_reason", frame.routeCheckReason(),
                "on_ground", frame.onGround(),
                "horizontal_velocity_squared", frame.playerHorizontalVelocitySquared());
    }

    private static Map<String, Object> stateMap(BlockStateFingerprint state) {
        if (state == null) return Map.of();
        return Map.of("block", state.blockId(), "properties", state.properties());
    }

    private static RoutineFailure failure(
            String code,
            RoutineFailure.Category category,
            boolean retryable,
            RoutineFailure.Recovery recovery,
            Map<String, Object> expected,
            Map<String, Object> observed) {
        return new RoutineFailure(category, code, retryable, recovery, RoutineFailure.Scope.STEP,
                1, expected, observed, Map.of(), List.of("player", "target", "inventory"), false);
    }

    private static BlockPos blockPos(BlockTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private static BlockTarget blockTarget(String dimension, BlockPos position) {
        return new BlockTarget(dimension, position.getX(), position.getY(), position.getZ());
    }

    private static String registryItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String registryEntityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
        return new BlockStateFingerprint(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }

    @SuppressWarnings("deprecation")
    private static ObservedContext observedContext(ClientLevel level, BlockPos position, BlockState state) {
        var fluid = state.getFluidState();
        var sturdy = new ArrayList<String>();
        for (Direction face : Direction.values()) {
            if (state.isFaceSturdy(level, position, face)) sturdy.add(face.getSerializedName());
        }
        return new ObservedContext(
                level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position),
                fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
                fluid.isEmpty() ? null : fluid.isSource(),
                fluid.isEmpty() ? null : fluid.getAmount(),
                state.canBeReplaced(), state.getCollisionShape(level, position).isEmpty(), sturdy);
    }

    private static Duration leaseHorizon(long currentTick, long expiresAtTick) {
        long remainingTicks = Math.max(1, expiresAtTick - currentTick);
        return Duration.ofMillis(Math.min(2_000L, Math.multiplyExact(remainingTicks, 50L)));
    }

    private record BlockFacts(Optional<BlockStateFingerprint> state, boolean inReach, boolean crosshair) {
    }

    private record PreparedPlacement(BlockHitResult hit, BlockPlaceContext context) {
        private PreparedPlacement {
            Objects.requireNonNull(hit, "hit");
            Objects.requireNonNull(context, "context");
        }
    }

    private record RouteCheck(boolean safe, String reason) {
        private RouteCheck {
            Objects.requireNonNull(reason, "reason");
        }

        static RouteCheck clear() {
            return new RouteCheck(true, "safe");
        }

        static RouteCheck unsafe(String reason) {
            return new RouteCheck(false, reason);
        }
    }

    private record Baseline(
            double x, double y, double z, float yaw, float pitch, int selectedSlot, float health) {
        static Baseline capture(LocalPlayer player) {
            return new Baseline(player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot(), player.getInventory().getSelectedSlot(), player.getHealth());
        }

        boolean matches(LocalPlayer player) {
            return positionMatches(player) && rotationAndSlotMatch(player);
        }

        boolean positionMatches(LocalPlayer player) {
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= MAX_STATIONARY_DRIFT_SQUARED;
        }

        boolean rotationAndSlotMatch(LocalPlayer player) {
            return Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) <= MAX_ROTATION_DRIFT
                    && Math.abs(Mth.wrapDegrees(player.getXRot() - pitch)) <= MAX_ROTATION_DRIFT
                    && player.getInventory().getSelectedSlot() == selectedSlot;
        }
    }

    private static final class ActiveAttempt {
        private final UUID attemptId;
        private final SemanticActionRequest request;
        private final ClientReconciliationSignals.Snapshot reconciliationAtDispatch;
        private long lastProgressTick;
        private double bestDistance;
        private ClientPredictionSignals.PredictionAttempt prediction;
        private AttackInputLease attackLease;
        private MovementInputLease movementLease;
        private NavigationViewLease navigationView;
        private NavigationViewLease blockView;
        private UUID blockViewOwner;
        private BlockStateFingerprint expectedServerState;
        private RoutineFailure failure;
        private boolean inputStopped;
        private boolean safeToRetry = true;
        private boolean confirmationHandled;
        private int settleTicks;

        private ActiveAttempt(
                UUID attemptId,
                SemanticActionRequest request,
                ClientReconciliationSignals.Snapshot reconciliationAtDispatch,
                long issuedTick,
                double initialDistance) {
            this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
            this.request = request;
            this.reconciliationAtDispatch = reconciliationAtDispatch;
            this.lastProgressTick = issuedTick;
            this.bestDistance = initialDistance;
            // A fast server may deliver both the air state and its covering ACK before the first
            // maintain() call. Seed break's fixed v1 postcondition at dispatch so that evidence is
            // never evaluated with a null/always-false predicate in that window.
            this.expectedServerState = initialExpectedServerState(request);
        }
    }

    private static final class PreparationState {
        private final SemanticActionPreparationAttempt attempt;
        private final SemanticActionRequest request;
        private final int ownedSlot;
        private final List<Vec3> aimPoints;
        private final NavigationViewLease view;
        private int aimIndex;
        private boolean transferred;
        private RoutineFailure failure;

        private PreparationState(
                SemanticActionPreparationAttempt attempt,
                SemanticActionRequest request,
                int ownedSlot,
                List<Vec3> aimPoints,
                NavigationViewLease view,
                RoutineFailure failure) {
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            this.request = Objects.requireNonNull(request, "request");
            this.ownedSlot = ownedSlot;
            this.aimPoints = List.copyOf(aimPoints);
            this.view = view;
            this.failure = failure;
        }
    }

    private record Rotation(float yaw, float pitch) {
    }
}
