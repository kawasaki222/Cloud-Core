package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RenderBackendSelectorTest {

    @Test
    void fallsBackWhenNanoVgIsUnavailable() {
        NativeBackend nativeBackend = new NativeBackend();
        RenderBackend selected = RenderBackendSelector.select(new NanoVgBackend(), nativeBackend);
        assertEquals(BackendId.NATIVE, selected.id());
    }

    @Test
    void prefersTheAvailableBackend() {
        RenderBackend selected = RenderBackendSelector.select(
                new StubBackend(BackendId.NANOVG, true), new NativeBackend());
        assertEquals(BackendId.NANOVG, selected.id());
    }

    private record StubBackend(BackendId id, boolean available) implements RenderBackend {
        @Override
        public void beginFrame(FrameContext context) {
        }

        @Override
        public void draw(RenderList list, Layer layer) {
        }

        @Override
        public void endFrame() {
        }
    }
}
