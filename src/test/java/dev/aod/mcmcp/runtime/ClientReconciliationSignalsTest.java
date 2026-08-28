package dev.aod.mcmcp.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientReconciliationSignalsTest {
    @Test
    void surfaceBarrierTracksOnlyTheMutatedPositionUntilALocalMutation() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        var target = new BlockPos(1, 65, 1);
        var unrelated = new BlockPos(2, 65, 1);

        channel.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                target.getX(), target.getY(), target.getZ(),
                ClientReconciliationSignals.NavigationImpact.NONE);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                target.getX(), target.getY(), target.getZ())).isEqualTo(1L);

        channel.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                unrelated.getX(), unrelated.getY(), unrelated.getZ(),
                ClientReconciliationSignals.NavigationImpact.NONE);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                target.getX(), target.getY(), target.getZ())).isEqualTo(1L);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                unrelated.getX(), unrelated.getY(), unrelated.getZ())).isEqualTo(2L);

        channel.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                target.getX(), target.getY(), target.getZ(),
                ClientReconciliationSignals.NavigationImpact.NONE);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                target.getX(), target.getY(), target.getZ())).isEqualTo(3L);

        channel.worldMutation(
                ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                20, 64, 20,
                ClientReconciliationSignals.NavigationImpact.LOCAL);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                target.getX(), target.getY(), target.getZ())).isEqualTo(4L);
        assertThat(channel.snapshot().surfaceBarrierWorldRevision(
                unrelated.getX(), unrelated.getY(), unrelated.getZ())).isEqualTo(4L);
    }

    @Test
    void evictedSurfaceMutationRevisionsRaiseAConservativeFloorAndRebindClearsIt() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        for (int index = 0;
                index <= ClientReconciliationSignals.MAX_SURFACE_MUTATION_REVISIONS;
                index++) {
            channel.worldMutation(
                    ClientReconciliationSignals.WorldMutation.Kind.BLOCK,
                    index, 65, 0,
                    ClientReconciliationSignals.NavigationImpact.NONE);
        }

        var full = channel.snapshot();
        assertThat(full.surfaceMutationRevisions())
                .hasSize(ClientReconciliationSignals.MAX_SURFACE_MUTATION_REVISIONS)
                .doesNotContainKey(new BlockPos(0, 65, 0));
        assertThat(full.surfaceMutationEvictionFloor()).isEqualTo(1L);
        assertThat(full.surfaceBarrierWorldRevision(999, 65, 999)).isEqualTo(1L);

        var rebound = channel.bindAndSnapshot(UUID.randomUUID());
        assertThat(rebound.surfaceMutationRevisions()).isEmpty();
        assertThat(rebound.surfaceMutationEvictionFloor()).isZero();
        assertThat(rebound.surfaceBarrierWorldRevision(999, 65, 999)).isZero();
    }

    @Test
    void cropEvolutionIsNavigationNeutralButGeometryChangesRemainRelevant() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        var crop = new BlockPos(1, 65, 1);
        var support = crop.below();
        var gate = new BlockPos(2, 64, 1);
        var solid = new BlockPos(3, 64, 1);

        channel.blockMutation(
                crop,
                ClientReconciliationSignals.NavigationClass.WHEAT,
                ClientReconciliationSignals.NavigationClass.WHEAT);
        channel.blockMutation(
                crop,
                ClientReconciliationSignals.NavigationClass.WHEAT,
                ClientReconciliationSignals.NavigationClass.WHEAT);
        channel.blockMutation(
                crop,
                ClientReconciliationSignals.NavigationClass.AIR,
                ClientReconciliationSignals.NavigationClass.AIR);
        channel.blockMutation(
                support,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().visualBarrierWorldRevision()).isEqualTo(4L);
        channel.blockMutation(
                support,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldRevision()).isEqualTo(5L);
        assertThat(channel.snapshot().visualBarrierWorldRevision()).isEqualTo(4L);
        channel.blockMutation(
                gate,
                ClientReconciliationSignals.NavigationClass.OTHER,
                ClientReconciliationSignals.NavigationClass.OTHER);
        channel.blockMutation(
                solid,
                ClientReconciliationSignals.NavigationClass.OTHER,
                ClientReconciliationSignals.NavigationClass.AIR);

        assertThat(channel.snapshot().worldMutations())
                .extracting(ClientReconciliationSignals.WorldMutation::navigationImpact)
                .containsExactly(
                        ClientReconciliationSignals.NavigationImpact.NONE,
                        ClientReconciliationSignals.NavigationImpact.NONE,
                        ClientReconciliationSignals.NavigationImpact.NONE,
                        ClientReconciliationSignals.NavigationImpact.LOCAL,
                        ClientReconciliationSignals.NavigationImpact.NONE,
                        ClientReconciliationSignals.NavigationImpact.LOCAL,
                        ClientReconciliationSignals.NavigationImpact.LOCAL);
        assertThat(channel.snapshot().worldRevision()).isEqualTo(7);
        assertThat(channel.snapshot().visualRevision()).isEqualTo(3);
        assertThat(channel.snapshot().visualBarrierWorldRevision()).isEqualTo(7L);
    }

    @Test
    void authoritativeNavigationCacheIsBoundedAndClearedBySessionRebind() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        var oldest = new BlockPos(0, 64, 0);
        channel.blockMutation(
                oldest,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        for (int index = 1;
                index <= ClientReconciliationSignals.MAX_AUTHORITATIVE_BLOCK_STATES;
                index++) {
            channel.blockMutation(
                    new BlockPos(index, 64, 0),
                    ClientReconciliationSignals.NavigationClass.WHEAT,
                    ClientReconciliationSignals.NavigationClass.WHEAT);
        }
        channel.blockMutation(
                oldest,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldMutations().getLast().navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);

        channel.bindAndSnapshot(UUID.randomUUID());
        channel.blockMutation(
                oldest,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldRevision()).isOne();
        assertThat(channel.snapshot().worldMutations().getLast().navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);
    }

    @Test
    void unknownBlockChunkAndAllMutationsDropPotentiallyStaleAuthoritativeCache() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        channel.bindAndSnapshot(UUID.randomUUID());
        var first = new BlockPos(1, 64, 1);
        var second = new BlockPos(33, 64, 1);
        channel.blockMutation(
                first,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        channel.blockMutation(
                second,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);

        channel.unknownBlockMutation(first);
        channel.blockMutation(
                first,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldMutations().getLast().navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);

        long beforeChunkVisualRevision = channel.snapshot().visualRevision();
        channel.worldMutation(ClientReconciliationSignals.WorldMutation.Kind.CHUNK, 2, 0, 0);
        assertThat(channel.snapshot().visualRevision())
                .isEqualTo(beforeChunkVisualRevision + 1L);
        assertThat(channel.snapshot().visualBarrierWorldRevision())
                .isEqualTo(channel.snapshot().worldRevision());
        channel.blockMutation(
                second,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldMutations().getLast().navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);

        long beforeAllVisualRevision = channel.snapshot().visualRevision();
        channel.worldMutation();
        assertThat(channel.snapshot().visualRevision())
                .isEqualTo(beforeAllVisualRevision + 1L);
        assertThat(channel.snapshot().visualBarrierWorldRevision())
                .isEqualTo(channel.snapshot().worldRevision());
        channel.blockMutation(
                first,
                ClientReconciliationSignals.NavigationClass.FARMLAND,
                ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(channel.snapshot().worldMutations().getLast().navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);
    }

    @Test
    void allowlistMapsOnlyVanillaAirWheatAndFarmlandFamilies() {
        var wheatAgeZero = Blocks.WHEAT.defaultBlockState();
        var wheatAgeSeven = wheatAgeZero.setValue(BlockStateProperties.AGE_7, 7);
        var dryFarmland = Blocks.FARMLAND.defaultBlockState();
        var wetFarmland = dryFarmland.setValue(BlockStateProperties.MOISTURE, 7);
        var closedGate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        var openGate = closedGate.setValue(BlockStateProperties.OPEN, true);

        assertThat(wheatAgeZero.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty())
                .isTrue();
        assertThat(wheatAgeSeven.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty())
                .isTrue();
        assertThat(dryFarmland.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs())
                .isEqualTo(wetFarmland.getCollisionShape(
                        EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs());
        assertThat(closedGate.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs())
                .isNotEqualTo(openGate.getCollisionShape(
                        EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs());

        assertThat(ClientReconciliationSignals.navigationClass(Blocks.AIR.defaultBlockState()))
                .isEqualTo(ClientReconciliationSignals.NavigationClass.AIR);
        assertThat(ClientReconciliationSignals.navigationClass(wheatAgeSeven))
                .isEqualTo(ClientReconciliationSignals.NavigationClass.WHEAT);
        assertThat(ClientReconciliationSignals.navigationClass(wetFarmland))
                .isEqualTo(ClientReconciliationSignals.NavigationClass.FARMLAND);
        assertThat(ClientReconciliationSignals.navigationClass(closedGate))
                .isEqualTo(ClientReconciliationSignals.NavigationClass.OTHER);
        assertThat(ClientReconciliationSignals.navigationClass(
                Blocks.MAGMA_BLOCK.defaultBlockState()))
                .isEqualTo(ClientReconciliationSignals.NavigationClass.OTHER);

        var compatibleConstructor = new ClientReconciliationSignals.WorldMutation(
                1, ClientReconciliationSignals.WorldMutation.Kind.BLOCK, 1, 2, 3);
        assertThat(compatibleConstructor.navigationImpact())
                .isEqualTo(ClientReconciliationSignals.NavigationImpact.LOCAL);
    }

    @Test
    void revisionsAreIndependentAndSelectedInventoryRequiresRelevantPacket() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        var session = UUID.randomUUID();
        var baseline = channel.bindAndSnapshot(session);

        channel.positionCorrection(new ClientReconciliationSignals.PositionCorrection(
                7, new Vec3(1, 2, 3)));
        channel.serverRotation(new ClientReconciliationSignals.ServerRotation(90, 15));
        channel.localMotion(new ClientReconciliationSignals.LocalMotion(
                new Vec3(0.1, 0, 0), "set_entity_motion"));
        channel.inventorySync(new ClientReconciliationSignals.InventorySync(
                "container_slot", 12, false, "minecraft:bucket", 1));
        channel.inventorySync(new ClientReconciliationSignals.InventorySync(
                "player_inventory", 0, true, "minecraft:milk_bucket", 1));
        channel.worldMutation();
        channel.worldMutation();
        var current = channel.snapshot();

        assertThat(baseline.positionCorrectionRevision()).isZero();
        assertThat(current.positionCorrectionRevision()).isOne();
        assertThat(current.rotationRevision()).isOne();
        assertThat(current.motionRevision()).isOne();
        assertThat(current.inventoryRevision()).isEqualTo(2);
        assertThat(current.selectedSlotInventoryRevision()).isOne();
        assertThat(current.worldRevision()).isEqualTo(2);
        assertThat(current.visualRevision()).isEqualTo(2);
        assertThat(current.visualBarrierWorldRevision()).isEqualTo(2);
        assertThat(current.worldMutations()).extracting(ClientReconciliationSignals.WorldMutation::revision)
                .containsExactly(1L, 2L);
        assertThat(current.worldMutations()).extracting(ClientReconciliationSignals.WorldMutation::kind)
                .containsOnly(ClientReconciliationSignals.WorldMutation.Kind.ALL);
        assertThat(current.lastPositionCorrection().teleportId()).isEqualTo(7);
        assertThat(current.lastInventorySync().selectedItemId()).isEqualTo("minecraft:milk_bucket");
    }

    @Test
    void rebindingAWorldSessionDropsAllPriorEvidence() {
        var channel = new ClientReconciliationSignals.SessionChannel();
        var first = UUID.randomUUID();
        channel.bindAndSnapshot(first);
        channel.positionCorrection(new ClientReconciliationSignals.PositionCorrection(
                3, Vec3.ZERO));

        var second = channel.bindAndSnapshot(UUID.randomUUID());

        assertThat(second.worldSessionId()).isNotEqualTo(first);
        assertThat(second.positionCorrectionRevision()).isZero();
        assertThat(second.worldRevision()).isZero();
        assertThat(second.visualRevision()).isZero();
        assertThat(second.visualBarrierWorldRevision()).isZero();
        assertThat(second.worldMutations()).isEmpty();
        assertThat(second.lastPositionCorrection()).isNull();
        assertThat(second.sameSession(channel.bindAndSnapshot(second.worldSessionId()))).isTrue();
    }
}
