package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 要求の解析とimmutableな在庫操作条件。Minecraftへの操作やattempt状態を持たない。 */
final class InventoryParameters {
    private InventoryParameters() {}

    static final String CRAFT_ITEMS = "craft_items";

    static final String TRANSFER_ITEMS = "transfer_items";

    static final String CRAFTING_MENU = "minecraft:crafting";

    static final String SINGLE_CONTAINER_MENU = "minecraft:generic_9x3";

    static final String DOUBLE_CONTAINER_MENU = "minecraft:generic_9x6";

    private static final float LEGACY_MAX_TURN_PER_TICK = 8.0F;

    static ParsedParameters parse(PhaseFiveRequest request) {
        return switch (request.kind()) {
            case CRAFT_ITEMS -> parseCraft(request.parameters());
            case TRANSFER_ITEMS -> parseTransfer(request.parameters());
            default -> throw new IllegalArgumentException("unsupported inventory routine kind");
        };
    }

    private static CraftParameters parseCraft(Map<String, Object> parameters) {
        var station = map(parameters.get("station"), "station");
        String stationKind = string(station.get("kind"), "station.kind");
        if ("inventory_2x2".equals(stationKind)) {
            throw new IllegalArgumentException(
                    "inventory_2x2 is not supported because strict close/reopen readback is unavailable");
        }
        if (!"crafting_table".equals(stationKind)) {
            throw new IllegalArgumentException("unsupported crafting station");
        }
        var goal = map(parameters.get("goal"), "goal");
        requireDefaultComponents(goal, "goal");
        var target = target(map(station.get("target"), "station.target"));
        return new CraftParameters(
                string(parameters.get("recipe_ref"), "recipe_ref"),
                string(parameters.get("recipe_fingerprint"), "recipe_fingerprint"),
                string(goal.get("item"), "goal.item"),
                integer(goal.get("minimum_inventory_count"), "goal.minimum_inventory_count"),
                integer(parameters.get("max_crafts"), "max_crafts"),
                target,
                state(map(station.get("expected_state"), "station.expected_state")));
    }

    private static TransferParameters parseTransfer(Map<String, Object> parameters) {
        var container = map(parameters.get("container"), "container");
        var stack = map(parameters.get("stack"), "stack");
        var goal = map(parameters.get("goal"), "goal");
        String stackPolicy = string(stack.get("stack_policy"), "stack.stack_policy");
        if (!"default_components_only".equals(stackPolicy)
                && !"item_id_any_components".equals(stackPolicy)) {
            throw new IllegalArgumentException("unsupported transfer stack policy");
        }
        String direction = string(parameters.get("direction"), "direction");
        if (!"player_to_container".equals(direction)
                && !"container_to_player".equals(direction)) {
            throw new IllegalArgumentException("unsupported transfer direction");
        }
        return new TransferParameters(
                "player_to_container".equals(direction),
                string(stack.get("item"), "stack.item"),
                stackPolicy,
                integer(goal.get("minimum_destination_count"),
                        "goal.minimum_destination_count"),
                integer(parameters.get("max_transfer_count"), "max_transfer_count"),
                parameters.containsKey("max_stack_moves")
                        ? integer(parameters.get("max_stack_moves"), "max_stack_moves")
                        : 1,
                Boolean.TRUE.equals(parameters.get("retain_view_on_release")),
                parameters.containsKey("max_camera_degrees_per_tick")
                        ? finiteNumber(parameters.get("max_camera_degrees_per_tick"),
                                "max_camera_degrees_per_tick")
                        : LEGACY_MAX_TURN_PER_TICK,
                target(map(container.get("target"), "container.target")),
                state(map(container.get("expected_state"), "container.expected_state")),
                routingLabel(parameters));
    }

    static Optional<RoutingLabelParameters> routingLabel(
            Map<String, Object> parameters) {
        if (!parameters.containsKey("routing_label")) return Optional.empty();
        Map<String, Object> label = map(parameters.get("routing_label"), "routing_label");
        if (!label.keySet().equals(java.util.Set.of("entity_ref", "item"))) {
            throw new IllegalArgumentException("routing_label has an invalid shape");
        }
        return Optional.of(new RoutingLabelParameters(
                string(label.get("entity_ref"), "routing_label.entity_ref"),
                string(label.get("item"), "routing_label.item")));
    }

    private static void requireDefaultComponents(Map<String, Object> source, String name) {
        if (!"default_components_only".equals(
                string(source.get("stack_policy"), name + ".stack_policy"))) {
            throw new IllegalArgumentException(name + " requires default_components_only");
        }
    }

    static BlockTarget target(Map<String, Object> value) {
        return new BlockTarget(
                string(value.get("dimension"), "dimension"),
                integer(value.get("x"), "x"),
                integer(value.get("y"), "y"),
                integer(value.get("z"), "z"));
    }

    static BlockStateFingerprint state(Map<String, Object> value) {
        var properties = new LinkedHashMap<String, String>();
        for (var entry : map(value.get("properties"), "properties").entrySet()) {
            properties.put(entry.getKey(), string(entry.getValue(), "property value"));
        }
        return new BlockStateFingerprint(
                string(value.get("block"), "block"), properties);
    }

