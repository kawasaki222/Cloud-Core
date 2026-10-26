package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Client hello, sent once after joining the play phase.
 */
public record Hello(
        int protoMajor,
        int protoMinor,
        String modVersion,
        int features,
        String locale,
        int guiScale,
        int scaledWidth,
        int scaledHeight) {

    public Hello {
        checkRange(protoMajor, 0, 0xFFFF, "protoMajor");
        checkRange(protoMinor, 0, 0xFFFF, "protoMinor");
        checkRange(features, 0, 0xFFFF, "features");
        checkRange(guiScale, 0, 0xFFFF, "guiScale");
        checkRange(scaledWidth, 0, 0xFFFF, "scaledWidth");
        checkRange(scaledHeight, 0, 0xFFFF, "scaledHeight");
        Objects.requireNonNull(modVersion, "modVersion");
        Objects.requireNonNull(locale, "locale");
    }

    private static void checkRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
