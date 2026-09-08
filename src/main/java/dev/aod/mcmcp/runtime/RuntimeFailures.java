package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.dsl.ActionDslException;
import dev.aod.mcmcp.mcp.McpRuntimePort.RuntimeReply;
import dev.aod.mcmcp.observation.BlockPlanValidationException;
import dev.aod.mcmcp.observation.MinecraftObservationService;
import dev.aod.mcmcp.routine.RoutineManager;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/** 内部失敗を固定の公開診断へ変換する。未知例外の本文は返さない。 */
final class RuntimeFailures {
    private RuntimeFailures() {}

    static String sanitizeLocalCode(String code) {
        if (code == null || code.isBlank()) {
            return "internal_error";
        }
        String normalized = code.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return "internal_error";
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    static void requireReady(WorldSessionTracker.Snapshot session) {
        if (!session.worldReady()) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
    }

    static RuntimeReply mapFailure(Throwable failure) {
        var cause = unwrap(failure);
        if (cause instanceof FailureWithDetailsException detailed) {
            var mapped = mapFailure(detailed.getCause());
            var original = mapped.failure();
            var details = new LinkedHashMap<String, Object>(original.details());
            details.putAll(detailed.details());
            return RuntimeReply.failure(
                    original.code(), original.message(), original.retryable(), details);
        }
        if (cause instanceof ClientCommandInbox.CommandTimeoutException) {
            return RuntimeReply.failure("server_busy", "The client-thread deadline expired", true);
        }
        if (cause instanceof ClientCommandInbox.CommandInvalidatedException) {
            return RuntimeReply.failure("unsafe_state", "The world or safety epoch changed before execution", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return RuntimeReply.failure("server_busy", "The bounded client command inbox is full", true);
        }
        if (cause instanceof MinecraftObservationService.ObservationUnavailableException unavailable) {
            return RuntimeReply.failure(unavailable.code(), unavailable.getMessage(), true);
        }
        if (cause instanceof ActionDslException invalidDsl) {
            return RuntimeReply.failure(
                    invalidDsl.code().name().toLowerCase(Locale.ROOT),
                    publicMessage(invalidDsl),
                    invalidDsl.code() != ActionDslException.Code.INVALID_ARGUMENT);
        }
        if (cause instanceof AgentActionStore.BusyException) {
            return RuntimeReply.failure("task_busy", "Another action is active", true);
        }
        if (cause instanceof AgentActionStore.NotFoundException) {
            return RuntimeReply.failure("action_not_found", "The action is not retained", false);
        }
        if (cause instanceof RuntimeInvocationException invocation) {
            return RuntimeReply.failure(
                    invocation.code(), publicMessage(invocation), invocation.retryable(), invocation.details());
        }
        if (cause instanceof RoutineManager.RoutineBusyException busy) {
            var details = busy.activeRoutineId() == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("active_routine_id", busy.activeRoutineId().toString());
            return RuntimeReply.failure("task_busy", "Another routine is active", true, details);
        }
        if (cause instanceof RoutineManager.IdempotencyConflictException) {
            return RuntimeReply.failure(
                    "idempotency_conflict", "The idempotency key has different arguments", false);
        }
        if (cause instanceof RoutineManager.RoutineNotFoundException) {
            return RuntimeReply.failure("routine_not_found", "The routine is not retained", false);
        }
        if (cause instanceof BlockPlanValidationException invalidPlan) {
            var details = new LinkedHashMap<String, Object>(invalidPlan.details());
            details.put("plan_validation_code", invalidPlan.code());
            details.put("path", invalidPlan.path());
            return RuntimeReply.failure(
                    "invalid_argument", publicMessage(invalidPlan), false, details);
        }
        if (cause instanceof IllegalArgumentException) {
            return RuntimeReply.failure("invalid_argument", publicMessage(cause), false);
        }
        return RuntimeReply.failure("internal_error", "The request failed; consult the local audit log", false);
    }

    static Throwable unwrap(Throwable failure) {
        var result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    static String publicMessage(Throwable failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "The request arguments are invalid";
        }
        var normalized = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(512, normalized.length()));
    }

    static final class RuntimeInvocationException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final Map<String, Object> details;

        RuntimeInvocationException(
                String code,
                String message,
                boolean retryable,
                Map<String, Object> details) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.retryable = retryable;
            this.details = Map.copyOf(details);
        }

        String code() {
            return code;
        }

        boolean retryable() {
            return retryable;
        }

        Map<String, Object> details() {
            return details;
        }
    }

    static final class FailureWithDetailsException extends RuntimeException {
        private final Map<String, Object> details;

        FailureWithDetailsException(Throwable cause, Map<String, Object> details) {
            super(Objects.requireNonNull(cause, "cause"));
            this.details = Map.copyOf(details);
        }

        Map<String, Object> details() {
            return details;
        }
    }
}
