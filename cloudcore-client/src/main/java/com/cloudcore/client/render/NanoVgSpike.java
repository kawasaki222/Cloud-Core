package com.cloudcore.client.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-first initialization/composite spike for U28/U29. It creates a NanoVG GL3
 * context, renders into a Minecraft-owned GPU texture through an FBO, reads one pixel
 * back, and registers the texture for {@code GuiGraphics.blit}.
 */
public final class NanoVgSpike {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath("cloudcore", "dynamic/nanovg_spike");
    private static volatile boolean textureReady;

    private NanoVgSpike() {
    }

    public static void runOnce() {
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        long context = 0L;
        GpuTexture texture = null;
        GpuTextureView view = null;
        GpuSampler sampler = null;
        int fbo = 0;
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture("cloudcore-nanovg-spike",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, 64, 64, 1, 1);
            view = device.createTextureView(texture);
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            int textureId = ((GlTexture) texture).glId();

            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, textureId, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("NanoVG spike: incomplete FBO status=0x{}", Integer.toHexString(status));
                return;
            }
            GL11.glViewport(0, 0, 64, 64);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            context = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
            if (context == 0L) {
                LOGGER.error("NanoVG spike: nvgCreate returned 0");
                return;
            }
            NanoVG.nvgBeginFrame(context, 64.0f, 64.0f, 1.0f);
            NanoVG.nvgBeginPath(context);
            NanoVG.nvgRect(context, 0.0f, 0.0f, 64.0f, 64.0f);
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
                    textureId, red, green, blue, alpha);
            if (red < 200 || green > 40 || blue > 40 || alpha < 200) {
                LOGGER.warn("NanoVG spike: unexpected pixel; blend/format needs investigation");
                return;
            }

            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID,
                    new FboTexture(texture, view, sampler));
            textureReady = true;
            LOGGER.info("NanoVG spike: registered {} for GuiGraphics compositing", TEXTURE_ID);
        } catch (RuntimeException exception) {
            LOGGER.error("NanoVG spike failed", exception);
        } finally {
            if (context != 0L) {
                NanoVGGL3.nvgDelete(context);
            }
            if (!textureReady) {
                if (sampler != null) {
                    sampler.close();
                }
                if (view != null) {
                    view.close();
                }
                if (texture != null) {
                    texture.close();
                }
            }
            if (fbo != 0) {
                GL30.glDeleteFramebuffers(fbo);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    public static boolean composite(GuiGraphics graphics, int x, int y, int size) {
        if (!textureReady) {
            return false;
        }
        // 1.21.11 requires the explicit texture size; the normalized-UV overload draws nothing
        // for FBO-backed textures.
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TEXTURE_ID,
                x, y, 0.0f, 0.0f, size, size, 64, 64, 64, 64);
        return true;
    }

    private static final class FboTexture extends AbstractTexture {
        private FboTexture(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
            this.texture = texture;
            this.textureView = view;
            this.sampler = sampler;
        }
    }
}
