package dev.aod.mcmcp.observation;

import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Safely transforms partial BlockState constraints without inventing omitted properties. */
public final class BlockPlanStateTransformer {
    private BlockPlanStateTransformer() {
    }

    public static BlockStateView transform(
            BlockStateView source,
            BlockPlan.Transform transform) {
        return transform(source, transform, "state");
    }

    /**
     * Validates and transforms one complete runtime BlockState representation.
     *
     * <p>Unlike {@link #transform(BlockStateView, BlockPlan.Transform)}, this entry point rejects
     * any input that omits a property defined by the registered block. It is intended for plan
     * application, where silently supplying a default property would place a state the caller did
     * not request. Property-free blocks such as air are valid with an empty property map.</p>
     */
    public static BlockStateView transformFull(
            BlockStateView source,
            BlockPlan.Transform transform) {
        return transformFull(source, transform, "state");
    }

    /** Complete-state transform with a caller-owned path for structured diagnostics. */
    @SuppressWarnings("deprecation") // BlockState is the only public path to block-specific rotate/mirror rules.
    public static BlockStateView transformFull(
            BlockStateView source,
            BlockPlan.Transform transform,
            String path) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(transform, "transform");
        requirePath(path);

        RegisteredInput input = registeredInput(source, path);
        Set<String> registeredNames = input.properties().keySet();
        if (!source.properties().keySet().equals(registeredNames)) {
            var missing = new ArrayList<>(registeredNames);
            missing.removeAll(source.properties().keySet());
            missing.sort(String::compareTo);
            throw invalid(
                    "incomplete_block_state", path + ".properties",
                    "a complete runtime BlockState must declare every registered property",
                    Map.of(
                            "block", source.block(),
                            "missing_properties", String.join(",", missing),
                            "expected_property_count", registeredNames.size(),
                            "actual_property_count", source.properties().size()));
        }

        BlockState matched = null;
        for (BlockState candidate : input.definition().getPossibleStates()) {
            if (!matches(candidate, source.properties(), input.properties())) {
                continue;
            }
            if (matched != null) {
                throw new IllegalStateException(
                        "complete registered BlockState properties resolved more than one state");
            }
            matched = candidate;
        }
        if (matched == null) {
            throw invalid(
                    "invalid_block_state", path,
                    "no runtime BlockState matches all requested properties",
                    Map.of("block", source.block()));
        }

