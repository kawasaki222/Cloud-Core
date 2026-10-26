package com.cloudcore.client.render;

import java.util.Objects;

public final class RenderBackendSelector {
    private RenderBackendSelector() {
    }

    public static RenderBackend select(RenderBackend preferred, RenderBackend fallback) {
        Objects.requireNonNull(preferred, "preferred");
        Objects.requireNonNull(fallback, "fallback");
        return preferred.available() ? preferred : fallback;
    }
}
