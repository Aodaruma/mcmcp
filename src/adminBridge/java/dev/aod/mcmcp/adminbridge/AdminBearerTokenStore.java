package dev.aod.mcmcp.adminbridge;

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
import java.util.Set;

/** Independent owner-only bearer material; never shares the production MCMCP token. */
final class AdminBearerTokenStore {
    private static final int RANDOM_BYTES = 32;

    String loadOrCreate(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalized, initialAttributes(normalized, true));
        restrict(normalized, true);
        Path tokenFile = normalized.resolve("admin-token");
        if (Files.notExists(tokenFile)) {
            byte[] entropy = new byte[RANDOM_BYTES];
            new SecureRandom().nextBytes(entropy);
            boolean created = false;
            try {
                Files.createFile(tokenFile, initialAttributes(tokenFile, false));
                created = true;
                restrict(tokenFile, false);
                Files.writeString(tokenFile,
                        Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
                                + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another bootstrap completed the owner-only file first.
            } catch (IOException failure) {
                if (created) {
                    try {
                        Files.deleteIfExists(tokenFile);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
        if (Files.isSymbolicLink(tokenFile) || !Files.isRegularFile(tokenFile)
                || Files.size(tokenFile) > 256L) {
            throw new IOException("admin token file is unsafe");
        }
        restrict(tokenFile, false);
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("admin token is malformed", invalid);
        }
        if (decoded.length != RANDOM_BYTES) {
            throw new IOException("admin token must contain exactly 256 random bits");
        }
        return token;
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        requirePermissionSupport(views);
        boolean restricted = false;
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
            restricted = true;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = currentUser(path);
            Files.setOwner(path, owner);
            acl.setAcl(List.of(ownerOnly(owner)));
            List<AclEntry> actual = acl.getAcl();
            if (actual.size() != 1 || actual.getFirst().type() != AclEntryType.ALLOW
                    || !actual.getFirst().principal().equals(owner)) {
                throw new IOException("admin token ACL is not owner-only");
            }
            restricted = true;
        }
        if (!restricted) {
            throw new IOException("owner-only permissions are unsupported");
        }
    }

    private static FileAttribute<?>[] initialAttributes(Path path, boolean directory)
            throws IOException {
        Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        requirePermissionSupport(views);
        if (views.contains("posix")) {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
        }
        if (views.contains("acl")) {
            List<AclEntry> acl = List.of(ownerOnly(currentUser(path)));
            return new FileAttribute<?>[] {new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return acl; }
            }};
        }
        throw new IOException("owner-only permissions are unsupported");
    }

    private static void requirePermissionSupport(Set<String> views) throws IOException {
        if (!views.contains("posix") && !views.contains("acl")) {
            throw new IOException("owner-only permissions are unsupported");
        }
    }

    private static UserPrincipal currentUser(Path path) throws IOException {
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            throw new IOException("current user is unavailable");
        }
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
    }

    private static AclEntry ownerOnly(UserPrincipal owner) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    }
}
