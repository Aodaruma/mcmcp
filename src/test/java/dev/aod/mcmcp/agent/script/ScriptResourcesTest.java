package dev.aod.mcmcp.agent.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static dev.aod.mcmcp.agent.script.ActionScript.*;

class ScriptResourcesTest {
    @TempDir Path root;

    @Test void registeredPathsAreRelativeRegularUtf8AndShareTheByteBudget() throws Exception {
        Files.writeString(root.resolve("data.json"), "{\"x\":1}");
        long[] bytes = {8};
        assertThat(ScriptResources.read(root, "data.json", bytes)).isEqualTo("{\"x\":1}");
        assertThat(bytes[0]).isEqualTo(1);
        assertThatThrownBy(() -> ScriptResources.read(root, "data.json", bytes)).isInstanceOf(IllegalArgumentException.class);
        for (String path : List.of("../data.json", "a/../data.json", "/data.json", "C:/data.json", "a\\data.json", "a//data.json", "data.json.", "data.json:secret"))
            assertThatThrownBy(() -> ScriptResources.read(root, path, new long[]{1024})).isInstanceOf(IllegalArgumentException.class);
        Files.write(root.resolve("bad.json"), new byte[]{(byte) 0xff});
        assertThatThrownBy(() -> ScriptResources.read(root, "bad.json", new long[]{1024})).isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsDuplicateKeysDeepDataNonfiniteNumbersAndMalformedCsv() {
        for (String input : List.of("{\"x\":1,\"x\":2}", "{x:1}", "[1] [2]", "[1e999]", "[".repeat(66) + "0" + "]".repeat(66)))
            assertThatThrownBy(() -> ScriptResources.parse("json", input, new int[]{0})).isInstanceOf(IllegalArgumentException.class);
        for (String input : List.of("x,x\n1,2", "x,y\n1", "x\n\"unclosed", "x\n\"a\"b"))
            assertThatThrownBy(() -> ScriptResources.parse("csv", input, new int[]{0})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScriptResources.parse("json", "[]", new int[]{ScriptResources.MAX_NODES})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void csvQuotedDataRemainsDataAndCanBeConvertedExplicitly() {
        var resource = ScriptResources.parse("csv", "x,note\r\n3,\"place();\nnot code\"\r\n", new int[]{0});
        var sent = new ArrayList<Command>();
        var result = ActionScript.run("let d=read_csv(name=\"rows\"); place(x=number(value=d[0].x),note=d[0].note);",
                Set.of("place"), (c, cancel) -> { sent.add(c); return Outcome.SUCCESS; }, () -> false, DEFAULT_BUDGET, Map.of("rows", resource));
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().arguments()).containsEntry("x", 3.0).containsEntry("note", "place();\nnot code");
    }

    @Test void interruptedCsvStopsInsideAQuotedField() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ScriptResources.parse("csv", "x\n\"" + "a".repeat(100000) + "\"", new int[]{0}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelled");
        } finally { Thread.interrupted(); }
    }

    @Test void a147456CellFileIsSeparateFromSourceAndCallBudgetsRemainEnforced() {
        String json = "[" + "0,".repeat(147455) + "1]";
        var resource = ScriptResources.parse("json", json, new int[]{0});
        String source = "let d=read_json(name=\"art\"); for(let i=0;i<length(value=d);i++){place(x=i,color=d[i]);}";
        var sent = new AtomicInteger();
        Sink sink = (command, cancelled) -> { sent.incrementAndGet(); return Outcome.SUCCESS; };
        var result = ActionScript.run(source, Set.of("place"), sink, () -> false,
                new Budget(10_000_000, 200_000, 150_000), Map.of("art", resource));
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(sent.get()).isEqualTo(147456);
        sent.set(0);
        result = ActionScript.run(source, Set.of("place"), sink, () -> false, new Budget(10000, 100, 10), Map.of("art", resource));
        assertThat(result.status()).isEqualTo(Status.LIMIT);
        assertThat(sent.get()).isEqualTo(10);
    }

    @Test void scriptCannotTurnDataIntoCodeOrPathsOrUseHostMethods() {
        var data = Map.of("data", ScriptResources.parse("json", "{\"code\":\"move();\"}", new int[]{0}));
        for (String source : List.of("let d=read_json(name=\"../secret\");", "let d=read_json(name=\"data\"); d.code();",
                "let d=read_json(name=\"data\"); eval(source=d.code);", "let d=read_json(name=\"data\"); d.code=1;")) {
            var result = ActionScript.run(source, Set.of("move"), (c, cancelled) -> { throw new AssertionError("unexpected action"); },
                    () -> false, DEFAULT_BUDGET, data);
            assertThat(result.status()).isEqualTo(Status.INVALID);
        }
    }
}
