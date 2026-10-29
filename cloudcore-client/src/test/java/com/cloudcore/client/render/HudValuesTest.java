package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HudValuesTest {

    @Test
    void unchangedValueKeepsTheSameDocument() {
        UiDocument document = document(element("hp", "progress",
                Map.of("value_from", "health", "value", 0.5), List.of()));

        assertSame(document, HudValues.apply(document, values(0.5, null)),
                "a steady HUD must not allocate a new document every frame");
    }

    @Test
    void secondFrameWithTheSameValueKeepsTheLiveDocument() {
        UiDocument document = document(element("hp", "progress",
                Map.of("value_from", "health", "value", 0.0), List.of()));

        UiDocument first = HudValues.apply(document, values(0.5, null));
        UiDocument second = HudValues.apply(first, values(0.5, null));

        assertNotSame(document, first);
        assertSame(first, second,
                "the previous live document is the baseline; an unchanged value must reuse it");
    }

    @Test
    void changedValueRewritesOnlyThatElement() {
        UiElement hp = element("hp", "progress",
                Map.of("value_from", "health", "value", 0.5), List.of());
        UiElement label = element("name", "label", Map.of("text", "Steve"), List.of());
        UiDocument document = document(hp, label);

        UiDocument live = HudValues.apply(document, values(0.25, null));

        assertNotSame(document, live);
        assertEquals(0.25, live.elements().get(0).attributes().get("value"));
        assertSame(label, live.elements().get(1), "untouched elements are shared");
        assertSame(label, document.elements().get(1));
    }

    @Test
    void textFromReplacesOnlyTextAttributes() {
        UiElement label = element("hp_text", "label",
                Map.of("text_from", "health", "color", "#FFFFFFFF"), List.of());
        UiDocument document = document(label);

        UiDocument live = HudValues.apply(document, values(null, "20/20"));

        assertEquals("20/20", live.elements().get(0).attributes().get("text"));
        assertEquals("#FFFFFFFF", live.elements().get(0).attributes().get("color"));
        assertEquals("health", live.elements().get(0).attributes().get("text_from"),
                "the source key survives so the next frame can refresh again");
    }

    @Test
    void nestedChildIsRewrittenWhileSiblingsAreShared() {
        UiElement bar = element("hp", "progress",
                Map.of("value_from", "health", "value", 1.0), List.of());
        UiElement staticLabel = element("caption", "label", Map.of("text", "HP"), List.of());
        UiElement panel = element("root", "panel", Map.of(), List.of(bar, staticLabel));
        UiDocument document = document(panel);

        UiDocument live = HudValues.apply(document, values(0.4, null));

        UiElement livePanel = live.elements().get(0);
        assertNotSame(panel, livePanel);
        assertEquals(0.4, livePanel.children().get(0).attributes().get("value"));
        assertSame(staticLabel, livePanel.children().get(1));
    }

    @Test
    void unknownSourceLeavesTheElementAlone() {
        UiElement hp = element("hp", "progress",
                Map.of("value_from", "mana", "value", 0.5), List.of());
        UiDocument document = document(hp);

        assertSame(document, HudValues.apply(document, values(null, null)));
    }

    @Test
    void documentsWithoutLiveValuesAreSkippedByTheClientEntryPoint() {
        UiDocument document = document(element("label", "label", Map.of("text", "Hi"), List.of()));
        assertFalse(HudValues.usesLiveValues(document));
        assertTrue(HudValues.usesLiveValues(document(
                element("hp", "progress", Map.of("value_from", "health"), List.of()))));
    }

    private static UiDocument document(UiElement... elements) {
        return new UiDocument(1, "hud", SurfaceType.HUD, TextRuns.plain("HUD"), null,
                320, 180, 1.0f, null, Map.of(), Map.of(), List.of(elements));
    }

    private static UiElement element(String id, String type, Map<String, Object> attributes,
            List<UiElement> children) {
        return new UiElement(id, type, 0, 0, "100", "8", true, true, 0, Map.of(), children,
                attributes);
    }

    private static HudValues.Values values(Double fraction, String text) {
        return new HudValues.Values() {
            @Override
            public Double fraction(String source) {
                return fraction;
            }

            @Override
            public String text(String source) {
                return text;
            }
        };
    }
}
