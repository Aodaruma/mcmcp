package dev.aod.mcmcp.agent.safety;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PortalEntryGeometryTest {
    private final PortalEntryGeometry geometry = new PortalEntryGeometry(new BlockPos(0, 64, 0));
    private static AABB body(double x, double feet, double z) { return new AABB(x - .3, feet, z - .3, x + .3, feet + 1.8, z + .3); }

    @Test void entryRequiresFullHorizontalContainmentBeforeReplacingLandingProof() {
        assertThat(geometry.nearby(body(.5, 65, -.25))).isTrue();
        assertThat(geometry.overPortal(body(.5, 65, -.25))).isFalse();
        assertThat(geometry.overPortal(body(.5, 65, .2))).isFalse();
        assertThat(geometry.overPortal(body(.5, 65, .35))).isTrue();
        assertThat(geometry.contact(body(.5, 65, .5))).isFalse();
        assertThat(geometry.contact(body(.5, 64.7, .5))).isTrue();
    }

    @Test void cannotGrantUpwardFarDeepOrDivergingMovement() {
        assertThat(geometry.approaches(body(.5,65,-.25),body(.5,65,-.1))).isTrue();
        assertThat(geometry.approaches(body(.5,65,-.25),body(.5,65,-.4))).isFalse();
        assertThat(geometry.approaches(body(.5,65,.5),body(.5,65.1,.5))).isFalse();
        assertThat(geometry.approaches(body(.5,65,.5),body(.5,64.4,.5))).isFalse();
        assertThat(geometry.nearby(body(.5,64.1,.5))).isFalse();
        assertThat(geometry.nearby(body(3,65,.5))).isFalse();
        assertThat(geometry.nearby(new AABB(0,65,0,1,67,1))).isFalse();
    }
    @Test void netherEntryUsesSupportedLevelBottomCellAndCenteredContact() {
        var nether = new PortalEntryGeometry(new BlockPos(0,64,0), true);
        assertThat(nether.nearby(body(.5,64,-.5))).isTrue();
        assertThat(nether.contact(body(.5,64,-.5))).isFalse();
        assertThat(nether.contact(body(.5,64,.5))).isTrue();
        assertThat(nether.nearby(body(.5,65,.5))).isFalse();
        assertThat(nether.approaches(body(.5,64,-.5),body(.5,63.9,-.25))).isFalse();
    }
}
