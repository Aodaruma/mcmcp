package dev.aod.mcmcp.adminbridge;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Persistent per-save identity used in addition to the save path in recovery journals. */
final class WorldIdentityStore {
    private static final String MARKER_NAME = ".mcmcp-fixture-admin-world-id";
    private static final int MAX_MARKER_BYTES = 64;

    Identity loadOrCreate(Path worldRoot) {
        try {
            Path canonicalRoot = worldRoot.toRealPath();
            if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IdentityException("world_identity_root_invalid");
            }
            Path marker = canonicalRoot.resolve(MARKER_NAME);
            String id;
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                id = readMarker(marker);
            } else {
                id = createMarker(canonicalRoot, marker);
            }
            return new Identity(sha256(canonicalRoot.toString()), id);
        } catch (IdentityException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IdentityException("world_identity_io_failed", failure);
        }
    }

    private static String createMarker(Path root, Path marker) throws IOException {
        String id = UUID.randomUUID().toString();
        byte[] bytes = (id + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        Path temporary = root.resolve("." + MARKER_NAME + "-" + UUID.randomUUID() + ".tmp");
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
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                return id;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                return readMarker(marker);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IdentityException("world_identity_atomic_move_unsupported", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String readMarker(Path marker) throws IOException {
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > MAX_MARKER_BYTES) {
            throw new IdentityException("world_identity_marker_invalid");
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).strip();
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IdentityException("world_identity_marker_invalid", invalid);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Identity(String worldPathSha256, String worldId) {
    }

    static final class IdentityException extends RuntimeException {
        private final String code;

        IdentityException(String code) {
            super(code);
            this.code = code;
        }

        IdentityException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
