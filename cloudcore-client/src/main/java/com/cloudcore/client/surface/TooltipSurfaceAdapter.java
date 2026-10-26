package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skinned item tooltip: draws the document's panel and the native tooltip lines, clipping
 * them to {@code surface_options.max_height} and scrolling with the wheel. The wheel goes
 * through {@code MouseHandler#onScroll} because screens may override the screen-level hook.
 *
 * <p>Render thread only; the scroll offset is reset whenever the hovered content changes.
 */
public final class TooltipSurfaceAdapter implements SurfaceAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_RENDER =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final int PADDING = 3;
    private static final int SCROLL_STEP = 12;
    /** A scroll event applies only while a tooltip was drawn within this window. */
    private static final long SCROLL_WINDOW_MS = 150;
    private static final int DEFAULT_MAX_HEIGHT = 160;

    private static volatile float scrollOffset;
    private static volatile int lastContentHeight;
    private static volatile int lastViewportHeight;
    private static volatile int lastSignature;
    private static volatile long lastRenderMs;

    @Override
    public SurfaceType type() {
        return SurfaceType.TOOLTIP;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.TOOLTIP;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        DOCUMENT.set(runtime.document());
        resetScroll();
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        UiDocument document = DOCUMENT.get();
        if (document != null && document.id().equals(uiId)) {
            DOCUMENT.set(null);
            resetScroll();
        }
    }

    @Override
    public void reset(CloseReason reason) {
        DOCUMENT.set(null);
        resetScroll();
    }

    private static void resetScroll() {
        scrollOffset = 0.0f;
        lastContentHeight = 0;
        lastViewportHeight = 0;
        lastSignature = 0;
    }

    /**
     * @return true when the tooltip was drawn here (the caller cancels the vanilla draw)
     */
    public static boolean render(GuiGraphics graphics, Font font,
            List<ClientTooltipComponent> lines, int x, int y,
            ClientTooltipPositioner positioner) {
        UiDocument document = DOCUMENT.get();
        if (document == null || lines.isEmpty()) {
            return false;
        }
        int signature = signature(lines, font);
        if (signature != lastSignature) {
            scrollOffset = 0.0f;
            lastSignature = signature;
        }

        int contentWidth = 0;
        int contentHeight = 0;
        for (ClientTooltipComponent line : lines) {
            contentWidth = Math.max(contentWidth, line.getWidth(font));
            contentHeight += line.getHeight(font);
        }
        int maxContentHeight = Math.max(1, maxHeight(document) - PADDING * 2);
        int viewport = Math.min(contentHeight, maxContentHeight);
        boolean scrollable = contentHeight > viewport;

        int width = contentWidth + PADDING * 2 + (scrollable ? scrollbarWidth(document) + 2 : 0);
        int height = viewport + PADDING * 2;

        Minecraft minecraft = Minecraft.getInstance();
        Vector2ic position = positioner.positionTooltip(
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                x, y, width, height);
        int left = position.x();
        int top = position.y();

        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xE0000000);
        drawBackground(graphics, document, left, top, width, height);

        int innerLeft = left + PADDING;
        int innerTop = top + PADDING;
        if (scrollable) {
            scrollOffset = clamp(scrollOffset, 0.0f, contentHeight - viewport);
            graphics.enableScissor(innerLeft, innerTop, innerLeft + contentWidth,
                    innerTop + viewport);
        }
        float lineY = innerTop - scrollOffset;
        for (ClientTooltipComponent line : lines) {
            if (lineY + line.getHeight(font) >= innerTop
                    && lineY <= innerTop + viewport) {
                line.renderText(graphics, font, innerLeft, (int) lineY);
                line.renderImage(font, innerLeft, (int) lineY, line.getWidth(font),
                        line.getHeight(font), graphics);
            }
            lineY += line.getHeight(font);
        }
        if (scrollable) {
            graphics.disableScissor();
            drawScrollbar(graphics, document, left + width - scrollbarWidth(document) - 1,
                    innerTop, scrollbarWidth(document), viewport, contentHeight);
        }

        lastContentHeight = contentHeight;
        lastViewportHeight = viewport;
        lastRenderMs = System.currentTimeMillis();

        if (FIRST_RENDER.compareAndSet(false, true)) {
            LOGGER.info("Custom tooltip rendered lines={} size={}x{} scrollable={}",
                    lines.size(), width, height, scrollable);
        } else {
            LOGGER.debug("Custom tooltip lines={} size={}x{} offset={}", lines.size(), width,
                    height, scrollOffset);
        }
        return true;
    }

    /** Applies a wheel step when a skinned tooltip was drawn recently. */
    public static boolean onScroll(double yOffset) {
        if (DOCUMENT.get() == null || yOffset == 0.0
                || System.currentTimeMillis() - lastRenderMs > SCROLL_WINDOW_MS) {
            return false;
        }
        int maxOffset = Math.max(0, lastContentHeight - lastViewportHeight);
        if (maxOffset <= 0) {
            return false;
        }
        scrollOffset = clamp(scrollOffset - (float) (yOffset * SCROLL_STEP), 0.0f, maxOffset);
        return true;
    }

    private static void drawScrollbar(GuiGraphics graphics, UiDocument document, int x, int y,
            int width, int viewport, int contentHeight) {
        int track = 0x60000000;
        int thumb = 0xFF9AD0FF;
        Map<String, Object> options = document.surfaceOptions();
        if (options.get("scrollbar") instanceof Map<?, ?> bar) {
            track = com.cloudcore.client.render.Colors.parse(bar.get("track_color"), track);
            thumb = com.cloudcore.client.render.Colors.parse(bar.get("thumb_color"), thumb);
        }
        graphics.fill(x, y, x + width, y + viewport, track);
        int thumbHeight = Math.max(8, viewport * viewport / Math.max(1, contentHeight));
        int maxOffset = Math.max(1, contentHeight - viewport);
        int thumbY = y + Math.round((viewport - thumbHeight) * (scrollOffset / maxOffset));
        graphics.fill(x, thumbY, x + width, thumbY + thumbHeight, thumb);
    }

    private static int maxHeight(UiDocument document) {
        Object value = document.surfaceOptions().get("max_height");
        return value instanceof Number number ? Math.max(8, number.intValue())
                : DEFAULT_MAX_HEIGHT;
    }

    private static int scrollbarWidth(UiDocument document) {
        Object value = document.surfaceOptions().get("scrollbar_width");
        return value instanceof Number number ? Math.max(1, number.intValue()) : 3;
    }

    private static int signature(List<ClientTooltipComponent> lines, Font font) {
        int result = lines.size();
        for (ClientTooltipComponent line : lines) {
            result = result * 31 + line.getWidth(font);
            result = result * 31 + line.getHeight(font);
        }
        return result;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The first element carrying a style background wins: a texture reference (with optional
     * {@code nine_slice}) is blitted over the tooltip box, otherwise its colour fills the box.
     */
    private static void drawBackground(GuiGraphics graphics, UiDocument document, int x, int y,
            int width, int height) {
        for (UiElement element : document.elements()) {
            if (!(element.attributes().get("style") instanceof Map<?, ?> style)) {
                continue;
            }
            Object background = style.get("background");
            if (background instanceof String reference
                    && com.cloudcore.client.render.NativeBackend.isTextureReference(reference)
                    && com.cloudcore.client.render.NativeBackend.drawTexture(graphics, reference,
                    x, y, width, height, nineSlice(style.get("nine_slice")))) {
                return;
            }
            int color = com.cloudcore.client.render.Colors.parse(background, 0);
            if (color != 0) {
                graphics.fill(x, y, x + width, y + height, color);
                return;
            }
        }
        graphics.fill(x, y, x + width, y + height, 0xF0101828);
    }

    private static int[] nineSlice(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 4) {
            return null;
        }
        int[] borders = new int[4];
        for (int index = 0; index < 4; index++) {
            borders[index] = list.get(index) instanceof Number number
                    ? Math.max(0, number.intValue()) : 0;
        }
        return borders;
    }
}
