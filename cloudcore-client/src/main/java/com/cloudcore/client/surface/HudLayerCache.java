package com.cloudcore.client.surface;

import com.cloudcore.api.document.UiDocument;

/**
 * Rebuild decisions for one HUD layer. Extracted from {@link HudSurfaceAdapter} so the rule
 * "same live values must not rebuild" is covered by a unit test instead of only by a client
 * session.
 *
 * <p>Render thread only once a layer is live; the class itself holds no handles.
 */
final class HudLayerCache {

    private UiDocument source;
    private UiDocument live;
    private int width = -1;
    private int height = -1;

    /**
     * A live-value rewrite must use the previous <em>live</em> document as its baseline when
     * the server document has not changed. Comparing against the server document would look
     * changed every frame — it carries no live {@code value} at all — and rebuild the render
     * list at the frame rate.
     */
    UiDocument baselineFor(UiDocument document) {
        return source == document && live != null ? live : document;
    }

    /** @return true when the cached render list no longer matches the document or viewport */
    boolean update(UiDocument document, UiDocument live, int width, int height) {
        if (this.live == live && this.width == width && this.height == height) {
            return false;
        }
        this.source = document;
        this.live = live;
        this.width = width;
        this.height = height;
        return true;
    }

    /** Last rewritten document, or {@code null} before the first frame. */
    UiDocument live() {
        return live;
    }
}
