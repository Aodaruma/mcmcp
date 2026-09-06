package dev.aod.mcmcp.fixture;

import java.util.Locale;

/** Closed Phase 5 fixture surface shared by commands and opt-in autorun configuration. */
enum FixturePhase5Mode {
    RECIPES("recipes", 0),
    CRAFT("craft", 0),
    SMELT("smelt", 0),
    WAREHOUSE_SMELT("warehouse_smelt", 0),
    LABEL_TRANSFER("label_transfer", 0),
    COPPER_TRANSFER("copper_transfer", 0),
    BREW("brew", 0),
    REDSTONE("redstone", 0),
    TRANSFER("transfer", 0),
    CONTAINER_BATCH_SUCCESS("container_batch_success", 0),
    CONTAINER_BATCH_PARTIAL("container_batch_partial", 0),
    CROP("crop", 1),
    COMBINED_WHEAT("combined_wheat", 0),
    TREE("tree", 0),
    SLEEP("sleep", 0),
    SURVEY("survey", 0),
    GENERALIZATION("generalization", 0),
    BOUNDED_INPUT_HOLD("bounded_input_hold", 0),
    COBBLESTONE_GENERATOR("cobblestone_generator", 0),
    FISHING("fishing", 0),
    KILL_ZONE("kill_zone", 0),
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
            case "smelt" -> SMELT;
            case "warehouse_smelt" -> WAREHOUSE_SMELT;
            case "label_transfer" -> LABEL_TRANSFER;
            case "copper_transfer" -> COPPER_TRANSFER;
            case "brew" -> BREW;
            case "redstone" -> REDSTONE;
            case "transfer" -> TRANSFER;
            case "container_batch_success" -> CONTAINER_BATCH_SUCCESS;
            case "container_batch_partial" -> CONTAINER_BATCH_PARTIAL;
            case "crop" -> CROP;
            case "combined_wheat" -> COMBINED_WHEAT;
            case "tree" -> TREE;
            case "sleep" -> SLEEP;
            case "survey" -> SURVEY;
            case "generalization" -> GENERALIZATION;
            case "bounded_input_hold" -> BOUNDED_INPUT_HOLD;
            case "cobblestone_generator" -> COBBLESTONE_GENERATOR;
            case "fishing" -> FISHING;
            case "kill_zone" -> KILL_ZONE;
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
