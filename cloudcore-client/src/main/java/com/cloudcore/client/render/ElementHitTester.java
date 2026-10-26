package com.cloudcore.client.render;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Finds the element under a GUI point. Geometry matches {@link DocumentRenderListBuilder}:
 * absolute positions, {@code fill}/{@code %}/pixel sizes, scroll offsets, and clipping —
 * a point outside a scroll box never reaches its children.
 *
 * <p>Render/input thread only.
 */
public final class ElementHitTester {
    private ElementHitTester() {
    }

    /**
     * @param event the document event name the element must declare (e.g. {@code click})
     * @return the deepest matching element, or {@code null}
     */
    public static UiElement findWithEvent(UiDocument document, int screenWidth,
            int screenHeight, int mouseX, int mouseY,
            ToIntFunction<String> localScroll, String event) {
        if (document == null || event == null) {
            return null;
        }
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        int originX = (screenWidth - documentWidth) / 2;
        int originY = (screenHeight - documentHeight) / 2;
        UiElement best = null;
        for (UiElement element : document.elements()) {
            UiElement hit = search(element, originX, originY, documentWidth, documentHeight,
                    mouseX, mouseY, localScroll, event);
            if (hit != null) {
                best = hit;
            }
        }
        return best;
    }

    private static UiElement search(UiElement element, int parentX, int parentY,
            int parentWidth, int parentHeight, int mouseX, int mouseY,
            ToIntFunction<String> localScroll, String event) {
        if (!element.visible()) {
            return null;
        }
        int x = parentX + element.x();
        int y = parentY + element.y();
        int width = resolve(element.width(), parentWidth, 0);
        int height = resolve(element.height(), parentHeight, 0);
        int boxWidth = width > 0 ? width : parentWidth;
        int boxHeight = height > 0 ? height : parentHeight;

        boolean inside = mouseX >= x && mouseX < x + boxWidth
                && mouseY >= y && mouseY < y + boxHeight;
        if (!inside) {
            return null;
        }
        UiElement deepest = null;
        int childX = x;
        int childY = y;
        if ("scroll".equals(element.type())) {
            childY -= localScroll.applyAsInt(element.id());
        }
        if (isLayout(element.type())) {
            // Row/column/grid children sit where the renderer put them, not at their own x/y.
            ContainerCursor cursor = new ContainerCursor(element, childX, childY, boxWidth,
                    boxHeight);
            for (UiElement child : element.children()) {
                int[] offset = cursor.next(child);
                UiElement hit = search(child, offset[0], offset[1], boxWidth, boxHeight,
                        mouseX, mouseY, localScroll, event);
                if (hit != null) {
                    deepest = hit;
                }
            }
        } else {
            for (UiElement child : element.children()) {
                UiElement hit = search(child, childX, childY, boxWidth, boxHeight,
                        mouseX, mouseY, localScroll, event);
                if (hit != null) {
                    deepest = hit;
                }
            }
        }
        if (deepest != null) {
            return deepest;
        }
        // A disabled element is drawn dimmed and never receives events; its children are
        // still searched above, so a disabled panel can hold enabled buttons.
        if (!element.enabled()) {
            return null;
        }
        return element.events().containsKey(event) ? element : null;
    }

    private static boolean isLayout(String type) {
        return "row".equals(type) || "column".equals(type) || "grid".equals(type);
    }

    private static int resolve(String value, int parent, int fallback) {
        return Geometry.resolve(value, parent, fallback);
    }

    /** @return true when any element in the list declares the event (used for cursor hints) */
    public static boolean hasAnyEvent(List<UiElement> elements, String event) {
        for (UiElement element : elements) {
            if (element.events().containsKey(event) || hasAnyEvent(element.children(), event)) {
                return true;
            }
        }
        return false;
    }
}
