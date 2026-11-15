package dev.aod.mcmcp.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Current vanilla XP reconstructed from the synchronized HUD, not lifetime totalExperience. */
final class PlayerExperience {
    private PlayerExperience() { }

    static Map<String, Object> payload(int level, float progress, int needed) {
        if (level < 0 || !Float.isFinite(progress) || progress < 0 || progress > 1 || needed < 1) return null;
        long inLevel = (long) Math.floor(progress * needed);
        Long points = null;
        try {
            long square = Math.multiplyExact((long) level, level);
            long base = level <= 16 ? square + 6L * level
                    : level <= 31 ? (5L * square - 81L * level + 720L) / 2
                    : Math.addExact(Math.multiplyExact(9L, square), -325L * level + 4440L) / 2;
            long total = Math.addExact(base, inLevel);
            if (total <= 9_007_199_254_740_991L) points = total;
        } catch (ArithmeticException outOfRange) {
            // A modded level outside exact JSON integer range cannot provide a reliable point balance.
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("level", level);
        result.put("progress", progress);
        result.put("points", points);
        result.put("points_to_next_level", Math.max(0L, needed - inLevel));
        result.put("points_source", "vanilla_level_and_progress");
        return Collections.unmodifiableMap(result);
    }
}
