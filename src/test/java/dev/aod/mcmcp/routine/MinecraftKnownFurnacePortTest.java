package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.observation.ClientRecipeCatalog;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.ContainerDataEvidence;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.OpenScreenEvidence;
import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinecraftKnownFurnacePortTest {
    private static final int INGREDIENT_HASH = 101;
    private static final int FUEL_HASH = 102;
    private static final int OUTPUT_HASH = 103;

    @Test
    void privateRequestIsExactAndDoesNotWidenPublicRoutineKinds() {
        for (var family : MinecraftKnownFurnacePort.FurnaceFamily.values()) {
            PhaseFiveRequest request = request(family.stationKind(), 1, "default_components_only");
            var parsed = MinecraftKnownFurnacePort.parseRequest(request);

            assertThat(parsed.family()).isEqualTo(family);
            assertThat(parsed.maxSmelts()).isOne();
            assertThat(parsed.minimumInventoryCount()).isEqualTo(4);
        }
        assertThat(PhaseFiveRequest.supportsAdapterKind("smelt_items")).isTrue();
        assertThat(PhaseFiveRequest.KINDS).doesNotContain("smelt_items");

        assertThatThrownBy(() -> MinecraftKnownFurnacePort.parseRequest(
                request("campfire", 1, "default_components_only")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MinecraftKnownFurnacePort.parseRequest(
                request("furnace", 64, "default_components_only")).maxSmelts())
                .isEqualTo(64);
        assertThatThrownBy(() -> MinecraftKnownFurnacePort.parseRequest(
                request("furnace", 65, "default_components_only")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinecraftKnownFurnacePort.parseRequest(
                request("furnace", 1, "item_id_any_components")))
                .isInstanceOf(IllegalArgumentException.class);

        PhaseFiveRequest base = request("furnace", 1, "default_components_only");
        var extra = new LinkedHashMap<>(base.parameters());
        extra.put("unexpected", true);
        assertThatThrownBy(() -> MinecraftKnownFurnacePort.parseRequest(
                new PhaseFiveRequest(base.kind(), extra, base.bounds(),
                        base.expectedUnits(), base.progressUnit())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyLitMayChangeAfterTheInitialExpectedState() {
        var expected = new BlockStateFingerprint("minecraft:furnace", Map.of(
                "facing", "north", "lit", "false"));
        assertThat(MinecraftKnownFurnacePort.sameExceptLit(expected,
                new BlockStateFingerprint("minecraft:furnace", Map.of(
                        "facing", "north", "lit", "true"))))
                .isTrue();
        assertThat(MinecraftKnownFurnacePort.sameExceptLit(expected,
                new BlockStateFingerprint("minecraft:furnace", Map.of(
                        "facing", "south", "lit", "true"))))
                .isFalse();
        assertThat(MinecraftKnownFurnacePort.sameExceptLit(expected,
                new BlockStateFingerprint("minecraft:smoker", Map.of(
                        "facing", "north", "lit", "true"))))
                .isFalse();
    }

    @Test
    void initialLoadedAndFinalDataProofsAreClosedAndFourWide() {
        assertThat(MinecraftKnownFurnacePort.initialDataReady(data(0, 0, 0, 0)))
                .isTrue();
        assertThat(MinecraftKnownFurnacePort.initialDataReady(data(1, 200, 0, 200)))
                .isFalse();
        assertThat(MinecraftKnownFurnacePort.smeltStarted(data(199, 200, 1, 200)))
                .isTrue();
        assertThat(MinecraftKnownFurnacePort.smeltStarted(data(0, 0, 0, 200)))
                .isFalse();
        assertThat(MinecraftKnownFurnacePort.finalDataReady(data(150, 200, 0, 200)))
                .isTrue();
        assertThat(MinecraftKnownFurnacePort.finalDataReady(data(150, 200, 1, 200)))
                .isFalse();
        assertThat(MinecraftKnownFurnacePort.finalDataReady(data(0, 0, 0)))
                .isFalse();

        UUID session = UUID.randomUUID();
        var open = new OpenScreenEvidence(session, 7, "minecraft:furnace", 10, 20);
        var fresh = new ContainerDataEvidence(
                session, 7, "minecraft:furnace", 0, 200, 11, 21);
        assertThat(MinecraftKnownFurnacePort.freshDataForOpen(
                open, fresh, 7, "minecraft:furnace")).isTrue();
        assertThat(MinecraftKnownFurnacePort.freshDataForOpen(
                open, fresh, 7, "minecraft:smoker")).isFalse();
        assertThat(MinecraftKnownFurnacePort.freshDataForOpen(
                open, new ContainerDataEvidence(
                        session, 7, "minecraft:furnace", 0, 200, 11, 20),
                7, "minecraft:furnace")).isFalse();
    }

    @Test
    void inputMustBeTheExactBatchAndFuelMayBeARecoverableStack() {
        var slots = emptyMenu();
        var ingredient = key("minecraft:raw_iron", INGREDIENT_HASH);
        var alternate = key("minecraft:raw_gold", INGREDIENT_HASH + 1);
        var fuel = key("minecraft:coal", FUEL_HASH);
        slots.set(3, stack("minecraft:raw_iron", 63, INGREDIENT_HASH));
        slots.set(4, stack("minecraft:raw_gold", 64, INGREDIENT_HASH + 1));
        slots.set(5, stack("minecraft:coal", 8, FUEL_HASH));

        assertThat(MinecraftKnownFurnacePort.chooseSources(
                slots, List.of(ingredient, alternate), fuel, 64, 8))
                .contains(new MinecraftKnownFurnacePort.SourcePlan(4, 5, alternate, 8));

        slots.set(5, stack("minecraft:coal", 7, FUEL_HASH));
        assertThat(MinecraftKnownFurnacePort.chooseSources(
                slots, List.of(ingredient, alternate), fuel, 64, 8)).isEmpty();
    }

    @Test
    void exactInventoryReadbackAllowsOnlyDeclaredBatchFuelAndResultDelta() {
        var ingredient = key("minecraft:raw_iron", INGREDIENT_HASH);
        var fuel = key("minecraft:coal", FUEL_HASH);
        var output = key("minecraft:iron_ingot", OUTPUT_HASH);
        var unrelated = key("minecraft:cobblestone", 999);
        Map<MinecraftKnownFurnacePort.StackKey, Integer> baseline = Map.of(
                ingredient, 64, fuel, 16, output, 3, unrelated, 9);

        var loaded = MinecraftKnownFurnacePort.expectedInventoryAfterLoad(
                baseline, ingredient, 64, fuel, 16);
        assertThat(loaded).containsExactlyInAnyOrderEntriesOf(Map.of(
                output, 3, unrelated, 9));
        var finished = MinecraftKnownFurnacePort.expectedInventoryAfterSmelt(
                baseline, ingredient, 64, fuel, 8, output, 64);
        assertThat(finished).containsExactlyInAnyOrderEntriesOf(Map.of(
                fuel, 8, output, 67, unrelated, 9));
        assertThat(MinecraftKnownFurnacePort.inventoryReadbackMatches(finished, finished))
                .isTrue();
        var changed = new HashMap<>(finished);
        changed.put(unrelated, 10);
        assertThat(MinecraftKnownFurnacePort.inventoryReadbackMatches(finished, changed))
                .isFalse();
    }

    @Test
    void fuelRequirementRoundsUpWithoutOverconsuming() {
        assertThat(MinecraftKnownFurnacePort.fuelItemsRequired(64, 200, 1_600))
                .isEqualTo(8);
        assertThat(MinecraftKnownFurnacePort.fuelItemsRequired(64, 200, 300))
                .isEqualTo(43);
        assertThatThrownBy(() ->
                MinecraftKnownFurnacePort.fuelItemsRequired(64, 201, 1_600))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedRecipeMustMatchExactFurnaceFamilyAndGoal() {
        var request = MinecraftKnownFurnacePort.parseRequest(
                request("blast_furnace", 1, "default_components_only"));
        MinecraftKnownFurnacePort.validateResolvedRecipe(
                request, recipe("blasting", "blast_furnace", "minecraft:iron_ingot"));

        assertThatThrownBy(() -> MinecraftKnownFurnacePort.validateResolvedRecipe(
                request, recipe("smelting", "furnace", "minecraft:iron_ingot")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinecraftKnownFurnacePort.validateResolvedRecipe(
                request, recipe("blasting", "blast_furnace", "minecraft:gold_ingot")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactFamiliesUseVanillaMenuScreenAndBlockEntityClasses() {
        assertThat(MinecraftKnownFurnacePort.FurnaceFamily.FURNACE.menuClass())
                .isEqualTo(net.minecraft.world.inventory.FurnaceMenu.class);
        assertThat(MinecraftKnownFurnacePort.FurnaceFamily.BLAST_FURNACE.screenClass())
                .isEqualTo(net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen.class);
        assertThat(MinecraftKnownFurnacePort.FurnaceFamily.SMOKER.blockEntityClass())
                .isEqualTo(net.minecraft.world.level.block.entity.SmokerBlockEntity.class);

        var canonical = new ArrayList<Integer>(36);
        for (int index = 0; index < 36; index++) {
            canonical.add(index < 27 ? index + 9 : index - 27);
        }
        assertThat(MinecraftKnownBrewingPort.exactPlayerSlotOrder(canonical)).isTrue();
        canonical.set(0, 10);
        assertThat(MinecraftKnownBrewingPort.exactPlayerSlotOrder(canonical)).isFalse();
    }

    @Test
    void clickPathIsSingleCursorInvariantQuickMoveAndAccountedBeforeDispatch()
            throws Exception {
        ClassNode node = classNode();
        List<String> calls = invocations(node, "dispatchQuickMove");
        assertThat(calls)
                .contains("net/minecraft/client/multiplayer/MultiPlayerGameMode"
                        + "#handleContainerInput")
                .doesNotContain("dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                        + "#invalidateServerCursorProof");
        assertThat(fieldReads(node))
                .contains("net/minecraft/world/inventory/ContainerInput#QUICK_MOVE")
                .doesNotContain("net/minecraft/world/inventory/ContainerInput#PICKUP");
        assertThat(fieldWritePrecedesInvocation(
                node, "dispatchQuickMove", "containerClicks",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                "handleContainerInput")).isTrue();
        assertThat(invocations(node, "acceptLoadedSnapshot"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownFurnacePort"
                        + "#inventoryReadbackMatches");
        assertThat(invocations(node, "acceptInitialSnapshot"))
                .contains(
                        "net/minecraft/world/item/ItemStack#getBurnTime",
                        "net/minecraft/world/item/crafting/RecipePropertySet#test")
                .doesNotContain("net/minecraft/world/inventory/Slot#mayPlace");
        assertThat(invocations(node, "acceptFinalSnapshot"))
                .contains("dev/aod/mcmcp/routine/MinecraftKnownFurnacePort"
                        + "#inventoryReadbackMatches");
        assertThat(invocations(node, "maintainTerminalRelease"))
                .contains(
                        "dev/aod/mcmcp/runtime/ScreenOwnershipSignals"
                                + "#releaseRoutineOnIdentityLoss",
                        "dev/aod/mcmcp/routine/MinecraftKnownFurnacePort"
                                + "#cancelScreenAuthority");
        assertThat(invocations(node, "closeOwnedMenuClient"))
                .contains("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen"
                        + "#onClose")
                .doesNotContain("net/minecraft/client/player/LocalPlayer#closeContainer");
    }

    private static PhaseFiveRequest request(
            String stationKind, int maxSmelts, String goalPolicy) {
        String block = switch (stationKind) {
            case "blast_furnace" -> "minecraft:blast_furnace";
            case "smoker" -> "minecraft:smoker";
            default -> "minecraft:furnace";
        };
        var target = new BlockTarget("minecraft:overworld", 1, 64, 2);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("recipe_ref", "recipe-ref");
        parameters.put("recipe_fingerprint", "recipe-fingerprint");
        parameters.put("goal", Map.of(
                "item", "minecraft:iron_ingot",
                "stack_policy", goalPolicy,
                "minimum_inventory_count", 4));
        parameters.put("station", Map.of(
                "kind", stationKind,
                "target", Map.of("dimension", target.dimension(),
                        "x", target.x(), "y", target.y(), "z", target.z()),
                "expected_state", Map.of(
                        "block", block,
                        "properties", Map.of("facing", "north", "lit", "false"))));
        parameters.put("fuel", Map.of(
                "item", "minecraft:coal",
                "stack_policy", "default_components_only"));
        parameters.put("max_smelts", maxSmelts);
        return new PhaseFiveRequest(
                "smelt_items", parameters,
                new PhaseFiveBounds(target.dimension(), target, target, 0, 750, false),
                maxSmelts, "smelts");
    }

    private static ClientRecipeCatalog.ResolvedRecipe recipe(
            String displayKind, String requiredScreen, String resultItem) {
        UUID session = UUID.randomUUID();
        var view = new ClientRecipeCatalog.RecipeView(
                "recipe-ref", "recipe-fingerprint", displayKind, requiredScreen,
                true, null,
                new ClientRecipeCatalog.Result(true, List.of(
                        new ClientRecipeCatalog.ResultAlternative(
                                resultItem, 1, "stack-fingerprint"))),
                List.of(new ClientRecipeCatalog.IngredientView(
                        0, 1, List.of("minecraft:raw_iron"))),
                null);
        return new ClientRecipeCatalog.ResolvedRecipe(
                new RecipeDisplayId(7), "recipe-fingerprint", view, 200, session, 1);
    }

    private static ArrayList<StackFingerprint> emptyMenu() {
        return new ArrayList<>(java.util.Collections.nCopies(
                MinecraftKnownFurnacePort.MENU_SLOT_COUNT, StackFingerprint.EMPTY));
    }

    private static MinecraftKnownFurnacePort.StackKey key(String item, int hash) {
        return new MinecraftKnownFurnacePort.StackKey(item, hash);
    }

    private static StackFingerprint stack(String item, int count, int hash) {
        return new StackFingerprint(item, count, hash);
    }

    private static List<ContainerDataEvidence> data(int... values) {
        UUID session = UUID.randomUUID();
        var result = new ArrayList<ContainerDataEvidence>();
        for (int index = 0; index < values.length; index++) {
            result.add(new ContainerDataEvidence(
                    session, 7, "minecraft:furnace", index, values[index], 10, 20 + index));
        }
        return List.copyOf(result);
    }

    private static ClassNode classNode() throws Exception {
        var node = new ClassNode();
        try (var stream = MinecraftKnownFurnacePortTest.class.getResourceAsStream(
                "/dev/aod/mcmcp/routine/MinecraftKnownFurnacePort.class")) {
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

    private static List<String> fieldReads(ClassNode node) {
        var fields = new ArrayList<String>();
        node.methods.forEach(method -> method.instructions.forEach(instruction -> {
            if (instruction instanceof FieldInsnNode field) {
                fields.add(field.owner + "#" + field.name);
            }
        }));
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
        for (var instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext(), index++) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals(fieldName)) fieldWrite = index;
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(invocationOwner)
                    && call.name.equals(invocationName)) {
                return fieldWrite >= 0 && fieldWrite < index;
            }
        }
        return false;
    }
}
