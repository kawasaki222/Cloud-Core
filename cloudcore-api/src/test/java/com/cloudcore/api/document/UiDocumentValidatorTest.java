package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiDocumentValidatorTest {

    @Test
    void acceptsValidCustomScreen() {
        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null, List.of(element("root", "panel", List.of()))));
        assertTrue(result.valid(), result.issues().toString());
    }

    @Test
    void rejectsDuplicateElementIdsAcrossTree() {
        UiElement child = element("same", "label", List.of());
        UiElement root = element("same", "panel", List.of(child));
        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null, List.of(root)));

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-007")));
    }

    @Test
    void rejectsContainerWithoutSpec() {
        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CONTAINER, null, List.of()));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-011")));
    }

    @Test
    void rejectsInventoryGridOnHud() {
        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.HUD, null,
                List.of(element("grid", "inventory_grid", List.of()))));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-014")));
    }

    @Test
    void warnsButAcceptsUnknownElementType() {
        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null,
                List.of(element("future", "future_widget", List.of()))));
        assertTrue(result.valid());
        assertEquals(1, result.issues().size());
    }

    @Test
    void warnsAboutUnknownElementAttributesWithoutRejecting() {
        UiElement progress = new UiElement("bar", "progress", 0, 0, "100", "8", true, true, 0,
                Map.of(), List.of(), Map.of("direction", "sideways"));
        UiElement slider = new UiElement("slide", "slider", 0, 20, "100", "6", true, true, 0,
                Map.of(), List.of(), Map.of("value", 1.5));
        UiElement field = new UiElement("input", "text_field", 0, 40, "100", "14", true, true, 0,
                Map.of(), List.of(), Map.of("filter", "letters"));
        UiElement button = new UiElement("btn", "button", 0, 60, "60", "18", true, true, 0,
                Map.of(), List.of(), Map.of("icon", 42));

        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null, List.of(progress, slider, field, button)));

        assertTrue(result.valid(), "attribute problems are warnings, not errors");
        assertEquals(4, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-022")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-023")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-024")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CC-DOC-025")));
    }

    @Test
    void sliderRangeFollowsMinMaxAndIgnoresPlaceholders() {
        UiElement inside = new UiElement("a", "slider", 0, 0, "100", "6", true, true, 0,
                Map.of(), List.of(), Map.of("min", 0, "max", 100, "step", 5, "value", 40));
        UiElement outside = new UiElement("b", "slider", 0, 20, "100", "6", true, true, 0,
                Map.of(), List.of(), Map.of("min", -10, "max", 10, "value", 12));
        UiElement placeholder = new UiElement("c", "slider", 0, 40, "100", "6", true, true, 0,
                Map.of(), List.of(), Map.of("min", 0, "max", "${state.max}", "value", 50));

        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null, List.of(inside, outside, placeholder)));

        assertTrue(result.valid(), result.issues().toString());
        assertEquals(1, result.issues().size(), result.issues().toString());
        assertEquals("CC-DOC-023", result.issues().get(0).code());
        assertEquals("elements[1].value", result.issues().get(0).path());
    }

    @Test
    void acceptsKnownElementAttributesWithoutNoise() {
        UiElement progress = new UiElement("bar", "progress", 0, 0, "100", "8", true, true, 0,
                Map.of(), List.of(), Map.of("direction", "RIGHT"));
        UiElement slider = new UiElement("slide", "slider", 0, 20, "100", "6", true, true, 0,
                Map.of(), List.of(), Map.of("value", 0.5));
        UiElement field = new UiElement("input", "text_field", 0, 40, "100", "14", true, true, 0,
                Map.of(), List.of(), Map.of("filter", "regex:[A-Za-z]+"));
        UiElement button = new UiElement("btn", "button", 0, 60, "60", "18", true, true, 0,
                Map.of(), List.of(), Map.of("icon", Map.of("item", "minecraft:diamond")));

        ValidationResult result = UiDocumentValidator.validate(document(
                SurfaceType.CUSTOM_SCREEN, null, List.of(progress, slider, field, button)));

        assertTrue(result.valid(), result.issues().toString());
        assertTrue(result.issues().isEmpty(), result.issues().toString());
    }

    private static UiDocument document(SurfaceType surface, ContainerSpec container,
            List<UiElement> elements) {
        return new UiDocument(
                UiDocument.CURRENT_SCHEMA,
                "test",
                surface,
                TextRuns.plain("Test"),
                "dark",
                320,
                180,
                1.0f,
                container,
                Map.of(),
                Map.of(),
                elements);
    }

    private static UiElement element(String id, String type, List<UiElement> children) {
        return new UiElement(id, type, 0, 0, null, null,
                true, true, 0, Map.of(), children, Map.of());
    }
}
