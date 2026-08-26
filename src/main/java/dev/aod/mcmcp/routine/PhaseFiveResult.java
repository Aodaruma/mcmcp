package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed terminal result carried only by positive server-derived evidence. */
public record PhaseFiveResult(
        int verifiedUnits,
        boolean goalVerified,
        Map<String, Object> basis,
        List<RoutineEffect> effects) {
    public PhaseFiveResult {
        if (verifiedUnits < 0) {
            throw new IllegalArgumentException("verified units must be non-negative");
        }
        basis = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(basis, "basis")));
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
    }
}
