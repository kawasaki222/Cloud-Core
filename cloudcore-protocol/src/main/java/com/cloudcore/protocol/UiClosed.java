package com.cloudcore.protocol;

import java.util.Objects;

public record UiClosed(String uiId, int reason) {

    public UiClosed {
        Objects.requireNonNull(uiId, "uiId");
        if (reason < 0 || reason > 0xFF) {
            throw new IllegalArgumentException("reason out of range: " + reason);
        }
    }
}
