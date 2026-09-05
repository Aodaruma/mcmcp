package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.FrameItemPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One normal frame attack/use, with server display ACK and no retries after dispatch. */
public final class FrameItemAttempt implements AutoCloseable {
    public static final long MAX_TICKS = 400;
    public static final long ACK_TICKS = 60;
    private static final int MAX_RELEASE_ATTEMPTS = 40;
    private final FrameItemPort port;
    private final FrameItemPort.Request request;
    private final long admittedTick;
    private final long deadlineTick;
    private long lastTick;
    private long dispatchTick;
    private FrameItemPort.Frame before;
    private FrameItemPort.Frame latest;
    private boolean dispatched;
    private boolean effectRecorded;
    private int interactionDelta;
    private Status terminalIntent;
    private String terminalEvidence;
    private int releaseAttempts;
    private boolean released;
    private final ArrayList<EffectDelta> effects = new ArrayList<>(1);

    public FrameItemAttempt(FrameItemPort port, FrameItemPort.Request request,
                            long admittedClientTick, long deadlineClientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        if (admittedClientTick < 0 || deadlineClientTick <= admittedClientTick
                || deadlineClientTick - admittedClientTick > MAX_TICKS) {
            throw new IllegalArgumentException("frame operation exceeds its finite deadline");
        }
        admittedTick = lastTick = admittedClientTick;
        deadlineTick = deadlineClientTick;
    }

