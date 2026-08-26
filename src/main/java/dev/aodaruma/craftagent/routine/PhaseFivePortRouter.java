package dev.aodaruma.craftagent.routine;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Routes the six public Phase 5 kinds to the two concrete adapters. */
public final class PhaseFivePortRouter implements PhaseFivePort {
    private final PhaseFivePort inventory;
    private final PhaseFivePort world;
    private final Map<PhaseFiveAttempt, PhaseFivePort> attempts = new IdentityHashMap<>();

    public PhaseFivePortRouter(PhaseFivePort inventory, PhaseFivePort world) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public PhaseFiveFrame observe(PhaseFiveRequest request) {
        return delegate(request.kind()).observe(request);
    }

    @Override
    public PhaseFiveAttempt begin(
            UUID routineId, PhaseFiveRequest request, long hardDeadlineClientTick) {
        var delegate = delegate(request.kind());
        var attempt = delegate.begin(routineId, request, hardDeadlineClientTick);
        attempts.put(attempt, delegate);
        return attempt;
    }

    @Override
    public void maintain(PhaseFiveAttempt attempt) {
        delegate(attempt).maintain(attempt);
    }

    @Override
    public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
        return delegate(attempt).evidence(attempt);
    }

    @Override
    public void release(PhaseFiveAttempt attempt) {
        var delegate = attempts.remove(Objects.requireNonNull(attempt, "attempt"));
        if (delegate != null) {
            delegate.release(attempt);
        }
    }

    @Override
    public void retire(PhaseFiveRequest request) {
        delegate(request.kind()).retire(request);
    }

    /** Drops routing ownership after the concrete adapters have cleared their sessions. */
    public void clearSession() {
        attempts.clear();
    }

    private PhaseFivePort delegate(String kind) {
        return switch (kind) {
            case "craft_items", "transfer_items" -> inventory;
            case "tend_crop_area", "harvest_tree_area", "sleep_at_bed", "survey_area" -> world;
            default -> throw new IllegalArgumentException("unsupported Phase 5 routine kind");
        };
    }

    private PhaseFivePort delegate(PhaseFiveAttempt attempt) {
        var delegate = attempts.get(Objects.requireNonNull(attempt, "attempt"));
        if (delegate == null) {
            throw new IllegalStateException("Phase 5 attempt is not owned by this router");
        }
        return delegate;
    }
}
