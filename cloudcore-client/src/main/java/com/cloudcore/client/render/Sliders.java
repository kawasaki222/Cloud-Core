package com.cloudcore.client.render;

/**
 * Slider value math shared by the renderer and the input handler, so a drawn knob and a
 * reported amount can never disagree about where the value sits.
 *
 * <p>The document stores {@code value} in {@code [min, max]} (default {@code 0..1}); the
 * client always draws and reports a {@code 0..1} fraction, and {@code step} optionally snaps
 * the value to a fixed grid before it is reported.
 */
public final class Sliders {
    private Sliders() {
    }

    /** Makes a sane range when a document gives {@code max <= min}. */
    public static double maxOrDefault(double min, double max) {
        return max > min ? max : min + 1.0;
    }

    /** Reads {@code step}; anything not strictly positive means continuous. */
    public static double stepOrDefault(double step) {
        return step > 0.0 ? step : 0.0;
    }

    /** Converts a document value to the {@code 0..1} fraction the client draws. */
    public static double fraction(double value, double min, double max) {
        if (!(max > min)) {
            return 0.0;
        }
        return clamp((value - min) / (max - min));
    }

    /** Converts a {@code 0..1} fraction back to a (optionally snapped) document value. */
    public static double value(double fraction, double min, double max, double step) {
        if (!(max > min)) {
            return min;
        }
        double raw = min + clamp(fraction) * (max - min);
        if (step <= 0.0) {
            return raw;
        }
        double snapped = min + Math.round((raw - min) / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    public static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
