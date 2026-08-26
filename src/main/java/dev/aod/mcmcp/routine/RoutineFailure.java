package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured outcome failure retained by a started routine. */
public record RoutineFailure(
        Category category,
        String code,
        boolean retryable,
        Recovery recovery,
        Scope scope,
        int attempts,
        Map<String, Object> expected,
        Map<String, Object> observed,
        Map<String, Object> evidence,
        List<String> suggestedSnapshotScopes,
        boolean requiresUser) {
    public RoutineFailure {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(scope, "scope");
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid routine failure code");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative");
        }
        expected = immutableMap(expected, "expected");
        observed = immutableMap(observed, "observed");
        evidence = immutableMap(evidence, "evidence");
        Objects.requireNonNull(suggestedSnapshotScopes, "suggestedSnapshotScopes");
        suggestedSnapshotScopes = List.copyOf(suggestedSnapshotScopes);
        if (suggestedSnapshotScopes.stream().anyMatch(scopeName -> scopeName == null || scopeName.isBlank())) {
            throw new IllegalArgumentException("suggested snapshot scopes must be non-blank");
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum Category {
        TRANSIENT("transient"),
        PRECONDITION("precondition"),
        DIVERGENCE("divergence"),
        SAFETY("safety"),
        EXTERNAL("external");

        private final String wireName;

        Category(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum Recovery {
        RETRY("retry"),
        REPLAN("replan"),
        USER("user"),
        NONE("none");

        private final String wireName;

        Recovery(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum Scope {
        STEP("step"),
        ROUTINE("routine"),
        FINALIZATION("finalization");

        private final String wireName;

        Scope(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
