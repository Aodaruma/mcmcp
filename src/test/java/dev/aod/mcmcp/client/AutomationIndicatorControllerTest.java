package dev.aod.mcmcp.client;

import dev.aod.mcmcp.runtime.AutomationUiSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationIndicatorControllerTest {
    @Test
    void statusButtonIsVisibleOnlyWhileAPlayerWorldIsActive() {
        assertThat(AutomationIndicatorController.statusButtonVisible(true, true)).isTrue();
        assertThat(AutomationIndicatorController.statusButtonVisible(false, true)).isFalse();
        assertThat(AutomationIndicatorController.statusButtonVisible(true, false)).isFalse();
        assertThat(AutomationIndicatorController.statusButtonVisible(false, false)).isFalse();
    }

    @Test
    void menuButtonUsesTheCurrentLabelAndClampsToTheScreen() {
        assertThat(AutomationIndicatorController.menuButtonWidth(320, 8, 60))
                .isEqualTo(96);
        assertThat(AutomationIndicatorController.menuButtonWidth(140, 8, 123))
                .isEqualTo(124);
    }

    @Test
    void lowerRightPlacementNeverCrossesTheTopOrLeftEdge() {
        assertThat(AutomationIndicatorController.lowerRightCoordinate(256, 16, 8))
                .isEqualTo(232);
        assertThat(AutomationIndicatorController.lowerRightCoordinate(10, 16, 8))
                .isZero();
    }

    @Test
    void chatButtonUsesTheTopRightWhileOtherScreensStayAtTheBottomRight() {
        assertThat(AutomationIndicatorController.screenButtonY(240, 24, 8, true))
                .isEqualTo(8);
        assertThat(AutomationIndicatorController.screenButtonY(240, 24, 8, false))
                .isEqualTo(208);
        assertThat(AutomationIndicatorController.screenButtonY(10, 24, 8, true))
                .isZero();
    }

    @Test
    void everyEnabledStatePressChoosesTheSafetyStop() {
        for (var state : new AutomationUiSnapshot.State[] {
                AutomationUiSnapshot.State.READY,
                AutomationUiSnapshot.State.EVALUATING,
                AutomationUiSnapshot.State.AGENT,
                AutomationUiSnapshot.State.RECOVERING
        }) {
            assertThat(AutomationIndicatorController.pressAction(state, false))
                    .isEqualTo(AutomationIndicatorController.PressAction.DISABLE);
            assertThat(AutomationIndicatorController.buttonActive(state, false)).isTrue();
        }
    }

    @Test
    void offStateEnablesInOnePressOnlyWhenTheWorldIsReady() {
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.OFF, true))
                .isEqualTo(AutomationIndicatorController.PressAction.ENABLE);
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.OFF, false))
                .isEqualTo(AutomationIndicatorController.PressAction.NONE);
        assertThat(AutomationIndicatorController.buttonActive(
                AutomationUiSnapshot.State.OFF, true)).isTrue();
        assertThat(AutomationIndicatorController.buttonActive(
                AutomationUiSnapshot.State.OFF, false)).isFalse();
    }

    @Test
    void pendingConsentDisablesTheOrdinaryStatusButton() {
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.CONSENT_PENDING, true))
                .isEqualTo(AutomationIndicatorController.PressAction.NONE);
        assertThat(AutomationIndicatorController.buttonActive(
                AutomationUiSnapshot.State.CONSENT_PENDING, true)).isFalse();
    }

    @Test
    void enablingRequiresAReadyWorldAndALivePlayer() {
        assertThat(AutomationIndicatorController.canEnable(true, true, true, true)).isTrue();
        assertThat(AutomationIndicatorController.canEnable(false, true, true, true)).isFalse();
        assertThat(AutomationIndicatorController.canEnable(true, false, true, true)).isFalse();
        assertThat(AutomationIndicatorController.canEnable(true, true, false, true)).isFalse();
        assertThat(AutomationIndicatorController.canEnable(true, true, true, false)).isFalse();
    }

    @Test
    void faultStateCannotBePressed() {
        assertThat(AutomationIndicatorController.pressAction(
                AutomationUiSnapshot.State.FAULT, true))
                .isEqualTo(AutomationIndicatorController.PressAction.NONE);
        assertThat(AutomationIndicatorController.buttonActive(
                AutomationUiSnapshot.State.FAULT, true)).isFalse();
    }
}
