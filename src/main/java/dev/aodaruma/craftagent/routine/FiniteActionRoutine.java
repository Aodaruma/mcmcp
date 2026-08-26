package dev.aodaruma.craftagent.routine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Deterministic block/entity finite-action supervisor. */
final class FiniteActionRoutine extends AbstractSemanticRoutine {
    private static final String PRECHECK = "precheck";
    private static final String PREPARE = "prepare";
    private static final String WAIT_PREPARE = "wait_prepare";
    private static final String EXECUTE = "execute";
    private static final String WAIT_SERVER_SYNC = "wait_server_sync";
    private static final String VERIFY = "verify";
    private static final String RETRY_FRESH = "retry_fresh_observation";

    private int retries;
    private int verifiedSteps;
    private long retryAfterTick;
    private long retryAfterRevision;
    private SemanticActionEvidence verificationEvidence;
    private SemanticActionEvidence lastEvidence;
    private SemanticActionPreparationAttempt preparation;

    FiniteActionRoutine(
            UUID routineId,
            SemanticActionRequest request,
            SemanticActionPort port,
            int eventCapacity,
            long admittedClientTick) {
        super(routineId, requireFinite(request), port, eventCapacity, admittedClientTick);
    }

    @Override
    protected void tickFrame(SemanticActionFrame frame) {
        switch (phase) {
            case "queued" -> startPhase(PRECHECK, RoutineState.VALIDATING);
            case PRECHECK -> precheck(frame);
            case PREPARE -> beginPreparation(frame);
            case WAIT_PREPARE -> awaitPreparation();
            case EXECUTE -> execute(frame);
            case WAIT_SERVER_SYNC -> awaitServerSync(frame);
            case VERIFY -> verify();
            case RETRY_FRESH -> awaitFreshObservation(frame);
            default -> fail(adapterFailure("INVALID_ROUTINE_PHASE"));
        }
    }

    private void precheck(SemanticActionFrame frame) {
        if (postconditionAlreadySatisfied(frame)) {
            verifiedSteps = 1;
            beginFinalization();
            return;
        }
        var precondition = preconditionFailure(frame);
        if (precondition != null) {
            if (blockNeedsPreparation(frame, precondition)) {
                startPhase(PREPARE, RoutineState.RUNNING);
                return;
            }
            fail(precondition);
            return;
        }
        startPhase(EXECUTE, RoutineState.RUNNING);
    }

    private void beginPreparation(SemanticActionFrame frame) {
        var issued = java.util.Objects.requireNonNull(
                port.beginPreparation(request, hardDeadlineClientTick),
                "adapter returned no preparation attempt");
        if (!kind().equals(issued.kind())
                || issued.issuedClientTick() != frame.clientTick()
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.leaseExpiresAtClientTick() != hardDeadlineClientTick
                || issued.positionCorrectionRevisionAtStart()
                        != frame.positionCorrectionRevision()) {
            try {
                port.releasePreparation(issued);
            } catch (RuntimeException | LinkageError ignored) {
            }
            throw new IllegalStateException("semantic action adapter violated preparation contract");
        }
        preparation = issued;
        startPhase(WAIT_PREPARE, RoutineState.WAITING);
    }

    private void awaitPreparation() {
        var current = java.util.Objects.requireNonNull(
                preparation, "no active preparation attempt");
        var observed = java.util.Objects.requireNonNull(
                port.preparationEvidence(current),
                "adapter returned no preparation evidence");
        if (!current.attemptId().equals(observed.attemptId())
                || observed.clientTick() < current.issuedClientTick()
                || observed.observationRevision() < current.issuedObservationRevision()) {
            throw new IllegalStateException(
                    "semantic action adapter violated preparation evidence contract");
        }
        if (observed.failure() != null) {
            fail(observed.failure());
            return;
        }
        if (!observed.prepared()) {
            port.maintainPreparation(current);
            return;
        }
        var live = observed.liveBlockState().orElseThrow();
        if (expectedAfter().matches(live)) {
            releasePreparation();
            verifiedSteps = 1;
            beginFinalization();
            return;
        }
        if (!expectedBefore().matches(live)) {
            fail(divergence(
                    "PRECONDITION_CHANGED_DURING_PREPARE",
                    stateMap(expectedBefore()), stateMap(live)));
            return;
        }
        startPhase(EXECUTE, RoutineState.RUNNING);
    }

