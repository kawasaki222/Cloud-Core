package com.cloudcore.client.render;

/**
 * Parses the document colour syntax {@code #AARRGGBB} / {@code #RRGGBB} into ARGB.
 * Anything else (theme tokens, names, malformed hex) falls back, so a bad colour can never
 * produce a transparent hole in the UI.
 */
public final class Colors {
    private Colors() {
    }

    public static int parse(Object value, int fallback) {
        if (value instanceof String string && !string.isEmpty()) {
            String hex = string.charAt(0) == '#' ? string.substring(1) : string;
            try {
                if (hex.length() == 8) {
                    return (int) Long.parseLong(hex, 16);
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
