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
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
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
public final class NanoVgBackend implements RenderBackend, RenderList.VectorTextSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int IMAGE_FLAGS = 0;

    private final FboCompositor compositor = new FboCompositor();
    private final FontRegistry fonts = new FontRegistry();
    /**
     * Measures with NanoVG's current font face at {@link RenderCommand.Text#DEFAULT_SIZE},
     * matching {@link #text}. The face is the first one that resolves (document font or the
     * built-in default), which is what a label without an explicit font draws with anyway.
     */
    private final TextWrap.Measure nanovgMeasure = new TextWrap.Measure() {
        @Override
        public int width(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            int fontId = fonts.resolve(null);
            if (context == 0L || fontId < 0) {
                return TextWrap.fallbackWidth(text);
            }
            NanoVG.nvgFontFaceId(context, fontId);
            NanoVG.nvgFontSize(context, RenderCommand.Text.DEFAULT_SIZE);
            return Math.max(1, Math.round(NanoVG.nvgTextBounds(context, 0.0f, 0.0f, text,
                    textBounds)));
        }

        @Override
        public int lineHeight() {
            return Math.max(1, Math.round(RenderCommand.Text.DEFAULT_SIZE * (9.0f / 9.0f)));
        }
    };
    private final java.util.concurrent.atomic.AtomicBoolean textFailureLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String, Integer> imageHandles = new HashMap<>();
    private final Map<String, int[]> imageSizes = new HashMap<>();
    private long context;
    private boolean attempted;
    private boolean available;
    private boolean failed;
    private FrameContext frame;
    private NVGColor color;
    private NVGPaint paint;
    /** Scratch for {@code nvgTextBounds}, which returns the advance width. */
    private final float[] textBounds = new float[4];
    /**
     * Bumped by {@code F9}. Each backend instance notices on its next frame and drops its
     * image handles and font ids, which is the only way to re-read edited files: NanoVG
     * caches both inside the context.
     */
    private static final java.util.concurrent.atomic.AtomicLong ASSET_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();
    private long seenAssetGeneration = Long.MIN_VALUE;

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
            // The target must be allocated in device pixels, not GUI units: NanoVG multiplies
            // the logical size by pixelRatio when it sets its viewport, so a GUI-sized target
            // left it drawing into the bottom quarter of the requested area and the composite
            // then magnified the result by guiScale with a LINEAR sampler, which softened every
            // glyph (measured edge ramp 2px against 1px for natively drawn text).
            int pixelRatio = Math.max(1, context.guiScale());
            if (!compositor.ensure(context.width() * pixelRatio,
                    context.height() * pixelRatio)) {
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
            syncAssets();
            // Wrapped labels measure while the list is built, before draw(); install NanoVG's
            // own metrics now so a TTF line breaks where it will actually end.
            TextWrap.install(nanovgMeasure);
        } catch (RuntimeException exception) {
            fail("beginFrame failed: " + exception.getMessage());
        }
    }

    /** Marks every live backend as stale; the next frame reloads images and fonts. */
    public static void invalidateAssets() {
        ASSET_GENERATION.incrementAndGet();
    }

    private void syncAssets() {
        long current = ASSET_GENERATION.get();
        if (current == seenAssetGeneration) {
            return;
        }
        seenAssetGeneration = current;
        for (int handle : imageHandles.values()) {
            if (handle != 0) {
                NanoVG.nvgDeleteImage(context, handle);
            }
        }
        imageHandles.clear();
        imageSizes.clear();
        // Font ids point into the old buffers; re-attaching clears the table so the next
        // resolve reads the file again. NanoVG keeps the old font objects until the context
        // is deleted, so repeated reloads grow memory slightly (see docs/10).
        fonts.attach(context);
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
                    case RenderCommand.ClipPush clip -> {
                        NanoVG.nvgSave(context);
                        NanoVG.nvgScissor(context, clip.x(), clip.y(), clip.width(),
                                clip.height());
                    }
                    case RenderCommand.ClipPop ignored -> NanoVG.nvgRestore(context);
                    case RenderCommand.ImageProbe probe -> probe(probe);
                    case RenderCommand.ShiftEnd ignored -> NanoVG.nvgRestore(context);
                    case RenderCommand.Text text -> text(text);
                    case RenderCommand.RichText rich -> richText(rich);
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

    /** Rich text uses the same rule: only an explicit, resolvable font goes through NanoVG. */
    @Override
    public boolean drawsRichText(RenderCommand.RichText text) {
        return drawsFont(text.font());
    }

    private boolean drawsFont(String font) {
        if (!available() || font == null || font.isBlank()) {
            return false;
        }
        return fonts.resolve(font) >= 0;
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
        NanoVG.nvgFontSize(context, text.size());
        NanoVG.nvgTextAlign(context, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_BASELINE);
        setColor(text.argb());
        NanoVG.nvgFillColor(context, color);
        // The baseline offset tracks the em size so larger text does not sit lower than the
        // row it belongs to; 7.5/9 is the ratio that aligned the default size.
        NanoVG.nvgText(context, text.x(), text.y() + text.size() * (7.5f / 9.0f), text.text());
    }

    /**
     * Draws coloured runs left to right. The cursor advances by each run's measured advance
     * width, so mixed colours stay kerned exactly as the vector font renders them.
     */
    private void richText(RenderCommand.RichText rich) {
        if (!drawsRichText(rich)) {
            return;
        }
        int fontId = fonts.resolve(rich.font());
        if (fontId < 0) {
            return;
        }
        NanoVG.nvgFontFaceId(context, fontId);
        NanoVG.nvgFontSize(context, rich.size());
        NanoVG.nvgTextAlign(context, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_BASELINE);
        float baseline = rich.y() + rich.size() * (7.5f / 9.0f);
        float cursor = rich.x();
        for (RenderCommand.RichText.Run run : rich.runs()) {
            if (run.text().isEmpty()) {
                continue;
            }
            setColor(run.argb());
            NanoVG.nvgFillColor(context, color);
            NanoVG.nvgText(context, cursor, baseline, run.text());
            cursor += NanoVG.nvgTextBounds(context, cursor, baseline, run.text(),
                    textBounds);
        }
    }

    private void fill(RenderCommand.FillRect rect) {
        setColor(rect.argb());
        NanoVG.nvgBeginPath(context);
        NanoVG.nvgRect(context, rect.x(), rect.y(), rect.width(), rect.height());
        NanoVG.nvgFillColor(context, color);
        NanoVG.nvgFill(context);
    }

    /**
     * Translates so the following image draws from its fitted top-left, which may sit outside
     * the element box, and clips that box. Without a known texture size the fit falls back to
     * stretching the box itself.
     */
    private void probe(RenderCommand.ImageProbe probe) {
        int boxX = probe.x();
        int boxY = probe.y();
        int boxWidth = probe.width();
        int boxHeight = probe.height();
        Integer handle = imageHandle(probe.texture());
        if (handle != null && handle != 0) {
            int[] size = imageSize(handle, probe.texture());
            if (size[0] > 0 && size[1] > 0 && boxWidth > 0 && boxHeight > 0) {
                double scale = probe.fit() == RenderCommand.Image.Fit.CONTAIN
                        ? Math.min((double) boxWidth / size[0], (double) boxHeight / size[1])
                        : Math.max((double) boxWidth / size[0], (double) boxHeight / size[1]);
                int width = Math.max(1, (int) Math.round(size[0] * scale));
                int height = Math.max(1, (int) Math.round(size[1] * scale));
                boxX -= Math.max(0, (width - boxWidth) / 2);
                boxY -= Math.max(0, (height - boxHeight) / 2);
            }
        }
        NanoVG.nvgSave(context);
        NanoVG.nvgTranslate(context, boxX, boxY);
        NanoVG.nvgScissor(context, -boxX, -boxY, boxWidth, boxHeight);
    }

    /** Size of an already-loaded image, cached for the life of the asset generation. */
    private int[] imageSize(int handle, String reference) {
        int[] cached = imageSizes.get(reference);
        if (cached != null) {
            return cached;
        }
        int[] width = new int[1];
        int[] height = new int[1];
        NanoVG.nvgImageSize(context, handle, width, height);
        int[] size = new int[]{Math.max(0, width[0]), Math.max(0, height[0])};
        imageSizes.put(reference, size);
        return size;
    }

    private void image(RenderCommand.Image image) {
        Integer handle = imageHandle(image.texture());
        if (handle == null || handle == 0) {
            return;
        }
        int[] size = imageSizes.getOrDefault(image.texture(), new int[]{1, 1});
        for (NineSlice.Piece piece : NineSlice.split(image.x(), image.y(),
                image.width(), image.height(), size[0], size[1], image.nineSlice())) {
            if (piece.dstWidth() <= 0 || piece.dstHeight() <= 0) {
                continue;
            }
            // NanoVG patterns map the whole image; scale it so the piece's source rect
            // lands exactly on the destination rect.
            float scaleX = (float) piece.dstWidth() / piece.srcWidth();
            float scaleY = (float) piece.dstHeight() / piece.srcHeight();
            float originX = piece.dstX() - piece.srcX() * scaleX;
            float originY = piece.dstY() - piece.srcY() * scaleY;
            NanoVG.nvgImagePattern(context, originX, originY,
                    size[0] * scaleX, size[1] * scaleY, 0.0f, handle, 1.0f, paint);
            NanoVG.nvgBeginPath(context);
            NanoVG.nvgRect(context, piece.dstX(), piece.dstY(),
                    piece.dstWidth(), piece.dstHeight());
            NanoVG.nvgFillPaint(context, paint);
            NanoVG.nvgFill(context);
        }
    }

    private Integer imageHandle(String reference) {
        Integer cached = imageHandles.get(reference);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = TextureReferences.readBytes(reference);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            // Do not cache misses: an inline texture may finish downloading later.
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
            imageSizes.put(reference, new int[]{width[0], height[0]});
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
            // NanoVG binds its own VAO, program and textures and enables stencil/cull.
            // Leaving them behind made the next frame's world pass draw through NanoVG's
            // vertex layout: the terrain rendered black while the GUI (which binds its own
            // state per pipeline) stayed correct.
            restoreGlState();
            compositor.end();
            compositor.composite(current.graphics(), 0, 0,
                    current.width(), current.height());
        } catch (RuntimeException exception) {
            fail("endFrame failed: " + exception.getMessage());
        }
    }

    /**
     * Puts raw GL back into the state Blaze3D expects after a GUI pass. NanoVG saves most of
     * what it enables, but the vertex-array binding, program and texture units are not part
     * of that restore set.
     */
    private void restoreGlState() {
        // Tracked accessors only: NanoVG binds raw, but Blaze3D caches everything it binds,
        // and a raw reset here would leave the cache claiming the *opposite* of the real
        // state, which is worse than the original pollution.
        com.mojang.blaze3d.opengl.GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.opengl.GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        com.mojang.blaze3d.opengl.GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        com.mojang.blaze3d.opengl.GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, 0);
        com.mojang.blaze3d.opengl.GlStateManager._bindTexture(0);
        com.mojang.blaze3d.opengl.GlStateManager._disableScissorTest();
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        com.mojang.blaze3d.opengl.GlStateManager._disableCull();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glStencilMask(0xFF);
        org.lwjgl.opengl.GL20.glBlendEquationSeparate(
                org.lwjgl.opengl.GL14.GL_FUNC_ADD, org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        com.mojang.blaze3d.opengl.GlStateManager._enableBlend();
        com.mojang.blaze3d.opengl.GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
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
