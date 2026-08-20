package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.routine.BlockTarget;
import dev.aodaruma.craftagent.routine.RoutineEvent;
import dev.aodaruma.craftagent.routine.RoutineFailure;
import dev.aodaruma.craftagent.routine.RoutineSnapshot;
import dev.aodaruma.craftagent.routine.RoutineState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable MCP wire projection for routine state; no Minecraft object crosses this boundary. */
final class RoutineWireMapper {
    private RoutineWireMapper() {
    }

    static Map<String, Object> toMap(RoutineSnapshot snapshot) {
        var result = new LinkedHashMap<String, Object>();
        result.put("routine_id", snapshot.routineId().toString());
        result.put("kind", snapshot.kind());
        result.put("state", snapshot.state().name());
        result.put("phase", snapshot.phase());
        result.put("goal", Map.of("verified", snapshot.goalVerified()));
        result.put("progress", Map.of(
                "completed", Math.min(snapshot.progress().completed(), snapshot.progress().total()),
                "total", snapshot.progress().total(),
                "unit", snapshot.progress().unit()));
        result.put("current_step", snapshot.state().terminal()
                ? null
                : Map.of("kind", "break_block", "target", target(snapshot.target())));

        int verifiedBreaks = intValue(snapshot.verification().get("verified_breaks"));
        result.put("checkpoint", Map.of(
                "seq", Math.max(0, verifiedBreaks),
                "observation_revision", snapshot.lastObservationRevision()));
        boolean inventorySynchronized = Boolean.TRUE.equals(
                snapshot.verification().get("inventory_server_synchronized"));
        result.put("verification", Map.of(
                "confirmed", inventorySynchronized
                        ? Math.min(snapshot.progress().completed(), snapshot.progress().total())
                        : 0,
                "expected", snapshot.progress().total(),
                "unknown", inventorySynchronized ? 0 : 1));
        result.put("effects", List.of());
        result.put("safety", Map.of(
                "mode", snapshot.state() == RoutineState.FINALIZING ? "stopping" : "normal",
                "last_check_client_tick", snapshot.lastClientTick()));
        result.put("wait", waitPayload(snapshot));
        result.put("finalization", finalization(snapshot));
        result.put("events", snapshot.eventPage().events().stream()
                .map(RoutineWireMapper::event)
                .toList());
        result.put("failure", snapshot.failure() == null ? null : failure(snapshot.failure()));
        result.put("next_poll_after_ms", snapshot.eventPage().hasMore()
                ? 50
                : snapshot.state().terminal() ? 1_000 : 250);
        result.put("events_truncated", snapshot.eventPage().eventsTruncated());
        return result;
    }

    private static Map<String, Object> target(BlockTarget target) {
        return Map.of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
    }

    private static Map<String, Object> waitPayload(RoutineSnapshot snapshot) {
        if (snapshot.state() != RoutineState.WAITING || snapshot.waitDeadlineClientTick() == null) {
            return null;
        }
        return Map.of(
                "reason", "target_regeneration",
                "deadline_client_tick", snapshot.waitDeadlineClientTick(),
                "wake_condition", "target matches the original full block state");
    }

    private static Map<String, Object> finalization(RoutineSnapshot snapshot) {
        String status;
        String phase = null;
        Map<String, Object> failure = null;
        if (snapshot.finalizationCompleted()) {
            phase = "release";
            var finalizationFailure = snapshot.finalizationFailure();
            if (finalizationFailure != null
                    && finalizationFailure.scope() == RoutineFailure.Scope.FINALIZATION) {
                status = "failed";
                failure = failure(finalizationFailure);
            }
            else {
                status = "succeeded";
            }
        }
        else if (snapshot.state() == RoutineState.FINALIZING) {
            status = "running";
            phase = snapshot.phase();
        }
        else {
            status = "pending";
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("required", true);
        payload.put("status", status);
        payload.put("phase", phase);
        payload.put("failure", failure);
        return payload;
    }

    private static Map<String, Object> event(RoutineEvent event) {
        return Map.of(
                "seq", event.seq(),
                "type", event.type().wireName(),
                "client_tick", event.clientTick(),
                "observation_revision", event.observationRevision(),
                "details", event.details());
    }

    private static Map<String, Object> failure(RoutineFailure failure) {
        var result = new LinkedHashMap<String, Object>();
        result.put("category", failure.category().wireName());
        result.put("code", failure.code());
        result.put("retryable", failure.retryable());
        result.put("recovery", failure.recovery().wireName());
        result.put("scope", failure.scope().wireName());
        result.put("attempts", failure.attempts());
        result.put("expected", failure.expected());
        result.put("observed", failure.observed());
        result.put("evidence", failure.evidence());
        result.put("suggested_snapshot_scopes", failure.suggestedSnapshotScopes());
        result.put("requires_user", failure.requiresUser());
        return result;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
