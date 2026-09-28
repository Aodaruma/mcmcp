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
    void helpersComposeWithForwardCallsNamedArgumentsAndIndependentLocals() {
        var result = run("""
                let x=90;
                row(count=2, start=10);
                row(start=x, count=1);
                move(x=x);
                function row(start, count) {
                    for(let i=0;i<count;i++) { pair(x=start+i, active=i==0); }
                }
                function pair(x, active) {
                    let local=x;
                    if(active) { repeat(2) { move(x=local); local=local+1; } }
                    else { place(x=local); }
                    x=0;
                }
                """);
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.iterations()).isEqualTo(7);
        assertThat(result.calls()).isEqualTo(6);
        assertThat(sent).extracting(Command::name).containsExactly("move", "move", "place", "move", "move", "move");
        assertThat(sent).extracting(c -> c.arguments().get("x")).containsExactly(10.0, 11.0, 11.0, 90.0, 91.0, 90.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "function f(){} function f(){}",
            "function f(x,x){}",
            "function move(){}",
            "function f(move){}",
            "function f(){let move=1;}",
            "if(false){let move=1;}",
            "function f(){unknown();}",
            "function f(){if(false){f();}}",
            "function f(){g();} function g(){f();}",
            "function f(x){} if(false){f();}",
            "function f(x){} function unused(){f(y=1);}",
            "function f(x){} f(x=1,y=2);",
            "function f(x){} f(x=1,x=2);",
            "function f(x=1){}",
            "function f(){function g(){}}",
            "if(false){function f(){}}",
            "function f(){return;}",
            "function f(){return 1;}",
            "function f(){} let x=f();",
            "function f(){} f(1);"
    })
    void invalidHelperGraphsAreRejectedBeforeAnySinkCommand(String source) {
        var result = run("move();" + source);
        assertThat(result.status()).isEqualTo(Status.INVALID);
        assertThat(result.calls()).isZero();
        assertThat(result.work()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void helpersCannotCaptureCallerOrGlobalVariablesOrLeakTheirLocals() {
        for (String source : List.of(
                "let x=1; function f(){move(x=x);} f();",
                "function f(){x=2;} let x=1; f();",
                "function f(x){g();} function g(){move(x=x);} f(x=1);",
                "function f(){let x=1;} f(); move(x=x);")) {
            assertThat(run(source).status()).isEqualTo(Status.INVALID);
            assertThat(sent).isEmpty();
        }
    }

    private static String helperChain(int depth) {
        var source = new StringBuilder("f0();");
        for (int i=0; i<depth; i++) {
            source.append("function f").append(i).append("(){");
            source.append(i+1 == depth ? "move();" : "f" + (i+1) + "();");
            source.append("}");
        }
        return source.toString();
    }

    @Test
    void helperDepthAndDeclarationAstAreBoundedBeforeDispatch() {
        assertThat(run(helperChain(MAX_CALL_DEPTH)).status()).isEqualTo(Status.SUCCESS);
        assertThat(sent).hasSize(1);
        sent.clear();
        assertThat(run("move();" + helperChain(MAX_CALL_DEPTH+1)).detail()).isEqualTo("Function call depth limit");
        assertThat(run("move(); function unused(){" + "let x=0;".repeat(2100) + "}").detail())
                .isEqualTo("AST node limit");
        assertThat(run("function unused(){" + "repeat(1){".repeat(100) + "}".repeat(101)).status())
                .isEqualTo(Status.LIMIT);
        assertThat(sent).isEmpty();
    }

    @Test
    void helpersShareWorkIterationAndSinkBudgetsAcrossInvocations() {
        String source = "function f(){repeat(2){move();}} f(); f();";
        var iterations = run(source, new Budget(100, 3, 10));
        assertThat(iterations.status()).isEqualTo(Status.LIMIT);
        assertThat(iterations.iterations()).isEqualTo(2);
        assertThat(iterations.completed()).isEqualTo(2);
        sent.clear();
        var calls = run(source, new Budget(100, 10, 3));
        assertThat(calls.detail()).isEqualTo("Call limit");
        assertThat(calls.calls()).isEqualTo(3);
        assertThat(calls.completed()).isEqualTo(3);
        assertThat(sent).hasSize(3);
        var exact = run("function f(){} f(); f();", new Budget(6, 0, 0));
        assertThat(exact.status()).isEqualTo(Status.SUCCESS); // root, declaration, two calls and bodies
        var work = run("function f(){} f(); f();", new Budget(5, 0, 0));
        assertThat(work.detail()).isEqualTo("Work limit");
        assertThat(work.work()).isEqualTo(5);
        var loop = run("function f(){for(let i=0;true;i=i){}} f();", new Budget(100, 4, 0));
        assertThat(loop.detail()).isEqualTo("Iteration limit");
        assertThat(loop.iterations()).isEqualTo(4);
    }

    @Test
    void nestedHelpersPreservePartialResultsOnFailureExceptionAndCancellation() {
        String source = "function outer(){inner(); input();} function inner(){move(); place();} outer();";
        for (int mode=0; mode<4; mode++) {
            sent.clear();
            int failureMode = mode;
            var cancelled = new AtomicBoolean();
            var result = ActionScript.run(source, COMMANDS, (command, signal) -> {
                sent.add(command);
                if (!command.name().equals("place")) return Outcome.SUCCESS;
                if (failureMode == 0) return Outcome.FAILURE;
                if (failureMode == 1) throw new IllegalStateException("private detail");
                if (failureMode == 2) return Outcome.CANCELLED;
                cancelled.set(true);
                assertThat(signal.getAsBoolean()).isTrue();
                return Outcome.SUCCESS;
            }, cancelled::get, DEFAULT_BUDGET);
            assertThat(result.status()).isEqualTo(mode < 2 ? Status.FAILED : Status.CANCELLED);
            assertThat(result.calls()).isEqualTo(2);
            assertThat(result.completed()).isEqualTo(mode == 3 ? 2 : 1);
            assertThat(result.detail()).doesNotContain("private detail");
            assertThat(sent).extracting(Command::name).containsExactly("move", "place");
        }
    }

    @Test
    void cancellationStopsHelperValidationAndPureFunctionWork() {
        String source = helperChain(MAX_CALL_DEPTH);
        var parseChecks = new AtomicInteger();
        new ActionScriptParser(source, COMMANDS, () -> { parseChecks.incrementAndGet(); return false; }).parse();
        var checks = new AtomicInteger();
        var validation = ActionScript.run(source, COMMANDS, sink,
                () -> checks.incrementAndGet() >= parseChecks.get(), DEFAULT_BUDGET);
        assertThat(validation.status()).isEqualTo(Status.CANCELLED);
        assertThat(validation.work()).isZero();
        checks.set(0);
        var pure = ActionScript.run("function f(){for(let i=0;true;i=i){}} f();", COMMANDS, sink,
                () -> checks.incrementAndGet() > 200, DEFAULT_BUDGET);
        assertThat(pure.status()).isEqualTo(Status.CANCELLED);
        assertThat(pure.iterations()).isPositive();
        assertThat(sent).isEmpty();
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
        assertThatThrownBy(() -> new Budget(100_000_001, 0, 0)).isInstanceOf(IllegalArgumentException.class);
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
