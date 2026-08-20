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
    private static final Identifier ENVIRONMENT_ID = id("fixture_environment");

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, CraftAgentTestFixtureMod.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PHASE1_BLOCK_STATES =
            TEST_FUNCTIONS.register("phase1_block_states", () -> FixtureGameTests::runPhase1BlockStates);

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
        GameTestInstance test = new FunctionGameTestInstance(PHASE1_BLOCK_STATES.getKey(), data);
        event.registerTest(TEST_ID, test);
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
