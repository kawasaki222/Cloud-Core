package com.cloudcore.protocol;

import com.cloudcore.api.SurfaceType;
import java.util.Objects;

public record UiReady(
        String uiId,
        int revision,
        int token,
        SurfaceType surface,
        int surfaceId,
        int state,
        String detail) {

    public UiReady {
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(surface, "surface");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (surfaceId < 0 || surfaceId > 0xFF) {
            throw new IllegalArgumentException("surfaceId out of range: " + surfaceId);
        }
        if (state < 0 || state > 0xFF) {
            throw new IllegalArgumentException("state out of range: " + state);
        }
        detail = detail == null ? "" : detail;
    }

    public UiReady(String uiId, int revision, int token, SurfaceType surface, int state,
            String detail) {
        this(uiId, revision, token, surface, surface.id(), state, detail);
    }
}
