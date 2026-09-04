package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.ApplyBlockPlanActionAttempt;
import dev.aod.mcmcp.routine.ApplyBlockPlanChildAction;
import dev.aod.mcmcp.routine.ApplyBlockPlanPort;
import dev.aod.mcmcp.routine.ApplyBlockPlanPreparationAttempt;
import dev.aod.mcmcp.routine.ConstructionSafetyChangedException;
import dev.aod.mcmcp.routine.KnownConstructionRequest;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.SafeConstructionBlockPolicy;
import dev.aod.mcmcp.routine.SafePlacementSupportPolicy;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tick-driven internal executor for one ordered, stationary construction suffix.
 *
 * <p>It performs a whole-plan resource preflight before the first adapter preparation.  Every
 * failure closes the active child and retires the plan, so no later suffix entry can dispatch.</p>
 */
public final class KnownConstructionAttempt implements AutoCloseable {
    public static final int TICKS_PER_ENTRY = 300;

    private final ApplyBlockPlanPort port;
    private final KnownConstructionRequest request;
    private final long deadlineTick;
    private final AdapterFailureSink adapterFailureSink;
    private final boolean[] completed;
    private Phase phase = Phase.PREFLIGHT;
    private ApplyBlockPlanChildAction child;
    private ApplyBlockPlanPreparationAttempt preparation;
    private ApplyBlockPlanActionAttempt action;
    private int currentIndex = -1;
    private int confirmedEntries;
    private long lastTick;
    private long finalVerificationAfterTick = -1L;
    private long finalVerificationAfterRevision;
    private boolean closed;
    private final ArrayList<EffectDelta> pendingEffects = new ArrayList<>(2);
    private boolean currentActionConfirmed;
    private boolean currentUnknownRecorded;

    public KnownConstructionAttempt(
            ApplyBlockPlanPort port,
            KnownConstructionRequest request,
            long admittedClientTick,
            long deadlineTick) {
        this(port, request, admittedClientTick, deadlineTick, AdapterFailureSink.noop());
    }

