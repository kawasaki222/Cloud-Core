package com.cloudcore.client.render;

import com.cloudcore.api.document.FontSpec;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a document into backend-independent draw commands.
 *
 * <p>Geometry is absolute: element positions are relative to their parent, and
 * {@code fill}/{@code %}/pixel sizes resolve against the parent box, matching
 * {@link ElementHitTester} and {@code LayoutBoxes}. Every element the DSL documents is
 * rendered here — panels/buttons (colour, texture or nine-slice background), labels and rich
 * text, text fields (background, borders), checkboxes, sliders, progress bars, images (with
 * animation options), item icons, icon buttons, scroll containers with clipping, and the
 * row/column/grid layouts.
 */
public final class DocumentRenderListBuilder {
    private static final int DEFAULT_PANEL = 0x66000000;
    private static final int DEFAULT_BUTTON = 0xAA4A4A4A;
    private static final int DEFAULT_BUTTON_HOVER = 0xCC6E6E6E;
    /** Pressed reads as "pushed in": darker than the resting state, not brighter. */
    private static final int DEFAULT_BUTTON_PRESSED = 0xCC2E2E2E;
    private static final int DEFAULT_FIELD = 0xAA202020;
    private static final int DEFAULT_INVALID_BORDER = 0xFFFF5555;
    private static final int DEFAULT_TEXT = 0xFFFFFFFF;
    private static final int MAX_COMMANDS = 20_000;
    private static final int MAX_DEPTH = 64;
    /** A bar is a readable gauge up to a few dozen notches; beyond that it is just noise. */
    private static final int MAX_SEGMENTS = 64;
    private static final int MAX_SEGMENT_GAP = 16;
    /** Corner radius is capped so a small bar cannot turn into a circle. */
    private static final int MAX_RADIUS_RATIO = 2;

    private DocumentRenderListBuilder() {
    }

    public static RenderList build(UiDocument document, int screenWidth, int screenHeight) {
        return build(document, screenWidth, screenHeight, id -> 0);
    }

    /** Same as {@link #build} but with an explicit document origin (HUD anchors). */
    public static RenderList buildAt(UiDocument document, int screenWidth, int screenHeight,
            int originX, int originY) {
        return buildAt(document, screenWidth, screenHeight, originX, originY, id -> 0, null);
    }

    public static RenderList buildAt(UiDocument document, int screenWidth, int screenHeight,
            int originX, int originY,
            java.util.function.ToIntFunction<String> localScroll) {
        return buildAt(document, screenWidth, screenHeight, originX, originY, localScroll, null);
    }

    /**
     * @param hoveredId element that should use its {@code style.hover} overrides, or null
     */
    public static RenderList buildAt(UiDocument document, int screenWidth, int screenHeight,
            int originX, int originY,
            java.util.function.ToIntFunction<String> localScroll, String hoveredId) {
        return buildAt(document, screenWidth, screenHeight, originX, originY, localScroll,
                hoveredId, null);
    }

    /**
     * @param pressedId element held down by the mouse, or null; it wins over {@code hoveredId}
     *                  because a pressed button is also hovered and the press is the newer
     *                  information
     */
    public static RenderList buildAt(UiDocument document, int screenWidth, int screenHeight,
            int originX, int originY,
            java.util.function.ToIntFunction<String> localScroll, String hoveredId,
            String pressedId) {
        return buildWithInteraction(document, screenWidth, screenHeight, originX, originY,
                localScroll, new Interaction(hoveredId, pressedId));
    }

