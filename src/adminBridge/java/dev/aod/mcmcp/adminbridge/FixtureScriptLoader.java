package dev.aod.mcmcp.adminbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.aod.mcmcp.adminbridge.FixtureManifest.BlockPosition;
import static dev.aod.mcmcp.adminbridge.FixtureManifest.Bounds;

/** Loads a named fixture without following links or accepting undeclared manifest fields. */
public final class FixtureScriptLoader {
    private static final Pattern FIXTURE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_MANIFEST_BYTES = 32_768;
    private static final int MAX_SCRIPT_BYTES = 65_536;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "id", "dimension", "mutation_bounds", "player_bounds",
            "max_changed_blocks", "containers", "random_tick_speed");
    private static final Set<String> BOUNDS_FIELDS = Set.of("min", "max");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Set<String> LEASE_FIELDS = Set.of("target", "maximum_seconds");

    private final Path root;
    private final RestrictedCommandPolicy policy;

    public FixtureScriptLoader(Path root) {
        this(root, new RestrictedCommandPolicy());
    }

    FixtureScriptLoader(Path root, RestrictedCommandPolicy policy) {
        this.root = root.toAbsolutePath().normalize();
        this.policy = policy;
    }

    public FixtureScript load(String fixtureId) throws FixtureFormatException {
        if (fixtureId == null || !FIXTURE_ID.matcher(fixtureId).matches()) {
            throw new FixtureFormatException("fixture_id_invalid");
        }
        try {
            requireSafeDirectory(root, "fixture_root_unavailable");
            Path directory = root.resolve(fixtureId).normalize();
            if (!directory.getParent().equals(root)) {
                throw new FixtureFormatException("fixture_path_invalid");
            }
            requireSafeDirectory(directory, "fixture_not_found");
            Path canonicalRoot = root.toRealPath();
            Path canonicalDirectory = directory.toRealPath();
            if (!canonicalRoot.equals(canonicalDirectory.getParent())) {
                throw new FixtureFormatException("fixture_path_invalid");
            }
            Path manifestPath = requireSafeFile(directory, "fixture.json", MAX_MANIFEST_BYTES);
            Path scriptPath = requireSafeFile(directory, "setup.mcfunction", MAX_SCRIPT_BYTES);
            byte[] manifestBytes = readSafeFile(
                    canonicalDirectory, manifestPath, MAX_MANIFEST_BYTES);
            byte[] scriptBytes = readSafeFile(
                    canonicalDirectory, scriptPath, MAX_SCRIPT_BYTES);
            FixtureManifest manifest = parseManifest(decode(manifestBytes));
            if (!fixtureId.equals(manifest.id())) {
                throw new FixtureFormatException("fixture_id_mismatch");
            }
            List<String> commands = parseCommands(decode(scriptBytes));
            var validated = policy.validate(manifest, commands);
            return new FixtureScript(
                    manifest, validated, sha256(manifestBytes, scriptBytes), directory);
        } catch (FixtureFormatException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new FixtureFormatException("fixture_io_failed", failure);
        } catch (RuntimeException failure) {
            throw new FixtureFormatException("fixture_manifest_invalid", failure);
        }
    }

    private static FixtureManifest parseManifest(String source) throws FixtureFormatException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (RuntimeException invalid) {
            throw new FixtureFormatException("fixture_json_invalid", invalid);
        }
        if (!parsed.isJsonObject()) {
            throw new FixtureFormatException("fixture_manifest_invalid");
        }
        JsonObject root = parsed.getAsJsonObject();
        requireExactOrOptionalFields(root, ROOT_FIELDS,
                Set.of("schema_version", "id", "dimension", "mutation_bounds",
                        "max_changed_blocks", "containers"));
        int schemaVersion = integer(root, "schema_version");
        String id = string(root, "id");
        if (!FIXTURE_ID.matcher(id).matches()) {
            throw new FixtureFormatException("fixture_id_invalid");
        }
        String dimension = string(root, "dimension");
        if (!"minecraft:overworld".equals(dimension)) {
            throw new FixtureFormatException("dimension_forbidden");
        }
        Bounds mutationBounds = bounds(root.get("mutation_bounds"));
        Bounds playerBounds = root.has("player_bounds")
                ? bounds(root.get("player_bounds")) : mutationBounds;
        int maximum = integer(root, "max_changed_blocks");
        if (maximum < 1 || maximum > 65_536) {
            throw new FixtureFormatException("max_changed_blocks_invalid");
        }
        List<BlockPosition> containers = positions(root.get("containers"));
        if (containers.size() > 64 || containers.stream().anyMatch(p -> !mutationBounds.contains(p))) {
            throw new FixtureFormatException("containers_invalid");
        }
        FixtureManifest.RandomTickLease lease = root.has("random_tick_speed")
                ? lease(root.get("random_tick_speed")) : null;
        try {
            return new FixtureManifest(schemaVersion, id, dimension, mutationBounds,
                    playerBounds, maximum, containers, lease);
        } catch (IllegalArgumentException invalid) {
            throw new FixtureFormatException("fixture_manifest_invalid", invalid);
        }
    }

    private static FixtureManifest.RandomTickLease lease(JsonElement element)
            throws FixtureFormatException {
        JsonObject object = object(element, "random_tick_speed_invalid");
        requireExactOrOptionalFields(object, LEASE_FIELDS, LEASE_FIELDS);
        int target = integer(object, "target");
        int duration = integer(object, "maximum_seconds");
        if (target < 0 || target > 4_096 || duration < 1 || duration > 1_800) {
            throw new FixtureFormatException("random_tick_speed_invalid");
        }
        return new FixtureManifest.RandomTickLease(target, duration);
    }

    private static Bounds bounds(JsonElement element) throws FixtureFormatException {
        JsonObject object = object(element, "bounds_invalid");
        requireExactOrOptionalFields(object, BOUNDS_FIELDS, BOUNDS_FIELDS);
        try {
            return new Bounds(position(object.get("min")), position(object.get("max")));
        } catch (IllegalArgumentException invalid) {
            throw new FixtureFormatException("bounds_invalid", invalid);
        }
    }

    private static List<BlockPosition> positions(JsonElement element) throws FixtureFormatException {
        if (element == null || !element.isJsonArray()) {
            throw new FixtureFormatException("containers_invalid");
        }
        JsonArray array = element.getAsJsonArray();
        List<BlockPosition> result = new ArrayList<>();
        Set<BlockPosition> unique = new LinkedHashSet<>();
        for (JsonElement value : array) {
            BlockPosition position = position(value);
            if (!unique.add(position)) {
                throw new FixtureFormatException("containers_invalid");
            }
            result.add(position);
        }
        return List.copyOf(result);
    }

    private static BlockPosition position(JsonElement element) throws FixtureFormatException {
        JsonObject object = object(element, "position_invalid");
        requireExactOrOptionalFields(object, POSITION_FIELDS, POSITION_FIELDS);
        int x = integer(object, "x");
        int y = integer(object, "y");
        int z = integer(object, "z");
        if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L
                || y < -2_048 || y > 2_048) {
            throw new FixtureFormatException("position_invalid");
        }
        return new BlockPosition(x, y, z);
    }

    private static JsonObject object(JsonElement element, String code) throws FixtureFormatException {
        if (element == null || !element.isJsonObject()) {
            throw new FixtureFormatException(code);
        }
        return element.getAsJsonObject();
    }

    private static int integer(JsonObject object, String name) throws FixtureFormatException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new FixtureFormatException("fixture_manifest_invalid");
        }
        String source = value.getAsString();
        if (!source.matches("-?(?:0|[1-9][0-9]{0,9})")) {
            throw new FixtureFormatException("fixture_manifest_invalid");
        }
        try {
            return Integer.parseInt(source);
        } catch (RuntimeException invalid) {
            throw new FixtureFormatException("fixture_manifest_invalid", invalid);
        }
    }

    private static String string(JsonObject object, String name) throws FixtureFormatException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new FixtureFormatException("fixture_manifest_invalid");
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > 128) {
            throw new FixtureFormatException("fixture_manifest_invalid");
        }
        return result;
    }

    private static void requireExactOrOptionalFields(
            JsonObject object, Set<String> allowed, Set<String> required)
            throws FixtureFormatException {
        if (!allowed.containsAll(object.keySet()) || !object.keySet().containsAll(required)) {
            throw new FixtureFormatException("fixture_manifest_fields_invalid");
        }
    }

    private static List<String> parseCommands(String source) throws FixtureFormatException {
        List<String> commands = new ArrayList<>();
        for (String raw : source.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            commands.add(line);
        }
        if (commands.isEmpty()) {
            throw new FixtureFormatException("command_count_invalid");
        }
        return List.copyOf(commands);
    }

    private static Path requireSafeFile(Path directory, String name, long maximum)
            throws IOException, FixtureFormatException {
        Path path = directory.resolve(name).normalize();
        if (!path.getParent().equals(directory) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > maximum) {
            throw new FixtureFormatException("fixture_file_invalid");
        }
        return path;
    }

    private static byte[] readSafeFile(Path canonicalDirectory, Path path, int maximum)
            throws IOException, FixtureFormatException {
        Path canonicalPath = path.toRealPath();
        if (!canonicalDirectory.equals(canonicalPath.getParent())) {
            throw new FixtureFormatException("fixture_path_invalid");
        }
        BasicFileAttributes before = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > maximum) {
            throw new FixtureFormatException("fixture_file_invalid");
        }
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.<java.nio.file.OpenOption>of(
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(4_096);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if (output.size() + buffer.remaining() > maximum) {
                    throw new FixtureFormatException("fixture_file_invalid");
                }
                output.write(buffer.array(), buffer.position(), buffer.remaining());
                buffer.clear();
            }
            bytes = output.toByteArray();
        }
        BasicFileAttributes after = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || before.lastModifiedTime().compareTo(after.lastModifiedTime()) != 0
                || before.fileKey() != null && !before.fileKey().equals(after.fileKey())) {
            throw new FixtureFormatException("fixture_changed_during_read");
        }
        return bytes;
    }

    private static void requireSafeDirectory(Path path, String code)
            throws FixtureFormatException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FixtureFormatException(code);
        }
    }

    private static String decode(byte[] bytes) throws FixtureFormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new FixtureFormatException("fixture_utf8_invalid", invalid);
        }
    }

    private static String sha256(byte[] manifest, byte[] script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(manifest);
            digest.update((byte) 0);
            digest.update(script);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
