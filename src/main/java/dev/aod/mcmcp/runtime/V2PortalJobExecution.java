package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Entry owns input only in the source world; arrival is a passive, bounded confirmation. */
final class V2PortalJobExecution implements V2JobExecution {
    enum Step { RUNNING, FAILED }
    interface Port extends AutoCloseable {
        void begin(long tick, BooleanSupplier allowed);
        Step tick(long tick, BooleanSupplier allowed);
        boolean contactObserved();
        boolean currentPositionSafe();
        void close();
    }
    private final AgentJobStore jobs;
    private final UUID id;
    private final UUID sourceSession;
    private final Object connection;
    private final V2PortalArguments request;
    private final Port port;
    private final BooleanSupplier release;
    private long deadline = Long.MIN_VALUE;
    private boolean begun;
    private boolean awaitingArrival;
    private boolean released;
    private boolean contact;
    private boolean arrived;
    private UUID arrivalSession;
    private String arrivalDimension;
    private AgentJobStore.State terminal;
    private String failure;

    V2PortalJobExecution(AgentJobStore jobs, UUID id, UUID session, Object connection,
            V2PortalArguments request, Port port, BooleanSupplier release) {
        this.jobs = Objects.requireNonNull(jobs);
        this.id = Objects.requireNonNull(id);
        this.sourceSession = Objects.requireNonNull(session);
        this.connection = Objects.requireNonNull(connection);
        this.request = Objects.requireNonNull(request);
        this.port = Objects.requireNonNull(port);
        this.release = Objects.requireNonNull(release);
        var job = jobs.get(id);
        if (job.kind() != AgentJobStore.Kind.INTERACT || !session.equals(job.worldSessionId()))
            throw new IllegalArgumentException("portal job does not match its source session");
    }

    boolean owns(UUID actionId) { return id.equals(actionId); }
    boolean currentPositionSafe() { return begun && !awaitingArrival && port.currentPositionSafe(); }

    @Override
    public AgentJobStore.Snapshot tick(UUID session, long tick, long now, boolean safe, BooleanSupplier allowed) {
        var job = jobs.get(id);
        if (job.state().terminal()) return job;
        if (terminal != null) return finish();
        if (job.cancelRequested()) return cancel();
        if (job.state() == AgentJobStore.State.UNCONFIRMED) {
            jobs.expireUnconfirmed(now);
            return jobs.get(id);
        }
        if (awaitingArrival || !sourceSession.equals(session) || !safe) return stop("safety_interrupted");
        if (job.state() == AgentJobStore.State.QUEUED) {
            jobs.start(id, session);
            deadline = now + request.maxTicks() * 50_000_000L;
        }
        if (expired(now) || !jobs.canDispatch(id, session)) return stop("duration_limit");
        if (!allowed.getAsBoolean()) return stop("dispatch_denied");
        try {
            if (!begun) {
                begun = true;
                port.begin(tick, allowed);
            }
            if (port.tick(tick, allowed) == Step.FAILED) return stop("portal_entry_unconfirmed");
            contact |= port.contactObserved();
            jobs.recordOperation(id);
            capture();
            return jobs.get(id);
        } catch (RuntimeException | LinkageError failure) {
            return stop("portal_entry_runtime_failed");
        }
    }

    /** Invoked before the usual world boundary cleanup. Failure keeps the normal stop path. */
    boolean prepareWorldBoundary(Object currentConnection, long now) {
        var job = jobs.get(id);
        if (terminal != null || !begun || job.state() != AgentJobStore.State.RUNNING
                || job.cancelRequested() || currentConnection != connection || expired(now)) return false;
        if (!port.contactObserved()) return false;
        contact = true;
        if (!releaseInput()) return false;
        awaitingArrival = true;
        capture();
        return true;
    }

    AgentJobStore.Snapshot pollArrival(UUID session, String dimension, Object currentConnection,
            boolean playerReady, boolean inputOwnerNone, long now) {
        var job = jobs.get(id);
        if (job.state().terminal()) return job;
        if (job.cancelRequested()) return cancel();
        if (terminal != null) return finish();
        if (!awaitingArrival || expired(now)) return stop("portal_arrival_timeout");
        if (currentConnection != connection) return stop("portal_connection_changed");
        if (!inputOwnerNone) { released = false; return stop("portal_arrival_input_owned"); }
        if (!playerReady || session == null || dimension == null) return job;
        arrivalSession = session;
        arrivalDimension = dimension;
        if (sourceSession.equals(session) || !request.expectedDimension().equals(dimension))
            return stop("portal_destination_mismatch");
        arrived = true;
        terminal = AgentJobStore.State.SUCCEEDED;
        return finish();
    }

    private boolean expired(long now) { return deadline != Long.MIN_VALUE && now - deadline >= 0; }

    @Override public AgentJobStore.Snapshot cancel() {
        if (jobs.get(id).state().terminal()) return jobs.get(id);
        jobs.requestCancel(id);
        terminal = AgentJobStore.State.CANCELLED;
        failure = "client_request";
        return finish();
    }

    @Override public AgentJobStore.Snapshot stop(String reason) {
        if (jobs.get(id).state().terminal()) return jobs.get(id);
        if (jobs.get(id).state() == AgentJobStore.State.UNCONFIRMED) {
            jobs.abandon(id);
            return jobs.get(id);
        }
        terminal = AgentJobStore.State.FAILED;
        failure = reason;
        return finish();
    }

    private boolean releaseInput() {
        boolean portReleased = false;
        try { port.close(); portReleased = true; }
        catch (RuntimeException | LinkageError ignored) { }
        boolean inputsReleased = false;
        try { inputsReleased = release.getAsBoolean(); }
        catch (RuntimeException | LinkageError ignored) { }
        released = portReleased && inputsReleased;
        return released;
    }

    private AgentJobStore.Snapshot finish() {
        if (released || releaseInput()) {
            if (jobs.get(id).cancelRequested()) {
                terminal = AgentJobStore.State.CANCELLED;
                failure = "client_request";
            }
            capture();
            jobs.finish(id, terminal, failure, true);
        } else capture();
        return jobs.get(id);
    }

    private void capture() {
        if (jobs.get(id).state() != AgentJobStore.State.RUNNING) return;
        var p = request.geometry().portal();
        var result = new LinkedHashMap<String, Object>();
        result.put("target", "portal");
        result.put("portal_block", "minecraft:end_portal");
        result.put("position", java.util.Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()));
        result.put("source_session_id", sourceSession.toString());
        result.put("source_dimension", request.sourceDimension());
        result.put("expected_dimension", request.expectedDimension());
        result.put("phase", terminal != null ? "terminal" : awaitingArrival ? "awaiting_arrival" : "entering");
        result.put("contact_observed", contact);
        result.put("arrival_confirmed", arrived);
        result.put("inputs_released", released);
        result.put("release_pending", terminal != null && !released);
        if (arrivalSession != null) result.put("arrival_session_id", arrivalSession.toString());
        if (arrivalDimension != null) result.put("arrival_dimension", arrivalDimension);
        jobs.recordResult(id, result);
    }
}
