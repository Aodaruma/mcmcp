package dev.aod.mcmcp.client;

import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationIndicatorControllerTest {
    @Test
    void pauseButtonUsesTheLongestLabelAndClampsToTheScreen() {
        assertThat(AutomationIndicatorController.pauseButtonWidth(320, 60, 123, 80, 90))
                .isEqualTo(155);
        assertThat(AutomationIndicatorController.pauseButtonWidth(140, 60, 123, 80, 90))
                .isEqualTo(128);
    }

    @Test
    void lowerRightPlacementNeverCrossesTheTopOrLeftEdge() {
        assertThat(AutomationIndicatorController.lowerRightCoordinate(256, 20, 3))
                .isEqualTo(233);
        assertThat(AutomationIndicatorController.lowerRightCoordinate(10, 20, 3))
                .isZero();
    }

    @Test
    void activeAndIdlePressesAlwaysChooseTheSafetyStop() {
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.ACTIVE, false))
                .isEqualTo(AutomationIndicatorController.PressAction.DISABLE);
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.IDLE, false))
                .isEqualTo(AutomationIndicatorController.PressAction.DISABLE);
    }

    @Test
    void disabledStateRequiresTwoDistinctPressesToEnable() {
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.DISABLED, false))
                .isEqualTo(AutomationIndicatorController.PressAction.REQUEST_ENABLE_CONFIRMATION);
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.DISABLED, true))
                .isEqualTo(AutomationIndicatorController.PressAction.ENABLE);
    }
}
