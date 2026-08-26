package dev.aodaruma.craftagent.fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.clock.ClockTimeMarkers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Development-only, hard-bounded lab with unsupported iron-farm prerequisites installed. */
final class FixtureIronFarmScenario {
    static final BlockPos LAB_MIN = new BlockPos(224, 198, 224);
    static final BlockPos LAB_MAX = new BlockPos(288, 224, 288);
    static final int FLOOR_Y = 199;

    static final BlockPos PLATFORM_MIN = new BlockPos(251, 205, 251);
    static final BlockPos PLATFORM_MAX = new BlockPos(260, 205, 260);
    static final Set<BlockPos> CENTER_CHUTE = Set.of(
            new BlockPos(255, 205, 255), new BlockPos(256, 205, 255),
            new BlockPos(255, 205, 256), new BlockPos(256, 205, 256));

    static final List<BlockPos> VILLAGER_BEDS = List.of(
            new BlockPos(252, 200, 246),
            new BlockPos(255, 200, 246),
            new BlockPos(258, 200, 246));
    static final List<BlockPos> VILLAGER_POSITIONS = List.of(
            new BlockPos(252, 200, 248),
            new BlockPos(255, 200, 248),
            new BlockPos(258, 200, 248));
    static final BlockPos ZOMBIE_BOAT = new BlockPos(255, 200, 251);
    static final List<BlockPos> SCARE_BLOCKERS = List.of(
            new BlockPos(253, 201, 249),
            new BlockPos(255, 201, 249),
            new BlockPos(257, 201, 249));
    static final BlockState VILLAGE_ROOF_STATE = Blocks.GLASS.defaultBlockState();

    static final BlockPos SAFE_BED_FOOT = new BlockPos(230, 200, 230);
    static final BlockPos SAFE_BED_HEAD = SAFE_BED_FOOT.relative(Direction.EAST);
    static final BlockPos SAFE_DOOR_LOWER = new BlockPos(232, 200, 236);
    static final BlockPos SAFE_DOOR_UPPER = SAFE_DOOR_LOWER.above();

    static final BlockPos LAVA_SOURCE = new BlockPos(254, 202, 255);
    static final BlockPos COLLECTION_CONTAINER = new BlockPos(258, 200, 255);
    static final List<BlockPos> MATERIAL_BARRELS = List.of(
            new BlockPos(239, 200, 229),
            new BlockPos(239, 200, 231),
            new BlockPos(239, 200, 233),
            new BlockPos(239, 200, 235));

    private static final int MUTATION_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final int PAIRED_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final String ENTITY_TAG = "craftagent_fixture_iron_farm";
    private static final Map<BlockPos, List<ContainerEntry>> MATERIALS = materials();

    private FixtureIronFarmScenario() {
    }

    static void prepare(FixtureSecurity.Context context, Consumer<Component> output) {
        FixturePhase2Scenario.stop();
        FixturePhase3Scenario.stop(context);
        clearLabEntities(context.level(), context.player());
        clearAndFloor(context.level());
        buildPerimeter(context.level());
        buildSafeHouse(context.level());
        buildMaterialDepot(context.level());
        buildVillageModule(context.level());
        buildUnfinishedPlatform(context.level());
        spawnResidents(context.level());
        configurePlayer(context.player());
        prepareClearNight(context.level());
        assertInitialState(context.level(), context.player());
        teleport(context);

        output.accept(Component.literal("iron_farm.lab=ready"
                + " bounds=" + position(LAB_MIN) + ".." + position(LAB_MAX)
                + " safe_house=northwest"
                + " materials=west_depot"
                + " build_zone=center_marked"
                + " unsupported=bed,water,lava,mob_transport_prepared"
                + " selected_slot=1"));
    }

    static boolean contains(BlockPos position) {
        return position.getX() >= LAB_MIN.getX() && position.getX() <= LAB_MAX.getX()
                && position.getY() >= LAB_MIN.getY() && position.getY() <= LAB_MAX.getY()
                && position.getZ() >= LAB_MIN.getZ() && position.getZ() <= LAB_MAX.getZ();
    }

