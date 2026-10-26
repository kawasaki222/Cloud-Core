package com.cloudcore.api;

public enum BindMode {
    NONE(0),
    NEXT_CONTAINER(1),
    CURRENT_CONTAINER(2),
    PERSISTENT(3),
    /** A mode this client does not know; the raw id is not retained. */
    UNKNOWN(255);

    private final int id;

    BindMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** Forward-compatible decode: unknown ids become {@link #UNKNOWN} instead of throwing. */
    public static BindMode fromIdOrUnknown(int id) {
        for (BindMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return UNKNOWN;
    }

    public static BindMode fromId(int id) {
        for (BindMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown bind mode id: " + id);
    }
}
