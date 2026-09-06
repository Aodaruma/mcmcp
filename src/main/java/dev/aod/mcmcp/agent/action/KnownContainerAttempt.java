package dev.aod.mcmcp.agent.action;

import dev.aod.mcmcp.routine.PhaseFiveAttempt;
import dev.aod.mcmcp.routine.PhaseFiveEvidence;
import dev.aod.mcmcp.routine.PhaseFivePort;
import dev.aod.mcmcp.routine.PhaseFiveRequest;
import dev.aod.mcmcp.routine.RoutineFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Drives one bounded container operation without admitting a second public routine. */
public final class KnownContainerAttempt implements AutoCloseable {
    private final PhaseFivePort port;
    private final PhaseFiveRequest request;
    private final UUID attemptId = UUID.randomUUID();
    private final long admittedClientTick;
    private final long deadlineClientTick;
    private PhaseFiveAttempt attempt;
    private long lastObservationRevision;
    private int recordedInteractions;
    private List<ItemCount> pendingSuccessItems;
    private ContainerInspection.Contents pendingInspection;
    private boolean successReleased;
    private boolean closed;
    private final ArrayList<EffectDelta> pendingEffects = new ArrayList<>(2);
    private boolean transferConfirmed;
    private boolean unknownTransferRecorded;
    private boolean potentialTransferDispatched;
    private int latestSourceBefore = -1;
    private int latestDestinationBefore = -1;
    private int latestSourceAfter = -1;
    private int latestDestinationAfter = -1;
    private boolean batchEvidence;
    private boolean transferInFlight;
    private int confirmedPrefix;
    private int confirmedSourceCount = -1;
    private int confirmedDestinationCount = -1;
    private int pendingSourceBefore = -1;
    private int pendingDestinationBefore = -1;
    private long latestEffectClientTick;
    private long latestEffectWorldRevision;

    public KnownContainerAttempt(
            PhaseFivePort port,
            PhaseFiveRequest request,
            long admittedClientTick,
            long deadlineClientTick) {
        this.port = Objects.requireNonNull(port, "port");
        this.request = Objects.requireNonNull(request, "request");
        request.validateAdmissionTick(admittedClientTick);
        if (deadlineClientTick <= admittedClientTick) {
            throw new IllegalArgumentException("deadline must follow admission");
        }
        this.admittedClientTick = admittedClientTick;
        this.deadlineClientTick = deadlineClientTick;
    }

