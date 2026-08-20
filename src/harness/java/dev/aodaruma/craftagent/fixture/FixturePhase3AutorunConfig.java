package dev.aodaruma.craftagent.fixture;

import java.util.Locale;
import java.util.Optional;

/** Pure configuration boundary for the opt-in Phase 3 live-test autorun. */
record FixturePhase3AutorunConfig(Mode mode, boolean autoArm) {
    static final String MODE_PROPERTY = "craftagent.fixture.phase3.mode";
    static final String AUTO_ARM_PROPERTY = "craftagent.fixture.phase3.autoArm";

    static Optional<FixturePhase3AutorunConfig> fromSystemProperties() {
        return parse(System.getProperty(MODE_PROPERTY), System.getProperty(AUTO_ARM_PROPERTY));
    }

    static Optional<FixturePhase3AutorunConfig> parse(String rawMode, String rawAutoArm) {
        if (rawMode == null || rawMode.isBlank()) {
            return Optional.empty();
        }

        Mode mode = switch (rawMode.strip().toLowerCase(Locale.ROOT)) {
            case "navigate" -> Mode.NAVIGATE;
            case "break" -> Mode.BREAK;
            case "place" -> Mode.PLACE;
            case "lever" -> Mode.LEVER;
            case "cow" -> Mode.COW;
            case "reset" -> Mode.RESET;
            default -> throw new IllegalArgumentException(
                    "unsupported Phase 3 fixture mode: " + sanitize(rawMode));
        };

        boolean requestedAutoArm = parseBoolean(rawAutoArm);
        return Optional.of(new FixturePhase3AutorunConfig(
                mode,
                requestedAutoArm && mode != Mode.RESET));
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

    enum Mode {
        NAVIGATE(0),
        BREAK(0),
        PLACE(1),
        LEVER(6),
        COW(0),
        RESET(0);

        private final int selectedSlot;

        Mode(int selectedSlot) {
            this.selectedSlot = selectedSlot;
        }

        int selectedSlot() {
            return selectedSlot;
        }
    }
}
