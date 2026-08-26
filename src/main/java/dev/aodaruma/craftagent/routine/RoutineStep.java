package dev.aodaruma.craftagent.routine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Wire-neutral description of the finite semantic action currently being supervised. */
public record RoutineStep(String kind, Map<String, Object> fields) {
    public RoutineStep {
        Objects.requireNonNull(kind, "kind");
        if (!kind.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid routine step kind");
        }
        Objects.requireNonNull(fields, "fields");
        if (fields.containsKey("kind")) {
            throw new IllegalArgumentException("routine step fields must not redefine kind");
        }
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }

    public static RoutineStep block(String kind, BlockTarget target) {
        Objects.requireNonNull(target, "target");
        return new RoutineStep(kind, Map.of("target", Map.of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z())));
    }
}
