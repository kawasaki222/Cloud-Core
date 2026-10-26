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
