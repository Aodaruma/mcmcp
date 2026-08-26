package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Confirmed or explicitly qualified side effect produced by a routine. */
public record RoutineEffect(
        String type,
        Map<String, Object> observedBefore,
        Map<String, Object> observedAfter,
        Verification verification) {
    public RoutineEffect {
        Objects.requireNonNull(type, "type");
        if (!type.matches("[a-z][a-z0-9_]{0,95}")) {
            throw new IllegalArgumentException("invalid effect type");
        }
        observedBefore = immutableCopy(observedBefore, "observedBefore");
        observedAfter = immutableCopy(observedAfter, "observedAfter");
        Objects.requireNonNull(verification, "verification");
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum Verification {
        CONFIRMED("confirmed"),
        INFERRED("inferred"),
        UNKNOWN("unknown");

        private final String wireName;

        Verification(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
