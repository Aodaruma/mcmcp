package dev.aodaruma.craftagent.fixture;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.function.Consumer;

/** Fixed, server-authoritative scenarios for client prediction/ACK routine testing. */
final class FixturePhase2Scenario {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REGENERATION_DELAY_TICKS = 8;

    private static FixtureSecurity.Context context;
    private static Mode mode;
    private static boolean targetWasSolid;
    private static long regenerateAtTick = -1;
    private static int serverBreaks;
    private static int regenerations;
    private static String stopReason;

    private FixturePhase2Scenario() {
    }

    static void prepare(FixtureSecurity.Context authorized, Mode requestedMode) {
        stop();
        FixtureArena.preparePhase2(authorized, requestedMode == Mode.SLOW_TARGET);
        context = authorized;
        mode = requestedMode;
        targetWasSolid = true;
        regenerateAtTick = -1;
        serverBreaks = 0;
        regenerations = 0;
        stopReason = null;
    }

    static void onServerTick(ServerTickEvent.Post event) {
        if (mode == null) {
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
        var state = context.level().getBlockState(FixtureArena.PHASE2_TARGET);
        if (targetWasSolid && state.isAir()) {
            targetWasSolid = false;
            serverBreaks++;
            if (mode == Mode.REGENERATE) {
                regenerateAtTick = tick + REGENERATION_DELAY_TICKS;
            }
        }

        if (mode == Mode.REGENERATE && regenerateAtTick >= 0 && tick >= regenerateAtTick) {
            if (!state.isAir()) {
                stopWithReason("target_changed_before_regeneration");
                return;
            }
            if (!FixtureArena.restorePhase2Stone(context)) {
                stopWithReason("regeneration_write_rejected");
                return;
            }
            regenerations++;
            targetWasSolid = true;
            regenerateAtTick = -1;
        }
    }

    static void onServerStopped(ServerStoppedEvent event) {
        if (context != null && event.getServer() == context.server()) {
            stop();
        }
    }

    static void sendStatus(FixtureSecurity.Context current, Consumer<Component> output) {
        boolean sameServer = context != null && current.server() == context.server();
        var state = current.level().getBlockState(FixtureArena.PHASE2_TARGET);
        output.accept(Component.literal("phase2.mode=" + (sameServer && mode != null ? mode.wireName : "off")
                + " target=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " server_breaks=" + serverBreaks
                + " regenerations=" + regenerations
                + " regenerate_at_tick=" + regenerateAtTick
                + " stop_reason=" + (stopReason == null ? "none" : stopReason)));
    }

    static void stop() {
        context = null;
        mode = null;
        targetWasSolid = false;
        regenerateAtTick = -1;
        stopReason = null;
    }

    private static void stopWithReason(String reason) {
        stopReason = reason == null ? "unknown" : reason.replaceAll("[\\p{Cntrl}]", " ").strip();
        LOGGER.warn("CraftAgent Phase 2 fixture stopped: {}", stopReason);
        context = null;
        mode = null;
        targetWasSolid = false;
        regenerateAtTick = -1;
    }

    enum Mode {
        REGENERATE("regen"),
        NO_REGENERATION("no_regen"),
        SLOW_TARGET("slow");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }
    }
}
