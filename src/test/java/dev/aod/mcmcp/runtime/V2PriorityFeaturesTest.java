package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.observation.ClientRecipeCatalog.IngredientView;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class V2PriorityFeaturesTest {
    private final V2StopCondition.Context context = new V2StopCondition.Context() {
        public Vec3 position() { return Vec3.ZERO; }
        public BlockStateFingerprint visibleBlock(int x, int y, int z) { return null; }
        public int itemCount(String item) { return 2; }
        public String screen() { return "inventory"; }
        public Map<String,Object> player() { return Map.of("air", 80, "in_water", true); }
    };

    @Test void unknownStaysUnknownUnderNegationAndBooleanCombinations() {
        var unknown = Map.<String,Object>of("type", "block", "x", 0, "y", 0, "z", 0, "block", "minecraft:air");
        var yes = Map.<String,Object>of("type", "player", "property", "air", "comparison", "at_most", "value", 100);
        assertThat(V2StopCondition.parse(Map.of("type", "not", "condition", unknown)).evaluate(context)).isNull();
        assertThat(V2StopCondition.parse(Map.of("type", "all", "conditions", List.of(yes, unknown))).evaluate(context)).isNull();
        assertThat(V2StopCondition.parse(Map.of("type", "any", "conditions", List.of(unknown, yes))).evaluate(context)).isTrue();
        assertThat(V2StopCondition.parse(Map.of("type", "not", "condition", yes)).matches(context)).isFalse();
        var composite = V2StopCondition.parse(Map.of("type", "all", "conditions", List.of(yes, Map.of("type", "screen", "screen", "inventory"))));
        assertThat(composite.matchesScreen(context)).isTrue();
    }

    @Test void conditionsAreBoundedAndTypedBeforeDispatch() {
        Map<String,Object> node = Map.of("type", "screen", "screen", "none");
        for(int i=0;i<10;i++) node=Map.of("type", "not", "condition", node);
        var deep = node;
        assertThatThrownBy(() -> V2StopCondition.parse(deep)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2StopCondition.parse(Map.of("type", "player", "property", "in_water", "value", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void craftChoiceReservesSpecificIngredientsInsteadOfGreedilyConsumingThem() {
        var requirements = List.of(new IngredientView(0, 1, List.of("minecraft:oak_planks", "minecraft:birch_planks")),
                new IngredientView(1, 1, List.of("minecraft:oak_planks")));
        assertThat(V2CraftGridPlacement.select(requirements, Map.of("minecraft:oak_planks", 1, "minecraft:birch_planks", 1)))
                .containsExactly("minecraft:birch_planks", "minecraft:oak_planks");
        assertThat(V2CraftGridPlacement.select(requirements, Map.of("minecraft:oak_planks", 1))).isNull();
        assertThat(V2CraftGridPlacement.select(requirements,
                Map.of("minecraft:oak_planks", 2, "minecraft:birch_planks", 2), 2))
                .containsExactly("minecraft:birch_planks", "minecraft:oak_planks");
    }

    @Test void sortingDefaultsToMainInventoryAndRejectsDuplicateOrEquipmentSlots() {
        assertThat(V2InventorySortArguments.parse(Map.of("operation", "sort")).slots()).containsExactlyElementsOf(java.util.stream.IntStream.range(9,36).boxed().toList());
        for(var slots:List.of(List.of(0,0),List.of(36),List.of(-1)))
            assertThatThrownBy(() -> V2InventorySortArguments.parse(Map.of("operation", "sort", "slots", slots))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void vehicleSearchFollowsCheckedEdgesAroundAnObstacleAndStaysLocal() {
        var start=new NavCell("minecraft:overworld",0,64,0);
        var goal=new NavCell(start.dimension(),2,64,0);
        var a=new NavCell(start.dimension(),0,64,1);
        var b=new NavCell(start.dimension(),1,64,1);
        var c=new NavCell(start.dimension(),2,64,1);
        var graph=Map.of(start,List.of(a),a,List.of(b),b,List.of(c),c,List.of(goal));
        assertThat(VehicleRoutePlanner.plan(start,goal,n->graph.getOrDefault(n,List.of()))).containsExactly(start,a,b,c,goal);
        assertThat(VehicleRoutePlanner.plan(start,goal,n->List.of(new NavCell(start.dimension(),100,64,0)))).isEmpty();
    }
}
