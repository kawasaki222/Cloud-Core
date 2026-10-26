package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudSurfaceAdapterTest {

    @Test
    void horizontalAnchors() {
        assertEquals(0, HudSurfaceAdapter.anchorX("top_left", 427, 100));
        assertEquals(163, HudSurfaceAdapter.anchorX("top_center", 427, 100));
        assertEquals(327, HudSurfaceAdapter.anchorX("top_right", 427, 100));
        assertEquals(163, HudSurfaceAdapter.anchorX("center", 427, 100));
    }

    @Test
    void verticalAnchors() {
        assertEquals(0, HudSurfaceAdapter.anchorY("top_left", 240, 50));
        assertEquals(95, HudSurfaceAdapter.anchorY("center_left", 240, 50));
        assertEquals(190, HudSurfaceAdapter.anchorY("bottom_right", 240, 50));
        assertEquals(95, HudSurfaceAdapter.anchorY("unknown", 240, 50) + 95);
    }
}
