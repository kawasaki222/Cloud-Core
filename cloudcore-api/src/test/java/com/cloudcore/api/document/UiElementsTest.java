package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cloudcore.api.SurfaceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiElementsTest {

    @Test
    void findsTopLevelAndNestedElements() {
        UiElement inner = new UiElement("list.row", "label", 0, 0, null, null,
                true, true, 0, Map.of(), List.of(), Map.of("text", "row"));
        UiElement list = new UiElement("list", "scroll", 0, 0, "100", "50",
                true, true, 0, Map.of(), List.of(inner), Map.of());
        UiElement other = new UiElement("top", "label", 0, 0, null, null,
                true, true, 0, Map.of(), List.of(), Map.of("text", "top"));
        UiDocument document = new UiDocument(1, "lookup", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(list, other));

        assertEquals(list, UiElements.findById(document, "list"));
        assertEquals(inner, UiElements.findById(document, "list.row"));
        assertEquals(other, UiElements.findById(document, "top"));
    }

    @Test
    void returnsNullForUnknownOrMissingIds() {
        UiElement label = new UiElement("a", "label", 0, 0, null, null,
                true, true, 0, Map.of(), List.of(), Map.of());
        UiDocument document = new UiDocument(1, "lookup", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(label));

        assertNull(UiElements.findById(document, "missing"));
        assertNull(UiElements.findById(document, null));
        assertNull(UiElements.findById(label, "b"));
    }
}
