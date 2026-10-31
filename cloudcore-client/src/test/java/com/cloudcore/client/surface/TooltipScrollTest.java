package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The wheel maths of {@link TooltipSurfaceAdapter} without a rendered frame: step size,
 * clamping and the smoothing ease. Drawing itself stays manual.
 *
 * <p>Minecraft reports a positive {@code yOffset} when the wheel rolls up, which scrolls
 * content back towards its start, so the offset shrinks.
 */
class TooltipScrollTest {

    @Test
    void rollingDownMovesOneStepFurther() {
        assertEquals(12.0f, TooltipSurfaceAdapter.nextTarget(0.0f, -1.0, 12, 500));
        assertEquals(24.0f, TooltipSurfaceAdapter.nextTarget(12.0f, -1.0, 12, 500));
    }

    @Test
    void theStepIsConfigurable() {
        assertEquals(24.0f, TooltipSurfaceAdapter.nextTarget(0.0f, -1.0, 24, 500));
    }

    @Test
    void rollingUpStopsAtZero() {
        assertEquals(0.0f, TooltipSurfaceAdapter.nextTarget(5.0f, 1.0, 12, 500));
        assertEquals(0.0f, TooltipSurfaceAdapter.nextTarget(5.0f, 2.0, 12, 500));
    }

    @Test
    void rollingDownStopsAtTheContentEnd() {
        assertEquals(500.0f, TooltipSurfaceAdapter.nextTarget(495.0f, -3.0, 12, 500));
    }

    @Test
    void smoothingClosesInOnTheTarget() {
        float first = TooltipSurfaceAdapter.smoothStep(0.0f, 100.0f, 16L);
        float second = TooltipSurfaceAdapter.smoothStep(first, 100.0f, 16L);
        assertTrue(first > 0.0f && first < 100.0f, "first step moves part of the way");
        assertTrue(second > first, "each frame gets closer");
    }

    @Test
    void smoothingSnapsWhenCloseEnough() {
        assertEquals(100.0f, TooltipSurfaceAdapter.smoothStep(99.8f, 100.0f, 16L));
    }

    @Test
    void aLongFrameMovesFurtherThanAShortOne() {
        float shortFrame = TooltipSurfaceAdapter.smoothStep(0.0f, 100.0f, 8L);
        float longFrame = TooltipSurfaceAdapter.smoothStep(0.0f, 100.0f, 32L);
        assertTrue(longFrame > shortFrame, "frame-rate independence");
    }
}
