package dev.aodaruma.craftagent.observation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Same-tick, provenance-preserving comparison of a bounded relative block plan. */
public final class BlockPlanComparator {
    public static final int MAX_EXPECTED_BLOCKS = 512;

    private final MinecraftObservationService observations;
    private final WorldMemory memory;

    public BlockPlanComparator(MinecraftObservationService observations, WorldMemory memory) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public Map<String, Object> compare(Minecraft minecraft, long clientTick, Map<String, Object> arguments) {
        var plan = parse(arguments);
        var level = minecraft.level;
        if (level == null || memory.sessionId() == null) {
            throw new MinecraftObservationService.ObservationUnavailableException("no_world", "No client world is ready");
        }
        var currentDimension = level.dimension().identifier().toString();
        if (!plan.anchor().dimension().equals(currentDimension)) {
            throw new IllegalArgumentException("anchor dimension must equal the current dimension");
        }

        var positions = plan.expected().stream()
                .map(Expected::worldPosition)
                .map(position -> new BlockPos(position.x(), position.y(), position.z()))
                .toList();
        var samples = observations.observeBlocks(
                minecraft,
                clientTick,
                positions,
                MinecraftObservationService.BlockSource.LIVE_AND_MEMORY);

        var coverage = new Counter();
        var summary = new LinkedHashMap<String, Integer>();
        for (var name : List.of("match_current", "mismatch_current", "match_last_known",
                "mismatch_last_known", "unknown")) {
            summary.put(name, 0);
        }
        var differences = new ArrayList<Map<String, Object>>();
        var required = new Counter();

        for (int index = 0; index < plan.expected().size(); index++) {
            var expected = plan.expected().get(index);
            var sample = samples.get(index);
            var outcome = classify(expected, sample);
            summary.compute(outcome.result(), (ignored, value) -> value + 1);
            coverage.record(sample.outcome());
            if (expected.required()) {
                required.total++;
                if ("match_current".equals(outcome.result())) {
                    required.current++;
                } else if ("unknown".equals(outcome.result())) {
                    required.unknown++;
                } else {
                    required.mismatch++;
                }
            }
            if (!outcome.match() || plan.includeMatches()) {
                differences.add(difference(expected, sample, outcome));
            }
        }

        var basis = new LinkedHashMap<String, Object>();
        basis.put("world_session_id", memory.sessionId().toString());
        basis.put("dimension", currentDimension);
        basis.put("client_tick", clientTick);
        basis.put("observation_revision", memory.revision());

        var result = new LinkedHashMap<String, Object>();
        result.put("plan_hash", plan.hash());
        result.put("basis", basis);
        result.put("coverage", Map.of(
                "requested", plan.expected().size(),
                "current", coverage.current,
                "last_known", coverage.lastKnown,
                "unknown", coverage.unknown));
        result.put("summary", summary);
        result.put("required_verification", Map.of(
                "total", required.total,
                "match_current", required.current,
                "mismatch_or_stale", required.mismatch,
                "unknown", required.unknown,
                "complete", required.total == required.current && required.unknown == 0));
        result.put("differences", List.copyOf(differences));
        return result;
    }

