package dev.aod.mcmcp.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.function.Consumer;

/** Lifecycle and reversible random-tick lease for the combined wheat E2E fixture. */
final class FixtureCombinedWheatScenario {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static FixtureSecurity.Context context;
    private static Object lastServerIdentity;
    private static Object lastWorldIdentity;
    private static State state = State.OFF;
    private static String stopReason;

    private FixtureCombinedWheatScenario() {
    }

    static void arm(FixtureSecurity.Context authorized) {
        if (context != null || state == State.RUNNING || state == State.RESTORE_PENDING) {
            throw new IllegalStateException("combined wheat fixture was already active");
        }
        context = authorized;
        lastServerIdentity = authorized.server();
        lastWorldIdentity = authorized.level();
        state = State.RUNNING;
        stopReason = null;
    }

    static void onServerTick(ServerTickEvent.Post event) {
        if (state != State.RUNNING && state != State.RESTORE_PENDING) {
            return;
        }
        if (context == null || event.getServer() != context.server()) {
            state = State.ROLLED_BACK;
            stopReason = "server_identity_changed";
            context = null;
            return;
        }

        FixtureSecurity.Decision decision = FixtureSecurity.reauthorize(context);
        if (!decision.allowed()) {
            restoreAndFinish(State.ROLLED_BACK, decision.rejection());
            return;
        }
        if (progress(context).complete()) {
            restoreAndFinish(State.COMPLETE, null);
        }
    }

    static void onServerStopped(ServerStoppedEvent event) {
        if (context != null && event.getServer() == context.server()) {
            context = null;
            state = State.OFF;
            stopReason = null;
        }
        if (event.getServer() == lastServerIdentity) {
            lastServerIdentity = null;
            lastWorldIdentity = null;
        }
    }

    static void sendStatus(FixtureSecurity.Context current, Consumer<Component> output) {
        boolean currentOwner = (context != null
                && current.server() == context.server()
                && current.level() == context.level())
                || (current.server() == lastServerIdentity
                && current.level() == lastWorldIdentity);
        FixtureCombinedWheatProgress progress = progress(current);
        int chestHoe = 0;
        int chestSeeds = 0;
        if (current.level().getBlockEntity(FixturePhase5Scenario.COMBINED_SUPPLY_CHEST)
                instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (stack.is(Items.IRON_HOE)) {
                    chestHoe += stack.getCount();
                } else if (stack.is(Items.WHEAT_SEEDS)) {
                    chestSeeds += stack.getCount();
                }
            }
        }
        var gate = current.level().getBlockState(FixturePhase5Scenario.COMBINED_FARM_GATE);
        output.accept(Component.literal("phase5.combined_wheat.state="
                + (currentOwner ? state.wireName : "inactive")
                + " wheat=" + progress.wheatCount()
                + " farmland=" + progress.farmlandCount() + '/' + progress.plotCount()
                + " replanted=" + progress.plantedCropCount() + '/' + progress.plotCount()
                + " chest_hoe=" + chestHoe
                + " chest_seeds=" + chestSeeds
                + " gate=" + BuiltInRegistries.BLOCK.getKey(gate.getBlock())
                + " gate_open=" + (gate.hasProperty(BlockStateProperties.OPEN)
                        ? gate.getValue(BlockStateProperties.OPEN) : "unknown")
                + " complete=" + progress.complete()
                + " stop_reason=" + (stopReason == null ? "none" : stopReason)));
        FixtureRandomTicks.status(current, output);
    }

    static void rollback(FixtureSecurity.Context current, Consumer<Component> output) {
        if (context != null
                && (current.server() != context.server() || current.level() != context.level())) {
            throw new IllegalStateException("combined wheat fixture belongs to another world");
        }
        if (context != null) {
            restoreAndFinish(State.ROLLED_BACK, "manual_rollback");
        }
        sendStatus(current, output);
    }

    static void rollbackForArenaReset(FixtureSecurity.Context current) {
        rollbackForReplacement(current);
    }

    static void rollbackForReplacement(FixtureSecurity.Context current) {
        if (context != null) {
            if (current.server() != context.server() || current.level() != context.level()) {
                throw new IllegalStateException("combined wheat fixture belongs to another world");
            }
            restoreAndFinish(State.ROLLED_BACK, "fixture_replaced");
            if (context != null) {
                throw new IllegalStateException(
                        "combined wheat random tick lease could not be restored");
            }
        }
        if (current.server() == lastServerIdentity && current.level() == lastWorldIdentity) {
            lastServerIdentity = null;
            lastWorldIdentity = null;
            state = State.OFF;
            stopReason = null;
        }
    }

    static FixtureCombinedWheatProgress progress(FixtureSecurity.Context current) {
        int wheat = 0;
        var inventory = current.player().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(Items.WHEAT)) {
                wheat += stack.getCount();
            }
        }
        int farmland = 0;
        int planted = 0;
        for (int index = 0; index < FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.size(); index++) {
            if (current.level().getBlockState(
                    FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.get(index)).is(Blocks.FARMLAND)) {
                farmland++;
            }
            if (current.level().getBlockState(
                    FixturePhase5Scenario.COMBINED_FARM_CROPS.get(index)).is(Blocks.WHEAT)) {
                planted++;
            }
        }
        return new FixtureCombinedWheatProgress(
                wheat, farmland, planted, FixturePhase5Scenario.COMBINED_FARM_SUPPORTS.size());
    }

    private static void restoreAndFinish(State terminal, String reason) {
        FixtureSecurity.Context owned = context;
        if (owned == null) {
            return;
        }
        try {
            FixtureRandomTicks.restore(owned,
                    component -> LOGGER.info("MCMCP combined wheat fixture: {}",
                            component.getString()));
        } catch (RuntimeException exception) {
            state = State.RESTORE_PENDING;
            stopReason = "random_tick_restore_failed";
            LOGGER.error("MCMCP combined wheat fixture could not restore random_tick_speed", exception);
            return;
        }
        state = terminal;
        stopReason = sanitize(reason);
        context = null;
        LOGGER.info("MCMCP combined wheat fixture ended: state={}, reason={}",
                state.wireName, stopReason == null ? "none" : stopReason);
    }

    private static String sanitize(String value) {
        return value == null ? null : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private enum State {
        OFF("off"),
        RUNNING("running"),
        COMPLETE("complete"),
        ROLLED_BACK("rolled_back"),
        RESTORE_PENDING("restore_pending");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }
    }
}
