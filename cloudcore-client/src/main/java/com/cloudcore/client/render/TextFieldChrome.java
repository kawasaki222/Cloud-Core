package com.cloudcore.client.render;

import com.cloudcore.api.document.UiElement;
import java.util.Map;

/**
 * Geometry and style parsing shared by the render list and the {@code EditBox} widgets.
 *
 * <p>The clear button sits in the element's right padding. {@code CloudScreen} narrows the
 * widget by the same amount, so typed text never runs underneath it.
 */
public final class TextFieldChrome {

    /** Box of the clear button inside an element, in element coordinates. */
    public record ClearBar(int x, int y, int size) {
    }

    private TextFieldChrome() {
    }

    /**
     * @return {@code true} when the page declares a texture reference for the clear button
     */
    public static boolean hasClearButton(UiElement element) {
        return styleValue(element, "clear_button") instanceof String text && !text.isBlank();
    }

    /** @return {@code style.cursor_color}, or {@code null} when unset */
    public static Integer cursorColor(UiElement element) {
        return optionalColor(styleValue(element, "cursor_color"));
    }

    /** @return {@code style.selection_color}, or {@code null} when unset */
    public static Integer selectionColor(UiElement element) {
        return optionalColor(styleValue(element, "selection_color"));
    }

    public static ClearBar clearBar(int x, int y, int width, int height) {
        int size = Math.max(8, Math.min(14, height - 4));
        return new ClearBar(x + width - size - 4, y + (height - size) / 2, size);
    }

    /**
     * Parses {@code #AARRGGBB} / {@code #RRGGBB} and returns {@code null} for anything else,
     * so an unset or malformed style key keeps the vanilla colour instead of a fallback that
     * may collide with a real ARGB value ({@code #FFFFFFFF} is {@code -1}).
     */
    public static Integer optionalColor(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        String hex = string.charAt(0) == '#' ? string.substring(1) : string;
        try {
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
        } catch (NumberFormatException ignored) {
            // A theme token or a named colour: not resolvable here, let vanilla decide.
        }
        return null;
    }

    /** Reads a style-level key ({@code style.<key>}). */
    static Object styleValue(UiElement element, String key) {
        Object style = element.attributes().get("style");
        return style instanceof Map<?, ?> map ? map.get(key) : null;
    }
}
