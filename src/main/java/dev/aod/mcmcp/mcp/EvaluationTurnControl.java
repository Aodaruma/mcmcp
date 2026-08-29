package dev.aod.mcmcp.mcp;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Trusted, loopback-only lifecycle control for an evaluator turn.
 *
 * <p>This is deliberately separate from the model-visible MCP Tool surface. It owns only
 * Minecraft physical-input isolation and never grants gameplay capabilities.</p>
 */
public interface EvaluationTurnControl {
    CompletionStage<LeaseReceipt> acquire(AcquireRequest request);

    CompletionStage<LeaseReceipt> await(UUID leaseId);

    CompletionStage<LeaseReceipt> release(UUID leaseId, ReleaseReason reason);

    /**
     * Atomically captures the control-plane revision and whether physical isolation is active.
     * The revision changes both when a lease is acquired and when its first terminal intent is
     * claimed, so a request admitted during an earlier ABSENT/ACTIVE state cannot pass after an
     * acquire-release ABA cycle.
     */
    FenceSnapshot fenceSnapshot();

    /** Fast, synchronized fence used before forwarding a lease-bound MCP request. */
    boolean active(UUID leaseId);

    /** Whether ordinary MCP requests must present the exact active evaluation lease. */
    boolean anyActive();

    record FenceSnapshot(
            long revision,
            boolean isolationActive,
            UUID acceptedLeaseId) {
        public FenceSnapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException("evaluation fence revision must be non-negative");
            }
            if (!isolationActive && acceptedLeaseId != null) {
                throw new IllegalArgumentException(
                        "an inactive evaluation fence cannot accept a lease");
            }
        }

        public boolean accepts(UUID leaseId) {
            return isolationActive
                    && acceptedLeaseId != null
                    && acceptedLeaseId.equals(leaseId);
        }
    }

    record AcquireRequest(
            UUID leaseId,
            long runnerProcessId,
            Duration maximumDuration) {
        public AcquireRequest {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(maximumDuration, "maximumDuration");
            if (runnerProcessId <= 0L) {
                throw new IllegalArgumentException("runnerProcessId must be positive");
            }
            if (maximumDuration.isZero() || maximumDuration.isNegative()) {
                throw new IllegalArgumentException("maximumDuration must be positive");
            }
        }
    }

    record LeaseReceipt(
            UUID leaseId,
            LeaseState state,
            String reason,
            boolean inputsReleased,
            boolean inputOwnerNone,
            boolean allActionsTerminal,
            boolean processIdentityBound) {
        public LeaseReceipt {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(state, "state");
            if (state == LeaseState.ACTIVE && reason != null) {
                throw new IllegalArgumentException("an active lease cannot have a terminal reason");
            }
            if (state == LeaseState.RELEASED
                    && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("a released lease requires a terminal reason");
            }
            if (state == LeaseState.RELEASED
                    && (!inputsReleased
                    || !inputOwnerNone
                    || !allActionsTerminal
                    || !processIdentityBound)) {
                throw new IllegalArgumentException(
                        "a released lease requires every safety boundary to be confirmed");
            }
        }
    }

    enum LeaseState {
        ACTIVE,
        RELEASED
    }

    enum ReleaseReason {
        TURN_COMPLETED("turn_completed"),
        RUNNER_FAILURE("runner_failure"),
        EVALUATION_DEADLINE("evaluation_deadline"),
        LAUNCHER_TEARDOWN("launcher_teardown"),
        RUNNER_CONNECTION_CLOSED("runner_connection_closed"),
        RUNNER_PROCESS_EXITED("runner_process_exited"),
        LOCAL_ESCAPE("local_escape"),
        LOCAL_UI_DISABLED("local_ui_disabled"),
        WORLD_CHANGED("world_changed"),
        PLAYER_UNAVAILABLE("player_unavailable"),
        ENDPOINT_FAULT("endpoint_fault"),
        CLIENT_SHUTDOWN("client_shutdown"),
        LEASE_EXPIRED("lease_expired"),
        ACQUIRE_ABANDONED("acquire_abandoned"),
        INPUT_RELEASE_FAILED("input_release_failed");

        private final String wireName;

        ReleaseReason(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ReleaseReason runnerValue(String value) {
            return switch (value) {
                case "turn_completed" -> TURN_COMPLETED;
                case "runner_failure" -> RUNNER_FAILURE;
                case "evaluation_deadline" -> EVALUATION_DEADLINE;
                case "launcher_teardown" -> LAUNCHER_TEARDOWN;
                default -> throw new IllegalArgumentException("unsupported runner release reason");
            };
        }
    }
}
