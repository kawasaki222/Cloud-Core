package com.cloudcore.client.render;

/**
 * Shared geometry rules for document elements. The render list builder, the hit tester and
 * the screen's layout boxes must agree on them, otherwise a click would not land where the
 * pixel was drawn.
 */
public final class Geometry {
    private Geometry() {
    }

    /**
     * Resolves a {@code width}/{@code height} value against its parent box:
     * {@code fill} takes the parent, {@code "50%"} takes half, a number is pixels, and null or
     * an unparsable value falls back.
     */
    public static int resolve(String value, int parent, int fallback) {
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
