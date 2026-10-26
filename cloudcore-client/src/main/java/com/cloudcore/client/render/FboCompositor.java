package com.cloudcore.client.render;

import net.minecraft.client.gui.GuiGraphics;

/**
 * FBO composition skeleton. The real implementation must wrap the colour texture in an
 * AbstractTexture, composite through {@code GuiGraphics#blit}, and restore GL state.
 * It reports unavailable until that path is verified on a real client.
 */
public final class FboCompositor implements AutoCloseable {
    private boolean available;

    public boolean available() {
        return available;
    }

    public void resize(int width, int height, int guiScale) {
        if (width <= 0 || height <= 0 || guiScale <= 0) {
            throw new IllegalArgumentException("Invalid FBO dimensions");
        }
    }

    public void composite(GuiGraphics graphics, int x, int y, int width, int height) {
        if (!available) {
            throw new IllegalStateException("FBO compositor is not initialized");
        }
    }

    @Override
    public void close() {
        available = false;
    }
}
