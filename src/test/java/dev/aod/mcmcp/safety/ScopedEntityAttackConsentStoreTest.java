package dev.aod.mcmcp.safety;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedEntityAttackConsentStoreTest {
    private static final String HASH = "sha256:" + "1".repeat(64);
    private static final String OTHER_HASH = "sha256:" + "2".repeat(64);
    private static final UUID SESSION =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ENTITY_REF = "abcdefghijklmnopqrstuvwx";
    private static final ScopedEntityAttackConsentStore.Scope SCOPE = scope(
            ENTITY_REF, "minecraft:zombie");

    @Test
    void localUiCapabilityMintsOneShortLivedGrantAndCannotBeReplayed() {
        var store = deterministicStore();
        var click = new ScopedEntityAttackConsentStore.LocalUiGrantCapability();

        assertThat(store.request(SESSION, HASH, SCOPE, 10))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
        assertThat(store.grantFromPhysicalUiClick(click, SESSION, 20)).isTrue();
        var granted = store.snapshot(SESSION, 20);
        assertThat(granted.state()).isEqualTo(ScopedEntityAttackConsentStore.State.GRANTED);
        assertThat(granted.consentRef()).hasSize(24);
        assertThat(granted.scope().entityRef()).isEqualTo(ENTITY_REF);
        assertThat(granted.validBeforeClientTick())
                .isEqualTo(20 + ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS);

        store.clear();
        assertThat(store.request(SESSION, HASH, SCOPE, 21))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
        assertThat(store.grantFromPhysicalUiClick(click, SESSION, 21)).isFalse();
    }

    @Test
    void identicalRequestsAreIdempotentButDifferentLiveAuthorityCannotReplaceThem() {
        var store = deterministicStore();
        var otherScope = scope("zyxwvutsrqponmlkjihgfedc", "minecraft:skeleton");

        assertThat(store.request(SESSION, HASH, SCOPE, 0))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
        assertThat(store.request(SESSION, HASH, SCOPE, 1))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.ALREADY_PENDING);
        assertThat(store.request(SESSION, OTHER_HASH, otherScope, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.BUSY);
        assertThat(store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(), SESSION, 3)).isTrue();
        assertThat(store.request(SESSION, HASH, SCOPE, 4))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.ALREADY_GRANTED);
        assertThat(store.request(SESSION, OTHER_HASH, otherScope, 5))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.BUSY);
    }

    @Test
    void consumeExactRejectsEveryBindingDriftIncludingEntityReference() {
        var otherSession = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertBindingMismatchDoesNotConsume((store, ref) ->
                store.consumeExact(ref, otherSession, HASH, SCOPE, 2));
        assertBindingMismatchDoesNotConsume((store, ref) ->
                store.consumeExact(ref, SESSION, OTHER_HASH, SCOPE, 2));
        assertBindingMismatchDoesNotConsume((store, ref) ->
                store.consumeExact(ref, SESSION, HASH,
                        scope("zyxwvutsrqponmlkjihgfedc", "minecraft:zombie"), 2));
        assertBindingMismatchDoesNotConsume((store, ref) ->
                store.consumeExact(ref, SESSION, HASH,
                        scope(ENTITY_REF, "minecraft:skeleton"), 2));
    }

    @Test
    void consumeExactIsAtomicAndSingleUse() {
        var granted = grantedAtOne();
        var store = granted.store();

        assertThat(store.consumeExact(granted.ref(), SESSION, HASH, SCOPE, 2)).isTrue();
        assertThat(store.consumeExact(granted.ref(), SESSION, HASH, SCOPE, 2)).isFalse();
        assertThat(store.snapshot(SESSION, 2).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void pendingAndGrantedExpireAtTheExactExclusiveBoundary() {
        var pending = deterministicStore();
        assertThat(pending.request(SESSION, HASH, SCOPE, 10))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
        assertThat(pending.snapshot(
                SESSION, 10 + ScopedEntityAttackConsentStore.PENDING_TTL_TICKS).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);

        var granted = grantedAtOne();
        assertThat(granted.store().consumeExact(
                granted.ref(), SESSION, HASH, SCOPE,
                1 + ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS)).isFalse();
        assertThat(granted.store().snapshot(
                SESSION, 1 + ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void backwardsTickRevokesAuthorityAndPoisonsStoreUntilExplicitBoundaryClear() {
        var granted = grantedAtOne();
        var store = granted.store();

        assertThat(store.consumeExact(granted.ref(), SESSION, HASH, SCOPE, 0)).isFalse();
        assertThat(store.snapshot(SESSION, 2).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
        assertThat(store.request(SESSION, HASH, SCOPE, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.TICK_REJECTED);

        store.clear();
        assertThat(store.request(SESSION, HASH, SCOPE, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
    }

    @Test
    void wrongWorldCannotGrantAndBoundaryClearRevokesPendingAndGranted() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 0);
        assertThat(store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1)).isFalse();
        assertThat(store.snapshot(SESSION, 1).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.PENDING);
        store.clear();
        assertThat(store.snapshot(SESSION, 1).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);

        store.request(SESSION, HASH, SCOPE, 2);
        store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(), SESSION, 3);
        store.clear();
        assertThat(store.snapshot(SESSION, 3).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void scopeIsExactlyOneEntityAndOneSemanticAttackByConstruction() {
        assertThat(ScopedEntityAttackConsentStore.Scope.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("dimension", "bounds", "entityRef", "entityType");
    }

    private static void assertBindingMismatchDoesNotConsume(ConsumeAttempt mismatch) {
        var granted = grantedAtOne();
        assertThat(mismatch.consume(granted.store(), granted.ref())).isFalse();
        assertThat(granted.store().consumeExact(
                granted.ref(), SESSION, HASH, SCOPE, 2)).isTrue();
    }

    private static GrantedStore grantedAtOne() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 0);
        store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(), SESSION, 1);
        return new GrantedStore(store, store.snapshot(SESSION, 1).consentRef());
    }

    private static ScopedEntityAttackConsentStore deterministicStore() {
        return new ScopedEntityAttackConsentStore(new SecureRandom(new byte[]{1, 2, 3}));
    }

    private static ScopedEntityAttackConsentStore.Scope scope(
            String entityRef, String entityType) {
        return new ScopedEntityAttackConsentStore.Scope(
                "minecraft:overworld",
                new ScopedEntityAttackConsentStore.Bounds(0, 60, 0, 4, 64, 4),
                entityRef,
                entityType);
    }

    @FunctionalInterface
    private interface ConsumeAttempt {
        boolean consume(ScopedEntityAttackConsentStore store, String ref);
    }

    private record GrantedStore(ScopedEntityAttackConsentStore store, String ref) {
    }
}
