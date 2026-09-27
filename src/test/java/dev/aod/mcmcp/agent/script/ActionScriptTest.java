package dev.aod.mcmcp.agent.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.aod.mcmcp.agent.script.ActionScript.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionScriptTest {
    private static final Set<String> COMMANDS = Set.of("move", "place", "input");
    private final List<Command> sent = new ArrayList<>();
    private final Sink sink = (command, cancelled) -> { sent.add(command); return Outcome.SUCCESS; };

    private Result run(String source) { return run(source, DEFAULT_BUDGET); }
    private Result run(String source, Budget budget) {
        return ActionScript.run(source, COMMANDS, sink, () -> false, budget);
    }

    @Test
    void namedArgumentsEvaluateExpressionsWithoutAssigningVariables() {
        var result = run("let x=7; move(x=100, y=8*8, z=120); move(x=x);");
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.calls()).isEqualTo(2);
        assertThat(sent.getFirst().arguments()).containsExactly(
                Map.entry("x", 100.0), Map.entry("y", 64.0), Map.entry("z", 120.0));
        assertThat(sent.get(1).arguments()).containsEntry("x", 7.0);
        assertThatThrownBy(() -> sent.getFirst().arguments().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void variableLoopsAndBranchesPreserveOrderAndBlockScope() {
        var result = run("""
                let count=3; let x=100;
                for (let i=0; i<count; i=i+1) {
                    if (i%2==0) { move(x=x+i, y=64, z=120); }
                    else { place(item="stone", x=x+i); }
                }
                repeat(count-1) { let x=9; input(held=true); }
                move(x=x);
                """);
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.iterations()).isEqualTo(5);
        assertThat(sent).extracting(Command::name).containsExactly("move", "place", "move", "input", "input", "move");
        assertThat(sent.get(2).arguments()).containsEntry("x", 102.0);
        assertThat(sent.getLast().arguments()).containsEntry("x", 100.0);
        assertThat(run("for(let i=0;i<2;i++){move(x=i);}").status()).isEqualTo(Status.SUCCESS);
        assertThat(run("repeat(1){let local=1;} move(x=local);").status()).isEqualTo(Status.INVALID);
    }

    @Test
    void scalarAndCollectionLiteralsAreImmutableDataOnly() {
        assertThat(run("input(steps=[{key:'jump', ticks:2}, null], label='a\\nb', flag=!false);").status())
                .isEqualTo(Status.SUCCESS);
        var values = (List<?>) sent.getFirst().arguments().get("steps");
        assertThat(values).hasSize(2);
        assertThat(values.get(1)).isNull();
        assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(((Map<?, ?>) values.getFirst())::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(sent.getFirst().arguments()).containsEntry("label", "a\nb").containsEntry("flag", true);
    }

    @Test
    void numericPrecedenceComparisonsAndShortCircuitAreStrict() {
        assertThat(run("""
                if (1+2*3==7 && -4/2<=-2 && 3>2 && 2>=2 && 1!=2 && -0==0) { move(); }
                if (false && missing>0) { place(); }
                if (true || missing>0) { input(); }
                """).status()).isEqualTo(Status.SUCCESS);
        assertThat(sent).extracting(Command::name).containsExactly("move", "input");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "move({x:1});", "move(1);", "move(x=1,x=2);", "move(x=1)", "move(x=1,);",
            "move(x=(x=2));", "if(true){move();", "if(true) move();", "else{}",
            "while(true){}", "function f(){f();} f();", "async function f(){}", "await move();",
            "Promise();", "new Java();", "Java.type('X');", "import('fs');", "require('fs');",
            "eval('move()');", "fetch(url='x');", "move.constructor();", "let x=this;",
            "move(x=`template`);", "move(x=1e);", "move(x='bad\\q');", "move(x='unterminated);",
            "move(x={a:1,a:2});", "move(x=[1,]);", "let a={}; a.x=1;", "let a=[]; move(x=a[0]);",
            "for(let i=0;i<1;j++){move();}", "move(); unknown();", "if(false){unknown();}",
            "if(false){await move();}"
    })
    void invalidOrForbiddenSyntaxNeverDispatchesEvenAnEarlierCommand(String source) {
        assertThat(run(source).status()).isEqualTo(Status.INVALID);
        assertThat(sent).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"move(x=1/0);", "move(x=0/0);", "move(x=1e309);", "move(x=1e308*2);",
            "move(x='a'+1);", "if(1){move();}", "repeat(-1){}", "repeat(1.5){}",
            "move(x=missing);", "let x=1; let x=2;", "x=1;", "move(x=[]==[]);"})
    void invalidValuesStopBeforeDispatch(String source) {
        assertThat(run(source).status()).isEqualTo(Status.INVALID);
        assertThat(sent).isEmpty();
    }

    @Test
    void sourceNodesAndBothKindsOfDepthAreBoundedBeforeDispatch() {
        assertThat(run(" ".repeat(MAX_SOURCE)).status()).isEqualTo(Status.SUCCESS);
        assertThat(run(" ".repeat(MAX_SOURCE + 1)).detail()).isEqualTo("Source limit");
        assertThat(new ActionScriptParser("move();".repeat(MAX_NODES - 1), COMMANDS, () -> false)
                .parse().children()).hasSize(MAX_NODES - 1); // root is the final allowed node
        sent.clear();
        assertThat(run("move();".repeat(MAX_NODES)).detail()).isEqualTo("AST node limit");
        assertThat(run("move(x=" + "(".repeat(1000) + "1" + ")".repeat(1000) + ");").status()).isEqualTo(Status.LIMIT);
        assertThat(run("move(x=" + "1+".repeat(1000) + "1);").detail()).isEqualTo("AST depth limit");
        assertThat(run("repeat(1){".repeat(1000) + "}".repeat(1000)).status()).isEqualTo(Status.LIMIT);
        assertThat(sent).isEmpty();
    }

    @Test
    void workAndCallsStopAtExactLimitsIncludingEmptyPrograms() {
        assertThat(run("", new Budget(1, 0, 0)).status()).isEqualTo(Status.SUCCESS);
        assertThat(run("", new Budget(0, 0, 0)).status()).isEqualTo(Status.LIMIT);
        var exact = run("move(x=1);", new Budget(3, 0, 1));
        assertThat(exact.status()).isEqualTo(Status.SUCCESS);
        assertThat(exact.work()).isEqualTo(3); // root block, call, literal
        sent.clear();
        assertThat(run("move(x=1);", new Budget(2, 0, 1)).work()).isEqualTo(2);
        assertThat(sent).isEmpty();
        var calls = run("repeat(4){move();}", new Budget(100, 4, 3));
        assertThat(calls.status()).isEqualTo(Status.LIMIT);
        assertThat(calls.calls()).isEqualTo(3);
        assertThat(sent).hasSize(3);
        assertThatThrownBy(() -> new Budget(100_001, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loopsHaveGlobalLimitsAndAreNeverExpanded() {
        var exact = run("repeat(3){}", new Budget(100, 3, 0));
        assertThat(exact.status()).isEqualTo(Status.SUCCESS);
        assertThat(exact.iterations()).isEqualTo(3);
        assertThat(run("repeat(1e100){move();}").status()).isEqualTo(Status.LIMIT);
        assertThat(sent).isEmpty();
        var nested = run("repeat(3){repeat(3){move();}}", new Budget(1000, 6, 100));
        assertThat(nested.status()).isEqualTo(Status.LIMIT);
        assertThat(nested.iterations()).isEqualTo(5); // second inner loop cannot reserve three
        assertThat(sent).hasSize(3);
        var endless = run("for(let i=0;true;i=i){}", new Budget(100, 4, 0));
        assertThat(endless.status()).isEqualTo(Status.LIMIT);
        assertThat(endless.iterations()).isEqualTo(4);
        var work = run("for(let i=0;true;i=i){}", new Budget(10, 100, 0));
        assertThat(work.work()).isEqualTo(10);
        assertThat(work.detail()).isEqualTo("Work limit");
    }

    @Test
    void sharedCollectionGraphsDoNotTriggerRecursiveExpansion() {
        var result = run("let a=[]; repeat(100){a=[a,a];} move(value=a==a);");
        assertThat(result.status()).isEqualTo(Status.INVALID);
        assertThat(sent).isEmpty();
    }

    @Test
    void sinkFailureExceptionAndCancellationStopWithoutRetry() {
        for (Outcome outcome : List.of(Outcome.FAILURE, Outcome.CANCELLED)) {
            sent.clear();
            var result = ActionScript.run("move();place();input();", COMMANDS, (command, cancelled) -> {
                sent.add(command);
                return command.name().equals("place") ? outcome : Outcome.SUCCESS;
            }, () -> false, DEFAULT_BUDGET);
            assertThat(result.status()).isEqualTo(outcome == Outcome.FAILURE ? Status.FAILED : Status.CANCELLED);
            assertThat(result.calls()).isEqualTo(2);
            assertThat(result.completed()).isEqualTo(1);
            assertThat(sent).extracting(Command::name).containsExactly("move", "place");
        }
        var throwing = ActionScript.run("move();place();", COMMANDS, (command, cancelled) -> {
            throw new IllegalStateException("private sink detail");
        }, () -> false, DEFAULT_BUDGET);
        assertThat(throwing.status()).isEqualTo(Status.FAILED);
        assertThat(throwing.calls()).isEqualTo(1);
        assertThat(throwing.detail()).doesNotContain("private sink detail");
    }

    @Test
    void cancellationIsCheckedDuringParsingPureWorkAndAfterSinkCompletion() {
        assertThat(ActionScript.run("move();", COMMANDS, sink, () -> true, DEFAULT_BUDGET).status())
                .isEqualTo(Status.CANCELLED);
        var checks = new AtomicInteger();
        assertThat(ActionScript.run("move();".repeat(100), COMMANDS, sink,
                () -> checks.incrementAndGet() > 10, DEFAULT_BUDGET).status()).isEqualTo(Status.CANCELLED);
        checks.set(0);
        var pure = ActionScript.run("for(let i=0;true;i=i){}", COMMANDS, sink,
                () -> checks.incrementAndGet() > 100, DEFAULT_BUDGET);
        assertThat(pure.status()).isEqualTo(Status.CANCELLED);
        assertThat(pure.iterations()).isPositive();
        assertThat(sent).isEmpty();
        var cancelled = new AtomicBoolean();
        var result = ActionScript.run("move();place();", COMMANDS, (command, signal) -> {
            sent.add(command);
            cancelled.set(true);
            assertThat(signal.getAsBoolean()).isTrue();
            return Outcome.SUCCESS;
        }, cancelled::get, DEFAULT_BUDGET);
        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(sent).hasSize(1);
    }

    @Test
    void threadInterruptionIsPreservedAndStopsDispatch() {
        Thread.currentThread().interrupt();
        try {
            assertThat(run("move();").status()).isEqualTo(Status.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(sent).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }
}