        BlockState transformed = transformState(matched, transform);
        return new BlockStateView(source.block(), project(transformed, input.properties()));
    }

    /** Same transform with a caller-owned bounded path for structured operation diagnostics. */
    @SuppressWarnings("deprecation") // BlockState is the only public path to block-specific rotate/mirror rules.
    public static BlockStateView transform(
            BlockStateView source,
            BlockPlan.Transform transform,
            String path) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(transform, "transform");
        requirePath(path);
        RegisteredInput input = registeredInput(source, path);
        var requestedProperties = new LinkedHashMap<String, Property<?>>();
        for (String propertyName : source.properties().keySet()) {
            requestedProperties.put(propertyName, input.properties().get(propertyName));
        }

        var projections = new ArrayList<Map<String, String>>();
        for (BlockState candidate : input.definition().getPossibleStates()) {
            if (!matches(candidate, source.properties(), requestedProperties)) {
                continue;
            }
            BlockState transformed = transformState(candidate, transform);
            projections.add(project(transformed, requestedProperties));
        }
        if (projections.isEmpty()) {
            throw invalid(
                    "invalid_block_state", path,
                    "no runtime BlockState matches all requested property constraints",
                    Map.of("block", source.block()));
        }
        return new BlockStateView(
                source.block(),
                requireUniqueProjection(source.block(), transform, path, projections));
    }

    private static RegisteredInput registeredInput(BlockStateView source, String path) {
        Identifier identifier = Identifier.tryParse(source.block());
        Optional<Holder.Reference<Block>> registeredBlock = identifier == null
                ? Optional.empty()
                : BuiltInRegistries.BLOCK.get(identifier);
        if (registeredBlock.isEmpty()) {
            throw invalid(
                    "unknown_block", path + ".block", "block is not present in the runtime registry",
                    Map.of("block", source.block()));
        }
        var definition = registeredBlock.orElseThrow().value().getStateDefinition();
        var properties = new LinkedHashMap<String, Property<?>>();
        for (Property<?> property : definition.getProperties()) {
            properties.put(property.getName(), property);
        }
        for (var entry : source.properties().entrySet()) {
            var property = properties.get(entry.getKey());
            if (property == null) {
                throw invalid(
                        "unknown_block_property", path + ".properties." + entry.getKey(),
                        "property is not defined by the requested block",
                        Map.of("block", source.block(), "property", entry.getKey()));
            }
            if (property.getValue(entry.getValue()).isEmpty()) {
                throw invalid(
                        "invalid_block_property_value", path + ".properties." + entry.getKey(),
                        "property value is not accepted by the requested block",
                        Map.of("block", source.block(), "property", entry.getKey(), "value", entry.getValue()));
            }
        }
        return new RegisteredInput(definition, Map.copyOf(properties));
    }

    private static void requirePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || path.length() > 160) {
            throw new IllegalArgumentException("state transform path must contain 1..160 characters");
        }
    }

    /** Pure ambiguity gate kept package-visible so its fail-closed rule is directly testable. */
    static Map<String, String> requireUniqueProjection(
            String block,
            BlockPlan.Transform transform,
            String path,
            List<Map<String, String>> projections) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(projections, "projections");
        if (projections.isEmpty()) {
            throw new IllegalArgumentException("at least one state projection is required");
        }
        var unique = Map.copyOf(projections.getFirst());
        if (projections.stream().skip(1).anyMatch(projection -> !unique.equals(projection))) {
            throw invalid(
                    "ambiguous_state_transform", path + ".properties",
                    "omitted properties make the transformed constraints ambiguous",
                    Map.of(
                            "block", block,
                            "rotation", transform.rotation(),
                            "mirror", transform.mirror()));
        }
        return unique;
    }

    private static boolean matches(
            BlockState state,
            Map<String, String> requested,
            Map<String, Property<?>> properties) {
        for (var entry : requested.entrySet()) {
            if (!entry.getValue().equals(valueName(state, properties.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> project(
            BlockState transformed,
            Map<String, Property<?>> properties) {
        var result = new TreeMap<String, String>();
        for (var entry : properties.entrySet()) {
            result.put(entry.getKey(), valueName(transformed, entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static String valueName(BlockState state, Property<?> property) {
        return capturedValueName(state, property);
    }

    private static <T extends Comparable<T>> String capturedValueName(
            BlockState state,
            Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Mirror minecraftMirror(String mirror) {
        return switch (mirror) {
            case "none" -> Mirror.NONE;
            // FRONT_BACK reverses east/west, matching the plan's x -> -x transform.
            case "x" -> requireDirectionMirror(
                    Mirror.FRONT_BACK, Direction.EAST, Direction.WEST, "x");
            // LEFT_RIGHT reverses north/south, matching the plan's z -> -z transform.
            case "z" -> requireDirectionMirror(
                    Mirror.LEFT_RIGHT, Direction.NORTH, Direction.SOUTH, "z");
            default -> throw new AssertionError(mirror);
        };
    }

    private static Mirror requireDirectionMirror(
            Mirror mirror,
            Direction input,
            Direction expected,
            String planMirror) {
        if (mirror.mirror(input) != expected) {
            throw new IllegalStateException(
                    "Minecraft mirror semantics no longer match plan mirror " + planMirror);
        }
        return mirror;
    }

    private static Rotation minecraftRotation(int rotation) {
        return switch (rotation) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new AssertionError(rotation);
        };
    }

    /**
     * Applies the spatial transform, correcting the neighbour-derived stair handedness so it
     * agrees with the mirrored component offsets. Vanilla's standalone StairBlock mirror rule
     * intentionally depends on facing-axis branches and is not a complete component transform.
     */
    @SuppressWarnings("deprecation")
    private static BlockState transformState(BlockState source, BlockPlan.Transform transform) {
        BlockState transformed = source
                .mirror(minecraftMirror(transform.mirror()))
                .rotate(minecraftRotation(transform.rotation()));
        if (source.getBlock() instanceof StairBlock && !"none".equals(transform.mirror())) {
            transformed = transformed.setValue(
                    BlockStateProperties.STAIRS_SHAPE,
                    mirroredStairShape(source.getValue(BlockStateProperties.STAIRS_SHAPE)));
        }
        return transformed;
    }

    private static StairsShape mirroredStairShape(StairsShape source) {
        return switch (source) {
            case STRAIGHT -> StairsShape.STRAIGHT;
            case INNER_LEFT -> StairsShape.INNER_RIGHT;
            case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
            case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
        };
    }

    private static BlockPlanValidationException invalid(
            String code,
            String path,
            String message,
            Map<String, Object> details) {
        return new BlockPlanValidationException(code, path, message, details);
    }

    private record RegisteredInput(
            net.minecraft.world.level.block.state.StateDefinition<Block, BlockState> definition,
            Map<String, Property<?>> properties) {
    }
}
