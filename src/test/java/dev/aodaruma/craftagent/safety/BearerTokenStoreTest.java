package dev.aodaruma.craftagent.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearerTokenStoreTest {
    @Test
    void createsOnceAndRedactsStringRepresentation(@TempDir Path directory) throws Exception {
        var store = new BearerTokenStore();
        var first = store.loadOrCreate(directory);
        var second = store.loadOrCreate(directory);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.secret()).isEqualTo(first.secret()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(first.toString()).doesNotContain(first.secret()).contains("<redacted>");
        assertThat(Files.readString(first.path()).strip()).isEqualTo(first.secret());

        var acl = Files.getFileAttributeView(first.path(), AclFileAttributeView.class);
        if (acl != null) {
            var owner = Files.getOwner(first.path());
            assertThat(acl.getAcl()).singleElement().satisfies(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(owner);
            });
        }
    }

    @Test
    void rejectsFilesystemsWithoutAnOwnerOnlyPermissionMechanism() {
        assertThatThrownBy(() -> BearerTokenStore.requireOwnerPermissionSupport(
                        Set.of("basic", "dos", "owner", "user")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not support POSIX permissions or ACLs");
    }
}