    private void execute(SemanticActionFrame frame) {
        var precondition = preconditionFailure(frame);
        if (precondition != null) {
            fail(precondition);
            return;
        }
        if (preparation == null) {
            dispatch(frame);
        } else {
            dispatchPrepared(frame);
        }
        startPhase(WAIT_SERVER_SYNC, RoutineState.RUNNING);
    }

    private void dispatchPrepared(SemanticActionFrame frame) {
        var current = java.util.Objects.requireNonNull(
                preparation, "no active preparation attempt");
        var issued = java.util.Objects.requireNonNull(
                port.dispatchPrepared(request, current, hardDeadlineClientTick),
                "adapter returned no prepared attempt");
        if (!kind().equals(issued.kind())
                || issued.issuedClientTick() != frame.clientTick()
                || issued.issuedObservationRevision() < frame.observationRevision()
                || issued.leaseExpiresAtClientTick() != hardDeadlineClientTick
                || issued.positionCorrectionRevisionAtDispatch()
                        != frame.positionCorrectionRevision()) {
            try {
                port.release(issued);
            } catch (RuntimeException | LinkageError ignored) {
            }
            throw new IllegalStateException(
                    "semantic action adapter violated prepared dispatch contract");
        }
        attempt = issued;
        attempts++;
        releasePreparation();
    }

    private void releasePreparation() {
        var current = preparation;
        preparation = null;
        if (current != null) {
            port.releasePreparation(current);
        }
    }

    private void awaitServerSync(SemanticActionFrame frame) {
        var observed = evidence();
        lastEvidence = observed;
        if (observed.failure() != null) {
            handleEvidenceFailure(observed, frame);
            return;
        }
        if (request instanceof InteractEntityRequest) {
            if (observed.inventoryUpdateObserved() && observed.inventoryServerSynchronized()) {
                verificationEvidence = observed;
                stopInput();
                releaseCurrent();
                startPhase(VERIFY, RoutineState.RUNNING);
                return;
            }
        } else if (observed.acknowledged() && observed.serverBlockState().isPresent()) {
            verificationEvidence = observed;
            stopInput();
            releaseCurrent();
            startPhase(VERIFY, RoutineState.RUNNING);
            return;
        }

        // The attempt lease is a renewable two-second adapter watchdog, not the action deadline.
        // The adapter heartbeat below renews it while this domain routine remains within its
        // independently enforced hard deadline.
        port.maintain(attempt);
    }

    private void verify() {
        var observed = verificationEvidence;
        if (observed == null) {
            fail(adapterFailure("MISSING_VERIFICATION_EVIDENCE"));
            return;
        }
        if (request instanceof InteractEntityRequest entityRequest) {
            boolean confirmed = observed.inventoryUpdateObserved()
                    && observed.inventoryServerSynchronized()
                    && observed.goalItemCount() >= entityRequest.goal().minimumInventoryCount();
            if (!confirmed) {
                fail(postconditionMismatch(
                        Map.of("item", entityRequest.goal().itemId(),
                                "minimum_inventory_count", entityRequest.goal().minimumInventoryCount()),
                        Map.of("goal_item_count", observed.goalItemCount(),
                                "inventory_server_synchronized", observed.inventoryServerSynchronized()),
                        observed.basis()));
                return;
            }
        } else {
            var expected = expectedAfter();
            var actual = observed.serverBlockState();
            if (!observed.acknowledged()
                    || actual.isEmpty()
                    || !expected.matches(actual.orElseThrow())) {
                fail(postconditionMismatch(
                        stateMap(expected),
                        actual.<Map<String, Object>>map(FiniteActionRoutine::stateMap)
                                .orElseGet(() -> Map.of("currentness", "unknown")),
                        observed.basis()));
                return;
            }
        }

        verifiedSteps = 1;
        events.append(RoutineEventType.STEP_VERIFIED, lastClientTick, lastObservationRevision,
                Map.of("kind", kind(), "attempts", attempts));
        beginFinalization();
    }