    public TickResult tick(long clientTick) {
        requireOpen();
        if (clientTick < admittedClientTick) {
            throw new IllegalArgumentException("client tick moved before admission");
        }
        if (pendingSuccessItems != null) {
            return releasePendingSuccess(captureInteractionDeltaStrict(clientTick));
        }
        if (clientTick >= deadlineClientTick) {
            return fail("container_deadline", captureInteractionDelta(clientTick));
        }
        var frame = Objects.requireNonNull(port.observe(request), "adapter returned no frame");
        if (frame.clientTick() != clientTick
                || frame.observationRevision() < lastObservationRevision) {
            return fail("container_observation_contract", 0);
        }
        lastObservationRevision = frame.observationRevision();
        if (frame.failure() != null) {
            return fail(frame.failure(),
                    captureInteractionDelta(clientTick));
        }

        if (attempt == null) {
            attempt = Objects.requireNonNull(
                    port.begin(attemptId, request, deadlineClientTick),
                    "adapter returned no attempt");
            if (!attemptId.equals(attempt.attemptId())
                    || !request.kind().equals(attempt.kind())
                    || attempt.issuedClientTick() != clientTick
                    || attempt.issuedObservationRevision() < frame.observationRevision()
                    || attempt.hardDeadlineClientTick() != deadlineClientTick) {
                return fail("container_begin_contract", 0);
            }
            return running(0);
        }

        PhaseFiveEvidence evidence = Objects.requireNonNull(
                port.evidence(attempt), "adapter returned no evidence");
        if (!attemptId.equals(evidence.attemptId())
                || evidence.clientTick() < attempt.issuedClientTick()
                || evidence.clientTick() > clientTick
                || evidence.observationRevision() < lastObservationRevision) {
            return fail("container_evidence_contract", 0);
        }
        lastObservationRevision = evidence.observationRevision();
        captureTransferEvidence(
                evidence.clientTick(), evidence.observationRevision(), evidence.basis());
        final int delta;
        try {
            delta = recordInteractions(evidence.basis());
        } catch (IllegalStateException invalidUsage) {
            return fail("container_interaction_contract", 0);
        }

        return switch (evidence) {
            case PhaseFiveEvidence.Pending ignored -> {
                port.maintain(attempt);
                yield running(delta);
            }
            case PhaseFiveEvidence.ServerConfirmed confirmed -> {
                if (!confirmed.result().goalVerified()
                        || confirmed.result().verifiedUnits() < request.expectedUnits()) {
                    yield fail("container_postcondition_unconfirmed", delta);
                }
                try {
                    recordConfirmedTransfer(
                            confirmed.clientTick(), confirmed.observationRevision(),
                            confirmed.result().basis());
                } catch (IllegalStateException invalidEffect) {
                    yield fail("container_effect_contract", delta);
                }
                pendingSuccessItems = itemCounts(confirmed.result().basis());
                Map<String, Object> basis = confirmed.result().basis();
                if (Boolean.TRUE.equals(basis.get("complete_container_inspection"))) {
                    if (!Boolean.FALSE.equals(basis.get("available_source_items_truncated"))) {
                        throw new IllegalStateException("complete container contents were truncated");
                    }
                    pendingInspection = new ContainerInspection.Contents(
                            UUID.fromString((String) basis.get("contents_world_session_id")),
                            ((Number) basis.get("contents_observed_tick")).longValue(),
                            ((Number) basis.get("contents_packet_revision")).longValue(),
                            pendingSuccessItems);
                }
                yield releasePendingSuccess(delta);
            }
            case PhaseFiveEvidence.Inconclusive inconclusive -> new TickResult(
                    Status.FAILED, "container_" + inconclusive.certainty().name().toLowerCase(Locale.ROOT),
                    delta, List.of(), drainEffectDeltas(),
                    "transfer_readback_did_not_confirm_exact_full_stack_move".equals(inconclusive.reason())
                            ? List.of("container_transfer_readback_mismatch") : List.of());
            case PhaseFiveEvidence.Failed failed ->
                    fail(failed.failure(), delta);
        };
    }

    private TickResult releasePendingSuccess(int interactionDelta) {
        List<ItemCount> items = pendingSuccessItems;
        try {
            if (attempt != null) port.release(attempt);
            int releaseDelta = attempt == null
                    ? 0 : captureInteractionDeltaStrict(Long.MAX_VALUE);
            port.retire(request);
            closed = true;
            successReleased = true;
            return succeeded(Math.addExact(interactionDelta, releaseDelta), items);
        } catch (RuntimeException | LinkageError releaseFailure) {
            if (releaseStatus() == ReleaseStatus.PROGRESSING) {
                return running(interactionDelta);
            }
            throw releaseFailure;
        }
    }

    private TickResult fail(String evidence, int interactionDelta) {
        // The runtime retains this terminal intent until its shared release fence confirms cleanup.
        return failed(evidence, interactionDelta);
    }

    private TickResult fail(RoutineFailure failure, int interactionDelta) {
        List<String> diagnostics = List.of();
        if ("INVENTORY_SAFE_OPEN_HAND_REQUIRED".equals(failure.code())
                || "INVENTORY_SAFE_OPEN_HAND_UNAVAILABLE".equals(failure.code())) {
            diagnostics = List.of(
                    "safe_open_hand=no_side_effect_free_hotbar_or_offhand_item",
                    "remedy=prepare_empty_or_safe_offhand_or_plain_material_or_safe_mining_tool");
        } else if ("CONTAINER_AIM_OCCLUDED".equals(failure.code())
                && failure.observed().get("crosshair") instanceof String kind) {
            diagnostics = switch (kind) {
                case "entity" -> List.of("container_crosshair=entity");
                case "block_other" -> List.of("container_crosshair=block_other");
                case "miss" -> List.of("container_crosshair=miss");
                case "unavailable" -> List.of("container_crosshair=unavailable");
                case "world_border" -> List.of("container_crosshair=world_border");
                default -> List.of();
            };
        }
        return new TickResult(Status.FAILED, failure.code().toLowerCase(Locale.ROOT),
                interactionDelta, List.of(), drainEffectDeltas(), diagnostics);
    }

