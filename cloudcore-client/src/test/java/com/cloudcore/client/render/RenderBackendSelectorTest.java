package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RenderBackendSelectorTest {

    @Test
    void fallsBackWhenNanoVgIsUnavailable() {
        NativeBackend nativeBackend = new NativeBackend();
        RenderBackend selected = RenderBackendSelector.select(new NanoVgBackend(false), nativeBackend);
        assertEquals(BackendId.NATIVE, selected.id());
    }

    @Test
    void prefersAvailableNanoVg() {
        RenderBackend selected = RenderBackendSelector.select(
                new NanoVgBackend(true), new NativeBackend());
        assertEquals(BackendId.NANOVG, selected.id());
    }
}
