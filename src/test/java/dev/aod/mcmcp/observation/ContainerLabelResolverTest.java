package dev.aod.mcmcp.observation;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerLabelResolverTest {
    @Test
    void singleCopperChestsCanCarryRoutingLabelsButDoubleAndOtherContainersCannot() {
        for (var block : Blocks.COPPER_CHEST.asList()) {
            var single = block.defaultBlockState()
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE);
            assertThat(ContainerLabelResolver.policyVisibleSingleContainer(single)).isTrue();
            assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                    single.setValue(ChestBlock.TYPE, ChestType.LEFT))).isFalse();
            assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                    single.setValue(ChestBlock.TYPE, ChestType.RIGHT))).isFalse();
        }

        assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                Blocks.CHEST.defaultBlockState())).isTrue();
        assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                Blocks.BARREL.defaultBlockState())).isTrue();
        assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                Blocks.TRAPPED_CHEST.defaultBlockState())).isFalse();
        assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                Blocks.ENDER_CHEST.defaultBlockState())).isFalse();
        assertThat(ContainerLabelResolver.policyVisibleSingleContainer(
                Blocks.SHULKER_BOX.defaultBlockState())).isFalse();
    }
}
