package com.cloudcore.client.render;

import com.cloudcore.api.document.UiElement;
import java.util.List;

/**
 * Walks the children of a {@code row}, {@code column} or {@code grid} the way the renderer
 * lays them out: rows advance x by each child's width + gap, columns advance y by its height +
 * gap, grids wrap every {@code columns} children using {@code cell} (or an even split).
 *
 * <p>A child's own {@code x}/{@code y} is an extra offset on top of the layout position, so
 * pages normally author {@code x: 0} inside a row. Keeping this in one class is what keeps
 * drawing, hit testing and focus/input boxes in agreement.
 */
public final class ContainerCursor {
    private final String type;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int gap;
    private final int columns;
    private final int cellWidth;
    private final int cellHeight;
    private int cursorX;
    private int cursorY;
    private int index;

    public ContainerCursor(UiElement container, int x, int y, int width, int height) {
        this.type = container.type();
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.gap = (int) Math.round(number(container.attributes().get("gap"), 0.0));
        this.columns = Math.max(1,
                (int) Math.round(number(container.attributes().get("columns"), 1.0)));
        this.cellWidth = cellWidth(container, width);
        this.cellHeight = cellHeight(container, height);
        this.cursorX = x;
        this.cursorY = y;
    }

    /** @return the parent origin for the next child ({@code {x, y}}) */
    public int[] next(UiElement child) {
        int childWidth = Geometry.resolve(child.width(), width, 0);
        int childHeight = Geometry.resolve(child.height(), height, 0);
        int offsetX = x;
        int offsetY = y;
        switch (type) {
            case "row" -> {
                offsetX = cursorX;
                cursorX += Math.max(0, childWidth) + gap;
            }
            case "column" -> {
                offsetY = cursorY;
                cursorY += Math.max(0, childHeight) + gap;
            }
            default -> {
                int column = index % columns;
                int row = index / columns;
                offsetX = x + column * (cellWidth + gap);
                offsetY = y + row * (cellHeight + gap);
            }
        }
        index++;
        return new int[]{offsetX, offsetY};
    }

    private static int cellWidth(UiElement element, int width) {
        List<?> cell = cell(element);
        if (cell != null) {
            return (int) Math.round(number(cell.get(0), 18.0));
        }
        return Math.max(1, width / Math.max(1,
                (int) Math.round(number(element.attributes().get("columns"), 1.0))));
    }

    private static int cellHeight(UiElement element, int height) {
        List<?> cell = cell(element);
        if (cell != null) {
            return (int) Math.round(number(cell.get(1), 18.0));
        }
        // Matches the renderer's original fallback: a grid without a cell keeps the full
        // container height per row.
        return height;
    }

    private static List<?> cell(UiElement element) {
        Object cell = element.attributes().get("cell");
        if (cell instanceof List<?> list && list.size() == 2) {
            return list;
        }
        return null;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
