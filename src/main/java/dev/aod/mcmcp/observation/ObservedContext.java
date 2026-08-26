package dev.aod.mcmcp.observation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Values captured at observation time; none are silently refreshed later. */
public record ObservedContext(
        int blockLight,
        int skyLight,
        String fluid,
        Boolean fluidSource,
        Integer fluidAmount,
        boolean replaceable,
        boolean collisionEmpty,
        List<String> sturdyFaces) {
    public ObservedContext {
        sturdyFaces = List.copyOf(sturdyFaces);
    }

    public ObservedContext(
            int blockLight,
            int skyLight,
            String fluid,
            boolean replaceable,
            boolean collisionEmpty,
            List<String> sturdyFaces) {
        this(blockLight, skyLight, fluid, null, null, replaceable, collisionEmpty, sturdyFaces);
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("block_light_at_observation", blockLight);
        result.put("sky_light_at_observation", skyLight);
        result.put("fluid_at_observation", fluid);
        result.put("fluid_source_at_observation", fluidSource);
        result.put("fluid_amount_at_observation", fluidAmount);
        result.put("replaceable_at_observation", replaceable);
        result.put("collision_empty_at_observation", collisionEmpty);
        result.put("sturdy_faces_at_observation", sturdyFaces);
        return result;
    }
}
