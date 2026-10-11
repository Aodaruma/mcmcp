package dev.aod.mcmcp.agent.script;

import com.google.gson.Gson;
import dev.aod.mcmcp.agent.action.AgentJobStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

class BlueprintPlanTest {
    static BlueprintPlan plan(String source, int start, int count) {
        return BlueprintPlan.compile(ScriptResources.parse("json", source, new int[]{0}), "minecraft:overworld",
                List.of(0, 0, 0), start, count, 3200, 64, true);
    }
    static Map<String, Object> cell(int x, String block) { return Map.of("x", x, "y", 64, "z", 0, "block", block); }
    static String source(Object blocks) { return new Gson().toJson(Map.of("blocks", blocks)); }

    @Test void fullMapArtSplitsWithoutMissingOrDuplicatingCoordinates() {
        String source = new Gson().toJson(Map.of("size", List.of(128,9,128), "palette", List.of("minecraft:stone"),
                "blocks", Collections.nCopies(147456, 0), "origin", List.of(-64,80,-64)));
        var seen = new HashSet<List<Integer>>();
        String hash = null;
        for (int start = 0; start < 147456; start += BlueprintPlan.MAX_BATCH) {
            var p = plan(source, start, BlueprintPlan.MAX_BATCH);
            if (hash == null) hash = p.sha256(); else assertThat(p.sha256()).isEqualTo(hash);
            assertThat(p.preview()).containsEntry("total_entries",147456).containsEntry("place_entries",147456);
            var data = (Map<?,?>) p.resources().get("blueprint").value();
            for (Object value : (List<?>) data.get("steps")) {
                var step = (Map<?,?>) value;
                int x = ((Number) step.get("x")).intValue(), y = ((Number) step.get("y")).intValue(), z = ((Number) step.get("z")).intValue();
                int count = ((Number) step.get("count")).intValue(), dx = ((Number) step.get("dx")).intValue();
                assertThat(count).isBetween(1,8);
                for (int i = 0; i < count; i++) assertThat(seen.add(List.of(x + Integer.signum(dx)*i,y,z))).isTrue();
            }
            assertThat(p.progress(p.commands())).containsEntry("next_index", Math.min(147456,start+BlueprintPlan.MAX_BATCH));
        }
        assertThat(seen).hasSize(147456).contains(List.of(-64,80,-64), List.of(63,88,63));
    }

    @Test void preservesExplicitOperationsGapsAndPropertiesAndNeverBreaksForAir() {
        var rows = List.of(cell(2,"minecraft:stone"), cell(1,"minecraft:stone"), cell(0,"minecraft:air"),
                Map.of("operation","break","x",5,"y",64,"z",0,"block","minecraft:dirt"),
                Map.of("x",5,"y",64,"z",0,"block","minecraft:oak_stairs","properties",Map.of("facing","east")));
        var p=plan(source(rows),0,100);
        assertThat(p.commands()).isEqualTo(3);
        assertThat(p.preview()).containsEntry("total_entries",4).containsEntry("break_entries",1);
        var steps=(List<?>)((Map<?,?>)p.resources().get("blueprint").value()).get("steps");
        assertThat(((Map<?,?>)steps.getFirst()).get("dx")).isEqualTo(-1.0);
        assertThat(p.progress(1)).containsEntry("next_index",2);
        assertThat(p.progress(2)).containsEntry("next_index",3);
        assertThat(p.confirmedStep(0,"place",Map.of("kind","place_block","result",Map.of("confirmed_count",1,"observed_count",1)))).isTrue();
        assertThat(p.confirmedStep(0,"place",Map.of("kind","place_block","result",Map.of("confirmed_count",1,"observed_count",0)))).isFalse();
        assertThat(p.confirmedStep(1,"breakBlocks",Map.of("kind","break_block","result",Map.of("confirmed_count",0,"observed_count",1)))).isTrue();
    }

