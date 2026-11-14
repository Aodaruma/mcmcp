package dev.aod.mcmcp.safety;

import java.util.function.IntConsumer;

/** Retains uncertain bow cancellation across the combat and global input-release owners. */
public final class BowUseCancellation {
    private static final BowUseCancellation GLOBAL = new BowUseCancellation();
    private Object pendingOwner;

    public static BowUseCancellation global() { return GLOBAL; }

    /** A replacement player must never inherit the old player's hotbar cleanup. */
    public boolean pendingFor(Object player) {
        if (pendingOwner != player) pendingOwner = null;
        return pendingOwner != null;
    }

    /** A throwing fire can already have dispatched; its cleanup must cancel, never fire again. */
    public void fire(Object player, int selectedSlot, IntConsumer sendSlot, Runnable fire, Runnable stopLocalUse) {
        try { fire.run(); }
        catch (RuntimeException | LinkageError failure) {
            cancel(player, selectedSlot, sendSlot, stopLocalUse);
            throw failure;
        }
    }

    public boolean cancel(Object player, int selectedSlot, IntConsumer sendSlot, Runnable stopLocalUse) {
        pendingOwner = java.util.Objects.requireNonNull(player);
        boolean released = true;
        try {
            sendSlot.accept((selectedSlot + 1) % 9);
        } catch (RuntimeException | LinkageError failure) {
            released = false;
        } finally {
            // Even a throwing send can have dispatched; restore the slot and stop local use independently.
            try { sendSlot.accept(selectedSlot); }
            catch (RuntimeException | LinkageError failure) { released = false; }
            finally {
                try { stopLocalUse.run(); }
                catch (RuntimeException | LinkageError failure) { released = false; }
            }
        }
        if (released) pendingOwner = null;
        return released;
    }
}
