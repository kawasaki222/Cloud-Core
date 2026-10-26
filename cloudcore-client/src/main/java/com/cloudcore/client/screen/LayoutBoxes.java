package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.List;

/**
 * Absolute GUI boxes for elements, mirroring {@code DocumentRenderListBuilder}: element
 * positions are relative to their parent and {@code fill}/{@code %}/pixel sizes resolve
 * against the parent box.
 *
 * <p>Render/input thread only.
 */
public final class LayoutBoxes {
    public record Box(UiElement element, int x, int y, int width, int height) {
    }

    private LayoutBoxes() {
    }

    public static List<Box> ofType(UiDocument document, int screenWidth, int screenHeight,
            String type) {
        return ofTypes(document, screenWidth, screenHeight, java.util.Set.of(type));
    }

    /** Boxes for several element types in document order (used for keyboard focus). */
    public static List<Box> ofTypes(UiDocument document, int screenWidth, int screenHeight,
            java.util.Set<String> types) {
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        int originX = (screenWidth - documentWidth) / 2;
        int originY = (screenHeight - documentHeight) / 2;
        List<Box> boxes = new ArrayList<>();
        for (UiElement element : document.elements()) {
            collect(element, originX, originY, documentWidth, documentHeight, types, boxes);
        }
        return boxes;
    }

    private static void collect(UiElement element, int parentX, int parentY,
            int parentWidth, int parentHeight, java.util.Set<String> types, List<Box> out) {
        if (!element.visible()) {
            return;
        }
        int x = parentX + element.x();
        int y = parentY + element.y();
        int width = resolve(element.width(), parentWidth, 0);
        int height = resolve(element.height(), parentHeight, 0);
        int boxWidth = width > 0 ? width : parentWidth;
        int boxHeight = height > 0 ? height : parentHeight;
        if (types.contains(element.type())) {
            out.add(new Box(element, x, y, boxWidth, boxHeight));
        }
        if (isLayout(element.type())) {
            com.cloudcore.client.render.ContainerCursor cursor =
                    new com.cloudcore.client.render.ContainerCursor(element, x, y, boxWidth,
                            boxHeight);
            for (UiElement child : element.children()) {
                int[] offset = cursor.next(child);
                collect(child, offset[0], offset[1], boxWidth, boxHeight, types, out);
            }
            return;
        }
        for (UiElement child : element.children()) {
            collect(child, x, y, boxWidth, boxHeight, types, out);
        }
    }

    private static boolean isLayout(String type) {
        return "row".equals(type) || "column".equals(type) || "grid".equals(type);
    }

    static int resolve(String value, int parent, int fallback) {
        return com.cloudcore.client.render.Geometry.resolve(value, parent, fallback);
    }
}
