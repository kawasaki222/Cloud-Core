package com.cloudcore.client.render;

import java.util.List;

/**
 * Insets from a document author's shorthand, in screen pixels.
 *
 * <p>Order is {@code [left, top, right, bottom]} — the same order {@code nine_slice} and
 * {@code fill_inset} already use, so a page author learns one convention. A bare number applies
 * to all four sides. Anything shorter than four values is read left to right and the missing
 * sides stay zero rather than silently copying a neighbour, which would make an asymmetric
 * shorthand behave unlike the four-value form.
 *
 * <p>Pure and unit-tested: the screen uses it to inset a vanilla {@code EditBox}, which draws
 * its own text flush against its left edge and cannot be told about padding any other way.
 */
public final class Insets {
    /** A field inset further than this has no room left to type in. */
    public static final int MAX = 32;

    private static final int[] NONE = {0, 0, 0, 0};

    private Insets() {
    }

    /** @return {@code [left, top, right, bottom]}, never null */
    public static int[] parse(Object value) {
        return parse(value, MAX);
    }

    /**
     * @param limit largest allowed inset per side; the caller usually passes the box's own
     *              smaller dimension so a field is never inset past its own size
     */
    public static int[] parse(Object value, int limit) {
        int cap = Math.max(0, limit);
        if (value instanceof List<?> list && !list.isEmpty()) {
            int[] sides = NONE.clone();
            for (int index = 0; index < Math.min(4, list.size()); index++) {
                sides[index] = clamp(list.get(index), cap);
            }
            return sides;
        }
        if (value instanceof Number || value instanceof String) {
            int all = clamp(value, cap);
            if (all == 0) {
                return NONE.clone();
            }
            return new int[]{all, all, all, all};
        }
        return NONE.clone();
    }

    private static int clamp(Object value, int cap) {
        double raw = value instanceof Number number ? number.doubleValue()
                : value instanceof String text ? parseDouble(text) : 0.0;
        return (int) Math.max(0, Math.min(cap, Math.round(raw)));
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }
}
