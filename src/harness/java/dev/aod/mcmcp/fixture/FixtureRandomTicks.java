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
    static final int ACCELERATED_SPEED = 30;
    static final int COMBINED_WHEAT_ACCELERATED_SPEED = 300;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FixtureRandomTickLease LEASE = new FixtureRandomTickLease();

    private FixtureRandomTicks() {
    }

    static void status(FixtureSecurity.Context current, Consumer<Component> output) {
        requireCurrentWorld(current);
        sendStatus(current, ACCELERATED_SPEED, output);
    }

    static void statusCombinedWheat(FixtureSecurity.Context current, Consumer<Component> output) {
        requireCurrentWorld(current);
        sendStatus(current, COMBINED_WHEAT_ACCELERATED_SPEED, output);
    }

    static void accelerate(FixtureSecurity.Context current, Consumer<Component> output) {
        accelerateTo(current, ACCELERATED_SPEED, FixtureRandomTickLease.Owner.GENERIC, output);
    }

    static void requireInactiveForCombinedWheat(FixtureSecurity.Context current) {
        requireCurrentWorld(current);
        if (LEASE.active()) {
            throw new IllegalStateException(
                    "another random tick acceleration lease is already active");
        }
    }

    static void accelerateCombinedWheat(
            FixtureSecurity.Context current, Consumer<Component> output) {
        accelerateTo(current, COMBINED_WHEAT_ACCELERATED_SPEED,
                FixtureRandomTickLease.Owner.COMBINED_WHEAT, output);
    }

    private static void accelerateTo(
            FixtureSecurity.Context current,
            int targetSpeed,
            FixtureRandomTickLease.Owner owner,
            Consumer<Component> output) {
        var rules = current.server().getGameRules();
        int target = LEASE.begin(
                current.server(), current.level(), rules.get(GameRules.RANDOM_TICK_SPEED),
                targetSpeed, owner);
        rules.set(GameRules.RANDOM_TICK_SPEED, target, current.server());
        sendStatus(current, targetSpeed, output);
    }

    static void restore(FixtureSecurity.Context current, Consumer<Component> output) {
        restore(current, FixtureRandomTickLease.Owner.GENERIC, ACCELERATED_SPEED, output);
    }

    static void restoreCombinedWheat(
            FixtureSecurity.Context current, Consumer<Component> output) {
        restore(current, FixtureRandomTickLease.Owner.COMBINED_WHEAT,
                COMBINED_WHEAT_ACCELERATED_SPEED, output);
    }

    private static void restore(
            FixtureSecurity.Context current,
            FixtureRandomTickLease.Owner expectedOwner,
            int inactiveTarget,
            Consumer<Component> output) {
        requireCurrentWorld(current);
        if (LEASE.active()) {
            if (LEASE.owner() != expectedOwner) {
                throw new IllegalStateException("random tick lease is owned by another fixture");
            }
            current.server().getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED, LEASE.originalSpeed(), current.server());
            clear();
        }
        sendStatus(current, inactiveTarget, output);
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
            clear();
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "MCMCP fixture could not restore random_tick_speed during shutdown; lease retained",
                    exception);
        }
    }

    static void onServerStopped(ServerStoppedEvent event) {
        if (!LEASE.active() || event.getServer() != LEASE.server()) {
            return;
        }
        LOGGER.error(
                "MCMCP fixture server stopped before random_tick_speed restoration was confirmed; "
                        + "the lease is retained fail-closed and is not falsely restored after save closure");
    }

    static boolean combinedLeaseActiveFor(Object server) {
        return LEASE.active()
                && LEASE.server() == server
                && LEASE.owner() == FixtureRandomTickLease.Owner.COMBINED_WHEAT;
    }

    private static void requireCurrentWorld(FixtureSecurity.Context current) {
        LEASE.requireCurrent(current.server(), current.level());
    }

    private static void sendStatus(
            FixtureSecurity.Context current, int inactiveTarget, Consumer<Component> output) {
        output.accept(Component.literal("random_ticks.mode=" + (LEASE.active() ? "accelerated" : "normal")
                + " current=" + current.server().getGameRules().get(GameRules.RANDOM_TICK_SPEED)
                + " saved=" + (LEASE.originalSpeed() == null ? "none" : LEASE.originalSpeed())
                + " target=" + (LEASE.targetSpeed() == null
                        ? inactiveTarget : LEASE.targetSpeed())));
    }

    private static void clear() {
        LEASE.clear();
    }
}
