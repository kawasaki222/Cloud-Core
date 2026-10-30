package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.inventory.Slot;

/**
 * Maps document {@code inventory_grid} elements onto the top-inventory slots of an open
 * container menu.
 *
 * <p>1.21.11 made {@code Slot#x}/{@code Slot#y} final, so a slot cannot be moved in place:
 * the menu's slot list is rebuilt with plain {@link Slot} instances that keep the original
 * container and index (clicks still travel by index, so the server menu is untouched).
 * Only slots inside the Cloud container are replaced; player-inventory slots keep their
 * vanilla positions and restrictions.
 *
 * <p>Grid attributes (docs/03-ui-dsl.md §5.9): {@code slots: [first, last]},
 * {@code origin: [x, y]}, {@code cell: [w, h]}, {@code gap: [x, y]}, {@code columns}.
 *
 * <p>Client tick / render thread only.
 */
public final class SlotLayout {
    private static final int HIDDEN = -10000;

    private SlotLayout() {
    }

    /**
     * @param topSize number of slots owned by the Cloud container; player slots after it are
     *                left untouched
     * @return indices that a grid declared
     */
    public static Set<Integer> apply(UiDocument document, List<Slot> slots, int topSize) {
        return apply(document, slots, index -> index < topSize);
    }

    /**
     * @param movable which menu indices may be rebuilt; player-inventory skins pass the
     *                main-inventory and hotbar range so armour/crafting/result slots keep
     *                their subclasses and restrictions
     */
    public static Set<Integer> apply(UiDocument document, List<Slot> slots,
            java.util.function.IntPredicate movable) {
        List<UiElement> grids = new ArrayList<>();
        collectGrids(document.elements(), grids);
        Set<Integer> declared = new LinkedHashSet<>();
        if (grids.isEmpty()) {
            return declared;
        }
        for (UiElement grid : grids) {
            int[] range = intRange(grid.attributes().get("slots"));
            if (range == null) {
                continue;
            }
            int[] origin = intPair(grid.attributes().get("origin"), new int[]{0, 0});
            int[] cell = intPair(grid.attributes().get("cell"), new int[]{18, 18});
            int[] gap = intPair(grid.attributes().get("gap"), new int[]{0, 0});
            int columns = Math.max(1, intValue(grid.attributes().get("columns"), 9));
            List<int[]> positions = positions(range, origin, cell, gap, columns,
                    grid.x(), grid.y());
            int first = Math.min(range[0], range[1]);
            int last = Math.max(range[0], range[1]);
            int index = 0;
            for (int slotIndex = first; slotIndex <= last && slotIndex < slots.size(); slotIndex++) {
                int[] position = positions.get(index++);
                if (!movable.test(slotIndex)) {
                    // Player-inventory slots keep vanilla coordinates for now: their Slot
                    // subclasses carry restrictions a plain Slot would drop.
                    continue;
                }
                move(slots, slotIndex, position[0], position[1]);
                declared.add(slotIndex);
            }
        }
        if (!declared.isEmpty()) {
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                if (!declared.contains(slotIndex) && movable.test(slotIndex)) {
                    move(slots, slotIndex, HIDDEN, HIDDEN);
                }
            }
        }
        return declared;
    }

    /** Hides every menu slot from {@code firstHidden} on; used by purpose-built containers. */
    public static void hideFrom(List<Slot> slots, int firstHidden) {
        for (int index = Math.max(0, firstHidden); index < slots.size(); index++) {
            move(slots, index, HIDDEN, HIDDEN);
        }
    }

    public static Set<Integer> gridSlots(UiDocument document) {
        List<UiElement> grids = new ArrayList<>();
        collectGrids(document.elements(), grids);
        Set<Integer> declared = new LinkedHashSet<>();
        for (UiElement grid : grids) {
            int[] range = intRange(grid.attributes().get("slots"));
            if (range == null) {
                continue;
            }
            int first = Math.min(range[0], range[1]);
            int last = Math.max(range[0], range[1]);
            for (int slot = first; slot <= last; slot++) {
                declared.add(slot);
            }
        }
        return declared;
    }

    /**
     * Pure geometry for one grid: slot N lands at column N % columns and row N / columns.
     * Package-private so the layout rules are unit-tested without a Minecraft bootstrap.
     */
    static List<int[]> positions(int[] range, int[] origin, int[] cell, int[] gap,
            int columns, int gridX, int gridY) {
        int first = Math.min(range[0], range[1]);
        int last = Math.max(range[0], range[1]);
        List<int[]> result = new ArrayList<>(Math.max(0, last - first + 1));
        for (int slot = first; slot <= last; slot++) {
            int index = slot - first;
            int column = index % columns;
            int row = index / columns;
            result.add(new int[]{
                gridX + origin[0] + column * (cell[0] + gap[0]),
                gridY + origin[1] + row * (cell[1] + gap[1])});
        }
        return result;
    }

    private static void move(List<Slot> slots, int index, int x, int y) {
        Slot original = slots.get(index);
        Slot replacement = new Slot(original.container, original.getContainerSlot(), x, y);
        replacement.index = index;
        slots.set(index, replacement);
    }

    private static void collectGrids(List<UiElement> elements, List<UiElement> out) {
        for (UiElement element : elements) {
            if ("inventory_grid".equals(element.type())) {
                out.add(element);
            }
            collectGrids(element.children(), out);
        }
    }

    private static int[] intRange(Object value) {
        if (value instanceof List<?> list && list.size() == 2) {
            return new int[]{toInt(list.get(0)), toInt(list.get(1))};
        }
        return null;
    }

    private static int[] intPair(Object value, int[] fallback) {
        if (value instanceof List<?> list && list.size() == 2) {
            return new int[]{toInt(list.get(0)), toInt(list.get(1))};
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return 0;
    }
}