    public KnownConstructionAttempt(
            ApplyBlockPlanPort port,
            KnownConstructionRequest request,
            long admittedClientTick,
            long deadlineTick,
            AdapterFailureSink adapterFailureSink) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        this.adapterFailureSink = Objects.requireNonNull(
                adapterFailureSink, "adapterFailureSink");
        if (admittedClientTick < 0L || deadlineTick <= admittedClientTick) {
            throw new IllegalArgumentException("deadline must follow a non-negative admission tick");
        }
        long nodeBudget = saturatedAdd(
                admittedClientTick,
                Math.multiplyExact(request.entries().size(), (long) TICKS_PER_ENTRY));
        long boundsDeadline = request.bounds().hardDeadlineClientTick(admittedClientTick);
        if (deadlineTick > nodeBudget || deadlineTick > boundsDeadline) {
            throw new IllegalArgumentException(
                    "construction deadline exceeds entries*300 ticks or request bounds");
        }
        request.plan().validateAdmissionTick(admittedClientTick);
        this.deadlineTick = deadlineTick;
        this.completed = new boolean[request.entries().size()];
        this.lastTick = admittedClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < lastTick) {
            return fail("construction_non_monotonic_tick");
        }
        lastTick = clientTick;
        if (clientTick >= deadlineTick) {
            return fail("construction_deadline");
        }
        try {
            return switch (phase) {
                case PREFLIGHT -> preflight(clientTick);
                case PREPARING -> prepare();
                case CONFIRMING -> confirm();
                case RELEASING_CONFIRMED -> releaseConfirmed();
                case FINAL_VERIFY -> finalVerify(clientTick);
            };
        } catch (ConstructionSafetyChangedException changed) {
            return fail("construction_safety_changed");
        } catch (SafeConstructionBlockPolicy.UnsafeConstructionBlockException
                | SafePlacementSupportPolicy.UnsafePlacementSupportException rejected) {
            return fail("construction_precondition_changed");
        } catch (RuntimeException | LinkageError adapterFailure) {
            return fail("construction_adapter_failed");
        }
    }

    private TickResult preflight(long clientTick) {
        var frame = Objects.requireNonNull(
                invokeAdapter(AdapterCall.PREFLIGHT_OBSERVE,
                        () -> port.observe(request.plan())),
                "adapter returned no construction frame");
        if (frame.clientTick() != clientTick) {
            return fail("construction_adapter_contract");
        }
        if (!frame.universalSafetyClear()) {
            return fail("construction_safety_changed");
        }

        var required = new LinkedHashMap<String, Integer>();
        int next = -1;
        for (int index = 0; index < request.entries().size(); index++) {
            var step = request.entries().get(index);
            // A server-confirmed completed entry may become visually occluded by its successors.
            // Its authoritative confirmation remains sufficient; a later dependency still gets
            // a packet-adjacent exact support check in the adapter.
            if (completed[index]) {
                continue;
            }
            var cell = frame.cells().get(step.target());
            if (cell == null || cell.liveState().isEmpty()) {
                return fail("construction_observation_unknown");
            }
            var live = cell.liveState().orElseThrow();
            if (step.expectedAfter().equals(live)) {
                completed[index] = true;
                continue;
            }
            if (!step.expectedBefore().equals(live)) {
                return fail("construction_precondition_changed");
            }
            if ((!request.breakOnly() && !cell.replaceable()) || !cell.safeStandAvailable()) {
                return fail("construction_step_unpreparable");
            }
            // Dependency support for a later entry intentionally has no aim candidate until its
            // earlier entry has been confirmed. Only the entry selected for this dispatch is
            // JIT-aimed; suffix state/resources are still checked before the first packet.
            if (next < 0) {
                next = index;
                if (!cell.aimFeasible()) {
                    return fail("construction_step_unpreparable");
                }
            }
            step.requiredItemId().ifPresent(item -> required.merge(item, 1, Integer::sum));
        }

        if (next < 0) {
            finalVerificationAfterTick = frame.clientTick();
            finalVerificationAfterRevision = frame.observationRevision();
            phase = Phase.FINAL_VERIFY;
            return result(Status.RUNNING, "construction_final_verifying");
        }
        if (!request.breakOnly() && !frame.inventoryServerSynchronized()) {
            return fail("construction_inventory_unsynchronized");
        }
        for (var entry : required.entrySet()) {
            if (frame.inventoryCounts().getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return fail("construction_insufficient_resources");
            }
            // MinecraftApplyBlockPlanPort uses this existing frame field for all inventory slots
            // which its bounded SWAP stager can make selectable.
            if (!frame.hotbarItemIds().contains(entry.getKey())) {
                return fail("construction_item_unstageable");
            }
        }

        currentIndex = next;
        child = ApplyBlockPlanChildAction.first(next, request.entries().get(next));
        preparation = Objects.requireNonNull(
                invokeAdapter(AdapterCall.BEGIN_PREPARATION,
                        () -> port.beginPreparation(request.plan(), child, deadlineTick)),
                "adapter returned no construction preparation");
        if (preparation.stepIndex() != next
                || preparation.issuedClientTick() != clientTick
                || preparation.leaseExpiresAtClientTick() != deadlineTick) {
            return fail("construction_adapter_contract");
        }
        phase = Phase.PREPARING;
        return result(Status.RUNNING, "construction_preparing");
    }

    private TickResult prepare() {
        var evidence = Objects.requireNonNull(
                invokeAdapter(AdapterCall.PREPARATION_EVIDENCE,
                        () -> port.preparationEvidence(preparation)),
                "adapter returned no construction preparation evidence");
        if (!preparation.attemptId().equals(evidence.attemptId())) {
            return fail("construction_adapter_contract");
        }
        if (evidence.failure() != null) {
            return fail(failureCode(evidence.failure()));
        }
        if (!evidence.prepared()) {
            invokeAdapter(AdapterCall.MAINTAIN_PREPARATION,
                    () -> port.maintainPreparation(preparation));
            return result(Status.RUNNING, "construction_preparing");
        }
        if (evidence.liveState().filter(child.expectedBefore()::equals).isEmpty()
                || (!request.breakOnly() && !evidence.targetReplaceable())) {
            return fail("construction_precondition_changed");
        }
        action = Objects.requireNonNull(
                invokeAdapter(AdapterCall.DISPATCH_PREPARED,
                        () -> port.dispatchPrepared(
                                request.plan(), child, preparation, deadlineTick)),
                "adapter returned no construction action");
        if (action.stepIndex() != currentIndex
                || action.leaseExpiresAtClientTick() != deadlineTick) {
            return fail("construction_adapter_contract");
        }
        currentActionConfirmed = false;
        currentUnknownRecorded = false;
        releasePreparationStrict();
        phase = Phase.CONFIRMING;
        return result(Status.RUNNING, "construction_confirming");
    }

    private TickResult confirm() {
        var evidence = Objects.requireNonNull(
                invokeAdapter(AdapterCall.ACTION_EVIDENCE,
                        () -> port.actionEvidence(action)),
                "adapter returned no construction evidence");
        if (!action.attemptId().equals(evidence.attemptId())) {
            return fail("construction_adapter_contract");
        }
        if (evidence.failure() != null) {
            return fail(failureCode(evidence.failure()));
        }
        if (evidence.acknowledged()
                && evidence.worldDiffObserved()
                && evidence.liveStateAfter().filter(after -> request.breakOnly()
                        ? child.expectedAfter().equals(after)
                        : SafeConstructionBlockPolicy
                                .placementStateMatchesFinalStableProperties(
                                        child.expectedAfter(), after)).isPresent()) {
            var liveAfter = evidence.liveStateAfter().orElseThrow();
            int affectedBlocks = request.breakOnly()
                    ? 1 : request.placementCellCount(currentIndex);
            pendingEffects.add(new EffectDelta(
                    request.breakOnly() ? "block_break" : "block_place",
                    blockSubject(child.target()),
                    blockObservation(child.expectedBefore(), affectedBlocks),
                    blockObservation(liveAfter, affectedBlocks),
                    AgentActionStore.Verification.CONFIRMED,
                    evidence.clientTick(),
                    evidence.observationRevision()));
            currentActionConfirmed = true;
            completed[currentIndex] = true;
            confirmedEntries++;
            phase = Phase.RELEASING_CONFIRMED;
            return releaseConfirmed();
        }
        invokeAdapter(AdapterCall.MAINTAIN_ACTION, () -> port.maintainAction(action));
        return result(Status.RUNNING, "construction_confirming");
    }

    private TickResult releaseConfirmed() {
        try {
            releaseActionStrict();
        } catch (RuntimeException | LinkageError releasePending) {
            // Keep the exact same action reachable and retry only its idempotent release on the
            // next tick.  A confirmed placement is not surfaced as a delta while input ownership
            // may still be active.
            return result(Status.RUNNING, "construction_releasing");
        }
        int confirmedDelta = request.breakOnly() ? 1 : request.placementCellCount(currentIndex);
        currentIndex = -1;
        child = null;
        currentActionConfirmed = false;
        currentUnknownRecorded = false;
        phase = Phase.PREFLIGHT;
        return result(Status.RUNNING, "construction_entry_confirmed", confirmedDelta);
    }

    private TickResult finalVerify(long clientTick) {
        var frame = Objects.requireNonNull(
                invokeAdapter(AdapterCall.FINAL_OBSERVE,
                        () -> port.observe(request.plan())),
                "adapter returned no construction frame");
        if (frame.clientTick() != clientTick
                || frame.observationRevision() < finalVerificationAfterRevision) {
            return fail("construction_adapter_contract");
        }
        if (!frame.universalSafetyClear()) {
            return fail("construction_safety_changed");
        }
        if (frame.clientTick() <= finalVerificationAfterTick) {
            return result(Status.RUNNING, "construction_final_verifying");
        }
        for (var step : request.entries()) {
            var cell = frame.cells().get(step.target());
            if (cell == null || cell.liveState().isEmpty()) {
                return fail("construction_observation_unknown");
            }
            if (!step.expectedAfter().equals(cell.liveState().orElseThrow())) {
                return fail("construction_precondition_changed");
            }
        }
        TickResult result = result(Status.SUCCEEDED, "construction_complete");
        close();
        return result;
    }

    private TickResult fail(String evidence) {
        close();
        return result(Status.FAILED, evidence);
    }

    private TickResult result(Status status, String evidence) {
        return result(status, evidence, 0);
    }

    private TickResult result(Status status, String evidence, int confirmedDelta) {
        return new TickResult(
                status,
                Objects.requireNonNull(evidence, "evidence"),
                request.breakOnly() ? 0 : confirmedDelta,
                request.breakOnly() ? confirmedDelta : 0,
                completedCount(),
                confirmedEntries,
                drainEffectDeltas());
    }

    public List<EffectDelta> drainEffectDeltas() {
        if (pendingEffects.isEmpty()) return List.of();
        List<EffectDelta> drained = List.copyOf(pendingEffects);
        pendingEffects.clear();
        return drained;
    }

    private int completedCount() {
        int count = 0;
        for (boolean value : completed) if (value) count++;
        return count;
    }

    private static String failureCode(RoutineFailure failure) {
        return switch (failure.category()) {
            case SAFETY -> "construction_safety_changed";
            case PRECONDITION -> "construction_precondition_changed";
            case DIVERGENCE -> "construction_world_diverged";
            case TRANSIENT -> "construction_transient_failure";
            case EXTERNAL -> "construction_adapter_failed";
        };
    }

    @Override
    public void close() {
        if (closed) return;
        recordUnknownDispatchedEffect();
        releaseActionStrict();
        releasePreparationStrict();
        invokeAdapter(AdapterCall.RETIRE_PLAN, () -> port.retire(request.plan()));
        closed = true;
    }

    private void recordUnknownDispatchedEffect() {
        if (action == null || child == null || currentActionConfirmed || currentUnknownRecorded) {
            return;
        }
        int affectedBlocks = request.breakOnly()
                ? 1 : request.placementCellCount(currentIndex);
        pendingEffects.add(new EffectDelta(
                request.breakOnly() ? "block_break" : "block_place",
                blockSubject(child.target()),
                blockObservation(child.expectedBefore(), affectedBlocks),
                Map.of(),
                AgentActionStore.Verification.UNKNOWN,
                Math.max(lastTick, action.issuedClientTick()),
                action.issuedObservationRevision()));
        currentUnknownRecorded = true;
    }

    private static String blockSubject(dev.aod.mcmcp.routine.BlockTarget target) {
        return "block:" + target.dimension() + ":"
                + target.x() + "," + target.y() + "," + target.z();
    }

    private static Map<String, Object> blockObservation(
            dev.aod.mcmcp.routine.BlockStateFingerprint state, int affectedBlocks) {
        var observation = new LinkedHashMap<String, Object>();
        observation.put("block", state.blockId());
        observation.put("properties", state.properties());
        observation.put("affected_blocks", affectedBlocks);
        return observation;
    }

    private void releasePreparationStrict() {
        if (preparation == null) return;
        invokeAdapter(AdapterCall.RELEASE_PREPARATION,
                () -> port.releasePreparation(preparation));
        preparation = null;
    }

    private void releaseActionStrict() {
        if (action == null) return;
        invokeAdapter(AdapterCall.RELEASE_ACTION, () -> port.releaseAction(action));
        action = null;
    }

    private <T> T invokeAdapter(AdapterCall call, Supplier<T> invocation) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(invocation, "invocation");
        try {
            return invocation.get();
        } catch (RuntimeException | LinkageError failure) {
            reportAdapterFailure(call, failure);
            throw failure;
        }
    }

    private void invokeAdapter(AdapterCall call, Runnable invocation) {
        invokeAdapter(call, () -> {
            invocation.run();
            return null;
        });
    }

    private void reportAdapterFailure(AdapterCall call, Throwable failure) {
        try {
            adapterFailureSink.onFailure(call, currentIndex, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never change the public fail-closed result or cleanup path.
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("construction attempt is closed");
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private enum Phase {
        PREFLIGHT, PREPARING, CONFIRMING, RELEASING_CONFIRMED, FINAL_VERIFY
    }

    public enum AdapterCall {
        PREFLIGHT_OBSERVE,
        BEGIN_PREPARATION,
        PREPARATION_EVIDENCE,
        MAINTAIN_PREPARATION,
        DISPATCH_PREPARED,
        ACTION_EVIDENCE,
        MAINTAIN_ACTION,
        FINAL_OBSERVE,
        RELEASE_PREPARATION,
        RELEASE_ACTION,
        RETIRE_PLAN
    }

    @FunctionalInterface
    public interface AdapterFailureSink {
        void onFailure(AdapterCall call, int stepIndex, Throwable failure);

        static AdapterFailureSink noop() {
            return (call, stepIndex, failure) -> { };
        }
    }

    public record TickResult(
            Status status,
            String evidence,
            int placedDelta,
            int brokenDelta,
            int completedEntries,
            int confirmedEntries,
            List<EffectDelta> effects) {
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidence, "evidence");
            if (placedDelta < 0 || placedDelta > 2
                    || brokenDelta < 0 || brokenDelta > 1
                    || placedDelta + brokenDelta > 2
                    || completedEntries < 0
                    || completedEntries > KnownConstructionRequest.MAX_ENTRIES
                    || confirmedEntries < 0
                    || confirmedEntries > KnownConstructionRequest.MAX_ENTRIES
                    || confirmedEntries > completedEntries) {
                throw new IllegalArgumentException("invalid construction progress counters");
            }
            effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
            if (effects.size() > 2) {
                throw new IllegalArgumentException("too many construction effect deltas");
            }
        }
    }

    public record EffectDelta(
            String kind,
            String subject,
            Map<String, Object> observedBefore,
            Map<String, Object> observedAfter,
            AgentActionStore.Verification verification,
            long clientTick,
            long worldRevision) {
        public EffectDelta {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
            observedBefore = Map.copyOf(Objects.requireNonNull(observedBefore, "observedBefore"));
            observedAfter = Map.copyOf(Objects.requireNonNull(observedAfter, "observedAfter"));
            Objects.requireNonNull(verification, "verification");
            if (clientTick < 0 || worldRevision < 0) {
                throw new IllegalArgumentException("effect clocks must be non-negative");
            }
        }
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }
}
