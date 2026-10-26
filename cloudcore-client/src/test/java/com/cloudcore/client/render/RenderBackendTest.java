package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RenderBackendTest {

    @Test
    void nanoVgBackendStaysOffUnlessEnabled() {
        NanoVgBackend backend = new NanoVgBackend();
        assertFalse(backend.available(), "the vector backend is opt-in until verified");
        assertEquals(BackendId.NANOVG, backend.id());
    }

    @Test
    void fboCompositeBeforeInitIsANoOp() {
        FboCompositor compositor = new FboCompositor();
        assertFalse(compositor.available());
        compositor.composite(null, 0, 0, 16, 16);
        assertFalse(compositor.ensure(0, 16));
        assertFalse(compositor.ensure(16, 0));
        compositor.close();
        assertFalse(compositor.available());
    }

    @Test
    void nativeOverlayKeepsTextItemIconsAndBuiltinImages() {
        RenderList list = new RenderList(List.of(
                new RenderCommand.FillRect(0, 0, 10, 10, 0xFFFFFFFF),
                new RenderCommand.Text("hi", 0, 0, 0xFFFFFFFF, true),
                new RenderCommand.Image("builtin:error", 0, 0, 4, 4),
                new RenderCommand.Image("local:test/red.png", 0, 0, 4, 4),
                new RenderCommand.ItemIcon("minecraft:diamond", 2, true, 0, 0, 1.0f)));

        RenderList overlay = list.nativeOverlay();
        assertEquals(3, overlay.commands().size());
        assertTrue(overlay.commands().get(0) instanceof RenderCommand.Text);
        assertTrue(overlay.commands().get(1) instanceof RenderCommand.Image);
        assertTrue(overlay.commands().get(2) instanceof RenderCommand.ItemIcon);
        assertTrue(RenderList.EMPTY.nativeOverlay().isEmpty());
    }
}
