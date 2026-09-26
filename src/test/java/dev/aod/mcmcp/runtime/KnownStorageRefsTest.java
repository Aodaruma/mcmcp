package dev.aod.mcmcp.runtime;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnownStorageRefsTest {
    @Test
    void sameItemTargetsRemainDistinctAndMovedTargetsSessionAndPlayerChangesReject() {
        var refs = new KnownStorageRefs(List.of());
        var session = UUID.randomUUID();
        Object player = new Object();
        var first = new Target();
        var second = new Target();
        String a = refs.issue(session, player, first, 100);
        String b = refs.issue(session, player, second, 100);
        String storage = refs.storageId(session, first);
        assertThat(refs.storageId(session, first)).isEqualTo(storage);
        assertThat(refs.storageId(session, second)).isNotEqualTo(storage);
        assertThat(refs.storageId(UUID.randomUUID(), first)).isNotEqualTo(storage);
        assertThat(a).isNotEqualTo(b).matches("[A-Za-z0-9_-]{24}");
        assertThat(refs.resolve(a, session, player, 1, target -> true, false)).containsSame(first);
        assertThat(refs.resolve(b, session, player, 1, target -> true, false)).containsSame(second);
        assertThat(refs.resolve(a, session, player, 1, target -> false, true)).isEmpty();
        assertThat(refs.resolve(a, UUID.randomUUID(), player, 1, target -> true, true)).isEmpty();
        assertThat(refs.resolve(a, session, new Object(), 1, target -> true, true)).isEmpty();
        assertThat(refs.resolve(a, session, player, 101, target -> true, true)).isEmpty();
        assertThat(refs.resolve(a, session, player, 99, target -> true, true)).containsSame(first);
        assertThat(refs.resolve(a, session, player, 99, target -> true, true)).isEmpty();
        refs.clear();
        assertThat(refs.resolve(b, session, player, 99, target -> true, true)).isEmpty();
    }

    private static final class Target implements StorageAccess.Target {
        public String profileHash() { return "sha256:" + "a".repeat(64); }
        public Object identityKey() { return this; }
        public String location() { return "inventory"; }
        public ItemStack item() { return ItemStack.EMPTY; }
        public boolean stillPresent(Minecraft minecraft) { return true; }
        public void open(Minecraft minecraft) { throw new AssertionError("reference resolution must not open"); }
        public boolean matches(KnownMenuProfileSupport.Context context) { return false; }
    }
}
