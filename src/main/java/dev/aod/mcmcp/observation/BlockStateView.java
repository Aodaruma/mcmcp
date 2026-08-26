package dev.aod.mcmcp.observation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Registry id plus the complete set of properties present on a BlockState. */
public record BlockStateView(String block, Map<String, String> properties) {
    public BlockStateView {
        Objects.requireNonNull(block, "block");
        properties = Map.copyOf(new TreeMap<>(Objects.requireNonNull(properties, "properties")));
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("block", block);
        result.put("properties", properties);
        return result;
    }
}
