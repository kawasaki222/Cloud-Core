package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElementHitTesterTest {

    @Test
    void findsTheDeepestClickableElement() {
        UiElement button = new UiElement("buy", "button", 10, 10, "40", "20",
                true, true, 0, Map.of("click", 7), List.of(), Map.of("text", "Buy"));
        UiElement panel = new UiElement("panel", "panel", 0, 0, "100", "100",
                true, true, 0, Map.of(), List.of(button), Map.of());
        UiDocument document = document(panel);

        // Document 100x100 centred in 320x180 => origin (110, 40).
        UiElement hit = ElementHitTester.findWithEvent(document, 320, 180, 110 + 20, 40 + 20,
                id -> 0, "click");

        assertEquals("buy", hit.id());
    }

    @Test
    void ignoresElementsWithoutTheEvent() {
        UiElement label = new UiElement("label", "label", 0, 0, "50", "10",
                true, true, 0, Map.of(), List.of(), Map.of("text", "hi"));
        UiDocument document = document(label);
        assertNull(ElementHitTester.findWithEvent(document, 320, 180, 110 + 5, 40 + 5,
                id -> 0, "click"));
    }

    @Test
    void rowChildrenAreHitWhereTheRendererDrawsThem() {
        UiElement first = new UiElement("a", "button", 0, 0, "40", "10",
                true, true, 0, Map.of("click", 1), List.of(), Map.of("text", "a"));
        UiElement second = new UiElement("b", "button", 0, 0, "40", "10",
                true, true, 0, Map.of("click", 2), List.of(), Map.of("text", "b"));
        UiElement row = new UiElement("row", "row", 10, 0, "100", "10",
                true, true, 0, Map.of(), List.of(first, second), Map.of("gap", 5));
        UiDocument document = document(row);

        // 100x100 document centred in 320x180 => origin (110, 40); the row starts at x=10.
        // "a" occupies 10..50 and "b" 55..95 because the row advances by width + gap.
        assertEquals("a", ElementHitTester.findWithEvent(document, 320, 180, 110 + 20,
                40 + 5, id -> 0, "click").id());
        assertEquals("b", ElementHitTester.findWithEvent(document, 320, 180, 110 + 60,
                40 + 5, id -> 0, "click").id());
        assertNull(ElementHitTester.findWithEvent(document, 320, 180, 110 + 52,
                40 + 5, id -> 0, "click"), "the gap between children is not a target");
    }

    @Test
    void gridChildrenAreHitOnTheirCells() {
        UiElement first = new UiElement("a", "button", 0, 0, "20", "10",
                true, true, 0, Map.of("click", 1), List.of(), Map.of("text", "a"));
        UiElement second = new UiElement("b", "button", 0, 0, "20", "10",
                true, true, 0, Map.of("click", 2), List.of(), Map.of("text", "b"));
        UiElement third = new UiElement("c", "button", 0, 0, "20", "10",
                true, true, 0, Map.of("click", 3), List.of(), Map.of("text", "c"));
        UiElement grid = new UiElement("grid", "grid", 0, 0, "100", "100",
                true, true, 0, Map.of(), List.of(first, second, third),
                Map.of("columns", 2, "cell", List.of(20, 12), "gap", 2));
        UiDocument document = document(grid);

        // Cell 0 at (0,0), cell 1 at (22,0), cell 2 wraps to (0,14).
        assertEquals("b", ElementHitTester.findWithEvent(document, 320, 180, 110 + 25,
                40 + 5, id -> 0, "click").id());
        assertEquals("c", ElementHitTester.findWithEvent(document, 320, 180, 110 + 5,
                40 + 16, id -> 0, "click").id());
    }

    @Test
    void scrollOffsetMovesHitTargets() {
        UiElement row = new UiElement("row", "button", 0, 80, "80", "18",
                true, true, 0, Map.of("click", 1), List.of(), Map.of("text", "row"));
        UiElement scroll = new UiElement("scroller", "scroll", 0, 0, "100", "50",
                true, true, 0, Map.of(), List.of(row), Map.of("scroll_y", 40));
        UiDocument document = document(scroll);

        // With 40px scrolled the row (y=80) sits at 40..58 inside the 0..50 box, so a point
        // at document y=45 hits it.
        UiElement hit = ElementHitTester.findWithEvent(document, 320, 180, 110 + 5, 40 + 45,
                id -> 40, "click");
        assertEquals("row", hit.id());

        // Without the offset the row is below the viewport, so nothing is hit.
        assertNull(ElementHitTester.findWithEvent(document, 320, 180, 110 + 5, 40 + 45,
                id -> 0, "click"));
    }

    @Test
    void hasAnyEventWalksChildren() {
        UiElement button = new UiElement("b", "button", 0, 0, "10", "10", true, true, 0,
                Map.of("click", 1), List.of(), Map.of());
        UiElement panel = new UiElement("p", "panel", 0, 0, "10", "10", true, true, 0,
                Map.of(), List.of(button), Map.of());
        UiDocument document = document(panel);
        assertTrue(ElementHitTester.hasAnyEvent(document.elements(), "click"));
    }

    private static UiDocument document(UiElement element) {
        return new UiDocument(1, "hit", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("t"),
                "default", 100, 100, 1.0f, null, Map.of(), Map.of(), List.of(element));
    }
}
