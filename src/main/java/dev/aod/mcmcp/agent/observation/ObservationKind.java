package dev.aod.mcmcp.agent.observation;

import java.util.Arrays;

/** The fixed record kinds exposed by {@code agent_get_observation}. */
public enum ObservationKind {
    VISIBLE_SURFACE("visible_surface"),
    VISIBLE_ENTITY("visible_entity"),
    TRAVERSABILITY("traversability"),
    HAZARD("hazard"),
    UNKNOWN_BOUNDARY("unknown_boundary"),
    SOUND_CLUE("sound_clue");

    private final String wireName;

    ObservationKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ObservationKind fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(kind -> kind.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown observation kind"));
    }
}
