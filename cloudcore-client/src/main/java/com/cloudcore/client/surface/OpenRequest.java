package com.cloudcore.client.surface;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import java.util.Objects;

/**
 * Client-side view of an {@code UI_OPEN} request.
 */
public record OpenRequest(
        SurfaceType surface,
        int surfaceId,
        String uiId,
        int revision,
        int token,
        BindMode bindMode,
        RenderMode renderMode,
        int flags,
        String surfaceKey) {

    public OpenRequest {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(uiId, "uiId");
        Objects.requireNonNull(bindMode, "bindMode");
        Objects.requireNonNull(renderMode, "renderMode");
        if (surfaceId < 0 || surfaceId > 0xFF) {
            throw new IllegalArgumentException("surfaceId out of range: " + surfaceId);
        }
        surfaceKey = surfaceKey == null ? "" : surfaceKey;
    }

    public OpenRequest(SurfaceType surface, String uiId, int revision, int token,
            BindMode bindMode, RenderMode renderMode, int flags, String surfaceKey) {
        this(surface, surface.id(), uiId, revision, token, bindMode, renderMode, flags, surfaceKey);
    }
}
