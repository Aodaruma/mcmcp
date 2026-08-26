package dev.aod.mcmcp.agent.dsl;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Policy-filtered values available to one snapshot predicate evaluation. */
public interface PolicySnapshot {
    OptionalDouble numeric(ActionDsl.NumericField field);

    Optional<Boolean> bool(ActionDsl.BooleanField field);

    /** Empty means inventory evidence is unavailable; a known absent item is {@code OptionalInt.of(0)}. */
    OptionalInt inventoryCount(String item);

    /** Empty means status-effect evidence is unavailable. */
    Optional<Boolean> hasStatusEffect(String effect);
}
