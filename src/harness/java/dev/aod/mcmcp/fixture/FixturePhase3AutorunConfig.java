package dev.aod.mcmcp.fixture;

import java.util.Locale;
import java.util.Optional;

/** Pure configuration boundary for the opt-in Phase 3/4 live-test autorun. */
record FixturePhase3AutorunConfig(Mode mode) {
    static final String MODE_PROPERTY = "mcmcp.fixture.phase3.mode";

    static Optional<FixturePhase3AutorunConfig> fromSystemProperties() {
        return parse(System.getProperty(MODE_PROPERTY));
    }

    static Optional<FixturePhase3AutorunConfig> parse(String rawMode) {
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
            case "all_satisfied" -> Mode.ALL_SATISFIED;
            case "mutations" -> Mode.MUTATIONS;
            case "waterlogged" -> Mode.WATERLOGGED;
            case "directional_stairs" -> Mode.DIRECTIONAL_STAIRS;
            case "directional_stairs_matrix" -> Mode.DIRECTIONAL_STAIRS_MATRIX;
            case "hopper" -> Mode.HOPPER;
            case "shortage" -> Mode.SHORTAGE;
            case "divergence" -> Mode.DIVERGENCE;
            case "hidden" -> Mode.HIDDEN;
            case "build_runner" -> Mode.BUILD_RUNNER;
            case "creative_capture" -> Mode.CREATIVE_CAPTURE;
            default -> throw new IllegalArgumentException(
                    "unsupported fixture autorun mode: " + sanitize(rawMode));
        };

        return Optional.of(new FixturePhase3AutorunConfig(mode));
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
        RESET(0),
        ALL_SATISFIED(0),
        MUTATIONS(0),
        WATERLOGGED(7),
        DIRECTIONAL_STAIRS(7),
        DIRECTIONAL_STAIRS_MATRIX(7),
        HOPPER(7),
        SHORTAGE(1),
        DIVERGENCE(0),
        HIDDEN(0),
        BUILD_RUNNER(1),
        CREATIVE_CAPTURE(0);

        private final int selectedSlot;

        Mode(int selectedSlot) {
            this.selectedSlot = selectedSlot;
        }

        int selectedSlot() {
            return selectedSlot;
        }

        boolean phase4() {
            return switch (this) {
                case ALL_SATISFIED, MUTATIONS, WATERLOGGED, DIRECTIONAL_STAIRS,
                        DIRECTIONAL_STAIRS_MATRIX, HOPPER, SHORTAGE, DIVERGENCE, HIDDEN,
                        BUILD_RUNNER -> true;
                case NAVIGATE, BREAK, PLACE, LEVER, COW, RESET, CREATIVE_CAPTURE -> false;
            };
        }

        boolean creativeCapture() {
            return this == CREATIVE_CAPTURE;
        }
    }
}
