package dev.aodaruma.craftagent.fixture;

import java.util.Optional;

/** Pure, property-only boundary for a future one-shot Phase 5 harness autorun. */
record FixturePhase5AutorunConfig(FixturePhase5Mode mode, boolean autoArm) {
    static final String MODE_PROPERTY = "craftagent.fixture.phase5.mode";
    static final String AUTO_ARM_PROPERTY = "craftagent.fixture.phase5.autoArm";

    static Optional<FixturePhase5AutorunConfig> fromSystemProperties() {
        return parse(System.getProperty(MODE_PROPERTY), System.getProperty(AUTO_ARM_PROPERTY));
    }

    static Optional<FixturePhase5AutorunConfig> parse(String rawMode, String rawAutoArm) {
        if (rawMode == null || rawMode.isBlank()) {
            return Optional.empty();
        }
        FixturePhase5Mode mode = FixturePhase5Mode.parse(rawMode);
        boolean requestedAutoArm = parseBoolean(rawAutoArm);
        return Optional.of(new FixturePhase5AutorunConfig(
                mode, requestedAutoArm && mode.routine()));
    }

    private static boolean parseBoolean(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || "false".equalsIgnoreCase(rawValue.strip())) {
            return false;
        }
        if ("true".equalsIgnoreCase(rawValue.strip())) {
            return true;
        }
        throw new IllegalArgumentException(
                "invalid " + AUTO_ARM_PROPERTY + " value: " + sanitize(rawValue));
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }
}
