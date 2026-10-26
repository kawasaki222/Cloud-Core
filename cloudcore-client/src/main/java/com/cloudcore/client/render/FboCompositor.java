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
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-screen colour target that NanoVG renders into and that is composited through
 * {@code GuiGraphics#blit}. The texture is owned by Minecraft's GPU device so the GUI
 * pipeline can sample it directly.
 *
 * <p>Render thread only. {@link #begin()} and {@link #end()} must be paired; all GL state
 * they touch (framebuffer binding, viewport) is restored by {@code end()}.
 */
public final class FboCompositor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath("cloudcore", "dynamic/nanovg_surface");

    private GpuTexture texture;
    private GpuTextureView view;
    private GpuSampler sampler;
    private int framebuffer;
    private int width;
    private int height;
    private boolean available;
    private int previousFramebuffer;
    private int[] previousViewport;

    public boolean available() {
        return available;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** (Re)creates the target when the logical size changes. */
    public boolean ensure(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (available && this.width == width && this.height == height) {
            return true;
        }
        close();
        GpuDevice device = RenderSystem.getDevice();
        try {
            texture = device.createTexture("cloudcore-nanovg-surface",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, width, height, 1, 1);
            view = device.createTextureView(texture);
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, ((GlTexture) texture).glId(), 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("[CC-RENDER-010] FBO incomplete status=0x{}",
                        Integer.toHexString(status));
                close();
                return false;
            }
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID,
                    new FboTexture(texture, view, sampler));
            this.width = width;
            this.height = height;
            available = true;
            LOGGER.info("FBO {}x{} ready as {}", width, height, TEXTURE_ID);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("[CC-RENDER-011] FBO creation failed", exception);
            close();
            return false;
        }
    }

    public void begin() {
        if (!available) {
            throw new IllegalStateException("FBO not initialized");
        }
        previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
    }

    public void end() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        if (previousViewport != null) {
            GL11.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
        }
    }

    public void composite(GuiGraphics graphics, int x, int y, int width, int height) {
        if (!available) {
            return;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, y, 0.0f, 0.0f,
                width, height, this.width, this.height);
    }

    @Override
    public void close() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        available = false;
        // The texture/view/sampler are owned by the registered AbstractTexture and are
        // released by the texture manager on reload; closing here would double free.
        texture = null;
        view = null;
        sampler = null;
        width = 0;
        height = 0;
    }

    private static final class FboTexture extends AbstractTexture {
        private FboTexture(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
            this.texture = texture;
            this.textureView = view;
            this.sampler = sampler;
        }
    }
}
