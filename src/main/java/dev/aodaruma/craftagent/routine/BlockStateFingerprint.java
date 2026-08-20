package dev.aodaruma.craftagent.routine;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Registry ID plus properties; request properties act as required-match constraints. */
public record BlockStateFingerprint(String blockId, Map<String, String> properties) {
    public BlockStateFingerprint {
        Objects.requireNonNull(blockId, "blockId");
        if (!blockId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid block registry id");
        }
        Objects.requireNonNull(properties, "properties");
        var ordered = new TreeMap<String, String>();
        for (var entry : properties.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "property name");
            var value = Objects.requireNonNull(entry.getValue(), "property value");
            if (!key.matches("[a-z0-9_]{1,64}") || value.isBlank() || value.length() > 96) {
                throw new IllegalArgumentException("invalid block state property");
            }
            ordered.put(key, value);
        }
        properties = Collections.unmodifiableMap(ordered);
    }

    /** Returns true when this expected state is a subset match for a live full state. */
    public boolean matches(BlockStateFingerprint liveState) {
        Objects.requireNonNull(liveState, "liveState");
        if (!blockId.equals(liveState.blockId)) {
            return false;
        }
        return properties.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(liveState.properties.get(entry.getKey())));
    }
}
