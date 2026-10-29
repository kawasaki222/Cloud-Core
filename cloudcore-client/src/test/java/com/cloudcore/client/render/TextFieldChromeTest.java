package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextFieldChromeTest {

    @Test
    void clearBarSitsInTheRightPadding() {
        TextFieldChrome.ClearBar bar = TextFieldChrome.clearBar(10, 10, 140, 20);

        assertEquals(14, bar.size());
        assertEquals(132, bar.x());
        assertEquals(13, bar.y());
        assertTrue(bar.x() + bar.size() <= 150, "the button stays inside the element");
    }

    @Test
    void clearBarShrinksWithAShortField() {
        TextFieldChrome.ClearBar bar = TextFieldChrome.clearBar(0, 0, 80, 12);

        assertEquals(8, bar.size());
        assertEquals(68, bar.x());
        assertEquals(2, bar.y());
    }

    @Test
    void clearButtonIsOptIn() {
        assertTrue(TextFieldChrome.hasClearButton(element(Map.of("clear_button",
                "local:ui/clear.png"))));
        assertFalse(TextFieldChrome.hasClearButton(element(Map.of())));
        assertFalse(TextFieldChrome.hasClearButton(element(Map.of("clear_button", " "))));
    }

    @Test
    void optionalColourKeepsVanillaWhenUnset() {
        assertNull(TextFieldChrome.optionalColor(null));
        assertNull(TextFieldChrome.optionalColor("tx:accent"));
        assertNull(TextFieldChrome.optionalColor("#12"));
        assertNull(TextFieldChrome.optionalColor(42));
    }

    @Test
    void optionalColourParsesArgbAndRgb() {
        assertEquals(0x80FFFFFF, TextFieldChrome.optionalColor("#80FFFFFF"));
        assertEquals(0xFFFFAA00, TextFieldChrome.optionalColor("#FFAA00"));
        assertEquals(-1, TextFieldChrome.optionalColor("#FFFFFFFF"),
                "white is -1; a sentinel fallback would silently drop it");
    }

    private static UiElement element(Map<String, Object> style) {
        return new UiElement("field", "text_field", 0, 0, "40", "20", true, true, 0,
                Map.of(), List.of(), Map.of("style", style));
    }
}
