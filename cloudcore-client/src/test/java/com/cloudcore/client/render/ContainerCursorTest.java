package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerCursorTest {

    @Test
    void rowAdvancesByWidthAndGap() {
        UiElement row = container("row", Map.of("gap", 4));
        ContainerCursor cursor = new ContainerCursor(row, 10, 20, 200, 40);

        assertArrayEquals(new int[]{10, 20}, cursor.next(child(30, 10)));
        assertArrayEquals(new int[]{44, 20}, cursor.next(child(30, 10)));
        assertArrayEquals(new int[]{78, 20}, cursor.next(child(0, 10)));
    }

    @Test
    void columnAdvancesByHeightAndGap() {
        UiElement column = container("column", Map.of("gap", 2));
        ContainerCursor cursor = new ContainerCursor(column, 5, 5, 100, 100);

        assertArrayEquals(new int[]{5, 5}, cursor.next(child(10, 20)));
        assertArrayEquals(new int[]{5, 27}, cursor.next(child(10, 20)));
    }

    @Test
    void gridWrapsEveryColumnsWithCellAndGap() {
        UiElement grid = container("grid", Map.of("columns", 2, "gap", 3,
                "cell", List.of(20, 12)));
        ContainerCursor cursor = new ContainerCursor(grid, 0, 0, 100, 100);

        assertArrayEquals(new int[]{0, 0}, cursor.next(child(20, 12)));
        assertArrayEquals(new int[]{23, 0}, cursor.next(child(20, 12)));
        assertArrayEquals(new int[]{0, 15}, cursor.next(child(20, 12)));
        assertArrayEquals(new int[]{23, 15}, cursor.next(child(20, 12)));
    }

    @Test
    void gridWithoutCellSplitsTheWidthEvenly() {
        UiElement grid = container("grid", Map.of("columns", 4));
        ContainerCursor cursor = new ContainerCursor(grid, 0, 0, 80, 40);

        assertArrayEquals(new int[]{0, 0}, cursor.next(child(20, 10)));
        assertArrayEquals(new int[]{20, 0}, cursor.next(child(20, 10)));
    }

    private static UiElement container(String type, Map<String, Object> attributes) {
        return new UiElement("c", type, 0, 0, "200", "200", true, true, 0,
                Map.of(), List.of(), attributes);
    }

    private static UiElement child(int width, int height) {
        return new UiElement("child", "button", 0, 0, String.valueOf(width),
                String.valueOf(height), true, true, 0, Map.of(), List.of(), Map.of());
    }
}
