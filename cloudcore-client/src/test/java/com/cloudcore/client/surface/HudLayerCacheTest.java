package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the HUD rule "same live values must not rebuild the render list".
 * Before the extraction this was only observable in a running client.
 */
class HudLayerCacheTest {

    @Test
    void firstFrameBuilds() {
        UiDocument document = document();

        assertTrue(new HudLayerCache().update(document, document, 427, 240));
    }

    @Test
    void sameLiveDocumentAndViewportDoesNotRebuild() {
        UiDocument document = document();
        HudLayerCache cache = new HudLayerCache();
        assertTrue(cache.update(document, document, 427, 240));

        assertFalse(cache.update(document, document, 427, 240),
                "a steady HUD must reuse the cached render list every frame");
        assertSame(document, cache.live());
    }

    @Test
    void changedLiveDocumentRebuilds() {
        UiDocument document = document();
        UiDocument changed = withValue(document, 0.4);
        HudLayerCache cache = new HudLayerCache();
        assertTrue(cache.update(document, document, 427, 240));

        assertTrue(cache.update(document, changed, 427, 240));
        assertSame(changed, cache.live());
    }

    @Test
    void viewportChangeRebuilds() {
        UiDocument document = document();
        HudLayerCache cache = new HudLayerCache();
        assertTrue(cache.update(document, document, 427, 240));

        assertTrue(cache.update(document, document, 854, 480));
    }

    @Test
    void baselineIsOnlyReusedForTheSameServerDocument() {
        UiDocument first = document();
        UiDocument second = document();
        HudLayerCache cache = new HudLayerCache();
        UiDocument liveFirst = withValue(first, 0.25);
        assertTrue(cache.update(first, liveFirst, 427, 240));

        assertSame(liveFirst, cache.baselineFor(first),
                "live values feed back into the next rewrite");
        assertSame(second, cache.baselineFor(second),
                "a new server revision must not be compared against the old live copy");
    }

    private static UiDocument document() {
        UiElement bar = new UiElement("hp", "progress", 0, 0, "100", "8", true, true, 0,
                Map.of(), List.of(), Map.of("value_from", "health", "value", 0.5));
        return new UiDocument(1, "hud", SurfaceType.HUD, TextRuns.plain("HUD"), null,
                320, 180, 1.0f, null, Map.of(), Map.of(), List.of(bar));
    }

    /** A new document instance with one changed attribute, like a live-value rewrite. */
    private static UiDocument withValue(UiDocument source, double value) {
        UiElement bar = source.elements().get(0);
        UiElement rewritten = new UiElement(bar.id(), bar.type(), bar.x(), bar.y(), bar.width(),
                bar.height(), bar.visible(), bar.enabled(), bar.z(), bar.events(),
                bar.children(), Map.of("value_from", "health", "value", value));
        return new UiDocument(source.schema(), source.id(), source.surface(), source.title(),
                source.theme(), source.width(), source.height(), source.scale(),
                source.container(), source.surfaceOptions(), source.fonts(),
                List.of(rewritten));
    }
}
