package dev.aod.mcmcp.observation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A dimension-qualified block coordinate that never carries server identity. */
public record BlockPosition(String dimension, int x, int y, int z) {
    public BlockPosition {
        Objects.requireNonNull(dimension, "dimension");
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", dimension);
        result.put("x", x);
        result.put("y", y);
        result.put("z", z);
        return result;
    }
}
