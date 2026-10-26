package com.cloudcore.client.render;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Finds the {@code scroll} element under a GUI point and reports how far it may scroll.
 * The geometry mirrors {@link DocumentRenderListBuilder}: elements are absolute positions of
 * their parent plus their own x/y, and {@code fill}/{@code %}/pixel sizes resolve against
 * the parent box.
 *
 * <p>Render/input thread only (it reads the current document).
 */
public final class ScrollTargets {
    private ScrollTargets() {
    }

    public record Target(String id, int x, int y, int width, int height,
            int maxScroll, int current) {
    }

    /**
     * @param mouseX/mouseY GUI coordinates
     * @param localScroll   wheel offset already applied to each scroll element
     * @return the deepest scroll element containing the point, or {@code null}
     */
    public static Target find(UiDocument document, int screenWidth, int screenHeight,
            int mouseX, int mouseY, ToIntFunction<String> localScroll) {
        if (document == null || screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        int originX = (screenWidth - documentWidth) / 2;
        int originY = (screenHeight - documentHeight) / 2;
        for (UiElement element : document.elements()) {
            Target target = search(element, originX, originY, documentWidth, documentHeight,
                    mouseX, mouseY, localScroll);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static Target search(UiElement element, int parentX, int parentY,
            int parentWidth, int parentHeight, int mouseX, int mouseY,
            ToIntFunction<String> localScroll) {
        if (!element.visible()) {
            return null;
        }
        int x = parentX + element.x();
        int y = parentY + element.y();
        int width = resolve(element.width(), parentWidth, 0);
        int height = resolve(element.height(), parentHeight, 0);
        int boxWidth = width > 0 ? width : parentWidth;
        int boxHeight = height > 0 ? height : parentHeight;

        if ("scroll".equals(element.type())) {
            int current = localScroll.applyAsInt(element.id());
            if (mouseX >= x && mouseX < x + boxWidth && mouseY >= y && mouseY < y + boxHeight) {
                return new Target(element.id(), x, y, boxWidth, boxHeight,
                        maxScroll(element, boxWidth, boxHeight), current);
            }
            return null;
        }

        int childX = x;
        int childY = y;
        for (UiElement child : element.children()) {
            Target target = search(child, childX, childY, boxWidth, boxHeight,
                    mouseX, mouseY, localScroll);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /** How far the content may move up: content height minus the visible viewport. */
    public static int maxScroll(UiElement scroll, int width, int height) {
        int contentHeight = 0;
        for (UiElement child : scroll.children()) {
            int childHeight = resolve(child.height(), height, 0);
            contentHeight = Math.max(contentHeight, child.y() + (childHeight > 0 ? childHeight : 0));
        }
        return Math.max(0, contentHeight - height);
    }

    private static int resolve(String value, int parent, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.equalsIgnoreCase("fill")) {
            return parent;
        }
        try {
            if (value.endsWith("%")) {
                double percent = Double.parseDouble(value.substring(0, value.length() - 1));
                return (int) Math.round(parent * percent / 100.0);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
