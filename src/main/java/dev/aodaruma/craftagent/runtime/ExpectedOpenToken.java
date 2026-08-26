package dev.aodaruma.craftagent.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Single-use authority for one automation-initiated container open.
 *
 * <p>The target fields are deliberately opaque to this layer. The routine adapter is responsible
 * for producing canonical values after checking the live target and raycast immediately before
 * normal-use dispatch. Keeping them on the token prevents screen identity alone from becoming an
 * ownership proof.</p>
 */
public record ExpectedOpenToken(
        UUID worldSessionId,
        UUID routineId,
        String targetIdentity,
        String targetStateFingerprint,
        String menuTypeId,
        long deadlineTick) {
    public ExpectedOpenToken {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        Objects.requireNonNull(routineId, "routineId");
        targetIdentity = boundedCanonical(targetIdentity, "targetIdentity", 512);
        targetStateFingerprint = boundedCanonical(
                targetStateFingerprint, "targetStateFingerprint", 2_048);
        menuTypeId = Objects.requireNonNull(menuTypeId, "menuTypeId");
        if (!menuTypeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid menuTypeId");
        }
        if (deadlineTick < 0) {
            throw new IllegalArgumentException("deadlineTick must be non-negative");
        }
    }

    private static String boundedCanonical(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be a bounded canonical value");
        }
        return value;
    }
}
