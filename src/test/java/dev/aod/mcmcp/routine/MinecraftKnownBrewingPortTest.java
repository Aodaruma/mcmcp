package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerDataEvidence;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.OpenScreenEvidence;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftKnownBrewingPortTest {
    private static final int INPUT_HASH = 101;
    private static final int OUTPUT_HASH = 102;
    private static final int BLAZE_HASH = 103;
    private static final int OTHER_HASH = 104;

    @Test
    void emptyStandAdmissionAllowsBoundedPrechargedFuelButNeverActiveBrew() {
        var empty = emptyMenu();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 0, 0)).isTrue();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 0, 1)).isTrue();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 0, 20)).isTrue();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 1, 0)).isFalse();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 0, -1)).isFalse();
        assertThat(MinecraftKnownBrewingPort.initialStandReady(empty, 0, 21)).isFalse();

        for (int standSlot = 0; standSlot <= 4; standSlot++) {
            var occupied = new ArrayList<>(empty);
            occupied.set(standSlot, stack("minecraft:stone", 1, OTHER_HASH));
            assertThat(MinecraftKnownBrewingPort.initialStandReady(occupied, 0, 0))
                    .as("stand slot %s must be empty", standSlot)
                    .isFalse();
        }
        assertThat(MinecraftKnownBrewingPort.standSlotsEmpty(empty.subList(0, 40)))
                .isFalse();
    }

    @Test
    void singletonLoadPlanRejectsBulkAndReservesDistinctMatchingSources() {
        var slots = emptyMenu();
        var blaze = key("minecraft:blaze_powder", BLAZE_HASH);
        slots.set(5, stack("minecraft:blaze_powder", 2, BLAZE_HASH));
        slots.set(6, stack("minecraft:blaze_powder", 1, BLAZE_HASH));
        slots.set(7, stack("minecraft:blaze_powder", 1, BLAZE_HASH));

        assertThat(MinecraftKnownBrewingPort.chooseSingletonSource(slots, blaze, List.of()))
                .contains(6);
        assertThat(MinecraftKnownBrewingPort.chooseSingletonSource(slots, blaze, List.of(6)))
                .contains(7);
        assertThat(MinecraftKnownBrewingPort.chooseSingletonSource(
                slots, blaze, List.of(6, 7))).isEmpty();
    }

    @Test
    void fuelPlanCoversEveryBoundaryWithoutExposingTheHiddenCounter() {
        assertThat(MinecraftKnownBrewingPort.fuelPlan(0))
                .isEqualTo(new MinecraftKnownBrewingPort.FuelPlan(true, 19));
        assertThat(MinecraftKnownBrewingPort.fuelPlan(1))
                .isEqualTo(new MinecraftKnownBrewingPort.FuelPlan(false, 0));
        assertThat(MinecraftKnownBrewingPort.fuelPlan(2))
                .isEqualTo(new MinecraftKnownBrewingPort.FuelPlan(false, 1));
        assertThat(MinecraftKnownBrewingPort.fuelPlan(19))
                .isEqualTo(new MinecraftKnownBrewingPort.FuelPlan(false, 18));
        assertThat(MinecraftKnownBrewingPort.fuelPlan(20))
                .isEqualTo(new MinecraftKnownBrewingPort.FuelPlan(false, 19));
        assertThatThrownBy(() -> MinecraftKnownBrewingPort.fuelPlan(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinecraftKnownBrewingPort.fuelPlan(21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cameraTurnUsesTheActionTotalPerTickLimitInBothDirections() {
        float admittedLimit = 1.25F;
        var forward = MinecraftKnownBrewingPort.boundedTurn(
                0.0F, 0.0F, 90.0F, 45.0F,
                admittedLimit);
        var restore = MinecraftKnownBrewingPort.boundedTurn(
                90.0F, 45.0F, 0.0F, 0.0F,
                admittedLimit);

        assertThat(Math.abs(forward.yaw()) + Math.abs(forward.pitch()))
                .isEqualTo(admittedLimit);
        assertThat(Math.abs(restore.yaw()) + Math.abs(restore.pitch()))
                .isEqualTo(admittedLimit);
        assertThat(MinecraftKnownBrewingPort.aimTimeoutTicks(0.75F))
                .isGreaterThanOrEqualTo(380);
    }

    @Test
    void oneWayCameraPreflightUsesTheSameYawPlusPitchBoundAsTheBudget() {
        assertThat(MinecraftKnownBrewingPort.oneWayCameraDegrees(
                0.0F, 0.0F, 180.0F, 90.0F))
                .isEqualTo(KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        assertThat(MinecraftKnownBrewingPort.oneWayCameraDegrees(
                0.0F, -0.25F, 180.0F, 90.0F))
                .isGreaterThan(KnownBrewingRequest.MAX_ONE_WAY_CAMERA_DEGREES);
        assertThat(MinecraftKnownBrewingPort.oneWayCameraDegrees(
                179.0F, 0.0F, -179.0F, 0.0F)).isEqualTo(2.0D);
        assertThatThrownBy(() -> MinecraftKnownBrewingPort.oneWayCameraDegrees(
                Float.NaN, 0.0F, 0.0F, 0.0F))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullHotbarFallbackIsClosedToDefaultBaseItems() {
        assertThat(MinecraftKnownBrewingPort.safeNormalUseStack(
                testStack(Items.BLAZE_POWDER))).isTrue();
        assertThat(MinecraftKnownBrewingPort.safeNormalUseStack(
                testStack(Blocks.STONE.asItem()))).isFalse();
        assertThat(MinecraftKnownBrewingPort.safeNormalUseStack(ItemStack.EMPTY)).isFalse();
    }

    @Test
    void potionSourcePlanIsClosedToVanillaSingletonStacks() throws Exception {
        ClassNode items = classNode(net.minecraft.world.item.Items.class);
        assertThat(itemRegistrationUsesSingletonStack(items, "POTION")).isTrue();
        assertThat(itemRegistrationUsesSingletonStack(items, "SPLASH_POTION")).isTrue();
        assertThat(itemRegistrationUsesSingletonStack(items, "LINGERING_POTION")).isTrue();

        ClassNode menu = classNode(net.minecraft.world.inventory.BrewingStandMenu.class);
        assertThat(invocations(menu, "quickMoveStack"))
                .containsSubsequence(
                        "net/minecraft/world/inventory/BrewingStandMenu$PotionSlot#mayPlaceItem",
                        "net/minecraft/world/inventory/BrewingStandMenu#moveItemStackTo");
        assertThat(potionQuickMoveUsesBottleRange(menu)).isTrue();

        var slots = emptyMenu();
        slots.set(5, stack("minecraft:potion", 1, INPUT_HASH));
        slots.set(6, stack("minecraft:potion", 2, INPUT_HASH));
        slots.set(7, stack("minecraft:potion", 1, INPUT_HASH));
        slots.set(8, stack("minecraft:potion", 1, 999));
        var input = new MinecraftKnownBrewingPort.StackKey(
                "minecraft:potion", INPUT_HASH);

        assertThat(MinecraftKnownBrewingPort.chooseExactPotionSources(
                slots, List.of(5, 6, 7, 8), input, 2))
                .contains(List.of(5, 7));
        assertThat(MinecraftKnownBrewingPort.chooseExactPotionSources(
                slots, List.of(6, 8), input, 2)).isEmpty();
        assertThat(MinecraftKnownBrewingPort.chooseExactPotionSources(
                slots, List.of(5, 7), input, 3)).isEmpty();
    }

    @Test
    void exactInventoryReadbackAllowsOnlyTheDeclaredBrewDelta() {
        var input = key("minecraft:potion", INPUT_HASH);
        var output = key("minecraft:potion", OUTPUT_HASH);
        var fuel = key("minecraft:blaze_powder", BLAZE_HASH);
        var ingredient = key("minecraft:nether_wart", OTHER_HASH);
        var unrelated = key("minecraft:cobblestone", OTHER_HASH);
        Map<MinecraftKnownBrewingPort.StackKey, Integer> baseline = Map.of(
                input, 3, fuel, 4, ingredient, 2, unrelated, 17);

        var expected = MinecraftKnownBrewingPort.expectedInventoryAfterBrew(
                baseline, input, 3, fuel, true, ingredient, output, 3, null);
        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                fuel, 3, ingredient, 1, output, 3, unrelated, 17));
        assertThat(MinecraftKnownBrewingPort.inventoryReadbackMatches(expected, expected))
                .isTrue();

        var unrelatedChanged = new HashMap<>(expected);
        unrelatedChanged.put(unrelated, 18);
        assertThat(MinecraftKnownBrewingPort.inventoryReadbackMatches(
                expected, unrelatedChanged)).isFalse();
    }

    @Test
    void emptyStackNeverMatchesAConcreteStackKey() {
        assertThat(MinecraftKnownBrewingPort.matches(
                StackFingerprint.EMPTY, key("minecraft:potion", OUTPUT_HASH), 0))
                .isFalse();
    }

    @Test
    void strengthBrewingConsumesTwoBlazePowderAndFitsInteractionCap() {
        var input = key("minecraft:potion", INPUT_HASH);
        var output = key("minecraft:potion", OUTPUT_HASH);
        var blaze = key("minecraft:blaze_powder", BLAZE_HASH);
        var expected = MinecraftKnownBrewingPort.expectedInventoryAfterBrew(
                Map.of(input, 3, blaze, 5),
                input, 3, blaze, true, blaze, output, 3, null);

        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                blaze, 3, output, 3));
        int worstCaseInteractions = 3 // initial, loaded-checkpoint and final opens
                + 1 // singleton fuel QUICK_MOVE
                + 3 // three singleton potion QUICK_MOVEs
                + 1 // singleton ingredient QUICK_MOVE
                + 1 // possible crafting remainder QUICK_MOVE
                + 3; // three output QUICK_MOVEs
        assertThat(worstCaseInteractions)
                .isLessThanOrEqualTo(KnownBrewingRequest.MAX_INTERACTIONS);
    }

    @Test
    void prechargedFuelConsumesNoInventoryFuelAndLowersInteractionDemand() {
        var input = key("minecraft:potion", INPUT_HASH);
        var output = key("minecraft:potion", OUTPUT_HASH);
        var blaze = key("minecraft:blaze_powder", BLAZE_HASH);
        var expected = MinecraftKnownBrewingPort.expectedInventoryAfterBrew(
                Map.of(input, 3, blaze, 1),
                input, 3, blaze, false, blaze, output, 3, null);

        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(output, 3));
        int prechargedWorstCaseInteractions = 3 // initial, loaded-checkpoint and final opens
                + 3 // three singleton potion QUICK_MOVEs
                + 1 // singleton ingredient QUICK_MOVE
                + 1 // possible crafting remainder QUICK_MOVE
                + 3; // three output QUICK_MOVEs
        assertThat(prechargedWorstCaseInteractions)
                .isLessThanOrEqualTo(KnownBrewingRequest.MAX_INTERACTIONS);
    }

    @Test
    void craftingRemainderIsIncludedInTheExactDelta() {
        var input = key("minecraft:splash_potion", INPUT_HASH);
        var output = key("minecraft:lingering_potion", OUTPUT_HASH);
        var fuel = key("minecraft:blaze_powder", BLAZE_HASH);
        var ingredient = key("minecraft:dragon_breath", OTHER_HASH);
        var bottle = key("minecraft:glass_bottle", OTHER_HASH + 1);

        var expected = MinecraftKnownBrewingPort.expectedInventoryAfterBrew(
                Map.of(input, 1, fuel, 1, ingredient, 1),
                input, 1, fuel, true, ingredient, output, 1, bottle);
        assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                output, 1, bottle, 1));
    }

    @Test
    void packetMenuTypeAndPlayerSlotOrderMustBeExact() {
        UUID session = UUID.randomUUID();
        var open = new OpenScreenEvidence(
                session, 7, "minecraft:brewing_stand", 10, 20);
        var data = new ContainerDataEvidence(
                session, 7, "minecraft:brewing_stand", 0, 400, 11, 21);
        assertThat(MinecraftKnownBrewingPort.freshDataForOpen(open, data, 7)).isTrue();

        var spoofedOpen = new OpenScreenEvidence(
                session, 7, "minecraft:generic_9x3", 10, 20);
        var spoofedData = new ContainerDataEvidence(
                session, 7, "minecraft:generic_9x3", 0, 400, 11, 21);
        assertThat(MinecraftKnownBrewingPort.freshDataForOpen(spoofedOpen, data, 7))
                .isFalse();
        assertThat(MinecraftKnownBrewingPort.freshDataForOpen(open, spoofedData, 7))
                .isFalse();
        assertThat(MinecraftKnownBrewingPort.freshDataForOpen(
                open, new ContainerDataEvidence(
                        session, 7, "minecraft:brewing_stand", 0, 400, 11, 20), 7))
                .isFalse();

        var canonical = new ArrayList<Integer>(36);
        for (int index = 0; index < 36; index++) {
            canonical.add(index < 27 ? index + 9 : index - 27);
        }
        assertThat(MinecraftKnownBrewingPort.exactPlayerSlotOrder(canonical)).isTrue();
        var reordered = new ArrayList<>(canonical);
        int first = reordered.get(0);
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);
        assertThat(MinecraftKnownBrewingPort.exactPlayerSlotOrder(reordered)).isFalse();
    }

    @Test
    void openClicksAndCleanupUseOnlyTheOwnedSemanticPaths() throws Exception {
        ClassNode node = classNode();

        assertThat(invocations(node, "dispatchExpectedOpen"))
                .containsSubsequence(
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#beginExpectedOpen",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#sequenceBeforePrediction",
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#useItemOn",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals$PredictionAttempt"
                                + "#captureIssuedPredictions");
        var clickCalls = invocations(node, "dispatchQuickMove");
        assertThat(clickCalls)
                .contains("net/minecraft/client/multiplayer/MultiPlayerGameMode#handleContainerInput")
                .doesNotContain(
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals#invalidateServerCursorProof",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#packetRevision");
        assertThat(clickCalls.stream()
                .filter(call -> call.endsWith("#handleContainerInput")))
                .hasSize(1);
        assertThat(fieldReads(node))
                .contains("net/minecraft/world/inventory/ContainerInput#QUICK_MOVE")
                .doesNotContain("net/minecraft/world/inventory/ContainerInput#PICKUP");
        assertThat(invocations(node, "acceptInitialSnapshot"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#chooseSingletonSource",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#expectedInventoryAfterLoad");
        assertThat(invocations(node, "acceptLoadedSnapshot"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#inventoryReadbackMatches");
        assertThat(invocations(node, "acceptFinalSnapshot"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#inventoryReadbackMatches");
        assertThat(stringConstants(node, "acceptFinalSnapshot"))
                .containsSubsequence(
                        "BREWING_FINAL_STAND_NOT_EMPTY",
                        "BREWING_FINAL_DATA_MISMATCH",
                        "BREWING_FINAL_CURSOR_NOT_EMPTY",
                        "BREWING_FINAL_INVENTORY_DELTA_MISMATCH",
                        "BREWING_FINAL_OUTPUT_COMPONENT_MISMATCH");
        assertThat(invocations(node, "closeOwnedMenuClient"))
                .contains("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen#onClose")
                .doesNotContain("net/minecraft/client/player/LocalPlayer#closeContainer");
        assertThat(fieldWritePrecedesInvocation(
                node, "dispatchExpectedOpen", "openCount",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode", "useItemOn"))
                .isTrue();
        assertThat(fieldWritePrecedesInvocation(
                node, "dispatchQuickMove", "containerClicks",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode", "handleContainerInput"))
                .isTrue();
        assertThat(invocations(node, "observe"))
                .containsSubsequence(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState#releasingOrTerminal",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#ongoingFailure");
        assertThat(invocations(node, "maintainTerminalRelease"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState"
                                + "#ownershipContextLost",
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#releaseRoutineOnIdentityLoss",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort"
                                + "#cancelScreenAuthority");
    }

    @Test
    void brewingEvidenceComparesPacketDataAndLiveMenuCounters() throws Exception {
        ClassNode node = classNode();
        assertThat(invocations(node, "brewingView"))
                .contains("dev/aod/mcmcp/runtime/ContainerSyncSignals#snapshot")
                .contains("net/minecraft/world/inventory/BrewingStandMenu#getBrewingTicks")
                .contains("net/minecraft/world/inventory/BrewingStandMenu#getFuel");
        assertThat(invocations(node, "exactBrewingLayout"))
                .contains("net/minecraft/world/inventory/Slot#getContainerSlot")
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#exactPlayerSlotOrder");
    }

    @Test
    void terminalIntentStaysPrivateUntilTheReleaseStatePublishesIt() throws Exception {
        ClassNode node = classNode();

        assertThat(fieldReads(node, "evidence"))
                .doesNotContain(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState"
                                + "#terminalIntent")
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState#result",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState#failure",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState"
                                + "#inconclusive");
    }

    @Test
    void admittedCameraLimitAndPlayerIdentityGuardBothAimAndRestore() throws Exception {
        ClassNode lease = classNode(
                "/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$ViewSlotLease.class");
        String rate = "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$ViewSlotLease"
                + "#maxCameraDegreesPerTick";

        assertThat(fieldReads(lease, "turnToward")).contains(rate);
        assertThat(fieldReads(lease, "releaseStep")).contains(rate);
        assertThat(fieldReads(lease, "ownershipContextLost"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$ViewSlotLease"
                                + "#playerIdentity",
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$ViewSlotLease"
                                + "#levelIdentity");
    }

    @Test
    void deliveryBackedAimDrivesPreflightAndEveryOpenWithoutWeakeningExactHit()
            throws Exception {
        ClassNode node = classNode();

        assertThat(invocations(node, "aimPoint"))
                .contains("dev/aod/mcmcp/routine/MinecraftPhaseFiveInventoryPort"
                        + "#inventoryAimPoint");
        assertThat(invocations(node, "initialPreflight"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#aimPoint");
        assertThat(fieldReads(node, "maintainAim"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort$AttemptState"
                        + "#aimPoint");
        assertThat(invocations(node, "maintainAim"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#targetReadyForOpen");
        assertThat(invocations(node, "dispatchExpectedOpen"))
                .contains(
                        "dev/aod/mcmcp/routine/MinecraftKnownBrewingPort#exactHit",
                        "dev/aod/mcmcp/runtime/ClientPredictionSignals#begin");
    }

    private static ClassNode classNode() throws Exception {
        return classNode("/dev/aod/mcmcp/routine/MinecraftKnownBrewingPort.class");
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = MinecraftKnownBrewingPortTest.class.getResourceAsStream(
                resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static ClassNode classNode(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static boolean itemRegistrationUsesSingletonStack(
            ClassNode node, String itemField) {
        var initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        for (var instruction = initializer.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || instruction.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals("net/minecraft/world/item/Items")
                    || !field.name.equals(itemField)) {
                continue;
            }
            int inspected = 0;
            for (var prior = instruction.getPrevious(); prior != null && inspected < 40;
                    prior = prior.getPrevious(), inspected++) {
                if (prior instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/item/Item$Properties")
                        && call.name.equals("stacksTo")) {
                    for (var count = prior.getPrevious(); count != null;
                            count = count.getPrevious()) {
                        if (count.getOpcode() < 0) continue;
                        return integerConstant(count) == 1;
                    }
                }
            }
            return false;
        }
        return false;
    }

    private static boolean potionQuickMoveUsesBottleRange(ClassNode node) {
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("quickMoveStack"))
                .findFirst().orElseThrow();
        boolean potionEligible = false;
        var recentConstants = new ArrayList<Integer>();
        for (var instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(
                            "net/minecraft/world/inventory/BrewingStandMenu$PotionSlot")
                    && call.name.equals("mayPlaceItem")) {
                potionEligible = true;
                recentConstants.clear();
                continue;
            }
            if (!potionEligible) continue;
            Integer value = integerConstant(instruction);
            if (value != null) recentConstants.add(value);
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/inventory/BrewingStandMenu")
                    && call.name.equals("moveItemStackTo")) {
                return recentConstants.equals(List.of(0, 3, 0));
            }
        }
        return false;
    }

    private static Integer integerConstant(org.objectweb.asm.tree.AbstractInsnNode node) {
        return switch (node.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            default -> node instanceof IntInsnNode integer ? integer.operand : null;
        };
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

    private static List<String> stringConstants(ClassNode node, String methodName) {
        var constants = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof LdcInsnNode constant
                            && constant.cst instanceof String value) {
                        constants.add(value);
                    }
                });
        return constants;
    }

    private static List<String> fieldReads(ClassNode node) {
        var fields = new ArrayList<String>();
        node.methods.forEach(method -> method.instructions.forEach(instruction -> {
            if (instruction instanceof FieldInsnNode field) {
                fields.add(field.owner + "#" + field.name);
            }
        }));
        return fields;
    }

    private static List<String> fieldReads(ClassNode node, String methodName) {
        var fields = new ArrayList<String>();
        node.methods.stream()
                .filter(method -> method.name.equals(methodName))
                .findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field
                            && instruction.getOpcode() == Opcodes.GETFIELD) {
                        fields.add(field.owner + "#" + field.name);
                    }
                });
        return fields;
    }

    private static boolean fieldWritePrecedesInvocation(
            ClassNode node,
            String methodName,
            String fieldName,
            String invocationOwner,
            String invocationName) {
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .findFirst().orElseThrow();
        int index = 0;
        int fieldWrite = -1;
        int invocation = -1;
        for (var instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext(), index++) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals(fieldName)) {
                fieldWrite = index;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(invocationOwner)
                    && call.name.equals(invocationName)) {
                invocation = index;
                break;
            }
        }
        return fieldWrite >= 0 && invocation >= 0 && fieldWrite < invocation;
    }

    private static ArrayList<StackFingerprint> emptyMenu() {
        return new ArrayList<>(java.util.Collections.nCopies(
                MinecraftKnownBrewingPort.MENU_SLOT_COUNT, StackFingerprint.EMPTY));
    }

    private static MinecraftKnownBrewingPort.StackKey key(String item, int hash) {
        return new MinecraftKnownBrewingPort.StackKey(item, hash);
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    /** NeoForge's isolated JUnit loader does not bind Vanilla item defaults. */
    private static ItemStack testStack(Item item) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
        }
        return new ItemStack(item);
    }
}