    public TickResult tick(long clientTick) {
        int priorDelta = interactionDelta;
        if (released) return result(0);
        if (clientTick < lastTick || clientTick < admittedTick) finish(Status.FAILED, "frame_tick_contract");
        lastTick = Math.max(clientTick, lastTick);
        if (terminalIntent != null) return finishRelease(0);
        try {
            if (clientTick >= deadlineTick) {
                finish(Status.FAILED, "frame_deadline");
            } else if (!dispatched) {
                latest = Objects.requireNonNull(port.prepare(request, clientTick), "frame preparation");
                if (!validFrame(latest, clientTick)) finish(Status.FAILED, "frame_observation_contract");
                else if (latest.failure() != null) finish(Status.FAILED, latest.failure());
                else if (latest.ready()) {
                    if (deadlineTick - clientTick < ACK_TICKS) {
                        finish(Status.FAILED, "frame_ack_budget_unavailable");
                    } else if (!matchesBefore(request, latest)) {
                        finish(Status.FAILED, "frame_display_precondition_changed");
                    } else {
                        before = latest;
                        dispatched = true;
                        dispatchTick = clientTick;
                        interactionDelta++;
                        // Reserve before the call: a throwing semantic dispatch is UNKNOWN, not retryable.
                        port.dispatch(request, clientTick);
                    }
                }
            } else {
                latest = Objects.requireNonNull(port.observe(request, clientTick), "frame observation");
                if (!validFrame(latest, clientTick)) finish(Status.FAILED, "frame_observation_contract");
                else if (latest.failure() != null) finish(Status.FAILED, latest.failure());
                else if (!latest.bodyAlive() || latest.rotation() != request.expectedRotation()) {
                    finish(Status.FAILED, "frame_body_or_rotation_changed");
                } else if (matchesAfter(request, before, latest)) {
                    recordEffect(AgentActionStore.Verification.CONFIRMED);
                    finish(Status.SUCCEEDED, "frame_display_server_confirmed");
                } else if (clientTick - dispatchTick >= ACK_TICKS) {
                    finish(Status.FAILED, "frame_item_ack_timeout");
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            finish(Status.FAILED, "frame_adapter_failed");
        }
        int delta = interactionDelta - priorDelta;
        return terminalIntent == null ? new TickResult(Status.RUNNING, null, delta) : finishRelease(delta);
    }

    private boolean validFrame(FrameItemPort.Frame frame, long tick) {
        return frame.clientTick() == tick && frame.worldRevision() >= 0
                && frame.packetRevision() >= 0 && frame.inventoryRevision() >= 0;
    }

    static boolean matchesBefore(FrameItemPort.Request request, FrameItemPort.Frame frame) {
        return frame.bodyAlive() && frame.rotation() == request.expectedRotation()
                && (request.mode() == FrameItemPort.Mode.REMOVE
                    ? request.item().equals(frame.displayedItem())
                    : frame.displayedItem() == null && frame.inventoryCount() >= 1);
    }

    static boolean matchesAfter(FrameItemPort.Request request, FrameItemPort.Frame before,
                                FrameItemPort.Frame after) {
        String expected = request.mode() == FrameItemPort.Mode.REMOVE ? null : request.item();
        return after.bodyAlive() && after.rotation() == request.expectedRotation()
                && after.serverItemObserved() && after.itemRevision() > before.packetRevision()
                && Objects.equals(expected, after.serverItem())
                && Objects.equals(expected, after.displayedItem())
                && (request.mode() == FrameItemPort.Mode.REMOVE
                    || (after.inventoryRevision() > before.inventoryRevision()
                        && after.inventoryExactChange()
                        && before.inventoryCount() - after.inventoryCount() == 1));
    }

    private void finish(Status status, String evidence) {
        if (terminalIntent != null) return;
        terminalIntent = status;
        terminalEvidence = evidence;
    }

    private TickResult finishRelease(int delta) {
        try { close(); } catch (RuntimeException | LinkageError ignored) { }
        return released ? result(delta) : new TickResult(Status.RUNNING, "frame_release_pending", delta);
    }

    private TickResult result(int delta) {
        return new TickResult(terminalIntent == null ? Status.FAILED : terminalIntent,
                terminalEvidence, delta);
    }

    @Override
    public void close() {
        if (released) return;
        if (terminalIntent == null) finish(Status.FAILED, "frame_cancelled");
        if (dispatched && !effectRecorded) recordEffect(AgentActionStore.Verification.UNKNOWN);
        releaseAttempts++;
        if (!port.release()) throw new IllegalStateException("frame release remains pending");
        released = true;
    }

    public ReleaseStatus releaseStatus() {
        if (released) return ReleaseStatus.CONFIRMED;
        if (releaseAttempts >= MAX_RELEASE_ATTEMPTS) return ReleaseStatus.FAULT;
        return terminalIntent == null ? ReleaseStatus.ACTIVE : ReleaseStatus.PROGRESSING;
    }

    public int drainInteractionDelta() {
        int delta = interactionDelta;
        interactionDelta = 0;
        return delta;
    }

    public List<EffectDelta> drainEffectDeltas() {
        var drained = List.copyOf(effects);
        effects.clear();
        return drained;
    }

    private void recordEffect(AgentActionStore.Verification verification) {
        if (effectRecorded || before == null) return;
        var observedBefore = new LinkedHashMap<String, Object>();
        observedBefore.put("displayed_item", before.displayedItem() == null ? "minecraft:air" : before.displayedItem());
        observedBefore.put("rotation", before.rotation());
        observedBefore.put("body_alive", before.bodyAlive());
        var observedAfter = new LinkedHashMap<String, Object>();
        if (latest != null && latest != before && latest.failure() == null) {
            observedAfter.put("body_alive", latest.bodyAlive());
            observedAfter.put("rotation", latest.rotation());
            if (latest.serverItemObserved() && latest.itemRevision() > before.packetRevision()) {
                observedAfter.put("displayed_item", latest.serverItem() == null ? "minecraft:air" : latest.serverItem());
            }
        }
        if (request.mode() == FrameItemPort.Mode.INSERT) {
            observedBefore.put("inventory_count", before.inventoryCount());
            if (latest != null && latest != before && latest.failure() == null
                    && latest.inventoryRevision() > before.inventoryRevision()) {
                observedAfter.put("inventory_count", latest.inventoryCount());
            }
        }
        effects.add(new EffectDelta(observedBefore, observedAfter, verification, lastTick,
                latest == null ? before.worldRevision() : Math.max(before.worldRevision(), latest.worldRevision())));
        effectRecorded = true;
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }
    public enum ReleaseStatus { ACTIVE, PROGRESSING, CONFIRMED, FAULT }
    public record TickResult(Status status, String evidence, int dispatchedThisTick) { }
    public record EffectDelta(Map<String, Object> observedBefore, Map<String, Object> observedAfter,
                              AgentActionStore.Verification verification,
                              long clientTick, long worldRevision) {
        public EffectDelta {
            observedBefore = Collections.unmodifiableMap(new LinkedHashMap<>(observedBefore));
            observedAfter = Collections.unmodifiableMap(new LinkedHashMap<>(observedAfter));
        }
    }
}
