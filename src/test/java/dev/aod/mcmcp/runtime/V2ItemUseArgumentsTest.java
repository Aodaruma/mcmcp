package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2ItemUseArgumentsTest {
    @Test
    void selectedItemNeedsNoInventoryReferenceAndModIdsAreAllowed() {
        var held = V2ItemUseArguments.parse(Map.of("target", "item"));
        assertThat(held.item()).isNull();
        assertThat(held.holdTicks()).isEqualTo(40);
        var mod = V2ItemUseArguments.parse(Map.of("target", "item",
                "item", "example:flask", "result_item", "example:empty_flask"));
        assertThat(mod.item()).isEqualTo("example:flask");
        assertThat(mod.resultItem()).isEqualTo("example:empty_flask");
    }

    @Test
    void longUseGetsADeadlineWithRoomForConfirmation() {
        var request = V2ItemUseArguments.parse(Map.of("target", "item", "hold_ticks", 1000));
        assertThat(request.maxTicks()).isEqualTo(1080);
        assertThatThrownBy(() -> V2ItemUseArguments.parse(Map.of(
                "target", "item", "hold_ticks", 40, "max_ticks", 40)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownFieldsAndUnboundedUse() {
        for (var args : java.util.List.of(
                Map.<String, Object>of("target", "item", "hold_ticks", 0),
                Map.<String, Object>of("target", "item", "hold_ticks", 1001),
                Map.<String, Object>of("target", "item", "max_ticks", 1201),
                Map.<String, Object>of("target", "item", "item", "unqualified"),
                Map.<String, Object>of("target", "item", "raw_packet", "use"))) {
            assertThatThrownBy(() -> V2ItemUseArguments.parse(args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
