package dev.aod.mcmcp.adminbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable pre-mutation record used to recover randomTickSpeed after a JVM/container crash. */
final class RandomTickLeaseJournal {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> FIELDS = Set.of(
            "schema_version", "world_path_sha256", "world_id", "original", "target");
    private static final int MAX_BYTES = 1_024;

    private final Path path;

    RandomTickLeaseJournal(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    boolean exists() {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    void write(Entry entry) throws JournalException {
        if (exists()) {
            throw new JournalException("random_tick_journal_already_exists");
        }
        Path parent = path.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new JournalException("random_tick_journal_directory_invalid");
        }
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", 2);
        object.addProperty("world_path_sha256", entry.worldPathSha256());
        object.addProperty("world_id", entry.worldId());
        object.addProperty("original", entry.original());
        object.addProperty("target", entry.target());
        byte[] bytes = (object + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        Path temporary = parent.resolve(".random-tick-lease-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new JournalException("random_tick_journal_atomic_move_unsupported", unsupported);
            }
        } catch (JournalException failure) {
            deleteTemporary(temporary, failure);
            throw failure;
        } catch (IOException failure) {
            var wrapped = new JournalException("random_tick_journal_write_failed", failure);
            deleteTemporary(temporary, wrapped);
            throw wrapped;
        }
    }

    Entry read() throws JournalException {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_BYTES) {
                throw new JournalException("random_tick_journal_invalid");
            }
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().keySet().equals(FIELDS)) {
                throw new JournalException("random_tick_journal_invalid");
            }
            JsonObject object = parsed.getAsJsonObject();
            if (integer(object, "schema_version") != 2) {
                throw new JournalException("random_tick_journal_invalid");
            }
            String worldHash = string(object, "world_path_sha256");
            String worldId = string(object, "world_id");
            int original = integer(object, "original");
            int target = integer(object, "target");
            if (!HASH.matcher(worldHash).matches() || original < 0 || original > 1_000_000
                    || target < 0 || target > 4_096) {
                throw new JournalException("random_tick_journal_invalid");
            }
            try {
                if (!UUID.fromString(worldId).toString().equals(worldId)) {
                    throw new IllegalArgumentException("non-canonical UUID");
                }
            } catch (IllegalArgumentException invalid) {
                throw new JournalException("random_tick_journal_invalid", invalid);
            }
            return new Entry(worldHash, worldId, original, target);
        } catch (JournalException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new JournalException("random_tick_journal_read_failed", failure);
        }
    }

    void delete() throws JournalException {
        try {
            if (Files.isSymbolicLink(path)) {
                throw new JournalException("random_tick_journal_invalid");
            }
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new JournalException("random_tick_journal_delete_failed", failure);
        }
    }

    private static int integer(JsonObject object, String field) throws JournalException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("(?:0|[1-9][0-9]{0,6})")) {
            throw new JournalException("random_tick_journal_invalid");
        }
        return Integer.parseInt(value.getAsString());
    }

    private static String string(JsonObject object, String field) throws JournalException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JournalException("random_tick_journal_invalid");
        }
        return value.getAsString();
    }

    private static void deleteTemporary(Path temporary, Exception failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    record Entry(String worldPathSha256, String worldId, int original, int target) {
        Entry {
            if (worldPathSha256 == null || !HASH.matcher(worldPathSha256).matches()) {
                throw new IllegalArgumentException("worldPathSha256");
            }
            try {
                if (!UUID.fromString(worldId).toString().equals(worldId)) {
                    throw new IllegalArgumentException("worldId");
                }
            } catch (NullPointerException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("worldId", invalid);
            }
        }
    }

    static final class JournalException extends RuntimeException {
        private final String code;

        JournalException(String code) {
            super(code);
            this.code = code;
        }

        JournalException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        String code() { return code; }
    }
}
