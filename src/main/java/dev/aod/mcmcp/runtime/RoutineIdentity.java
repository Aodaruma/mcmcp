package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.ActionBounds;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.routine.BlockTarget;
import dev.aod.mcmcp.routine.BreakBlockRequest;
import dev.aod.mcmcp.routine.InteractBlockRequest;
import dev.aod.mcmcp.routine.InteractEntityRequest;
import dev.aod.mcmcp.routine.NavigateToRequest;
import dev.aod.mcmcp.routine.PlaceBlockRequest;
import dev.aod.mcmcp.routine.SemanticActionRequest;
import dev.aod.mcmcp.routine.StationaryBreakGoal;
import dev.aod.mcmcp.routine.UseItemOnBlockRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 同一routine要求の照合に使う決定的な正規化と識別子。 */
final class RoutineIdentity {
    private RoutineIdentity() {}

    static String stationaryBreakIdentity(
            BlockTarget target,
            Set<String> allowedBlocks,
            StationaryBreakGoal goal,
            BlockTarget minimum,
            BlockTarget maximum,
            int maxDurationSeconds,
            int regenerationSeconds,
            String completionIntent) {
        var sortedBlocks = allowedBlocks.stream().sorted().toList();
        return String.join("\u001f",
                target.dimension(),
                Integer.toString(target.x()),
                Integer.toString(target.y()),
                Integer.toString(target.z()),
                String.join(",", sortedBlocks),
                goal.itemId(),
                Integer.toString(goal.minimumInventoryCount()),
                Integer.toString(minimum.x()),
                Integer.toString(minimum.y()),
                Integer.toString(minimum.z()),
                Integer.toString(maximum.x()),
                Integer.toString(maximum.y()),
                Integer.toString(maximum.z()),
                Integer.toString(maxDurationSeconds),
                Integer.toString(regenerationSeconds),
                completionIntent);
    }

    static String semanticActionIdentity(SemanticActionRequest request) {
        return semanticActionIdentity(request, GoalContinuationSession.FINISH_GOAL);
    }

    static String semanticActionIdentity(SemanticActionRequest request, String completionIntent) {
        Objects.requireNonNull(request, "request");
        GoalContinuationSession.requireIntent(completionIntent);
        var canonical = new StringBuilder();
        appendIdentity(canonical, request.kind());
        switch (request) {
            case NavigateToRequest navigation -> {
                appendTargetIdentity(canonical, navigation.target());
                appendIdentity(canonical, Double.toHexString(
                        navigation.horizontalToleranceBlocks()));
            }
            case BreakBlockRequest block -> {
                appendTargetIdentity(canonical, block.target());
                appendBlockStateIdentity(canonical, block.expectedBefore());
                appendBlockStateIdentity(canonical, block.expectedAfter());
            }
            case PlaceBlockRequest place -> {
                appendTargetIdentity(canonical, place.target());
                appendBlockStateIdentity(canonical, place.expectedBefore());
                appendIdentity(canonical, place.item());
                appendBlockStateIdentity(canonical, place.expectedAfter());
            }
            case UseItemOnBlockRequest use -> {
                appendTargetIdentity(canonical, use.target());
                appendBlockStateIdentity(canonical, use.expectedBefore());
                appendIdentity(canonical, use.item());
                appendBlockStateIdentity(canonical, use.expectedAfter());
            }
            case InteractBlockRequest block -> {
                appendTargetIdentity(canonical, block.target());
                appendBlockStateIdentity(canonical, block.expectedBefore());
                appendBlockStateIdentity(canonical, block.expectedAfter());
            }
            case InteractEntityRequest entity -> {
                appendIdentity(canonical, entity.entityRef());
                appendIdentity(canonical, entity.expectedType());
                appendIdentity(canonical, entity.hand());
                appendIdentity(canonical, entity.heldItem());
                appendIdentity(canonical, entity.goal().itemId());
                appendIdentity(canonical, Integer.toString(
                        entity.goal().minimumInventoryCount()));
            }
        }
        appendBoundsIdentity(canonical, request.bounds());
        appendIdentity(canonical, completionIntent);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void appendBoundsIdentity(StringBuilder output, ActionBounds bounds) {
        appendIdentity(output, bounds.dimension());
        appendTargetIdentity(output, bounds.minimum());
        appendTargetIdentity(output, bounds.maximum());
        appendIdentity(output, Integer.toString(bounds.maxTravelBlocks()));
        appendIdentity(output, Integer.toString(bounds.maxDurationSeconds()));
        appendIdentity(output, Boolean.toString(bounds.allowBreak()));
    }

    static void appendTargetIdentity(StringBuilder output, BlockTarget target) {
        appendIdentity(output, target.dimension());
        appendIdentity(output, Integer.toString(target.x()));
        appendIdentity(output, Integer.toString(target.y()));
        appendIdentity(output, Integer.toString(target.z()));
    }

    static void appendBlockStateIdentity(
            StringBuilder output,
            BlockStateFingerprint state) {
        appendIdentity(output, state.blockId());
        state.properties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendIdentity(output, entry.getKey());
                    appendIdentity(output, entry.getValue());
                });
        appendIdentity(output, Integer.toString(state.properties().size()));
    }

    static void appendIdentity(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value).append(';');
    }

    static void appendCanonicalValue(StringBuilder output, Object value) {
        if (value instanceof Map<?, ?> map) {
            appendIdentity(output, "map");
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException("canonical map keys must be strings");
                        }
                        appendIdentity(output, key);
                        appendCanonicalValue(output, entry.getValue());
                    });
            appendIdentity(output, Integer.toString(map.size()));
        }
        else if (value instanceof List<?> list) {
            appendIdentity(output, "list");
            for (Object element : list) {
                appendCanonicalValue(output, element);
            }
            appendIdentity(output, Integer.toString(list.size()));
        }
        else if (value instanceof String text) {
            appendIdentity(output, "string");
            appendIdentity(output, text);
        }
        else if (value instanceof Boolean flag) {
            appendIdentity(output, "boolean");
            appendIdentity(output, flag.toString());
        }
        else if (value instanceof Number number) {
            try {
                long integral = RuntimeArguments.exactLong(number);
                appendIdentity(output, "integer");
                appendIdentity(output, Long.toString(integral));
            }
            catch (ArithmeticException nonInteger) {
                double finite = number.doubleValue();
                if (!Double.isFinite(finite)) {
                    throw new IllegalArgumentException("canonical numbers must be finite");
                }
                appendIdentity(output, "number");
                appendIdentity(output, new BigDecimal(number.toString())
                        .stripTrailingZeros().toPlainString());
            }
        }
        else {
            throw new IllegalArgumentException("unsupported value in Phase 5 identity");
        }
    }

    static String sha256Identity(StringBuilder canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