    static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long longValue = number.longValue();
        if (number.doubleValue() != longValue
                || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) longValue;
    }

    private static double finiteNumber(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
    }

    static Vec3 inventoryAimPoint(PhaseFiveRequest request, BlockTarget target) {
        Object raw = request.parameters().get("aim_point");
        if (raw == null) {
            return new Vec3(target.x() + 0.5D, target.y() + 0.5D, target.z() + 0.5D);
        }
        Map<String, Object> point = map(raw, "aim_point");
        if (!point.keySet().equals(java.util.Set.of("dimension", "x", "y", "z"))) {
            throw new IllegalArgumentException("aim_point fields are invalid");
        }
        if (!target.dimension().equals(string(point.get("dimension"), "aim_point.dimension"))) {
            throw new IllegalArgumentException("aim_point dimension does not match target");
        }
        double x = finiteNumber(point.get("x"), "aim_point.x");
        double y = finiteNumber(point.get("y"), "aim_point.y");
        double z = finiteNumber(point.get("z"), "aim_point.z");
        if (x < target.x() || x > target.x() + 1.0D
                || y < target.y() || y > target.y() + 1.0D
                || z < target.z() || z > target.z() + 1.0D) {
            throw new IllegalArgumentException("aim_point is outside its target block");
        }
        return new Vec3(x, y, z);
    }

    sealed interface ParsedParameters permits CraftParameters, TransferParameters {
        BlockTarget target();

        BlockStateFingerprint expectedState();

        String menuTypeId();

        default boolean restoreViewOnRelease() {
            return true;
        }

        default float maxCameraDegreesPerTick() {
            return LEGACY_MAX_TURN_PER_TICK;
        }

        default Optional<RoutingLabelParameters> routingLabel() {
            return Optional.empty();
        }
    }

    record CraftParameters(
            String recipeRef,
            String recipeFingerprint,
            String goalItem,
            int minimumInventoryCount,
            int maxCrafts,
            BlockTarget target,
            BlockStateFingerprint expectedState) implements ParsedParameters {
        CraftParameters {
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(goalItem, "goalItem");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            if (minimumInventoryCount < 1 || minimumInventoryCount > 2_304
                    || maxCrafts < 1 || maxCrafts > 64) {
                throw new IllegalArgumentException("craft limits are outside the v1 contract");
            }
        }

        @Override
        public String menuTypeId() {
            return CRAFTING_MENU;
        }

        @Override
        public boolean restoreViewOnRelease() {
            return false;
        }
    }

    record TransferParameters(
            boolean playerToContainer,
            String item,
            String stackPolicy,
            int minimumDestinationCount,
            int maxTransferCount,
            int maxStackMoves,
            boolean retainViewOnRelease,
            double cameraDegreesPerTick,
            BlockTarget target,
            BlockStateFingerprint expectedState,
            Optional<RoutingLabelParameters> routingLabel) implements ParsedParameters {
        TransferParameters {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(routingLabel, "routingLabel");
            if (!"default_components_only".equals(stackPolicy)
                    && !"item_id_any_components".equals(stackPolicy)) {
                throw new IllegalArgumentException("unsupported transfer stack policy");
            }
            if (minimumDestinationCount < 0
                    || minimumDestinationCount > (playerToContainer ? 3_456 : 2_304)
                    || maxTransferCount < 1 || maxTransferCount > 896
                    || maxStackMoves < 1 || maxStackMoves > 14
                    || !Double.isFinite(cameraDegreesPerTick)
                    || cameraDegreesPerTick < 0.1D || cameraDegreesPerTick > 18.0D) {
                throw new IllegalArgumentException("transfer limits are outside the v1 contract");
            }
        }

        TransferParameters(
                boolean playerToContainer,
                String item,
                String stackPolicy,
                int minimumDestinationCount,
                int maxTransferCount,
                int maxStackMoves,
                boolean retainViewOnRelease,
                double cameraDegreesPerTick,
                BlockTarget target,
                BlockStateFingerprint expectedState) {
            this(playerToContainer, item, stackPolicy, minimumDestinationCount,
                    maxTransferCount, maxStackMoves, retainViewOnRelease,
                    cameraDegreesPerTick, target, expectedState, Optional.empty());
        }

        @Override
        public String menuTypeId() {
            return transferMenuType(expectedState);
        }

        boolean defaultComponentsOnly() {
            return "default_components_only".equals(stackPolicy);
        }

        @Override
        public boolean restoreViewOnRelease() {
            return !retainViewOnRelease;
        }

        @Override
        public float maxCameraDegreesPerTick() {
            return (float) cameraDegreesPerTick;
        }
    }

    record RoutingLabelParameters(String entityRef, String item) {
        RoutingLabelParameters {
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(item, "item");
            if (!entityRef.matches("[A-Za-z0-9_-]{24}")
                    || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || item.length() > 128) {
                throw new IllegalArgumentException("invalid routing label");
            }
        }
    }

    static String transferMenuType(BlockStateFingerprint expectedState) {
        if (KnownContainerPolicy.isBarrel(expectedState.blockId())) {
            return SINGLE_CONTAINER_MENU;
        }
        if (KnownContainerPolicy.isChest(expectedState.blockId())) {
            String type = expectedState.properties().get("type");
            if (ChestType.SINGLE.getSerializedName().equals(type)) {
                return SINGLE_CONTAINER_MENU;
            }
            if (ChestType.LEFT.getSerializedName().equals(type)
                    || ChestType.RIGHT.getSerializedName().equals(type)) {
                return DOUBLE_CONTAINER_MENU;
            }
            throw new IllegalArgumentException("unsupported chest type");
        }
        throw new IllegalArgumentException("unsupported vanilla storage container");
    }
}
