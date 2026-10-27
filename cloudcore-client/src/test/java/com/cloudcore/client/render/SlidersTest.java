package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SlidersTest {

    @Test
    void defaultRangeMapsValueToItself() {
        assertEquals(0.0, Sliders.fraction(0.0, 0.0, 1.0), 1e-9);
        assertEquals(0.5, Sliders.fraction(0.5, 0.0, 1.0), 1e-9);
        assertEquals(1.0, Sliders.fraction(1.0, 0.0, 1.0), 1e-9);
    }

    @Test
    void customRangeMapsIntoTheUnitInterval() {
        // A 0..100 slider at 25 draws a quarter filled.
        assertEquals(0.25, Sliders.fraction(25.0, 0.0, 100.0), 1e-9);
        // A -10..10 slider with value 0 is centred.
        assertEquals(0.5, Sliders.fraction(0.0, -10.0, 10.0), 1e-9);
    }

    @Test
    void fractionClampsOutsideTheRange() {
        assertEquals(0.0, Sliders.fraction(-5.0, 0.0, 10.0), 1e-9);
        assertEquals(1.0, Sliders.fraction(99.0, 0.0, 10.0), 1e-9);
    }

    @Test
    void valueSnapsToTheStepGrid() {
        // Step 5 over 0..100: a 0.23 fraction lands on 25, not 23.
        assertEquals(25.0, Sliders.value(0.23, 0.0, 100.0, 5.0), 1e-9);
        // Step still respects the range ends.
        assertEquals(0.0, Sliders.value(0.0, 0.0, 100.0, 5.0), 1e-9);
        assertEquals(100.0, Sliders.value(1.0, 0.0, 100.0, 5.0), 1e-9);
    }

    @Test
    void stepFromAnOffsetRangeSnapsRelativeToMin() {
        // 10..20 step 2: 0.55 of the way is 15.5, which snaps to 16.
        assertEquals(16.0, Sliders.value(0.55, 10.0, 20.0, 2.0), 1e-9);
    }

    @Test
    void zeroStepKeepsTheValueContinuous() {
        assertEquals(23.0, Sliders.value(0.23, 0.0, 100.0, 0.0), 1e-9);
    }

    @Test
    void invalidRangeFallsBackToASingleUnit() {
        // max == min and max < min are both degenerate; the range becomes one unit wide.
        assertEquals(6.0, Sliders.maxOrDefault(5.0, 5.0), 1e-9);
        assertEquals(6.0, Sliders.maxOrDefault(5.0, 3.0), 1e-9);
    }

    @Test
    void degenerateRangeDoesNotDivideByZero() {
        assertEquals(0.0, Sliders.fraction(3.0, 2.0, 2.0), 1e-9);
        assertEquals(2.0, Sliders.value(0.5, 2.0, 2.0, 0.0), 1e-9);
    }

    @Test
    void negativeStepIsTreatedAsContinuous() {
        assertEquals(0.0, Sliders.stepOrDefault(-1.0), 1e-9);
        assertEquals(0.0, Sliders.stepOrDefault(0.0), 1e-9);
        assertEquals(0.5, Sliders.stepOrDefault(0.5), 1e-9);
    }
}