    /**
     * Shared entry for the two public {@code buildAt} forms. It cannot be an overload of them:
     * every call that passes a literal {@code null} would match both and stop compiling.
     */
    static RenderList buildWithInteraction(UiDocument document, int screenWidth, int screenHeight,
            int originX, int originY,
            java.util.function.ToIntFunction<String> localScroll, Interaction interaction) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(localScroll, "localScroll");
        Objects.requireNonNull(interaction, "interaction");
        if (screenWidth <= 0 || screenHeight <= 0) {
            return RenderList.EMPTY;
        }
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        List<RenderCommand> commands = new ArrayList<>();
        Map<String, FontSpec> fonts = document.fonts();
        for (UiElement element : document.elements()) {
            append(element, originX, originY, documentWidth, documentHeight,
                    commands, 1, fonts, localScroll, interaction);
        }
        return commands.isEmpty() ? RenderList.EMPTY : new RenderList(commands);
    }

    /** Which element is under the cursor and which one is held down; both may be null. */
    record Interaction(String hoveredId, String pressedId) {
        static final Interaction NONE = new Interaction(null, null);

        Interaction {
            // An id can only be pressed while it is also the hovered one: dragging off a
            // button has to release the press, or it would stay dark under the cursor.
            if (pressedId != null && !pressedId.equals(hoveredId)) {
                pressedId = null;
            }
        }
    }

    /**
     * @param localScroll extra offset per scroll element id, added to the document's
     *                    {@code scroll_y}; the client uses it for wheel input
     */
    public static RenderList build(UiDocument document, int screenWidth, int screenHeight,
            java.util.function.ToIntFunction<String> localScroll) {
        return buildAt(document, screenWidth, screenHeight,
                (screenWidth - Math.max(1, Math.round(document.width() * document.scale()))) / 2,
                (screenHeight - Math.max(1, Math.round(document.height() * document.scale()))) / 2,
                localScroll, null);
    }

    private static void append(UiElement element, int parentX, int parentY,
            int parentWidth, int parentHeight, List<RenderCommand> commands, int depth,
            Map<String, FontSpec> fonts,
            java.util.function.ToIntFunction<String> localScroll, Interaction interaction) {
        if (!element.visible() || depth > MAX_DEPTH || commands.size() >= MAX_COMMANDS) {
            return;
        }
        int x = parentX + element.x();
        int y = parentY + element.y();
        int width = resolve(element.width(), parentWidth, 0);
        int height = resolve(element.height(), parentHeight, 0);
        int childX = x;
        int childY = y;
        int childWidth = width > 0 ? width : parentWidth;
        int childHeight = height > 0 ? height : parentHeight;
        boolean clipChildren = false;

        switch (element.type()) {
            case "panel" -> {
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : parentHeight;
                boolean hovered = element.id().equals(interaction.hoveredId());
                Object background = styled(element, "background", hovered, false);
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    // A texture background may stretch or use a nine-slice border.
                    addImage(commands, reference, x, y, w, h,
                            intArray(styled(element, "nine_slice", hovered, false)));
                } else {
                    addFill(commands, x, y, w, h, color(background, DEFAULT_PANEL));
                }
                childWidth = w;
                childHeight = h;
            }
            case "button" -> {
                int w = width > 0 ? width : Math.max(40, text(element).length() * 6 + 12);
                int h = height > 0 ? height : 20;
                boolean hovered = element.enabled() && element.id().equals(interaction.hoveredId());
                boolean pressed = hovered && element.id().equals(interaction.pressedId());
                Object background = styled(element, "background", hovered, pressed);
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    addImage(commands, reference, x, y, w, h,
                            intArray(styled(element, "nine_slice", hovered, pressed)));
                } else {
                    addFill(commands, x, y, w, h, dim(element, color(background,
                            pressed ? DEFAULT_BUTTON_PRESSED
                                    : hovered ? DEFAULT_BUTTON_HOVER : DEFAULT_BUTTON)));
                }
                int textY = y + Math.max(2, (h - 8) / 2);
                // A 1px downward nudge is what sells the press with no other styling available.
                if (pressed) {
                    textY += 1;
                }
                int textX = x + 6;
                if (attribute(element, "icon") != null) {
                    addIcon(commands, element, x + 3,
                            y + Math.max(1, (h - 14) / 2) + (pressed ? 1 : 0), 14);
                    textX = x + 21;
                }
                addText(commands, text(element), textX, textY,
                        dim(element, color(styleText(element), DEFAULT_TEXT)),
                        fontPath(element, fonts), fontSize(element));
                childWidth = w;
                childHeight = h;
            }
            case "icon_button" -> {
                int w = width > 0 ? width : 20;
                int h = height > 0 ? height : 20;
                boolean hovered = element.enabled() && element.id().equals(interaction.hoveredId());
                boolean pressed = hovered && element.id().equals(interaction.pressedId());
                Object background = styled(element, "background", hovered, pressed);
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    addImage(commands, reference, x, y, w, h,
                            intArray(styled(element, "nine_slice", hovered, pressed)));
                } else {
                    addFill(commands, x, y, w, h, dim(element, color(background,
                            pressed ? DEFAULT_BUTTON_PRESSED
                                    : hovered ? DEFAULT_BUTTON_HOVER : DEFAULT_BUTTON)));
                }
                addIcon(commands, element, x + 2, y + 2 + (pressed ? 1 : 0),
                        Math.max(4, Math.min(w, h) - 4));
                childWidth = w;
                childHeight = h;
            }
            case "label" -> {
                java.util.List<RenderCommand.RichText.Run> runs = runs(element);
                if (runs != null) {
                    addRichText(commands, runs, x, y, fontPath(element, fonts),
                            fontSize(element));
                } else {
                    addText(commands, text(element), x, y,
                            color(attribute(element, "color"), DEFAULT_TEXT),
                            fontPath(element, fonts), fontSize(element));
                }
            }
            case "text_field" -> {
                // Only the background and border: the vanilla EditBox widget (added by
                // CloudScreen) draws the hint, value, selection and caret; the screen adds the
                // focused border on top.
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : 18;
                Object background = style(element, "background");
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    addImage(commands, reference, x, y, w, h,
                            intArray(style(element, "nine_slice")));
                } else {
                    addFill(commands, x, y, w, h, dim(element, color(background, DEFAULT_FIELD)));
                }
                addFrame(commands, x, y, w, h, truthy(attribute(element, "invalid"))
                        ? color(styleBorder(element, "invalid"), DEFAULT_INVALID_BORDER)
                        : color(styleBorder(element, "normal"), 0));
                Object clearButton = style(element, "clear_button");
                if (clearButton instanceof String reference && !reference.isBlank()) {
                    TextFieldChrome.ClearBar bar = TextFieldChrome.clearBar(x, y, w, h);
                    if (isSupportedTexture(reference)) {
                        addImage(commands, reference, bar.x(), bar.y(), bar.size(), bar.size());
                    } else {
                        // An unusable reference still leaves a clickable affordance; the click
                        // itself is handled by CloudScreen, not by this list.
                        addText(commands, "x", bar.x() + Math.max(1, (bar.size() - 5) / 2),
                                bar.y() + Math.max(1, (bar.size() - 8) / 2),
                                color(styleText(element), DEFAULT_TEXT));
                    }
                }
                childWidth = w;
                childHeight = h;
            }
            case "checkbox" -> {
                boolean checked = truthy(attribute(element, "checked"));
                addFill(commands, x, y, 9, 9, checked ? 0xFF4FA3E8 : 0xAA303030);
                addFill(commands, x + 1, y + 1, 7, 7, checked ? 0xFF1B3F63 : 0xFF1A1A1A);
                if (checked) {
                    addFill(commands, x + 2, y + 2, 5, 5, 0xFFFFFFFF);
                }
                addText(commands, text(element), x + 13, y,
                        color(styleText(element), DEFAULT_TEXT), fontPath(element, fonts),
                        fontSize(element));
            }
            case "slider" -> {
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : 8;
                addFill(commands, x, y, w, h, 0xFF202020);
                double min = number(attribute(element, "min"), 0.0);
                double max = Sliders.maxOrDefault(min, number(attribute(element, "max"), 1.0));
                double value = Sliders.fraction(number(attribute(element, "value"), min),
                        min, max);
                int filled = (int) Math.round(w * value);
                if (filled > 0) {
                    addFill(commands, x, y, filled, h, 0xFF4FA3E8);
                }
                int knob = Math.max(4, Math.min(8, h + 2));
                int knobX = Math.max(x, Math.min(x + w - knob,
                        x + filled - knob / 2));
                addFill(commands, knobX, y - 1, knob, h + 2, 0xFFE8E8E8);
                childWidth = w;
                childHeight = h;
            }
            case "progress" -> {
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : 8;
                int radius = radius(element, w, h);
                Object background = attribute(element, "texture_bg");
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    // nine_slice keeps a framed track's border and caps from stretching.
                    addImage(commands, reference, x, y, w, h,
                            intArray(attribute(element, "nine_slice")));
                } else {
                    addRounded(commands, x, y, w, h,
                            color(style(element, "background"), 0xAA202020), radius);
                }
                double value = Math.max(0.0, Math.min(1.0,
                        number(attribute(element, "value"), 0.0)));
                Object direction = attribute(element, "direction");
                String dir = direction instanceof String text ? text : null;
                Object fillTexture = attribute(element, "texture_fill");
                boolean textured = fillTexture instanceof String reference
                        && isSupportedTexture(reference);
                int fillColor = color(style(element, "fill_color"), 0xFF3C8CE7);
                // A segmented bar is a row of cells: each fills independently, so a 10-cell bar
                // reads as discrete notches instead of one continuous edge.
                int segments = (int) Math.max(1, Math.min(MAX_SEGMENTS,
                        number(attribute(element, "segments"), 1.0)));
                int gap = segments > 1
                        ? (int) Math.max(0, Math.min(MAX_SEGMENT_GAP,
                                number(attribute(element, "segment_gap"), 1.0)))
                        : 0;
                for (int index = 0; index < segments; index++) {
                    int cellX = x + (int) Math.round((double) w * index / segments);
                    int cellRight = x + (int) Math.round((double) w * (index + 1) / segments);
                    int cellW = cellRight - cellX - (index < segments - 1 ? gap : 0);
                    if (cellW <= 0) {
                        continue;
                    }
                    double segmentValue = segments == 1 ? value
                            : Math.max(0.0, Math.min(1.0, value * segments - index));
                    // The fill keeps its full size and is clipped, so texture_fill is never
                    // squashed into the part that is currently showing.
                    int[] area = progressFill(cellX, y, cellW, h, segmentValue, dir);
                    if (area[2] <= 0 || area[3] <= 0) {
                        continue;
                    }
                    if (textured) {
                        commands.add(new RenderCommand.ClipPush(area[0], area[1], area[2],
                                area[3]));
                        addImage(commands, (String) fillTexture, cellX, y, cellW, h);
                        commands.add(new RenderCommand.ClipPop());
                    } else {
                        addRounded(commands, area[0], area[1], area[2], area[3], fillColor,
                                radius);
                    }
                }
                childWidth = w;
                childHeight = h;
            }
            case "image" -> {
                addImageLayers(commands, element, x, y,
                        width > 0 ? width : 64, height > 0 ? height : 64);
            }
            case "item_icon" -> {
                Object descriptor = attribute(element, "item");
                String itemId = descriptor instanceof Map<?, ?> map && map.get("id") instanceof String id
                        ? id
                        : attribute(element, "item") instanceof String id ? id : null;
                if (itemId != null) {
                    int count = 1;
                    if (descriptor instanceof Map<?, ?> map && map.get("count") instanceof Number value) {
                        count = Math.max(1, value.intValue());
                    }
                    boolean showCount = attribute(element, "show_count") instanceof Boolean show && show;
                    float scale = (float) number(attribute(element, "scale"), 1.0);
                    addItem(commands, itemId, count, showCount, x, y, scale);
                }
            }
            case "scroll" -> {
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : parentHeight;
                addFill(commands, x, y, w, h, color(style(element, "background"), 0x00000000));
                childWidth = w;
                childHeight = h;
                clipChildren = true;
                int scrollY = (int) Math.round(number(attribute(element, "scroll_y"), 0.0))
                        + localScroll.applyAsInt(element.id());
                childY = y - scrollY;
                childX = x - (int) Math.round(number(attribute(element, "scroll_x"), 0.0));
            }
            case "inventory_grid", "spacer" -> {
                // Slots are drawn by the owning screen; the grid only publishes its geometry.
            }
            default -> {
                // row/column/grid/scroll/overlay and future containers: recurse with the
                // parent box until their layout algorithms land.
            }
        }

        if (clipChildren && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.ClipPush(x, y, childWidth, childHeight));
        }
        if ("row".equals(element.type()) || "column".equals(element.type())
                || "grid".equals(element.type())) {
            layoutChildren(element, childX, childY, childWidth, childHeight, commands, depth,
                    fonts, localScroll, interaction);
        } else {
            for (UiElement child : element.children()) {
                append(child, childX, childY, childWidth, childHeight, commands, depth + 1,
                        fonts, localScroll, interaction);
            }
        }
        if (clipChildren && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.ClipPop());
        }
    }

    /**
     * Row/column/grid place children by their own sizes; {@link ContainerCursor} owns the
     * rules so hit testing and focus boxes use the same positions.
     */
    private static void layoutChildren(UiElement element, int x, int y, int width, int height,
            List<RenderCommand> commands, int depth, Map<String, FontSpec> fonts,
            java.util.function.ToIntFunction<String> localScroll, Interaction interaction) {
        ContainerCursor cursor = new ContainerCursor(element, x, y, width, height);
        for (UiElement child : element.children()) {
            int[] offset = cursor.next(child);
            append(child, offset[0], offset[1], width, height, commands, depth + 1, fonts,
                    localScroll, interaction);
        }
    }

    private static void addFill(List<RenderCommand> commands, int x, int y, int width, int height,
            int argb) {
        if (width > 0 && height > 0 && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.FillRect(x, y, width, height, argb));
        }
    }

    /**
     * Radius in pixels from {@code radius}, clamped so it never eats more than half the box.
     * A percentage is resolved against the shorter side, which keeps a bar's caps circular.
     */
    private static int radius(UiElement element, int width, int height) {
        Object value = attribute(element, "radius");
        if (value == null) {
            return 0;
        }
        double raw;
        if (value instanceof String text && text.trim().endsWith("%")) {
            raw = number(text.trim().substring(0, text.trim().length() - 1), 0.0)
                    * Math.min(width, height) / 100.0;
        } else {
            raw = number(value, 0.0);
        }
        int limit = Math.min(width, height) / MAX_RADIUS_RATIO;
        return (int) Math.max(0, Math.min(limit, Math.round(raw)));
    }

    /**
     * A rectangle with rounded corners, approximated row by row: the backends only know how to
     * fill rects, and a per-row inset is indistinguishable from a true arc at these sizes.
     */
    private static void addRounded(List<RenderCommand> commands, int x, int y, int width,
            int height, int argb, int radius) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (radius <= 0 || height <= 2 * radius) {
            addFill(commands, x, y, width, height, argb);
            return;
        }
        for (int row = 0; row < height; row++) {
            // Distance from the nearest cap centre, 0 in the straight middle section.
            int inset = 0;
            if (row < radius) {
                inset = capInset(radius, radius - row);
            } else if (row >= height - radius) {
                inset = capInset(radius, row - (height - radius) + 1);
            }
            addFill(commands, x + inset, y + row, width - 2 * inset, 1, argb);
        }
    }

    /**
     * Horizontal inset of a rounded cap at vertical distance {@code dy} from its arc centre:
     * 0 at the centre (the straight section), {@code radius} at the outer edge.
     */
    private static int capInset(int radius, int dy) {
        if (dy <= 0) {
            return 0;
        }
        if (dy >= radius) {
            return radius;
        }
        double covered = Math.sqrt((double) radius * radius - (double) dy * dy);
        return (int) Math.round(radius - covered);
    }

    /**
     * Rect of the filled part of a progress bar ({@code x, y, width, height}).
     * {@code direction} is {@code left} (default), {@code right}, {@code up} or {@code down}.
     */
    private static int[] progressFill(int x, int y, int width, int height, double value,
            String direction) {
        int fillWidth = (int) Math.round(width * value);
        int fillHeight = (int) Math.round(height * value);
        String normalised = direction == null ? "left"
                : direction.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalised) {
            case "right" -> new int[]{x + width - fillWidth, y, fillWidth, height};
            case "up" -> new int[]{x, y + height - fillHeight, width, fillHeight};
            case "down" -> new int[]{x, y, width, fillHeight};
            default -> new int[]{x, y, fillWidth, height};
        };
    }

    /** A 1px outline; skipped entirely when the colour is fully transparent. */
    private static void addFrame(List<RenderCommand> commands, int x, int y, int width, int height,
            int argb) {
        if ((argb >>> 24) == 0 || width <= 0 || height <= 0) {
            return;
        }
        addFill(commands, x, y, width, 1, argb);
        addFill(commands, x, y + height - 1, width, 1, argb);
        addFill(commands, x, y + 1, 1, height - 2, argb);
        addFill(commands, x + width - 1, y + 1, 1, height - 2, argb);
    }

    /**
     * Draws a button icon: a texture reference ({@code local:}/{@code pack:}/{@code builtin:}/
     * {@code inline:}), or {@code {item: "minecraft:diamond", count: 3}} for an item icon.
     */
    private static void addIcon(List<RenderCommand> commands, UiElement element, int x, int y,
            int size) {
        Object icon = attribute(element, "icon");
        if (icon instanceof String reference) {
            if (isSupportedTexture(reference)) {
                addImage(commands, reference, x, y, size, size);
            }
            return;
        }
        if (!(icon instanceof Map<?, ?> map)) {
            return;
        }
        Object item = map.get("item");
        String itemId = item instanceof String id ? id
                : item instanceof Map<?, ?> descriptor && descriptor.get("id") instanceof String id
                ? id : null;
        if (itemId != null) {
            int count = (int) Math.round(number(map.get("count"), 1.0));
            addItem(commands, itemId, count, false, x, y, size / 16.0f);
        }
    }

    private static void addText(List<RenderCommand> commands, String text, int x, int y,
            int argb) {
        addText(commands, text, x, y, argb, null);
    }

    private static void addText(List<RenderCommand> commands, String text, int x, int y,
            int argb, String font) {
        addText(commands, text, x, y, argb, font, RenderCommand.Text.DEFAULT_SIZE);
    }

    private static void addText(List<RenderCommand> commands, String text, int x, int y,
            int argb, String font, float size) {
        if (!text.isEmpty() && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.Text(text, x, y, argb, true, font, size));
        }
    }

    /**
     * Reads the em size for a label. {@code font_size} is accepted both as an attribute and
     * under {@code style}; absent values keep the backend default so existing documents are
     * unchanged.
     */
    private static float fontSize(UiElement element) {
        Object value = attribute(element, "font_size");
        if (value == null) {
            value = style(element, "font_size");
        }
        double size = number(value, RenderCommand.Text.DEFAULT_SIZE);
        return size > 0.0 ? (float) size : RenderCommand.Text.DEFAULT_SIZE;
    }

    /**
     * Resolves an element's font token to a CloudCore-relative file path. Documents can name
     * a {@code fonts} entry or write the path directly; unknown keys fall back to the
     * backend default.
     */
    private static String fontPath(UiElement element, Map<String, FontSpec> fonts) {
        String name = null;
        Object value = attribute(element, "font");
        if (value instanceof String candidate && !candidate.isBlank()) {
            name = candidate;
        } else {
            Object styleValue = style(element, "font");
            if (styleValue instanceof String styleName && !styleName.isBlank()) {
                name = styleName;
            }
        }
        if (name == null) {
            return null;
        }
        FontSpec spec = fonts.get(name);
        if (spec == null) {
            return name;
        }
        if ("local".equals(spec.source())) {
            return spec.id();
        }
        return "resource:" + spec.id();
    }

    /**
     * Expands an image's base texture and its {@code layers} list into ordered draw commands.
     * Layers are back-to-front: the base first, then each declared layer in array order. A
     * layer inherits the base geometry unless it declares its own {@code x/y/width/height};
     * its {@code tint} is a multiplicative ARGB colour, so {@code #80FFFFFF} fades it and
     * {@code #FFFFC040} warms it. This is intentionally independent from any UI animation:
     * every layer may carry its own {@code animated/loop/speed} options.
     */
    private static void addImageLayers(List<RenderCommand> commands, UiElement element,
            int x, int y, int width, int height) {
        Object base = attribute(element, "texture");
        if (base instanceof String reference && isSupportedTexture(reference)) {
            addImage(commands, reference, x, y, width, height,
                    intArray(attribute(element, "nine_slice")), animation(element),
                    Colors.parse(attribute(element, "tint"), RenderCommand.Image.DEFAULT_TINT));
        }
        Object layers = attribute(element, "layers");
        if (!(layers instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> layer)) {
                continue;
            }
            Object texture = layer.get("texture");
            if (!(texture instanceof String reference) || !isSupportedTexture(reference)) {
                continue;
            }
            int layerX = x + (int) Math.round(number(layer.get("x"), 0.0));
            int layerY = y + (int) Math.round(number(layer.get("y"), 0.0));
            int layerWidth = layer.containsKey("width")
                    ? (int) Math.round(number(layer.get("width"), width)) : width;
            int layerHeight = layer.containsKey("height")
                    ? (int) Math.round(number(layer.get("height"), height)) : height;
            addImage(commands, reference, layerX, layerY, layerWidth, layerHeight,
                    intArray(layer.get("nine_slice")), animation(layer),
                    Colors.parse(layer.get("tint"), RenderCommand.Image.DEFAULT_TINT));
        }
    }

    private static void addImage(List<RenderCommand> commands, String texture, int x, int y,
            int width, int height) {
        addImage(commands, texture, x, y, width, height, null);
    }

    private static void addImage(List<RenderCommand> commands, String texture, int x, int y,
            int width, int height, int[] nineSlice) {
        addImage(commands, texture, x, y, width, height, nineSlice,
                RenderCommand.Image.Animation.DEFAULT);
    }

    private static void addImage(List<RenderCommand> commands, String texture, int x, int y,
            int width, int height, int[] nineSlice,
            RenderCommand.Image.Animation animation) {
        addImage(commands, texture, x, y, width, height, nineSlice, animation,
                RenderCommand.Image.DEFAULT_TINT);
    }

    private static void addImage(List<RenderCommand> commands, String texture, int x, int y,
            int width, int height, int[] nineSlice,
            RenderCommand.Image.Animation animation, int tint) {
        if (width > 0 && height > 0 && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.Image(texture, x, y, width, height, nineSlice,
                    animation, tint));
        }
    }

    /** Reads {@code animated: auto|off}, {@code loop} and {@code speed} from the element. */
    private static RenderCommand.Image.Animation animation(UiElement element) {
        Object animated = attribute(element, "animated");
        boolean enabled = !(animated instanceof String text && text.equalsIgnoreCase("off"));
        boolean loop = !Boolean.FALSE.equals(attribute(element, "loop"));
        float speed = (float) number(attribute(element, "speed"), 1.0);
        return new RenderCommand.Image.Animation(enabled, loop, speed);
    }

    /** Same animation options for a layer map; absent keys keep the normal defaults. */
    private static RenderCommand.Image.Animation animation(Map<?, ?> layer) {
        Object animated = layer.get("animated");
        boolean enabled = !(animated instanceof String text && text.equalsIgnoreCase("off"));
        boolean loop = !Boolean.FALSE.equals(layer.get("loop"));
        float speed = (float) number(layer.get("speed"), 1.0);
        return new RenderCommand.Image.Animation(enabled, loop, speed);
    }

    /** Accepts both real booleans and the strings templates produce. */
    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    /** Reads a {@code [a, b, c, d]} attribute array; anything else becomes {@code null}. */
    private static int[] intArray(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 4) {
            return null;
        }
        int[] result = new int[4];
        for (int index = 0; index < 4; index++) {
            result[index] = (int) Math.round(number(list.get(index), 0.0));
        }
        return result;
    }

    private static void addItem(List<RenderCommand> commands, String itemId, int count,
            boolean showCount, int x, int y, float scale) {
        if (commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.ItemIcon(itemId, count, showCount, x, y,
                    scale > 0.0f ? scale : 1.0f));
        }
    }

    private static boolean isSupportedTexture(String reference) {
        return reference.startsWith("local:")
                || reference.startsWith("pack:")
                || reference.startsWith("builtin:")
                || reference.startsWith("inline:");
    }

    /**
     * Parses the {@code text: {runs: [...]}} form. Returns {@code null} when the element uses
     * plain text, so the caller keeps the single-colour path.
     */
    private static java.util.List<RenderCommand.RichText.Run> runs(UiElement element) {
        Object value = attribute(element, "text");
        if (!(value instanceof Map<?, ?> map) || !(map.get("runs") instanceof List<?> list)) {
            return null;
        }
        java.util.List<RenderCommand.RichText.Run> runs = new java.util.ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> run) || !(run.get("text") instanceof String text)) {
                continue;
            }
            int argb = color(run.get("color"), DEFAULT_TEXT);
            runs.add(new RenderCommand.RichText.Run(text, argb));
        }
        return runs.isEmpty() ? null : runs;
    }

    private static void addRichText(List<RenderCommand> commands,
            java.util.List<RenderCommand.RichText.Run> runs, int x, int y, String font,
            float size) {
        if (commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.RichText(runs, x, y, true, font, size));
        }
    }

    private static String text(UiElement element) {
        return textOf(attribute(element, "text"));
    }

    private static String textOf(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Map<?, ?> map && map.get("runs") instanceof List<?> runs) {
            StringBuilder builder = new StringBuilder();
            for (Object run : runs) {
                if (run instanceof Map<?, ?> runMap && runMap.get("text") instanceof String part) {
                    builder.append(part);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static Object attribute(UiElement element, String key) {
        return element.attributes().get(key);
    }

    /**
     * Style lookup that prefers {@code style.pressed.<key>}, then {@code style.hover.<key>},
     * so a button can declare all three states in the document. Pressed is checked first
     * because a held button is hovered too and the press is the more specific state.
     */
    private static Object styled(UiElement element, String key, boolean hovered,
            boolean pressed) {
        Object style = element.attributes().get("style");
        if (style instanceof Map<?, ?> map) {
            if (pressed && map.get("pressed") instanceof Map<?, ?> states
                    && states.containsKey(key)) {
                return states.get(key);
            }
            if (hovered && map.get("hover") instanceof Map<?, ?> states
                    && states.containsKey(key)) {
                return states.get(key);
            }
            return map.get(key);
        }
        return null;
    }

    private static Object style(UiElement element, String key) {
        Object style = element.attributes().get("style");
        if (style instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private static Object styleText(UiElement element) {
        Object text = style(element, "text");
        if (text instanceof Map<?, ?> map) {
            return map.get("color");
        }
        return null;
    }

    /** Reads {@code style.border.<key>} ({@code normal}/{@code focused}/{@code invalid}). */
    private static Object styleBorder(UiElement element, String key) {
        Object border = style(element, "border");
        return border instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static int color(Object value, int fallback) {
        return Colors.parse(value, fallback);
    }

    /** Halves the alpha of a colour for a disabled element; textures keep their look. */
    private static int dim(UiElement element, int argb) {
        if (element.enabled()) {
            return argb;
        }
        int alpha = (argb >>> 24) & 0xFF;
        return ((alpha / 2) << 24) | (argb & 0xFFFFFF);
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // Older documents and quoted YAML numbers arrive as text; parse rather than drop.
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int resolve(String value, int parent, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.equalsIgnoreCase("fill")) {
            return parent;
        }
        try {
            if (value.endsWith("%")) {
                double percent = Double.parseDouble(value.substring(0, value.length() - 1));
                return (int) Math.round(parent * percent / 100.0);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
