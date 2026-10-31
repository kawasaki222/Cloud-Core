package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code tx:} references stay in the document and the client resolves them, so a typo has to
 * be caught at validate time or it shows up as a silent fallback in game.
 */
class ThemeTokensTest {

    private static ThemeTokens dark() {
        return ThemeTokens.from(Map.of(
                "color.primary", "#FF4FA3E8",
                "tex.panel", "local:公共/贴图/panel_dark.png",
                "font.body", "body",
                "space.md", 8));
    }

    private static UiDocument document(String theme, Map<String, Object> attributes) {
        UiElement element = new UiElement("panel", "panel", 0, 0, "100", "100", true, true, 0,
                Map.of(), List.of(), attributes);
        return new UiDocument(1, "page", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("x"), theme,
                200, 100, 1.0f, null, Map.of("background", "tx:tex.panel"), Map.of(),
                List.of(element));
    }

    @Test
    void classifiesTokensByNameAndValue() {
        ThemeTokens theme = dark();
        assertEquals("color", theme.find("color.primary").kind());
        assertEquals("texture", theme.find("tex.panel").kind());
        assertEquals("font", theme.find("font.body").kind());
        assertEquals("number", theme.find("space.md").kind());
    }

    @Test
    void namesAreCaseInsensitive() {
        assertTrue(dark().contains("COLOR.Primary"));
        assertFalse(dark().contains("color.unknown"));
    }

    @Test
    void collectsReferencesFromNestedValues() {
        // Map iteration order is not guaranteed; compare as a set of names.
        assertEquals(java.util.Set.of("panel", "accent"),
                java.util.Set.copyOf(ThemeTokens.references(Map.of("background", "tx:panel",
                        "states", Map.of("hover", Map.of("background", "tx:accent"))))));
        assertEquals(List.of(), ThemeTokens.references("#FF0000"));
    }

    @Test
    void reportsUndeclaredReferencesWithTheirPath() {
        ThemeTokens theme = dark();
        List<String> missing = theme.missingReferences(document("dark",
                Map.of("style", Map.of("background", "tx:panel_hover"))));

        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("panel_hover"), missing.get(0));
        assertTrue(missing.get(0).contains("elements.panel"), missing.get(0));
    }

    @Test
    void aDocumentWithOnlyDeclaredTokensReportsNothing() {
        ThemeTokens theme = dark();
        assertTrue(theme.missingReferences(document("dark",
                Map.of("style", Map.of("background", "tx:tex.panel")))).isEmpty());
    }

    @Test
    void anUnloadedThemeDoesNotInventFailures() {
        assertTrue(new ThemeTokens().missingReferences(
                document("dark", Map.of("background", "tx:whatever"))).isEmpty());
    }

    @Test
    void aDocumentWithoutAThemeIsNotChecked() {
        assertTrue(dark().missingReferences(
                document(null, Map.of("background", "tx:whatever"))).isEmpty());
    }
}
