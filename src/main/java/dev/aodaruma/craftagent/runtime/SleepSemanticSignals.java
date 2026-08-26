package dev.aodaruma.craftagent.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Session-fenced semantic evidence from vanilla bed system messages. */
public final class SleepSemanticSignals {
    private static final SleepSemanticSignals GLOBAL = new SleepSemanticSignals();

    private final Object gate = new Object();
    private final Map<ClientLevel, SessionLedger> ledgers = new WeakHashMap<>();

    public static SleepSemanticSignals global() {
        return GLOBAL;
    }

    /** Binds this exact level to a world session and returns an action baseline. */
    public Baseline bindSession(ClientLevel level, UUID worldSessionId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        synchronized (gate) {
            return ledgers.computeIfAbsent(level, ignored -> new SessionLedger())
                    .bind(worldSessionId);
        }
    }

    /** Matches only one allowlisted key recorded after the supplied action baseline. */
    public Optional<Signal> latestAfter(ClientLevel level, Baseline baseline, Key key) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(key, "key");
        synchronized (gate) {
            var ledger = ledgers.get(level);
            return ledger == null ? Optional.empty() : ledger.latestAfter(baseline, key);
        }
    }

    /** Called from the post-handler inbound system-chat hook on the client thread. */
    public void onSystemChat(ClientLevel level, Component component) {
        var key = allowlistedKey(component);
        if (level == null || key.isEmpty()) {
            return;
        }
        synchronized (gate) {
            var ledger = ledgers.get(level);
            // Messages received before an action binds the current world session are not evidence.
            if (ledger != null && ledger.bound()) {
                ledger.record(key.orElseThrow());
            }
        }
    }

    /** Explicit lifecycle fence for disconnect or level replacement. */
    public void clearSession(ClientLevel level) {
        if (level == null) {
            return;
        }
        synchronized (gate) {
            ledgers.remove(level);
        }
    }

    static Optional<Key> allowlistedKey(Component component) {
        if (component == null
                || !(component.getContents() instanceof TranslatableContents translated)) {
            return Optional.empty();
        }
        return Key.fromTranslationKey(translated.getKey());
    }

    public enum Key {
        SET_SPAWN("block.minecraft.set_spawn", false),
        BED_OCCUPIED("block.minecraft.bed.occupied", true),
        BED_TOO_FAR_AWAY("block.minecraft.bed.too_far_away", true),
        BED_OBSTRUCTED("block.minecraft.bed.obstructed", true),
        BED_NOT_SAFE("block.minecraft.bed.not_safe", true),
        BED_NO_SLEEP("block.minecraft.bed.no_sleep", true);

        private final String translationKey;
        private final boolean failure;

        Key(String translationKey, boolean failure) {
            this.translationKey = translationKey;
            this.failure = failure;
        }

        public String translationKey() {
            return translationKey;
        }

        public boolean failure() {
            return failure;
        }

        private static Optional<Key> fromTranslationKey(String translationKey) {
            for (var key : values()) {
                if (key.translationKey.equals(translationKey)) {
                    return Optional.of(key);
                }
            }
            return Optional.empty();
        }
    }

    public record Baseline(UUID worldSessionId, long revision) {
        public Baseline {
            Objects.requireNonNull(worldSessionId, "worldSessionId");
            if (revision < 0) {
                throw new IllegalArgumentException("baseline revision must be non-negative");
            }
        }
    }

    public record Signal(Key key, long revision) {
        public Signal {
            Objects.requireNonNull(key, "key");
            if (revision <= 0) {
                throw new IllegalArgumentException("signal revision must be positive");
            }
        }
    }

    /** Package-private deterministic core used by focused unit tests. */
    static final class SessionLedger {
        private final EnumMap<Key, Long> latestRevisions = new EnumMap<>(Key.class);
        private UUID worldSessionId;
        private long revision;

        boolean bound() {
            return worldSessionId != null;
        }

        Baseline bind(UUID requestedSession) {
            Objects.requireNonNull(requestedSession, "requestedSession");
            if (!requestedSession.equals(worldSessionId)) {
                worldSessionId = requestedSession;
                revision = 0;
                latestRevisions.clear();
            }
            return new Baseline(worldSessionId, revision);
        }

        void record(Key key) {
            Objects.requireNonNull(key, "key");
            latestRevisions.put(key, ++revision);
        }

        Optional<Signal> latestAfter(Baseline baseline, Key key) {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(key, "key");
            if (!baseline.worldSessionId().equals(worldSessionId)) {
                return Optional.empty();
            }
            long matchedRevision = latestRevisions.getOrDefault(key, 0L);
            return matchedRevision > baseline.revision()
                    ? Optional.of(new Signal(key, matchedRevision))
                    : Optional.empty();
        }
    }
}
