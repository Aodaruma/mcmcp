package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** A native send invocation is not proof of server delivery or another player's receipt. */
final class V2ChatSendDriver implements V2OperationJobExecution.Driver {
    private final String text;
    private final Consumer<String> send;
    private final RateLimit rateLimit;
    private String dispatch = "not_attempted";

    V2ChatSendDriver(String text, Consumer<String> send, RateLimit rateLimit) {
        V2ChatArguments.validateText(text);
        this.text = text; this.send = send; this.rateLimit = rateLimit;
    }

    @Override public void begin(long tick, BooleanSupplier allowed) { }

    @Override public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (!dispatch.equals("not_attempted")) return V2OperationJobExecution.Step.FAILED;
        if (!allowed.getAsBoolean()) return V2OperationJobExecution.Step.FAILED;
        if (!rateLimit.acquire()) {
            dispatch = "rate_limited";
            return V2OperationJobExecution.Step.FAILED;
        }
        dispatch = "unknown"; // A throwing native send may have sent; never retry it.
        send.accept(text);
        dispatch = "submitted_to_client";
        return V2OperationJobExecution.Step.CONFIRMED;
    }

    @Override public Map<String, Object> result() {
        return Map.of("operation", "send", "dispatch", dispatch,
                "delivery_confirmed", false, "automatic_retry", false);
    }

    @Override public void close() { }

    static final class RateLimit {
        private final LongSupplier clock;
        private boolean used;
        private long last;
        RateLimit(LongSupplier clock) { this.clock = clock; }
        boolean acquire() {
            long now = clock.getAsLong();
            if (used && now - last < 1_000_000_000L) return false;
            used = true; last = now;
            return true;
        }
    }
}
