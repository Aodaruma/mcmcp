package dev.aod.mcmcp.observation;

public enum ObservationProvenance {
    LINE_OF_SIGHT_OBSERVATION("line_of_sight_observation"),
    CROSSHAIR_OBSERVATION("crosshair_observation"),
    INTERACTION_CONFIRMATION("interaction_confirmation");

    private final String wireName;

    ObservationProvenance(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
