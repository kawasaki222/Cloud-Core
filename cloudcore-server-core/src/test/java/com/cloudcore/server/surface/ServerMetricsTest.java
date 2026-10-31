package com.cloudcore.server.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerMetricsTest {

    private final ServerMetrics metrics = new ServerMetrics();

    @Test
    void startsAtZeroWithAStableOrder() {
        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(java.util.List.of("s2c_frames", "s2c_fragments", "s2c_bytes"),
                java.util.List.copyOf(snapshot.keySet()).subList(0, 3));
        assertTrue(snapshot.values().stream().allMatch(value -> value == 0L));
    }

    @Test
    void countsFramesFragmentsAndBytes() {
        metrics.s2cFrame(100, 3);
        metrics.s2cFrame(50, 1);
        metrics.c2sFrame(20);
        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("s2c_frames"));
        assertEquals(4L, snapshot.get("s2c_fragments"));
        assertEquals(150L, snapshot.get("s2c_bytes"));
        assertEquals(1L, snapshot.get("c2s_frames"));
        assertEquals(20L, snapshot.get("c2s_bytes"));
    }

    @Test
    void negativeSizesCannotUnderflowTheTotal() {
        metrics.s2cFrame(-5, 0);
        assertEquals(0L, metrics.snapshot().get("s2c_bytes"));
        assertEquals(1L, metrics.snapshot().get("s2c_fragments"));
    }

    @Test
    void countsRejectionsSeparately() {
        metrics.rateLimited();
        metrics.rateLimited();
        metrics.unknownElement();
        metrics.rejected();
        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("rate_limited"));
        assertEquals(1L, snapshot.get("unknown_element"));
        assertEquals(1L, snapshot.get("rejected"));
    }

    @Test
    void splitsIncrementalAndFullRefreshes() {
        metrics.refresh(true);
        metrics.refresh(true);
        metrics.refresh(false);
        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("refresh_incremental"));
        assertEquals(1L, snapshot.get("refresh_full"));
    }

    @Test
    void resetClearsEveryCounter() {
        metrics.s2cFrame(10, 2);
        metrics.rateLimited();
        metrics.reset();
        assertTrue(metrics.snapshot().values().stream().allMatch(value -> value == 0L));
    }
}
