package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class DocumentUpdatesTest {

    @Test
    void textUpdateRewritesOnlyThatElement() {
        UiElement label = element("balance", "label", Map.of("text", "100"));
        UiElement caption = element("caption", "label", Map.of("text", "Balance"));
        UiDocument document = document(label, caption);

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("balance", ElementUpdate.PROPERTY_TEXT,
                        new WireValue.StringValue("80"))));

        assertTrue(result.applied());
        assertEquals("80", result.document().elements().get(0).attributes().get("text"));
        assertSame(caption, result.document().elements().get(1),
                "untouched elements are shared");
        assertSame(caption, document.elements().get(1));
    }

    @Test
    void nestedElementsAreFoundById() {
        UiElement child = element("row.title", "label", Map.of("text", "old"));
        UiElement row = new UiElement("row", "row", 0, 0, "100", "20", true, true, 0,
                Map.of(), List.of(child), Map.of());
        UiDocument document = document(row);

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("row.title", ElementUpdate.PROPERTY_TEXT,
                        new WireValue.StringValue("new"))));

        assertTrue(result.applied());
        assertEquals("new", result.document().elements().get(0)
                .children().get(0).attributes().get("text"));
    }

    @Test
    void visibleAndEnabledUseElementFlags() {
        UiDocument document = document(element("button", "button", Map.of("text", "Buy")));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("button", ElementUpdate.PROPERTY_VISIBLE, new WireValue.BoolValue(false)),
                entry("button", ElementUpdate.PROPERTY_ENABLED, new WireValue.BoolValue(false))));

        assertTrue(result.applied());
        assertFalse(result.document().elements().get(0).visible());
        assertFalse(result.document().elements().get(0).enabled());
    }

    @Test
    void richTextReplacesPlainText() {
        UiDocument document = document(element("label", "label", Map.of("text", "hi")));
        WireValue runs = new WireValue.ListValue(List.of(new WireValue.MapValue(Map.of(
                "text", new WireValue.StringValue("bye")))));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("label", ElementUpdate.PROPERTY_TEXT, runs)));

        assertTrue(result.applied());
        Map<String, Object> attributes = result.document().elements().get(0).attributes();
        assertFalse(attributes.containsKey("text"));
        assertTrue(attributes.get("runs") instanceof List<?>);
    }

    @Test
    void nullRemovesAnAttribute() {
        UiDocument document = document(element("panel", "panel",
                Map.of("style", Map.of("background", "#FF000000"))));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("panel", ElementUpdate.PROPERTY_STYLE, WireValue.NullValue.INSTANCE)));

        assertTrue(result.applied());
        assertFalse(result.document().elements().get(0).attributes().containsKey("style"));
    }

    @Test
    void unknownElementRejectsTheWholeUpdate() {
        UiDocument document = document(element("a", "label", Map.of("text", "x")));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("a", ElementUpdate.PROPERTY_TEXT, new WireValue.StringValue("y")),
                entry("missing", ElementUpdate.PROPERTY_TEXT,
                        new WireValue.StringValue("z"))));

        assertFalse(result.applied());
        assertSame(document, result.document());
        assertTrue(result.problem().contains("missing"));
    }

    @Test
    void unknownPropertyRejectsTheWholeUpdate() {
        UiDocument document = document(element("a", "label", Map.of("text", "x")));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("a", 99, new WireValue.StringValue("y"))));

        assertFalse(result.applied());
        assertSame(document, result.document());
    }

    @Test
    void emptyUpdateIsTheSameDocument() {
        UiDocument document = document(element("a", "label", Map.of("text", "x")));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of());

        assertTrue(result.applied());
        assertSame(document, result.document());
    }

    @Test
    void styleMapReplacesTheWholeStyleAttribute() {
        UiDocument document = document(element("panel", "panel",
                Map.of("style", Map.of("background", "#FF000000"))));
        WireValue style = new WireValue.MapValue(Map.of(
                "background", new WireValue.StringValue("#FFFF0000")));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("panel", ElementUpdate.PROPERTY_STYLE, style)));

        assertTrue(result.applied());
        assertEquals(Map.of("background", "#FFFF0000"),
                result.document().elements().get(0).attributes().get("style"));
    }

    @Test
    void progressAliasWritesValue() {
        UiDocument document = document(element("bar", "progress", Map.of("value", 0.1)));

        DocumentUpdates.Result result = DocumentUpdates.apply(document, List.of(
                entry("bar", ElementUpdate.PROPERTY_PROGRESS,
                        new WireValue.DoubleValue(0.75))));

        assertTrue(result.applied());
        assertEquals(0.75,
                result.document().elements().get(0).attributes().get("value"));
    }

    private static ElementUpdate.Entry entry(String id, int property, WireValue value) {
        return new ElementUpdate.Entry(id, property, value);
    }

    private static UiDocument document(UiElement... elements) {
        return new UiDocument(1, "page", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("Page"),
                null, 200, 100, 1.0f, null, Map.of(), Map.of(), List.of(elements));
    }

    private static UiElement element(String id, String type, Map<String, Object> attributes) {
        return new UiElement(id, type, 0, 0, "100", "8", true, true, 0, Map.of(), List.of(),
                attributes);
    }
}
