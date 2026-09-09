package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import dev.aod.mcmcp.runtime.ScreenOwnershipSignals;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftPhaseFiveInventoryPortTest {
    private static final int DEFAULT_HASH = 41;

    @Test
    void rendererWaitGatesOnlyUnsentInitialOpenAndNeverAckReadbackOrCleanup() throws Exception {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                MinecraftPhaseFiveInventoryPort.InitialOpenWitness.RENDERER_EVIDENCE_MISSING);
        java.util.function.Supplier<MinecraftPhaseFiveInventoryPort.InitialOpenWitness> witness = () -> {
            checks.incrementAndGet();
            return current.get();
        };
        for (int tick = 0; tick < 4; tick++) {
            assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                    MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                    .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.RENDERER_EVIDENCE_MISSING);
        }
        current.set(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        current.set(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.DELIVERY_EXPIRED);
        for (var stage : MinecraftPhaseFiveInventoryPort.Stage.values()) {
            if (stage == MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL) continue;
            assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(stage, witness))
                    .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.READY);
        }
        assertThat(checks).hasValue(5);
        assertThat(MinecraftPhaseFiveInventoryPort.initialOpenWitness(
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL, witness))
                .isEqualTo(MinecraftPhaseFiveInventoryPort.InitialOpenWitness.DELIVERY_EXPIRED);

        var node = classNode();
        assertThat(invocations(node, "dispatchExpectedOpen")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#initialOpenWitness",
                "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#beginExpectedOpen",
                "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "maintain")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                "dev/aod/mcmcp/routine/PhaseFiveAttempt#hardDeadlineClientTick",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#maintainAim");
    }

    @Test
    void expiredReleaseRetriesOnlyWhenObservableCleanupEvidenceChanges() {
        var waiting = List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 42L,
                ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK);
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(null, waiting)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting, List.copyOf(waiting)))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 43L,
                        ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("same-menu", ScreenOwnershipSignals.Phase.FAILED, 42L,
                        ScreenOwnershipSignals.CausalBarrierStatus.ACKNOWLEDGED))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseBoundaryChanged(waiting,
                List.of("closed", ScreenOwnershipSignals.Phase.IDLE, 42L,
                        ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK))).isTrue();
    }

    @Test
    void retiringFailedScreenPreservesOnlyExactOwnerServerEmptyCursorProof() {
        var owner = java.util.UUID.randomUUID();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, true, true))
                .isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, false, true))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, owner, true, false))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(
                owner, java.util.UUID.randomUUID(), true, true)).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(owner, null, true, true))
                .isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseCursorProofMatches(null, null, true, true))
                .isFalse();
    }

    @Test
    void fourteenStackTakeKeepsAVanillaBlockStackSafeInMainHandWithoutAnotherInteraction() {
        var hotbar = new ArrayList<ItemStack>();
        for (int slot = 0; slot < 8; slot++) {
            hotbar.add(testItemStack(Blocks.COBBLESTONE.asItem(), 64));
        }
        hotbar.add(ItemStack.EMPTY);

        var opening = InventoryOpenHandPolicy.chooseOpenHand(
                hotbar, false, 3).orElseThrow();
        assertThat(opening.selectedSlot()).isZero();
        assertThat(InventoryOpenHandPolicy.safeKnownMenuOpenStack(hotbar.getFirst()))
                .isTrue();
        assertThat(MinecraftKnownBrewingPort.safeNormalUseStack(hotbar.getFirst())).isFalse();

        // All fourteen transfer clicks remain available: no parking or hand-swap click is needed.
        hotbar.set(8, testItemStack(Blocks.COBBLESTONE.asItem(), 64));
        var nextContainer = InventoryOpenHandPolicy.chooseOpenHand(
                hotbar, false, 3).orElseThrow();
        assertThat(nextContainer.selectedSlot()).isZero();
    }

    @Test
    void emptyMainHandFallbackRejectsOnlyAnUnsafeOffhandSneakBypassHook() {
        var emptyHotbar = new ArrayList<>(
                java.util.Collections.nCopies(9, ItemStack.EMPTY));
        var plainOffhand = testItemStack(Blocks.COBBLESTONE.asItem(), 1);

        assertThat(ItemStack.EMPTY.doesSneakBypassUse(null, null, null)).isTrue();
        assertThat(InventoryOpenHandPolicy.chooseOpenHand(
                emptyHotbar, ItemStack.EMPTY, 3))
                .contains(new InventoryOpenHandPolicy.OpenHandPlan(0));
        assertThat(InventoryOpenHandPolicy.chooseOpenHand(
                emptyHotbar, plainOffhand, 3))
                .contains(new InventoryOpenHandPolicy.OpenHandPlan(0));
        assertThat(InventoryOpenHandPolicy.chooseOpenHand(
                emptyHotbar, false, 3)).isEmpty();
        assertThat(InventoryOpenHandPolicy.safeEmptyMainHandOffhand(
                false, CustomFirstUseItem.class)).isTrue();
        assertThat(InventoryOpenHandPolicy.safeEmptyMainHandOffhand(
                false, CustomSneakBypassItem.class)).isFalse();
    }

    @Test
    void customNeoForgeOpenHooksAreNeverTreatedAsSafeContainerOpeningHands() {
        assertThat(InventoryOpenHandPolicy.usesDefaultNeoForgeOpenHooks(
                CustomFirstUseItem.class)).isFalse();
        assertThat(InventoryOpenHandPolicy.usesDefaultNeoForgeOpenHooks(
                CustomSneakBypassItem.class)).isFalse();
        assertThat(InventoryOpenHandPolicy.usesDefaultNeoForgeOpenHooks(
                InheritedSneakBypassItem.class)).isFalse();
        assertThat(InventoryOpenHandPolicy.usesDefaultNeoForgeOpenHooks(
                Blocks.COBBLESTONE.asItem().getClass())).isTrue();
        assertThat(InventoryOpenHandPolicy.safeKnownMenuOpenStack(ItemStack.EMPTY))
                .isFalse();
    }

    @Test
    void unsafeInboundItemCannotFillAnEmptyMainHandBeforeTransferPlanning()
            throws Exception {
        assertThat(InventoryOpenHandPolicy.inboundTransferKeepsOpenHandSafe(
                false, true, CustomFirstUseItem.class)).isFalse();
        assertThat(InventoryOpenHandPolicy.inboundTransferKeepsOpenHandSafe(
                false, true, CustomSneakBypassItem.class)).isFalse();
        assertThat(InventoryOpenHandPolicy.inboundTransferKeepsOpenHandSafe(
                false, true, Blocks.COBBLESTONE.asItem().getClass())).isTrue();
        assertThat(InventoryOpenHandPolicy.inboundTransferKeepsOpenHandSafe(
                true, true, CustomFirstUseItem.class)).isTrue();
        assertThat(InventoryOpenHandPolicy.inboundTransferKeepsOpenHandSafe(
                false, false, CustomFirstUseItem.class)).isTrue();

        var node = classNode();
        assertThat(invocations(node, "acceptTransferSnapshot")).containsSubsequence(
                "dev/aod/mcmcp/routine/InventoryOpenHandPolicy"
                        + "#inboundTransferKeepsOpenHandSafe",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState"
                        + "#beginTransferBatch");
        assertThat(invocations(node, "dispatchTransferClick"))
                .contains("dev/aod/mcmcp/routine/KnownMenuTransfers"
                        + "#dispatchServerConfirmedQuickMove");
    }

    @Test
    void transferParametersKeepDirectionalGoalAndSeparateFiniteBatchLimits() {
        assertThat(transferState(true, 3_456, 896, 14)).isNotNull();
        assertThat(transferState(false, 2_304, 64, 1)).isNotNull();
        assertThatThrownBy(() -> transferState(true, 3_457, 64, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(false, 2_305, 64, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(true, 1, 897, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferState(true, 1, 64, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferEvidenceDistinguishesUnreadZeroFromObservedZeroAndResetsBetweenClicks() {
        var target = new BlockTarget("minecraft:overworld", 147, 66, -300);
        var parameters = new InventoryParameters.TransferParameters(
                true, "minecraft:torch", "default_components_only", 1, 128, 2,
                true, 8.0D, target, new BlockStateFingerprint("minecraft:barrel", Map.of()));
        var state = new MinecraftPhaseFiveInventoryPort.AttemptState(
                inventoryRequest(target, null), parameters);

        assertThat(state.basis()).containsEntry("source_before", 0)
                .containsEntry("destination_before", 0)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");
        state.prepareTransfer(64, 0, 64);
        assertThat(state.basis()).containsEntry("source_before", 64)
                .containsEntry("destination_before", 0)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");

        state.recordTransferReadback(0, 64);
        assertThat(state.basis()).containsEntry("transfer_readback_observed", true)
                .containsEntry("source_after", 0).containsEntry("destination_after", 64);
        state.prepareTransfer(64, 64, 64);
        assertThat(state.basis()).containsEntry("source_before", 64)
                .containsEntry("destination_before", 64)
                .containsEntry("transfer_readback_observed", false)
                .doesNotContainKeys("source_after", "destination_after");
        state.recordTransferReadback(64, 128);
        assertThat(state.basis()).containsEntry("transfer_readback_observed", true)
                .containsEntry("source_after", 64).containsEntry("destination_after", 128);
    }

    @Test
    void refilledVanillaBlockHandRemainsSafeForTheNextExactKnownMenuUse()
            throws Exception {
        // An empty opening hand may be filled by normal full-content sync or an auto-refill mod.
        // The isolated NeoForge JUnit loader does not bind Vanilla item defaults.
        var holder = Items.TORCH.builtInRegistryHolder();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        boolean safeRefilledHand = InventoryOpenHandPolicy.safeKnownMenuOpenStack(
                new ItemStack(Items.TORCH, 64));
        assertThat(safeRefilledHand).isTrue();
        for (var stage : List.of(MinecraftPhaseFiveInventoryPort.Stage.OPENING_INITIAL,
                MinecraftPhaseFiveInventoryPort.Stage.OPENING_READBACK)) {
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(stage, () -> {
                throw new AssertionError("a completed block use must not inspect the changed hand");
            }))
                    .isNull();
        }
        for (var stage : List.of(MinecraftPhaseFiveInventoryPort.Stage.AIMING_INITIAL,
                MinecraftPhaseFiveInventoryPort.Stage.AIMING_READBACK)) {
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(
                    stage, () -> safeRefilledHand)).isNull();
            assertThat(MinecraftPhaseFiveInventoryPort.safeOpenHandFailure(stage, () -> true)).isNull();
        }

        var node = classNode();
        assertThat(invocations(node, "ongoingFailure")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#undisturbed",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#screenContextMatches",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#safeOpenHandFailure");
        assertThat(invocations(node, "dispatchExpectedOpen")).containsSubsequence(
                "dev/aod/mcmcp/routine/InventoryOpenHandPolicy$OpenHandPlan#ready",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "acceptTransferSnapshot")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState#recordTransferReadback",
                "dev/aod/mcmcp/routine/InventorySlotPlanning#verifyTransferReadback");
        assertThat(invocations(node, "dispatchTransferClick")).containsSubsequence(
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#prepareOwnedDispatch",
                "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#freshEmptyServerCursorProof",
                "dev/aod/mcmcp/routine/InventoryTransferBatch#beginClick",
                "dev/aod/mcmcp/routine/KnownMenuTransfers#dispatchServerConfirmedQuickMove");
    }

    @Test
    void craftingTableAcceptsSmallerGridRecipesButRejectsIncompatibleDisplays() {
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("crafting_table", true))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("inventory_2x2", true))).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("unsupported", true))).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingTableRecipeSupported(
                recipe("crafting_table", false))).isFalse();
    }

    @Test
    void transferAllowsTheSameVanillaContainerWhileBarrelOpenStateEvolves() {
        var closedBarrel = Blocks.BARREL.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, false);
        var openBarrel = closedBarrel.setValue(BlockStateProperties.OPEN, true);
        var expected = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(closedBarrel);

        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, openBarrel)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, Blocks.CHEST.defaultBlockState())).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expected, openBarrel.setValue(BlockStateProperties.FACING, Direction.EAST)))
                .isFalse();

        var chest = Blocks.CHEST.defaultBlockState();
        var expectedChest = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(chest);
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedChest, chest)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedChest, chest.setValue(BlockStateProperties.HORIZONTAL_FACING,
                        Direction.EAST))).isFalse();

        var doubleChest = chest.setValue(ChestBlock.TYPE, ChestType.LEFT);
        var expectedDouble = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(doubleChest);
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedDouble, doubleChest)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                expectedDouble, doubleChest.setValue(ChestBlock.TYPE, ChestType.RIGHT))).isFalse();
        assertThat(InventoryParameters.transferMenuType(expectedChest))
                .isEqualTo(InventoryParameters.SINGLE_CONTAINER_MENU);
        assertThat(InventoryParameters.transferMenuType(expectedDouble))
                .isEqualTo(InventoryParameters.DOUBLE_CONTAINER_MENU);
    }

    @Test
    void transferSupportsEveryCopperChestSizeAndFailsClosedOnIdentityChanges() {
        var copperChests = Blocks.COPPER_CHEST.asList();
        assertThat(copperChests).hasSize(8);
        for (int index = 0; index < copperChests.size(); index++) {
            var single = copperChests.get(index).defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.SOUTH)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE)
                    .setValue(ChestBlock.WATERLOGGED, false);
            var expectedSingle = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(single);
            assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                    expectedSingle, single)).isTrue();
            assertThat(InventoryParameters.transferMenuType(expectedSingle))
                    .isEqualTo(InventoryParameters.SINGLE_CONTAINER_MENU);

            var doubleChest = single.setValue(ChestBlock.TYPE, ChestType.LEFT);
            var expectedDouble = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(doubleChest);
            assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                    expectedDouble, doubleChest)).isTrue();
            assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                    expectedDouble,
                    doubleChest.setValue(ChestBlock.TYPE, ChestType.RIGHT))).isFalse();
            assertThat(InventoryParameters.transferMenuType(expectedDouble))
                    .isEqualTo(InventoryParameters.DOUBLE_CONTAINER_MENU);

            var changedVariant = copperChests.get((index + 1) % copperChests.size())
                    .withPropertiesOf(single);
            assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                    expectedSingle, changedVariant)).isFalse();
        }

        for (var rejected : List.of(
                Blocks.TRAPPED_CHEST.defaultBlockState(),
                Blocks.ENDER_CHEST.defaultBlockState(),
                Blocks.SHULKER_BOX.defaultBlockState())) {
            var fingerprint = MinecraftPhaseFiveInventoryPort.fingerprintLiveState(rejected);
            assertThat(MinecraftPhaseFiveInventoryPort.sameTransferContainerIdentity(
                    fingerprint, rejected)).isFalse();
            assertThatThrownBy(() -> InventoryParameters.transferMenuType(fingerprint))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> InventoryParameters.transferMenuType(
                new BlockStateFingerprint(
                        "minecraft:waxed_copper_chest", Map.of("type", "unknown"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void craftingAloneRetainsTheStationHeadingAndUsesTheRuntimeCameraLimit() throws Exception {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var parameters = new InventoryParameters.CraftParameters(
                "recipe-ref", "fingerprint", "minecraft:stick", 1, 1, target,
                new BlockStateFingerprint("minecraft:crafting_table", Map.of()));
        assertThat(parameters.restoreViewOnRelease()).isFalse();
        var transfer = new InventoryParameters.TransferParameters(
                false, "minecraft:stone", "default_components_only", 0, 1, 1,
                false, 8.0D, target,
                new BlockStateFingerprint("minecraft:barrel", Map.of()));
        assertThat(transfer.restoreViewOnRelease()).isTrue();

        var node = classNode();
        assertThat(invocations(node, "begin"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#configuredCameraDegreesPerTick",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#acquire",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#selectOpenHand");
        assertThat(invocations(node, "configuredCameraDegreesPerTick"))
                .contains("java/util/function/DoubleSupplier#getAsDouble");
    }

    @Test
    void aimingChecksSafetyBeforeTurningAndReadbackCanRecoverAStaleCrosshair() throws Exception {
        var node = classNode();

        assertThat(invocations(node, "maintainAim"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#ongoingFailure",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease#turnToward");
        assertThat(invocations(node, "targetReadyForReopen"))
                .contains("net/minecraft/client/player/LocalPlayer#isWithinBlockInteractionRange")
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#exactHit");
    }

    @Test
    void internalVisibleAimIsRevalidatedAndReplacesTheOccludableBlockCenter() {
        var target = new BlockTarget("minecraft:overworld", -11, 56, 3);
        var visibleUpHit = Map.<String, Object>of(
                "dimension", target.dimension(),
                "x", -10.5D,
                "y", 57.0D,
                "z", 3.5D);

        assertThat(InventoryParameters.inventoryAimPoint(
                inventoryRequest(target, visibleUpHit), target))
                .isEqualTo(new Vec3(-10.5D, 57.0D, 3.5D));
        assertThat(InventoryParameters.inventoryAimPoint(
                inventoryRequest(target, null), target))
                .isEqualTo(new Vec3(-10.5D, 56.5D, 3.5D));

        assertThatThrownBy(() -> InventoryParameters.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", "minecraft:the_nether",
                        "x", -10.5D, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryParameters.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", target.dimension(),
                        "x", -9.5D, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryParameters.inventoryAimPoint(
                inventoryRequest(target, Map.of(
                        "dimension", target.dimension(),
                        "x", Double.NaN, "y", 57.0D, "z", 3.5D)), target))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownedContainerCleanupUsesTheCanonicalScreenCloseLifecycle() throws Exception {
        var node = classNode();

        assertThat(invocations(node, "closeOwnedMenuClient"))
                .containsSubsequence(
                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                                + "#onClose",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#onScreenClosing")
                .doesNotContain("net/minecraft/client/player/LocalPlayer#closeContainer");
        assertThat(invocations(node, "closeForReadback"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
        assertThat(invocations(node, "releaseOwnedMenu"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
        var brewing = classNode("/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class");
        assertThat(invocations(brewing, "closeOwnedMenuClient"))
                .containsSubsequence(
                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                                + "#onClose",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#onScreenClosing");
    }

    @Test
    void cursorInvariantQuickMovesKeepTheInitialEmptyProofForAuthoritativeReadback()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "prepareOwnedDispatch"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#packetRevision")
                .doesNotContain("dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                        + "#invalidateServerCursorProof");
        assertThat(invocations(node, "dispatchContainerClick"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#prepareOwnedDispatch",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode"
                                + "#handleContainerInput");
        assertThat(invocations(node, "dispatchRecipePlacement"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#packetRevision",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode"
                                + "#handlePlaceRecipe")
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#prepareOwnedDispatch",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#invalidateServerCursorProof");
        assertThat(invocations(node, "maintainCraftResult"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#dispatchContainerClick")
                .noneMatch(call -> call.endsWith("#handleContainerInput"));
        assertThat(invocations(node, "maintainClickAck"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshServerCursorSnapshot",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeForReadback")
                .doesNotContain("dev/aod/mcmcp/routine/InventorySlotPlanning"
                        + "#countTransfer");
        assertThat(invocations(node, "acceptCraftSnapshot"))
                .contains(
                        "dev/aod/mcmcp/routine/InventorySlotPlanning"
                                + "#craftingGridAndResultEmpty",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchRecipePlacement");
        assertThat(invocations(node, "maintainCraftResult"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#freshServerCursorSnapshot");
        assertThat(invocations(node, "acceptTransferSnapshot"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/InventorySlotPlanning"
                                + "#liveMenuMatchesSnapshot",
                        "dev/aod/mcmcp/runtime/KnownMenuProfileSupport"
                                + "#hasFullDestinationCapacity",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchTransferClick")
                .noneMatch(call -> call.endsWith("#handleContainerInput"));
        assertThat(node.methods.stream()
                .filter(method -> method.instructions.iterator().hasNext())
                .filter(method -> invocations(node, method.name).stream()
                        .anyMatch(call -> call.endsWith("#handleContainerInput")))
                .map(method -> method.name))
                .containsExactly("dispatchContainerClick");
        assertThat(node.methods.stream()
                .filter(method -> method.instructions.iterator().hasNext())
                .filter(method -> invocations(node, method.name).stream()
                        .anyMatch(call -> call.endsWith("#handlePlaceRecipe")))
                .map(method -> method.name))
                .containsExactly("dispatchRecipePlacement");
        assertThat(invocations(node, "closeForReadback"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshEmptyServerCursorProof",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#cancelRoutine",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeOwnedMenuClient");
        assertThat(containerInputs(node, "maintainCraftResult"))
                .containsExactly("QUICK_MOVE");
    }

    @Test
    void optionalRoutingLabelIsRecheckedAtAdmissionAndImmediatelyBeforeEachClick()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "preflight"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#routingLabelFailure");
        assertThat(invocations(node, "dispatchExpectedOpen"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#routingLabelFailure",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn");
        assertThat(invocations(node, "prepareOwnedDispatch"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#routingLabelFailure");
        assertThat(invocations(node, "routingLabelFailure"))
                .contains(
                        "dev/aod/mcmcp/observation/MinecraftObservationService"
                                + "#resolveCurrentlyVisibleEntity",
                        "dev/aod/mcmcp/observation/ContainerLabelResolver#resolve");
    }

    @Test
    void expectedOpenRetainsCausalAuthorityUntilPredictionAckOrOwnedCleanup()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "dispatchExpectedOpen"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#ongoingFailure",
                        "dev/aod/mcmcp/routine/InventoryOpenHandPolicy$OpenHandPlan"
                                + "#ready",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#beginExpectedOpen",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#sequenceBeforePrediction",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#captureIssuedPredictions",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#cancelRoutineAfterPredictedUse");
        assertThat(invocations(node, "dispatchExpectedOpen"))
                .doesNotContain("net/minecraft/client/player/LocalPlayer#getOffhandItem");
        assertThat(interactionHands(node, "dispatchExpectedOpen")).containsExactly("MAIN_HAND");
        assertThat(invocations(node, "cancelScreenAuthority"))
                .contains("dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                        + "#cancelRoutineAfterPredictedUse");
        assertThat(invocations(node, "maintainTerminalRelease"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#cancelScreenAuthority",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeOpenPrediction");
    }

    @Test
    void vanillaKnownMenuOpenOrderConsumesBeforeAnyHeldItemUse() throws Exception {
        var client = classNode("/net/minecraft/client/multiplayer/MultiPlayerGameMode.class");
        assertThat(invocations(client, "performUseItemOn")).containsSubsequence(
                "net/minecraft/world/item/ItemStack#onItemUseFirst",
                "net/minecraft/world/item/ItemStack#doesSneakBypassUse",
                "net/minecraft/world/level/block/state/BlockState#useItemOn",
                "net/minecraft/world/level/block/state/BlockState#useWithoutItem",
                "net/minecraft/world/item/ItemStack#useOn");

        var server = classNode("/net/minecraft/server/level/ServerPlayerGameMode.class");
        assertThat(invocations(server, "useItemOn")).containsSubsequence(
                "net/minecraft/world/item/ItemStack#onItemUseFirst",
                "net/minecraft/world/level/block/state/BlockState#useItemOn",
                "net/minecraft/world/level/block/state/BlockState#useWithoutItem",
                "net/minecraft/world/item/ItemStack#useOn");

        var block = classNode("/net/minecraft/world/level/block/state/BlockBehaviour.class");
        assertThat(fieldReads(block, "useItemOn", "net/minecraft/world/InteractionResult"))
                .contains("TRY_WITH_EMPTY_HAND");

        for (String resource : List.of(
                "/net/minecraft/world/level/block/CraftingTableBlock.class",
                "/net/minecraft/world/level/block/ChestBlock.class",
                "/net/minecraft/world/level/block/BarrelBlock.class")) {
            var menuBlock = classNode(resource);
            assertThat(menuBlock.methods.stream().map(method -> method.name).toList())
                    .doesNotContain("useItemOn");
            assertThat(invocations(menuBlock, "useWithoutItem"))
                    .contains("net/minecraft/world/entity/player/Player#openMenu");
        }

        assertThat(CraftingTableBlock.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName).doesNotContain("useItemOn");
        assertThat(ChestBlock.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName).doesNotContain("useItemOn");
        assertThat(BarrelBlock.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName).doesNotContain("useItemOn");
    }

    @Test
    void safetyAndSlotOwnershipAreRecheckedAndReleasedBeforeTerminal() throws Exception {
        var node = classNode();
        assertThat(invocations(node, "observe"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState"
                                + "#terminal",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#ongoingFailure");
        assertThat(invocations(node, "preflight"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#basicPlayerSafety",
                        "dev/aod/mcmcp/routine/InventoryOpenHandPolicy"
                                + "#chooseOpenHand");
        assertThat(invocations(node, "ongoingFailure"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#basicPlayerSafety",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#screenContextMatches",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease"
                                + "#undisturbed");
        assertThat(invocations(node, "basicPlayerSafety"))
                .contains(
                        "net/minecraft/client/Minecraft#isPaused",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#visibleThreatClear");
        assertThat(invocations(classNode("/dev/aod/mcmcp/routine/InventoryOpenHandPolicy.class"),
                "chooseOpenHand"))
                .contains("net/minecraft/client/player/LocalPlayer#getOffhandItem");
        var openHand = classNode(
                "/dev/aod/mcmcp/routine/InventoryOpenHandPolicy$OpenHandPlan.class");
        assertThat(invocations(openHand, "ready"))
                .containsSubsequence(
                        "net/minecraft/client/player/LocalPlayer#getMainHandItem",
                        "net/minecraft/client/player/LocalPlayer#getOffhandItem",
                        "dev/aod/mcmcp/routine/InventoryOpenHandPolicy"
                                + "#safeKnownMenuOpenContext");
        assertThat(invocations(openHand, "readyAtSlot"))
                .contains(
                        "net/minecraft/client/player/LocalPlayer#getOffhandItem",
                        "dev/aod/mcmcp/routine/InventoryOpenHandPolicy"
                                + "#safeKnownMenuOpenContext");
        var brewing = classNode("/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class");
        assertThat(invocations(brewing, "chooseOpenHand"))
                .doesNotContain("net/minecraft/client/player/LocalPlayer#getOffhandItem");
        assertThat(invocations(node, "maintain"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#selectOpenHand");
        assertThat(invocations(node, "maintainAim"))
                .doesNotContain("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#selectOpenHand");
        assertThat(invocations(node, "confirmReleaseIfClear"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$AttemptState"
                        + "#closeView");

        var lease = classNode(
                "/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$ViewLease.class");
        assertThat(invocations(lease, "close"))
                .contains("net/minecraft/world/entity/player/Inventory#setSelectedSlot");
        assertThat(invocations(lease, "undisturbed"))
                .contains("net/minecraft/world/entity/player/Inventory#getSelectedSlot");
    }

    @Test
    void unownedScreenDoesNotBlockSlotReleaseButOwnedScreenStillRequiresFullClearance() {
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                false, ScreenOwnershipSignals.Phase.IDLE, false)).isTrue();

        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                true, ScreenOwnershipSignals.Phase.IDLE, true)).isTrue();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                true, ScreenOwnershipSignals.Phase.IDLE, false)).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.releaseScreenContextClear(
                false, ScreenOwnershipSignals.Phase.CLOSING, false)).isFalse();
    }

    private static List<String> containerInputs(ClassNode node, String methodName) {
        var inputs = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals("net/minecraft/world/inventory/ContainerInput")) {
                        inputs.add(field.name);
                    }
                });
        return inputs;
    }

    private static List<String> interactionHands(ClassNode node, String methodName) {
        var hands = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals("net/minecraft/world/InteractionHand")) {
                        hands.add(field.name);
                    }
                });
        return hands;
    }

    private static List<String> fieldReads(ClassNode node, String methodName, String owner) {
        var fields = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field && field.owner.equals(owner)) {
                        fields.add(field.name);
                    }
                });
        return fields;
    }

    private ClassNode classNode() throws Exception {
        return classNode("/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort.class");
    }

    private ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        var local = getClass().getResourceAsStream(resource);
        if (local == null && resource.startsWith("/net/minecraft/")) {
            String binaryName = resource.substring(1, resource.length() - ".class".length())
                    .replace('/', '.');
            Class<?> targetClass = Class.forName(binaryName);
            local = targetClass.getModule().getResourceAsStream(resource.substring(1));
        }
        try (var stream = local) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static List<String> invocations(ClassNode node, String methodName) {
        var calls = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call.owner + "#" + call.name);
                    }
                });
        return calls;
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    private static ItemStack testItemStack(Item item, int count) {
        return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), count);
    }

    private static final class CustomFirstUseItem extends Item {
        private CustomFirstUseItem() {
            super(new Item.Properties());
        }

        @Override
        public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
            return InteractionResult.SUCCESS;
        }
    }

    private static class CustomSneakBypassItem extends Item {
        private CustomSneakBypassItem() {
            super(new Item.Properties());
        }

        @Override
        public boolean doesSneakBypassUse(
                ItemStack stack, LevelReader level, BlockPos pos, Player player) {
            return true;
        }
    }

    private static final class InheritedSneakBypassItem extends CustomSneakBypassItem {
        private InheritedSneakBypassItem() {
            super();
        }
    }

    private static MinecraftPhaseFiveInventoryPort.AttemptState transferState(
            boolean store, int minimum, int maximumCount, int maximumStacks) {
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        return new MinecraftPhaseFiveInventoryPort.AttemptState(inventoryRequest(target, null),
                new InventoryParameters.TransferParameters(
                        store, "minecraft:stone", "default_components_only", minimum,
                        maximumCount, maximumStacks, true, 8.0D, target,
                        new BlockStateFingerprint("minecraft:chest", Map.of("type", "left"))));
    }

    private static PhaseFiveRequest inventoryRequest(
            BlockTarget target, Map<String, Object> aimPoint) {
        return new PhaseFiveRequest(
                "transfer_items",
                aimPoint == null ? Map.of() : Map.of("aim_point", aimPoint),
                new PhaseFiveBounds(
                        target.dimension(), target, target, 0, 20, false),
                0,
                "items");
    }

    private static ClientRecipeCatalog.RecipeView recipe(
            String requiredScreen, boolean supported) {
        return new ClientRecipeCatalog.RecipeView(
                "recipe-ref", "fingerprint", "shaped", requiredScreen, supported,
                supported ? null : "unsupported",
                new ClientRecipeCatalog.Result(true, List.of(
                        new ClientRecipeCatalog.ResultAlternative(
                                "minecraft:stick", 4, "stack-fingerprint"))),
                List.of(
                        new ClientRecipeCatalog.IngredientView(
                                0, 1, List.of("minecraft:oak_planks")),
                        new ClientRecipeCatalog.IngredientView(
                                1, 1, List.of("minecraft:oak_planks"))),
                new ClientRecipeCatalog.Shape(1, 2));
    }
}
