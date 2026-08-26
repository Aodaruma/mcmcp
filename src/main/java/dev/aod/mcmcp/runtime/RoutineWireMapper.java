package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.RoutineEvent;
import dev.aod.mcmcp.routine.RoutineEffect;
import dev.aod.mcmcp.routine.RoutineFailure;
import dev.aod.mcmcp.routine.RoutineSnapshot;
import dev.aod.mcmcp.routine.RoutineState;

import java.util.ArrayList;
import java.util.Comparator;
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
                : step(snapshot));

        var checkpoint = snapshot.checkpoint();
        result.put("checkpoint", Map.of(
                "seq", checkpoint.seq(),
                "observation_revision", checkpoint.observationRevision()));
        var verification = snapshot.verificationSummary();
        result.put("verification", Map.of(
                "confirmed", verification.confirmed(),
                "expected", verification.expected(),
                "unknown", verification.unknown()));
        result.put("resources", resources(snapshot));
        result.put("effects", snapshot.effects().stream()
                .map(RoutineWireMapper::effect)
                .toList());
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

    private static Map<String, Object> step(RoutineSnapshot snapshot) {
        var step = snapshot.currentStep();
        if (step == null) {
            return null;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", step.kind());
        payload.putAll(step.fields());
        return Map.copyOf(payload);
    }

    private static Map<String, Object> waitPayload(RoutineSnapshot snapshot) {
        var wait = snapshot.waitState();
        if (snapshot.state() != RoutineState.WAITING || wait == null) {
            return null;
        }
        return Map.of(
                "reason", wait.reason(),
                "deadline_client_tick", wait.deadlineClientTick(),
                "wake_condition", wait.wakeCondition());
    }

    private static Map<String, Object> effect(RoutineEffect effect) {
        return Map.of(
                "type", effect.type(),
                "observed_before", effect.observedBefore(),
                "observed_after", effect.observedAfter(),
                "verification", effect.verification().wireName());
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

    private static Map<String, Object> resources(RoutineSnapshot snapshot) {
        if (!"apply_block_plan".equals(snapshot.kind())) {
            return null;
        }
        Object raw = snapshot.diagnostics().get("resource_plan");
        if (!(raw instanceof Map<?, ?> plan)) {
            throw new IllegalStateException("apply_block_plan snapshot has no resource_plan");
        }
        long basisRevision = snapshot.checkpoint().observationRevision();
        Object rawBasis = plan.get("basis_observation_revision");
        if (rawBasis instanceof Number number) {
            basisRevision = number.longValue();
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("planned", resourceCounts(plan.get("planned"), "planned"));
        result.put("remaining", resourceCounts(plan.get("remaining"), "remaining"));
        result.put("available", resourceCounts(plan.get("available"), "available"));
        result.put("server_synchronized", Boolean.TRUE.equals(plan.get("server_synchronized")));
        result.put("basis_observation_revision", Math.max(0, basisRevision));
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> resourceCounts(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalStateException("resource_plan." + name + " must be a map");
        }
        var result = new ArrayList<Map<String, Object>>(values.size());
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String item)
                    || !(entry.getValue() instanceof Number count)
                    || count.longValue() < 0
                    || count.longValue() > Integer.MAX_VALUE) {
                throw new IllegalStateException("resource_plan." + name + " is malformed");
            }
            result.add(Map.of("item", item, "count", count.intValue()));
        }
        result.sort(Comparator.comparing(entry -> (String) entry.get("item")));
        return List.copyOf(result);
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

}