    private void handleEvidenceFailure(
            SemanticActionEvidence observed, SemanticActionFrame frame) {
        var outcome = observed.failure();
        boolean retryPermitted = !(request instanceof InteractEntityRequest)
                && outcome.retryable()
                && outcome.recovery() == RoutineFailure.Recovery.RETRY
                && observed.safeToRetry()
                && frame.safeToRetry()
                && retries < MAX_RETRIES;
        if (!retryPermitted) {
            fail(outcome.recovery() == RoutineFailure.Recovery.RETRY
                    ? asReplanFailure(outcome)
                    : outcome);
            return;
        }
        releaseCurrent();
        retries++;
        retryAfterTick = Math.max(observed.clientTick(), lastClientTick);
        retryAfterRevision = Math.max(observed.observationRevision(), lastObservationRevision);
        events.append(RoutineEventType.RETRYING, lastClientTick, lastObservationRevision,
                Map.of("code", outcome.code(), "retry", retries, "max_retries", MAX_RETRIES));
        startPhase(RETRY_FRESH, RoutineState.WAITING);
    }

    private void awaitFreshObservation(SemanticActionFrame frame) {
        if (frame.clientTick() <= retryAfterTick
                || frame.observationRevision() <= retryAfterRevision) {
            return;
        }
        verificationEvidence = null;
        startPhase(PRECHECK, RoutineState.VALIDATING);
    }

    private boolean postconditionAlreadySatisfied(SemanticActionFrame frame) {
        if (request instanceof InteractEntityRequest entityRequest) {
            return frame.inventoryServerSynchronized()
                    && frame.goalItemCount() >= entityRequest.goal().minimumInventoryCount();
        }
        return frame.liveBlockState().filter(expectedAfter()::matches).isPresent();
    }

    private RoutineFailure preconditionFailure(SemanticActionFrame frame) {
        if (request instanceof InteractEntityRequest entityRequest) {
            if (!frame.entityResolved() || frame.entityType().isEmpty()) {
                return precondition("ENTITY_REF_NOT_CURRENT", "entity_resolved", false);
            }
            if (!entityRequest.expectedType().equals(frame.entityType().orElseThrow())) {
                return divergence(
                        "ENTITY_TYPE_MISMATCH",
                        Map.of("type", entityRequest.expectedType()),
                        Map.of("type", frame.entityType().orElseThrow()));
            }
            if (!frame.entityVisible() || !frame.entityLineOfSight()
                    || !frame.entityInReach() || !frame.crosshairOnEntity()) {
                return precondition("ENTITY_NOT_INTERACTABLE", "visible_los_reach_crosshair", false);
            }
            return null;
        }

        Optional<BlockStateFingerprint> current = frame.liveBlockState();
        if (current.isEmpty()) {
            return divergence(
                    "TARGET_NOT_CURRENTLY_OBSERVABLE",
                    stateMap(expectedBefore()),
                    Map.of("currentness", "unknown"));
        }
        if (!expectedBefore().matches(current.orElseThrow())) {
            return divergence(
                    "PRECONDITION_MISMATCH",
                    stateMap(expectedBefore()),
                    stateMap(current.orElseThrow()));
        }
        if (!frame.blockInReach() || !frame.crosshairOnBlock()) {
            return precondition("BLOCK_NOT_INTERACTABLE", "reach_and_crosshair", false);
        }
        return null;
    }

    private boolean blockNeedsPreparation(
            SemanticActionFrame frame, RoutineFailure precondition) {
        return !(request instanceof InteractEntityRequest)
                && !frame.crosshairOnBlock()
                && (frame.liveBlockState().isEmpty()
                        || expectedBefore().matches(frame.liveBlockState().orElseThrow()))
                && ("TARGET_NOT_CURRENTLY_OBSERVABLE".equals(precondition.code())
                        || "BLOCK_NOT_INTERACTABLE".equals(precondition.code()));
    }

    private RoutineFailure precondition(String code, String condition, boolean requiresUser) {
        return failure(
                RoutineFailure.Category.PRECONDITION,
                code,
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                Map.of(condition, true),
                Map.of(condition, false),
                Map.of(),
                requiresUser);
    }

    private RoutineFailure divergence(
            String code, Map<String, Object> expected, Map<String, Object> observed) {
        return failure(
                RoutineFailure.Category.DIVERGENCE,
                code,
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                expected,
                observed,
                Map.of(),
                false);
    }

