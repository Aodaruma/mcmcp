package dev.aod.mcmcp.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Minecraft-independent boundary between MCP request handling and the client runtime.
 *
 * <p>The implementation owns dispatch onto the Minecraft client thread. It must inspect
 * {@link RuntimeCallContext#canBeginWork()} immediately before starting work and again
 * immediately before every side effect. A future that completes after cancellation or the
 * deadline must not cause a delayed side effect.</p>
 */
@FunctionalInterface
public interface McpRuntimePort {
    CompletionStage<RuntimeReply> submit(RuntimeCommand command, RuntimeCallContext context);

    sealed interface RuntimeCommand permits GetState, GetSnapshot, CompareBlockPlan,
            GetRecipes, ListRoutines, GetRoutine, StartRoutine, CancelRoutine, EmergencyStop {
        String toolName();
    }

    record GetState() implements RuntimeCommand {
        @Override
        public String toolName() {
            return "agent_get_state";
        }
    }

    record GetSnapshot(Map<String, Object> arguments) implements RuntimeCommand {
        public GetSnapshot {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "get_snapshot";
        }
    }

    record CompareBlockPlan(Map<String, Object> arguments) implements RuntimeCommand {
        public CompareBlockPlan {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "compare_block_plan";
        }
    }

    record GetRecipes(Map<String, Object> arguments) implements RuntimeCommand {
        public GetRecipes {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "get_recipes";
        }
    }

    record ListRoutines(Map<String, Object> arguments) implements RuntimeCommand {
        public ListRoutines {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "list_routines";
        }
    }

    record GetRoutine(Map<String, Object> arguments) implements RuntimeCommand {
        public GetRoutine {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "get_routine";
        }
    }

    record StartRoutine(Map<String, Object> arguments) implements RuntimeCommand {
        public StartRoutine {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "start_routine";
        }
    }

    record CancelRoutine(Map<String, Object> arguments) implements RuntimeCommand {
        public CancelRoutine {
            arguments = immutableCopy(arguments);
        }

        @Override
        public String toolName() {
            return "cancel_routine";
        }
    }

    record EmergencyStop(String reason) implements RuntimeCommand {
        public EmergencyStop {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toolName() {
            return "emergency_stop";
        }
    }

    record RuntimeReply(Map<String, Object> data, RuntimeFailure failure) {
        public RuntimeReply {
            if ((data == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one of data or failure must be supplied");
            }
            if (data != null) {
                data = immutableCopy(data);
            }
        }

        public static RuntimeReply success(Map<String, Object> data) {
            return new RuntimeReply(Objects.requireNonNull(data, "data"), null);
        }

        public static RuntimeReply failure(String code, String message, boolean retryable) {
            return failure(code, message, retryable, Map.of());
        }

        public static RuntimeReply failure(
                String code, String message, boolean retryable, Map<String, Object> details) {
            return new RuntimeReply(null, new RuntimeFailure(code, message, retryable, details));
        }

        public boolean successful() {
            return failure == null;
        }
    }

    record RuntimeFailure(String code, String message, boolean retryable, Map<String, Object> details) {
        public RuntimeFailure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (!code.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("Invalid public failure code");
            }
            if (message.isBlank() || message.length() > 512) {
                throw new IllegalArgumentException("Public failure message must contain 1..512 characters");
            }
            details = immutableCopy(details);
        }
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
