package com.cloudcore.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Bounded ring of recent frame headers for the diagnostics dump. Only direction, opcode and
 * body length are kept: the dump must never carry payloads, which can contain document text.
 */
final class FrameLog {
    private static final int CAPACITY = 64;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final Deque<String> LINES = new ArrayDeque<>(CAPACITY);

    private FrameLog() {
    }

    static synchronized void record(String direction, int opcode, int length, long nowMillis) {
        if (LINES.size() == CAPACITY) {
            LINES.removeFirst();
        }
        String time = TIME.format(Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault()));
        LINES.addLast(String.format(Locale.ROOT, "%s %s op=0x%02X len=%d",
                time, direction, opcode, length));
    }

    /** @return oldest first, at most {@link #CAPACITY} lines */
    static synchronized List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    static synchronized void clear() {
        LINES.clear();
    }
}
