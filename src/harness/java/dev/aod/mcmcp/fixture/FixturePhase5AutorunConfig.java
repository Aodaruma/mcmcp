package dev.aod.mcmcp.fixture;

import java.util.Optional;

/** Pure, property-only boundary for one-shot Phase 5 harness setup. */
record FixturePhase5AutorunConfig(FixturePhase5Mode mode) {
    static final String MODE_PROPERTY = "mcmcp.fixture.phase5.mode";

    static Optional<FixturePhase5AutorunConfig> fromSystemProperties() {
        return parse(System.getProperty(MODE_PROPERTY));
    }

    static Optional<FixturePhase5AutorunConfig> parse(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new FixturePhase5AutorunConfig(FixturePhase5Mode.parse(rawMode)));
    }
}
