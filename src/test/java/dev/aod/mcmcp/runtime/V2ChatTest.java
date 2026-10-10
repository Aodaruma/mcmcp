package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentJobStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class V2ChatTest {
    @Test void validatesSingleLineCommandsUnicodeAndReadBounds() {
        for (String text : List.of("こんにちは！", "a".repeat(256), "\ud83d\ude00".repeat(128)))
            assertThat(V2ChatArguments.parse(Map.of("operation", "send", "text", text)).text()).isEqualTo(text);
        for (String text : List.of("", " ", "/op test", "  /say test", "a\nb", "a\rb", "a\tb", "\u00a7a", "\ud800", "\udc00", "x".repeat(257), "a\u2028b"))
            assertThatThrownBy(() -> V2ChatArguments.parse(Map.of("operation", "send", "text", text))).isInstanceOf(IllegalArgumentException.class);
        assertThat(V2ChatArguments.parse(Map.of("operation", "read")).limit()).isEqualTo(50);
        for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of("operation", "read", "limit", 101),
                Map.of("operation", "read", "text", "no"), Map.of("operation", "send", "text", "hi", "cursor", "x"),
                Map.of("operation", "command", "text", "/help"), Map.of("operation", "read", "cursor", "oops")))
            assertThatThrownBy(() -> V2ChatArguments.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sendRequiresDeliveryAndCannotBeRetriedEvenWhenNativeSendThrows() {
        var sends = new AtomicInteger(); var fixture = new Fixture(text -> sends.incrementAndGet());
        assertThat(fixture.tick(true).state()).isEqualTo(AgentJobStore.State.UNCONFIRMED);
        assertThat(sends).hasValue(0);
        fixture.store.confirm(fixture.id, 0);
        var done = fixture.tick(true);
        assertThat(done.state()).isEqualTo(AgentJobStore.State.SUCCEEDED);
        assertThat(done.result()).containsEntry("dispatch", "submitted_to_client").containsEntry("delivery_confirmed", false);
        fixture.tick(true);
        assertThat(sends).hasValue(1);
        var uncertain = new Fixture(text -> { sends.incrementAndGet(); throw new IllegalStateException("private content"); });
        uncertain.store.confirm(uncertain.id, 0);
        var failed = uncertain.tick(true);
        assertThat(failed.state()).isEqualTo(AgentJobStore.State.FAILED);
        assertThat(failed.result()).containsEntry("dispatch", "unknown");
        assertThat(failed.failure()).isEqualTo("chat_runtime_failed");
        uncertain.tick(true);
        assertThat(sends).hasValue(2);
    }

    @Test void cancellationSafetyAndWorldChangePreventDispatch() {
        var sends = new AtomicInteger();
        for (String stop : List.of("cancel", "safety", "world", "delivery")) {
            var f = new Fixture(text -> sends.incrementAndGet());
            if (!stop.equals("delivery")) f.store.confirm(f.id, 0);
            switch (stop) {
                case "cancel" -> f.job.cancel();
                case "safety" -> f.tick(false);
                case "world" -> f.job.tick(UUID.randomUUID(), 1, 100, true, () -> true);
                case "delivery" -> f.job.stop("delivery_not_confirmed");
            }
            f.tick(true);
            assertThat(f.store.get(f.id).state().terminal()).isTrue();
        }
        assertThat(sends).hasValue(0);
    }

    @Test void rateLimitUsesElapsedTimeAndNeverQueuesAnAutomaticRetry() {
        var clock = new AtomicLong(); var rate = new V2ChatSendDriver.RateLimit(clock::get);
        var sends = new AtomicInteger();
        var first = new V2ChatSendDriver("one", text -> sends.incrementAndGet(), rate);
        assertThat(first.tick(1, () -> true)).isEqualTo(V2OperationJobExecution.Step.CONFIRMED);
        var second = new V2ChatSendDriver("two", text -> sends.incrementAndGet(), rate);
        assertThat(second.tick(2, () -> true)).isEqualTo(V2OperationJobExecution.Step.FAILED);
        assertThat(second.result()).containsEntry("dispatch", "rate_limited");
        clock.set(1_000_000_000L);
        second.tick(20, () -> true);
        assertThat(sends).hasValue(1);
        var third = new V2ChatSendDriver("three", text -> sends.incrementAndGet(), rate);
        assertThat(third.tick(20, () -> true)).isEqualTo(V2OperationJobExecution.Step.CONFIRMED);
        assertThat(sends).hasValue(2);
    }

    private static class Fixture {
        final UUID session = UUID.randomUUID();
        final AgentJobStore store = new AgentJobStore();
        final UUID id = store.reserve(AgentJobStore.Kind.CHAT, session, 20, Long.MAX_VALUE);
        final V2OperationJobExecution job;
        Fixture(java.util.function.Consumer<String> send) {
            job = new V2OperationJobExecution(store, id, session,
                    new V2ChatSendDriver("test", send, new V2ChatSendDriver.RateLimit(() -> 0)), () -> true);
        }
        AgentJobStore.Snapshot tick(boolean safe) { return job.tick(session, 1, 100, safe, () -> true); }
    }
}
