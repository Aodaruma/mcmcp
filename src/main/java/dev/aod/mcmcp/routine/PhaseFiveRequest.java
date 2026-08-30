package dev.aod.mcmcp.routine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One bounded Phase 5 operation; action-specific parsing remains adapter-owned. */
public record PhaseFiveRequest(
        String kind,
        Map<String, Object> parameters,
        PhaseFiveBounds bounds,
        int expectedUnits,
        String progressUnit) {
    public static final Set<String> KINDS = Set.of(
            "craft_items",
            "transfer_items",
            "tend_crop_area",
            "harvest_tree_area",
            "sleep_at_bed",
            "survey_area");
    private static final Set<String> ADAPTER_KINDS = Set.of(
            "craft_items",
            "transfer_items",
            "brew_known_potion_batch",
            "smelt_items",
            "tend_crop_area",
            "harvest_tree_area",
            "sleep_at_bed",
            "survey_area");

    public PhaseFiveRequest {
        Objects.requireNonNull(kind, "kind");
        if (!supportsAdapterKind(kind)) {
            throw new IllegalArgumentException("unsupported Phase 5 routine kind");
        }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(parameters, "parameters")));
        if (parameters.containsKey("kind")) {
            throw new IllegalArgumentException("parameters must not redefine the routine kind");
        }
        Objects.requireNonNull(bounds, "bounds");
        int maximumTravel = shortOperation(kind) ? 32 : 128;
        int maximumDuration = shortOperation(kind) ? 120
                : kind.equals("tend_crop_area") ? 7_200 : 600;
        if (bounds.maxTravelBlocks() > maximumTravel
                || bounds.maxDurationSeconds() > maximumDuration) {
            throw new IllegalArgumentException("bounds exceed the routine kind limit");
        }
        boolean requiresBreak = kind.equals("tend_crop_area")
                || kind.equals("harvest_tree_area");
        if (bounds.allowBreak() != requiresBreak) {
            throw new IllegalArgumentException("allowBreak does not match the routine kind");
        }
        if (expectedUnits < 0 || expectedUnits > 2_304) {
            throw new IllegalArgumentException("expected units must be in 0..2304");
        }
        Objects.requireNonNull(progressUnit, "progressUnit");
        if (progressUnit.isBlank() || progressUnit.length() > 64) {
            throw new IllegalArgumentException("progress unit must be 1..64 characters");
        }
    }

    public void validateAdmissionTick(long admittedClientTick) {
        bounds.hardDeadlineClientTick(admittedClientTick);
    }

    /** Includes internal Action adapters without widening the legacy public routine catalog. */
    public static boolean supportsAdapterKind(String kind) {
        return ADAPTER_KINDS.contains(kind);
    }

    private static boolean shortOperation(String kind) {
        return kind.equals("craft_items")
                || kind.equals("transfer_items")
                || kind.equals("brew_known_potion_batch")
                || kind.equals("smelt_items");
    }
}
