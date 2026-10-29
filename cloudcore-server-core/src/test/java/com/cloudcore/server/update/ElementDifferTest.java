package com.cloudcore.server.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.protocol.ElementUpdate;
import com.cloudcore.protocol.WireValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElementDifferTest {

    @Test
    void identicalDocumentsNeedNoTraffic() {
        UiDocument document = document(element("label", "label", Map.of("text", "0")));

        ElementDiffer.Result result = ElementDiffer.diff(document, document);

        assertEquals(ElementDiffer.Mode.UNCHANGED, result.mode());
    }

    @Test
    void labelTextBecomesOneEntry() {
        ElementDiffer.Result result = ElementDiffer.diff(
                document(element("balance", "label", Map.of("text", "100"))),
                document(element("balance", "label", Map.of("text", "80"))));

        assertEquals(ElementDiffer.Mode.INCREMENTAL, result.mode());
        assertEquals(List.of(new ElementUpdate.Entry("balance",
                        ElementUpdate.PROPERTY_TEXT, new WireValue.StringValue("80"))),
                result.entries());
    }

    @Test
    void visibilityAndEnabledUseTheirOwnProperties() {
        ElementDiffer.Result result = ElementDiffer.diff(
                document(element("b", "button", Map.of("text", "Buy"), true, true)),
                document(element("b", "button", Map.of("text", "Buy"), false, false)));

        assertEquals(ElementDiffer.Mode.INCREMENTAL, result.mode());
        assertEquals(2, result.entries().size());
        assertTrue(has(result, "b", ElementUpdate.PROPERTY_VISIBLE,
                new WireValue.BoolValue(false)));
        assertTrue(has(result, "b", ElementUpdate.PROPERTY_ENABLED,
                new WireValue.BoolValue(false)));
    }

    @Test
    void nestedElementChangesKeepTheChildId() {
        UiElement before = panel(element("row.title", "label", Map.of("text", "old")));
        UiElement after = panel(element("row.title", "label", Map.of("text", "new")));

        ElementDiffer.Result result = ElementDiffer.diff(document(before), document(after));

        assertEquals(ElementDiffer.Mode.INCREMENTAL, result.mode());
        assertEquals(1, result.entries().size());
        assertEquals("row.title", result.entries().get(0).elementId());
    }

    @Test
    void numericValueAndScrollOffsetAreIncremental() {
        ElementDiffer.Result value = ElementDiffer.diff(
                document(element("bar", "progress", Map.of("value", 0.25))),
                document(element("bar", "progress", Map.of("value", 0.5))));
        assertTrue(has(value, "bar", ElementUpdate.PROPERTY_VALUE,
                new WireValue.DoubleValue(0.5)));

        ElementDiffer.Result scroll = ElementDiffer.diff(
                document(element("list", "scroll", Map.of("scroll_y", 0))),
                document(element("list", "scroll", Map.of("scroll_y", 24))));
        assertTrue(has(scroll, "list", ElementUpdate.PROPERTY_SCROLL_Y,
                new WireValue.IntValue(24)));
    }

    @Test
    void styleCheckedAndColourAreIncremental() {
        ElementDiffer.Result style = ElementDiffer.diff(
                document(element("panel", "panel", Map.of("style",
                        Map.of("background", "#FF000000")))),
                document(element("panel", "panel", Map.of("style",
                        Map.of("background", "#FFFF0000")))));
        assertTrue(has(style, "panel", ElementUpdate.PROPERTY_STYLE,
                new WireValue.MapValue(Map.of("background",
                        new WireValue.StringValue("#FFFF0000")))));

        ElementDiffer.Result checked = ElementDiffer.diff(
                document(element("check", "checkbox", Map.of("checked", false))),
                document(element("check", "checkbox", Map.of("checked", true))));
        assertTrue(has(checked, "check", ElementUpdate.PROPERTY_CHECKED,
                new WireValue.BoolValue(true)));

        ElementDiffer.Result colour = ElementDiffer.diff(
                document(element("label", "label", Map.of("text", "x", "color", "#FF000000"))),
                document(element("label", "label", Map.of("text", "x", "color", "#FFFFFFFF"))));
        assertTrue(has(colour, "label", ElementUpdate.PROPERTY_COLOR,
                new WireValue.StringValue("#FFFFFFFF")));
    }

    @Test
    void richTextRunsTravelAsPropertyText() {
        Map<String, Object> before = Map.of("runs",
                List.of(Map.of("text", "hi", "color", "#FF000000")));
        Map<String, Object> after = Map.of("runs",
                List.of(Map.of("text", "bye", "color", "#FFFFFFFF")));

        ElementDiffer.Result result = ElementDiffer.diff(
                document(element("label", "label", before)),
                document(element("label", "label", after)));

        assertEquals(ElementDiffer.Mode.INCREMENTAL, result.mode());
        ElementUpdate.Entry entry = result.entries().get(0);
        assertEquals(ElementUpdate.PROPERTY_TEXT, entry.propertyId());
        assertTrue(entry.value() instanceof WireValue.ListValue);
    }

    @Test
    void removedStyleBecomesNull() {
        ElementDiffer.Result result = ElementDiffer.diff(
                document(element("panel", "panel", Map.of("style",
                        Map.of("background", "#FF000000")))),
                document(element("panel", "panel", Map.of())));

        assertTrue(has(result, "panel", ElementUpdate.PROPERTY_STYLE,
                WireValue.NullValue.INSTANCE));
    }

    @Test
    void textRemovalFallsBackToAFullDocument() {
        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(
                document(element("label", "label", Map.of("text", "hi"))),
                document(element("label", "label", Map.of()))).mode());
    }

    @Test
    void textFieldChangesFallBackToAFullDocument() {
        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(
                document(element("name", "text_field", Map.of("value", "a"))),
                document(element("name", "text_field", Map.of("value", "b")))).mode());
        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(
                document(element("name", "text_field", Map.of("value", "a"), true, true)),
                document(element("name", "text_field", Map.of("value", "a"), false, true)))
                .mode());
    }

    @Test
    void structuralChangesFallBack() {
        UiElement a = element("a", "label", Map.of("text", "x"));
        UiElement b = element("b", "label", Map.of("text", "y"));

        assertEquals(ElementDiffer.Mode.FULL,
                ElementDiffer.diff(document(a), document(a, b)).mode());
        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(
                document(element("a", "label", Map.of("text", "x"))),
                document(element("a", "label", Map.of("text", "x", "width", "120")))).mode());
        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(
                document(element("a", "label", Map.of("text", "x", "unknown", 1))),
                document(element("a", "label", Map.of("text", "x", "unknown", 2)))).mode());
    }

    @Test
    void documentLevelChangesFallBack() {
        UiElement element = element("a", "label", Map.of("text", "x"));
        UiDocument before = document(element);
        UiDocument after = new UiDocument(before.schema(), before.id(), before.surface(),
                before.title(), before.theme(), 320, before.height(), before.scale(),
                before.container(), before.surfaceOptions(), before.fonts(),
                before.elements());

        assertEquals(ElementDiffer.Mode.FULL, ElementDiffer.diff(before, after).mode());
    }

    private static boolean has(ElementDiffer.Result result, String id, int property,
            WireValue value) {
        return result.entries().stream()
                .anyMatch(entry -> entry.elementId().equals(id)
                        && entry.propertyId() == property && entry.value().equals(value));
    }

    private static UiDocument document(UiElement... elements) {
        return new UiDocument(1, "page", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("Page"),
                null, 200, 100, 1.0f, null, Map.of(), Map.of(), List.of(elements));
    }

    private static UiElement element(String id, String type, Map<String, Object> attributes) {
        return element(id, type, attributes, true, true);
    }

    private static UiElement element(String id, String type, Map<String, Object> attributes,
            boolean visible, boolean enabled) {
        return new UiElement(id, type, 0, 0, "100", "8", visible, enabled, 0, Map.of(),
                List.of(), attributes);
    }

    private static UiElement panel(UiElement child) {
        return new UiElement("row", "row", 0, 0, "100", "8", true, true, 0, Map.of(),
                List.of(child), Map.of());
    }
}
