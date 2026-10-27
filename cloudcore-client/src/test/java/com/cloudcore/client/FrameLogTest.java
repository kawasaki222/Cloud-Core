package com.cloudcore.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrameLogTest {

    @Test
    void keepsOnlyTheMostRecentFramesInOrder() {
        FrameLog.clear();
        for (int index = 0; index < 100; index++) {
            FrameLog.record("S2C", 0x03, index, 0L);
        }

        List<String> lines = FrameLog.snapshot();
        assertEquals(64, lines.size());
        assertTrue(lines.get(0).endsWith("len=36"), lines.get(0));
        assertTrue(lines.get(63).endsWith("len=99"), lines.get(63));
    }

    @Test
    void storesHeadersOnly() {
        FrameLog.clear();
        FrameLog.record("C2S", 0x02, 12, 0L);

        String line = FrameLog.snapshot().get(0);
        assertTrue(line.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} C2S op=0x02 len=12"), line);
    }
}