    Plan parse(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        var anchorInput = map(arguments.get("anchor"), "anchor");
        var anchor = new BlockPosition(
                string(anchorInput.get("dimension"), "anchor.dimension"),
                integerInRange(anchorInput.get("x"), "anchor.x", -30_000_000, 29_999_999),
                integerInRange(anchorInput.get("y"), "anchor.y", -2_048, 2_047),
                integerInRange(anchorInput.get("z"), "anchor.z", -30_000_000, 29_999_999));

        var transformInput = optionalMap(arguments.get("transform"));
        var transform = new Transform(
                optionalInteger(transformInput.get("rotation"), 0, "transform.rotation"),
                optionalString(transformInput.get("mirror"), "none"));
        var includeMatches = optionalBoolean(arguments.get("include_matches"), false, "include_matches");

        if (!(arguments.get("expected") instanceof List<?> rawExpected)) {
            throw new IllegalArgumentException("expected must be an array");
        }
        if (rawExpected.isEmpty() || rawExpected.size() > MAX_EXPECTED_BLOCKS) {
            throw new IllegalArgumentException("expected must contain 1.." + MAX_EXPECTED_BLOCKS + " entries");
        }
        var ids = new HashSet<String>();
        var coordinates = new HashSet<BlockPos>();
        var expected = new ArrayList<Expected>(rawExpected.size());
        for (Object raw : rawExpected) {
            var entry = map(raw, "expected item");
            var id = string(entry.get("id"), "expected.id");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}") || !ids.add(id)) {
                throw new IllegalArgumentException("expected.id must be unique and contain 1..96 characters");
            }
            var offsetInput = map(entry.get("offset"), "expected.offset");
            var rawOffset = new Offset(
                    integerInRange(offsetInput.get("x"), "offset.x", -4_096, 4_096),
                    integerInRange(offsetInput.get("y"), "offset.y", -4_096, 4_096),
                    integerInRange(offsetInput.get("z"), "offset.z", -4_096, 4_096));
            var offset = transform.apply(rawOffset);
            int worldX = Math.addExact(anchor.x(), offset.x());
            int worldY = Math.addExact(anchor.y(), offset.y());
            int worldZ = Math.addExact(anchor.z(), offset.z());
            if (worldX < -30_000_000 || worldX > 29_999_999
                    || worldY < -2_048 || worldY > 2_047
                    || worldZ < -30_000_000 || worldZ > 29_999_999) {
                throw new IllegalArgumentException("transformed world position is outside the supported coordinate range");
            }
            var world = new BlockPos(worldX, worldY, worldZ);
            if (!coordinates.add(world)) {
                throw new IllegalArgumentException("expected contains duplicate world coordinates");
            }
            var stateInput = map(entry.get("state"), "expected.state");
            var block = string(stateInput.get("block"), "expected.state.block");
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("expected.state.block must be a namespaced registry id");
            }
            var propertyInput = optionalMap(stateInput.get("properties"));
            if (propertyInput.size() > 128) {
                throw new IllegalArgumentException("expected.state.properties contains more than 128 entries");
            }
            var properties = new TreeMap<String, String>();
            for (var property : propertyInput.entrySet()) {
                var name = string(property.getKey(), "property name");
                var value = string(property.getValue(), "property value");
                if (!name.matches("[a-z0-9_]+") || value.isEmpty() || value.length() > 64) {
                    throw new IllegalArgumentException("block property name or value is outside the supported schema");
                }
                properties.put(name, value);
            }
            expected.add(new Expected(
                    id,
                    rawOffset,
                    new BlockPosition(anchor.dimension(), world.getX(), world.getY(), world.getZ()),
                    new BlockStateView(block, properties),
                    optionalBoolean(entry.get("required"), true, "expected.required")));
        }

        var hash = hash(anchor, transform, expected);
        return new Plan(anchor, transform, List.copyOf(expected), includeMatches, hash);
    }

    private static Outcome classify(Expected expected, MinecraftObservationService.BlockSample sample) {
        return switch (sample.outcome()) {
            case CURRENT -> {
                boolean match = stateMatches(expected.state(), sample.observation().state());
                yield new Outcome(match ? "match_current" : "mismatch_current", match);
            }
            case LAST_KNOWN -> {
                boolean match = stateMatches(expected.state(), sample.observation().state());
                yield new Outcome(match ? "match_last_known" : "mismatch_last_known", match);
            }
            case NOT_CURRENTLY_OBSERVABLE, UNKNOWN -> new Outcome("unknown", false);
        };
    }

    private static boolean stateMatches(BlockStateView expected, BlockStateView actual) {
        if (!expected.block().equals(actual.block())) {
            return false;
        }
        for (var property : expected.properties().entrySet()) {
            if (!property.getValue().equals(actual.properties().get(property.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> difference(
            Expected expected, MinecraftObservationService.BlockSample sample, Outcome outcome) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", expected.id());
        result.put("required", expected.required());
        result.put("result", outcome.result());
        result.put("world_position", expected.worldPosition().toMap());
        result.put("expected", expected.state().toMap());
        if (sample.observation() != null) {
            result.put("actual", sample.observation().toMap(
                    sample.currentTick(),
                    sample.outcome() == MinecraftObservationService.BlockOutcome.CURRENT,
                    sample.visibleFaces(),
                    sample.withinReach()));
        } else {
            result.put("reason", sample.reason());
        }
        return result;
    }

    private static String hash(BlockPosition anchor, Transform transform, List<Expected> expected) {
        var canonical = new StringBuilder();
        canonical.append(anchor.dimension()).append('|').append(anchor.x()).append('|').append(anchor.y()).append('|')
                .append(anchor.z()).append('|').append(transform.rotation()).append('|').append(transform.mirror());
        for (var entry : expected) {
            canonical.append('\n').append(entry.id()).append('|')
                    .append(entry.rawOffset().x()).append('|').append(entry.rawOffset().y()).append('|')
                    .append(entry.rawOffset().z()).append('|').append(entry.state().block()).append('|')
                    .append(entry.required());
            entry.state().properties().forEach((name, value) -> canonical.append('|').append(name).append('=').append(value));
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<Object, Object>) input;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> optionalMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException("value must be an object");
        }
        return (Map<Object, Object>) input;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return text;
    }

    private static String optionalString(Object value, String fallback) {
        return value == null ? fallback : string(value, "value");
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        var result = number.longValue();
        if (number.doubleValue() != result || result < -30_000_000L || result > 30_000_000L) {
            throw new IllegalArgumentException(name + " is outside the supported integer range");
        }
        return (int) result;
    }

    private static int integerInRange(Object value, String name, int minimum, int maximum) {
        int result = integer(value, name);
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported integer range");
        }
        return result;
    }

    private static int optionalInteger(Object value, int fallback, String name) {
        return value == null ? fallback : integer(value, name);
    }

    private static boolean optionalBoolean(Object value, boolean fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return result;
    }

    record Plan(BlockPosition anchor, Transform transform, List<Expected> expected, boolean includeMatches, String hash) {
    }

    record Expected(String id, Offset rawOffset, BlockPosition worldPosition, BlockStateView state, boolean required) {
    }

    record Offset(int x, int y, int z) {
    }

    record Transform(int rotation, String mirror) {
        Transform {
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
                throw new IllegalArgumentException("rotation must be 0, 90, 180, or 270");
            }
            if (!Set.of("none", "x", "z").contains(mirror)) {
                throw new IllegalArgumentException("mirror must be none, x, or z");
            }
        }

        Offset apply(Offset input) {
            int x = "x".equals(mirror) ? -input.x() : input.x();
            int z = "z".equals(mirror) ? -input.z() : input.z();
            return switch (rotation) {
                case 0 -> new Offset(x, input.y(), z);
                case 90 -> new Offset(-z, input.y(), x);
                case 180 -> new Offset(-x, input.y(), -z);
                case 270 -> new Offset(z, input.y(), -x);
                default -> throw new AssertionError(rotation);
            };
        }
    }

    private record Outcome(String result, boolean match) {
    }

    private static final class Counter {
        private int total;
        private int current;
        private int lastKnown;
        private int mismatch;
        private int unknown;

        private void record(MinecraftObservationService.BlockOutcome outcome) {
            switch (outcome) {
                case CURRENT -> current++;
                case LAST_KNOWN -> lastKnown++;
                case NOT_CURRENTLY_OBSERVABLE, UNKNOWN -> unknown++;
            }
        }
    }
}