    static int unfinishedPlatformCellCount() {
        return constructionTargets().size();
    }

    static List<BlockPos> constructionTargets() {
        var result = new ArrayList<BlockPos>();
        for (int x = PLATFORM_MIN.getX(); x <= PLATFORM_MAX.getX(); x++) {
            for (int z = PLATFORM_MIN.getZ(); z <= PLATFORM_MAX.getZ(); z++) {
                BlockPos position = new BlockPos(x, PLATFORM_MIN.getY(), z);
                if (!CENTER_CHUTE.contains(position) && !isConstructionLane(position)) {
                    result.add(position);
                }
            }
        }
        return List.copyOf(result);
    }

    static List<BlockPos> constructionLanes() {
        var result = new ArrayList<BlockPos>();
        for (int x = PLATFORM_MIN.getX(); x <= PLATFORM_MAX.getX(); x++) {
            for (int z = PLATFORM_MIN.getZ(); z <= PLATFORM_MAX.getZ(); z++) {
                BlockPos position = new BlockPos(x, PLATFORM_MIN.getY(), z);
                if (!CENTER_CHUTE.contains(position) && isConstructionLane(position)) {
                    result.add(position);
                }
            }
        }
        return List.copyOf(result);
    }

    static List<BlockPos> waterSources() {
        return List.of(
                new BlockPos(255, 206, 250), new BlockPos(256, 206, 250),
                new BlockPos(255, 206, 261), new BlockPos(256, 206, 261),
                new BlockPos(250, 206, 255), new BlockPos(250, 206, 256),
                new BlockPos(261, 206, 255), new BlockPos(261, 206, 256));
    }

    static List<BlockPos> waterPlugs() {
        return waterSources().stream().map(source -> {
            if (source.getZ() < PLATFORM_MIN.getZ()) {
                return source.relative(Direction.SOUTH);
            }
            if (source.getZ() > PLATFORM_MAX.getZ()) {
                return source.relative(Direction.NORTH);
            }
            if (source.getX() < PLATFORM_MIN.getX()) {
                return source.relative(Direction.EAST);
            }
            return source.relative(Direction.WEST);
        }).distinct().toList();
    }

    static Map<BlockPos, List<ContainerEntry>> materialContents() {
        return MATERIALS;
    }

    static List<BlockPos> scareSightline() {
        var result = new ArrayList<BlockPos>();
        for (int x = 252; x <= 259; x++) {
            result.add(new BlockPos(x, 201, 249));
            result.add(new BlockPos(x, 202, 249));
        }
        return List.copyOf(result);
    }

