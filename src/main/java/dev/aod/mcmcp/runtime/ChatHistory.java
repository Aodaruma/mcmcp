package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.RuntimeFailures.RuntimeInvocationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Client-thread memory only. Text is untrusted display data, never an action source. */
final class ChatHistory {
    static final int CAPACITY = 256;
    static final int MAX_TEXT = 2048;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private UUID session;
    private UUID stream = UUID.randomUUID();
    private long sequence;

    void clear() {
        entries.clear();
        session = null;
        stream = UUID.randomUUID();
        sequence = 0;
    }

    void bind(UUID currentSession) {
        if (!Objects.equals(session, currentSession)) {
            clear();
            session = Objects.requireNonNull(currentSession);
        }
    }

    void append(UUID currentSession, long tick, String source, String text, Object signature) {
        bind(currentSession);
        boolean truncated = text.length() > MAX_TEXT;
        int end = Math.min(text.length(), MAX_TEXT);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        entries.addLast(new Entry(++sequence, tick, source, text.substring(0, end), truncated, signature));
        if (entries.size() > CAPACITY) entries.removeFirst();
    }

    void delete(Object signature) {
        if (signature == null) return;
        for (var entry : entries) {
            if (signature.equals(entry.signature)) {
                entry.text = "";
                entry.deleted = true;
            }
        }
    }

    Map<String, Object> read(UUID currentSession, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be in 1..100");
        bind(currentSession);
        long after = 0;
        if (cursor != null) {
            String[] parts = cursor.split(":", -1);
            if (parts.length != 2 || !parts[0].equals(stream.toString())) throw expired();
            try { after = Long.parseLong(parts[1]); }
            catch (NumberFormatException failure) { throw expired(); }
            if (after < 0 || after > sequence) throw expired();
        }
        long first = entries.isEmpty() ? sequence + 1 : entries.getFirst().sequence;
        long missed = Math.max(0, first - after - 1);
        var messages = new ArrayList<Map<String, Object>>();
        long next = Math.max(after, first - 1);
        for (var entry : entries) {
            if (entry.sequence <= after) continue;
            if (messages.size() == limit) break;
            messages.add(entry.wire());
            next = entry.sequence;
        }
        return Map.of("schema_version", 2, "operation", "read", "session_id", session.toString(),
                "messages", messages, "next_cursor", stream + ":" + next,
                "has_more", next < sequence, "missed_messages", missed,
                "untrusted", true, "capacity", CAPACITY);
    }

    private static RuntimeInvocationException expired() {
        return new RuntimeInvocationException("invalid_cursor",
                "Chat history changed or the cursor is invalid; read again without a cursor.", true, Map.of());
    }

    private static final class Entry {
        final long sequence;
        final long tick;
        final String source;
        String text;
        final boolean truncated;
        final Object signature;
        boolean deleted;

        Entry(long sequence, long tick, String source, String text, boolean truncated, Object signature) {
            this.sequence = sequence; this.tick = tick; this.source = source;
            this.text = text; this.truncated = truncated; this.signature = signature;
        }

        Map<String, Object> wire() {
            var value = new LinkedHashMap<String, Object>();
            value.put("sequence", sequence);
            value.put("received_tick", tick);
            value.put("source", source);
            value.put("text", text);
            value.put("truncated", truncated);
            value.put("deleted", deleted);
            return value;
        }
    }
}
