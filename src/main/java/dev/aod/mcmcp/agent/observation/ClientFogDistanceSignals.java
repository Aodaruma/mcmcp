package dev.aod.mcmcp.agent.observation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.entity.Entity;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Fresh renderer-computed fog boundary for the exact level, camera entity and entity tick. */
public final class ClientFogDistanceSignals {
    private static final Map<Object, Signal> SIGNALS = new WeakHashMap<>();

    private ClientFogDistanceSignals() {
    }

    public static void record(ClientLevel level, Entity cameraEntity, FogData fog) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cameraEntity, "cameraEntity");
        Objects.requireNonNull(fog, "fog");
        recordIdentity(
                level,
                cameraEntity,
                cameraEntity.tickCount,
                effectiveEnd(fog.environmentalEnd, fog.renderDistanceEnd));
    }

    public static double currentOr(
            ClientLevel level, Entity cameraEntity, int entityTick, double fallback) {
        return currentOrIdentity(level, cameraEntity, entityTick, fallback);
    }

    static void recordIdentity(
            Object levelIdentity, Object cameraIdentity, int entityTick, double distance) {
        Objects.requireNonNull(levelIdentity, "levelIdentity");
        Objects.requireNonNull(cameraIdentity, "cameraIdentity");
        if (entityTick < 0 || !Double.isFinite(distance) || distance <= 0.0D) {
            throw new IllegalArgumentException("fog sample must have a valid tick and distance");
        }
        synchronized (SIGNALS) {
            SIGNALS.put(levelIdentity, new Signal(
                    new WeakReference<>(levelIdentity),
                    new WeakReference<>(cameraIdentity),
                    entityTick,
                    distance));
        }
    }

    static double currentOrIdentity(
            Object levelIdentity, Object cameraIdentity, int entityTick, double fallback) {
        Objects.requireNonNull(levelIdentity, "levelIdentity");
        Objects.requireNonNull(cameraIdentity, "cameraIdentity");
        if (!Double.isFinite(fallback) || fallback <= 0.0D) {
            throw new IllegalArgumentException("fallback must be positive and finite");
        }
        synchronized (SIGNALS) {
            Signal signal = SIGNALS.get(levelIdentity);
            return signal != null
                    && signal.levelIdentity().get() == levelIdentity
                    && signal.cameraIdentity().get() == cameraIdentity
                    && signal.entityTick() == entityTick
                    ? signal.distance() : fallback;
        }
    }

    static double effectiveEnd(float environmentalEnd, float renderDistanceEnd) {
        double environment = positiveOrInfinity(environmentalEnd);
        double render = positiveOrInfinity(renderDistanceEnd);
        double nearest = Math.min(environment, render);
        return Double.isFinite(nearest) ? Math.max(1.0D / 16.0D, nearest) : 1.0D / 16.0D;
    }

    private static double positiveOrInfinity(float value) {
        return Float.isFinite(value) && value > 0.0F ? value : Double.POSITIVE_INFINITY;
    }

    private record Signal(
            WeakReference<Object> levelIdentity,
            WeakReference<Object> cameraIdentity,
            int entityTick,
            double distance) {
    }
}