    static void sendOracle(FixtureSecurity.Context context, Consumer<Component> output) {
        AABB bounds = AABB.encapsulatingFullBlocks(LAB_MIN, LAB_MAX);
        int villagers = context.level().getEntities(
                EntityTypes.VILLAGER, bounds, entity -> entity.entityTags().contains(ENTITY_TAG)).size();
        int zombies = context.level().getEntities(
                EntityTypes.ZOMBIE, bounds, entity -> entity.entityTags().contains(ENTITY_TAG)
                        && entity.getVehicle() instanceof Boat).size();
        int golems = context.level().getEntities(EntityTypes.IRON_GOLEM, bounds, entity -> true).size();
        var looseIronEntities = context.level().getEntities(
                EntityTypes.ITEM, bounds, entity -> entity.getItem().is(Items.IRON_INGOT));
        int looseIron = looseIronEntities.stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        String looseIronPositions = looseIronEntities.stream().limit(16)
                .map(entity -> String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)x%d",
                        entity.getX(), entity.getY(), entity.getZ(), entity.getItem().getCount()))
                .collect(java.util.stream.Collectors.joining(",", "[",
                        looseIronEntities.size() > 16 ? ",...]" : "]"));
        int playerIron = 0;
        for (int slot = 0; slot < context.player().getInventory().getContainerSize(); slot++) {
            if (context.player().getInventory().getItem(slot).is(Items.IRON_INGOT)) {
                playerIron += context.player().getInventory().getItem(slot).getCount();
            }
        }
        int collectedIron = 0;
        if (context.level().getBlockEntity(COLLECTION_CONTAINER) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.IRON_INGOT)) {
                    collectedIron += container.getItem(slot).getCount();
                }
            }
        }
        output.accept(Component.literal("TEST-ONLY IRON-FARM ORACLE:"
                + " tagged_villagers=" + villagers
                + " zombie_in_boat=" + zombies
                + " live_golems=" + golems
                + " loose_iron=" + looseIron
                + " loose_iron_positions=" + looseIronPositions
                + " player_iron=" + playerIron
                + " collected_iron=" + collectedIron));
    }

    static void activate(FixtureSecurity.Context context, Consumer<Component> output) {
        for (BlockPos target : constructionTargets()) {
            if (!context.level().getBlockState(target).is(Blocks.SMOOTH_STONE)) {
                throw new IllegalStateException("iron-farm floor is incomplete at " + target);
            }
        }
        teleportToSafeHouse(context);
        for (BlockPos plug : waterPlugs()) {
            BlockState state = context.level().getBlockState(plug);
            if (!state.is(Blocks.STONE) && !state.isAir()) {
                throw new IllegalStateException("iron-farm water plug diverged at " + plug);
            }
            setBlock(context.level(), plug, Blocks.AIR.defaultBlockState());
        }
        for (BlockPos sightline : scareSightline()) {
            setBlock(context.level(), sightline, Blocks.AIR.defaultBlockState());
        }
        output.accept(Component.literal(
                "TEST-ONLY IRON-FARM ACTIVATION: floor=verified water=released scare=enabled player=safe_house"));
    }

    static void completeForEvaluation(FixtureSecurity.Context context, Consumer<Component> output) {
        int completed = 0;
        for (BlockPos target : constructionTargets()) {
            BlockState state = context.level().getBlockState(target);
            if (!state.isAir() && !state.is(Blocks.SMOOTH_STONE)) {
                throw new IllegalStateException("iron-farm evaluation target diverged at " + target);
            }
            if (state.isAir()) {
                setBlock(context.level(), target, Blocks.SMOOTH_STONE.defaultBlockState());
                completed++;
            }
        }
        output.accept(Component.literal(
                "TEST-ONLY IRON-FARM EVALUATION COMPLETION: filled=" + completed
                        + " total=" + constructionTargets().size()));
    }

    static BlockState bedState(BedPart part) {
        return Blocks.BED.white().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.BED_PART, part)
                .setValue(BlockStateProperties.OCCUPIED, false);
    }

    static void configureVillager(Villager villager, long currentGameTime) {
        villager.setAge(0);
        villager.setPersistenceRequired();
        villager.addTag(ENTITY_TAG);
        villager.getBrain().setMemory(MemoryModuleType.LAST_SLEPT, currentGameTime);
    }

    static void configureZombie(Zombie zombie) {
        zombie.setBaby(false);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setCanPickUpLoot(false);
        zombie.addTag(ENTITY_TAG);
    }

    private static void clearAndFloor(ServerLevel level) {
        for (int x = LAB_MIN.getX(); x <= LAB_MAX.getX(); x++) {
            for (int z = LAB_MIN.getZ(); z <= LAB_MAX.getZ(); z++) {
                setBlock(level, new BlockPos(x, FLOOR_Y, z), Blocks.GLASS.defaultBlockState());
                for (int y = FLOOR_Y + 1; y <= LAB_MAX.getY(); y++) {
                    setBlock(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void buildPerimeter(ServerLevel level) {
        for (int coordinate = LAB_MIN.getX(); coordinate <= LAB_MAX.getX(); coordinate++) {
            setBlock(level, new BlockPos(coordinate, 200, LAB_MIN.getZ()),
                    Blocks.OAK_FENCE.defaultBlockState());
            setBlock(level, new BlockPos(coordinate, 200, LAB_MAX.getZ()),
                    Blocks.OAK_FENCE.defaultBlockState());
            setBlock(level, new BlockPos(LAB_MIN.getX(), 200, coordinate),
                    Blocks.OAK_FENCE.defaultBlockState());
            setBlock(level, new BlockPos(LAB_MAX.getX(), 200, coordinate),
                    Blocks.OAK_FENCE.defaultBlockState());
        }

        for (int x = 248; x <= 263; x++) {
            setBlock(level, new BlockPos(x, FLOOR_Y, 248), Blocks.STAINED_GLASS.yellow().defaultBlockState());
            setBlock(level, new BlockPos(x, FLOOR_Y, 263), Blocks.STAINED_GLASS.yellow().defaultBlockState());
        }
        for (int z = 249; z < 263; z++) {
            setBlock(level, new BlockPos(248, FLOOR_Y, z), Blocks.STAINED_GLASS.yellow().defaultBlockState());
            setBlock(level, new BlockPos(263, FLOOR_Y, z), Blocks.STAINED_GLASS.yellow().defaultBlockState());
        }
        for (int x = 230; x <= 282; x += 13) {
            for (int z = 230; z <= 282; z += 13) {
                setBlock(level, new BlockPos(x, FLOOR_Y, z), Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
    }

    private static void buildSafeHouse(ServerLevel level) {
        for (int x = 228; x <= 236; x++) {
            for (int z = 228; z <= 236; z++) {
                setBlock(level, new BlockPos(x, FLOOR_Y, z), Blocks.SMOOTH_STONE.defaultBlockState());
                setBlock(level, new BlockPos(x, 204, z), Blocks.SMOOTH_STONE.defaultBlockState());
                if (x == 228 || x == 236 || z == 228 || z == 236) {
                    for (int y = 200; y <= 203; y++) {
                        setBlock(level, new BlockPos(x, y, z), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
        }
        setBlock(level, new BlockPos(232, 204, 232), Blocks.SEA_LANTERN.defaultBlockState());
        setPaired(level, SAFE_BED_FOOT, bedState(BedPart.FOOT),
                SAFE_BED_HEAD, bedState(BedPart.HEAD));

        BlockState door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
        setPaired(level,
                SAFE_DOOR_LOWER,
                door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                SAFE_DOOR_UPPER,
                door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
    }

    private static void buildMaterialDepot(ServerLevel level) {
        MATERIALS.forEach((position, contents) -> {
            setBlock(level, position.below(), Blocks.CONCRETE.blue().defaultBlockState());
            setBlock(level, position, Blocks.BARREL.defaultBlockState()
                    .setValue(BarrelBlock.FACING, Direction.UP)
                    .setValue(BarrelBlock.OPEN, false));
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new IllegalStateException("iron-farm material barrel is unavailable at " + position);
            }
            container.clearContent();
            for (ContainerEntry entry : contents) {
                container.setItem(entry.slot(), new ItemStack(entry.item(), entry.count()));
            }
            container.setChanged();
        });
    }

    private static void buildVillageModule(ServerLevel level) {
        for (int x = 251; x <= 260; x++) {
            for (int z = 245; z <= 253; z++) {
                setBlock(level, new BlockPos(x, 203, z), VILLAGE_ROOF_STATE);
                if (x == 251 || x == 260 || z == 245 || z == 253) {
                    for (int y = 200; y <= 202; y++) {
                        setBlock(level, new BlockPos(x, y, z), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
        }

        for (BlockPos foot : VILLAGER_BEDS) {
            setPaired(level, foot, bedState(BedPart.FOOT),
                    foot.relative(Direction.EAST), bedState(BedPart.HEAD));
        }
        for (int x = 252; x <= 259; x++) {
            for (int y = 200; y <= 202; y++) {
                setBlock(level, new BlockPos(x, y, 249), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        for (BlockPos blocker : SCARE_BLOCKERS) {
            setPaired(level,
                    blocker, scareDoorState(DoubleBlockHalf.LOWER),
                    blocker.above(), scareDoorState(DoubleBlockHalf.UPPER));
        }
    }

    static BlockState scareDoorState(DoubleBlockHalf half) {
        return Blocks.IRON_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
    }

    static Map<BlockPos, BlockState> collectionLayout() {
        return Map.of(
                new BlockPos(255, 200, 255), hopperState(Direction.EAST),
                new BlockPos(256, 200, 255), hopperState(Direction.EAST),
                new BlockPos(257, 200, 255), hopperState(Direction.EAST),
                new BlockPos(255, 200, 256), hopperState(Direction.NORTH),
                new BlockPos(256, 200, 256), hopperState(Direction.NORTH),
                COLLECTION_CONTAINER, Blocks.BARREL.defaultBlockState()
                        .setValue(BarrelBlock.FACING, Direction.UP)
                        .setValue(BarrelBlock.OPEN, false));
    }

    static BlockState hopperState(Direction direction) {
        return Blocks.HOPPER.defaultBlockState()
                .setValue(BlockStateProperties.FACING_HOPPER, direction)
                .setValue(BlockStateProperties.ENABLED, true);
    }

    private static void buildUnfinishedPlatform(ServerLevel level) {
        constructionLanes().forEach(position ->
                setBlock(level, position, Blocks.SMOOTH_STONE.defaultBlockState()));

        // Spawnproof outer ring remains dry while the final-floor cross is built.
        for (int x = 248; x <= 263; x++) {
            setBlock(level, new BlockPos(x, 205, 248), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(x, 205, 263), Blocks.GLASS.defaultBlockState());
        }
        for (int z = 249; z < 263; z++) {
            setBlock(level, new BlockPos(248, 205, z), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(263, 205, z), Blocks.GLASS.defaultBlockState());
        }
        for (int z = 249; z <= 251; z++) {
            setBlock(level, new BlockPos(254, 205, z),
                    z == 251 ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.GLASS.defaultBlockState());
        }

        // All water starts behind a one-block stone plug. The dry outer ring can reach
        // every plug after the 60-cell floor plan succeeds.
        for (int x = 249; x <= 262; x++) {
            setBlock(level, new BlockPos(x, 205, 249), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(x, 205, 262), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(x, 206, 249), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(x, 206, 262), Blocks.GLASS.defaultBlockState());
        }
        for (int z = 250; z < 262; z++) {
            setBlock(level, new BlockPos(249, 205, z), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(262, 205, z), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(249, 206, z), Blocks.GLASS.defaultBlockState());
            setBlock(level, new BlockPos(262, 206, z), Blocks.GLASS.defaultBlockState());
        }
        for (BlockPos source : waterSources()) {
            setBlock(level, source.below(), Blocks.GLASS.defaultBlockState());
        }
        for (BlockPos plug : waterPlugs()) {
            setBlock(level, plug, Blocks.STONE.defaultBlockState());
        }
        for (BlockPos source : waterSources()) {
            setBlock(level, source, Blocks.WATER.defaultBlockState());
        }

        for (int y = 200; y <= 204; y++) {
            for (int x = 254; x <= 257; x++) {
                for (int z = 254; z <= 257; z++) {
                    boolean wall = x == 254 || x == 257 || z == 254 || z == 257;
                    if (wall) {
                        setBlock(level, new BlockPos(x, y, z), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
        }
        setBlock(level, LAVA_SOURCE, Blocks.LAVA.defaultBlockState());
        collectionLayout().forEach((position, state) -> setBlock(level, position, state));
        if (!(level.getBlockEntity(COLLECTION_CONTAINER) instanceof Container container)) {
            throw new IllegalStateException("iron-farm collection barrel is unavailable");
        }
        container.clearContent();
        container.setChanged();
        for (BlockPos chute : CENTER_CHUTE) {
            setBlock(level, new BlockPos(chute.getX(), FLOOR_Y, chute.getZ()),
                    Blocks.STAINED_GLASS.lime().defaultBlockState());
        }
        setBlock(level, new BlockPos(258, FLOOR_Y, 255), Blocks.STAINED_GLASS.lime().defaultBlockState());
    }

    private static void spawnResidents(ServerLevel level) {
        long currentGameTime = level.getGameTime();
        for (BlockPos position : VILLAGER_POSITIONS) {
            Villager villager = EntityTypes.VILLAGER.spawn(level, position, EntitySpawnReason.COMMAND);
            if (villager == null) {
                throw new IllegalStateException("iron-farm fixture could not spawn an adult villager");
            }
            configureVillager(villager, currentGameTime);
            villager.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        }

        Boat boat = EntityTypes.OAK_BOAT.spawn(level, ZOMBIE_BOAT, EntitySpawnReason.COMMAND);
        Zombie zombie = EntityTypes.ZOMBIE.spawn(level, ZOMBIE_BOAT, EntitySpawnReason.COMMAND);
        if (boat == null || zombie == null) {
            if (boat != null) {
                boat.discard();
            }
            if (zombie != null) {
                zombie.discard();
            }
            throw new IllegalStateException("iron-farm fixture could not spawn the zombie boat pair");
        }
        boat.addTag(ENTITY_TAG);
        boat.setPos(ZOMBIE_BOAT.getX() + 0.5D, ZOMBIE_BOAT.getY(), ZOMBIE_BOAT.getZ() + 0.5D);
        configureZombie(zombie);
        zombie.setPos(ZOMBIE_BOAT.getX() + 0.5D, ZOMBIE_BOAT.getY(), ZOMBIE_BOAT.getZ() + 0.5D);
        if (!zombie.startRiding(boat)) {
            zombie.discard();
            boat.discard();
            throw new IllegalStateException("iron-farm fixture zombie could not enter its boat");
        }
    }

    private static void configurePlayer(ServerPlayer player) {
        FixtureArena.resetPlayer(player);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        player.getInventory().setItem(1, new ItemStack(Items.SMOOTH_STONE, 64));
        player.getInventory().setItem(2, new ItemStack(Items.SMOOTH_STONE, 64));
        player.getInventory().setItem(3, new ItemStack(Items.SMOOTH_STONE, 64));
        player.getInventory().setItem(4, new ItemStack(Items.GLASS, 64));
        player.getInventory().setItem(5, new ItemStack(Items.IRON_AXE));
        player.getInventory().setItem(6, new ItemStack(Items.LADDER, 32));
        player.getInventory().setItem(7, new ItemStack(Items.TORCH, 64));
        player.getInventory().setItem(8, new ItemStack(Items.BREAD, 16));
        player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                new ItemStack(Items.SHIELD));
        player.getInventory().setSelectedSlot(1);
        player.resetSentInfo();
        player.initInventoryMenu();
        player.inventoryMenu.broadcastFullState();
    }

    private static void prepareClearNight(ServerLevel level) {
        var clock = level.dimensionType().defaultClock()
                .orElseThrow(() -> new IllegalStateException("Overworld fixture clock is unavailable"));
        level.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.NIGHT);
        var weather = level.getWeatherData();
        weather.setClearWeatherTime(6000);
        weather.setRainTime(0);
        weather.setRaining(false);
        weather.setThunderTime(0);
        weather.setThundering(false);
    }

    private static void teleport(FixtureSecurity.Context context) {
        if (!context.player().teleportTo(
                context.level(), 254.5D, 206.0D, 254.5D, Set.<Relative>of(),
                0.0F, 28.0F, false)) {
            throw new IllegalStateException("iron-farm fixture could not synchronize player pose");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static void teleportToSafeHouse(FixtureSecurity.Context context) {
        if (!context.player().teleportTo(
                context.level(), 232.5D, 200.0D, 233.5D, Set.<Relative>of(),
                180.0F, 10.0F, false)) {
            throw new IllegalStateException("iron-farm fixture could not move player to the safe house");
        }
        context.player().setDeltaMovement(0.0D, 0.0D, 0.0D);
        context.player().resetFallDistance();
    }

    private static void clearLabEntities(ServerLevel level, ServerPlayer owner) {
        var entities = new ArrayList<Entity>();
        AABB bounds = AABB.encapsulatingFullBlocks(LAB_MIN, LAB_MAX);
        for (Entity entity : level.getAllEntities()) {
            if (entity != owner && bounds.contains(entity.position())) {
                entities.add(entity);
            }
        }
        entities.forEach(Entity::discard);
    }

    private static void assertInitialState(ServerLevel level, ServerPlayer player) {
        AABB bounds = AABB.encapsulatingFullBlocks(LAB_MIN, LAB_MAX);
        boolean hasGolem = !level.getEntities(EntityTypes.IRON_GOLEM, bounds, entity -> true).isEmpty();
        boolean hasLooseIron = !level.getEntities(
                EntityTypes.ITEM, bounds, entity -> entity.getItem().is(Items.IRON_INGOT)).isEmpty();
        boolean hasPlayerIron = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            hasPlayerIron |= player.getInventory().getItem(slot).is(Items.IRON_INGOT);
        }
        if (hasGolem || hasLooseIron || hasPlayerIron) {
            throw new IllegalStateException("iron-farm fixture initial output state is not zero");
        }
        if (!(level.getBlockEntity(COLLECTION_CONTAINER) instanceof Container container)
                || !container.isEmpty()) {
            throw new IllegalStateException("iron-farm fixture collection barrel is not empty");
        }
    }

    private static boolean isConstructionLane(BlockPos position) {
        return position.getX() == 254 || position.getX() == 257
                || position.getZ() == 254 || position.getZ() == 257;
    }

    private static Map<BlockPos, List<ContainerEntry>> materials() {
        var result = new LinkedHashMap<BlockPos, List<ContainerEntry>>();
        result.put(MATERIAL_BARRELS.get(0), List.of(
                new ContainerEntry(0, Items.SMOOTH_STONE, 64),
                new ContainerEntry(1, Items.SMOOTH_STONE, 64),
                new ContainerEntry(2, Items.SMOOTH_STONE, 64),
                new ContainerEntry(3, Items.SMOOTH_STONE, 64),
                new ContainerEntry(4, Items.SMOOTH_STONE, 64),
                new ContainerEntry(5, Items.SMOOTH_STONE, 64)));
        result.put(MATERIAL_BARRELS.get(1), List.of(
                new ContainerEntry(0, Items.GLASS, 64),
                new ContainerEntry(1, Items.GLASS, 64),
                new ContainerEntry(2, Items.GLASS, 64),
                new ContainerEntry(3, Items.GLASS_PANE, 64),
                new ContainerEntry(4, Items.SMOOTH_STONE_SLAB, 64)));
        result.put(MATERIAL_BARRELS.get(2), List.of(
                new ContainerEntry(0, Items.HOPPER, 16),
                new ContainerEntry(1, Items.CHEST, 8),
                new ContainerEntry(2, Items.OAK_SIGN, 32),
                new ContainerEntry(3, Items.LADDER, 64)));
        result.put(MATERIAL_BARRELS.get(3), List.of(
                new ContainerEntry(0, Items.TORCH, 64),
                new ContainerEntry(1, Items.BREAD, 32),
                new ContainerEntry(2, Items.IRON_PICKAXE, 1),
                new ContainerEntry(3, Items.IRON_AXE, 1)));
        return Map.copyOf(result);
    }

    private static void setPaired(
            ServerLevel level,
            BlockPos firstPosition,
            BlockState firstState,
            BlockPos secondPosition,
            BlockState secondState) {
        setBlock(level, firstPosition, firstState, PAIRED_FLAGS);
        setBlock(level, secondPosition, secondState, PAIRED_FLAGS);
    }

    private static void setBlock(ServerLevel level, BlockPos position, BlockState state) {
        setBlock(level, position, state, MUTATION_FLAGS);
    }

    private static void setBlock(ServerLevel level, BlockPos position, BlockState state, int flags) {
        if (!contains(position)) {
            throw new IllegalArgumentException("iron-farm fixture attempted an out-of-bounds write at " + position);
        }
        if (!level.setBlock(position, state, flags) && !level.getBlockState(position).equals(state)) {
            throw new IllegalStateException("iron-farm fixture could not set " + position + " to " + state);
        }
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    record ContainerEntry(int slot, Item item, int count) {
        ContainerEntry {
            if (slot < 0 || slot >= 27 || item == Items.AIR || count < 1 || count > 64) {
                throw new IllegalArgumentException("invalid iron-farm fixture container entry");
            }
        }
    }
}
