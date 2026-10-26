package com.cloudcore.client.render;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Runs one document render list through the best available backends: NanoVG for fills and
 * images plus a native overlay for text and item icons. When NanoVG is unavailable (or fails
 * mid-frame) the native backend draws the whole list, so a frame is never half-missing.
 *
 * <p>Render thread only.
 */
public final class DocumentRenderer {
    private final NanoVgBackend nanoVg = new NanoVgBackend();
    private final NativeBackend nativeBackend = new NativeBackend();

    public void render(GuiGraphics graphics, RenderList list, FrameContext context) {
        if (list.isEmpty()) {
            return;
        }
        RenderBackend vector = RenderBackendSelector.select(nanoVg, nativeBackend);
        vector.beginFrame(context);
        try {
            vector.draw(list, Layer.BACKGROUND);
        } finally {
            vector.endFrame();
        }
        if (vector.id() == BackendId.NANOVG && nanoVg.available()) {
            RenderList overlay = list.nativeOverlay();
            if (!overlay.isEmpty()) {
                nativeBackend.beginFrame(context);
                try {
                    nativeBackend.draw(overlay, Layer.BACKGROUND);
                } finally {
                    nativeBackend.endFrame();
                }
            }
        }
    }

    public boolean nanoVgActive() {
        return nanoVg.available();
    }

    public void close() {
        nanoVg.close();
    }
}
