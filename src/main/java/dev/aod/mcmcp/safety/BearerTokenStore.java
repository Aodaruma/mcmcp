package dev.aod.mcmcp.safety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Persists a loopback bearer token without ever logging or returning it over MCP. */
public final class BearerTokenStore {
    private static final int RANDOM_BYTES = 32;
    private static final int MAX_TOKEN_FILE_BYTES = 256;
    private final SecureRandom random;

    public BearerTokenStore() {
        this(new SecureRandom());
    }

    BearerTokenStore(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public TokenMaterial loadOrCreate(Path configDirectory) throws IOException {
        var directory = configDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory, initialOwnerOnlyAttributes(directory, true));
        restrictOwnerPermissions(directory, true);
        var path = directory.resolve("mcp-token");
        boolean created = false;
        if (Files.notExists(path)) {
            var bytes = new byte[RANDOM_BYTES];
            random.nextBytes(bytes);
            var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + System.lineSeparator();
            boolean fileCreated = false;
            try {
                Files.createFile(path, initialOwnerOnlyAttributes(path, false));
                fileCreated = true;
                restrictOwnerPermissions(path, false);
                Files.writeString(path, encoded, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                created = true;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another bootstrap won the race; validate and use its complete file.
            } catch (IOException failure) {
                if (fileCreated) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > MAX_TOKEN_FILE_BYTES) {
            throw new IOException("MCMCP bearer token file is not a small regular file");
        }
        restrictOwnerPermissions(path, false);
        var token = Files.readString(path, StandardCharsets.UTF_8).strip();
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("MCMCP bearer token file is malformed", invalid);
        }
        if (decoded.length < RANDOM_BYTES) {
            throw new IOException("MCMCP bearer token does not contain 256 bits of random data");
        }
        return new TokenMaterial(token, path, created);
    }

    private static void restrictOwnerPermissions(Path path, boolean directory) throws IOException {
        requireOwnerPermissionSupport(path.getFileSystem().supportedFileAttributeViews());
        boolean restricted = false;
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
            restricted = true;
        }

        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var owner = currentUser(path);
            Files.setOwner(path, owner);
            var ownerOnly = ownerOnlyAcl(owner);
            acl.setAcl(List.of(ownerOnly));
            var effective = acl.getAcl();
            if (effective.size() != 1
                    || effective.getFirst().type() != AclEntryType.ALLOW
                    || !effective.getFirst().principal().equals(owner)) {
                throw new IOException("MCMCP bearer token ACL is not owner-only");
            }
            restricted = true;
        }
        if (!restricted) {
            throw new IOException("MCMCP bearer token filesystem cannot enforce owner-only permissions");
        }
    }

    private static FileAttribute<?>[] initialOwnerOnlyAttributes(Path path, boolean directory) throws IOException {
        var views = path.getFileSystem().supportedFileAttributeViews();
        requireOwnerPermissionSupport(views);
        if (views.contains("posix")) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
        }
        if (views.contains("acl")) {
            var acl = List.of(ownerOnlyAcl(currentUser(path)));
            return new FileAttribute<?>[] {new FileAttribute<List<AclEntry>>() {
                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return acl;
                }
            }};
        }
        throw new IOException("MCMCP bearer token filesystem cannot create owner-only files");
    }

    static void requireOwnerPermissionSupport(Set<String> views) throws IOException {
        Objects.requireNonNull(views, "views");
        if (!views.contains("posix") && !views.contains("acl")) {
            throw new IOException("MCMCP bearer token filesystem does not support POSIX permissions or ACLs");
        }
    }

    private static UserPrincipal currentUser(Path path) throws IOException {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) {
            throw new IOException("Cannot identify the current user for the MCMCP token ACL");
        }
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
    }

    private static AclEntry ownerOnlyAcl(UserPrincipal owner) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
    }

    public record TokenMaterial(String secret, Path path, boolean created) {
        public TokenMaterial {
            Objects.requireNonNull(secret, "secret");
            Objects.requireNonNull(path, "path");
        }

        @Override
        public String toString() {
            return "TokenMaterial[secret=<redacted>, path=" + path + ", created=" + created + "]";
        }
    }
}
