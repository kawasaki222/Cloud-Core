package com.cloudcore.client.render;

/**
 * Backend-independent drawing instructions. Geometry is in logical GUI coordinates.
 */
public sealed interface RenderCommand {

    record FillRect(int x, int y, int width, int height, int argb) implements RenderCommand {
    }

    record Text(String text, int x, int y, int argb, boolean shadow) implements RenderCommand {
    }

    record Image(String texture, int x, int y, int width, int height) implements RenderCommand {
    }

    /**
     * A decorative vanilla item drawn by the native bridge; {@code itemId} is the registry id
     * and {@code count} is only drawn when {@code showCount} is set.
     */
    record ItemIcon(String itemId, int count, boolean showCount, int x, int y, float scale)
            implements RenderCommand {
    }
}
