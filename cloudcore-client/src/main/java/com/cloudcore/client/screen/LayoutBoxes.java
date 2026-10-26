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
        int documentWidth = Math.max(1, Math.round(document.width() * document.scale()));
        int documentHeight = Math.max(1, Math.round(document.height() * document.scale()));
        int originX = (screenWidth - documentWidth) / 2;
        int originY = (screenHeight - documentHeight) / 2;
        List<Box> boxes = new ArrayList<>();
        for (UiElement element : document.elements()) {
            collect(element, originX, originY, documentWidth, documentHeight, type, boxes);
        }
        return boxes;
    }

    private static void collect(UiElement element, int parentX, int parentY,
            int parentWidth, int parentHeight, String type, List<Box> out) {
        if (!element.visible()) {
            return;
        }
        int x = parentX + element.x();
        int y = parentY + element.y();
        int width = resolve(element.width(), parentWidth, 0);
        int height = resolve(element.height(), parentHeight, 0);
        int boxWidth = width > 0 ? width : parentWidth;
        int boxHeight = height > 0 ? height : parentHeight;
        if (type.equals(element.type())) {
            out.add(new Box(element, x, y, boxWidth, boxHeight));
        }
        for (UiElement child : element.children()) {
            collect(child, x, y, boxWidth, boxHeight, type, out);
        }
    }

    static int resolve(String value, int parent, int fallback) {
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
