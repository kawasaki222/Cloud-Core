package com.cloudcore.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vector backend: fills and images are drawn by NanoVG into an off-screen FBO that is
 * composited through {@code GuiGraphics}. Text and item icons stay native (see
 * {@link NativeBackend}); the caller draws them after this backend's frame.
 *
 * <p>Enabled with {@code -PcloudcoreNanoVg=true} until cross-platform verification lands.
 * Every failure disables the backend for the session and leaves the native path in charge.
 *
 * <p>Render thread only.
 */
public final class NanoVgBackend implements RenderBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int IMAGE_FLAGS = 0;

    private final FboCompositor compositor = new FboCompositor();
    private final FontRegistry fonts = new FontRegistry();
    private final java.util.concurrent.atomic.AtomicBoolean textFailureLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String, Integer> imageHandles = new HashMap<>();
    private long context;
    private boolean attempted;
    private boolean available;
    private boolean failed;
    private FrameContext frame;
    private NVGColor color;
    private NVGPaint paint;

    public NanoVgBackend() {
        color = NVGColor.create();
        paint = NVGPaint.create();
    }

    @Override
    public BackendId id() {
        return BackendId.NANOVG;
    }

    @Override
    public boolean available() {
        ensureInitialized();
        return available && !failed;
    }

    private void ensureInitialized() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!Boolean.getBoolean("cloudcore.nanovg")) {
            return;
        }
        try {
            context = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
            if (context == 0L) {
                LOGGER.error("[CC-RENDER-020] nvgCreate returned 0");
                return;
            }
            available = true;
            fonts.attach(context);
            LOGGER.info("NanoVG backend initialised (custom font available={})", fonts.hasFont());
        } catch (Throwable throwable) {
            LOGGER.error("[CC-RENDER-021] NanoVG backend init failed", throwable);
            available = false;
        }
    }

    @Override
    public void beginFrame(FrameContext context) {
        this.frame = Objects.requireNonNull(context, "context");
        if (!available()) {
            return;
        }
        try {
            // Render at device resolution so text and edges stay crisp after compositing.
            int pixelRatio = Math.max(1, context.guiScale());
            if (!compositor.ensure(context.width(), context.height())) {
                fail("FBO unavailable");
                return;
            }
            compositor.begin();
            // Minecraft leaves sampler objects and texture bindings behind; NanoVG's GL
            // backend assumes plain GL state, so reset the units it samples from.
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            NanoVG.nvgBeginFrame(this.context, context.width(), context.height(), pixelRatio);
            // FBO row 0 is the bottom row while GuiGraphics samples v=0 as the top row,
            // so the scene is flipped before compositing.
            NanoVG.nvgTranslate(this.context, 0.0f, context.height());
            NanoVG.nvgScale(this.context, 1.0f, -1.0f);
        } catch (RuntimeException exception) {
            fail("beginFrame failed: " + exception.getMessage());
        }
    }

    @Override
    public void draw(RenderList list, Layer layer) {
        if (!available || frame == null) {
            return;
        }
        try {
            for (RenderCommand command : list.commands()) {
                switch (command) {
                    case RenderCommand.FillRect rect -> fill(rect);
                    case RenderCommand.Image image -> image(image);
                    case RenderCommand.Text text -> text(text);
                    case RenderCommand.ItemIcon ignored -> {
                        // Item models are drawn by the native bridge.
                    }
                }
            }
        } catch (RuntimeException exception) {
            fail("draw failed: " + exception.getMessage());
        }
    }

    /**
     * Text is drawn by NanoVG only when the document asks for an explicit font; the default
     * Minecraft font stays native so ordinary pages keep the vanilla, crisp look.
     */
    public boolean drawsText(RenderCommand.Text text) {
        if (!available() || text.font() == null || text.font().isBlank()) {
            return false;
        }
        return fonts.resolve(text.font()) >= 0;
    }

    private void text(RenderCommand.Text text) {
        if (!drawsText(text)) {
            return;
        }
        int fontId = fonts.resolve(text.font());
        if (fontId < 0) {
            fontId = fonts.resolve(null);
        }
        if (fontId < 0) {
            if (textFailureLogged.compareAndSet(false, true)) {
                LOGGER.warn("[CC-FONT-005] no font available for text '{}'", text.text());
            }
            return;
        }
        NanoVG.nvgFontFaceId(context, fontId);
        NanoVG.nvgFontSize(context, 9.0f);
        NanoVG.nvgTextAlign(context, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_BASELINE);
        setColor(text.argb());
        NanoVG.nvgFillColor(context, color);
        NanoVG.nvgText(context, text.x(), text.y() + 7.5f, text.text());
    }

    private void fill(RenderCommand.FillRect rect) {
        setColor(rect.argb());
        NanoVG.nvgBeginPath(context);
        NanoVG.nvgRect(context, rect.x(), rect.y(), rect.width(), rect.height());
        NanoVG.nvgFillColor(context, color);
        NanoVG.nvgFill(context);
    }

    private void image(RenderCommand.Image image) {
        Integer handle = imageHandle(image.texture());
        if (handle == null || handle == 0) {
            return;
        }
        NanoVG.nvgImagePattern(context, image.x(), image.y(), image.width(), image.height(),
                0.0f, handle, 1.0f, paint);
        NanoVG.nvgBeginPath(context);
        NanoVG.nvgRect(context, image.x(), image.y(), image.width(), image.height());
        NanoVG.nvgFillPaint(context, paint);
        NanoVG.nvgFill(context);
    }

    private Integer imageHandle(String reference) {
        Integer cached = imageHandles.get(reference);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = TextureReferences.readBytes(reference);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            LOGGER.warn("[CC-RENDER-022] NanoVG cannot load texture {}", reference);
            imageHandles.put(reference, 0);
            return 0;
        }
        java.nio.ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes).flip();
        int handle = NanoVG.nvgCreateImageMem(context, IMAGE_FLAGS, buffer);
        if (handle == 0) {
            LOGGER.warn("[CC-RENDER-023] NanoVG rejected texture {}", reference);
        } else {
            int[] width = new int[1];
            int[] height = new int[1];
            NanoVG.nvgImageSize(context, handle, width, height);
            LOGGER.info("NanoVG image {} -> handle {} size {}x{}", reference, handle,
                    width[0], height[0]);
        }
        imageHandles.put(reference, handle);
        return handle;
    }

    private void setColor(int argb) {
        color.r(((argb >> 16) & 0xFF) / 255.0f)
                .g(((argb >> 8) & 0xFF) / 255.0f)
                .b((argb & 0xFF) / 255.0f)
                .a(((argb >>> 24) & 0xFF) / 255.0f);
    }

    @Override
    public void endFrame() {
        FrameContext current = frame;
        frame = null;
        if (!available || current == null) {
            return;
        }
        try {
            NanoVG.nvgEndFrame(context);
            compositor.end();
            compositor.composite(current.graphics(), 0, 0,
                    current.width(), current.height());
        } catch (RuntimeException exception) {
            fail("endFrame failed: " + exception.getMessage());
        }
    }

    private void fail(String reason) {
        failed = true;
        LOGGER.error("[CC-RENDER-024] NanoVG backend disabled: {}", reason);
        try {
            compositor.end();
        } catch (RuntimeException ignored) {
            // The framebuffer may already be gone; nothing else to restore.
        }
    }

    /** Releases GPU state; call on client shutdown if the backend was ever used. */
    public void close() {
        for (int handle : imageHandles.values()) {
            if (handle != 0) {
                NanoVG.nvgDeleteImage(context, handle);
            }
        }
        imageHandles.clear();
        if (context != 0L) {
            NanoVGGL3.nvgDelete(context);
            context = 0L;
        }
        compositor.close();
        available = false;
    }
}
