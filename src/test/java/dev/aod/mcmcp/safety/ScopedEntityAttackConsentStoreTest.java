package dev.aod.mcmcp.safety;

import dev.aod.mcmcp.mcp.RuntimeCallContext;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedEntityAttackConsentStoreTest {
    private static final String HASH = "sha256:" + "1".repeat(64);
    private static final String OTHER_HASH = "sha256:" + "2".repeat(64);
    private static final String PROFILE_FINGERPRINT = "sha256:" + "3".repeat(64);
    private static final UUID SESSION =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ScopedEntityAttackConsentStore.Scope SCOPE = scope(
            List.of("minecraft:zombie", "minecraft:skeleton"), 256, 10, 36_000);

    @Test
    void onePhysicalGrantApprovesOneFiniteMultiMobOperationStart() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 10);
        assertThat(ScopedEntityAttackConsentUiBridge
                .grantFromPhysicalPromptClick(store, SESSION, 20)).isTrue();

        var granted = store.snapshot(SESSION, 20);
        assertThat(granted.state()).isEqualTo(ScopedEntityAttackConsentStore.State.GRANTED);
        assertThat(granted.policyBindingHash()).isEqualTo(HASH);
        assertThat(granted.consentRef()).hasSize(24);
        assertThat(granted.validBeforeClientTick()).isEqualTo(3_620L);
        assertThat(granted.scope().entityTypeAllowlist())
                .containsExactly("minecraft:skeleton", "minecraft:zombie");
        assertThat(granted.scope().maxAttacks()).isEqualTo(256);
        assertThat(granted.scope().maxOperationDurationTicks()).isEqualTo(36_000);
    }

    @Test
    void startTokenIsSingleConsumeRatherThanAPerAttackBearerLease() {
        var granted = grantedAtOne();
        assertThat(granted.store().consumeExactForActionStart(
                granted.ref(), SESSION, HASH, SCOPE, 2)).isTrue();
        assertThat(granted.store().consumeExactForActionStart(
                granted.ref(), SESSION, HASH, SCOPE, 2)).isFalse();
        assertThat(granted.store().snapshot(SESSION, 2).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void everyPolicyBindingMismatchFailsWithoutConsumingTheValidToken() {
        var otherSession = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertMismatchDoesNotConsume((store, ref) ->
                store.consumeExactForActionStart(ref, otherSession, HASH, SCOPE, 2));
        assertMismatchDoesNotConsume((store, ref) ->
                store.consumeExactForActionStart(ref, SESSION, OTHER_HASH, SCOPE, 2));
        assertMismatchDoesNotConsume((store, ref) ->
                store.consumeExactForActionStart(
                        ref, SESSION, HASH,
                        scope(List.of("minecraft:creeper"), 256, 10, 36_000), 2));
    }

    @Test
    void identicalRequestsAreIdempotentButDifferentLivePoliciesCannotReplaceThem() {
        var store = deterministicStore();
        var otherScope = scope(List.of("minecraft:creeper"), 16, 20, 1_200);
        assertThat(store.request(SESSION, HASH, SCOPE, 0))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
        assertThat(store.request(SESSION, HASH, SCOPE, 1))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.ALREADY_PENDING);
        assertThat(store.request(SESSION, OTHER_HASH, otherScope, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.BUSY);
        assertThat(ScopedEntityAttackConsentUiBridge
                .grantFromPhysicalPromptClick(store, SESSION, 3)).isTrue();
        assertThat(store.request(SESSION, HASH, SCOPE, 4))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.ALREADY_GRANTED);
        assertThat(store.request(SESSION, OTHER_HASH, otherScope, 5))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.BUSY);
    }

    @Test
    void pendingAndStartTokenExpireAtTheirThreeMinuteBoundaries() {
        var pending = deterministicStore();
        pending.request(SESSION, HASH, SCOPE, 10);
        assertThat(pending.snapshot(
                SESSION, 10 + ScopedEntityAttackConsentStore.PENDING_TTL_TICKS - 1L).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.PENDING);
        assertThat(pending.snapshot(
                SESSION, 10 + ScopedEntityAttackConsentStore.PENDING_TTL_TICKS).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
        assertThat(pending.request(
                SESSION, OTHER_HASH, SCOPE,
                10 + ScopedEntityAttackConsentStore.PENDING_TTL_TICKS))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);

        var granted = grantedAtOne();
        assertThat(granted.store().consumeExactForActionStart(
                granted.ref(), SESSION, HASH, SCOPE,
                1 + ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS)).isFalse();
        assertThat(granted.store().snapshot(
                SESSION, 1 + ScopedEntityAttackConsentStore.GRANTED_TTL_TICKS).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void transportApprovalConsumesOnlyTheExactPendingPolicyWithoutABearerRef() {
        var store = deterministicStore();
        store.request(
                SESSION, HASH, SCOPE,
                ScopedEntityAttackConsentStore.Channel.TRANSPORT, 10);
        var pending = store.snapshot(SESSION, 10);
        assertThat(pending.channel())
                .isEqualTo(ScopedEntityAttackConsentStore.Channel.TRANSPORT);
        assertThat(pending.approvalRequestState()).hasSize(24);
        assertThat(pending.consentRef()).isNull();

        assertThat(ScopedEntityAttackConsentTransportBridge.consumeApprovedPending(
                store, accepted(pending.approvalRequestState()),
                SESSION, OTHER_HASH, SCOPE, 11)).isFalse();
        assertThat(ScopedEntityAttackConsentTransportBridge.consumeApprovedPending(
                store, accepted("x".repeat(24)), SESSION, HASH, SCOPE, 11)).isFalse();
        assertThat(store.snapshot(SESSION, 11).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.PENDING);
        var approval = accepted(pending.approvalRequestState());
        assertThat(ScopedEntityAttackConsentTransportBridge.consumeApprovedPending(
                store, approval, SESSION, HASH, SCOPE, 12)).isTrue();
        assertThat(ScopedEntityAttackConsentTransportBridge.consumeApprovedPending(
                store, approval, SESSION, HASH, SCOPE, 12)).isFalse();
        assertThat(store.snapshot(SESSION, 12).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
        assertThat(ScopedEntityAttackConsentTransportBridge.ResponseCapability.class
                .getDeclaredConstructors()).allMatch(constructor ->
                        !java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
        assertThatThrownBy(() -> ScopedEntityAttackConsentTransportBridge
                .bindTransportResponse(RuntimeCallContext.ElicitationInput.awaitingResponse()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportDeclineClearsOnlyTheExactChallengeAndPolicy() {
        var store = deterministicStore();
        store.request(
                SESSION, HASH, SCOPE,
                ScopedEntityAttackConsentStore.Channel.TRANSPORT, 10);
        String challenge = store.snapshot(SESSION, 10).approvalRequestState();

        assertThat(ScopedEntityAttackConsentTransportBridge.rejectPending(
                store, rejected("x".repeat(24)), SESSION, HASH, SCOPE, 11)).isFalse();
        assertThat(store.snapshot(SESSION, 11).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.PENDING);
        assertThat(ScopedEntityAttackConsentTransportBridge.rejectPending(
                store, rejected(challenge), SESSION, HASH, SCOPE, 12)).isTrue();
        assertThat(store.snapshot(SESSION, 12).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    @Test
    void localAndTransportPendingChannelsCannotReplaceEachOther() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 0);
        assertThat(store.snapshot(SESSION, 0).channel())
                .isEqualTo(ScopedEntityAttackConsentStore.Channel.LOCAL_UI);
        assertThat(store.snapshot(SESSION, 0).approvalRequestState()).isNull();
        assertThat(store.request(
                SESSION, HASH, SCOPE,
                ScopedEntityAttackConsentStore.Channel.TRANSPORT, 1))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.BUSY);
    }

    @Test
    void backwardsTickRevokesAuthorityAndPoisonsUntilBoundaryClear() {
        var granted = grantedAtOne();
        assertThat(granted.store().consumeExactForActionStart(
                granted.ref(), SESSION, HASH, SCOPE, 0)).isFalse();
        assertThat(granted.store().snapshot(SESSION, 2).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
        assertThat(granted.store().request(SESSION, HASH, SCOPE, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.TICK_REJECTED);
        granted.store().clear();
        assertThat(granted.store().request(SESSION, HASH, SCOPE, 2))
                .isEqualTo(ScopedEntityAttackConsentStore.RequestResult.REGISTERED);
    }

    @Test
    void scopeContainsNoEntityRefAndHasHardSpatialBudgetAndPlayerCaps() {
        assertThat(ScopedEntityAttackConsentStore.Scope.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("entityRef", "entityType");
        assertThatThrownBy(() -> scope(List.of("minecraft:player"), 1, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope(List.of("minecraft:zombie"), 2_049, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope(List.of("minecraft:zombie"), 1, 9, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope(List.of("minecraft:zombie"), 1, 10, 36_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedEntityAttackConsentStore.Scope(
                "minecraft:overworld", bounds(0, 60, 0, 1.6, 62.5, 1),
                bounds(3, 60, 0, 4, 64, 2), List.of("minecraft:zombie"),
                "minecraft:iron_axe", PROFILE_FINGERPRINT,
                PROFILE_FINGERPRINT,
                ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET,
                1, 10, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedEntityAttackConsentStore.Scope(
                "minecraft:overworld", bounds(0, 60, 0, 1, 62.5, 1),
                bounds(1.4, 60, 0, 4, 64, 2), List.of("minecraft:zombie"),
                "minecraft:iron_axe", PROFILE_FINGERPRINT,
                PROFILE_FINGERPRINT,
                ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET,
                1, 10, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declaredAdapterProfileCanBindAModWeaponWithoutLeakingItsComponents() {
        var adapted = new ScopedEntityAttackConsentStore.Scope(
                "minecraft:overworld", bounds(0, 60, 0, 1, 62.5, 1),
                bounds(2, 60, 0, 4, 64, 2), List.of("examplemod:mob"),
                "examplemod:cleaver", PROFILE_FINGERPRINT,
                PROFILE_FINGERPRINT,
                ScopedEntityAttackConsentStore.AttackSideEffectProfile.ADAPTER_SINGLE_TARGET,
                32, 20, 6_000);
        assertThat(adapted.mainHandItem()).isEqualTo("examplemod:cleaver");
        assertThat(adapted.attackProfileFingerprint()).isEqualTo(PROFILE_FINGERPRINT);
    }

    @Test
    void bridgeMintsWithoutPublicCapabilityConstructionAndClearRevokes() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 0);
        assertThat(ScopedEntityAttackConsentUiBridge
                .grantFromPhysicalPromptClick(store, SESSION, 1)).isTrue();
        assertThat(ScopedEntityAttackConsentStore.LocalUiGrantCapability.class
                .getDeclaredConstructors()).allMatch(constructor ->
                        !java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
        store.clear();
        assertThat(store.snapshot(SESSION, 1).state())
                .isEqualTo(ScopedEntityAttackConsentStore.State.NONE);
    }

    private static void assertMismatchDoesNotConsume(ConsumeAttempt mismatch) {
        var granted = grantedAtOne();
        assertThat(mismatch.consume(granted.store(), granted.ref())).isFalse();
        assertThat(granted.store().consumeExactForActionStart(
                granted.ref(), SESSION, HASH, SCOPE, 2)).isTrue();
    }

    private static GrantedStore grantedAtOne() {
        var store = deterministicStore();
        store.request(SESSION, HASH, SCOPE, 0);
        store.grantFromPhysicalUiClick(
                new ScopedEntityAttackConsentStore.LocalUiGrantCapability(), SESSION, 1);
        return new GrantedStore(store, store.snapshot(SESSION, 1).consentRef());
    }

    private static ScopedEntityAttackConsentStore.Scope scope(
            List<String> types,
            int maxAttacks,
            long interval,
            long duration) {
        return new ScopedEntityAttackConsentStore.Scope(
                "minecraft:overworld",
                bounds(0, 60, 0, 1, 62.5, 1),
                bounds(2, 60, 0, 4, 64, 2),
                types,
                "minecraft:iron_axe",
                PROFILE_FINGERPRINT,
                PROFILE_FINGERPRINT,
                ScopedEntityAttackConsentStore.AttackSideEffectProfile.VANILLA_SINGLE_TARGET,
                maxAttacks,
                interval,
                duration);
    }

    private static ScopedEntityAttackConsentStore.Bounds bounds(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return new ScopedEntityAttackConsentStore.Bounds(
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static ScopedEntityAttackConsentStore deterministicStore() {
        return new ScopedEntityAttackConsentStore(new SecureRandom(new byte[]{1, 2, 3}));
    }

    private static ScopedEntityAttackConsentTransportBridge.ResponseCapability accepted(
            String requestState) {
        return response(requestState, RuntimeCallContext.ResponseAction.ACCEPT, true);
    }

    private static ScopedEntityAttackConsentTransportBridge.ResponseCapability rejected(
            String requestState) {
        return response(requestState, RuntimeCallContext.ResponseAction.DECLINE, false);
    }

    private static ScopedEntityAttackConsentTransportBridge.ResponseCapability response(
            String requestState,
            RuntimeCallContext.ResponseAction action,
            boolean approved) {
        return ScopedEntityAttackConsentTransportBridge.bindTransportResponse(
                new RuntimeCallContext.ElicitationInput(
                        true, requestState, action, approved));
    }

    @FunctionalInterface
    private interface ConsumeAttempt {
        boolean consume(ScopedEntityAttackConsentStore store, String ref);
    }

    private record GrantedStore(ScopedEntityAttackConsentStore store, String ref) {
    }
}
