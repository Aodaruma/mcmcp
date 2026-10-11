package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class V2BuildArgumentsTest {
    @TempDir Path root;
    @Test void immutablePlanBindsSourceOriginDimensionAndExplicitWorldBeforeExecution() throws Exception {
        Files.writeString(root.resolve("test.json"),"[{\"x\":1,\"y\":64,\"z\":0,\"block\":\"minecraft:stone\"}]");
        var args=new HashMap<String,Object>(Map.of("blueprint_file","test.json","dimension","minecraft:overworld"));
        var preview=V2BuildArguments.parse(args,root,1024);
        assertThat(preview.execute()).isFalse();
        args.put("operation","execute");
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1024)).isInstanceOf(IllegalArgumentException.class);
        args.put("world_session_id",UUID.randomUUID().toString()); args.put("plan_sha256",preview.plan().sha256());
        assertThat(V2BuildArguments.parse(args,root,1024).execute()).isTrue();
        args.put("origin",Map.of("x",1,"y",0,"z",0));
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1024)).hasMessageContaining("plan changed");
        args.remove("origin"); Files.writeString(root.resolve("test.json"),"[{\"x\":2,\"y\":64,\"z\":0,\"block\":\"minecraft:stone\"}]");
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1024)).hasMessageContaining("plan changed");
    }
    @Test void preservesResourcePathAndByteBoundsAndRejectsEmptyExecution() throws Exception {
        Files.writeString(root.resolve("air.json"),"[{\"x\":1,\"y\":64,\"z\":0,\"block\":\"minecraft:air\"}]");
        var args=new HashMap<String,Object>(Map.of("blueprint_file","air.json","dimension","minecraft:overworld"));
        var preview=V2BuildArguments.parse(args,root,1024);
        assertThat(preview.plan().commands()).isZero();
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1)).isInstanceOf(IllegalArgumentException.class);
        args.put("operation","execute");args.put("world_session_id",UUID.randomUUID().toString());args.put("plan_sha256",preview.plan().sha256());
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1024)).hasMessageContaining("no work");
        args.put("blueprint_file","../air.json");
        assertThatThrownBy(()->V2BuildArguments.parse(args,root,1024)).isInstanceOf(IllegalArgumentException.class);
    }
}
