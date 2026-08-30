package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerSnapshot;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftPhaseFiveInventoryPortTest {
    private static final int DEFAULT_HASH = 41;

    @Test
    void fullStackSelectionIgnoresCustomComponentsAndRespectsRemainingCap() {
        var stacks = List.of(
                stack("minecraft:stone", 32, 99),
                stack("minecraft:stone", 16, DEFAULT_HASH),
                stack("minecraft:stone", 8, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.chooseFullStackSlot(
                stacks, List.of(0, 1, 2), "minecraft:stone", DEFAULT_HASH, 12))
                .contains(2);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseFullStackSlot(
                stacks, List.of(0, 1), "minecraft:stone", DEFAULT_HASH, 12))
                .isEmpty();
    }

    @Test
    void packetSnapshotCountIncludesOnlyExactDefaultComponentStacks() {
        var stacks = List.of(
                stack("minecraft:stone", 12, DEFAULT_HASH),
                stack("minecraft:stone", 4, 99),
                stack("minecraft:dirt", 7, DEFAULT_HASH),
                StackFingerprint.EMPTY);

        assertThat(MinecraftPhaseFiveInventoryPort.countExact(
                stacks, List.of(0, 1, 2, 3), "minecraft:stone", DEFAULT_HASH))
                .isEqualTo(12);
    }

    @Test
    void unavailableTransferReportsBoundedSourceItemChoices() {
        var stacks = List.of(
                stack("minecraft:wheat_seeds", 32, DEFAULT_HASH),
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:wheat_seeds", 16, DEFAULT_HASH),
                StackFingerprint.EMPTY);

        assertThat(MinecraftPhaseFiveInventoryPort.availableItemEvidence(
                stacks, List.of(0, 1, 2, 3), 16))
                .containsEntry("available_source_items_truncated", false)
                .containsEntry("available_source_items", List.of(
                        java.util.Map.of("item", "minecraft:diamond_hoe", "count", 1),
                        java.util.Map.of("item", "minecraft:wheat_seeds", "count", 48)));
        assertThat(MinecraftPhaseFiveInventoryPort.availableItemEvidence(
                stacks, List.of(0, 1, 2, 3), 1))
                .containsEntry("available_source_items_truncated", true);
    }

    @Test
    void transferReadbackRequiresEqualFullStackDecreaseAndIncrease() {
        var confirmed = MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 24, 19, 16, 18);
        assertThat(confirmed.exactMove()).isTrue();
        assertThat(confirmed.goalVerified()).isTrue();

        assertThat(MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 24, 18, 16, 18).exactMove()).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.verifyTransferReadback(
                40, 3, 25, 19, 16, 18).exactMove()).isFalse();
    }

    @Test
    void itemIdTransferCanBindAndMoveADamagedToolStack() {
        var stacks = List.of(
                stack("minecraft:diamond_hoe", 1, 99),
                stack("minecraft:diamond_hoe", 1, DEFAULT_HASH),
                stack("minecraft:wheat_seeds", 16, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.countTransfer(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, false))
                .isEqualTo(2);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", 0, 1, false))
                .contains(0);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseTransferSlot(
                stacks, List.of(0, 1, 2), "minecraft:diamond_hoe", DEFAULT_HASH, 1, true))
                .contains(1);
    }

    @Test
    void craftReadbackRejectsNoOpAndMultipleCraftDelta() {
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 6, 4, 6))
                .isEqualTo(new MinecraftPhaseFiveInventoryPort.CraftReadback(true, true));
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 2, 4, 6).exactlyOneCraft()).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.verifyCraftReadback(
                2, 10, 4, 6).exactlyOneCraft()).isFalse();
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
    void craftPreparationAndReadbackRequireConservedEmptyGridAndOneIngredientSet() {
        var slots = emptySlots(46);
        assertThat(MinecraftPhaseFiveInventoryPort.craftingGridAndResultEmpty(slots)).isTrue();

        slots.set(0, stack("minecraft:stick", 4, DEFAULT_HASH));
        slots.set(1, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        slots.set(2, stack("minecraft:oak_planks", 1, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isTrue();

        slots.set(1, stack("minecraft:oak_planks", 2, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.exactlyOneCraftPrepared(
                slots, recipe("crafting_table", true).ingredients())).isFalse();
        assertThat(MinecraftPhaseFiveInventoryPort.craftingGridAndResultEmpty(slots)).isFalse();
    }

    @Test
    void craftOutputUsesOnlyCompatibleCapacityOrAnEmptyPlayerSlot() {
        var slots = emptySlots(46);
        slots.set(10, stack("minecraft:stick", 60, DEFAULT_HASH));

        assertThat(MinecraftPhaseFiveInventoryPort.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 4, 64))
                .contains(10);
        assertThat(MinecraftPhaseFiveInventoryPort.chooseCraftDestinationSlot(
                slots, List.of(10, 11), "minecraft:stick", DEFAULT_HASH, 5, 64))
                .contains(11);

        var before = slots.get(10);
        slots.set(10, stack("minecraft:stick", 64, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.craftDestinationConfirmed(
                slots, 10, before, "minecraft:stick", DEFAULT_HASH, 4)).isTrue();
        slots.set(10, stack("minecraft:stick", 63, DEFAULT_HASH));
        assertThat(MinecraftPhaseFiveInventoryPort.craftDestinationConfirmed(
                slots, 10, before, "minecraft:stick", DEFAULT_HASH, 4)).isFalse();
    }

    @Test
    void interruptedCraftCanReturnOnlyTheExactCursorStackToTheUnchangedDestination() {
        var slots = emptySlots(46);
        var before = stack("minecraft:stick", 60, DEFAULT_HASH);
        slots.set(10, before);
        var safe = new ContainerSnapshot(
                UUID.randomUUID(), 1, "minecraft:crafting", 1, slots,
                stack("minecraft:stick", 4, DEFAULT_HASH), 2, 3);

        assertThat(MinecraftPhaseFiveInventoryPort.craftCursorReturnSafe(
                safe, 10, before, "minecraft:stick", DEFAULT_HASH, 4)).isTrue();
        slots.set(10, stack("minecraft:stick", 61, DEFAULT_HASH));
        var changed = new ContainerSnapshot(
                safe.worldSessionId(), 1, "minecraft:crafting", 2, slots,
                safe.carried(), 3, 4);
        assertThat(MinecraftPhaseFiveInventoryPort.craftCursorReturnSafe(
                changed, 10, before, "minecraft:stick", DEFAULT_HASH, 4)).isFalse();
    }

    @Test
    void transferAllowsOnlyTheSameSingleContainerWhileBarrelOpenStateEvolves() {
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
    }

    @Test
    void craftingAloneRetainsTheStationHeadingAndUsesTheRuntimeCameraLimit() throws Exception {
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var parameters = new MinecraftPhaseFiveInventoryPort.CraftParameters(
                "recipe-ref", "fingerprint", "minecraft:stick", 1, 1, target,
                new BlockStateFingerprint("minecraft:crafting_table", Map.of()));
        assertThat(parameters.restoreViewOnRelease()).isFalse();
        var transfer = new MinecraftPhaseFiveInventoryPort.TransferParameters(
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
    void ownedContainerCleanupUsesTheCanonicalScreenCloseLifecycle() throws Exception {
        var node = classNode();

        assertThat(invocations(node, "closeOwnedMenuClient"))
                .contains("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                        + "#onClose")
                .doesNotContain("net/minecraft/client/player/LocalPlayer#closeContainer");
        assertThat(invocations(node, "closeForReadback"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
        assertThat(invocations(node, "releaseOwnedMenu"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#closeOwnedMenuClient");
    }

    @Test
    void recipePlacementReusesEmptyProofButBothCraftPickupsRequireFreshCursorProof()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "prepareOwnedDispatch"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort#packetRevision",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
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
        assertThat(invocations(node, "maintainCraftResultPickupAck"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshServerCursorSnapshot",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchContainerClick");
        assertThat(invocations(node, "maintainClickAck"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#freshServerCursorSnapshot",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#craftDestinationConfirmed",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#closeForReadback");
        assertThat(invocations(node, "acceptCraftSnapshot"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#craftingGridAndResultEmpty",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#dispatchRecipePlacement");
        assertThat(invocations(node, "maintainCraftResult"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#freshServerCursorSnapshot");
        assertThat(invocations(node, "acceptTransferSnapshot"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#dispatchContainerClick")
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
                .containsExactly("PICKUP");
        assertThat(containerInputs(node, "maintainCraftResultPickupAck"))
                .containsExactly("PICKUP");
    }

    @Test
    void expectedOpenRetainsCausalAuthorityUntilPredictionAckOrOwnedCleanup()
            throws Exception {
        var node = classNode();

        assertThat(invocations(node, "dispatchExpectedOpen"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#ongoingFailure",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$OpenHandPlan"
                                + "#ready",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort$OpenHandPlan"
                                + "#hand",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#sequenceBeforePrediction",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#captureIssuedPredictions",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#cancelRoutineAfterPredictedUse");
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
    void safetyAndSlotOwnershipAreRecheckedAndReleasedBeforeTerminal() throws Exception {
        var node = classNode();
        assertThat(invocations(node, "preflight"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                                + "#basicPlayerSafety",
                        "dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
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
        assertThat(invocations(node, "chooseOpenHand"))
                .contains(
                        "net/minecraft/client/player/LocalPlayer#getOffhandItem",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort"
                                + "#safeNormalUseStack");
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

    private ClassNode classNode() throws Exception {
        return classNode("/dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort.class");
    }

    private ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(resource)) {
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

    private static ArrayList<StackFingerprint> emptySlots(int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, StackFingerprint.EMPTY));
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
