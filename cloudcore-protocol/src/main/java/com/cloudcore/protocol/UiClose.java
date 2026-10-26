package com.cloudcore.protocol;

import java.util.Objects;

public record UiClose(String uiId, int reason) {

    public UiClose {
        Objects.requireNonNull(uiId, "uiId");
        if (reason < 0 || reason > 0xFF) {
            throw new IllegalArgumentException("reason out of range: " + reason);
        }
    }
}
