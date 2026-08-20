package dev.aodaruma.craftagent.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/** Small server-side fixture test for the BlockState assumptions used by the interactive arena. */
final class FixtureGameTests {
    private static final Identifier TEST_ID = id("phase1_block_states");
    private static final Identifier PHASE3_TEST_ID = id("phase3_action_fixture");
    private static final Identifier PHASE4_TEST_ID = id("phase4_block_plan_fixture");
    private static final Identifier ENVIRONMENT_ID = id("fixture_environment");

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, CraftAgentTestFixtureMod.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE1_BLOCK_STATES =
            TEST_FUNCTIONS.register("phase1_block_states", () -> FixtureGameTests::runPhase1BlockStates);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE3_ACTION_FIXTURE =
            TEST_FUNCTIONS.register("phase3_action_fixture", () -> FixtureGameTests::runPhase3ActionFixture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE4_BLOCK_PLAN_FIXTURE =
            TEST_FUNCTIONS.register(
                    "phase4_block_plan_fixture", () -> FixtureGameTests::runPhase4BlockPlanFixture);

    private FixtureGameTests() {
    }

    static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(FixtureGameTests::register);
    }

    private static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                ENVIRONMENT_ID, new TestEnvironmentDefinition.AllOf());
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.withDefaultNamespace("empty"),
                100,
                0,
                true);
        event.registerTest(TEST_ID, new FunctionGameTestInstance(PHASE1_BLOCK_STATES.getKey(), data));
        event.registerTest(PHASE3_TEST_ID, new FunctionGameTestInstance(PHASE3_ACTION_FIXTURE.getKey(), data));
        event.registerTest(PHASE4_TEST_ID,
                new FunctionGameTestInstance(PHASE4_BLOCK_PLAN_FIXTURE.getKey(), data));
    }

    private static void runPhase1BlockStates(GameTestHelper helper) {
        BlockPos support = new BlockPos(0, 0, 0);
        BlockPos sample = support.above();

        helper.setBlock(support, Blocks.FARMLAND.defaultBlockState()
                .setValue(BlockStateProperties.MOISTURE, 7));
        helper.setBlock(sample, Blocks.WHEAT.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 7));
        helper.assertBlockProperty(sample, BlockStateProperties.AGE_7, 7);

        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        helper.setBlock(sample, stairs);
        helper.assertBlockProperty(sample, BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        helper.assertBlockProperty(sample, BlockStateProperties.HALF, Half.TOP);
        helper.assertBlockProperty(sample, BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);

        BlockState door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.POWERED, false);
        helper.setBlock(sample, door);
        helper.assertBlockProperty(sample, BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        helper.assertBlockProperty(sample, BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
        helper.assertBlockProperty(sample, BlockStateProperties.OPEN, true);

        helper.setBlock(support, Blocks.REDSTONE_BLOCK);
        helper.setBlock(sample, Blocks.REDSTONE_LAMP.defaultBlockState()
                .setValue(BlockStateProperties.LIT, true));
        helper.assertBlockProperty(sample, BlockStateProperties.LIT, true);

        BlockPos waterSample = new BlockPos(1, 1, 1);
        BlockState water = Blocks.WATER.defaultBlockState();
        helper.setBlock(waterSample.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(waterSample, water);
        var waterFluid = helper.getLevel().getFluidState(helper.absolutePos(waterSample));
        if (!waterFluid.isSource() || waterFluid.getAmount() != 8) {
            helper.fail(Component.literal("expected a full source fluid at " + waterSample
                    + " but found source=" + waterFluid.isSource()
                    + " amount=" + waterFluid.getAmount()));
        }
        assertExactState(helper, waterSample, water);

        BlockPos slabSample = new BlockPos(2, 1, 1);
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        helper.setBlock(slabSample.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(slabSample, slab);
        helper.assertBlockProperty(slabSample, BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        helper.assertBlockProperty(slabSample, BlockStateProperties.WATERLOGGED, false);
        assertExactState(helper, slabSample, slab);

        BlockPos absoluteLamp = helper.absolutePos(sample);
        BlockPos absoluteLightSample = helper.absolutePos(sample.above());
        var lightEngine = helper.getLevel().getChunkSource().getLightEngine();
        lightEngine.checkBlock(absoluteLamp);
        lightEngine.checkBlock(absoluteLightSample);
        while (lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
        }
        helper.succeedWhen(() -> {
            int blockLight = helper.getLevel().getBrightness(LightLayer.BLOCK, absoluteLightSample);
            if (blockLight <= 0) {
                helper.fail(Component.literal("expected positive block light above the powered lamp"));
            }
        });
    }

    private static void runPhase3ActionFixture(GameTestHelper helper) {
        var navigation = FixturePhase3Scenario.layout(FixturePhase3Scenario.Mode.NAVIGATE);
        for (int x = 0; x < 9; x++) {
            for (int z = -1; z <= 1; z++) {
                assertLayoutState(helper, navigation, new BlockPos(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) {
                    assertLayoutState(helper, navigation, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        var breakLayout = FixturePhase3Scenario.layout(FixturePhase3Scenario.Mode.BREAK);
        assertLayoutState(
                helper,
                breakLayout,
                FixturePhase3Scenario.BREAK_TARGET.subtract(FixturePhase3Scenario.ORIGIN),
                Blocks.STONE.defaultBlockState());

        var placeLayout = FixturePhase3Scenario.layout(FixturePhase3Scenario.Mode.PLACE);
        assertLayoutState(
                helper,
                placeLayout,
                FixturePhase3Scenario.PLACEMENT_SUPPORT.subtract(FixturePhase3Scenario.ORIGIN),
                Blocks.SMOOTH_STONE.defaultBlockState());
        assertLayoutState(
                helper,
                placeLayout,
                FixturePhase3Scenario.PLACEMENT_DESTINATION.subtract(FixturePhase3Scenario.ORIGIN),
                Blocks.AIR.defaultBlockState());

        var leverLayout = FixturePhase3Scenario.layout(FixturePhase3Scenario.Mode.LEVER);
        BlockState lever = FixturePhase3Scenario.leverOffState();
        assertLayoutState(
                helper,
                leverLayout,
                FixturePhase3Scenario.LEVER.subtract(FixturePhase3Scenario.ORIGIN),
                lever);
        assertLeverAimGeometry(helper);

        BlockPos corridorFloor = new BlockPos(0, 0, 0);
        BlockPos corridorAir = corridorFloor.above();
        helper.setBlock(corridorFloor, navigation.get(new BlockPos(0, -1, 0)));
        helper.setBlock(corridorAir, navigation.get(new BlockPos(0, 0, 0)));
        helper.assertBlockState(corridorFloor, Blocks.SMOOTH_STONE.defaultBlockState());
        helper.assertBlockState(corridorAir, Blocks.AIR.defaultBlockState());

        BlockPos leverSample = new BlockPos(1, 1, 1);
        helper.setBlock(leverSample.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(leverSample, lever);
        helper.assertBlockProperty(leverSample, BlockStateProperties.ATTACH_FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        helper.assertBlockProperty(leverSample, BlockStateProperties.POWERED, false);
        assertExactState(helper, leverSample, lever);

        BlockPos cowSample = new BlockPos(3, 1, 1);
        var cow = helper.spawn(EntityTypes.COW, cowSample);
        FixturePhase3Scenario.configureCow(cow);
        assertCowAimGeometry(helper, cow);
        helper.assertEntityProperty(
                cow, entity -> entity.isNoAi() && entity.isPersistenceRequired(), true,
                Component.literal("expected the Phase 3 cow to be NoAI and persistent"));
        helper.succeed();
    }

    private static void runPhase4BlockPlanFixture(GameTestHelper helper) {
        for (var mode : FixturePhase4Scenario.Mode.values()) {
            var layout = FixturePhase4Scenario.layout(mode);
            var ids = new java.util.HashSet<String>();
            var targets = new java.util.HashSet<BlockPos>();
            for (var cell : FixturePhase4Scenario.plan(mode)) {
                if (!ids.add(cell.id()) || !targets.add(cell.target())) {
                    helper.fail(Component.literal(
                            "Phase 4 plan ids and targets must be unique for " + mode.name()));
                }
                BlockPos relative = cell.target().subtract(FixturePhase4Scenario.ORIGIN);
                assertLayoutState(helper, layout, relative, cell.before());
                assertInsideArena(helper, cell.target());
                if (cell.operation().equals("verify_only")
                        && !cell.before().equals(cell.after())) {
                    helper.fail(Component.literal("verify_only must be an exact no-op for " + cell.id()));
                }
            }
        }

        var mutationPlan = FixturePhase4Scenario.plan(FixturePhase4Scenario.Mode.MUTATIONS);
        if (!mutationPlan.stream().map(FixturePhase4Scenario.PlanCell::operation).toList()
                .equals(java.util.List.of("break_to_air", "place", "replace"))) {
            helper.fail(Component.literal("mutation fixture must cover break/place/replace in order"));
        }

        long shortageCobblestone = FixturePhase4Scenario.plan(FixturePhase4Scenario.Mode.SHORTAGE)
                .stream()
                .filter(cell -> cell.item().filter(item -> item == net.minecraft.world.item.Items.COBBLESTONE)
                        .isPresent())
                .count();
        if (shortageCobblestone != 2) {
            helper.fail(Component.literal("shortage fixture must require two cobblestone items"));
        }

        var hiddenLayout = FixturePhase4Scenario.layout(FixturePhase4Scenario.Mode.HIDDEN);
        assertLayoutState(
                helper,
                hiddenLayout,
                FixturePhase4Scenario.HIDDEN_TARGET.subtract(FixturePhase4Scenario.ORIGIN),
                Blocks.GOLD_BLOCK.defaultBlockState());
        assertLayoutState(
                helper,
                hiddenLayout,
                FixturePhase4Scenario.HIDDEN_APERTURE.subtract(FixturePhase4Scenario.ORIGIN),
                Blocks.STONE.defaultBlockState());

        // Exercise exact equality against the server's canonical state definitions. Reusing one
        // sample cell keeps this independent of the tiny built-in empty GameTest template.
        BlockPos sample = new BlockPos(1, 1, 1);
        helper.setBlock(sample.below(), Blocks.SMOOTH_STONE);

        BlockState water = Blocks.WATER.defaultBlockState();
        helper.setBlock(sample, water);
        assertExactState(helper, sample, water);
        var fluid = helper.getLevel().getFluidState(helper.absolutePos(sample));
        if (!fluid.isSource() || fluid.getAmount() != 8) {
            helper.fail(Component.literal("Phase 4 water target must be a level-0 source"));
        }

        BlockState waterlogged = FixturePhase4Scenario.waterloggedSlab();
        helper.setBlock(sample, waterlogged);
        helper.assertBlockProperty(sample, BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        helper.assertBlockProperty(sample, BlockStateProperties.WATERLOGGED, true);
        assertExactState(helper, sample, waterlogged);

        BlockState stairs = FixturePhase4Scenario.eastBottomStairs();
        helper.setBlock(sample, stairs);
        helper.assertBlockProperty(sample, BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        helper.assertBlockProperty(sample, BlockStateProperties.HALF, Half.BOTTOM);
        helper.assertBlockProperty(sample, BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT);
        helper.assertBlockProperty(sample, BlockStateProperties.WATERLOGGED, false);
        assertExactState(helper, sample, stairs);

        BlockState hopper = FixturePhase4Scenario.downHopper();
        helper.setBlock(sample, hopper);
        helper.assertBlockProperty(sample, BlockStateProperties.FACING_HOPPER, Direction.DOWN);
        helper.assertBlockProperty(sample, BlockStateProperties.ENABLED, true);
        assertExactState(helper, sample, hopper);
        helper.succeed();
    }

    private static void assertInsideArena(GameTestHelper helper, BlockPos position) {
        if (position.getX() < FixtureArena.MIN.getX() || position.getX() > FixtureArena.MAX.getX()
                || position.getY() < FixtureArena.MIN.getY() || position.getY() > FixtureArena.MAX.getY()
                || position.getZ() < FixtureArena.MIN.getZ() || position.getZ() > FixtureArena.MAX.getZ()) {
            helper.fail(Component.literal("Phase 4 fixture cell escaped the bounded arena: " + position));
        }
    }

    private static void assertLayoutState(
            GameTestHelper helper,
            java.util.Map<BlockPos, BlockState> layout,
            BlockPos relativePos,
            BlockState expected) {
        BlockState actual = layout.get(relativePos);
        if (!expected.equals(actual)) {
            helper.fail(Component.literal("expected fixture layout state " + expected + " at " + relativePos
                    + " but found " + actual));
        }
    }

    private static void assertLeverAimGeometry(GameTestHelper helper) {
        double eyeY = FixturePhase3Scenario.ORIGIN.getY() + 1.62D;
        double horizontalDistanceToLeverCenter = FixturePhase3Scenario.LEVER.getX() + 0.5D
                - (FixturePhase3Scenario.ORIGIN.getX() + 1.5D);
        double downwardSlope = Math.tan(Math.toRadians(
                FixturePhase3Scenario.LEVER_PITCH_DEGREES));
        double sightlineYAtLeverCenter = eyeY - downwardSlope * horizontalDistanceToLeverCenter;
        double horizontalDistanceToSupportTop = (eyeY - FixturePhase3Scenario.LEVER.getY())
                / downwardSlope;
        boolean crossesLeverHeight = sightlineYAtLeverCenter
                >= FixturePhase3Scenario.LEVER.getY() + 0.25D
                && sightlineYAtLeverCenter <= FixturePhase3Scenario.LEVER.getY() + 0.45D;
        if (!crossesLeverHeight
                || horizontalDistanceToSupportTop <= horizontalDistanceToLeverCenter) {
            helper.fail(Component.literal("lever pose ray misses the lever before the support floor"
                    + ": pitch=" + FixturePhase3Scenario.LEVER_PITCH_DEGREES
                    + " sightline_y=" + sightlineYAtLeverCenter
                    + " support_distance=" + horizontalDistanceToSupportTop));
        }
    }

    private static void assertCowAimGeometry(
            GameTestHelper helper, net.minecraft.world.entity.animal.cow.Cow cow) {
        double eyeY = FixturePhase3Scenario.ORIGIN.getY() + 1.62D;
        double playerX = FixturePhase3Scenario.ORIGIN.getX() + 2.5D;
        double cowCenterX = FixturePhase3Scenario.COW.getX() + 0.5D;
        double horizontalDistanceToCowFront = cowCenterX - cow.getBbWidth() / 2.0D - playerX;
        double sightlineYAtCowFront = eyeY - Math.tan(Math.toRadians(
                FixturePhase3Scenario.COW_PITCH_DEGREES)) * horizontalDistanceToCowFront;
        double cowMinY = FixturePhase3Scenario.COW.getY();
        double cowMaxY = cowMinY + cow.getBbHeight();
        if (sightlineYAtCowFront < cowMinY || sightlineYAtCowFront > cowMaxY) {
            helper.fail(Component.literal("cow pose ray misses the adult cow bounding box"
                    + ": pitch=" + FixturePhase3Scenario.COW_PITCH_DEGREES
                    + " sightline_y=" + sightlineYAtCowFront
                    + " cow_y=" + cowMinY + ".." + cowMaxY));
        }
    }

    /** Equality covers the block id and the complete property set, including future additions. */
    private static void assertExactState(GameTestHelper helper, BlockPos relativePos, BlockState expected) {
        BlockState actual = helper.getLevel().getBlockState(helper.absolutePos(relativePos));
        if (!actual.equals(expected)) {
            helper.fail(Component.literal("expected exact BlockState " + expected + " at " + relativePos
                    + " but found " + actual));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(CraftAgentTestFixtureMod.MOD_ID, path);
    }
}
