package dev.aod.mcmcp.safety;

import dev.aod.mcmcp.mcp.RuntimeCallContext;

import java.util.Objects;
import java.util.UUID;

/**
 * Narrow capability bridge for a user response authenticated by the MCP transport.
 *
 * <p>The caller must derive approval from transport metadata, never from Action/tool arguments.
 * A successful call consumes the matching pending policy directly and creates no reusable bearer
 * token.</p>
 */
public final class ScopedEntityAttackConsentTransportBridge {
    private ScopedEntityAttackConsentTransportBridge() {
    }

    public static ResponseCapability bindTransportResponse(
            RuntimeCallContext.ElicitationInput response) {
        Objects.requireNonNull(response, "response");
        if (!response.formSupported() || !response.responded()) {
            throw new IllegalArgumentException("a transport response is required");
        }
        return new ResponseCapability(
                Objects.requireNonNull(response.requestState(), "requestState"),
                response.acceptedAndConfirmed());
    }

    public static boolean consumeApprovedPending(
            ScopedEntityAttackConsentStore store,
            ResponseCapability approval,
            UUID worldSessionId,
            String policyBindingHash,
            ScopedEntityAttackConsentStore.Scope scope,
            long clientTick) {
        Objects.requireNonNull(store, "store");
        return store.consumePendingFromTransportApproval(
                Objects.requireNonNull(approval, "approval"),
                approval.requestState(),
                Objects.requireNonNull(worldSessionId, "worldSessionId"),
                policyBindingHash,
                Objects.requireNonNull(scope, "scope"),
                clientTick);
    }

    public static boolean rejectPending(
            ScopedEntityAttackConsentStore store,
            ResponseCapability response,
            UUID worldSessionId,
            String policyBindingHash,
            ScopedEntityAttackConsentStore.Scope scope,
            long clientTick) {
        Objects.requireNonNull(store, "store");
        return store.rejectPendingFromTransportResponse(
                Objects.requireNonNull(response, "response"),
                response.requestState(),
                Objects.requireNonNull(worldSessionId, "worldSessionId"),
                policyBindingHash,
                Objects.requireNonNull(scope, "scope"),
                clientTick);
    }

    /** Opaque, single-use proof that an elicitation response came from transport metadata. */
    public static final class ResponseCapability {
        private final String requestState;
        private final boolean approved;
        private boolean consumed;

        private ResponseCapability(String requestState, boolean approved) {
            this.requestState = requestState;
            this.approved = approved;
        }

        public String requestState() {
            return requestState;
        }

        boolean consumeApprovedOnce() {
            return approved && consumeOnce();
        }

        boolean consumeRejectedOnce() {
            return !approved && consumeOnce();
        }

        private boolean consumeOnce() {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }
    }
}
