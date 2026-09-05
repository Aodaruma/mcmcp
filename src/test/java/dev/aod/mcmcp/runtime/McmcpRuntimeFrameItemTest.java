package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeFrameItemTest {
    private static final String REF = "abcdefghijklmnopqrstuvwx";
    private static final Vec3 AIM = new Vec3(1.5D, 65.5D, 2.9375D);

    @Test
    void frameOperationsReserveOneInteractionAndTheEntireFiniteEnvelope() {
        for (ActionDsl.Node node : new ActionDsl.Node[] {
                new ActionDsl.RemoveVisibleFrameItem("remove", REF, "minecraft:stone"),
                new ActionDsl.InsertVisibleFrameItem("insert", REF, "minecraft:torch")}) {
            var cost = McmcpRuntime.structuralPrimitiveCost(node).orElseThrow();
            assertThat(cost.durationMillis()).isEqualTo(20_000L);
            assertThat(cost.ticks()).isEqualTo(400L);
            assertThat(cost.cameraDegrees()).isEqualTo(360.0D);
            assertThat(cost.interactions()).isEqualTo(1L);
            assertThat(cost.distanceBlocks()).isZero();
            assertThat(cost.blocksBroken()).isZero();
            assertThat(cost.blocksPlaced()).isZero();
        }
    }

    @Test
    void freshFrameRaysAreRequestedOnlyForTheExactFramePrimitive() {
        assertThat(McmcpRuntime.frameItemTargetRef(
                new ActionDsl.RemoveVisibleFrameItem("remove", REF, "minecraft:stone"))).isEqualTo(REF);
        assertThat(McmcpRuntime.frameItemTargetRef(
                new ActionDsl.InsertVisibleFrameItem("insert", REF, "minecraft:torch"))).isEqualTo(REF);
        assertThat(McmcpRuntime.frameItemTargetRef(null)).isNull();
        assertThat(McmcpRuntime.frameItemTargetRef(new ActionDsl.WaitTicks("wait", 1))).isNull();
    }

    @Test
    void refreshedDeliveryCanAdvanceClocksWithoutChangingTheAuthorizedOperation() {
        var original = aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 10, 4);
        assertThat(McmcpRuntime.sameFrameItemAuthorization(original, original)).isTrue();
        assertThat(McmcpRuntime.sameFrameItemAuthorization(original,
                aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 11, 5))).isTrue();
        assertThat(McmcpRuntime.sameFrameItemAuthorization(original,
                aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 9, 4))).isFalse();
        assertThat(McmcpRuntime.sameFrameItemAuthorization(original,
                aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 11, 3))).isFalse();
    }

    @Test
    void actualClientTickBoundsRawEntityAgeIndependentlyOfCompositeFrameRefresh() {
        var observed = aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 10, 4);
        assertThat(McmcpRuntime.frameItemEvidenceFresh(observed, 9)).isFalse();
        assertThat(McmcpRuntime.frameItemEvidenceFresh(observed, 10)).isTrue();
        assertThat(McmcpRuntime.frameItemEvidenceFresh(observed, 110)).isTrue();
        assertThat(McmcpRuntime.frameItemEvidenceFresh(observed, 111)).isFalse();
    }

    @Test
    void admissionRejectsAnyChangedFrameDisplayIdentityOrDeliveredAim() {
        var original = aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 10, 4);
        var changed = new AgentPrimitivePlanner.FrameItemAim[] {
                aim("ABCDEFGHIJKLMNOPQRSTUVWX", "minecraft:item_frame", "minecraft:stone", null, 3, AIM, 10, 4),
                aim(REF, "minecraft:glow_item_frame", "minecraft:stone", null, 3, AIM, 10, 4),
                aim(REF, "minecraft:item_frame", "minecraft:dirt", null, 3, AIM, 10, 4),
                aim(REF, "minecraft:item_frame", "minecraft:stone", null, 4, AIM, 10, 4),
                aim(REF, "minecraft:item_frame", "minecraft:stone", null, 3, AIM.add(0.01, 0, 0), 10, 4),
                aim(REF, "minecraft:item_frame", null, "minecraft:stone", 3, AIM, 10, 4)
        };
        for (var candidate : changed) {
            assertThat(McmcpRuntime.sameFrameItemAuthorization(original, candidate)).isFalse();
        }
        assertThat(McmcpRuntime.sameFrameItemAuthorization(original, null)).isFalse();
        assertThat(McmcpRuntime.sameFrameItemAuthorization(null, original)).isFalse();
    }

    private static AgentPrimitivePlanner.FrameItemAim aim(
            String ref, String type, String before, String inserted, int rotation,
            Vec3 point, long tick, long revision) {
        return new AgentPrimitivePlanner.FrameItemAim(ref, type,
                Optional.ofNullable(before), Optional.ofNullable(inserted), rotation, point, tick, revision);
    }
}
