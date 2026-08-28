package dev.aod.mcmcp.adminbridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.aod.mcmcp.adminbridge.FixtureManifest.BlockPosition;

/** Closed command grammar. It deliberately excludes execute/function and arbitrary selectors. */
public final class RestrictedCommandPolicy {
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]{0,8})");
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,6})?");
    private static final Pattern BLOCK = Pattern.compile(
            "minecraft:[a-z0-9_./-]+(?:\\[[a-z0-9_:./=,-]+])?");
    private static final Pattern ITEM = Pattern.compile(
            "minecraft:[a-z0-9_./-]+(?:\\[[a-z0-9_:./=,-]+])?");
    private static final Pattern SLOT = Pattern.compile("container\\.([0-9]{1,3})");
    private static final Pattern ITEM_SELECTOR = Pattern.compile(
            "@e\\[type=minecraft:item,x=(-?[0-9]{1,9}),y=(-?[0-9]{1,9}),"
                    + "z=(-?[0-9]{1,9}),dx=([0-9]{1,6}),dy=([0-9]{1,6}),dz=([0-9]{1,6})]");
    private static final Set<String> ALLOWED_BLOCKS = Set.of(
            "minecraft:air", "minecraft:dirt", "minecraft:farmland", "minecraft:wheat",
            "minecraft:chest", "minecraft:oak_fence", "minecraft:oak_fence_gate",
            "minecraft:stone", "minecraft:smooth_stone", "minecraft:sandstone");
    private static final Set<String> ALLOWED_ITEMS = Set.of(
            "minecraft:air", "minecraft:iron_hoe", "minecraft:netherite_hoe",
            "minecraft:wheat_seeds",
            "minecraft:iron_axe", "minecraft:oak_sapling", "minecraft:bread");

    private static final int MAX_COMMANDS = 256;
    private static final int MAX_LINE_CHARS = 512;
    private static final int MAX_TOTAL_CHARS = 32_768;

    public List<ValidatedCommand> validate(FixtureManifest manifest, List<String> sourceLines)
            throws FixtureFormatException {
        if (sourceLines.isEmpty() || sourceLines.size() > MAX_COMMANDS) {
            throw new FixtureFormatException("command_count_invalid");
        }
        Set<BlockPosition> containers = new HashSet<>(manifest.containers());
        List<ValidatedCommand> result = new ArrayList<>();
        long totalChangedBlocks = 0L;
        int totalChars = 0;
        for (int index = 0; index < sourceLines.size(); index++) {
            String line = sourceLines.get(index);
            totalChars += line.length();
            if (line.isBlank() || line.length() > MAX_LINE_CHARS || totalChars > MAX_TOTAL_CHARS) {
                throw new FixtureFormatException("command_size_invalid");
            }
            if (!line.equals(line.strip()) || line.startsWith("/")
                    || line.indexOf(';') >= 0 || line.indexOf('\t') >= 0
                    || line.indexOf('{') >= 0 || line.indexOf('}') >= 0
                    || line.codePoints().anyMatch(Character::isISOControl)) {
                throw new FixtureFormatException("command_lexically_forbidden");
            }
            String[] tokens = line.split(" +", -1);
            String root = tokens[0].toLowerCase(Locale.ROOT);
            long changed = switch (root) {
                case "setblock" -> validateSetBlock(manifest, tokens);
                case "fill" -> validateFill(manifest, tokens);
                case "item" -> validateItem(manifest, containers, tokens);
                case "clear" -> validateClear(tokens);
                case "tp" -> validateTeleport(manifest, tokens);
                case "kill" -> validateKill(manifest, tokens);
                case "gamemode" -> validateGameMode(tokens);
                default -> throw new FixtureFormatException("command_root_forbidden");
            };
            try {
                totalChangedBlocks = Math.addExact(totalChangedBlocks, changed);
            } catch (ArithmeticException overflow) {
                throw new FixtureFormatException("changed_block_budget_exceeded", overflow);
            }
            if (totalChangedBlocks > manifest.maxChangedBlocks()) {
                throw new FixtureFormatException("changed_block_budget_exceeded");
            }
            result.add(new ValidatedCommand(index + 1, root, line, changed));
        }
        return List.copyOf(result);
    }

    private static long validateSetBlock(FixtureManifest manifest, String[] tokens)
            throws FixtureFormatException {
        if (tokens.length != 5 && tokens.length != 6) {
            throw new FixtureFormatException("setblock_syntax_invalid");
        }
        BlockPosition position = position(tokens, 1);
        requireMutationTarget(manifest.mutationBounds(), position);
        requireBlock(tokens[4]);
        if (tokens.length == 6 && !Set.of("replace", "keep").contains(tokens[5])) {
            throw new FixtureFormatException("setblock_mode_forbidden");
        }
        return 1L;
    }

    private static long validateFill(FixtureManifest manifest, String[] tokens)
            throws FixtureFormatException {
        if (tokens.length != 8 && tokens.length != 9) {
            throw new FixtureFormatException("fill_syntax_invalid");
        }
        BlockPosition first = position(tokens, 1);
        BlockPosition second = position(tokens, 4);
        requireMutationTarget(manifest.mutationBounds(), first);
        requireMutationTarget(manifest.mutationBounds(), second);
        requireBlock(tokens[7]);
        if (tokens.length == 9 && !Set.of("replace", "keep").contains(tokens[8])) {
            throw new FixtureFormatException("fill_mode_forbidden");
        }
        try {
            long x = Math.addExact(Math.abs((long) first.x() - second.x()), 1L);
            long y = Math.addExact(Math.abs((long) first.y() - second.y()), 1L);
            long z = Math.addExact(Math.abs((long) first.z() - second.z()), 1L);
            return Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException overflow) {
            throw new FixtureFormatException("changed_block_budget_exceeded", overflow);
        }
    }

    private static long validateItem(
            FixtureManifest manifest, Set<BlockPosition> containers, String[] tokens)
            throws FixtureFormatException {
        if (tokens.length != 9 && tokens.length != 10) {
            throw new FixtureFormatException("item_replace_syntax_invalid");
        }
        if (!"replace".equals(tokens[1]) || !"block".equals(tokens[2])
                || !"with".equals(tokens[7])) {
            throw new FixtureFormatException("item_replace_syntax_invalid");
        }
        BlockPosition position = position(tokens, 3);
        requireMutationTarget(manifest.mutationBounds(), position);
        if (!containers.contains(position)) {
            throw new FixtureFormatException("container_not_declared");
        }
        Matcher slot = SLOT.matcher(tokens[6]);
        if (!slot.matches() || Integer.parseInt(slot.group(1)) > 53) {
            throw new FixtureFormatException("container_slot_invalid");
        }
        if (!ITEM.matcher(tokens[8]).matches()
                || !ALLOWED_ITEMS.contains(baseIdentifier(tokens[8]))) {
            throw new FixtureFormatException("item_id_invalid");
        }
        if (tokens.length == 10) {
            int count = positiveInt(tokens[9], "item_count_invalid");
            if (count > 64) {
                throw new FixtureFormatException("item_count_invalid");
            }
        }
        return 0L;
    }

    private static long validateClear(String[] tokens) throws FixtureFormatException {
        if (tokens.length != 2 || !"@s".equals(tokens[1])) {
            throw new FixtureFormatException("clear_target_forbidden");
        }
        return 0L;
    }

    private static long validateTeleport(FixtureManifest manifest, String[] tokens)
            throws FixtureFormatException {
        if (tokens.length != 5 && tokens.length != 7 || !"@s".equals(tokens[1])) {
            throw new FixtureFormatException("tp_syntax_invalid");
        }
        double x = decimal(tokens[2], "tp_coordinate_invalid");
        double y = decimal(tokens[3], "tp_coordinate_invalid");
        double z = decimal(tokens[4], "tp_coordinate_invalid");
        if (!manifest.playerBounds().contains(x, y, z)) {
            throw new FixtureFormatException("tp_outside_player_bounds");
        }
        if (tokens.length == 7) {
            double yaw = decimal(tokens[5], "tp_rotation_invalid");
            double pitch = decimal(tokens[6], "tp_rotation_invalid");
            if (yaw < -360.0D || yaw > 360.0D || pitch < -90.0D || pitch > 90.0D) {
                throw new FixtureFormatException("tp_rotation_invalid");
            }
        }
        return 0L;
    }

    private static long validateKill(FixtureManifest manifest, String[] tokens)
            throws FixtureFormatException {
        if (tokens.length != 2) {
            throw new FixtureFormatException("kill_selector_forbidden");
        }
        Matcher matcher = ITEM_SELECTOR.matcher(tokens[1]);
        if (!matcher.matches()) {
            throw new FixtureFormatException("kill_selector_forbidden");
        }
        BlockPosition min = new BlockPosition(
                parseInt(matcher.group(1), "kill_selector_forbidden"),
                parseInt(matcher.group(2), "kill_selector_forbidden"),
                parseInt(matcher.group(3), "kill_selector_forbidden"));
        BlockPosition max;
        try {
            max = new BlockPosition(
                    Math.addExact(min.x(), parseInt(matcher.group(4), "kill_selector_forbidden")),
                    Math.addExact(min.y(), parseInt(matcher.group(5), "kill_selector_forbidden")),
                    Math.addExact(min.z(), parseInt(matcher.group(6), "kill_selector_forbidden")));
        } catch (ArithmeticException overflow) {
            throw new FixtureFormatException("kill_selector_forbidden", overflow);
        }
        if (!manifest.mutationBounds().contains(new FixtureManifest.Bounds(min, max))) {
            throw new FixtureFormatException("kill_selector_outside_bounds");
        }
        return 0L;
    }

    private static long validateGameMode(String[] tokens) throws FixtureFormatException {
        if (tokens.length != 3 || !"survival".equals(tokens[1]) || !"@s".equals(tokens[2])) {
            throw new FixtureFormatException("gamemode_forbidden");
        }
        return 0L;
    }

    private static BlockPosition position(String[] tokens, int offset) throws FixtureFormatException {
        return new BlockPosition(
                parseInt(tokens[offset], "coordinate_invalid"),
                parseInt(tokens[offset + 1], "coordinate_invalid"),
                parseInt(tokens[offset + 2], "coordinate_invalid"));
    }

    private static int parseInt(String value, String code) throws FixtureFormatException {
        if (!INTEGER.matcher(value).matches()) {
            throw new FixtureFormatException(code);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new FixtureFormatException(code, invalid);
        }
    }

    private static int positiveInt(String value, String code) throws FixtureFormatException {
        int parsed = parseInt(value, code);
        if (parsed < 1) {
            throw new FixtureFormatException(code);
        }
        return parsed;
    }

    private static double decimal(String value, String code) throws FixtureFormatException {
        if (!DECIMAL.matcher(value).matches()) {
            throw new FixtureFormatException(code);
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new FixtureFormatException(code);
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new FixtureFormatException(code, invalid);
        }
    }

    private static void requireMutationTarget(
            FixtureManifest.Bounds bounds, BlockPosition position)
            throws FixtureFormatException {
        if (!bounds.contains(position)
                || position.x() <= bounds.min().x() || position.x() >= bounds.max().x()
                || position.y() <= bounds.min().y() || position.y() >= bounds.max().y()
                || position.z() <= bounds.min().z() || position.z() >= bounds.max().z()) {
            throw new FixtureFormatException("coordinate_outside_effect_inset");
        }
    }

    private static void requireBlock(String value) throws FixtureFormatException {
        if (!BLOCK.matcher(value).matches() || !ALLOWED_BLOCKS.contains(baseIdentifier(value))) {
            throw new FixtureFormatException("block_id_invalid");
        }
    }

    private static String baseIdentifier(String value) {
        int properties = value.indexOf('[');
        return properties < 0 ? value : value.substring(0, properties);
    }

    public record ValidatedCommand(int line, String root, String source, long changedBlocks) {
    }
}
