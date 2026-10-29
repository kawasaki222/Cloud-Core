package com.cloudcore.api.page;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * When visible text should be re-resolved. Element specs override the page default; a spec
 * only controls scheduling, the actual re-resolution is a full page recompile plus a diff.
 */
public record RefreshSpec(Mode mode, int ticks) {

    public enum Mode {
        STATIC,
        INTERVAL,
        TICK,
        MANUAL,
        EVENT
    }

    public static final int DEFAULT_INTERVAL_TICKS = 20;
    public static final int MAX_TICKS = 72_000;

    public static final RefreshSpec STATIC = new RefreshSpec(Mode.STATIC, 0);

    public RefreshSpec {
        if (mode == null) {
            throw new IllegalArgumentException("mode");
        }
        if (mode == Mode.INTERVAL && (ticks < 1 || ticks > MAX_TICKS)) {
            throw new IllegalArgumentException("interval ticks out of range: " + ticks);
        }
    }

    public boolean periodic() {
        return mode == Mode.INTERVAL || mode == Mode.TICK;
    }

    /** Ticks between refreshes; {@link Mode#TICK} is every tick. */
    public int periodTicks() {
        return mode == Mode.TICK ? 1 : Math.max(1, ticks);
    }

    /** Parses {@code mode} and {@code ticks}/{@code interval_ticks}; unknown modes throw. */
    public static RefreshSpec parse(Object modeValue, Object ticksValue, String where) {
        Mode mode = Mode.STATIC;
        if (modeValue != null) {
            String name = String.valueOf(modeValue).trim().toUpperCase(Locale.ROOT);
            try {
                mode = Mode.valueOf(name);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(where + ": unknown refresh mode '" + modeValue
                        + "'");
            }
        }
        int ticks = DEFAULT_INTERVAL_TICKS;
        if (ticksValue != null && !String.valueOf(ticksValue).isBlank()) {
            try {
                if (ticksValue instanceof Number number) {
                    // intValueExact rejects 1.5 and 2^31 instead of silently truncating.
                    ticks = new BigDecimal(number.toString()).intValueExact();
                } else {
                    String text = String.valueOf(ticksValue).trim();
                    if (!text.matches("[+-]?\\d+")) {
                        throw new NumberFormatException("not an integer: " + text);
                    }
                    ticks = Integer.parseInt(text);
                }
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IllegalArgumentException(where + ": ticks must be an integer");
            }
        }
        if (mode == Mode.INTERVAL && (ticks < 1 || ticks > MAX_TICKS)) {
            throw new IllegalArgumentException(where + ": ticks must be 1.." + MAX_TICKS
                    + ", got " + ticks);
        }
        return new RefreshSpec(mode, mode == Mode.INTERVAL ? ticks : 0);
    }
}
