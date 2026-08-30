package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.ApplyBlockPlanPort;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.KnownConstructionRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Sequential supervisor for an already-validated, bounded list of local construction phases. */
public final class KnownConstructionBatchAttempt implements AutoCloseable {
    public static final int MAX_PHASES = 32;
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_FOOTPRINT_AXIS = 9;

    private final ApplyBlockPlanPort port;
    private final List<KnownConstructionRequest> phases;
    private final long deadlineTick;
    private long lastTick;
    private int phaseIndex;
    private int placedEntries;
    private int completedPriorPhases;
    private int confirmedPriorPhases;
    private int completedEntries;
    private int serverConfirmedEntries;
    private boolean advancePending;
    private boolean closed;
    private KnownConstructionAttempt active;

    public KnownConstructionBatchAttempt(
            ApplyBlockPlanPort port,
            List<KnownConstructionRequest> phases,
            long admittedClientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.phases = validate(phases);
        if (admittedClientTick < 0L) {
            throw new IllegalArgumentException("admission tick must be non-negative");
        }
        int entries = this.phases.stream().mapToInt(phase -> phase.entries().size()).sum();
        deadlineTick = saturatedAdd(
                admittedClientTick,
                Math.multiplyExact(entries, (long) KnownConstructionAttempt.TICKS_PER_ENTRY));
        lastTick = admittedClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < lastTick) {
            return fail(
                    "construction_batch_non_monotonic_tick",
                    completedEntries,
                    serverConfirmedEntries);
        }
        lastTick = clientTick;
        if (clientTick >= deadlineTick) {
            return fail(
                    "construction_batch_deadline", completedEntries, serverConfirmedEntries);
        }
        if (advancePending) {
            phaseIndex++;
            advancePending = false;
        }
        if (active == null) {
            KnownConstructionRequest phase = phases.get(phaseIndex);
            long phaseDeadline = Math.min(
                    deadlineTick,
                    Math.min(
                            saturatedAdd(
                                    clientTick,
                                    Math.multiplyExact(
                                            phase.entries().size(),
                                            (long) KnownConstructionAttempt.TICKS_PER_ENTRY)),
                            phase.bounds().hardDeadlineClientTick(clientTick)));
            active = new KnownConstructionAttempt(port, phase, clientTick, phaseDeadline);
        }

        KnownConstructionAttempt.TickResult result = active.tick(clientTick);
        placedEntries = Math.addExact(placedEntries, result.placedDelta());
        completedEntries = Math.addExact(
                completedPriorPhases, result.completedEntries());
        serverConfirmedEntries = Math.addExact(
                confirmedPriorPhases, result.confirmedEntries());
        return switch (result.status()) {
            case RUNNING -> result(
                    Status.RUNNING, result.evidence(), completedEntries, serverConfirmedEntries);
            case FAILED -> fail(result.evidence(), completedEntries, serverConfirmedEntries);
            case SUCCEEDED -> completePhase(completedEntries, serverConfirmedEntries);
        };
    }

    private TickResult completePhase(int completed, int confirmed) {
        active = null; // KnownConstructionAttempt publishes success only after its complete release.
        completedPriorPhases = completed;
        confirmedPriorPhases = confirmed;
        if (phaseIndex == phases.size() - 1) {
            TickResult result = result(
                    Status.SUCCEEDED, "construction_batch_complete", completed, confirmed);
            closed = true;
            return result;
        }
        advancePending = true;
        return result(Status.RUNNING, "construction_phase_complete", completed, confirmed);
    }

    private TickResult fail(String evidence, int completed, int confirmed) {
        if (active != null) {
            active.close();
            active = null;
        }
        TickResult result = result(Status.FAILED, evidence, completed, confirmed);
        closed = true;
        return result;
    }

    private TickResult result(
            Status status, String evidence, int completed, int confirmed) {
        return new TickResult(
                status,
                evidence,
                placedEntries,
                completed,
                confirmed,
                phaseIndex + 1,
                phases.size());
    }

    @Override
    public void close() {
        if (closed) return;
        if (active != null) {
            active.close();
            active = null;
        }
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("construction batch attempt is closed");
    }

    private static List<KnownConstructionRequest> validate(
            List<KnownConstructionRequest> input) {
        Objects.requireNonNull(input, "phases");
        var phases = List.copyOf(input);
        if (phases.isEmpty() || phases.size() > MAX_PHASES) {
            throw new IllegalArgumentException("construction batch must contain 1..32 phases");
        }
        String dimension = null;
        int totalEntries = 0;
        BlockTarget minimum = null;
        BlockTarget maximum = null;
        var targets = new HashSet<BlockTarget>();
        for (var phase : phases) {
            Objects.requireNonNull(phase, "construction phase");
            if (phase.bounds().maxTravelBlocks() != 0 || phase.bounds().allowBreak()) {
                throw new IllegalArgumentException("construction batch phases must be stationary");
            }
            if (dimension == null) {
                dimension = phase.bounds().dimension();
            } else if (!dimension.equals(phase.bounds().dimension())) {
                throw new IllegalArgumentException("construction batch phases must share a dimension");
            }
            totalEntries = Math.addExact(totalEntries, phase.entries().size());
            if (totalEntries > MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "construction batch must contain at most 256 entries");
            }
            for (var entry : phase.entries()) {
                BlockTarget target = entry.target();
                if (!targets.add(target)) {
                    throw new IllegalArgumentException(
                            "construction batch targets must be globally unique");
                }
                minimum = minimum == null ? target : new BlockTarget(
                        target.dimension(),
                        Math.min(minimum.x(), target.x()),
                        Math.min(minimum.y(), target.y()),
                        Math.min(minimum.z(), target.z()));
                maximum = maximum == null ? target : new BlockTarget(
                        target.dimension(),
                        Math.max(maximum.x(), target.x()),
                        Math.max(maximum.y(), target.y()),
                        Math.max(maximum.z(), target.z()));
            }
        }
        if (axisSize(minimum.x(), maximum.x()) > MAX_FOOTPRINT_AXIS
                || axisSize(minimum.y(), maximum.y()) > MAX_FOOTPRINT_AXIS
                || axisSize(minimum.z(), maximum.z()) > MAX_FOOTPRINT_AXIS) {
            throw new IllegalArgumentException("construction batch footprint must fit within 9x9x9");
        }
        return phases;
    }

    private static long axisSize(int minimum, int maximum) {
        return (long) maximum - minimum + 1L;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public record TickResult(
            Status status,
            String evidence,
            int placedEntries,
            int completedEntries,
            int serverConfirmedEntries,
            int phaseIndex,
            int phaseCount) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidence, "evidence");
            if (placedEntries < 0
                    || completedEntries < 0
                    || completedEntries > MAX_ENTRIES
                    || serverConfirmedEntries < 0
                    || serverConfirmedEntries > completedEntries
                    || phaseIndex < 1
                    || phaseIndex > phaseCount
                    || phaseCount < 1
                    || phaseCount > MAX_PHASES) {
                throw new IllegalArgumentException("invalid construction batch progress");
            }
        }
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }
}
