package dev.aod.mcmcp.agent.safety;

/** Movement proof required for one traversability edge. */
public enum Locomotion {
    GROUND,
    LADDER,
    SCAFFOLDING,
    WATER,
    FLIGHT,
    SPECTATOR;

    public static final double FLIGHT_FEET_OFFSET = 0.2D;

    public boolean aerial() { return this == FLIGHT || this == SPECTATOR; }

    /** Permission comes from the current vanilla mode and ability, never from a tool argument. */
    public static Locomotion aerialMode(net.minecraft.client.player.LocalPlayer player) {
        return aerialMode(player.isCreative(), player.isSpectator(), player.mayFly());
    }

    public static Locomotion aerialMode(boolean creative, boolean spectator, boolean mayFly) {
        return !mayFly ? GROUND : spectator ? SPECTATOR : creative ? FLIGHT : GROUND;
    }

    public static Locomotion observedMode(net.minecraft.client.player.LocalPlayer player) {
        return player.getAbilities().flying ? aerialMode(player) : GROUND;
    }
}
