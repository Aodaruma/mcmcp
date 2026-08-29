package dev.aod.mcmcp.routine;

import dev.aod.mcmcp.brewing.StandardPotionPolicy;
import dev.aod.mcmcp.brewing.StandardPotionStackSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Typed request for one empty-stand, one-recipe standard Vanilla brewing batch. */
public record KnownBrewingRequest(
        BlockTarget target,
        StandardPotionStackSpec input,
        String ingredientItem,
        String fuelItem,
        StandardPotionStackSpec expectedOutput,
        float maxCameraDegreesPerTick,
        PhaseFiveRequest operation) {
    public static final int MAX_INTERACTIONS = 16;
    public static final int MAX_DURATION_SECONDS = 70;
    public static final int MAX_TICKS = 1_400;
    /** Maximum admitted |yaw|+|pitch| travel to the stand before the first mutation. */
    public static final float MAX_ONE_WAY_CAMERA_DEGREES = 270.0F;
    /** Aim plus restoration to the view admitted at this terminal node. */
    public static final float MAX_ROUND_TRIP_CAMERA_DEGREES =
            MAX_ONE_WAY_CAMERA_DEGREES * 2.0F;
    public static final float MIN_CAMERA_DEGREES_PER_TICK = 15.0F / 20.0F;
    public static final float MAX_CAMERA_DEGREES_PER_TICK = 360.0F / 20.0F;
    public static final float DEFAULT_CAMERA_DEGREES_PER_TICK = 90.0F / 20.0F;

    public KnownBrewingRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(input, "input");
        ingredientItem = resource(Objects.requireNonNull(ingredientItem, "ingredientItem"));
        fuelItem = resource(Objects.requireNonNull(fuelItem, "fuelItem"));
        Objects.requireNonNull(expectedOutput, "expectedOutput");
        Objects.requireNonNull(operation, "operation");
        if (!fuelItem.equals("minecraft:blaze_powder")) {
            throw new IllegalArgumentException("brewing fuel must be blaze powder");
        }
        if (input.count() != expectedOutput.count()) {
            throw new IllegalArgumentException("brewing input/output counts must match");
        }
        requireCameraLimit(maxCameraDegreesPerTick);
        if (!StandardPotionPolicy.isKnownOneStepRecipe(
                input, ingredientItem, expectedOutput)) {
            throw new IllegalArgumentException("brewing transition is outside the closed policy");
        }
        if (!operation.kind().equals("brew_known_potion_batch")
                || operation.expectedUnits() != expectedOutput.count()
                || !operation.progressUnit().equals("standard_potions")
                || !operation.bounds().contains(target)
                || operation.bounds().maxTravelBlocks() != 0
                || operation.bounds().allowBreak()
                || operation.bounds().maxDurationSeconds() != MAX_DURATION_SECONDS
                || !cameraParameterMatches(operation, maxCameraDegreesPerTick)) {
            throw new IllegalArgumentException("brewing operation bounds are invalid");
        }
    }

    public KnownBrewingRequest(
            BlockTarget target,
            StandardPotionStackSpec input,
            String ingredientItem,
            String fuelItem,
            StandardPotionStackSpec expectedOutput) {
        this(target, input, ingredientItem, fuelItem, expectedOutput,
                DEFAULT_CAMERA_DEGREES_PER_TICK);
    }

    public KnownBrewingRequest(
            BlockTarget target,
            StandardPotionStackSpec input,
            String ingredientItem,
            String fuelItem,
            StandardPotionStackSpec expectedOutput,
            float maxCameraDegreesPerTick) {
        this(target, input, ingredientItem, fuelItem, expectedOutput,
                maxCameraDegreesPerTick,
                operation(target, input, ingredientItem, fuelItem, expectedOutput,
                        maxCameraDegreesPerTick));
    }

    private static PhaseFiveRequest operation(
            BlockTarget target,
            StandardPotionStackSpec input,
            String ingredientItem,
            String fuelItem,
            StandardPotionStackSpec output,
            float maxCameraDegreesPerTick) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("target", targetMap(target));
        parameters.put("input", potionMap(input));
        parameters.put("ingredient_item", ingredientItem);
        parameters.put("fuel_item", fuelItem);
        parameters.put("expected_output", potionMap(output));
        parameters.put("max_camera_degrees_per_tick", maxCameraDegreesPerTick);
        return new PhaseFiveRequest(
                "brew_known_potion_batch",
                parameters,
                new PhaseFiveBounds(
                        target.dimension(), target, target, 0, MAX_DURATION_SECONDS, false),
                output.count(),
                "standard_potions");
    }

    private static boolean cameraParameterMatches(
            PhaseFiveRequest operation, float maxCameraDegreesPerTick) {
        Object value = operation.parameters().get("max_camera_degrees_per_tick");
        return value instanceof Number number
                && Double.compare(number.doubleValue(), maxCameraDegreesPerTick) == 0;
    }

    private static void requireCameraLimit(float value) {
        if (!Float.isFinite(value)
                || value < MIN_CAMERA_DEGREES_PER_TICK
                || value > MAX_CAMERA_DEGREES_PER_TICK) {
            throw new IllegalArgumentException(
                    "brewing camera limit must be in 0.75..18 degrees per tick");
        }
    }

    private static Map<String, Object> targetMap(BlockTarget target) {
        return Map.of(
                "dimension", target.dimension(),
                "x", target.x(),
                "y", target.y(),
                "z", target.z());
    }

    private static Map<String, Object> potionMap(StandardPotionStackSpec potion) {
        return Map.of(
                "item", potion.item(),
                "potion", potion.potion(),
                "count", potion.count());
    }

    private static String resource(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.length() > 128) {
            throw new IllegalArgumentException("invalid resource location");
        }
        return value;
    }
}
