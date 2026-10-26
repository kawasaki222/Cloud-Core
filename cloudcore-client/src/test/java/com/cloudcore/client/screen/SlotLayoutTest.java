package com.cloudcore.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grid geometry is pure arithmetic, so it is tested without bootstrapping Minecraft.
 */
class SlotLayoutTest {

    @Test
    void slotsFillRowsThenColumns() {
        List<int[]> positions = SlotLayout.positions(new int[]{0, 9},
                new int[]{24, 20}, new int[]{18, 18}, new int[]{0, 0}, 9, 0, 0);

        assertEquals(10, positions.size());
        assertEquals(24, positions.get(0)[0]);
        assertEquals(20, positions.get(0)[1]);
        assertEquals(24 + 8 * 18, positions.get(8)[0]);
        assertEquals(20, positions.get(8)[1]);
        assertEquals(24, positions.get(9)[0], "tenth slot wraps to the next row");
        assertEquals(38, positions.get(9)[1]);
    }

    @Test
    void gapsAndOriginOffsetsAreApplied() {
        List<int[]> positions = SlotLayout.positions(new int[]{5, 7},
                new int[]{2, 4}, new int[]{20, 22}, new int[]{1, 2}, 2, 10, 6);

        assertEquals(3, positions.size());
        assertEquals(12, positions.get(0)[0]);
        assertEquals(10, positions.get(0)[1]);
        assertEquals(33, positions.get(1)[0]);
        assertEquals(10, positions.get(1)[1]);
        assertEquals(12, positions.get(2)[0]);
        assertEquals(34, positions.get(2)[1]);
    }

    @Test
    void reversedRangeIsNormalised() {
        List<int[]> positions = SlotLayout.positions(new int[]{8, 0},
                new int[]{0, 0}, new int[]{18, 18}, new int[]{0, 0}, 9, 0, 0);

        assertEquals(9, positions.size());
        assertEquals(0, positions.get(0)[0]);
        assertEquals(8 * 18, positions.get(8)[0]);
    }
}
