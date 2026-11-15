package dev.aod.mcmcp.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WaystoneMenuSupportTest {
    @Test void preservesServerBalancesAndDoesNotInferAbsentCosts() throws ReflectiveOperationException {
        var points = new net.blay09.mods.shogi.common.effect.cost.ExperiencePointsCostInformation(27, 3442L);
        var levels = new net.blay09.mods.shogi.common.effect.cost.ExperienceLevelCostInformation(2, 43);
        var costs = WaystoneMenuSupport.readCosts(List.of(points, levels));
        assertThat(costs.availablePoints()).isEqualTo(3442L);
        assertThat(costs.availableLevels()).isEqualTo(43L);
        assertThat(costs.points()).isEqualTo(27);
        assertThat(costs.supported()).isTrue();
        var absent = WaystoneMenuSupport.readCosts(List.of(true));
        assertThat(absent.availablePoints()).isNull();
        assertThat(absent.availableLevels()).isNull();
    }

    @Test void repeatedCostsUseConservativeBalanceAndTotalPayment() throws ReflectiveOperationException {
        var costs = WaystoneMenuSupport.readCosts(List.of(
                new net.blay09.mods.shogi.common.effect.cost.ExperiencePointsCostInformation(27, 50),
                new net.blay09.mods.shogi.common.effect.cost.ExperiencePointsCostInformation(27, 40)));
        assertThat(costs.availablePoints()).isEqualTo(40L);
        assertThat(costs.points()).isEqualTo(54);
        assertThat(costs.supported()).isFalse();
    }

    @Test void nativeRuleScalarResultsDoNotBecomeUnsupportedCosts() throws ReflectiveOperationException {
        var costs = WaystoneMenuSupport.readCosts(List.of(new JsonPrimitive(0.01), new JsonPrimitive("300s"),
                new JsonPrimitive(true), 27f, 0d, 3, 4L, "setting", true));
        assertThat(costs.supported()).isTrue();
        assertThat(costs.levels()).isZero();
        assertThat(costs.points()).isZero();
        assertThat(costs.messages()).isEmpty();
    }

    @Test void unrecognizedStructuredRequirementsStillPreventSelection() throws ReflectiveOperationException {
        var costs = WaystoneMenuSupport.readCosts(List.of(true, new JsonObject(), new Object()));
        assertThat(costs.supported()).isFalse();
        assertThat(costs.messages()).containsExactly("unsupported_requirement:JsonObject", "unsupported_requirement:Object");
    }
}
