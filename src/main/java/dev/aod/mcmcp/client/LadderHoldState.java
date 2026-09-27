package dev.aod.mcmcp.client;

/** Explicit resting owner, separate from finite Action movement leases. Client-thread only. */
public final class LadderHoldState {
    private static final LadderHoldState GLOBAL = new LadderHoldState();
    private Object player;
    private Object level;

    public static LadderHoldState global() { return GLOBAL; }

    public void acquire(Object player, Object level) {
        this.player = java.util.Objects.requireNonNull(player);
        this.level = java.util.Objects.requireNonNull(level);
    }

    public boolean active(Object player, Object level) {
        return this.player != null && this.player == player && this.level == level;
    }

    public void validate(Object player, Object level, boolean armed, boolean safeLadder) {
        if (!armed || !safeLadder || !active(player, level)) clear();
    }

    public void physicalInput(net.minecraft.world.entity.player.Input input, boolean actionOwnsMovement) {
        if (!actionOwnsMovement && (input.forward() || input.backward() || input.left()
                || input.right() || input.jump() || input.sprint())) clear();
    }

    public void clear() { player = null; level = null; }
}
