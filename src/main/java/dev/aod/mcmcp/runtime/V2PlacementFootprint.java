package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockStateFingerprint;

import java.util.LinkedHashMap;

/** Vanilla's derived companion cell; the supplied coordinate is the foot/lower placement anchor. */
final class V2PlacementFootprint {
    enum Kind { SINGLE, BED, DOUBLE }
    record Cell(NavCell position, BlockStateFingerprint state) { }

    static Cell companion(NavCell anchor, BlockStateFingerprint state, Kind kind) {
        if (kind == Kind.SINGLE) return null;
        var properties = new LinkedHashMap<>(state.properties());
        int dx = 0, dy = 0, dz = 0;
        if (kind == Kind.BED) {
            int sign = "head".equals(properties.get("part")) ? -1 : 1;
            switch (properties.getOrDefault("facing", "")) {
                case "north" -> dz = -sign;
                case "south" -> dz = sign;
                case "west" -> dx = -sign;
                case "east" -> dx = sign;
                default -> throw new IllegalArgumentException("bed facing missing");
            }
            properties.put("part", sign == 1 ? "head" : "foot");
        } else {
            boolean upper = "upper".equals(properties.get("half"));
            dy = upper ? -1 : 1;
            properties.put("half", upper ? "lower" : "upper");
        }
        return new Cell(new NavCell(anchor.dimension(), Math.addExact(anchor.x(), dx),
                Math.addExact(anchor.y(), dy), Math.addExact(anchor.z(), dz)),
                new BlockStateFingerprint(state.blockId(), properties));
    }
}
