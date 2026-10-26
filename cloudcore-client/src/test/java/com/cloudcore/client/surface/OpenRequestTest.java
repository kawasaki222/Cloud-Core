package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import org.junit.jupiter.api.Test;

class OpenRequestTest {

    @Test
    void unknownModesAreReported() {
        assertFalse(request(BindMode.NONE, RenderMode.SCREEN).hasUnknownMode());
        assertFalse(request(BindMode.PERSISTENT, RenderMode.SURFACE_NATIVE).hasUnknownMode());
        assertTrue(request(BindMode.UNKNOWN, RenderMode.SCREEN).hasUnknownMode());
        assertTrue(request(BindMode.NONE, RenderMode.UNKNOWN).hasUnknownMode());
    }

    private static OpenRequest request(BindMode bindMode, RenderMode renderMode) {
        return new OpenRequest(SurfaceType.CUSTOM_SCREEN, "ui", 1, 0, bindMode, renderMode,
                0, "");
    }
}