    @Test void csvIsDataAndInvalidOperationsOrDimensionsAreRejected() {
        var input=ScriptResources.parse("csv","x,y,z,block,operation\n1,64,0,minecraft:stone,place\n2,64,0,minecraft:dirt,break\n",new int[]{0});
        var p=BlueprintPlan.compile(input,"minecraft:overworld",List.of(10,0,0),0,10,3200,64,true);
        assertThat(p.preview()).containsEntry("total_entries",2);
        assertThatThrownBy(()->plan(source(List.of(Map.of("x",1,"y",64,"z",0,"block","minecraft:stone","operation","eval"))),0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan("{\"blocks\":[0],\"palette\":[\"minecraft:stone\"],\"size\":[30000000,30000000,30000000]}",0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan("{\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"}],\"dimension\":\"minecraft:the_end\"}",0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan(source(List.of(Map.of("x",1.5,"y",64,"z",0,"block","minecraft:stone"))),0,10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scriptRetainsPartialChangesButDoesNotAdvancePastAnIncompleteSection() {
        for (boolean stoppedChild : List.of(false,true)) {
            var p=plan(source(List.of(cell(1,"minecraft:stone"),cell(2,"minecraft:stone"),cell(3,"minecraft:dirt"))),0,100);
            var jobs=new AgentJobStore(); var world=UUID.randomUUID();
            var id=jobs.reserve(AgentJobStore.Kind.SCRIPT,world,p.commands(),Long.MAX_VALUE);
            var runner=new ScriptJobExecution.CommandRunner() {
                int calls;
                public ScriptJobExecution.Outcome execute(String name,Map<String,Object> args,BooleanSupplier cancel) {
                    assertThat(++calls).isEqualTo(1);
                    return stoppedChild ? ScriptJobExecution.Outcome.FAILURE : ScriptJobExecution.Outcome.SUCCESS;
                }
                public Map<String,Object> result() { return Map.of("kind","place_block","state", stoppedChild?"failed":"succeeded",
                        "result",Map.of("confirmed_count",1,"observed_count",0)); }
                public void cancelActive() { }
            };
            var execution=new ScriptJobExecution(jobs,id,world,BlueprintPlan.SOURCE,Set.of("place","breakBlocks"),10000,100, p.commands(),runner,()->true,Long.MAX_VALUE)
                    .withResources(p.resources()).withBlueprint(p);
            jobs.confirm(id,1); execution.run();
            assertThat(execution.finishIfReleased(false).state()).isEqualTo(AgentJobStore.State.RUNNING);
            var result=execution.finishIfReleased(true);
            assertThat(result.state()).isEqualTo(AgentJobStore.State.FAILED);
            assertThat(result.result()).containsEntry("placed_blocks",1L).containsEntry("completed_commands",0);
            assertThat(((Map<?,?>)result.result().get("blueprint")).get("next_index")).isEqualTo(0);
        }
    }

    @Test void distributedSourceExecutesMixedBatchAndReturnsExactCursor() {
        var p=plan(source(List.of(cell(1,"minecraft:stone"),cell(2,"minecraft:stone"),
                Map.of("x",3,"y",64,"z",0,"block","minecraft:dirt","operation","break"))),0,100);
        var jobs=new AgentJobStore(); var world=UUID.randomUUID(); var id=jobs.reserve(AgentJobStore.Kind.SCRIPT,world,p.commands(),Long.MAX_VALUE);
        var runner=new ScriptJobExecution.CommandRunner() {
            Map<String,Object> result=Map.of();
            public ScriptJobExecution.Outcome execute(String name,Map<String,Object> args,BooleanSupplier cancel) {
                result=Map.of("kind",name.equals("place")?"place_block":"break_block","state","succeeded",
                        "result",Map.of("confirmed_count",((Number)args.get("max_blocks")).intValue(),"observed_count",0));
                return ScriptJobExecution.Outcome.SUCCESS;
            }
            public Map<String,Object> result() { return result; }
            public void cancelActive() { }
        };
        var execution=new ScriptJobExecution(jobs,id,world,BlueprintPlan.SOURCE,Set.of("place","breakBlocks"),10000,100,p.commands(),runner,()->true,Long.MAX_VALUE)
                .withResources(p.resources()).withBlueprint(p);
        jobs.confirm(id,1); execution.run();
        var result=execution.finishIfReleased(true);
        assertThat(result.state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(result.result()).containsEntry("placed_blocks",2L).containsEntry("broken_blocks",1L);
        assertThat(((Map<?,?>)result.result().get("blueprint")).get("next_index")).isEqualTo(3);
        assertThat(((Map<?,?>)result.result().get("blueprint")).get("blueprint_complete")).isEqualTo(true);
    }
}
