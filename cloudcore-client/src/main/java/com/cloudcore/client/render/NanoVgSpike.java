package com.cloudcore.client.render;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.BufferUtils;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGLUFramebuffer;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-first initialization spike for U28/U29. It renders one red rectangle into a
 * NanoVG FBO and reads the center pixel back. This validates native loading, GL context,
 * offscreen target creation and NanoVG drawing without yet compositing to the screen.
 */
public final class NanoVgSpike {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private NanoVgSpike() {
    }

    public static void runOnce() {
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        long context = 0L;
        NVGLUFramebuffer framebuffer = null;
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            context = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
            if (context == 0L) {
                LOGGER.error("NanoVG spike: nvgCreate returned 0");
                return;
            }
            framebuffer = NanoVGGL3.nvgluCreateFramebuffer(context, 64, 64, 0);
            if (framebuffer == null) {
                LOGGER.error("NanoVG spike: nvgluCreateFramebuffer failed");
                return;
            }
            NanoVGGL3.nvgluBindFramebuffer(context, framebuffer);
            GL11.glViewport(0, 0, 64, 64);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            NanoVG.nvgBeginFrame(context, 64.0f, 64.0f, 1.0f);
            NanoVG.nvgBeginPath(context);
            NanoVG.nvgRect(context, 8.0f, 8.0f, 48.0f, 48.0f);
            NVGColor color = NVGColor.create();
            color.r(1.0f).g(0.0f).b(0.0f).a(1.0f);
            NanoVG.nvgFillColor(context, color);
            NanoVG.nvgFill(context);
            NanoVG.nvgEndFrame(context);

            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            int red = pixel.get(0) & 0xFF;
            int green = pixel.get(1) & 0xFF;
            int blue = pixel.get(2) & 0xFF;
            int alpha = pixel.get(3) & 0xFF;
            LOGGER.info("NanoVG spike: fbo texture={} pixel=({},{},{},{})",
                    framebuffer.texture(), red, green, blue, alpha);
            if (red < 200 || green > 40 || blue > 40 || alpha < 200) {
                LOGGER.warn("NanoVG spike: unexpected pixel; blend/format needs investigation");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("NanoVG spike failed", exception);
        } finally {
            if (context != 0L) {
                try {
                    NanoVGGL3.nvgluBindFramebuffer(context, null);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup after a failed context.
                }
                if (framebuffer != null) {
                    NanoVGGL3.nvgluDeleteFramebuffer(context, framebuffer);
                }
                NanoVGGL3.nvgDelete(context);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
}
