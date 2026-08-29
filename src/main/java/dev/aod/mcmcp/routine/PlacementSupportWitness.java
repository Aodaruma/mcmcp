package dev.aod.mcmcp.routine;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit policy-visible support used by one construction placement. */
public record PlacementSupportWitness(
        BlockTarget support,
        String clickedFace,
        BlockStateFingerprint expectedState,
        Optional<String> confirmedDependencyEntryId) {
    private static final Set<String> FACES =
            Set.of("down", "up", "north", "south", "west", "east");

    public PlacementSupportWitness {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(clickedFace, "clickedFace");
        if (!FACES.contains(clickedFace)) {
            throw new IllegalArgumentException("support face must be a canonical direction");
        }
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(confirmedDependencyEntryId, "confirmedDependencyEntryId");
        confirmedDependencyEntryId = confirmedDependencyEntryId.map(value -> {
            if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("invalid support dependency entry id");
            }
            return value;
        });
    }

    public static PlacementSupportWitness visible(
            BlockTarget support, String clickedFace, BlockStateFingerprint expectedState) {
        return new PlacementSupportWitness(
                support, clickedFace, expectedState, Optional.empty());
    }

    public static PlacementSupportWitness confirmedDependency(
            BlockTarget support,
            String clickedFace,
            BlockStateFingerprint expectedState,
            String entryId) {
        return new PlacementSupportWitness(
                support, clickedFace, expectedState, Optional.of(entryId));
    }
}
