package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class InsetsTest {

    @Test
    void aSingleValueAppliesToEverySide() {
        assertArrayEquals(new int[]{3, 3, 3, 3}, Insets.parse(3));
        assertArrayEquals(new int[]{4, 4, 4, 4}, Insets.parse("4"));
    }

    @Test
    void listValuesAreReadLeftTopRightBottom() {
        // The same order nine_slice and fill_inset use, so one convention in the whole DSL.
        assertArrayEquals(new int[]{1, 2, 3, 4}, Insets.parse(List.of(1, 2, 3, 4)));
        // A short list leaves the remaining sides at zero instead of copying a neighbour.
        assertArrayEquals(new int[]{2, 6, 0, 0}, Insets.parse(List.of(2, 6)));
        assertArrayEquals(new int[]{5, 1, 7, 0}, Insets.parse(List.of(5, 1, 7)));
    }

    @Test
    void absentAndUnreadableValuesInsetNothing() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, Insets.parse(null));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Insets.parse("wide"));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Insets.parse(List.of()));
    }

    @Test
    void insetsAreClampedToTheLimitAndNeverNegative() {
        assertArrayEquals(new int[]{8, 8, 8, 8}, Insets.parse(500, 8));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Insets.parse(-40));
        // The limit applies per side, so a field keeps some room to type in.
        assertArrayEquals(new int[]{5, 5, 5, 5}, Insets.parse(List.of(99, 99, 99, 99), 5));
    }

    @Test
    void extraValuesBeyondFourAreIgnored() {
        assertArrayEquals(new int[]{1, 2, 3, 4}, Insets.parse(List.of(1, 2, 3, 4, 9, 9)));
    }
}
