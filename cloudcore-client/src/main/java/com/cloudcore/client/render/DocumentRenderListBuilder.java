package com.cloudcore.client.render;

import com.cloudcore.api.document.FontSpec;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts the currently supported subset of a document into backend-independent
 * instructions. Layout containers with explicit coordinates and simple content elements
 * are supported; images, item icons, clipping and text shaping remain pending.
 */
public final class DocumentRenderListBuilder {
    private static final int DEFAULT_PANEL = 0x66000000;
    private static final int DEFAULT_BUTTON = 0xAA4A4A4A;
    private static final int DEFAULT_BUTTON_HOVER = 0xCC6E6E6E;
    private static final int DEFAULT_FIELD = 0xAA202020;
    private static final int DEFAULT_TEXT = 0xFFFFFFFF;
    private static final int MAX_COMMANDS = 20_000;
    private static final int MAX_DEPTH = 64;

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
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(localScroll, "localScroll");
        if (screenWidth <= 0 || screenHeight <= 0) {
            return RenderList.EMPTY;
        }
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        List<RenderCommand> commands = new ArrayList<>();
        Map<String, FontSpec> fonts = document.fonts();
        for (UiElement element : document.elements()) {
            append(element, originX, originY, documentWidth, documentHeight,
                    commands, 1, fonts, localScroll, hoveredId);
        }
        return commands.isEmpty() ? RenderList.EMPTY : new RenderList(commands);
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
            java.util.function.ToIntFunction<String> localScroll, String hoveredId) {
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
                boolean hovered = element.id().equals(hoveredId);
                Object background = styled(element, "background", hovered);
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    // A texture background may stretch or use a nine-slice border.
                    addImage(commands, reference, x, y, w, h,
                            intArray(styled(element, "nine_slice", hovered)));
                } else {
                    addFill(commands, x, y, w, h, color(background, DEFAULT_PANEL));
                }
                childWidth = w;
                childHeight = h;
            }
            case "button" -> {
                int w = width > 0 ? width : Math.max(40, text(element).length() * 6 + 12);
                int h = height > 0 ? height : 20;
                boolean hovered = element.id().equals(hoveredId);
                Object background = styled(element, "background", hovered);
                if (background instanceof String reference && isSupportedTexture(reference)) {
                    addImage(commands, reference, x, y, w, h,
                            intArray(styled(element, "nine_slice", hovered)));
                } else {
                    addFill(commands, x, y, w, h, color(background,
                            hovered ? DEFAULT_BUTTON_HOVER : DEFAULT_BUTTON));
                }
                addText(commands, text(element), x + 6, y + Math.max(2, (h - 8) / 2),
                        color(styleText(element), DEFAULT_TEXT), fontPath(element, fonts));
                childWidth = w;
                childHeight = h;
            }
            case "label" -> {
                java.util.List<RenderCommand.RichText.Run> runs = runs(element);
                if (runs != null) {
                    addRichText(commands, runs, x, y);
                } else {
                    addText(commands, text(element), x, y,
                            color(attribute(element, "color"), DEFAULT_TEXT),
                            fontPath(element, fonts));
                }
            }
            case "text_field" -> {
                // Only the background: the vanilla EditBox widget (added by CloudScreen)
                // draws the hint, value, selection and caret.
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : 18;
                addFill(commands, x, y, w, h, color(style(element, "background"), DEFAULT_FIELD));
                childWidth = w;
                childHeight = h;
            }
            case "checkbox" -> {
                addFill(commands, x, y, 9, 9, 0xAA303030);
                addText(commands, text(element), x + 13, y,
                        color(styleText(element), DEFAULT_TEXT), fontPath(element, fonts));
            }
            case "progress" -> {
                int w = width > 0 ? width : parentWidth;
                int h = height > 0 ? height : 8;
                addFill(commands, x, y, w, h, 0xAA202020);
                double value = number(attribute(element, "value"), 0.0);
                int fill = (int) Math.round(w * Math.max(0.0, Math.min(1.0, value)));
                if (fill > 0) {
                    addFill(commands, x, y, fill, h, 0xFF3C8CE7);
                }
            }
            case "image" -> {
                Object texture = attribute(element, "texture");
                if (texture instanceof String reference && isSupportedTexture(reference)) {
                    addImage(commands, reference, x, y,
                            width > 0 ? width : 64, height > 0 ? height : 64,
                            intArray(attribute(element, "nine_slice")),
                            animation(element));
                }
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
        for (UiElement child : element.children()) {
            append(child, childX, childY, childWidth, childHeight, commands, depth + 1,
                    fonts, localScroll, hoveredId);
        }
        if (clipChildren && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.ClipPop());
        }
    }

    private static void addFill(List<RenderCommand> commands, int x, int y, int width, int height,
            int argb) {
        if (width > 0 && height > 0 && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.FillRect(x, y, width, height, argb));
        }
    }

    private static void addText(List<RenderCommand> commands, String text, int x, int y,
            int argb) {
        addText(commands, text, x, y, argb, null);
    }

    private static void addText(List<RenderCommand> commands, String text, int x, int y,
            int argb, String font) {
        if (!text.isEmpty() && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.Text(text, x, y, argb, true, font));
        }
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
        if (width > 0 && height > 0 && commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.Image(texture, x, y, width, height, nineSlice,
                    animation));
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
            java.util.List<RenderCommand.RichText.Run> runs, int x, int y) {
        if (commands.size() < MAX_COMMANDS) {
            commands.add(new RenderCommand.RichText(runs, x, y, true));
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
     * Style lookup that prefers {@code style.hover.<key>} when the element is hovered, so a
     * button can declare both states in the document.
     */
    private static Object styled(UiElement element, String key, boolean hovered) {
        if (hovered) {
            Object style = element.attributes().get("style");
            if (style instanceof Map<?, ?> map && map.get("hover") instanceof Map<?, ?> hover
                    && hover.containsKey(key)) {
                return hover.get(key);
            }
        }
        return style(element, key);
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

    private static int color(Object value, int fallback) {
        if (value instanceof String string && string.startsWith("#")) {
            String hex = string.substring(1);
            try {
                if (hex.length() == 8) {
                    return Integer.parseUnsignedInt(hex, 16);
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
                }
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
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
