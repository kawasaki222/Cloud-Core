package com.cloudcore.protocol;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import java.util.Objects;

public record UiOpen(
        String uiId,
        int revision,
        SurfaceType surface,
        int surfaceId,
        BindMode bindMode,
        int token,
        RenderMode renderMode,
        int flags,
        String surfaceKey) {

    public UiOpen {
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(bindMode, "bindMode");
        Objects.requireNonNull(renderMode, "renderMode");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (surfaceId < 0 || surfaceId > 0xFF) {
            throw new IllegalArgumentException("surfaceId out of range: " + surfaceId);
        }
        if (flags < 0 || flags > 0xFF) {
            throw new IllegalArgumentException("flags out of range: " + flags);
        }
        surfaceKey = surfaceKey == null ? "" : surfaceKey;
    }

    public UiOpen(String uiId, int revision, SurfaceType surface, BindMode bindMode,
            int token, RenderMode renderMode, int flags, String surfaceKey) {
        this(uiId, revision, surface, surface.id(), bindMode, token, renderMode, flags, surfaceKey);
    }
}
