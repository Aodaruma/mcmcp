package dev.aod.mcmcp.fixture;

import java.util.Locale;

/** Closed Phase 5 fixture surface shared by commands and opt-in autorun configuration. */
enum FixturePhase5Mode {
    RECIPES("recipes", 0),
    CRAFT("craft", 0),
    TRANSFER("transfer", 0),
    CROP("crop", 1),
    TREE("tree", 0),
    SLEEP("sleep", 0),
    SURVEY("survey", 0),
    IRON_FARM("iron_farm", 1),
    RESET("reset", 0);

    private final String wireName;
    private final int selectedSlot;

    FixturePhase5Mode(String wireName, int selectedSlot) {
        this.wireName = wireName;
        this.selectedSlot = selectedSlot;
    }

    static FixturePhase5Mode parse(String rawMode) {
        String normalized = rawMode.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "recipes" -> RECIPES;
            case "craft" -> CRAFT;
            case "transfer" -> TRANSFER;
            case "crop" -> CROP;
            case "tree" -> TREE;
            case "sleep" -> SLEEP;
            case "survey" -> SURVEY;
            case "iron_farm" -> IRON_FARM;
            case "reset" -> RESET;
            default -> throw new IllegalArgumentException(
                    "unsupported Phase 5 fixture mode: " + sanitize(rawMode));
        };
    }

    String wireName() {
        return wireName;
    }

    int selectedSlot() {
        return selectedSlot;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }
}
