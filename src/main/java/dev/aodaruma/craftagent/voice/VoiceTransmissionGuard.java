package dev.aodaruma.craftagent.voice;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe second barrier used by the Voice Chat microphone callback. */
public final class VoiceTransmissionGuard {
    public static final VoiceTransmissionGuard GLOBAL = new VoiceTransmissionGuard();

    private final AtomicBoolean blocked = new AtomicBoolean();
    private final LongAdder suppressedFrames = new LongAdder();

    public void block() {
        blocked.set(true);
    }

    public void unblock() {
        blocked.set(false);
    }

    public boolean blocksTransmission() {
        return blocked.get();
    }

    public void recordSuppressedFrame() {
        suppressedFrames.increment();
    }

    public long suppressedFrames() {
        return suppressedFrames.sum();
    }
}
