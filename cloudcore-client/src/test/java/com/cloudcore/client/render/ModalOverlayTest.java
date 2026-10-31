package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code overlay} with {@code modal: true} blocks input for everything drawn before it, and
 * its {@code dim} veil is an opacity rather than a colour.
 */
class ModalOverlayTest {

    private static UiDocument document(List<UiElement> elements) {
        return new UiDocument(1, "page", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("x"), null,
                100, 100, 1.0f, null, Map.of(), Map.of(), elements);
    }

    private static UiElement button(String id, int x, int y) {
        return new UiElement(id, "button", x, y, "40", "20", true, true, 0,
                Map.of("click", 1), List.of(), Map.of("text", id));
    }

    @Test
    void aModalOverlaySwallowsClicksOnElementsBehindIt() {
        UiElement background = button("background", 0, 0);
        UiElement confirm = button("confirm", 0, 60);
        UiElement overlay = new UiElement("dialog", "overlay", 0, 60, "100", "40", true, true,
                0, Map.of(), List.of(confirm), Map.of("modal", true));

        // Document 100x100 centred in 320x180 => origin (110, 40).
        UiElement hit = ElementHitTester.findWithEvent(
                document(List.of(background, overlay)), 320, 180, 110 + 20, 40 + 10,
                id -> 0, "click");

        assertNull(hit, "the background button is behind a modal overlay");
    }

    @Test
    void theOverlayOwnsItsOwnClicks() {
        UiElement confirm = button("confirm", 0, 0);
        UiElement overlay = new UiElement("dialog", "overlay", 0, 60, "100", "40", true, true,
                0, Map.of(), List.of(confirm), Map.of("modal", true));

        UiElement hit = ElementHitTester.findWithEvent(document(List.of(overlay)), 320, 180,
                110 + 20, 40 + 70, id -> 0, "click");

        assertEquals("confirm", hit.id());
    }

    @Test
    void aNonModalOverlayDoesNotBlock() {
        UiElement background = button("background", 0, 0);
        UiElement overlay = new UiElement("hint", "overlay", 0, 60, "100", "40", true, true,
                0, Map.of(), List.of(), Map.of());

        UiElement hit = ElementHitTester.findWithEvent(document(List.of(background, overlay)),
                320, 180, 110 + 20, 40 + 10, id -> 0, "click");

        assertEquals("background", hit.id());
    }

    @Test
    void hasModalReportsTheCaptureState() {
        UiElement overlay = new UiElement("dialog", "overlay", 0, 0, "100", "100", true, true,
                0, Map.of(), List.of(), Map.of("modal", true));
        assertTrue(ElementHitTester.hasModal(document(List.of(overlay))));
        assertFalse(ElementHitTester.hasModal(document(List.of(button("b", 0, 0)))));
        assertFalse(ElementHitTester.hasModal(null));
    }

    @Test
    void dimIsAnOpacityNotAColour() {
        assertEquals(0x80000000, DocumentRenderListBuilder.dimVeil(0.5));
        assertEquals(0xFF000000, DocumentRenderListBuilder.dimVeil(1.0));
        assertEquals(0x00000000, DocumentRenderListBuilder.dimVeil(0.0));
        assertEquals(0x80000000, DocumentRenderListBuilder.dimVeil(null));
    }
}
