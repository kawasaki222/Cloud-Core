package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderBackendTest {

    @Test
    void nanoVgSkeletonIsUnavailableByDefault() {
        NanoVgBackend backend = new NanoVgBackend();
        assertFalse(backend.available());
        assertThrows(IllegalStateException.class, () -> backend.beginFrame(null));
        assertTrue(new NanoVgBackend(true).available());
    }

    @Test
    void fboCompositorRejectsUseBeforeInitialization() {
        FboCompositor compositor = new FboCompositor();
        assertFalse(compositor.available());
        assertThrows(IllegalStateException.class, () -> compositor.composite(null, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> compositor.resize(0, 1, 1));
        compositor.close();
        assertFalse(compositor.available());
    }
}
