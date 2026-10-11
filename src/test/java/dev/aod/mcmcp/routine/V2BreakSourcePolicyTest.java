package dev.aod.mcmcp.routine;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V2BreakSourcePolicyTest {
    @Test
    void acceptsVanillaTargetsBeyondTheOldClosedListIncludingBlockEntities() {
        assertThat(List.of(Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.CHEST,
                Blocks.TNT, Blocks.INFESTED_STONE))
                .allSatisfy(block -> assertThat(V2BreakSourcePolicy.allowsLiveState(
                        block.defaultBlockState())).isTrue());
        assertThat(SafeBreakSourcePolicy.allowsLiveState(
                Blocks.CHEST.defaultBlockState(), true)).isFalse();
        assertThat(V2BreakSourcePolicy.allowsLiveState(Blocks.AIR.defaultBlockState())).isFalse();
    }
}