    private int recordInteractions(Map<String, Object> basis) {
        int interactions = interactionCount(basis);
        if (interactions < recordedInteractions) {
            throw new IllegalStateException("container interaction contract changed");
        }
        int delta = interactions - recordedInteractions;
        recordedInteractions = interactions;
        return delta;
    }

    private int captureInteractionDelta(long maximumClientTick) {
        if (attempt == null) return 0;
        try {
            return captureInteractionDeltaStrict(maximumClientTick);
        } catch (RuntimeException | LinkageError invalidEvidence) {
            return 0;
        }
    }

    private int captureInteractionDeltaStrict(long maximumClientTick) {
        if (attempt == null) return 0;
        PhaseFiveEvidence evidence = Objects.requireNonNull(
                port.evidence(attempt), "adapter returned no evidence");
        if (!attemptId.equals(evidence.attemptId())
                || evidence.clientTick() < attempt.issuedClientTick()
                || evidence.clientTick() > maximumClientTick
                || evidence.observationRevision() < lastObservationRevision) {
            throw new IllegalStateException("container release evidence contract changed");
        }
        lastObservationRevision = evidence.observationRevision();
        captureTransferEvidence(
                evidence.clientTick(), evidence.observationRevision(), evidence.basis());
        return recordInteractions(evidence.basis());
    }

    /** Drains recipe/cursor cleanup usage while the first terminal intent remains retained. */
    public int drainReleaseInteractionDelta() {
        return closed ? 0 : captureInteractionDelta(Long.MAX_VALUE);
    }

    public ContainerInspection.Contents inspectionContents() {
        if (!successReleased || pendingInspection == null) {
            throw new IllegalStateException("complete container inspection is not released");
        }
        return pendingInspection;
    }

