package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationMutationInvalidationTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void neutralBlockMutationDoesNotInvalidateNearbyNavigationCells() {
        var mutation = new ClientReconciliationSignals.WorldMutation(
                1,
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                10, 64, 10,
                ClientReconciliationSignals.NavigationImpact.NONE);

        assertThat(McmcpRuntime.mutationAffects(
                mutation, new NavCell(DIMENSION, 10, 64, 10))).isFalse();
        assertThat(McmcpRuntime.mutationAffects(
                mutation, new NavCell(DIMENSION, 11, 67, 11))).isFalse();
    }

    @Test
    void relevantBlockAndChunkMutationsKeepTheExistingLimitedScope() {
        var block = new ClientReconciliationSignals.WorldMutation(
                1,
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                10, 64, 10);

        assertThat(McmcpRuntime.mutationAffects(
                block, new NavCell(DIMENSION, 9, 62, 9))).isTrue();
        assertThat(McmcpRuntime.mutationAffects(
                block, new NavCell(DIMENSION, 11, 67, 11))).isTrue();
        assertThat(McmcpRuntime.mutationAffects(
                block, new NavCell(DIMENSION, 12, 64, 10))).isFalse();
        assertThat(McmcpRuntime.mutationAffects(
                block, new NavCell(DIMENSION, 10, 68, 10))).isFalse();

        var chunk = new ClientReconciliationSignals.WorldMutation(
                2,
                ClientReconciliationSignals.WorldMutation.Kind.CHUNK,
                3, 0, -2);
        assertThat(McmcpRuntime.mutationAffects(
                chunk, new NavCell(DIMENSION, 48, 64, -32))).isTrue();
        assertThat(McmcpRuntime.mutationAffects(
                chunk, new NavCell(DIMENSION, 47, 64, -32))).isFalse();
    }
}
