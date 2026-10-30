package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.page.PageLoadException;
import org.junit.jupiter.api.Test;

class UiDocumentJsonTest {

    @Test
    void parsesMinimalDocument() throws Exception {
        UiDocument document = UiDocumentJson.parse("""
                {
                  "schema": 1,
                  "id": "shop",
                  "surface": "custom_screen",
                  "title": { "runs": [ { "text": "Shop", "color": "#FFFFFFFF" } ] },
                  "theme": "dark",
                  "size": [320, 180],
                  "scale": 1.0,
                  "surface_options": { "foo": true },
                  "fonts": {},
                  "elements": [
                    {
                      "id": "root",
                      "type": "panel",
                      "x": 1,
                      "y": 2,
                      "width": "60%",
                      "height": "fill",
                      "events": { "click": 7 },
                      "children": [
                        { "id": "label", "type": "label", "text": "Hi" }
                      ]
                    }
                  ]
                }
                """);

        assertEquals("shop", document.id());
        assertEquals(SurfaceType.CUSTOM_SCREEN, document.surface());
        assertEquals("Shop", document.title().plainText());
        assertEquals(320, document.width());
        assertEquals(180, document.height());
        assertEquals(1, document.elements().size());
        assertEquals("60%", document.elements().get(0).width());
        assertEquals(7, document.elements().get(0).events().get("click"));
        assertEquals(1, document.elements().get(0).children().size());
        assertTrue(document.surfaceOptions().containsKey("foo"));
        assertTrue(UiDocumentValidator.validate(document).valid());
    }

    @Test
    void parsesContainerSpec() throws Exception {
        UiDocument document = UiDocumentJson.parse("""
                {
                  "schema": 1,
                  "id": "chest",
                  "surface": "container",
                  "title": "Chest",
                  "size": [176, 166],
                  "container": { "rows": 6, "interaction": "readonly",
                                 "hide_player_inventory": true,
                                 "allow_player_inventory": true },
                  "elements": []
                }
                """);
        assertNotNull(document.container());
        assertEquals(6, document.container().rows());
        assertEquals("readonly", document.container().interaction());
        assertTrue(document.container().hidePlayerInventory());
        assertTrue(document.container().allowPlayerInventory());
    }

    @Test
    void rejectsUnknownSurface() {
        assertThrows(PageLoadException.class, () -> UiDocumentJson.parse("""
                {
                  "schema": 1,
                  "id": "x",
                  "surface": "unknown",
                  "title": "X",
                  "size": [1, 1],
                  "elements": []
                }
                """));
    }

    @Test
    void requiresElementsArray() {
        assertThrows(PageLoadException.class, () -> UiDocumentJson.parse("""
                {
                  "schema": 1,
                  "id": "x",
                  "surface": "custom_screen",
                  "title": "X",
                  "size": [1, 1]
                }
                """));
    }

    @Test
    void writeRoundTripsWithParse() throws Exception {
        String json = """
                {
                  "schema": 1,
                  "id": "round",
                  "surface": "container",
                  "title": { "runs": [ { "text": "Round" } ] },
                  "size": [176, 166],
                  "container": { "rows": 3, "interaction": "readonly" },
                  "fonts": { "body": { "provider": "nanovg_ttf", "source": "local",
                                       "id": "body.ttf" } },
                  "elements": [
                    { "id": "panel", "type": "panel", "x": 0, "y": 0,
                      "width": "fill", "height": "fill",
                      "style": { "background": "#FF000000" } }
                  ]
                }
                """;
        UiDocument parsed = UiDocumentJson.parse(json);

        UiDocument reparsed = UiDocumentJson.parse(UiDocumentJson.write(parsed));

        assertEquals(parsed.id(), reparsed.id());
        assertEquals(parsed.surface(), reparsed.surface());
        assertEquals(parsed.width(), reparsed.width());
        assertEquals(parsed.elements().size(), reparsed.elements().size());
        assertEquals(parsed.elements().get(0).attributes().get("style"),
                reparsed.elements().get(0).attributes().get("style"));
        assertEquals(parsed.fonts().keySet(), reparsed.fonts().keySet());
        assertEquals("readonly", reparsed.container().interaction());
    }
}
