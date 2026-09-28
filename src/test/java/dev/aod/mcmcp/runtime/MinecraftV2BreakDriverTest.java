package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftV2BreakDriverTest {
    @Test
    void correctHarvestToolBeatsFasterWrongTool() {
        var hand = new MinecraftV2BreakDriver.ToolCandidate(true, false, 1.0F);
        var hotbar = new ArrayList<>(Collections.nCopies(9, hand));
        hotbar.set(0, new MinecraftV2BreakDriver.ToolCandidate(true, false, 12.0F));
        hotbar.set(1, new MinecraftV2BreakDriver.ToolCandidate(true, true, 6.0F));
        assertThat(MinecraftV2BreakDriver.chooseHotbarTool(
                hotbar, 0)).isEqualTo(1);
    }

    @Test
    void emptyHandRemainsAvailableWhenNoToolIsPresent() {
        var hand = new MinecraftV2BreakDriver.ToolCandidate(true, true, 1.0F);
        var hotbar = new ArrayList<>(Collections.nCopies(9, hand));
        assertThat(MinecraftV2BreakDriver.chooseHotbarTool(
                hotbar, 5)).isEqualTo(5);
    }

    @Test
    void unusableToolFallsBackToAnEmptySlot() {
        var unavailable = new MinecraftV2BreakDriver.ToolCandidate(false, true, 12.0F);
        var hotbar = new ArrayList<>(Collections.nCopies(9, unavailable));
        hotbar.set(4, new MinecraftV2BreakDriver.ToolCandidate(true, false, 1.0F));
        assertThat(MinecraftV2BreakDriver.chooseHotbarTool(hotbar, 0)).isEqualTo(4);
    }
}