    public ReleaseStatus releaseStatus() {
        if (closed) return ReleaseStatus.CONFIRMED;
        if (attempt == null) return ReleaseStatus.ACTIVE;
        try {
            Map<String, Object> basis = port.evidence(attempt).basis();
            if (Boolean.TRUE.equals(basis.get("release_fault"))) return ReleaseStatus.FAULT;
            if (Boolean.TRUE.equals(basis.get("release_confirmed"))) {
                return ReleaseStatus.CONFIRMED;
            }
            return Boolean.TRUE.equals(basis.get("release_pending"))
                    ? ReleaseStatus.PROGRESSING : ReleaseStatus.ACTIVE;
        } catch (RuntimeException | LinkageError invalidEvidence) {
            return ReleaseStatus.FAULT;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        captureFinalTransferEvidence();
        recordConfirmedPrefix();
        recordUnknownTransfer();
        if (attempt != null) port.release(attempt);
        port.retire(request);
        closed = true;
    }

    public List<EffectDelta> drainEffectDeltas() {
        if (pendingEffects.isEmpty()) return List.of();
        List<EffectDelta> drained = List.copyOf(pendingEffects);
        pendingEffects.clear();
        return drained;
    }

    private TickResult running(int delta) {
        return new TickResult(Status.RUNNING, null, delta, List.of(), drainEffectDeltas());
    }

    private TickResult succeeded(int delta, List<ItemCount> items) {
        return new TickResult(Status.SUCCEEDED, null, delta, items, drainEffectDeltas());
    }

    private TickResult failed(String evidence, int delta) {
        return new TickResult(
                Status.FAILED, Objects.requireNonNull(evidence, "evidence"),
                delta, List.of(), drainEffectDeltas());
    }

    private void captureFinalTransferEvidence() {
        if (attempt == null || !"transfer_items".equals(request.kind())) return;
        try {
            PhaseFiveEvidence evidence = Objects.requireNonNull(port.evidence(attempt));
            captureTransferEvidence(
                    evidence.clientTick(), evidence.observationRevision(), evidence.basis());
            if (evidence instanceof PhaseFiveEvidence.ServerConfirmed confirmed
                    && confirmed.result().goalVerified()
                    && confirmed.result().verifiedUnits() >= request.expectedUnits()) {
                recordConfirmedTransfer(
                        confirmed.clientTick(), confirmed.observationRevision(),
                        confirmed.result().basis());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Unknown means exactly that the post-dispatch state could not be read safely.
        }
    }

    private void captureTransferEvidence(
            long clientTick, long worldRevision, Map<String, Object> basis) {
        if (!"transfer_items".equals(request.kind())) return;
        int clicks = nonNegativeInt(basis.get("container_clicks"));
        if (clicks > 0) potentialTransferDispatched = true;
        latestSourceBefore = optionalNonNegativeInt(basis.get("source_before"), latestSourceBefore);
        latestDestinationBefore = optionalNonNegativeInt(
                basis.get("destination_before"), latestDestinationBefore);
        // Only a validated same-target full-content readback can supply after counts.
        // In particular, default zeros and local click prediction are not observations.
        boolean readbackObserved = Boolean.TRUE.equals(basis.get("transfer_readback_observed"));
        latestSourceAfter = readbackObserved
                ? requiredNonNegativeInt(basis, "source_after") : -1;
        latestDestinationAfter = readbackObserved
                ? requiredNonNegativeInt(basis, "destination_after") : -1;
        if (basis.containsKey("confirmed_transfer_count")) {
            batchEvidence = true;
            int prefix = requiredNonNegativeInt(basis, "confirmed_transfer_count");
            if (prefix < confirmedPrefix || prefix > 896) {
                throw new IllegalStateException("container confirmed prefix changed");
            }
            if (prefix > 0) {
                int source = requiredNonNegativeInt(basis, "confirmed_source_count");
                int destination = requiredNonNegativeInt(basis, "confirmed_destination_count");
                if (latestSourceBefore - source != prefix
                        || destination - latestDestinationBefore != prefix) {
                    throw new IllegalStateException("container confirmed prefix is inconsistent");
                }
                confirmedSourceCount = source;
                confirmedDestinationCount = destination;
            }
            confirmedPrefix = prefix;
            transferInFlight = Boolean.TRUE.equals(basis.get("transfer_in_flight"));
            if (transferInFlight) {
                pendingSourceBefore = requiredNonNegativeInt(basis, "pending_source_before");
                pendingDestinationBefore = requiredNonNegativeInt(basis, "pending_destination_before");
            }
        }
        latestEffectClientTick = clientTick;
        latestEffectWorldRevision = worldRevision;
    }

    private void recordConfirmedTransfer(
            long clientTick, long worldRevision, Map<String, Object> basis) {
        if (!"transfer_items".equals(request.kind()) || transferConfirmed) return;
        if (!basis.containsKey("transferred") && !potentialTransferDispatched) {
            transferConfirmed = true;
            return;
        }
        int transferred = requiredNonNegativeInt(basis, "transferred");
        if (transferred == 0) {
            transferConfirmed = true;
            return;
        }
        int sourceBefore = requiredNonNegativeInt(basis, "source_count_before");
        int sourceAfter = requiredNonNegativeInt(basis, "source_count_after");
        int destinationBefore = requiredNonNegativeInt(basis, "destination_count_before");
        int destinationAfter = requiredNonNegativeInt(basis, "destination_count_after");
        if (transferred > 896 || sourceBefore - sourceAfter != transferred
                || destinationAfter - destinationBefore != transferred) {
            throw new IllegalStateException("confirmed transfer counts are inconsistent");
        }
        pendingEffects.add(new EffectDelta(
                Map.of("source_count", sourceBefore, "destination_count", destinationBefore),
                Map.of("source_count", sourceAfter, "destination_count", destinationAfter,
                        "transferred", transferred),
                AgentActionStore.Verification.CONFIRMED,
                clientTick,
                worldRevision));
        transferConfirmed = true;
    }

    private void recordConfirmedPrefix() {
        if (transferConfirmed || confirmedPrefix == 0) return;
        pendingEffects.add(new EffectDelta(
                Map.of("source_count", latestSourceBefore,
                        "destination_count", latestDestinationBefore),
                Map.of("source_count", confirmedSourceCount,
                        "destination_count", confirmedDestinationCount,
                        "transferred", confirmedPrefix),
                AgentActionStore.Verification.CONFIRMED,
                latestEffectClientTick, latestEffectWorldRevision));
        // Record the prefix only once, including when release needs another close attempt.
        transferConfirmed = true;
    }

    private void recordUnknownTransfer() {
        if (!"transfer_items".equals(request.kind())
                || !potentialTransferDispatched || unknownTransferRecorded
                || (batchEvidence ? !transferInFlight : transferConfirmed)) {
            return;
        }
        var before = new java.util.LinkedHashMap<String, Object>();
        int sourceBefore = batchEvidence ? pendingSourceBefore : latestSourceBefore;
        int destinationBefore = batchEvidence ? pendingDestinationBefore : latestDestinationBefore;
        if (sourceBefore >= 0) before.put("source_count", sourceBefore);
        if (destinationBefore >= 0) {
            before.put("destination_count", destinationBefore);
        }
        var after = new java.util.LinkedHashMap<String, Object>();
        if (latestSourceAfter >= 0) after.put("source_count", latestSourceAfter);
        if (latestDestinationAfter >= 0) after.put("destination_count", latestDestinationAfter);
        pendingEffects.add(new EffectDelta(
                before,
                after,
                AgentActionStore.Verification.UNKNOWN,
                latestEffectClientTick,
                latestEffectWorldRevision));
        unknownTransferRecorded = true;
    }

    private static int optionalNonNegativeInt(Object value, int fallback) {
        if (value == null) return fallback;
        return nonNegativeInt(value);
    }

    private static int requiredNonNegativeInt(Map<String, Object> basis, String key) {
        if (!basis.containsKey(key)) {
            throw new IllegalStateException("container effect count is absent");
        }
        return nonNegativeInt(basis.get(key));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("container attempt is closed");
    }

    private static int interactionCount(Map<String, Object> basis) {
        return Math.addExact(
                Math.addExact(nonNegativeInt(basis.get("open_count")),
                        nonNegativeInt(basis.get("container_clicks"))),
                nonNegativeInt(basis.get("recipe_placements")));
    }

    private static int nonNegativeInt(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("container evidence count is invalid");
        }
        return number.intValue();
    }

    private static List<ItemCount> itemCounts(Map<String, Object> basis) {
        Object raw = basis.get("available_source_items");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > ContainerInspection.MAX_ITEMS) {
            throw new IllegalStateException("container item evidence is invalid");
        }
        var output = new ArrayList<ItemCount>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)
                    || !(map.get("item") instanceof String item)
                    || !(map.get("count") instanceof Number count)
                    || count.doubleValue() != count.intValue()) {
                throw new IllegalStateException("container item evidence is invalid");
            }
            output.add(new ItemCount(item, count.intValue()));
        }
        return List.copyOf(output);
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public enum ReleaseStatus { ACTIVE, PROGRESSING, CONFIRMED, FAULT }

    public record ItemCount(String item, int count) {
        public ItemCount {
            Objects.requireNonNull(item, "item");
            // This is a container aggregate, not the 36-slot player inventory limit.
            // The supported double chest has 54 ordinary stacks of up to 64 items.
            if (count < 1 || count > 54 * 64) {
                throw new IllegalArgumentException("item count is outside the bounded range");
            }
        }
    }

    public record TickResult(
            Status status,
            String evidence,
            int interactionDelta,
            List<ItemCount> items,
            List<EffectDelta> effects,
            List<String> diagnostics) {
        public TickResult(Status status, String evidence, int interactionDelta,
                List<ItemCount> items, List<EffectDelta> effects) {
            this(status, evidence, interactionDelta, items, effects, List.of());
        }

        public TickResult {
            Objects.requireNonNull(status, "status");
            if (interactionDelta < 0 || interactionDelta > 16) {
                throw new IllegalArgumentException("interaction delta is outside the action bound");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (effects.size() > 2) {
                throw new IllegalArgumentException("too many container effect deltas");
            }
        }
    }

    public record EffectDelta(
            Map<String, Object> observedBefore,
            Map<String, Object> observedAfter,
            AgentActionStore.Verification verification,
            long clientTick,
            long worldRevision) {
        public EffectDelta {
            observedBefore = Map.copyOf(Objects.requireNonNull(observedBefore, "observedBefore"));
            observedAfter = Map.copyOf(Objects.requireNonNull(observedAfter, "observedAfter"));
            Objects.requireNonNull(verification, "verification");
            if (clientTick < 0 || worldRevision < 0) {
                throw new IllegalArgumentException("effect clocks must be non-negative");
            }
        }
    }
}
