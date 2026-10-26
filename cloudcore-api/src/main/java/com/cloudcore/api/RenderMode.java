package com.cloudcore.api;

public enum RenderMode {
    SCREEN(0),
    OVERLAY(1),
    REPLACE_CURRENT(2),
    SURFACE_NATIVE(3);

    private final int id;

    RenderMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RenderMode fromId(int id) {
        for (RenderMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown render mode id: " + id);
    }
}
