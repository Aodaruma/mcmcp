package dev.aodaruma.craftagent.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** One deterministic, temporary neutral-mob obstruction for the moving build-runner fixture. */
final class FixturePhase4RouteBlocker {
    static final int OCCUPANCY_TICKS = 20;
    static final String COW_TAG = "craftagent_phase4_route_blocker";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static FixtureSecurity.Context context;
    private static long removeAtTick = -1L;
    private static State state = State.OFF;

    private FixturePhase4RouteBlocker() {
    }

    static void arm(FixtureSecurity.Context authorized) {
        stop();
        context = authorized;
        state = State.WAITING_FOR_FIRST_COLUMN;
    }

    static void onServerTick(ServerTickEvent.Post event) {
        if (state == State.OFF) {
            return;
        }
        if (context == null || event.getServer() != context.server()) {
            stopWithReason("server_identity_changed");
            return;
        }
        var decision = FixtureSecurity.reauthorize(context);
        if (!decision.allowed()) {
            stopWithReason(decision.rejection());
            return;
        }
        long tick = event.getServer().getTickCount();
        if (state == State.WAITING_FOR_FIRST_COLUMN && firstColumnComplete()) {
            spawnBlocker();
            removeAtTick = tick + OCCUPANCY_TICKS;
            state = State.OCCUPYING_SECOND_ROUTE;
            LOGGER.info("CraftAgent fixture placed a neutral route blocker for {} ticks",
                    OCCUPANCY_TICKS);
            return;
        }
        if (state == State.OCCUPYING_SECOND_ROUTE && tick >= removeAtTick) {
            discardBlocker();
            state = State.COMPLETE;
            LOGGER.info("CraftAgent fixture removed the neutral route blocker");
        }
    }

    static void onServerStopped(ServerStoppedEvent event) {
        if (context != null && event.getServer() == context.server()) {
            stop();
        }
    }

    static void stop() {
        var previous = context;
        context = null;
        removeAtTick = -1L;
        state = State.OFF;
        if (previous != null && previous.server().isSameThread()) {
            discardBlocker(previous);
        }
    }

    private static boolean firstColumnComplete() {
        return FixturePhase4Scenario.BUILD_RUNNER_FIRST_COLUMN.stream()
                .allMatch(position -> context.level().getBlockState(position).is(Blocks.COBBLESTONE));
    }

    private static void spawnBlocker() {
        BlockPos position = FixturePhase4Scenario.BUILD_RUNNER_SECOND_POSE;
        Cow cow = EntityTypes.COW.spawn(context.level(), position, EntitySpawnReason.COMMAND);
        if (cow == null) {
            throw new IllegalStateException("Phase 4 fixture could not spawn its route blocker");
        }
        cow.setNoAi(true);
        cow.setPersistenceRequired();
        cow.addTag(COW_TAG);
        cow.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
    }

    private static void discardBlocker() {
        if (context == null) {
            return;
        }
        discardBlocker(context);
    }

    private static void discardBlocker(FixtureSecurity.Context target) {
        AABB arena = AABB.encapsulatingFullBlocks(FixtureArena.MIN, FixtureArena.MAX);
        target.level().getEntities(EntityTypes.COW, arena,
                        cow -> cow.entityTags().contains(COW_TAG))
                .forEach(Cow::discard);
    }

    private static void stopWithReason(String reason) {
        LOGGER.warn("CraftAgent Phase 4 route blocker stopped: {}", reason);
        stop();
    }

    private enum State {
        OFF,
        WAITING_FOR_FIRST_COLUMN,
        OCCUPYING_SECOND_ROUTE,
        COMPLETE
    }
}
