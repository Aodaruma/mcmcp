package dev.aod.mcmcp.runtime;

/** Client packet evidence. Counts losses individually so healing packets never erase them. */
public final class PlayerDamageSignals {
    private static final PlayerDamageSignals GLOBAL = new PlayerDamageSignals();
    public static PlayerDamageSignals global() { return GLOBAL; }
    private Object player;
    private double health, absorption, loss;
    private long falls, other;
    public synchronized Snapshot bind(Object identity, double health, double absorption) {
        if (player != identity) {
            player = identity; this.health = health; this.absorption = absorption;
            loss = 0; falls = 0; other = 0;
        }
        return new Snapshot(loss, falls, other);
    }
    public synchronized void health(Object identity, double value, double absorbed) {
        if (player != identity) { bind(identity, value, absorbed); return; }
        loss += Math.max(0, health - value) + Math.max(0, absorption - absorbed);
        health = value; absorption = absorbed;
    }
    public synchronized void damage(Object identity, boolean fall, double health, double absorption) {
        bind(identity, health, absorption);
        if (fall) falls++; else other++;
    }
    public record Snapshot(double loss, long falls, long other) { }
}
