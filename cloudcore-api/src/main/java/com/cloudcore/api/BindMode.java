package com.cloudcore.api;

public enum BindMode {
    NONE(0),
    NEXT_CONTAINER(1),
    CURRENT_CONTAINER(2),
    PERSISTENT(3);

    private final int id;

    BindMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
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
