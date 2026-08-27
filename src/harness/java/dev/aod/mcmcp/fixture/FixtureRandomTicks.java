package dev.aod.mcmcp.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.function.Consumer;

/** A fixed, reversible random-tick accelerator for private integrated-server fixtures. */
final class FixtureRandomTicks {
    static final int ACCELERATED_SPEED = FixtureRandomTickLease.ACCELERATED_SPEED;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FixtureRandomTickLease LEASE = new FixtureRandomTickLease();

    private FixtureRandomTicks() {
    }

    static void status(FixtureSecurity.Context current, Consumer<Component> output) {
        requireCurrentWorld(current);
        sendStatus(current, output);
    }

    static void accelerate(FixtureSecurity.Context current, Consumer<Component> output) {
        var rules = current.server().getGameRules();
        int target = LEASE.begin(
                current.server(), current.level(), rules.get(GameRules.RANDOM_TICK_SPEED));
        rules.set(GameRules.RANDOM_TICK_SPEED, target, current.server());
        sendStatus(current, output);
    }

    static void restore(FixtureSecurity.Context current, Consumer<Component> output) {
        requireCurrentWorld(current);
        if (LEASE.active()) {
            current.server().getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED, LEASE.originalSpeed(), current.server());
            clear();
        }
        sendStatus(current, output);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        if (!LEASE.active() || event.getServer() != LEASE.server()) {
            return;
        }
        try {
            if (!event.getServer().isSameThread()) {
                throw new IllegalStateException("server stopping event was off-thread");
            }
            event.getServer().getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED, LEASE.originalSpeed(), event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("MCMCP fixture could not restore random_tick_speed during shutdown", exception);
        } finally {
            clear();
        }
    }

    static void onServerStopped(ServerStoppedEvent event) {
        if (LEASE.active() && event.getServer() == LEASE.server()) {
            LOGGER.warn("MCMCP fixture server stopped before random_tick_speed restoration was confirmed");
            clear();
        }
    }

    private static void requireCurrentWorld(FixtureSecurity.Context current) {
        LEASE.requireCurrent(current.server(), current.level());
    }

    private static void sendStatus(
            FixtureSecurity.Context current, Consumer<Component> output) {
        output.accept(Component.literal("random_ticks.mode=" + (LEASE.active() ? "accelerated" : "normal")
                + " current=" + current.server().getGameRules().get(GameRules.RANDOM_TICK_SPEED)
                + " saved=" + (LEASE.originalSpeed() == null ? "none" : LEASE.originalSpeed())
                + " target=" + ACCELERATED_SPEED));
    }

    private static void clear() {
        LEASE.clear();
    }
}