    private RoutineFailure postconditionMismatch(
            Map<String, Object> expected,
            Map<String, Object> observed,
            Map<String, Object> basis) {
        return failure(
                RoutineFailure.Category.DIVERGENCE,
                "POSTCONDITION_MISMATCH",
                false,
                RoutineFailure.Recovery.REPLAN,
                RoutineFailure.Scope.STEP,
                expected,
                observed,
                basis,
                false);
    }

    private RoutineFailure asReplanFailure(RoutineFailure source) {
        return new RoutineFailure(
                source.category(),
                source.code(),
                false,
                RoutineFailure.Recovery.REPLAN,
                source.scope(),
                attempts,
                source.expected(),
                source.observed(),
                source.evidence(),
                source.suggestedSnapshotScopes(),
                source.requiresUser());
    }

    @Override
    protected RoutineProgress progress() {
        return new RoutineProgress(verifiedSteps, 1,
                request instanceof InteractEntityRequest ? "interactions" : "blocks");
    }

    @Override
    protected RoutineStep currentStep() {
        if (request instanceof InteractEntityRequest entity) {
            return new RoutineStep(kind(), Map.of(
                    "entity_ref", entity.entityRef(),
                    "expected_type", entity.expectedType()));
        }
        return new RoutineStep(kind(), Map.of(
                "target", targetMap(blockTarget()),
                "expected_after", stateMap(expectedAfter())));
    }

    @Override
    protected RoutineCheckpoint checkpoint() {
        return new RoutineCheckpoint(verifiedSteps, lastObservationRevision);
    }

    @Override
    protected RoutineVerification verification() {
        return new RoutineVerification(verifiedSteps, 1, verifiedSteps == 0 ? 1 : 0);
    }

    @Override
    protected RoutineWait waitState() {
        return new RoutineWait(
                phase.equals(WAIT_PREPARE) ? "bounded_preparation" : "server_sync",
                hardDeadlineClientTick,
                phase.equals(WAIT_PREPARE)
                        ? "bounded aim and hotbar selection are ready"
                        : phase.equals(RETRY_FRESH)
                        ? "a fresh client tick and observation revision are available"
                        : "server confirms the expected action result");
    }

    @Override
    protected Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("attempts", attempts);
        result.put("retries", retries);
        if (lastEvidence != null) {
            result.put("acknowledged", lastEvidence.acknowledged());
            result.put("inventory_update_observed", lastEvidence.inventoryUpdateObserved());
            result.put("evidence_basis", lastEvidence.basis());
        }
        return result;
    }

    private BlockTarget blockTarget() {
        return switch (request) {
            case BreakBlockRequest action -> action.target();
            case PlaceBlockRequest action -> action.target();
            case UseItemOnBlockRequest action -> action.target();
            case InteractBlockRequest action -> action.target();
            default -> throw new IllegalStateException("entity action has no block target");
        };
    }

    private BlockStateFingerprint expectedBefore() {
        return switch (request) {
            case BreakBlockRequest action -> action.expectedBefore();
            case PlaceBlockRequest action -> action.expectedBefore();
            case UseItemOnBlockRequest action -> action.expectedBefore();
            case InteractBlockRequest action -> action.expectedBefore();
            default -> throw new IllegalStateException("entity action has no block state");
        };
    }

    private BlockStateFingerprint expectedAfter() {
        return switch (request) {
            case BreakBlockRequest action -> action.expectedAfter();
            case PlaceBlockRequest action -> action.expectedAfter();
            case UseItemOnBlockRequest action -> action.expectedAfter();
            case InteractBlockRequest action -> action.expectedAfter();
            default -> throw new IllegalStateException("entity action has no block state");
        };
    }

    private static SemanticActionRequest requireFinite(SemanticActionRequest request) {
        if (request instanceof NavigateToRequest) {
            throw new IllegalArgumentException("navigate_to requires NavigateRoutine");
        }
        return request;
    }

    private static Map<String, Object> targetMap(BlockTarget target) {
        return Map.of(
                "dimension", target.dimension(),
                "x", target.x(), "y", target.y(), "z", target.z());
    }

    private static Map<String, Object> stateMap(BlockStateFingerprint state) {
        return Map.of("block", state.blockId(), "properties", state.properties());
    }

}
