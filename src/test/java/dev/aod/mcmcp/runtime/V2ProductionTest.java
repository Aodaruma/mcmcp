package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.routine.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class V2ProductionTest {
    @Test void brewValidatesPotionIdentityAndFiniteQuantities() {
        var args = new java.util.LinkedHashMap<String, Object>();
        args.putAll(Map.of("target", "block", "operation", "brew", "x", 0, "y", 64, "z", 0,
                "block", "minecraft:brewing_stand", "count", 3, "ingredient", "minecraft:nether_wart"));
        args.put("input", Map.of("item", "minecraft:potion", "potion", "minecraft:water"));
        args.put("output", Map.of("item", "minecraft:potion", "potion", "minecraft:awkward"));
        args.put("fuel", Map.of("item", "minecraft:blaze_powder", "max_count", 1));
        assertThat(V2ProductionArguments.parse(args, "minecraft:overworld").count()).isEqualTo(3);
        args.put("output", Map.of("item", "minecraft:potion", "potion", "minecraft:strength"));
        assertThatThrownBy(() -> V2ProductionArguments.parse(args, "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
        args.put("output", Map.of("item", "minecraft:potion", "potion", "minecraft:awkward"));
        args.put("count", 4);
        assertThatThrownBy(() -> V2ProductionArguments.parse(args, "minecraft:overworld")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void exactMoveRejectsSameSlotAndOutOfBoundsWithoutChangingAnything() {
        assertThat(V2InventoryRequest.parse(Map.of("operation", "move", "source_slot", 0, "destination_slot", 9,
                "item", "minecraft:coal", "count", 1))).isInstanceOf(V2InventoryMoveArguments.class);
        assertThatThrownBy(() -> new V2InventoryMoveArguments(0, 0, "minecraft:coal", 1, 400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new V2InventoryMoveArguments(0, 36, "minecraft:coal", 1, 400)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void productionBeginsOnceAndRetainsReleaseEvidenceThroughCancellation() {
        var port = new FakePort();
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        var request = new PhaseFiveRequest("smelt_items", Map.of(), new PhaseFiveBounds(target.dimension(), target, target, 0, 750, false), 2, "smelts");
        var attempt = new V2ProductionAttempt(port, request, 100);
        assertThat(attempt.tick(1)).isEqualTo(V2OperationJobExecution.Step.RUNNING);
        assertThat(attempt.tick(2)).isEqualTo(V2OperationJobExecution.Step.RUNNING);
        assertThat(port.begins).isEqualTo(1);
        assertThatThrownBy(attempt::close).isInstanceOf(IllegalStateException.class);
        assertThat(port.retires).isZero();
        assertThat(attempt.result()).containsEntry("processing_may_continue", true);
        attempt.close();
        assertThat(port.retires).isEqualTo(1);
        attempt.close();
        assertThat(port.retires).isEqualTo(1);
        assertThat(attempt.result()).doesNotContainKey("private_payload");
    }

    private static final class FakePort implements PhaseFivePort {
        int begins, releases, retires;
        boolean identityLost;
        public PhaseFiveFrame observe(PhaseFiveRequest request) { return new PhaseFiveFrame(1, 1, null); }
        public PhaseFiveAttempt begin(UUID owner, PhaseFiveRequest request, long deadline) {
            begins++;
            return new PhaseFiveAttempt(owner, request.kind(), 1, 1, deadline, Map.of());
        }
        public void maintain(PhaseFiveAttempt attempt) { }
        public PhaseFiveEvidence evidence(PhaseFiveAttempt attempt) {
            return new PhaseFiveEvidence.Pending(attempt.attemptId(), identityLost ? 0 : 1, identityLost ? 0 : 1,
                    Map.of("release_pending", releases == 1, "private_payload", "excluded"));
        }
        public void release(PhaseFiveAttempt attempt) { if (++releases == 1 && !identityLost) throw new IllegalStateException("release pending"); }
        public void retire(PhaseFiveRequest request) { retires++; }
    }

    @Test void lostSessionEvidenceCannotPreventIdentityRelease() {
        var port = new FakePort();
        var target = new BlockTarget("minecraft:overworld", 0, 64, 0);
        var request = new PhaseFiveRequest("smelt_items", Map.of(), new PhaseFiveBounds(target.dimension(), target, target, 0, 750, false), 2, "smelts");
        var attempt = new V2ProductionAttempt(port, request, 100);
        attempt.tick(1);
        port.identityLost = true;
        attempt.close();
        assertThat(port.releases).isEqualTo(1);
        assertThat(port.retires).isEqualTo(1);
        assertThat(attempt.result()).containsEntry("release_evidence_unavailable", true)
                .containsEntry("confirmed", false);
    }
}
