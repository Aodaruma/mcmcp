package dev.aod.mcmcp.routine;

/** Closed Phase 3 action family; no arbitrary predicate or low-level input is accepted. */
public sealed interface SemanticActionRequest permits NavigateToRequest, BreakBlockRequest,
        PlaceBlockRequest, UseItemOnBlockRequest, InteractBlockRequest, InteractEntityRequest {
    String kind();

    ActionBounds bounds();

    default void validateAdmissionTick(long admittedClientTick) {
        bounds().hardDeadlineClientTick(admittedClientTick);
    }
}
