package dev.aod.mcmcp.fixture;

import dev.aod.mcmcp.agent.navigation.LocalObservationProjector;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardSeverity;
import dev.aod.mcmcp.agent.observation.ObservationRecord.HazardType;
import dev.aod.mcmcp.agent.safety.LocalObservationVolume;
import dev.aod.mcmcp.agent.safety.ObservationRecord;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Small server-side fixture test for the BlockState assumptions used by the interactive arena. */
final class FixtureGameTests {
    private static final Identifier TEST_ID = id("phase1_block_states");
    private static final Identifier PHASE2_SAFETY_TEST_ID = id("phase2_local_safety_hazards");
    private static final Identifier PHASE3_TEST_ID = id("phase3_action_fixture");
    private static final Identifier PHASE4_TEST_ID = id("phase4_block_plan_fixture");
    private static final Identifier PHASE5_TEST_ID = id("phase5_workspace_fixture");
    private static final Identifier PASSAGE_TEST_ID = id("passage_shapes_fixture");
    private static final Identifier IRON_FARM_TEST_ID = id("iron_farm_lab_fixture");
    private static final Identifier ENVIRONMENT_ID = id("fixture_environment");

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, McmcpTestFixtureMod.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE1_BLOCK_STATES =
            TEST_FUNCTIONS.register("phase1_block_states", () -> FixtureGameTests::runPhase1BlockStates);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE2_LOCAL_SAFETY =
            TEST_FUNCTIONS.register(
                    "phase2_local_safety_hazards", () -> FixtureGameTests::runPhase2LocalSafetyHazards);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE3_ACTION_FIXTURE =
            TEST_FUNCTIONS.register("phase3_action_fixture", () -> FixtureGameTests::runPhase3ActionFixture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE4_BLOCK_PLAN_FIXTURE =
            TEST_FUNCTIONS.register(
                    "phase4_block_plan_fixture", () -> FixtureGameTests::runPhase4BlockPlanFixture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE5_WORKSPACE_FIXTURE =
            TEST_FUNCTIONS.register(
                    "phase5_workspace_fixture", () -> FixtureGameTests::runPhase5WorkspaceFixture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PASSAGE_SHAPES_FIXTURE =
            TEST_FUNCTIONS.register(
                    "passage_shapes_fixture", () -> FixtureGameTests::runPassageShapesFixture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> IRON_FARM_LAB_FIXTURE =
            TEST_FUNCTIONS.register(
                    "iron_farm_lab_fixture", () -> FixtureGameTests::runIronFarmLabFixture);

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
        event.registerTest(PHASE2_SAFETY_TEST_ID,
                new FunctionGameTestInstance(PHASE2_LOCAL_SAFETY.getKey(), data));
        event.registerTest(PHASE3_TEST_ID, new FunctionGameTestInstance(PHASE3_ACTION_FIXTURE.getKey(), data));
        event.registerTest(PHASE4_TEST_ID,
                new FunctionGameTestInstance(PHASE4_BLOCK_PLAN_FIXTURE.getKey(), data));
        event.registerTest(PHASE5_TEST_ID,
                new FunctionGameTestInstance(PHASE5_WORKSPACE_FIXTURE.getKey(), data));
        event.registerTest(PASSAGE_TEST_ID,
                new FunctionGameTestInstance(PASSAGE_SHAPES_FIXTURE.getKey(), data));
        event.registerTest(IRON_FARM_TEST_ID,
                new FunctionGameTestInstance(IRON_FARM_LAB_FIXTURE.getKey(), data));
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

    private static void runPhase2LocalSafetyHazards(GameTestHelper helper) {
        BlockPos cactus = new BlockPos(0, 1, 0);
        helper.setBlock(cactus.below(), Blocks.SAND);
        helper.setBlock(cactus, Blocks.CACTUS);
        assertHazardStateAndProjection(
                helper,
                cactus,
                Blocks.CACTUS.defaultBlockState(),
                ObservationRecord.Hazard.CONTACT_DAMAGE,
                HazardType.CONTACT_DAMAGE);

        BlockPos berryBush = new BlockPos(1, 1, 0);
        BlockState berry = Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(SweetBerryBushBlock.AGE, 2);
        helper.setBlock(berryBush.below(), Blocks.DIRT);
        helper.setBlock(berryBush, berry);
        assertHazardStateAndProjection(
                helper,
                berryBush,
                berry,
                ObservationRecord.Hazard.CONTACT_DAMAGE,
                HazardType.CONTACT_DAMAGE);

        BlockPos witherRose = new BlockPos(2, 1, 0);
        helper.setBlock(witherRose.below(), Blocks.DIRT);
        helper.setBlock(witherRose, Blocks.WITHER_ROSE);
        assertHazardStateAndProjection(
                helper,
                witherRose,
                Blocks.WITHER_ROSE.defaultBlockState(),
                ObservationRecord.Hazard.CONTACT_DAMAGE,
                HazardType.CONTACT_DAMAGE);

        BlockPos powderSnow = new BlockPos(3, 1, 0);
        helper.setBlock(powderSnow.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(powderSnow, Blocks.POWDER_SNOW);
        assertHazardStateAndProjection(
                helper,
                powderSnow,
                Blocks.POWDER_SNOW.defaultBlockState(),
                ObservationRecord.Hazard.FREEZING,
                HazardType.FREEZING);

        BlockPos fire = new BlockPos(4, 1, 0);
        helper.setBlock(fire.below(), Blocks.NETHERRACK);
        helper.setBlock(fire, Blocks.FIRE);
        assertHazardStateAndProjection(
                helper,
                fire,
                Blocks.FIRE.defaultBlockState(),
                ObservationRecord.Hazard.FIRE_DAMAGE,
                HazardType.FIRE);
        helper.succeed();
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

        var buildRunnerPlan = FixturePhase4Scenario.plan(
                FixturePhase4Scenario.Mode.BUILD_RUNNER);
        var expectedBuildRunnerTargets = java.util.List.of(
                FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.get(0),
                FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.get(1),
                FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.get(0),
                FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.get(1));
        if (!buildRunnerPlan.stream().map(FixturePhase4Scenario.PlanCell::target).toList()
                        .equals(expectedBuildRunnerTargets)
                || buildRunnerPlan.stream().anyMatch(cell ->
                        !cell.operation().equals("place")
                                || !cell.before().equals(Blocks.AIR.defaultBlockState())
                                || !cell.after().equals(Blocks.COBBLESTONE.defaultBlockState())
                                || cell.item().filter(item -> item == net.minecraft.world.item.Items.COBBLESTONE)
                                        .isEmpty())
                || FixturePhase4Scenario.BUILD_RUNNER_COBBLESTONE_COUNT != 8) {
            helper.fail(Component.literal(
                    "build-runner fixture must declare four air-to-cobblestone placements and eight items"));
        }
        if (!FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.get(1).equals(
                        FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.get(0).above())
                || !FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.get(1).equals(
                        FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.get(0).above())
                || FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.get(0).getZ()
                        == FixturePhase4Scenario.ORIGIN.getZ()
                || FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.get(0).getZ()
                        == FixturePhase4Scenario.ORIGIN.getZ()
                || FixturePhase4Scenario.BUILD_RUNNER_START_POSE.getZ()
                        != FixturePhase4Scenario.ORIGIN.getZ()
                || FixturePhase4Scenario.BUILD_RUNNER_FIRST_POSE.getZ()
                        >= FixturePhase4Scenario.BUILD_RUNNER_START_POSE.getZ()
                || FixturePhase4Scenario.BUILD_RUNNER_SECOND_POSE.getZ()
                        <= FixturePhase4Scenario.BUILD_RUNNER_START_POSE.getZ()) {
            helper.fail(Component.literal(
                    "build-runner fixture must keep two vertical columns beside two work poses"));
        }
        assertInsideArena(helper, FixturePhase4Scenario.BUILD_RUNNER_START_POSE);
        assertInsideArena(helper, FixturePhase4Scenario.BUILD_RUNNER_FIRST_POSE);
        assertInsideArena(helper, FixturePhase4Scenario.BUILD_RUNNER_SECOND_POSE);
        long normalReachSquared = 20;
        if (FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.stream().anyMatch(target ->
                    squaredBlockDistance(
                            FixturePhase4Scenario.BUILD_RUNNER_FIRST_POSE, target)
                            > normalReachSquared)
                || FixturePhase4Scenario.BUILD_RUNNER_SECOND_COLUMN.stream().anyMatch(target ->
                    squaredBlockDistance(
                            FixturePhase4Scenario.BUILD_RUNNER_SECOND_POSE, target)
                            > normalReachSquared)
                || squaredBlockDistance(
                        FixturePhase4Scenario.BUILD_RUNNER_START_POSE,
                        FixturePhase4Scenario.BUILD_RUNNER_FIRST_POSE) > 4
                || squaredBlockDistance(
                        FixturePhase4Scenario.BUILD_RUNNER_FIRST_POSE,
                        FixturePhase4Scenario.BUILD_RUNNER_SECOND_POSE) > 4
                || FixturePhase4RouteBlocker.OCCUPANCY_TICKS != 20) {
            helper.fail(Component.literal(
                    "build-runner movement, reach and temporary blocker bounds are inconsistent"));
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

    private static void runPhase5WorkspaceFixture(GameTestHelper helper) {
        var layout = FixturePhase5Scenario.layout();
        layout.keySet().forEach(position -> assertInsideArena(helper, position));

        assertLayoutState(helper, layout, FixturePhase5Scenario.CRAFTING_TABLE,
                Blocks.CRAFTING_TABLE.defaultBlockState());
        assertLayoutState(helper, layout, FixturePhase5Scenario.TRANSFER_BARREL,
                FixturePhase5Scenario.barrelState());
        assertLayoutState(helper, layout, FixturePhase5Scenario.CROP_WATER,
                Blocks.WATER.defaultBlockState());

        for (int index = 0; index < FixturePhase5Scenario.CROP_SUPPORTS.size(); index++) {
            assertLayoutState(helper, layout, FixturePhase5Scenario.CROP_SUPPORTS.get(index),
                    FixturePhase5Scenario.hydratedFarmland());
            assertLayoutState(helper, layout, FixturePhase5Scenario.MATURE_CROPS.get(index),
                    FixturePhase5Scenario.matureWheat());
        }

        assertLayoutState(helper, layout, FixturePhase5Scenario.TREE_SUPPORT,
                Blocks.DIRT.defaultBlockState());
        if (!FixturePhase5Scenario.TREE_SAPLING_POSITION.equals(
                FixturePhase5Scenario.TREE_SUPPORT.above())
                || !FixturePhase5Scenario.TREE_LOGS.getFirst().equals(
                        FixturePhase5Scenario.TREE_SAPLING_POSITION)) {
            helper.fail(Component.literal(
                    "Phase 5 oak logs must begin directly above the declared sapling support"));
        }
        for (BlockPos log : FixturePhase5Scenario.TREE_LOGS) {
            assertLayoutState(helper, layout, log, FixturePhase5Scenario.oakLog());
        }
        for (BlockPos clearance : FixturePhase5Scenario.TREE_GROWTH_CLEARANCE) {
            assertLayoutState(helper, layout, clearance, Blocks.AIR.defaultBlockState());
        }
        if (FixturePhase5Scenario.TREE_ENCLOSURE.size() != 26) {
            helper.fail(Component.literal("Phase 5 tree enclosure must be one closed fence ring"));
        }
        for (BlockPos fence : FixturePhase5Scenario.TREE_ENCLOSURE) {
            assertLayoutState(helper, layout, fence, Blocks.OAK_FENCE.defaultBlockState());
        }

        assertLayoutState(helper, layout, FixturePhase5Scenario.BED_FOOT,
                FixturePhase5Scenario.bedState(BedPart.FOOT));
        assertLayoutState(helper, layout, FixturePhase5Scenario.BED_HEAD,
                FixturePhase5Scenario.bedState(BedPart.HEAD));
        if (!FixturePhase5Scenario.BED_HEAD.equals(
                FixturePhase5Scenario.BED_FOOT.relative(Direction.EAST))) {
            helper.fail(Component.literal("Phase 5 bed halves must be an east-facing adjacent pair"));
        }

        for (BlockPos waypoint : FixturePhase5Scenario.SURVEY_WAYPOINTS) {
            assertLayoutState(helper, layout, waypoint, Blocks.AIR.defaultBlockState());
        }
        for (BlockPos sample : FixturePhase5Scenario.SURVEY_SAMPLES) {
            assertLayoutState(helper, layout, sample, Blocks.SMOOTH_STONE.defaultBlockState());
            assertLayoutState(helper, layout, sample.above(), Blocks.AIR.defaultBlockState());
        }

        var declaredContents = FixturePhase5Scenario.transferContents();
        if (declaredContents.size() != 2
                || declaredContents.get(0).slot() != 0
                || declaredContents.get(0).item() != net.minecraft.world.item.Items.COBBLESTONE
                || declaredContents.get(0).count() != 12
                || declaredContents.get(1).slot() != 8
                || declaredContents.get(1).item() != net.minecraft.world.item.Items.OAK_LOG
                || declaredContents.get(1).count() != 4) {
            helper.fail(Component.literal("Phase 5 transfer contents are not deterministic"));
        }

        var combined = FixturePhase5Scenario.combinedWheatLayout();
        combined.keySet().forEach(position -> assertInsideArena(helper, position));
        assertLayoutState(helper, combined, FixturePhase5Scenario.COMBINED_SUPPLY_CHEST,
                FixturePhase5Scenario.combinedSupplyChestState());
        assertLayoutState(helper, combined, FixturePhase5Scenario.COMBINED_FARM_GATE,
                FixturePhase5Scenario.closedCombinedFarmGate());
        assertLayoutState(helper, combined, FixturePhase5Scenario.COMBINED_FARM_WATER,
                Blocks.WATER.defaultBlockState());
        if (FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.size() != 9
                || FixturePhase5Scenario.COMBINED_FARM_FENCE.size() != 16
                || !FixturePhase5Scenario.COMBINED_FARM_FENCE.contains(
                        FixturePhase5Scenario.COMBINED_FARM_WATER.above())) {
            helper.fail(Component.literal(
                    "Combined wheat fixture must have nine plots and one guarded water source"));
        }
        for (int index = 0; index < FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.size(); index++) {
            assertLayoutState(helper, combined,
                    FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.get(index),
                    Blocks.DIRT.defaultBlockState());
            assertLayoutState(helper, combined,
                    FixturePhase5Scenario.COMBINED_FARM_CROPS.get(index),
                    Blocks.AIR.defaultBlockState());
        }
        ItemStack supplyHoe = FixturePhase5Scenario.combinedSupplyHoe();
        ItemStack supplySeeds = FixturePhase5Scenario.combinedSupplySeeds();
        if (!supplyHoe.is(net.minecraft.world.item.Items.IRON_HOE)
                || supplyHoe.getDamageValue() != FixturePhase5Scenario.COMBINED_HOE_DAMAGE
                || !supplySeeds.is(net.minecraft.world.item.Items.WHEAT_SEEDS)
                || supplySeeds.getCount() != FixturePhase5Scenario.COMBINED_SEED_COUNT) {
            helper.fail(Component.literal("Combined wheat chest supplies are not deterministic"));
        }

        var generalization = FixturePhase5Scenario.generalizationLayout();
        generalization.keySet().forEach(position -> assertInsideArena(helper, position));
        for (BlockPos target : FixturePhase5Scenario.GENERALIZATION_FAN_OUT_LAMPS) {
            assertLayoutState(helper, generalization, target, Blocks.AIR.defaultBlockState());
        }
        assertLayoutState(helper, generalization, FixturePhase5Scenario.REDSTONE_LEVER_TARGET,
                Blocks.AIR.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.REDSTONE_LEVER_TARGET.below(),
                Blocks.GLASS.defaultBlockState());
        for (BlockPos target : FixturePhase5Scenario.GENERALIZATION_CONSTRUCTION_BLOCKS) {
            assertLayoutState(helper, generalization, target, Blocks.GLASS.defaultBlockState());
        }
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_CHEST_WEST,
                FixturePhase5Scenario.generalizationChestState(
                        net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_CHEST_EAST,
                FixturePhase5Scenario.generalizationChestState(
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        for (BlockPos backing : FixturePhase5Scenario.GENERALIZATION_LADDER_BACKING) {
            assertLayoutState(helper, generalization, backing,
                    Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for (BlockPos rung : FixturePhase5Scenario.GENERALIZATION_LADDER_RUNGS) {
            assertLayoutState(helper, generalization, rung,
                    FixturePhase5Scenario.generalizationLadderState());
        }
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_LADDER_LOWER_LANDING,
                Blocks.AIR.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_LADDER_UPPER_LANDING,
                Blocks.AIR.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_LADDER_UPPER_LANDING.below(),
                Blocks.SMOOTH_STONE.defaultBlockState());
        for (BlockPos cell : FixturePhase5Scenario.GENERALIZATION_SCAFFOLDING_COLUMN) {
            assertLayoutState(helper, generalization, cell,
                    FixturePhase5Scenario.generalizationScaffoldingState());
        }
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_SCAFFOLDING_LOWER_LANDING,
                Blocks.AIR.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_SCAFFOLDING_LOWER_LANDING.below(),
                Blocks.SMOOTH_STONE.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_SCAFFOLDING_UPPER_LANDING,
                Blocks.AIR.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_SCAFFOLDING_UPPER_LANDING.below(),
                Blocks.SMOOTH_STONE.defaultBlockState());

        var wireTargets = java.util.Set.of(
                FixturePhase5Scenario.GENERALIZATION_WIRE_LAMP_TARGET,
                FixturePhase5Scenario.GENERALIZATION_WIRE_TARGET,
                FixturePhase5Scenario.GENERALIZATION_WIRE_LEVER_TARGET);
        var wireSupports = java.util.Set.copyOf(
                FixturePhase5Scenario.GENERALIZATION_WIRE_SUPPORTS);
        int wireFloorY = FixturePhase5Scenario.WORKSPACE_MIN.getY();
        if (wireTargets.stream().anyMatch(target -> target.getY() != wireFloorY + 1)
                || wireSupports.stream().anyMatch(support -> support.getY() != wireFloorY)) {
            helper.fail(Component.literal(
                    "Generalization wire targets must expose floor-level UP supports"));
        }
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_WIRE_OBSERVATION_PEDESTAL,
                Blocks.SMOOTH_STONE.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_WIRE_OBSERVATION_PEDESTAL.below(),
                Blocks.SMOOTH_STONE.defaultBlockState());
        assertLayoutState(helper, generalization,
                FixturePhase5Scenario.GENERALIZATION_WIRE_OBSERVATION_PEDESTAL.above(),
                Blocks.AIR.defaultBlockState());
        for (int y = wireFloorY; y <= wireFloorY + 3; y++) {
            for (int x = 200; x <= 204; x++) {
                for (int z = 203; z <= 205; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState expected = wireSupports.contains(position)
                            ? Blocks.GLASS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                    if (!wireTargets.contains(position)) {
                        assertLayoutState(helper, generalization, position, expected);
                    }
                }
            }
        }
        var westContents = FixturePhase5Scenario.generalizationChestWestContents();
        var eastContents = FixturePhase5Scenario.generalizationChestEastContents();
        if (westContents.size() != 1 || westContents.getFirst().slot() != 0
                || westContents.getFirst().item() != net.minecraft.world.item.Items.COBBLESTONE
                || westContents.getFirst().count() != 12
                || eastContents.size() != 1 || eastContents.getFirst().slot() != 26
                || eastContents.getFirst().item() != net.minecraft.world.item.Items.OAK_LOG
                || eastContents.getFirst().count() != 4) {
            helper.fail(Component.literal(
                    "Generalization double chest contents must cover both vanilla halves"));
        }

        // Install one representative of every state family against the real GameTest level.
        BlockPos cropSupport = new BlockPos(0, 0, 0);
        BlockPos crop = cropSupport.above();
        helper.setBlock(cropSupport, FixturePhase5Scenario.hydratedFarmland());
        helper.setBlock(crop, FixturePhase5Scenario.matureWheat());
        assertExactState(helper, cropSupport, FixturePhase5Scenario.hydratedFarmland());
        assertExactState(helper, crop, FixturePhase5Scenario.matureWheat());

        BlockPos table = new BlockPos(2, 1, 0);
        helper.setBlock(table.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        assertExactState(helper, table, Blocks.CRAFTING_TABLE.defaultBlockState());

        BlockPos barrelPosition = new BlockPos(3, 1, 0);
        helper.setBlock(barrelPosition.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(barrelPosition, FixturePhase5Scenario.barrelState());
        BarrelBlockEntity barrel = helper.getBlockEntity(barrelPosition, BarrelBlockEntity.class);
        for (var entry : declaredContents) {
            barrel.setItem(entry.slot(), new ItemStack(entry.item(), entry.count()));
            ItemStack actual = barrel.getItem(entry.slot());
            if (actual.getItem() != entry.item() || actual.getCount() != entry.count()) {
                helper.fail(Component.literal("Phase 5 barrel content mismatch at slot " + entry.slot()));
            }
        }
        assertExactState(helper, barrelPosition, FixturePhase5Scenario.barrelState());

        BlockPos treeSupport = new BlockPos(4, 0, 0);
        BlockPos treeLog = treeSupport.above();
        helper.setBlock(treeSupport, Blocks.DIRT);
        helper.setBlock(treeLog, FixturePhase5Scenario.oakLog());
        assertExactState(helper, treeLog, FixturePhase5Scenario.oakLog());

        BlockPos foot = new BlockPos(5, 1, 0);
        BlockPos head = foot.relative(Direction.EAST);
        helper.setBlock(foot.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(head.below(), Blocks.SMOOTH_STONE);
        int pairFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        helper.getLevel().setBlock(helper.absolutePos(foot),
                FixturePhase5Scenario.bedState(BedPart.FOOT), pairFlags);
        helper.getLevel().setBlock(helper.absolutePos(head),
                FixturePhase5Scenario.bedState(BedPart.HEAD), pairFlags);
        assertExactState(helper, foot, FixturePhase5Scenario.bedState(BedPart.FOOT));
        assertExactState(helper, head, FixturePhase5Scenario.bedState(BedPart.HEAD));

        BlockPos chestWest = new BlockPos(0, 1, 4);
        BlockPos chestEast = chestWest.relative(Direction.EAST);
        helper.setBlock(chestWest.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(chestEast.below(), Blocks.SMOOTH_STONE);
        helper.getLevel().setBlock(
                helper.absolutePos(chestWest),
                FixturePhase5Scenario.generalizationChestState(
                        net.minecraft.world.level.block.state.properties.ChestType.RIGHT),
                pairFlags);
        helper.getLevel().setBlock(
                helper.absolutePos(chestEast),
                FixturePhase5Scenario.generalizationChestState(
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT),
                pairFlags);
        Container doubleChest = net.minecraft.world.level.block.ChestBlock.getContainer(
                (net.minecraft.world.level.block.ChestBlock) Blocks.CHEST,
                helper.getBlockState(chestWest),
                helper.getLevel(),
                helper.absolutePos(chestWest),
                false);
        if (doubleChest == null || doubleChest.getContainerSize() != 54) {
            helper.fail(Component.literal(
                    "Generalization chest pair must expose one vanilla generic_9x6 container"));
        }

        BlockPos scaffoldingBase = new BlockPos(4, 1, 4);
        helper.setBlock(scaffoldingBase.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(scaffoldingBase.relative(Direction.WEST).above(2), Blocks.SMOOTH_STONE);
        for (int offset = 0; offset < 4; offset++) {
            BlockPos cell = scaffoldingBase.above(offset);
            helper.setBlock(cell, FixturePhase5Scenario.generalizationScaffoldingState());
            BlockState actual = helper.getBlockState(cell);
            if (!actual.equals(FixturePhase5Scenario.generalizationScaffoldingState())
                    || !actual.canSurvive(helper.getLevel(), helper.absolutePos(cell))) {
                helper.fail(Component.literal(
                        "Generalization scaffolding column must stay exact and supported"));
            }
        }

        // Exercise the same ordering as prepare(COMBINED_WHEAT): initial purge, layout-created
        // drop, then final purge. The item outside the workspace must survive both passes.
        var preExistingHoe = helper.spawnItem(net.minecraft.world.item.Items.IRON_HOE,
                0.5F, 1.25F, 2.5F);
        var outsideItem = helper.spawnItem(net.minecraft.world.item.Items.WHEAT,
                3.5F, 1.25F, 2.5F);
        AABB cleanupBounds = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 1, 2)),
                helper.absolutePos(new BlockPos(1, 1, 2)));
        int preDiscarded = FixturePhase5Scenario.discardItemEntities(
                helper.getLevel(), cleanupBounds);
        if (preDiscarded != 1 || !preExistingHoe.isRemoved() || outsideItem.isRemoved()) {
            helper.fail(Component.literal(
                    "Combined wheat initial cleanup must remove only pre-existing workspace items"));
        }

        var layoutGeneratedSeeds = helper.spawnItem(net.minecraft.world.item.Items.WHEAT_SEEDS,
                1.5F, 1.25F, 2.5F);
        int finalDiscarded = FixturePhase5Scenario.discardItemEntities(
                helper.getLevel(), cleanupBounds);
        if (finalDiscarded != 1 || !layoutGeneratedSeeds.isRemoved() || outsideItem.isRemoved()
                || !helper.getLevel().getEntities(
                        EntityTypes.ITEM, cleanupBounds, entity -> entity.isAlive()).isEmpty()) {
            helper.fail(Component.literal(
                    "Combined wheat final cleanup must remove layout-generated workspace items"));
        }
        outsideItem.discard();
        helper.succeed();
    }

    private static void runPassageShapesFixture(GameTestHelper helper) {
        BlockPos shapePosition = new BlockPos(0, 1, 0);
        AABB horizontalPassage = new AABB(0.25D, 1.0D, -0.25D, 0.75D, 2.8D, 1.25D);
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
        BlockState openDoor = closedDoor.setValue(BlockStateProperties.OPEN, true);
        assertShapeTransition(helper, "wooden door", shapePosition,
                horizontalPassage, closedDoor, openDoor, true);

        AABB verticalHatchPassage = new AABB(0.25D, -0.5D, 0.25D, 0.75D, 1.5D, 0.75D);
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BlockState openTrapdoor = closedTrapdoor.setValue(BlockStateProperties.OPEN, true);
        assertShapeTransition(helper, "wooden trapdoor", BlockPos.ZERO,
                verticalHatchPassage, closedTrapdoor, openTrapdoor, true);

        BlockState poweredPlate = Blocks.OAK_PRESSURE_PLATE.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, true);
        if (!poweredPlate.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                || poweredPlate.getSignal(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.NORTH) <= 0) {
            helper.fail(Component.literal(
                    "Powered wooden pressure plate must be passable and emit a redstone signal"));
        }

        BlockPos doorLower = new BlockPos(4, 1, 1);
        BlockPos doorUpper = doorLower.above();
        BlockPos plate = doorLower.relative(Direction.SOUTH);
        helper.setBlock(doorLower.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(plate.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(doorLower, closedDoor);
        helper.setBlock(doorUpper, closedDoor
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(plate, poweredPlate);
        helper.getLevel().updateNeighborsAt(helper.absolutePos(plate), Blocks.OAK_PRESSURE_PLATE);
        helper.runAfterDelay(1L, () -> {
            if (!helper.getBlockState(doorLower).getValue(BlockStateProperties.OPEN)
                    || !helper.getBlockState(doorUpper).getValue(BlockStateProperties.OPEN)) {
                helper.fail(Component.literal(
                        "Wooden pressure plate did not open both halves of the wooden door"));
            }
            helper.setBlock(plate, Blocks.OAK_PRESSURE_PLATE.defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, false));
            helper.getLevel().updateNeighborsAt(helper.absolutePos(plate), Blocks.OAK_PRESSURE_PLATE);
        });
        helper.runAfterDelay(2L, () -> {
            if (helper.getBlockState(doorLower).getValue(BlockStateProperties.OPEN)
                    || helper.getBlockState(doorUpper).getValue(BlockStateProperties.OPEN)) {
                helper.fail(Component.literal(
                        "Wooden door did not close after its pressure plate was released"));
            }
            helper.succeed();
        });
    }

    private static void assertShapeTransition(
            GameTestHelper helper,
            String label,
            BlockPos position,
            AABB passage,
            BlockState closed,
            BlockState open,
            boolean closedMustBlock) {
        boolean closedIntersects = closed.getCollisionShape(EmptyBlockGetter.INSTANCE, position)
                .toAabbs().stream()
                .map(box -> box.move(position))
                .anyMatch(box -> box.intersects(passage));
        boolean openIntersects = open.getCollisionShape(EmptyBlockGetter.INSTANCE, position)
                .toAabbs().stream()
                .map(box -> box.move(position))
                .anyMatch(box -> box.intersects(passage));
        if (closedIntersects != closedMustBlock || openIntersects) {
            helper.fail(Component.literal(label
                    + " collision shape did not change from blocked to passable"));
        }
    }

    private static void runIronFarmLabFixture(GameTestHelper helper) {
        int width = FixtureIronFarmScenario.LAB_MAX.getX()
                - FixtureIronFarmScenario.LAB_MIN.getX() + 1;
        int depth = FixtureIronFarmScenario.LAB_MAX.getZ()
                - FixtureIronFarmScenario.LAB_MIN.getZ() + 1;
        if (width < 64 || depth < 64
                || FixtureIronFarmScenario.LAB_MIN.getX() <= FixtureArena.MAX.getX()
                || FixtureIronFarmScenario.unfinishedPlatformCellCount() != 60) {
            helper.fail(Component.literal("iron-farm lab is not a separate, broad unfinished workspace"));
        }
        var constructionTargets = FixtureIronFarmScenario.constructionTargets();
        var constructionLanes = FixtureIronFarmScenario.constructionLanes();
        for (BlockPos target : constructionTargets) {
            boolean inNormalReach = constructionLanes.stream().anyMatch(lane -> {
                double horizontal = Math.hypot(
                        lane.getX() - target.getX(), lane.getZ() - target.getZ());
                return horizontal <= 3.0D;
            });
            if (!inNormalReach || FixtureIronFarmScenario.CENTER_CHUTE.contains(target)) {
                helper.fail(Component.literal("iron-farm floor target escaped normal cross-lane reach"));
            }
        }
        for (BlockPos villager : FixtureIronFarmScenario.VILLAGER_POSITIONS) {
            for (BlockPos chute : FixtureIronFarmScenario.CENTER_CHUTE) {
                if (Math.abs(villager.getX() - chute.getX()) > 8
                        || Math.abs(villager.getZ() - chute.getZ()) > 8) {
                    helper.fail(Component.literal("iron-farm chute escaped a villager spawn volume"));
                }
            }
            for (BlockPos peer : FixtureIronFarmScenario.VILLAGER_POSITIONS) {
                if (Math.abs(villager.getX() - peer.getX()) > 10
                        || Math.abs(villager.getZ() - peer.getZ()) > 10) {
                    helper.fail(Component.literal("iron-farm villagers escaped their mutual range"));
                }
            }
        }
        if (!FixtureIronFarmScenario.VILLAGE_ROOF_STATE.equals(Blocks.GLASS.defaultBlockState())
                || FixtureIronFarmScenario.waterSources().size() != 8
                || FixtureIronFarmScenario.waterPlugs().size() != 8) {
            helper.fail(Component.literal("iron-farm spawnproof roof or sealed reservoirs changed"));
        }
        for (BlockPos source : FixtureIronFarmScenario.waterSources()) {
            boolean adjacentPlug = FixtureIronFarmScenario.waterPlugs().stream()
                    .anyMatch(plug -> source.distManhattan(plug) == 1);
            if (!adjacentPlug) {
                helper.fail(Component.literal("iron-farm water source has no safe-break plug"));
            }
        }
        for (BlockPos plug : FixtureIronFarmScenario.waterPlugs()) {
            boolean alignedWithChute = plug.getX() == 255 || plug.getX() == 256
                    || plug.getZ() == 255 || plug.getZ() == 256;
            boolean onPlatformEdge = plug.getX() == FixtureIronFarmScenario.PLATFORM_MIN.getX()
                    || plug.getX() == FixtureIronFarmScenario.PLATFORM_MAX.getX()
                    || plug.getZ() == FixtureIronFarmScenario.PLATFORM_MIN.getZ()
                    || plug.getZ() == FixtureIronFarmScenario.PLATFORM_MAX.getZ();
            if (!alignedWithChute || !onPlatformEdge) {
                helper.fail(Component.literal("iron-farm water stream is not aimed at the central chute"));
            }
        }
        if (FixtureIronFarmScenario.scareSightline().size() != 16
                || FixtureIronFarmScenario.SCARE_BLOCKERS.stream().anyMatch(blocker ->
                        !FixtureIronFarmScenario.scareSightline().contains(blocker)
                                || !FixtureIronFarmScenario.scareSightline().contains(blocker.above()))) {
            helper.fail(Component.literal("iron-farm activation does not open the full scare sightline"));
        }
        var collection = FixtureIronFarmScenario.collectionLayout();
        for (var entry : collection.entrySet()) {
            if (!entry.getValue().is(Blocks.HOPPER)) {
                continue;
            }
            BlockPos cursor = entry.getKey();
            for (int step = 0; step < 4 && !cursor.equals(FixtureIronFarmScenario.COLLECTION_CONTAINER); step++) {
                BlockState state = collection.get(cursor);
                if (state == null || !state.is(Blocks.HOPPER)) {
                    break;
                }
                cursor = cursor.relative(state.getValue(BlockStateProperties.FACING_HOPPER));
            }
            if (!cursor.equals(FixtureIronFarmScenario.COLLECTION_CONTAINER)) {
                helper.fail(Component.literal("iron-farm hopper path does not reach the collection barrel"));
            }
        }
        var completePlatform = new java.util.HashSet<BlockPos>();
        completePlatform.addAll(constructionTargets);
        completePlatform.addAll(constructionLanes);
        completePlatform.addAll(FixtureIronFarmScenario.CENTER_CHUTE);
        if (completePlatform.size() != 100
                || FixtureIronFarmScenario.LAVA_SOURCE.getY() <= 200
                || FixtureIronFarmScenario.LAVA_SOURCE.getY()
                        >= FixtureIronFarmScenario.PLATFORM_MIN.getY()
                || FixtureIronFarmScenario.LAVA_SOURCE.distManhattan(
                        new BlockPos(255, FixtureIronFarmScenario.LAVA_SOURCE.getY(), 255)) != 1) {
            helper.fail(Component.literal(
                    "iron-farm spawn surface, central chute, or lava blade geometry changed"));
        }
        if (FixtureIronFarmScenario.scareDoorState(DoubleBlockHalf.LOWER)
                    .getValue(BlockStateProperties.OPEN)) {
            helper.fail(Component.literal("iron-farm scare doors must start with sight blocked"));
        }
        for (BlockPos position : FixtureIronFarmScenario.VILLAGER_BEDS) {
            if (!FixtureIronFarmScenario.contains(position)) {
                helper.fail(Component.literal("iron-farm bed escaped the dedicated lab boundary"));
            }
        }
        if (FixtureIronFarmScenario.materialContents().size() != 4
                || FixtureIronFarmScenario.materialContents().values().stream().anyMatch(java.util.List::isEmpty)) {
            helper.fail(Component.literal("iron-farm material depot categories are incomplete"));
        }

        BlockPos bedFoot = new BlockPos(0, 1, 0);
        BlockPos bedHead = bedFoot.relative(Direction.EAST);
        helper.setBlock(bedFoot.below(), Blocks.SMOOTH_STONE);
        helper.setBlock(bedHead.below(), Blocks.SMOOTH_STONE);
        int pairFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        helper.getLevel().setBlock(helper.absolutePos(bedFoot),
                FixtureIronFarmScenario.bedState(BedPart.FOOT), pairFlags);
        helper.getLevel().setBlock(helper.absolutePos(bedHead),
                FixtureIronFarmScenario.bedState(BedPart.HEAD), pairFlags);
        assertExactState(helper, bedFoot, FixtureIronFarmScenario.bedState(BedPart.FOOT));
        assertExactState(helper, bedHead, FixtureIronFarmScenario.bedState(BedPart.HEAD));

        BlockPos water = new BlockPos(3, 1, 0);
        BlockPos lava = new BlockPos(7, 1, 7);
        helper.setBlock(water.below(), Blocks.GLASS);
        helper.setBlock(lava.below(), Blocks.GLASS);
        helper.setBlock(water, Blocks.WATER);
        helper.setBlock(lava, Blocks.LAVA);
        if (!helper.getLevel().getFluidState(helper.absolutePos(water)).isSource()) {
            helper.fail(Component.literal("iron-farm prerequisite water must be a source block"));
        }
        assertExactState(helper, lava, Blocks.LAVA.defaultBlockState());

        for (int x = 0; x < 3; x++) {
            var villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(x, 1, 3));
            long currentGameTime = helper.getLevel().getGameTime();
            FixtureIronFarmScenario.configureVillager(villager, currentGameTime);
            if (villager.isBaby()
                    || !villager.getBrain().getMemory(
                            net.minecraft.world.entity.ai.memory.MemoryModuleType.LAST_SLEPT)
                            .filter(value -> value == currentGameTime).isPresent()) {
                helper.fail(Component.literal(
                        "iron-farm fixture villager must be adult with deterministic LAST_SLEPT"));
            }
        }
        var boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(4, 1, 3));
        var zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(4, 1, 3));
        FixtureIronFarmScenario.configureZombie(zombie);
        if (zombie.isBaby() || !zombie.startRiding(boat) || zombie.getVehicle() != boat) {
            helper.fail(Component.literal("iron-farm fixture zombie must be an adult boat passenger"));
        }
        helper.succeed();
    }

    private static void assertInsideArena(GameTestHelper helper, BlockPos position) {
        if (position.getX() < FixtureArena.MIN.getX() || position.getX() > FixtureArena.MAX.getX()
                || position.getY() < FixtureArena.MIN.getY() || position.getY() > FixtureArena.MAX.getY()
                || position.getZ() < FixtureArena.MIN.getZ() || position.getZ() > FixtureArena.MAX.getZ()) {
            helper.fail(Component.literal("fixture cell escaped the bounded arena: " + position));
        }
    }

    private static long squaredBlockDistance(BlockPos first, BlockPos second) {
        long x = (long) first.getX() - second.getX();
        long y = (long) first.getY() - second.getY();
        long z = (long) first.getZ() - second.getZ();
        return x * x + y * y + z * z;
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

    private static void assertHazardStateAndProjection(
            GameTestHelper helper,
            BlockPos relativePos,
            BlockState expectedState,
            ObservationRecord.Hazard localHazard,
            HazardType publicHazard) {
        assertExactState(helper, relativePos, expectedState);
        var point = new ObservationRecord.Point(
                relativePos.getX() + 0.5D,
                relativePos.getY() + 0.5D,
                relativePos.getZ() + 0.5D);
        var current = new ObservationRecord(
                1L,
                7L,
                0,
                point,
                point,
                point,
                ObservationRecord.Support.PRESENT,
                ObservationRecord.Clearance.CLEAR,
                ObservationRecord.Transition.STATIONARY,
                ObservationRecord.Fluid.NONE,
                false,
                localHazard,
                ObservationRecord.LoadedState.LOADED,
                ObservationRecord.Drop.SUPPORTED,
                false);
        var projection = LocalObservationProjector.project(
                new LocalObservationVolume.Snapshot(1L, 7L, point, current, List.of()),
                new UUID(0L, 1L),
                "minecraft:overworld",
                7L,
                point.y() - 0.5D);
        if (projection.currentSafety() != LocalObservationProjector.CurrentSafety.RECOVER) {
            helper.fail(Component.literal(
                    "expected RECOVER for " + expectedState + " but found "
                            + projection.currentSafety()));
        }
        if (projection.records().size() != 1
                || !(projection.records().getFirst()
                        instanceof dev.aod.mcmcp.agent.observation.ObservationRecord.Hazard hazard)
                || hazard.hazardType() != publicHazard
                || hazard.severity() != HazardSeverity.URGENT) {
            helper.fail(Component.literal(
                    "unexpected projected hazard for " + expectedState + ": "
                            + projection.records()));
        }
        if (LocalObservationVolume.avoidsNewDamageHazard(
                ObservationRecord.Hazard.NONE, localHazard, ObservationRecord.Hazard.NONE)) {
            helper.fail(Component.literal(
                    "new damage hazard was accepted for " + expectedState));
        }
        if (!LocalObservationVolume.avoidsNewDamageHazard(
                localHazard, localHazard, ObservationRecord.Hazard.NONE)) {
            helper.fail(Component.literal(
                    "same-hazard escape progress was rejected for " + expectedState));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(McmcpTestFixtureMod.MOD_ID, path);
    }
}
