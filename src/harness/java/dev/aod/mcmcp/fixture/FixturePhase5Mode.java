package dev.aod.mcmcp.fixture;

import java.util.Locale;

/** Closed Phase 5 fixture surface shared by commands and opt-in autorun configuration. */
enum FixturePhase5Mode {
    RECIPES("recipes", 0, false),
    CRAFT("craft", 0, true),
    TRANSFER("transfer", 0, true),
    CROP("crop", 1, true),
    TREE("tree", 0, true),
    SLEEP("sleep", 0, true),
    SURVEY("survey", 0, true),
    IRON_FARM("iron_farm", 1, true),
    RESET("reset", 0, false);

    private final String wireName;
    private final int selectedSlot;
    private final boolean routine;

    FixturePhase5Mode(String wireName, int selectedSlot, boolean routine) {
        this.wireName = wireName;
        this.selectedSlot = selectedSlot;
        this.routine = routine;
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

    boolean routine() {
        return routine;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }
}
