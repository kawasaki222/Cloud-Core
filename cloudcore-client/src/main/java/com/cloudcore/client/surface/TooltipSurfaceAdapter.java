package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.FontSpec;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.client.mixin.AbstractContainerScreenAccessor;
import com.cloudcore.client.mixin.FontManagerAccessor;
import com.cloudcore.client.mixin.MinecraftAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
    private static final java.util.Set<String> WARNED_FONTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
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

        int padding = padding(document);
        Icon icon = icon(document);
        ItemStack hovered = hoveredStack();
        ItemStack iconStack = icon.show() ? hovered : ItemStack.EMPTY;
        List<Attachment> attachments = attachments(document, hovered, font);
        List<LineLayout> layout = layout(document, lines, font);
        int contentWidth = 0;
        int contentHeight = 0;
        for (LineLayout line : layout) {
            contentWidth = Math.max(contentWidth, line.x() + line.width());
            contentHeight = Math.max(contentHeight, line.y() + line.height());
        }
        if (!iconStack.isEmpty()) {
            contentWidth = Math.max(contentWidth, icon.x() + icon.size());
            contentHeight = Math.max(contentHeight, icon.y() + icon.size());
        }
        Minecraft minecraft = Minecraft.getInstance();
        int screenHeight = Math.max(32, minecraft.getWindow().getGuiScaledHeight() - 16);
        int configuredHeight = Math.max(8, Math.min(maxHeight(document), screenHeight));
        int maxContentHeight = Math.max(1, configuredHeight - padding * 2);
        ScrollMode mode = scrollMode(document);
        // Scroll is opt-in: the default keeps the whole tooltip visible and only clamps to
        // the screen when the content is genuinely taller than the window.
        int viewport;
        boolean scrollable;
        if (mode == ScrollMode.NEVER) {
            viewport = Math.min(contentHeight, screenHeight - padding * 2);
            scrollable = contentHeight > viewport;
        } else if (mode == ScrollMode.ALWAYS) {
            viewport = Math.min(contentHeight, maxContentHeight);
            scrollable = true;
        } else {
            viewport = Math.min(contentHeight, maxContentHeight);
            scrollable = contentHeight > viewport;
        }

        int width = Math.max(document.width(),
                contentWidth + padding * 2 + (scrollable ? scrollbarWidth(document) + 2 : 0));
        int height = viewport + padding * 2;

        Vector2ic position = positioner.positionTooltip(
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                x, y, width, height);
        int left = position.x();
        int top = position.y();

        for (Attachment attachment : attachments) {
            drawAttachment(graphics, attachment, left, top, width, height, minecraft);
        }

        int backdrop = com.cloudcore.client.render.Colors.parse(
                option(document, "backdrop_color"), 0);
        if (backdrop != 0) {
            graphics.fill(left, top, left + width, top + height, backdrop);
        }
        drawBackground(graphics, document, left, top, width, height);

        int innerLeft = left + padding;
        int innerTop = top + padding;
        if (scrollable) {
            scrollOffset = clamp(scrollOffset, 0.0f, contentHeight - viewport);
            graphics.enableScissor(innerLeft, innerTop, innerLeft + contentWidth,
                    innerTop + viewport);
        }
        if (!iconStack.isEmpty()) {
            drawItem(graphics, iconStack, innerLeft + icon.x(), innerTop + icon.y(),
                    icon.size());
        }
        for (LineLayout line : layout) {
            float drawY = innerTop + line.y() - scrollOffset;
            if (drawY + line.height() >= innerTop
                    && drawY <= innerTop + viewport) {
                int drawX = innerLeft + line.x();
                line.component().renderText(graphics, line.font(), drawX, (int) drawY);
                line.component().renderImage(line.font(), drawX, (int) drawY, line.width(),
                        line.height(), graphics);
            }
        }
        if (scrollable) {
            graphics.disableScissor();
            drawScrollbar(graphics, document,
                    left + width - padding - scrollbarWidth(document) - 2,
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
        if (option(document, "scrollbar") instanceof Map<?, ?> bar) {
            track = com.cloudcore.client.render.Colors.parse(bar.get("track_color"), track);
            thumb = com.cloudcore.client.render.Colors.parse(bar.get("thumb_color"), thumb);
        }
        graphics.fill(x, y, x + width, y + viewport, track);
        int thumbHeight = Math.max(8, viewport * viewport / Math.max(1, contentHeight));
        int maxOffset = Math.max(1, contentHeight - viewport);
        int thumbY = y + Math.round((viewport - thumbHeight) * (scrollOffset / maxOffset));
        graphics.fill(x, thumbY, x + width, thumbY + thumbHeight, thumb);
    }

    private enum ScrollMode {
        NEVER, AUTO, ALWAYS
    }

    /**
     * {@code scroll: never|auto|always}; omitted means never so a normal tooltip is never
     * clipped into a scroll box by accident. {@code scrollable: true/false} also works.
     */
    private static ScrollMode scrollMode(UiDocument document) {
        Object value = option(document, "scroll");
        if (value == null) {
            value = option(document, "scrollable");
        }
        ScrollMode fromToggle = parseScrollMode(value);
        if (fromToggle != null) {
            return fromToggle;
        }
        if (option(document, "scrollbar") instanceof Map<?, ?> bar) {
            ScrollMode fromBar = parseScrollMode(bar.get("mode"));
            if (fromBar != null) {
                return fromBar;
            }
        }
        return ScrollMode.NEVER;
    }

    private static ScrollMode parseScrollMode(Object value) {
        if (value instanceof Boolean enabled) {
            return enabled ? ScrollMode.AUTO : ScrollMode.NEVER;
        }
        if (value instanceof String text) {
            return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "always", "on" -> ScrollMode.ALWAYS;
                case "auto" -> ScrollMode.AUTO;
                case "never", "off" -> ScrollMode.NEVER;
                default -> null;
            };
        }
        return null;
    }

    /** Nested {@code surface_options.tooltip.*} wins, flat keys stay supported. */
    private static Object option(UiDocument document, String key) {
        Object nested = document.surfaceOptions().get("tooltip");
        if (nested instanceof Map<?, ?> map && map.containsKey(key)) {
            return map.get(key);
        }
        return document.surfaceOptions().get(key);
    }

    private static int maxHeight(UiDocument document) {
        Object value = option(document, "max_height");
        return value instanceof Number number ? Math.max(8, number.intValue())
                : DEFAULT_MAX_HEIGHT;
    }

    /** Space between the frame and native tooltip text; ornate skins need more than 3 px. */
    private static int padding(UiDocument document) {
        Object value = option(document, "padding");
        if (value instanceof Number number) {
            return Math.max(0, Math.min(48, number.intValue()));
        }
        return PADDING;
    }

    private static int scrollbarWidth(UiDocument document) {
        Object value = option(document, "scrollbar_width");
        if (!(value instanceof Number) && option(document, "scrollbar") instanceof Map<?, ?> bar) {
            value = bar.get("width");
        }
        return value instanceof Number number ? Math.max(1, number.intValue()) : 3;
    }

    private record LineLayout(ClientTooltipComponent component, Font font,
            int x, int y, int width, int height) {
    }

    private record Icon(boolean show, int x, int y, int size) {
    }

    private record AttachmentLine(String text, int color) {
    }

    /**
     * A panel attached to one side of the tooltip. It carries normal page text, not native
     * item lines, so set summaries and similar blocks can live beside the item tooltip.
     */
    private record Attachment(
            String side, int gap, int offsetX, int offsetY, String align,
            int padding, int width, int height,
            Object background, int[] nineSlice, int borderColor, int borderWidth,
            String title, List<AttachmentLine> lines, Font font,
            int titleColor, int lineColor, int lineGap, boolean separator) {
    }

    /**
     * {@code attachments: [{side, match, ...}]}. Coordinates are relative to the rendered
     * tooltip box; {@code match} limits an attachment to a hovered item whose display name
     * contains that text. Without {@code match} the attachment accompanies every tooltip.
     */
    private static List<Attachment> attachments(UiDocument document, ItemStack stack,
            Font fallback) {
        Object value = option(document, "attachments");
        if (value == null) {
            return List.of();
        }
        List<?> entries = value instanceof List<?> list ? list : List.of(value);
        String hoverName = stack.isEmpty() ? "" : stack.getHoverName().getString();
        List<Attachment> result = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String match = stringOption(map, "match", "");
            if (!match.isBlank() && !hoverName.contains(match)) {
                continue;
            }
            Attachment attachment = attachment(document, map, fallback);
            if (attachment != null) {
                result.add(attachment);
            }
        }
        return result;
    }

    private static Attachment attachment(UiDocument document, Map<?, ?> map, Font fallback) {
        String side = stringOption(map, "side", "right").toLowerCase(java.util.Locale.ROOT);
        if (!side.equals("left") && !side.equals("right") && !side.equals("top")
                && !side.equals("bottom")) {
            side = "right";
        }
        String align = stringOption(map, "align", "center").toLowerCase(java.util.Locale.ROOT);
        if (!align.equals("start") && !align.equals("end")) {
            align = "center";
        }

        int gap = clampInt(intOption(map, "gap", 4), 0, 128);
        int offsetX = clampInt(intOption(map, "offset_x", 0), -4096, 4096);
        int offsetY = clampInt(intOption(map, "offset_y", 0), -4096, 4096);
        int padding = clampInt(intOption(map, "padding", 8), 0, 48);
        Font font = sectionFont(document, map, fallback);
        int lineGap = clampInt(intOption(map, "line_gap", 0), 0, 16);
        int lineColor = com.cloudcore.client.render.Colors.parse(map.get("color"),
                0xFFFFFFFF);
        int titleColor = com.cloudcore.client.render.Colors.parse(map.get("title_color"),
                lineColor);
        String title = stringOption(map, "title", "");
        boolean separator = booleanOption(map, "separator", !title.isBlank());

        List<AttachmentLine> lines = new ArrayList<>();
        if (map.get("lines") instanceof List<?> rawLines) {
            for (Object raw : rawLines) {
                if (raw instanceof String text) {
                    lines.add(new AttachmentLine(text, lineColor));
                } else if (raw instanceof Map<?, ?> line) {
                    lines.add(new AttachmentLine(stringOption(line, "text", ""),
                            com.cloudcore.client.render.Colors.parse(line.get("color"),
                                    lineColor)));
                }
            }
        }
        if (title.isBlank() && lines.isEmpty()) {
            return null;
        }

        int textWidth = font.width(title);
        for (AttachmentLine line : lines) {
            textWidth = Math.max(textWidth, font.width(line.text()));
        }
        int requestedWidth = clampInt(intOption(map, "width", 0), 0, 4096);
        int width = requestedWidth > 0 ? requestedWidth
                : Math.max(40, textWidth + padding * 2);
        int contentHeight = padding;
        if (!title.isBlank()) {
            contentHeight += font.lineHeight + 4;
        }
        for (int index = 0; index < lines.size(); index++) {
            contentHeight += font.lineHeight + (index == 0 ? 0 : lineGap);
        }
        contentHeight += padding;
        int requestedHeight = clampInt(intOption(map, "height", 0), 0, 4096);
        int height = requestedHeight > 0 ? requestedHeight : contentHeight;

        Map<?, ?> style = map.get("style") instanceof Map<?, ?> nested ? nested : map;
        Object background = style.get("background");
        if (background == null) {
            background = 0xE0101828;
        }
        return new Attachment(side, gap, offsetX, offsetY, align, padding, width, height,
                background, nineSlice(style.get("nine_slice")),
                com.cloudcore.client.render.Colors.parse(style.get("border_color"), 0),
                clampInt(intOption(style, "border_width", 1), 0, 8),
                title, lines, font, titleColor, lineColor, lineGap, separator);
    }

    private static void drawAttachment(GuiGraphics graphics, Attachment attachment,
            int tooltipLeft, int tooltipTop, int tooltipWidth, int tooltipHeight,
            Minecraft minecraft) {
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int x = tooltipLeft;
        int y = tooltipTop;
        if (attachment.side().equals("left")) {
            x -= attachment.gap() + attachment.width();
            y += alignOffset(tooltipHeight, attachment.height(), attachment.align());
        } else if (attachment.side().equals("right")) {
            x += tooltipWidth + attachment.gap();
            y += alignOffset(tooltipHeight, attachment.height(), attachment.align());
        } else if (attachment.side().equals("top")) {
            y -= attachment.gap() + attachment.height();
            x += alignOffset(tooltipWidth, attachment.width(), attachment.align());
        } else {
            y += tooltipHeight + attachment.gap();
            x += alignOffset(tooltipWidth, attachment.width(), attachment.align());
        }
        x += attachment.offsetX();
        y += attachment.offsetY();
        x = Math.max(0, Math.min(Math.max(0, screenWidth - attachment.width()), x));
        y = Math.max(0, Math.min(Math.max(0, screenHeight - attachment.height()), y));

        boolean textured = attachment.background() instanceof String reference
                && com.cloudcore.client.render.NativeBackend.isTextureReference(reference)
                && com.cloudcore.client.render.NativeBackend.drawTexture(graphics, reference,
                x, y, attachment.width(), attachment.height(), attachment.nineSlice());
        if (!textured) {
            graphics.fill(x, y, x + attachment.width(), y + attachment.height(),
                    com.cloudcore.client.render.Colors.parse(attachment.background(),
                            0xE0101828));
        }
        int borderWidth = attachment.borderWidth();
        if (borderWidth > 0 && attachment.borderColor() != 0) {
            int color = attachment.borderColor();
            graphics.fill(x, y, x + attachment.width(), y + borderWidth, color);
            graphics.fill(x, y + attachment.height() - borderWidth,
                    x + attachment.width(), y + attachment.height(), color);
            graphics.fill(x, y, x + borderWidth, y + attachment.height(), color);
            graphics.fill(x + attachment.width() - borderWidth, y,
                    x + attachment.width(), y + attachment.height(), color);
        }

        int padding = Math.min(attachment.padding(),
                Math.min(attachment.width(), attachment.height()) / 3);
        int textX = x + padding;
        int textY = y + padding;
        graphics.enableScissor(x + padding, y + padding, x + attachment.width() - padding,
                y + attachment.height() - padding);
        if (!attachment.title().isBlank()) {
            graphics.drawString(attachment.font(), attachment.title(), textX, textY,
                    attachment.titleColor(), true);
            textY += attachment.font().lineHeight + 4;
            if (attachment.separator()) {
                graphics.fill(textX, textY - 2, x + attachment.width() - padding,
                        textY - 1, attachment.borderColor());
            }
        }
        for (AttachmentLine line : attachment.lines()) {
            if (!line.text().isEmpty()) {
                graphics.drawString(attachment.font(), line.text(), textX, textY,
                        line.color(), true);
            }
            textY += attachment.font().lineHeight + attachment.lineGap();
        }
        graphics.disableScissor();
    }

    private static int alignOffset(int container, int child, String align) {
        return switch (align) {
            case "start" -> 0;
            case "end" -> container - child;
            default -> (container - child) / 2;
        };
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stringOption(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String text ? text : fallback;
    }

    private static boolean booleanOption(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean enabled ? enabled : fallback;
    }

    /**
     * {@code icon: true} or {@code icon: {show, x, y, size}}; the default is hidden so a
     * normal tooltip keeps its text-only layout.
     */
    private static Icon icon(UiDocument document) {
        Object value = option(document, "icon");
        if (value instanceof Boolean show) {
            return new Icon(show, 0, 0, 16);
        }
        if (value instanceof Map<?, ?> map) {
            boolean show = !(map.get("show") instanceof Boolean enabled) || enabled;
            int size = Math.max(8, Math.min(64, intOption(map, "size", 16)));
            return new Icon(show, offset(map, "x"), offset(map, "y"), size);
        }
        return new Icon(false, 0, 0, 16);
    }

    private static ItemStack hoveredStack() {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return ItemStack.EMPTY;
        }
        Slot slot = ((AbstractContainerScreenAccessor) screen).cloudcore$hoveredSlot();
        return slot == null ? ItemStack.EMPTY : slot.getItem();
    }

    private static void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y, int size) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        float scale = size / 16.0f;
        pose.scale(scale, scale);
        graphics.renderItem(stack, 0, 0);
        pose.popMatrix();
    }

    /**
     * Splits the native components into the item name and the remaining lore. Offsets are
     * relative to the padded content origin; a lore {@code y} is absolute inside that origin.
     */
    private static List<LineLayout> layout(UiDocument document,
            List<ClientTooltipComponent> lines, Font fallback) {
        Map<?, ?> name = section(document, "name");
        Map<?, ?> lore = section(document, "lore");
        Font nameFont = sectionFont(document, name, fallback);
        Font loreFont = sectionFont(document, lore, fallback);

        List<LineLayout> result = new ArrayList<>(lines.size());
        ClientTooltipComponent first = lines.get(0);
        int nameX = offset(name, "x");
        int nameY = offset(name, "y");
        int nameHeight = first.getHeight(nameFont);
        result.add(new LineLayout(first, nameFont, nameX, nameY,
                first.getWidth(nameFont), nameHeight));

        int loreX = offset(lore, "x");
        int loreY = lore.containsKey("y") ? offset(lore, "y") : nameY + nameHeight;
        int gap = Math.max(0, Math.min(16, intOption(lore, "line_gap", 0)));
        for (int index = 1; index < lines.size(); index++) {
            ClientTooltipComponent component = lines.get(index);
            int height = component.getHeight(loreFont);
            result.add(new LineLayout(component, loreFont, loreX, loreY,
                    component.getWidth(loreFont), height));
            loreY += height + gap;
        }
        return result;
    }

    private static Map<?, ?> section(UiDocument document, String key) {
        Object value = option(document, key);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static int offset(Map<?, ?> section, String key) {
        return Math.max(0, Math.min(4096, intOption(section, key, 0)));
    }

    private static int intOption(Map<?, ?> section, String key, int fallback) {
        Object value = section.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /**
     * Resolves {@code name.font}/{@code lore.font}. A document font entry with
     * {@code source: native} points at a Minecraft font id; a bare value is that id.
     * NanoVG TTF fonts cannot be used by {@link ClientTooltipComponent}, so they fall back.
     */
    private static Font sectionFont(UiDocument document, Map<?, ?> section, Font fallback) {
        Object value = section.get("font");
        if (!(value instanceof String raw) || raw.isBlank()) {
            return fallback;
        }
        FontSpec spec = document.fonts().get(raw);
        String id = raw;
        if (spec != null) {
            if (!"native".equals(spec.source())) {
                warnOnce(raw, "[CC-TOOLTIP-003] tooltip font must be a native font id: {}",
                        raw);
                return fallback;
            }
            id = spec.id();
        }
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            warnOnce(id, "[CC-TOOLTIP-004] invalid tooltip font id: {}", id);
            return fallback;
        }
        Minecraft minecraft = Minecraft.getInstance();
        FontManager manager = ((MinecraftAccessor) (Object) minecraft).cloudcore$fontManager();
        FontSet set = ((FontManagerAccessor) (Object) manager).cloudcore$fontSets().get(identifier);
        if (set == null) {
            warnOnce(id, "[CC-TOOLTIP-005] tooltip font not loaded: {}", id);
            return fallback;
        }
        return new Font(new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription description) {
                return set.source(false);
            }

            @Override
            public EffectGlyph effect() {
                return set.whiteGlyph();
            }
        });
    }

    private static void warnOnce(String key, String message, Object value) {
        if (WARNED_FONTS.add(key)) {
            LOGGER.warn(message, value);
        }
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
