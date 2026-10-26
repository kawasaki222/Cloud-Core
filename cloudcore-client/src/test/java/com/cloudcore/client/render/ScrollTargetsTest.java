package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScrollTargetsTest {

    @Test
    void findsScrollElementUnderTheCursor() {
        UiDocument document = document();
        // Document 320x180 inside 427x240 => origin (53, 30); scroll at (16,36).
        ScrollTargets.Target target = ScrollTargets.find(document, 427, 240,
                53 + 20, 30 + 40, id -> 0);

        assertEquals("scroller", target.id());
        assertEquals(53 + 16, target.x());
        assertEquals(30 + 36, target.y());
        assertEquals(200, target.width());
        assertEquals(80, target.height());
        assertEquals(38, target.maxScroll(), "content 118 tall in an 80 tall box");
    }

    @Test
    void missesOutsideTheScrollBox() {
        UiDocument document = document();
        assertNull(ScrollTargets.find(document, 427, 240, 5, 5, id -> 0));
        assertNull(ScrollTargets.find(document, 427, 240, 53 + 20, 30 + 130, id -> 0));
    }

    @Test
    void maxScrollNeverGoesNegative() {
        UiElement scroll = new UiElement("s", "scroll", 0, 0, "100", "60",
                true, true, 0, Map.of(), List.of(), Map.of());
        assertEquals(0, ScrollTargets.maxScroll(scroll, 100, 60));
    }

    private static UiDocument document() {
        UiElement rows = new UiElement("rows", "panel", 0, 0, "200", "118",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement scroll = new UiElement("scroller", "scroll", 16, 36, "200", "80",
                true, true, 0, Map.of(), List.of(rows), Map.of("scroll_y", 0));
        return new UiDocument(1, "scroll", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 320, 180, 1.0f, null,
                Map.of(), Map.of(), List.of(scroll));
    }
}
