package dev.aod.mcmcp.brewing;

import java.util.Objects;

/** Public-safe identity of one unmodified Vanilla potion stack family. */
public record StandardPotionStackSpec(String item, String potion, int count) {
    public StandardPotionStackSpec {
        item = resource(Objects.requireNonNull(item, "item"));
        potion = resource(Objects.requireNonNull(potion, "potion"));
        if (!item.equals("minecraft:potion")
                && !item.equals("minecraft:splash_potion")
                && !item.equals("minecraft:lingering_potion")) {
            throw new IllegalArgumentException("unsupported standard potion container");
        }
        if (count < 1 || count > 3) {
            throw new IllegalArgumentException("potion count must be in 1..3");
        }
    }

    private static String resource(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.length() > 128) {
            throw new IllegalArgumentException("invalid resource location");
        }
        return value;
    }
}
