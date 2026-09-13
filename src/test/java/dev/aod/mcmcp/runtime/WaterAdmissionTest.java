package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentPrimitivePlanner.Pose;
import dev.aod.mcmcp.agent.navigation.NavCell;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WaterAdmissionTest {
    private static Pose pose(double x, double y, float yaw, double eye) {
        return new Pose(new NavCell("minecraft:overworld", (int) Math.floor(x), (int) Math.floor(y), 5),
                x, y, 5.5, eye, yaw, 0);
    }

    @Test
    void driftMustStaySmallFreshImmersedAndInTheSameCellWithoutChangingViewOrPose() {
        var start = pose(-1.5, 60.5, 90, 1.62);
        var drift = pose(-1.6, 60.4, 90, 1.62);
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, drift, true, 2)).isTrue();
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, drift, false, 2)).isFalse();
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, drift, true, 6)).isFalse();
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, pose(-1.5, 60.2, 90, 1.62), true, 2)).isFalse();
        assertThat(ActionAdmission.boundedWaterPoseDrift(pose(-1.5, 60.05, 90, 1.62),
                pose(-1.5, 59.95, 90, 1.62), true, 2)).isFalse();
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, pose(-1.5, 60.4, 91, 1.62), true, 2)).isFalse();
        assertThat(ActionAdmission.boundedWaterPoseDrift(start, pose(-1.5, 60.4, 90, 1.27), true, 2)).isFalse();
    }
}
