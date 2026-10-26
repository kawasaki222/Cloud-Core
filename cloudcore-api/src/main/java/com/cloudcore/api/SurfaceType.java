package com.cloudcore.api;

import java.util.Arrays;

/**
 * A render target that the server can customize.
 *
 * <p>Values are part of the wire protocol; do not reorder or reuse ids.
 */
public enum SurfaceType {
    CUSTOM_SCREEN(0),
    CONTAINER(1),
    PLAYER_INVENTORY(2),
    HUD(3),
    TOOLTIP(4),
    BOSS_BAR(5);

    private final int id;

    SurfaceType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static SurfaceType fromId(int id) {
        return Arrays.stream(values())
                .filter(type -> type.id == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown surface id: " + id));
    }
}
